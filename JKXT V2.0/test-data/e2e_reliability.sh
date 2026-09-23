#!/usr/bin/env bash
# Reliability harness: hammer the solver N times on each dataset's fixed task and count
# succeeded vs failed/UNKNOWN. With the portfolio+retry fix this should be 100% succeeded.
# Usage: bash e2e_reliability.sh [N]   (default N=8)
set -u
BASE=http://localhost:18180/api/v1
N=${1:-8}
JAR=$(mktemp); PJ() { python -c "import sys,json;d=json.load(sys.stdin);print($1)" 2>/dev/null; }
R=$(curl -s -c "$JAR" -X POST $BASE/auth/captcha); CID=$(echo "$R"|PJ "d['captchaId']"); DBG=$(echo "$R"|PJ "d.get('debugAnswer','')")
curl -s -c "$JAR" -b "$JAR" -X POST $BASE/auth/login -H 'Content-Type: application/json' \
  -d "{\"username\":\"tenant.test\",\"password\":\"Tenant001!!!\",\"captchaId\":\"$CID\",\"captcha\":\"$DBG\"}" >/dev/null
up() { local tid=$1 kind=$2 src=$3; local t=$(mktemp --suffix=.xlsx); cp "$src" "$t"; curl -s -b "$JAR" -o /dev/null -X POST "$BASE/exam-tasks/$tid/uploads?kind=$kind" -F "file=@$t"; rm -f "$t"; }

GRAND_OK=0; GRAND_BAD=0
hammer() { # label teacherFile roomFile examFile
  local lbl=$1 tf=$2 cf=$3 ef=$4
  local TID=$(curl -s -b "$JAR" -X POST $BASE/exam-tasks -H 'Content-Type: application/json' -d "{\"name\":\"rel-$lbl\",\"source\":\"manual\"}" | PJ "d['id']")
  up "$TID" teacher "$tf"; up "$TID" classroom "$cf"; up "$TID" exam "$ef"
  local ok=0 bad=0 ms=""
  for run in $(seq 1 $N); do
    local JID=$(curl -s -b "$JAR" -X POST "$BASE/exam-tasks/$TID/schedule-jobs" -H 'Content-Type: application/json' -d '{}' | PJ "d.get('jobId')")
    local S ST; for i in $(seq 1 40); do S=$(curl -s -b "$JAR" "$BASE/schedule-jobs/$JID"); ST=$(echo "$S"|PJ "d.get('status')"); [ "$ST" = succeeded ] || [ "$ST" = failed ] || [ "$ST" = infeasible ] && break; sleep 1; done
    ms="$ms $(echo "$S"|PJ "d.get('stats',{}).get('solveMillis')")"
    [ "$ST" = succeeded ] && ok=$((ok+1)) || bad=$((bad+1))
  done
  echo "  [$lbl] succeeded=$ok/$N  failed=$bad/$N  solveMillis:$ms"
  GRAND_OK=$((GRAND_OK+ok)); GRAND_BAD=$((GRAND_BAD+bad))
}

echo "Reliability hammer (N=$N per dataset):"
hammer iter1 "import-backend-columns/01-教师安排-后端列-天津市实验中学.xlsx" "import-backend-columns/02-考场信息-后端列-天津市实验中学.xlsx" "import-backend-columns/03-考试安排-后端列-天津市实验中学.xlsx"
hammer iter2 "02-多年级期末-完全中学/01-教师安排-第2次-多年级期末.xlsx" "02-多年级期末-完全中学/02-考场信息-第2次-多年级期末.xlsx" "02-多年级期末-完全中学/03-考试安排-第2次-多年级期末.xlsx"
hammer iter3 "03-高三大型模考-单年级/01-教师安排-第3次-高三模考.xlsx" "03-高三大型模考-单年级/02-考场信息-第3次-高三模考.xlsx" "03-高三大型模考-单年级/03-考试安排-第3次-高三模考.xlsx"
echo "==== RELIABILITY TOTAL: succeeded=$GRAND_OK failed=$GRAND_BAD ===="
rm -f "$JAR"
