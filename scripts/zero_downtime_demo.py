#!/usr/bin/env python3
"""
Proves the zero-downtime claim instead of asserting it.

Runs a continuous read/write workload against main.orders while a migration that REWRITES that
table runs underneath it, and reports the error count and latency distribution.

    pip3 install psycopg2-binary requests
    ./scripts/zero_downtime_demo.py                 # the online path (SchemaSync)
    ./scripts/zero_downtime_demo.py --naive         # the same change, issued directly

The --naive run is the control. It performs the identical schema change with a single
ALTER TABLE, which is what a migration tool that does not think about locks would emit. Watching
the two side by side is the entire argument for the online path.
"""
import argparse, json, os, random, statistics, sys, threading, time

try:
    import psycopg2, requests
except ImportError:
    sys.exit("pip3 install psycopg2-binary requests")

DSN = os.environ.get("SCHEMASYNC_DSN",
                     "host=localhost user=schemasync password=schemasync dbname=schemasync")
API = os.environ.get("SCHEMASYNC_API", "http://localhost:8080/api")


class Workload(threading.Thread):
    """One client doing what an application does: point reads and point updates."""

    def __init__(self, max_id):
        super().__init__(daemon=True)
        self.max_id = max_id
        self.stop = threading.Event()
        self.ok = 0
        self.errors = 0
        self.error_samples = []
        self.latencies = []

    def run(self):
        conn = psycopg2.connect(DSN)
        conn.autocommit = True
        while not self.stop.is_set():
            started = time.time()
            try:
                with conn.cursor() as cur:
                    i = random.randint(1, self.max_id)
                    cur.execute("SELECT id, status FROM main.orders WHERE id = %s", (i,))
                    cur.fetchone()
                    cur.execute("UPDATE main.orders SET status = 'paid' WHERE id = %s", (i,))
                self.ok += 1
            except Exception as e:
                self.errors += 1
                if len(self.error_samples) < 3:
                    self.error_samples.append(str(e).strip().split("\n")[0])
                try:
                    conn.close()
                    conn = psycopg2.connect(DSN)
                    conn.autocommit = True
                except Exception:
                    time.sleep(0.1)
            self.latencies.append((time.time() - started) * 1000)
            time.sleep(0.005)
        try:
            conn.close()
        except Exception:
            pass


def percentile(values, p):
    if not values:
        return 0.0
    ordered = sorted(values)
    return round(ordered[min(len(ordered) - 1, int(len(ordered) * p))], 1)


def report(label, workers, seconds):
    ok = sum(w.ok for w in workers)
    errors = sum(w.errors for w in workers)
    latencies = [x for w in workers for x in w.latencies]
    samples = [s for w in workers for s in w.error_samples][:3]

    print(f"\n  {label}")
    print(f"    requests succeeded : {ok:,}  ({ok / max(seconds, 1):.0f}/s)")
    print(f"    requests FAILED    : {errors:,}")
    for s in samples:
        print(f"      ! {s}")
    print(f"    latency p50        : {percentile(latencies, 0.50)} ms")
    print(f"    latency p99        : {percentile(latencies, 0.99)} ms")
    print(f"    latency max        : {percentile(latencies, 1.0)} ms")
    return ok, errors, percentile(latencies, 1.0)


def max_order_id():
    conn = psycopg2.connect(DSN)
    with conn.cursor() as cur:
        cur.execute("SELECT max(id) FROM main.orders")
        n = cur.fetchone()[0] or 1
    conn.close()
    return n


def current_amount_type():
    conn = psycopg2.connect(DSN)
    with conn.cursor() as cur:
        cur.execute("""SELECT data_type FROM information_schema.columns
                       WHERE table_schema='main' AND table_name='orders'
                         AND column_name IN ('amount','amount_cents')
                       LIMIT 1""")
        row = cur.fetchone()
    conn.close()
    return row[0] if row else None


def run_naive(column, target_type):
    """The control: the obvious statement, with no lock discipline at all."""
    conn = psycopg2.connect(DSN)
    conn.autocommit = True
    started = time.time()
    with conn.cursor() as cur:
        cur.execute(f'ALTER TABLE main.orders ALTER COLUMN "{column}" TYPE {target_type}')
    conn.close()
    return time.time() - started


