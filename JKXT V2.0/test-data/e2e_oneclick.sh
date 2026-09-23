#!/usr/bin/env bash
# End-to-end one-click exam-scheduling smoke test against the private stack.
# Usage: bash e2e_oneclick.sh <user> <pass> <teacherXlsx> <classroomXlsx> <examXlsx> <label>
set -u
BASE=http://localhost:18180/api/v1
JAR=$(mktemp)
PJ() { python -c "import sys,json;d=json.load(sys.stdin);print($1)" 2>/dev/null; }

U="$1"; P="$2"; TF="$3"; CF="$4"; EF="$5"; LABEL="$6"

# --- login ---
R=$(curl -s -c "$JAR" -X POST $BASE/auth/captcha)
CID=$(echo "$R" | PJ "d['captchaId']")
DBG=$(echo "$R" | PJ "d.get('debugAnswer','')")
L=$(curl -s -c "$JAR" -b "$JAR" -X POST $BASE/auth/login -H 'Content-Type: application/json' \
  -d "{\"username\":\"$U\",\"password\":\"$P\",\"captchaId\":\"$CID\",\"captcha\":\"$DBG\"}")
UNAME=$(echo "$L" | PJ "d.get('user',{}).get('username') or d.get('code')")
echo "[$LABEL] login=$UNAME role=$(echo "$L" | PJ "d.get('user',{}).get('role')")"
if [ "$UNAME" != "$U" ]; then echo "[$LABEL] LOGIN FAILED: $L"; exit 1; fi

# --- create task ---
TID=$(curl -s -b "$JAR" -X POST $BASE/exam-tasks -H 'Content-Type: application/json' \
  -d "{\"name\":\"e2e-$LABEL\",\"source\":\"manual\"}" | PJ "d.get('id')")
echo "[$LABEL] taskId=$TID"
if [ -z "$TID" ] || [ "$TID" = "None" ]; then echo "[$LABEL] CREATE TASK FAILED"; exit 1; fi

# --- uploads (copy to mktemp so curl gets a Windows-readable path) ---
upload() {
  local kind="$1" src="$2"; local tmp=$(mktemp --suffix=.xlsx); cp "$src" "$tmp"
  local resp=$(curl -s -b "$JAR" -X POST "$BASE/exam-tasks/$TID/uploads?kind=$kind" -F "file=@$tmp")
  local sev=$(echo "$resp" | PJ "d.get('report',{}).get('severity')")
  echo "[$LABEL] upload $kind -> sev=$sev counts=$(echo "$resp" | PJ "d.get('report',{}).get('counts')")"
  rm -f "$tmp"
}
upload teacher "$TF"
upload classroom "$CF"
upload exam "$EF"

# --- validate ---
V=$(curl -s -b "$JAR" -X POST "$BASE/exam-tasks/$TID/imports/validate")
echo "[$LABEL] validate sev=$(echo "$V" | PJ "d.get('report',{}).get('severity')") overview=$(echo "$V" | PJ "d.get('report',{}).get('overview')")"

# --- schedule job ---
JOB=$(curl -s -b "$JAR" -X POST "$BASE/exam-tasks/$TID/schedule-jobs" -H 'Content-Type: application/json' -d '{}')
JID=$(echo "$JOB" | PJ "d.get('jobId') or d.get('id')")
echo "[$LABEL] jobId=$JID job=$(echo "$JOB" | python -c "import sys,json;d=json.load(sys.stdin);print({k:d.get(k) for k in ('jobId','id','status','code','message')})" 2>/dev/null)"
if [ -z "$JID" ] || [ "$JID" = "None" ]; then echo "[$LABEL] SCHEDULE JOB FAILED: $JOB"; exit 1; fi

# --- poll ---
STATUS=""; for i in $(seq 1 60); do
  S=$(curl -s -b "$JAR" "$BASE/schedule-jobs/$JID")
  STATUS=$(echo "$S" | PJ "d.get('status')")
  if [ "$STATUS" = "succeeded" ] || [ "$STATUS" = "failed" ] || [ "$STATUS" = "infeasible" ]; then
    echo "[$LABEL] poll#$i status=$STATUS detail=$(echo "$S" | python -c "import sys,json;d=json.load(sys.stdin);print({k:d.get(k) for k in ('status','assignmentRows','stats','message','error')})" 2>/dev/null)"
    break
  fi
  sleep 2
done

# --- assignments + export ---
A=$(curl -s -b "$JAR" "$BASE/exam-tasks/$TID/assignments")
NA=$(echo "$A" | python -c "import sys,json;d=json.load(sys.stdin);print(len(d.get('rows') or d.get('assignments') or d.get('items') or (d if isinstance(d,list) else [])))" 2>/dev/null)
echo "[$LABEL] assignments=$NA"
XOUT=$(mktemp --suffix=.xlsx)
HTTP=$(curl -s -b "$JAR" -o "$XOUT" -w "%{http_code}" "$BASE/exam-tasks/$TID/export?format=xlsx")
SZ=$(wc -c < "$XOUT")
echo "[$LABEL] export http=$HTTP bytes=$SZ"
rm -f "$XOUT" "$JAR"
echo "[$LABEL] DONE status=$STATUS assignments=$NA"
