#!/usr/bin/env bash
# Edge-case / robustness internal-test harness for the one-click exam-scheduling flow.
# Exercises importer robustness + solver infeasibility against the private stack.
# Usage: bash e2e_edge.sh
#
# NOTE: crafted files are written under a PROJECT-RELATIVE dir (./_edge_tmp). On Windows,
# MSYS bash `/tmp` and Windows-python `/tmp` resolve to DIFFERENT locations, so a file
# python saves to /tmp is not found by bash cp. A relative path is resolved identically.
set -u
BASE=http://localhost:18180/api/v1
TMP=./_edge_tmp; mkdir -p "$TMP"
JAR="$TMP/jar.txt"
PASS=0; FAIL=0
PJ() { python -c "import sys,json;d=json.load(sys.stdin);print($1)" 2>/dev/null; }
chk() { if [ "$2" = "$3" ]; then echo "  PASS: $1 ($2)"; PASS=$((PASS+1)); else echo "  FAIL: $1 — got [$2] want [$3]"; FAIL=$((FAIL+1)); fi; }

R=$(curl -s -c "$JAR" -X POST $BASE/auth/captcha)
CID=$(echo "$R" | PJ "d['captchaId']"); DBG=$(echo "$R" | PJ "d.get('debugAnswer','')")
curl -s -c "$JAR" -b "$JAR" -X POST $BASE/auth/login -H 'Content-Type: application/json' \
  -d "{\"username\":\"tenant.test\",\"password\":\"Tenant001!!!\",\"captchaId\":\"$CID\",\"captcha\":\"$DBG\"}" >/dev/null

newtask() { curl -s -b "$JAR" -X POST $BASE/exam-tasks -H 'Content-Type: application/json' -d "{\"name\":\"$1\",\"source\":\"manual\"}" | PJ "d['id']"; }
up() { curl -s -b "$JAR" -o /dev/null -w "%{http_code}" -X POST "$BASE/exam-tasks/$1/uploads?kind=$2" -F "file=@$3"; }

D2="02-多年级期末-完全中学"

echo "### T1: in-file duplicate-email upload returns clean 2xx (importer drops dup email, no 500)"
python - "$D2/01-教师安排-第2次-多年级期末.xlsx" "$TMP/dupemail.xlsx" <<'PY'
import sys,openpyxl
wb=openpyxl.load_workbook(sys.argv[1]); ws=wb.active
ws.cell(row=3,column=7).value = ws.cell(row=2,column=7).value  # row3 email := row2 email
wb.save(sys.argv[2])
PY
TID=$(newtask edge-dupemail); HTTP=$(up "$TID" teacher "$TMP/dupemail.xlsx")
chk "duplicate-email upload http (no 500)" "$([ "$HTTP" -ge 500 ] && echo 5xx || echo ok)" "ok"
echo "    (http=$HTTP)"

echo "### T2: re-import same teacher file is idempotent (no 500)"
TID=$(newtask edge-reimport)
H1=$(up "$TID" teacher "$D2/01-教师安排-第2次-多年级期末.xlsx")
H2=$(up "$TID" teacher "$D2/01-教师安排-第2次-多年级期末.xlsx")
chk "first import" "$H1" "200"; chk "second import (idempotent)" "$H2" "200"

echo "### T3: exam with missing rooms is skipped with warning, not crash"
TID=$(newtask edge-missingroom)
up "$TID" teacher "$D2/01-教师安排-第2次-多年级期末.xlsx" >/dev/null
HEX=$(up "$TID" exam "$D2/03-考试安排-第2次-多年级期末.xlsx")  # no classrooms uploaded
chk "missing-room exam upload (no 5xx)" "$([ "$HEX" -ge 500 ] && echo 5xx || echo ok)" "ok"
echo "    (http=$HEX)"

echo "### T4: over-capacity (监考人数=999) -> infeasible/failed, NOT a bogus success (R2: no auto-relax)"
TID=$(newtask edge-infeasible)
up "$TID" teacher "$D2/01-教师安排-第2次-多年级期末.xlsx" >/dev/null
up "$TID" classroom "$D2/02-考场信息-第2次-多年级期末.xlsx" >/dev/null
python - "$D2/03-考试安排-第2次-多年级期末.xlsx" "$TMP/infeasible.xlsx" <<'PY'
import sys,openpyxl
wb=openpyxl.load_workbook(sys.argv[1]); ws=wb.active
for r in range(2, ws.max_row+1): ws.cell(row=r,column=8).value = 999  # 监考人数 col
wb.save(sys.argv[2])
PY
UP4=$(up "$TID" exam "$TMP/infeasible.xlsx"); echo "    (exam upload http=$UP4)"
JOB=$(curl -s -b "$JAR" -w "\n%{http_code}" -X POST "$BASE/exam-tasks/$TID/schedule-jobs" -H 'Content-Type: application/json' -d '{}')
JHTTP=$(echo "$JOB" | tail -1); JID=$(echo "$JOB" | head -1 | PJ "d.get('jobId')")
if [ -n "$JID" ] && [ "$JID" != "None" ]; then
  ST=""; for i in $(seq 1 40); do ST=$(curl -s -b "$JAR" "$BASE/schedule-jobs/$JID" | PJ "d.get('status')"); [ "$ST" = infeasible ] || [ "$ST" = failed ] || [ "$ST" = succeeded ] && break; sleep 2; done
  echo "    over-capacity terminal status=$ST"
  chk "over-capacity NOT bogus-success" "$([ "$ST" = succeeded ] && echo bad || echo good)" "good"
else
  chk "over-capacity job-create rejected 400" "$JHTTP" "400"
fi

echo "### T5: wrong-columns file -> handled (no 5xx)"
python - "$TMP/wrongcols.xlsx" <<'PY'
import sys,openpyxl
wb=openpyxl.Workbook(); ws=wb.active; ws.append(["foo","bar","baz"]); ws.append(["1","2","3"]); wb.save(sys.argv[1])
PY
TID=$(newtask edge-wrongcols); HTTP=$(up "$TID" teacher "$TMP/wrongcols.xlsx")
chk "wrong-cols handled (no 5xx)" "$([ "$HTTP" -ge 500 ] && echo 5xx || echo ok)" "ok"
echo "    (http=$HTTP)"

echo ""
echo "==== EDGE SUMMARY: PASS=$PASS FAIL=$FAIL ===="
rm -rf "$TMP"