def run_online(branch_name, column_id, table_id, target_type, project_id):
    """The SchemaSync path: branch, change, merge, and let the planner decide how."""
    branch = requests.post(f"{API}/projects/{project_id}/branches",
                           json={"from": "main", "name": branch_name, "author": "demo"}).json()
    bid = branch["id"]

    schema = requests.get(f"{API}/branches/{bid}/schema").json()
    orders = next(t for t in schema["tables"] if t["name"] == "orders")
    col = next(c for c in orders["columns"] if c["id"] == column_id or c["name"] in ("amount", "amount_cents"))

    requests.post(f"{API}/branches/{bid}/operations", json={
        "message": f"Retype {col['name']} to {target_type}",
        "author": "demo",
        "operations": [{"op": "CHANGE_COLUMN_TYPE", "tableId": orders["id"],
                        "columnId": col["id"], "newType": target_type}]}).raise_for_status()

    preview = requests.post(f"{API}/branches/{bid}/merge/preview", json={}).json()
    print(f"    plan mode: {preview['mode']}, {len(preview['steps'])} steps")
    for s in preview["steps"]:
        print(f"      {s['seq']}. [{s['verdict']:7}] blocks={s['blocks']:17} {s['description'][:64]}")

    started = time.time()
    applied = requests.post(f"{API}/branches/{bid}/merge/apply", json={"author": "demo"}).json()
    if "runId" not in applied:
        raise SystemExit(f"merge refused: {applied}")

    while True:
        run = requests.get(f"{API}/runs/{applied['runId']}").json()
        if run["status"] in ("SUCCEEDED", "FAILED"):
            break
        time.sleep(0.5)
    elapsed = time.time() - started

    if run["status"] != "SUCCEEDED":
        print(f"    migration FAILED: {run.get('error_message')}")
        for s in run["steps"]:
            if s.get("error"):
                print(f"      step {s['seq']}: {s['error'][:200]}")
    return elapsed, run


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--naive", action="store_true",
                    help="issue the change directly, with no lock discipline (the control)")
    ap.add_argument("--clients", type=int, default=4)
    ap.add_argument("--type", default="numeric(14,2)")
    args = ap.parse_args()

    max_id = max_order_id()
    before = current_amount_type()
    print(f"main.orders: up to id {max_id:,}, amount column is currently {before}")

    workers = [Workload(max_id) for _ in range(args.clients)]
    for w in workers:
        w.start()
    print(f"\nWorkload running: {args.clients} clients doing SELECT + UPDATE on main.orders")
    time.sleep(3)

    if args.naive:
        print("\n>>> NAIVE: a single ALTER TABLE, exactly what an unaware tool would emit")
        column = "amount_cents" if before is None else (
            "amount_cents" if "amount_cents" in str(before) else "amount")
        conn = psycopg2.connect(DSN)
        with conn.cursor() as cur:
            cur.execute("""SELECT column_name FROM information_schema.columns
                           WHERE table_schema='main' AND table_name='orders'
                             AND column_name IN ('amount','amount_cents') LIMIT 1""")
            column = cur.fetchone()[0]
        conn.close()
        started = time.time()
        elapsed = run_naive(column, args.type)
        print(f"    ALTER TABLE took {elapsed:.1f}s")
    else:
        print("\n>>> SCHEMASYNC: branch, retype, merge -- planner picks the strategy")
        projects = requests.get(f"{API}/projects").json()
        elapsed, run = run_online(f"demo_{int(time.time())}", None, None, args.type, projects[0]["id"])
        print(f"    migration took {elapsed:.1f}s, status {run['status']}")
        started = time.time()

    time.sleep(3)
    for w in workers:
        w.stop.set()
    for w in workers:
        w.join(timeout=5)

    total_seconds = sum(len(w.latencies) for w in workers) and 1
    ok, errors, worst = report("Application traffic during the migration:", workers, 12)

    print(f"\n  amount column is now {current_amount_type()}")
    # "No errors" is not the same as "no impact". A request that waited five seconds behind a
    # lock did not fail -- it just made someone's page hang. Report both, or the naive run looks
    # deceptively fine.
    if errors:
        print(f"\n  \033[31m{errors} requests failed.\033[0m")
    elif worst > 2000:
        print(f"\n  No request failed, but the worst waited \033[31m{worst:.0f}ms\033[0m.")
        print("  That is the lock queue: every query arriving during the ALTER sat behind it.")
    else:
        print(f"\n  \033[32mZero failures, worst request {worst:.0f}ms.\033[0m "
              "Reads and writes continued throughout.")


if __name__ == "__main__":
    main()
