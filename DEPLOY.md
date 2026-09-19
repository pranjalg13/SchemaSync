# Deploying SchemaSync for free

Production is **one container** (the root `Dockerfile`): Spring Boot serving both the API and the
built React app from one origin, so there is no proxy and no CORS to configure. It needs one
Postgres, which it reads from `DATABASE_URL` in the usual `postgres://user:pass@host/db` form.
On an empty database it creates and seeds the demo schema by itself.

Free-tier terms below were checked in September 2026. They change often, so check them again.

---

## Recommended: Render for the app, Neon for the database

Both free, and Neon's free database does not expire (Render's free Postgres is deleted after 30 days).

**1. Push the repo to GitHub.** Render deploys from a Git repository.

**2. Create the database on [Neon](https://neon.com).** New project → copy the connection string.

> **Use the direct connection string — the one *without* `-pooler` in the host name.**
> Neon's pooled endpoint is PgBouncer in transaction mode, which does not support session `SET`
> or session advisory locks. SchemaSync's executor needs both (`CREATE INDEX CONCURRENTLY` cannot
> run inside a transaction, so its timeouts must be session-level, and one migration at a time is
> enforced with an advisory lock). On the pooled URL, migrations would behave unpredictably.

**3. Create the web service on [Render](https://render.com).** New → Web Service → your repo.
- Runtime: **Docker** (it picks up the root `Dockerfile`)
- Instance type: **Free**
- Health check path: `/api/health`
- Environment variables:
  - `DATABASE_URL` = the Neon **direct** connection string
  - `SCHEMASYNC_DEMO_SEED_ORDERS` = `200000` (≈30MB, well inside Neon's 0.5GB)

**4. Open the URL** Render gives you. The first start seeds the demo (a few seconds), then you can
branch straight away.

### Or: everything on Render, in one click

`render.yaml` is a Blueprint: **New → Blueprint → this repo** creates the web service and a free
Render Postgres and wires `DATABASE_URL` for you. Simplest path, but the database is **deleted 30
days after creation**, so it suits a short review window rather than a long-lived link.

---

## What "free" costs you

| Limit | Effect on SchemaSync |
| --- | --- |
| Render free: **512MB RAM, 0.1 CPU** | The image caps the heap at 70% of the container and uses the serial GC. Measured: **~180MB** in use under a 512MB limit. 0.1 CPU makes startup noticeably slower than on a laptop. |
| Render free: **sleeps after 15 min idle**, ~1 min to wake | The first visitor after a quiet spell waits about a minute. A reviewer should open the link, then wait. |
| Neon free: **0.5GB storage**, scales to zero after 5 min | Hence 200k demo rows, not millions. Waking the database adds a moment to that first request. |
| Render free Postgres: **1GB, deleted after 30 days**, no backups | Fine for a review window, not beyond. |
| In-process migrations | A migration runs inside the web service. If a free instance sleeps or restarts mid-migration, it stops. The cursor and step status are saved, but automatic resume is not built, so keep hosted demos to small tables. |

**5GB does not fit on any free managed Postgres.** For the full-scale demo, use a VM instead.

---

## Full-scale option: a free VM

Oracle Cloud's Always Free tier includes an ARM VM (**2 OCPU / 12GB RAM** since June 2026; it was
4 / 24 before) and **200GB of block storage** — enough to hold and migrate a 5GB table. Every image
used here is multi-arch, so ARM needs no changes.

```bash
# on the VM, with Docker installed
git clone <your repo> && cd SchemaSync
docker network create ss
docker run -d --name db --network ss -e POSTGRES_PASSWORD=change-me \
  -v pgdata:/var/lib/postgresql/data postgres:16-alpine
docker build -t schemasync .
docker run -d --name app --network ss -p 80:8080 \
  -e DATABASE_URL='postgres://postgres:change-me@db:5432/postgres' \
  -e SCHEMASYNC_DEMO_SEED_ORDERS=35000000 \
  -e JAVA_TOOL_OPTIONS='-XX:MaxRAMPercentage=25 -XX:+UseSerialGC' \
  schemasync
docker logs -f app      # first boot seeds ~5GB server-side; wait for "Seeded demo schema"
```

Seeding only happens when the demo schema does not exist yet, so this is a fresh-database setting;
it never touches existing data. Open port 80 in the VM's security list. `JAVA_TOOL_OPTIONS`
replaces the image's 512MB tuning, which is too tight for a 12GB machine.

---

## After any deploy

```bash
curl https://<your-app>/api/health          # {"status":"ok",...}
```

Then, in the UI, create a branch, retype `orders.amount` to `numeric(14,2)`, and open **Merge**:
on the 200k-row demo table the plan should read **online**, with 9 steps and a batched backfill.
