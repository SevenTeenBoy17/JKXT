#!/usr/bin/env bash
# Verify 1.1 "班主任不监本班": import teachers→classes→classrooms→exams (with bindings),
# schedule, then assert no homeroom teacher (TBZ0c) is assigned to their own class's room (R10c).
set -u
BASE=http://localhost:18180/api/v1
PG="docker exec jkxt-private-postgres psql -U jkxt -d jkxt_private"
DIR="04-班主任避本班"
JAR=$(mktemp); PJ() { python -c "import sys,json;d=json.load(sys.stdin);print($1)" 2>/dev/null; }
R=$(curl -s -c "$JAR" -X POST $BASE/auth/captcha); CID=$(echo "$R"|PJ "d['captchaId']"); DBG=$(echo "$R"|PJ "d.get('debugAnswer','')")
curl -s -c "$JAR" -b "$JAR" -X POST $BASE/auth/login -H 'Content-Type: application/json' \
  -d "{\"username\":\"tenant.test\",\"password\":\"Tenant001!!!\",\"captchaId\":\"$CID\",\"captcha\":\"$DBG\"}" >/dev/null

echo "reset org data..."
$PG -c "TRUNCATE schedule_assignment, schedule_revision, schedule_run, exam_class, exam_room, exam, exam_task, teacher_subject, school_class_subject, teacher, classroom, school_class RESTART IDENTITY CASCADE;" >/dev/null 2>&1

TID=$(curl -s -b "$JAR" -X POST $BASE/exam-tasks -H 'Content-Type: application/json' -d '{"name":"verify-homeroom","source":"manual"}' | PJ "d['id']")
echo "taskId=$TID"
commit() { local kind=$1 src=$2 extra=${3:-}; local t=$(mktemp --suffix=.xlsx); cp "$DIR/$src" "$t"
  local resp=$(curl -s -b "$JAR" -X POST "$BASE/import/$kind/commit$extra" -F "file=@$t"); rm -f "$t"
  echo "  commit $kind -> $(echo "$resp" | python -c "import sys,json;d=json.load(sys.stdin);print('inserted=%s updated=%s skipped=%s warnings=%d'%(d.get('inserted'),d.get('updated'),d.get('skipped'),len(d.get('warnings') or [])))" 2>/dev/null || echo "$resp" | head -c 200)"
}
commit teachers   "01-教师安排-第4次-班主任避本班.xlsx"
commit classes    "02-班级信息-第4次-班主任避本班.xlsx"
commit classrooms "03-考场信息-第4次-班主任避本班.xlsx"
commit exams      "04-考试安排-第4次-班主任避本班.xlsx" "?taskId=$TID"

echo "=== DB check: exam_class rows + homeroom bindings ==="
$PG -t -c "SELECT 'exam_class='||count(*) FROM exam_class; SELECT 'classes_with_homeroom='||count(*) FROM school_class WHERE homeroom_teacher_id IS NOT NULL; SELECT 'teachers_homeroom='||count(*) FROM teacher WHERE is_homeroom;" 2>&1 | tr -d '\r' | grep -E "="

JID=$(curl -s -b "$JAR" -X POST "$BASE/exam-tasks/$TID/schedule-jobs" -H 'Content-Type: application/json' -d '{}' | PJ "d.get('jobId')")
ST=""; for i in $(seq 1 40); do ST=$(curl -s -b "$JAR" "$BASE/schedule-jobs/$JID" | PJ "d.get('status')"); [ "$ST" = succeeded ] || [ "$ST" = failed ] || [ "$ST" = infeasible ] && break; sleep 2; done
echo "schedule status=$ST"

curl -s -b "$JAR" "$BASE/exam-tasks/$TID/assignments" > /tmp/hr_assign.json 2>/dev/null
curl -s -b "$JAR" "$BASE/exam-tasks/$TID/assignments" | python -c "
import sys,json
d=json.load(sys.stdin); rows=d.get('rows') or []
# room R10c hosts class G1-0c whose homeroom is TBZ0c
viol=[]
for a in rows:
    rc=str(a.get('roomCode','')); c=rc[-1] if rc.startswith('R10') else None
    if c:
        bz='TBZ%02d'%int(c)
        if a.get('teacherNo')==bz:
            viol.append((a.get('teacherNo'),a.get('teacherName'),rc,a.get('subjectName')))
print('assignments=%d'%len(rows))
print('班主任监本班 violations=%d'%len(viol))
for v in viol[:10]: print('  VIOLATION:',json.dumps(v,ensure_ascii=True))
print('RESULT:', 'PASS (no homeroom teacher on own class)' if not viol and rows else ('FAIL' if viol else 'NO-ASSIGNMENTS'))
"
rm -f "$JAR"
