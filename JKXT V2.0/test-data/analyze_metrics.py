# -*- coding: utf-8 -*-
"""Deterministic ground-truth data-quality metrics for captured scheduling results."""
import json
from collections import defaultdict
from statistics import pstdev, mean

def tmin(t):  # "HH:MM[:SS]" -> minutes
    p = str(t).split(":"); return int(p[0])*60 + int(p[1])

def overlaps(a, b):
    return a[0] == b[0] and a[1] < b[3] and b[2] < a[4]  # same date, [s,e) intervals; a=(date,s,e),b=(date,s,e)... use tuples

def analyze(lbl):
    d = json.load(open(f"results/{lbl}_combined.json", encoding="utf-8"))
    A = d["assignments"]
    pool = d["job_stats"]["teacherPool"]
    inteacher = {t["teacherNo"]: t for t in d["input_teachers"]}
    inrooms = {r["code"] for r in d["input_rooms"]}
    inexams = d["input_exams"]
    R = {}
    R["label"] = lbl
    R["status"] = d["status"]
    R["counts"] = {"assignments": len(A), "exams_in": len(inexams), "rooms_in": len(d["input_rooms"]),
                   "teachers_in": len(d["input_teachers"]), "pool": pool,
                   "supervisions_overview": d["validate_overview"].get("supervisions")}

    # ---- coverage: group by (examId, roomId) ----
    grp = defaultdict(list)
    for a in A: grp[(a["examId"], a["roomId"])].append(a)
    sup_in = {}  # expected supervisors per exam-room from input (match by roomCode + subject + time, approx by roomCode)
    # build expected supervisors per (roomCode,date,start) from input
    exp = {}
    for e in inexams:
        exp[(e["roomCode"], e["date"], e["start"][:5])] = int(e["supervisors"] or 2)
    bad_count = []; bad_main = []
    for (eid, rid), rows in grp.items():
        rc = rows[0].get("roomCode"); dt = rows[0].get("examDate"); st = str(rows[0].get("startTime",""))[:5]
        want = exp.get((rc, dt, st), 2)
        mains = sum(1 for r in rows if r["role"] == "main")
        if len(rows) != want: bad_count.append({"exam_room": f"{rc}@{dt} {st}", "got": len(rows), "want": want})
        if mains != 1: bad_main.append({"exam_room": f"{rc}@{dt} {st}", "mains": mains})
    R["coverage"] = {"exam_room_groups": len(grp), "groups_wrong_supervisor_count": len(bad_count),
                     "groups_wrong_main_count": len(bad_main), "examples_bad_count": bad_count[:5], "examples_bad_main": bad_main[:5]}

    # ---- conflict: teacher double-booked across overlapping times ----
    byT = defaultdict(list)
    for a in A:
        byT[a["teacherId"]].append((a.get("examDate"), tmin(a.get("startTime")), tmin(a.get("endTime")), a.get("roomCode")))
    conflicts = []
    for tid, slots in byT.items():
        slots.sort()
        for i in range(len(slots)):
            for j in range(i+1, len(slots)):
                if slots[i][0] == slots[j][0] and slots[i][1] < slots[j][2] and slots[j][1] < slots[i][2]:
                    conflicts.append({"teacher": tid[:8], "a": slots[i], "b": slots[j]})
    R["conflicts"] = {"teacher_time_overlaps": len(conflicts), "examples": conflicts[:5]}

    # ---- fairness / load ----
    load = defaultdict(int); mainload = defaultdict(int)
    for a in A:
        load[a["teacherId"]] += 1
        if a["role"] == "main": mainload[a["teacherId"]] += 1
    used = len(load)
    loads_used = sorted(load.values(), reverse=True)
    idle = pool - used
    # over whole pool (idle teachers count as 0)
    loads_pool = loads_used + [0]*idle
    def gini(x):
        x = sorted(x); n = len(x); s = sum(x)
        if n == 0 or s == 0: return 0.0
        cum = sum((i+1)*v for i, v in enumerate(x))
        return round((2*cum)/(n*s) - (n+1)/n, 4)
    R["fairness"] = {
        "teachers_used": used, "teachers_idle": idle, "utilization_pct": round(100*used/pool, 1) if pool else None,
        "load_used_max": max(loads_used) if loads_used else 0, "load_used_min": min(loads_used) if loads_used else 0,
        "load_used_avg": round(mean(loads_used), 2) if loads_used else 0,
        "load_used_stdev": round(pstdev(loads_used), 2) if len(loads_used) > 1 else 0,
        "maxDiff_pool": (max(loads_used) - 0) if idle > 0 else (max(loads_used)-min(loads_used) if loads_used else 0),
        "gini_pool": gini(loads_pool), "gini_used": gini(loads_used),
        "top5_loads": loads_used[:5], "main_per_teacher_max": max(mainload.values()) if mainload else 0,
        "backend_fairness": d.get("fairness"),
    }

    # ---- integrity: export vs assignments; refs exist; supervisions sum ----
    exp_rows = d["export_rows"]
    R["integrity"] = {
        "export_rows": len(exp_rows), "assignments": len(A), "export_eq_assignments": len(exp_rows) == len(A),
        "assignment_roomCodes_in_input": sum(1 for a in A if a.get("roomCode") in inrooms),
        "assignment_roomCodes_total": len(A),
        "assignment_teacherNo_in_input": sum(1 for a in A if a.get("teacherNo") in inteacher),
        "input_supervisor_sum": sum(int(e["supervisors"] or 2) for e in inexams),
    }

    # ---- grade match: teacher grade vs exam grade (same-grade policy is OFF by default => same grade allowed) ----
    same_grade = 0; cross_grade = 0; unknown = 0
    for a in A:
        tno = a.get("teacherNo"); tg = inteacher.get(tno, {}).get("grade", ""); eg = a.get("grade", "")
        if not tg: unknown += 1
        elif tg == eg: same_grade += 1
        else: cross_grade += 1
    R["grade"] = {"same_grade_assignments": same_grade, "cross_grade_assignments": cross_grade, "unknown_teacher_grade": unknown}

    # ---- distribution: assignments per date + per slot; peak concurrency ----
    byslot = defaultdict(int); bydate = defaultdict(int)
    slotrooms = defaultdict(set)
    for a in A:
        bydate[a.get("examDate")] += 1
        key = (a.get("examDate"), str(a.get("startTime"))[:5], str(a.get("endTime"))[:5])
        byslot[key] += 1
        slotrooms[key].add(a.get("roomId"))
    peak = max(byslot.values()) if byslot else 0
    R["distribution"] = {"slots": len(byslot), "dates": dict(bydate), "peak_concurrent_assignments": peak,
                         "peak_concurrent_rooms": max((len(v) for v in slotrooms.values()), default=0),
                         "assignments_per_slot": sorted(byslot.values(), reverse=True)}

    # ---- input room double-booking (same room, overlapping time in INPUT) ----
    roomslots = defaultdict(list)
    for e in inexams:
        roomslots[e["roomCode"]].append((e["date"], tmin(e["start"]), tmin(e["end"]), e["subject"]))
    rdb = 0
    for rc, sl in roomslots.items():
        sl.sort()
        for i in range(len(sl)):
            for j in range(i+1, len(sl)):
                if sl[i][0]==sl[j][0] and sl[i][1]<sl[j][2] and sl[j][1]<sl[i][2]: rdb += 1
    R["input_room_double_booking"] = rdb
    return R

