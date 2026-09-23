#!/usr/bin/env bash
# K12 全学段批量端到端执行器（阶段 A）——对 jkxt-private 真实栈跑全链路并抓真值。
#
# 用法:
#   bash k12_run_all.sh <user> <pass> [学段过滤]      # 学段过滤: 小学|初中|高中，省略=全部
# 例:
#   bash k12_run_all.sh tenant.test 'Tenant001!!!' 小学
#
# 每份数据集的流水线（前后端真通，不止交互）：
#   登录 → 建任务 → 上传3表(记 severity/counts) → imports/validate(ready)
#   → 启动排考 → 轮询终态 → GET assignments → publish → export xlsx+pdf(magic bytes)
#   → DB 真值比对(schedule_run/schedule_assignment 行数) → DELETE 任务 → 按工号前缀清理教师池
#
# 关键：**教师池清理**（决策 B）——求解器教师池=全 org 在职教师，不清理会让后续数据集
# 看到前面所有教师，池膨胀导致指标失真、场景（如"教师紧缺"/"预期无解"）失效。
set -u
BASE=http://localhost:18180/api/v1
PG="docker exec jkxt-private-postgres psql -U jkxt -d jkxt_private -tc"
ROOT="$(cd "$(dirname "$0")" && pwd)"
K12="$ROOT/k12"
OUTDIR="$ROOT/results/k12"
mkdir -p "$OUTDIR"

U="${1:?用法: k12_run_all.sh <user> <pass> [学段]}"
P="${2:?缺少密码}"
FILTER="${3:-}"

PJ() { python -c "import sys,json;d=json.load(sys.stdin);print($1)" 2>/dev/null; }
now() { date '+%H:%M:%S'; }

# ---------- 登录（每份数据集复用同一会话） ----------
JAR=$(mktemp)
R=$(curl -s -c "$JAR" -X POST $BASE/auth/captcha)
CID=$(echo "$R" | PJ "d['captchaId']"); DBG=$(echo "$R" | PJ "d.get('debugAnswer','')")
L=$(curl -s -c "$JAR" -b "$JAR" -X POST $BASE/auth/login -H 'Content-Type: application/json' \
  -d "{\"username\":\"$U\",\"password\":\"$P\",\"captchaId\":\"$CID\",\"captcha\":\"$DBG\"}")
if [ "$(echo "$L" | PJ "d.get('user',{}).get('username')")" != "$U" ]; then
  echo "LOGIN FAILED: $L"; exit 1
fi
echo "[$(now)] 登录成功: $U ($(echo "$L" | PJ "d.get('user',{}).get('role')"))"

# 汇总为**追加**模式：分学段多次调用时不得覆盖上一批结果（首跑才写表头）。
SUMMARY="$OUTDIR/summary.tsv"
if [ ! -s "$SUMMARY" ]; then
  echo -e "数据集\t前缀\t终态\tAPI分配数\tDB分配数\t一致\t导出xlsx\t导出pdf\t发布\t耗时秒" > "$SUMMARY"
fi

