#!/usr/bin/env bash
# End-to-end walkthrough against a running SchemaSync.
#
#   docker compose up -d db && ./scripts/seed.sh 100000
#   cd backend && mvn spring-boot:run
#   ./scripts/e2e.sh
#
# Exercises the full loop -- import, branch, edit, diff -- and asserts the things that
# actually matter, most importantly that a rename stays a rename.
set -euo pipefail

API="${API:-http://localhost:8080/api}"
export PGPASSWORD="${PGPASSWORD:-schemasync}"
PSQL=(psql -h "${PGHOST:-localhost}" -U "${PGUSER:-schemasync}" -d "${PGDATABASE:-schemasync}" -tAq)

pass() { printf '  \033[32mPASS\033[0m  %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; FAILURES=$((FAILURES+1)); }
step() { printf '\n\033[1m%s\033[0m\n' "$1"; }
FAILURES=0

jqp() { python3 -c "import sys,json;$1" ; }

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# A test that depends on leftover state is a test that passes once. Reset to a known schema and
# a clean control plane every run, so this is repeatable and a reviewer gets the same output.
if [ "${E2E_SKIP_RESET:-0}" != "1" ]; then
  step "0. Reset to a known state"
  # /api/health answers while the app may still be seeding its demo data on first boot. Resetting
  # underneath that races the seeder, so wait (bounded) for startup to finish first.
  for _ in $(seq 1 30); do
    [ "$(curl -fsS "$API/projects" 2>/dev/null | python3 -c 'import sys,json;print(len(json.load(sys.stdin)))' 2>/dev/null)" != "0" ] && break
    sleep 1
  done
  "${PSQL[@]}" -c "SELECT 'DROP SCHEMA IF EXISTS ' || quote_ident(nspname) || ' CASCADE;'
                   FROM pg_namespace WHERE nspname LIKE 'br\\_%';" \
    | psql -h "${PGHOST:-localhost}" -U "${PGUSER:-schemasync}" -d "${PGDATABASE:-schemasync}" \
           -q -v ON_ERROR_STOP=1 2>/dev/null
  "${PSQL[@]}" -c "TRUNCATE sv.project CASCADE;" >/dev/null 2>&1
  "$SCRIPT_DIR/seed.sh" "${E2E_ROWS:-100000}" >/dev/null 2>&1
  echo "        reseeded main with ${E2E_ROWS:-100000} orders; control plane cleared"
  echo "        restarting the API so it re-imports the demo project..."
  # The demo project is imported at startup, so the API has to see the fresh schema.
  curl -fsS -X POST "$API/admin/reimport" >/dev/null 2>&1 || true
  sleep 1
fi

step "1. Project imported from the live schema"
PROJECT=$(curl -fsS "$API/projects")
PID=$(echo "$PROJECT" | jqp "print(json.load(sys.stdin)[0]['id'])")
[ -n "$PID" ] && pass "project id $PID" || fail "no project"

MAIN_ROWS=$("${PSQL[@]}" -c "SELECT count(*) FROM main.orders;")
echo "        main.orders holds $MAIN_ROWS rows"

step "2. Branching is O(schema), not O(data)"
START=$(python3 -c 'import time;print(time.time())')
BRANCH=$(curl -fsS -X POST "$API/projects/$PID/branches" -H 'Content-Type: application/json' \
  -d '{"from":"main","name":"e2e_currency","author":"e2e"}')
ELAPSED=$(python3 -c "import time;print(int((time.time()-$START)*1000))")
BID=$(echo "$BRANCH" | jqp "print(json.load(sys.stdin)['id'])")
SCHEMA=$(echo "$BRANCH" | jqp "print(json.load(sys.stdin)['pgSchema'])")
echo "        created $SCHEMA in ${ELAPSED}ms"
[ "$ELAPSED" -lt 5000 ] && pass "branch created in ${ELAPSED}ms despite $MAIN_ROWS rows in main" \
                        || fail "branch creation took ${ELAPSED}ms"

BRANCH_ROWS=$("${PSQL[@]}" -c "SELECT count(*) FROM $SCHEMA.orders;")
[ "$BRANCH_ROWS" -lt "$MAIN_ROWS" ] \
  && pass "branch sampled $BRANCH_ROWS rows rather than copying $MAIN_ROWS" \
  || fail "branch copied $BRANCH_ROWS rows"

ORPHANS=$("${PSQL[@]}" -c "SELECT count(*) FROM $SCHEMA.orders o
  LEFT JOIN $SCHEMA.customers c ON c.id=o.customer_id WHERE c.id IS NULL;")
[ "$ORPHANS" = "0" ] && pass "sampled child rows all have their parent present" \
                     || fail "$ORPHANS orders reference a missing customer"

step "3. Apply the nine-verb palette"
IDS=$(curl -fsS "$API/branches/$BID/schema" | python3 -c "
import sys,json
s=json.load(sys.stdin)
o=[t for t in s['tables'] if t['name']=='orders'][0]
c={x['name']:x['id'] for x in o['columns']}
print(o['id'],c['amount'],c['status'],c['notes'],c['placed_at'])")
read -r ORDERS AMOUNT STATUS NOTES PLACED <<< "$IDS"

COMMIT=$(curl -fsS -X POST "$API/branches/$BID/operations" -H 'Content-Type: application/json' -d "{
  \"message\":\"Multi-currency support\",\"author\":\"e2e\",
  \"operations\":[
    {\"op\":\"RENAME_COLUMN\",\"tableId\":\"$ORDERS\",\"columnId\":\"$AMOUNT\",\"newName\":\"amount_cents\"},
    {\"op\":\"ADD_COLUMN\",\"tableId\":\"$ORDERS\",\"name\":\"currency\",\"type\":\"varchar(3)\",\"nullable\":false,\"defaultExpr\":\"'USD'\"},
    {\"op\":\"CHANGE_COLUMN_TYPE\",\"tableId\":\"$ORDERS\",\"columnId\":\"$STATUS\",\"newType\":\"text\"},
    {\"op\":\"DROP_COLUMN\",\"tableId\":\"$ORDERS\",\"columnId\":\"$NOTES\"},
    {\"op\":\"ADD_INDEX\",\"tableId\":\"$ORDERS\",\"name\":\"orders_currency_idx\",\"columnIds\":[\"$PLACED\"],\"unique\":false,\"method\":\"btree\"}
  ]}")
echo "$COMMIT" | jqp "print('        commit:', json.load(sys.stdin)['message'])"
pass "5 operations applied in one commit"

step "4. The real branch database actually changed"
COLS=$("${PSQL[@]}" -c "SELECT string_agg(column_name,',' ORDER BY ordinal_position)
  FROM information_schema.columns WHERE table_schema='$SCHEMA' AND table_name='orders';")
echo "        $COLS"
case "$COLS" in *amount_cents*) pass "amount_cents exists in Postgres";; *) fail "rename not applied";; esac
case "$COLS" in *notes*) fail "notes still present";; *) pass "notes dropped";; esac
case "$COLS" in *currency*) pass "currency added";; *) fail "currency missing";; esac

STATUS_TYPE=$("${PSQL[@]}" -c "SELECT data_type FROM information_schema.columns
  WHERE table_schema='$SCHEMA' AND table_name='orders' AND column_name='status';")
[ "$STATUS_TYPE" = "text" ] && pass "status retyped to text" || fail "status is $STATUS_TYPE"

IDX=$("${PSQL[@]}" -c "SELECT count(*) FROM pg_indexes
  WHERE schemaname='$SCHEMA' AND indexname='orders_currency_idx';")
[ "$IDX" = "1" ] && pass "index created" || fail "index missing"

step "5. main is untouched"
MAIN_COLS=$("${PSQL[@]}" -c "SELECT string_agg(column_name,',' ORDER BY ordinal_position)
  FROM information_schema.columns WHERE table_schema='main' AND table_name='orders';")
echo "        $MAIN_COLS"
case "$MAIN_COLS" in *amount,*) pass "main still has 'amount'";; *) fail "main was modified";; esac
case "$MAIN_COLS" in *notes*) pass "main still has 'notes'";; *) fail "main lost notes";; esac

step "6. The diff -- the claim this whole product rests on"
DIFF=$(curl -fsS "$API/branches/$BID/diff")
echo "$DIFF" | python3 -c "
import sys,json
d=json.load(sys.stdin)
for c in d['changes']:
    print(f\"        [{'DESTRUCTIVE' if c['destructive'] else 'safe':11}] {c['kind']:28} {c['description']}\")"

RENAMED=$(echo "$DIFF" | jqp "print(sum(1 for c in json.load(sys.stdin)['changes'] if c['kind']=='COLUMN_RENAMED'))")
DROPPED=$(echo "$DIFF" | jqp "print(sum(1 for c in json.load(sys.stdin)['changes'] if c['kind']=='COLUMN_DROPPED'))")
DRIFTED=$(echo "$DIFF" | jqp "print(json.load(sys.stdin)['drifted'])")

[ "$RENAMED" = "1" ] && pass "rename reported as COLUMN_RENAMED" || fail "expected 1 rename, got $RENAMED"
# One drop, for 'notes'. If the rename had been missed it would show as a second drop -- which on
# a real table means destroying a column of data instead of a free catalog update.
[ "$DROPPED" = "1" ] && pass "exactly 1 drop (notes) -- the rename did NOT become a drop+add" \
                     || fail "expected 1 drop, got $DROPPED"
[ "$DRIFTED" = "False" ] && pass "no drift: recorded snapshot matches the live database" \
                         || fail "branch reports drift after its own changes"

step "7. Drift detection catches out-of-band DDL"
"${PSQL[@]}" -c "ALTER TABLE $SCHEMA.orders ADD COLUMN snuck_in text;" >/dev/null
DRIFTED2=$(curl -fsS "$API/branches/$BID/diff" | jqp "print(json.load(sys.stdin)['drifted'])")
[ "$DRIFTED2" = "True" ] && pass "raw DDL outside SchemaSync is detected as drift" \
                         || fail "drift went undetected"
"${PSQL[@]}" -c "ALTER TABLE $SCHEMA.orders DROP COLUMN snuck_in;" >/dev/null
DRIFTED3=$(curl -fsS "$API/branches/$BID/diff" | jqp "print(json.load(sys.stdin)['drifted'])")
[ "$DRIFTED3" = "False" ] && pass "drift clears once the schema matches again" \
                          || fail "branch stuck in DRIFTED"

step "8. Guard rails"
# Note: no -f here. These assert on the ERROR BODY, and curl -f throws the body away.
ERR=$(curl -sS -X POST "$API/branches/$BID/operations" -H 'Content-Type: application/json' \
  -d "{\"operations\":[{\"op\":\"ADD_COLUMN\",\"tableId\":\"$ORDERS\",\"name\":\"__sv_evil\",\"type\":\"text\",\"nullable\":true}]}")
case "$ERR" in *reserved*) pass "reserved __sv_ prefix rejected";; *) fail "reserved prefix allowed: $ERR";; esac

