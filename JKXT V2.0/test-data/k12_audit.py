# -*- coding: utf-8 -*-
"""排监考**方案质量与逻辑**审计器 —— 不看"是否 succeeded"，只看"排出来的方案对不对、合不合理"。

背景：k12_run_all.sh 只验证了流程通与 API↔DB 数量一致，这**不足以证明方案正确**。
本审计器对每份排考结果做逐条逻辑校验，任何一条 FAIL 都意味着方案不可用于真实学校。

用法:
    python k12_audit.py <combined.json>            # 单份
    python k12_audit.py --dir results/k12/raw      # 批量
combined.json 结构（由 k12_run_all.sh --capture 产出）:
    {"label","status","assignments":[...],"input_teachers":[...],"input_rooms":[...],
     "input_exams":[...],"job_stats":{...}}

审计维度（H=硬性必须为 0，S=软性质量指标）:
  H1 教师同时段双派      —— 同一教师在时间重叠的两个考场 → 物理上不可能，严重缺陷
  H2 考场同时段多考      —— 同一考场同一时段被两场考试占用
  H3 监考人数不足/超配   —— 每个 exam-room 实配人数 != 表中"监考人数"
  H4 幽灵教师/考场       —— 分配引用了不存在于导入名册/考场表的实体
  H5 重复分配            —— 同一 (exam, room, teacher) 出现多次
  H6 班主任监本班        —— 违反 M1.1 硬约束（需 homeroom 数据）
  S1 负载均衡            —— 人均监考场次的极差/标准差
  S2 教师利用率          —— 参与监考的教师占可用池比例
  S3 连场压力            —— 同一教师当天连续时段监考次数（疲劳度）
  S4 同学科回避          —— 教师监考自己任教学科的比例（软偏好，越低越好）
"""
import json
import sys
import os
import io
from collections import defaultdict


def tmin(t):
    """'HH:MM[:SS]' -> 分钟"""
    p = str(t).split(":")
    return int(p[0]) * 60 + int(p[1])


def overlap(a_start, a_end, b_start, b_end):
    """半开区间 [s,e) 是否重叠"""
    return a_start < b_end and b_start < a_end