run_one() {
  local dir="$1"                       # 形如 k12/小学/P01-...
  local label="$(basename "$dir")"
  local stage="$(basename "$(dirname "$dir")")"
  local prefix="$(echo "$label" | grep -oE '^[PJS][0-9]{2}')"
  local t0=$(date +%s)
  echo ""
  echo "───────────────────────────────────────────────"
  echo "[$(now)] ▶ $stage / $label  (前缀=$prefix)"

  # 建任务
  # 注意：Git Bash 下 curl -d 内联中文会破坏 UTF-8 → 后端 400 MALFORMED_REQUEST。
  # 故任务名只用 ASCII 前缀（k12-P02），中文场景名不进 JSON 请求体（已在 summary/manifest 中留存）。
  local body_file=$(mktemp)
  printf '{"name":"k12-%s","source":"k12test"}' "$prefix" > "$body_file"
  local craw=$(curl -s -b "$JAR" -X POST $BASE/exam-tasks \
    -H 'Content-Type: application/json; charset=utf-8' --data-binary "@$body_file")
  rm -f "$body_file"
  local TID=$(echo "$craw" | PJ "d.get('id')")
  if [ -z "$TID" ] || [ "$TID" = "None" ]; then echo "  ✗ 建任务失败: $(echo "$craw" | head -c 200)"; return 1; fi

  # 上传三表
  local sev_all=""
  for kv in "teacher:01-教师安排.xlsx" "classroom:02-考场信息.xlsx" "exam:03-考试安排.xlsx"; do
    local kind="${kv%%:*}" file="${kv#*:}"
    local tmp=$(mktemp --suffix=.xlsx); cp "$dir/$file" "$tmp"
    local resp=$(curl -s -m 60 -b "$JAR" -X POST "$BASE/exam-tasks/$TID/uploads" -F "kind=$kind" -F "file=@$tmp")
    local sev=$(echo "$resp" | PJ "d.get('report',{}).get('severity')")
    local cnt=$(echo "$resp" | PJ "json.dumps(d.get('report',{}).get('counts'),ensure_ascii=False)")
    echo "  上传 $kind: severity=$sev counts=$cnt"
    sev_all="$sev_all$kind=$sev "
    rm -f "$tmp"
  done

  # 就绪校验（真实前端必走此步，跳过会触发 commit 可见性竞态）
  # 注：响应体是 ValidationReport（severity/counts/overview），"ready" 是 exam_task.status 的取值
  # （ExamTaskService:206 在 error==0 时置 ready），故就绪与否要看 severity/error 计数与任务状态。
  local V=$(curl -s -m 30 -b "$JAR" -X POST "$BASE/exam-tasks/$TID/imports/validate")
  local vsev=$(echo "$V" | PJ "d.get('severity')")
  local verr=$(echo "$V" | PJ "(d.get('counts') or {}).get('error')")
  local vov=$(echo "$V" | PJ "json.dumps(d.get('overview'),ensure_ascii=False)")
  local tstatus=$(curl -s -m 15 -b "$JAR" "$BASE/exam-tasks/$TID" | PJ "d.get('status')")
  echo "  校验 severity=$vsev error=$verr 任务状态=$tstatus overview=$vov"

  # 启动排考
  local J=$(curl -s -m 30 -b "$JAR" -X POST "$BASE/exam-tasks/$TID/schedule-jobs")
  local JID=$(echo "$J" | PJ "d.get('jobId')")
  local ERRC=$(echo "$J" | PJ "d.get('code')")
  local status="" napi=0
  if [ -z "$JID" ] || [ "$JID" = "None" ]; then
    status="REJECTED:${ERRC:-unknown}"
    echo "  排考被拒: $status  ← 负向/无解数据集这是**预期**结果"
  else
    for i in $(seq 1 90); do
      local JS=$(curl -s -m 15 -b "$JAR" "$BASE/schedule-jobs/$JID")
      status=$(echo "$JS" | PJ "d.get('status')")
      case "$status" in succeeded|failed|infeasible) napi=$(echo "$JS" | PJ "len(d.get('assignments') or [])"); break;; esac
      sleep 2
    done
    echo "  排考终态=$status  API分配数=$napi"
  fi

  # DB 真值
  local ndb=$($PG "select count(*) from schedule_assignment sa join schedule_run sr on sr.id=sa.schedule_run_id where sr.exam_task_id='$TID';" 2>/dev/null | tr -d ' \n')
  ndb="${ndb:-0}"
  local consist="✓"; [ "$napi" != "$ndb" ] && consist="✗API=$napi/DB=$ndb"
  echo "  DB分配数=$ndb 一致性=$consist"

  # 抓取原始结果供**方案质量审计**（k12_audit.py 逐条校验冲突/人数/负载，而非只看 succeeded）
  if [ -n "${JID:-}" ] && [ "$JID" != "None" ]; then
    mkdir -p "$OUTDIR/raw"
    curl -s -m 20 -b "$JAR" "$BASE/schedule-jobs/$JID" -o "$OUTDIR/raw/${prefix}_job.json"
    python - "$dir" "$OUTDIR/raw/${prefix}_job.json" "$OUTDIR/raw/${prefix}_combined.json" "$label" <<'PYEOF'