MAIN_BID=$(curl -fsS "$API/projects/$PID/branches" | jqp "
import sys;print([b['id'] for b in json.load(sys.stdin) if b['name']=='main'][0])")
ERR2=$(curl -sS -X POST "$API/branches/$MAIN_BID/operations" -H 'Content-Type: application/json' \
  -d "{\"operations\":[{\"op\":\"DROP_TABLE\",\"tableId\":\"$ORDERS\"}]}")
case "$ERR2" in *"cannot be edited directly"*) pass "main rejects direct edits";; *) fail "main was editable: $ERR2";; esac

step "9. Merge back into main, applied to the real database"
PREVIEW=$(curl -fsS -X POST "$API/branches/$BID/merge/preview" -H 'Content-Type: application/json' -d '{}')
echo "$PREVIEW" | python3 -c "
import sys,json
p=json.load(sys.stdin)
print(f\"        {p['source']} -> {p['target']}  mode={p['mode']}  canApply={p['canApply']}\")
for s in p['steps']:
    print(f\"        {s['seq']}. [{s['verdict']:7}] blocks={s['blocks']:17} {s['description'][:60]}\")"

MODE=$(echo "$PREVIEW" | jqp "print(json.load(sys.stdin)['mode'])")
CAN=$(echo "$PREVIEW" | jqp "print(json.load(sys.stdin)['canApply'])")
[ "$CAN" = "True" ] && pass "merge is applicable with no outstanding conflicts" || fail "cannot apply: $PREVIEW"

# The rename must appear in the plan as a RENAME and never as a DROP. This is the assertion the
# whole identity model exists to satisfy: as a drop plus an add it would destroy a column of data.
RENAME_STEPS=$(echo "$PREVIEW" | jqp "
import sys;p=json.load(sys.stdin)
print(sum(1 for s in p['steps'] if s['sql'] and 'RENAME COLUMN' in s['sql']))")
DROP_STEPS=$(echo "$PREVIEW" | jqp "
import sys;p=json.load(sys.stdin)
print(sum(1 for s in p['steps'] if s['sql'] and 'DROP COLUMN' in s['sql']))")
[ "$RENAME_STEPS" = "1" ] && pass "plan renames the column" || fail "expected 1 RENAME, got $RENAME_STEPS"
[ "$DROP_STEPS" = "1" ] && pass "plan has exactly 1 DROP (the column actually dropped), not 2" \
                        || fail "expected 1 DROP COLUMN, got $DROP_STEPS"

MAIN_BEFORE=$("${PSQL[@]}" -c "SELECT string_agg(column_name,',' ORDER BY ordinal_position)
  FROM information_schema.columns WHERE table_schema='main' AND table_name='orders';")

RUNID=$(curl -fsS -X POST "$API/branches/$BID/merge/apply" -H 'Content-Type: application/json' \
  -d '{"author":"e2e"}' | jqp "print(json.load(sys.stdin)['runId'])")
for _ in $(seq 1 180); do
  RSTATUS=$(curl -fsS "$API/runs/$RUNID" | jqp "print(json.load(sys.stdin)['status'])")
  case "$RSTATUS" in SUCCEEDED|FAILED) break;; esac
  sleep 1
done
[ "$RSTATUS" = "SUCCEEDED" ] && pass "migration applied to main" || {
  fail "migration $RSTATUS"
  curl -fsS "$API/runs/$RUNID" | python3 -c "
import sys,json
r=json.load(sys.stdin)
print('        ', r.get('error_message'))
for s in r['steps']:
    if s.get('error'): print('        step', s['seq'], s['error'][:200])"
}

step "10. main really changed, and the data survived the rename"
MAIN_AFTER=$("${PSQL[@]}" -c "SELECT string_agg(column_name,',' ORDER BY ordinal_position)
  FROM information_schema.columns WHERE table_schema='main' AND table_name='orders';")
echo "        before: $MAIN_BEFORE"
echo "        after : $MAIN_AFTER"
case "$MAIN_AFTER" in *amount_cents*) pass "main now has amount_cents";; *) fail "rename did not reach main";; esac
case "$MAIN_AFTER" in *currency*) pass "main now has currency";; *) fail "new column missing from main";; esac

# The point of tracking identity: the renamed column still holds its values.
NONZERO=$("${PSQL[@]}" -c "SELECT count(*) FROM main.orders WHERE amount_cents IS NOT NULL;")
[ "$NONZERO" -gt 0 ] && pass "$NONZERO rows still have their amount after the rename" \
                     || fail "renamed column is empty -- data was lost"

# Every statement that actually ran is recorded, and none of them dropped the renamed column.
BADDROP=$("${PSQL[@]}" -c "SELECT count(*) FROM sv.migration_step
  WHERE run_id='$RUNID' AND sql_text ILIKE '%DROP COLUMN \"amount\"%';")
[ "$BADDROP" = "0" ] && pass "no executed statement dropped the renamed column" \
                     || fail "the rename was executed as a drop"

step "Cleanup"
curl -fsS -X DELETE "$API/branches/$BID" -o /dev/null -w '' || true
GONE=$("${PSQL[@]}" -c "SELECT count(*) FROM pg_namespace WHERE nspname='$SCHEMA';")
[ "$GONE" = "0" ] && pass "branch schema dropped" || fail "schema $SCHEMA left behind"

echo ""
if [ "$FAILURES" -eq 0 ]; then
  printf '\033[32mAll end-to-end checks passed.\033[0m\n'
else
  printf '\033[31m%s check(s) failed.\033[0m\n' "$FAILURES"; exit 1
fi