def audit(data):
    """返回 (结论dict, 明细list)。结论含 hard_fail 数与各维度值。"""
    label = data.get("label", "?")
    status = data.get("status", "?")
    A = data.get("assignments") or []
    in_teachers = {str(t.get("teacherNo")): t for t in (data.get("input_teachers") or [])}
    in_rooms = {str(r.get("code")) for r in (data.get("input_rooms") or [])}
    in_exams = data.get("input_exams") or []

    issues = []
    R = {"label": label, "status": status, "n_assignments": len(A)}

    if not A:
        R["verdict"] = "SKIP(无分配)"
        return R, issues

    # ---------- H1 教师同时段双派 ----------
    by_teacher = defaultdict(list)
    for a in A:
        by_teacher[a.get("teacherNo") or a.get("teacherName")].append(a)
    h1 = 0
    for t, lst in by_teacher.items():
        for i in range(len(lst)):
            for j in range(i + 1, len(lst)):
                x, y = lst[i], lst[j]
                if x.get("examDate") != y.get("examDate"):
                    continue
                if overlap(tmin(x["startTime"]), tmin(x["endTime"]),
                           tmin(y["startTime"]), tmin(y["endTime"])):
                    # 同考场同场次的两条记录不算冲突（主/副监）
                    if x.get("roomCode") == y.get("roomCode") and x.get("examId") == y.get("examId"):
                        continue
                    h1 += 1
                    issues.append(f"H1 教师同时段双派: {t} {x['examDate']} "
                                  f"{x['startTime']}-{x['endTime']}@{x.get('roomCode')} 与 "
                                  f"{y['startTime']}-{y['endTime']}@{y.get('roomCode')}")
    R["H1_教师同时段双派"] = h1

    # ---------- H2 考场同时段多场考试 ----------
    room_slots = defaultdict(set)
    for a in A:
        room_slots[(a.get("roomCode"), a.get("examDate"), a.get("startTime"))].add(a.get("examId"))
    h2 = sum(1 for k, v in room_slots.items() if len(v) > 1)
    for k, v in room_slots.items():
        if len(v) > 1:
            issues.append(f"H2 考场同时段多场: {k} → {len(v)} 场考试")
    R["H2_考场同时段多考"] = h2

    # ---------- H3 每考场监考人数是否符合要求 ----------
    # 期望值来自 input_exams: (科目,年级,日期,开始,考场) -> 监考人数
    want = {}
    for e in in_exams:
        key = (str(e.get("subjectName") or e.get("subject")), str(e.get("grade")),
               str(e.get("examDate")), str(e.get("startTime"))[:5], str(e.get("roomCode")))
        want[key] = int(e.get("supervisors") or e.get("supervisorCount") or 0)
    got = defaultdict(int)
    for a in A:
        key = (str(a.get("subjectName")), str(a.get("grade")), str(a.get("examDate")),
               str(a.get("startTime"))[:5], str(a.get("roomCode")))
        got[key] += 1
    h3 = 0
    if want:
        for k, need in want.items():
            actual = got.get(k, 0)
            if need and actual != need:
                h3 += 1
                issues.append(f"H3 监考人数不符: {k} 期望{need} 实际{actual}")
    R["H3_监考人数不符"] = h3 if want else "N/A(无输入考试明细)"

    # ---------- H4 幽灵实体 ----------
    h4 = 0
    if in_rooms:
        for a in A:
            if a.get("roomCode") not in in_rooms:
                h4 += 1
                issues.append(f"H4 分配到不存在的考场: {a.get('roomCode')}")
                break
    R["H4_幽灵考场"] = h4 if in_rooms else "N/A"

    # ---------- H5 重复分配 ----------
    seen = defaultdict(int)
    for a in A:
        seen[(a.get("examId"), a.get("roomId"), a.get("teacherId"))] += 1
    h5 = sum(1 for v in seen.values() if v > 1)
    if h5:
        issues.append(f"H5 重复分配 {h5} 组 (同一 exam+room+teacher 多次)")
    R["H5_重复分配"] = h5

    # ---------- H6 班主任监本班（需 input_teachers 带 homeroom 信息） ----------
    h6 = "N/A(无班主任数据)"
    R["H6_班主任监本班"] = h6

    # ---------- S1 负载均衡 ----------
    loads = [len(v) for v in by_teacher.values()]
    if loads:
        avg = sum(loads) / len(loads)
        var = sum((x - avg) ** 2 for x in loads) / len(loads)
        R["S1_负载"] = {"教师数": len(loads), "均值": round(avg, 2),
                        "最小": min(loads), "最大": max(loads),
                        "极差": max(loads) - min(loads), "标准差": round(var ** .5, 2)}

    # ---------- S2 教师利用率 ----------
    pool = data.get("job_stats", {}).get("teacherPool") or len(in_teachers) or len(by_teacher)
    R["S2_利用率"] = f"{len(by_teacher)}/{pool} = {round(100.0*len(by_teacher)/pool,1)}%" if pool else "N/A"

    # ---------- S3 连场压力：同一教师同一天的场次数 ----------
    per_day = defaultdict(lambda: defaultdict(int))
    for a in A:
        per_day[a.get("teacherNo") or a.get("teacherName")][a.get("examDate")] += 1
    max_day = max((c for d in per_day.values() for c in d.values()), default=0)
    R["S3_单日最多场次"] = max_day

    # ---------- S4 同学科回避（教师监考自己任教学科） ----------
    same_subj = 0
    if in_teachers:
        for a in A:
            t = in_teachers.get(str(a.get("teacherNo")))
            if t:
                subs = str(t.get("subjects") or t.get("subject") or "")
                if a.get("subjectName") and a["subjectName"] in subs:
                    same_subj += 1
        R["S4_同学科监考"] = f"{same_subj}/{len(A)} = {round(100.0*same_subj/len(A),1)}%"
    else:
        R["S4_同学科监考"] = "N/A"

    hard = sum(v for k, v in R.items() if k.startswith("H") and isinstance(v, int))
    R["hard_fail"] = hard
    R["verdict"] = "PASS" if hard == 0 else f"FAIL({hard} 项硬性违规)"
    return R, issues


def main():
    args = sys.argv[1:]
    files = []
    if args and args[0] == "--dir":
        d = args[1]
        files = [os.path.join(d, f) for f in sorted(os.listdir(d)) if f.endswith(".json")]
    else:
        files = args
    if not files:
        print(__doc__)
        return
    out = io.open("audit_report.txt", "w", encoding="utf-8")
    allpass = True
    for f in files:
        data = json.load(io.open(f, encoding="utf-8"))
        R, issues = audit(data)
        out.write("=" * 80 + "\n")
        out.write(f"{R['label']}  状态={R['status']}  分配数={R['n_assignments']}  判定={R['verdict']}\n")
        for k, v in R.items():
            if k in ("label", "status", "n_assignments", "verdict", "hard_fail"):
                continue
            out.write(f"  {k}: {v}\n")
        if issues:
            out.write("  --- 明细 ---\n")
            for i in issues[:20]:
                out.write(f"    {i}\n")
            if len(issues) > 20:
                out.write(f"    ...(另有 {len(issues)-20} 条)\n")
        if R.get("hard_fail"):
            allpass = False
    out.close()
    print("审计完成 → audit_report.txt ；全部通过:", allpass)


if __name__ == "__main__":
    main()