import json, sys, io, os
try:
    import openpyxl
except ImportError:
    sys.exit(0)
d_dir, job_f, out_f, label = sys.argv[1:5]
job = json.load(io.open(job_f, encoding='utf-8'))
def rows(fn):
    p = os.path.join(d_dir, fn)
    if not os.path.exists(p): return []
    ws = openpyxl.load_workbook(p).active
    vals = list(ws.values)
    if not vals: return []
    hdr = vals[0]
    return [dict(zip(hdr, r)) for r in vals[1:]]
tea = [{"teacherNo": r.get("工号"), "name": r.get("姓名"), "grade": r.get("年级"),
        "subjects": r.get("任教学科")} for r in rows("01-教师安排.xlsx")]
rms = [{"code": r.get("考场编号"), "capacity": r.get("考试容量")} for r in rows("02-考场信息.xlsx")]
exs = [{"subjectName": r.get("考试科目"), "grade": r.get("年级"), "examDate": str(r.get("考试日期")),
        "startTime": str(r.get("开始时间")), "endTime": str(r.get("结束时间")),
        "roomCode": r.get("考场编号"), "supervisors": r.get("监考人数")} for r in rows("03-考试安排.xlsx")]
json.dump({"label": label, "status": job.get("status"), "assignments": job.get("assignments") or [],
           "job_stats": job.get("stats") or {}, "input_teachers": tea, "input_rooms": rms,
           "input_exams": exs}, io.open(out_f, "w", encoding="utf-8"), ensure_ascii=False)
PYEOF
  fi

  # 发布 + 导出（仅成功场景）
  local pub="-" xlsx="-" pdf="-"
  if [ "$status" = "succeeded" ]; then
    pub=$(curl -s -m 30 -b "$JAR" -X POST "$BASE/tasks/$TID/publish" -o /dev/null -w "%{http_code}")
    local fx=$(mktemp); curl -s -m 60 -b "$JAR" "$BASE/exam-tasks/$TID/export?format=xlsx" -o "$fx"
    xlsx="$(head -c2 "$fx" | grep -q 'PK' && echo "PK/$(wc -c <"$fx")B" || echo "BAD")"
    local fp=$(mktemp); curl -s -m 60 -b "$JAR" "$BASE/exam-tasks/$TID/export?format=pdf" -o "$fp"
    pdf="$(head -c4 "$fp" | grep -q '%PDF' && echo "PDF/$(wc -c <"$fp")B" || echo "BAD")"
    echo "  发布=$pub 导出xlsx=$xlsx pdf=$pdf"
    rm -f "$fx" "$fp"
  fi

  # 清理任务 + 教师池（决策 B：按工号前缀，保证下个数据集池纯净）
  curl -s -m 30 -b "$JAR" -X DELETE "$BASE/exam-tasks/$TID" -o /dev/null
  if [ -n "$prefix" ]; then
    $PG "delete from teacher_subject where teacher_id in (select id from teacher where teacher_no like '${prefix}%'); delete from teacher where teacher_no like '${prefix}%';" >/dev/null 2>&1
    local left=$($PG "select count(*) from teacher where teacher_no like '${prefix}%';" 2>/dev/null | tr -d ' \n')
    echo "  清理: 任务已删, 教师前缀 ${prefix}* 剩余=$left"
  fi

  local t1=$(date +%s)
  echo -e "$stage/$label\t$prefix\t$status\t$napi\t$ndb\t$consist\t$xlsx\t$pdf\t$pub\t$((t1-t0))" >> "$SUMMARY"
}

# ---------- 主循环 ----------
for stage in 小学 初中 高中; do
  [ -n "$FILTER" ] && [ "$FILTER" != "$stage" ] && continue
  [ -d "$K12/$stage" ] || continue
  for d in "$K12/$stage"/*/; do
    run_one "${d%/}"
  done
done

echo ""
echo "═══════════════ 汇总 ═══════════════"
column -t -s $'\t' "$SUMMARY" 2>/dev/null || cat "$SUMMARY"
echo ""
echo "汇总文件: $SUMMARY"
rm -f "$JAR"
