#!/usr/bin/env bash
# Capture clean per-dataset one-click scheduling results for data-quality analysis.
# For each dataset: reset org data (isolation) -> import 3 files -> validate -> schedule ->
# poll -> assignments -> export xlsx; dump raw responses, then post-process to a clean JSON.
set -u
BASE=http://localhost:18180/api/v1
PG="docker exec jkxt-private-postgres psql -U jkxt -d jkxt_private"
OUT=results; mkdir -p "$OUT"
JAR=$(mktemp)
PJ() { python -c "import sys,json;d=json.load(sys.stdin);print($1)" 2>/dev/null; }

# login (ADMIN)
R=$(curl -s -c "$JAR" -X POST $BASE/auth/captcha)
CID=$(echo "$R"|PJ "d['captchaId']"); DBG=$(echo "$R"|PJ "d.get('debugAnswer','')")
curl -s -c "$JAR" -b "$JAR" -X POST $BASE/auth/login -H 'Content-Type: application/json' \
  -d "{\"username\":\"tenant.test\",\"password\":\"Tenant001!!!\",\"captchaId\":\"$CID\",\"captcha\":\"$DBG\"}" >/dev/null

RESET_SQL="TRUNCATE schedule_assignment, schedule_revision, schedule_run, exam_class, exam_room, exam, exam_task, teacher_subject, school_class_subject, teacher, classroom, school_class RESTART IDENTITY CASCADE;"

run_ds() { # label dir teacherFile roomFile examFile
  local lbl=$1 dir=$2 tf=$3 cf=$4 ef=$5
  echo "########## $lbl ##########"
  echo "  reset org data..."; $PG -c "$RESET_SQL" >/dev/null 2>&1
  local TID=$(curl -s -b "$JAR" -X POST $BASE/exam-tasks -H 'Content-Type: application/json' -d "{\"name\":\"cap-$lbl\",\"source\":\"manual\"}" | PJ "d['id']")
  echo "  taskId=$TID"
  for kc in "teacher|$tf" "classroom|$cf" "exam|$ef"; do
    local k=${kc%%|*}; local f=${kc#*|}; local t=$(mktemp --suffix=.xlsx); cp "$dir/$f" "$t"
    curl -s -b "$JAR" -o /dev/null -X POST "$BASE/exam-tasks/$TID/uploads?kind=$k" -F "file=@$t"; rm -f "$t"
  done
  curl -s -b "$JAR" -X POST "$BASE/exam-tasks/$TID/imports/validate" > "$OUT/${lbl}_validate.json"
  local JID=$(curl -s -b "$JAR" -X POST "$BASE/exam-tasks/$TID/schedule-jobs" -H 'Content-Type: application/json' -d '{}' | PJ "d.get('jobId')")
  local ST=""; for i in $(seq 1 60); do
    curl -s -b "$JAR" "$BASE/schedule-jobs/$JID" > "$OUT/${lbl}_job.json"
    ST=$(PJ "d.get('status')" < "$OUT/${lbl}_job.json")
    [ "$ST" = succeeded ] || [ "$ST" = failed ] || [ "$ST" = infeasible ] && break; sleep 2
  done
  echo "  job status=$ST"
  curl -s -b "$JAR" "$BASE/exam-tasks/$TID/assignments" > "$OUT/${lbl}_assign.json"
  curl -s -b "$JAR" -o "$OUT/${lbl}_export.xlsx" "$BASE/exam-tasks/$TID/export?format=xlsx"
  echo "  captured -> $OUT/${lbl}_{validate,job,assign}.json + _export.xlsx"
  # copy inputs alongside for the analyzer
  cp "$dir/$tf" "$OUT/${lbl}_in_teacher.xlsx"; cp "$dir/$cf" "$OUT/${lbl}_in_room.xlsx"; cp "$dir/$ef" "$OUT/${lbl}_in_exam.xlsx"
}

run_ds iter1 "import-backend-columns" "01-教师安排-后端列-天津市实验中学.xlsx" "02-考场信息-后端列-天津市实验中学.xlsx" "03-考试安排-后端列-天津市实验中学.xlsx"
run_ds iter2 "02-多年级期末-完全中学" "01-教师安排-第2次-多年级期末.xlsx" "02-考场信息-第2次-多年级期末.xlsx" "03-考试安排-第2次-多年级期末.xlsx"
run_ds iter3 "03-高三大型模考-单年级" "01-教师安排-第3次-高三模考.xlsx" "02-考场信息-第3次-高三模考.xlsx" "03-考试安排-第3次-高三模考.xlsx"
rm -f "$JAR"
echo "DONE capture."