allr = {}
for lbl in ["iter1","iter2","iter3"]:
    allr[lbl] = analyze(lbl)
json.dump(allr, open("results/metrics.json","w",encoding="utf-8"), ensure_ascii=False, indent=1)
# print summary
for lbl, R in allr.items():
    print(f"\n===== {lbl} ({R['status']}) =====")
    print(" counts:", json.dumps(R["counts"],ensure_ascii=True))
    print(" coverage:", json.dumps({k:R['coverage'][k] for k in ['exam_room_groups','groups_wrong_supervisor_count','groups_wrong_main_count']},ensure_ascii=True))
    print(" conflicts:", R["conflicts"]["teacher_time_overlaps"])
    print(" fairness:", json.dumps({k:R['fairness'][k] for k in ['teachers_used','teachers_idle','utilization_pct','load_used_max','load_used_min','load_used_avg','load_used_stdev','gini_pool','top5_loads']},ensure_ascii=True))
    print(" integrity:", json.dumps(R["integrity"],ensure_ascii=True))
    print(" grade:", json.dumps(R["grade"],ensure_ascii=True))
    print(" distribution:", json.dumps({k:R['distribution'][k] for k in ['slots','dates','peak_concurrent_assignments','peak_concurrent_rooms']},ensure_ascii=True))
    print(" input_room_double_booking:", R["input_room_double_booking"])
print("\nMETRICS DONE -> results/metrics.json")
