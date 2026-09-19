# SchemaSync

Version control for your Postgres schema: branch it, change it, diff it and merge it back. Every
change is applied to a real Postgres database.

## Repo layout

```
backend/            Spring Boot API (Java 21, Maven)
  src/main/java/com/schemasync/
    core/           diff, three-way merge and migration planning (pure Java, no DB)
    branch/         creating branches and detecting drift
    merge/, exec/   running merges online, lock-safe DDL, batched backfill
    store/          control-plane persistence (Postgres schema `sv`)
    api/            REST endpoints
web/                React UI (Vite + TypeScript)
scripts/            seed.sh (demo data), e2e.sh (end-to-end checks against the real API)
docker-compose.yml  Postgres + API + UI for local runs
Dockerfile          single production image (UI built into the API jar)
render.yaml         Render deployment config
```

## Run locally with Docker

You only need Docker (or OrbStack).

```bash
docker compose up --build
```

Open http://localhost:5173. On first start the API creates and seeds a demo e-commerce schema
(200k orders), so you can create a branch straight away.

If the UI says the API did not respond, check that nothing else is listening on port 5173
(`lsof -nP -iTCP:5173 -sTCP:LISTEN`), then rebuild with `docker compose up --build --force-recreate`.

## Run locally without Docker for the app

You need Java 21, Maven, Node 22 and `psql`. Postgres still runs in Docker.

```bash
docker compose up -d db              # Postgres on localhost:5432
./scripts/seed.sh 100000             # demo data (≈15MB); omit the number for 3.5M rows (≈500MB)

cd backend && mvn spring-boot:run    # API on localhost:8080
cd web && npm install && npm run dev # UI on localhost:5173, proxies /api to the API
```

Run the API and the UI in separate terminals.

## More

- [DEPLOY.md](DEPLOY.md): how to deploy it on Render with Neon Postgres
- [decisions.md](decisions.md): the design decisions, and the alternatives I rejected
