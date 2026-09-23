# -*- coding: utf-8 -*-
"""Assemble clean combined JSON per dataset from captured responses + input/export xlsx."""
import json, os, glob
import openpyxl

OUT = "results"

def rows_of(path):
    wb = openpyxl.load_workbook(path, read_only=True); ws = wb.active
    rows = [[ (c if c is not None else "") for c in r] for r in ws.iter_rows(values_only=True)]
    wb.close(); return rows

def parse_teachers(path):
    r = rows_of(path); out = []
    for row in r[1:]:
        if not row or not str(row[0]).strip(): continue
        out.append({"teacherNo": str(row[0]).strip(), "name": str(row[1]).strip(),
                    "gender": str(row[2]).strip() if len(row)>2 else "",
                    "grade": str(row[3]).strip() if len(row)>3 else "",
                    "subjects": str(row[4]).strip() if len(row)>4 else ""})
    return out

def parse_rooms(path):
    r = rows_of(path); out = []
    for row in r[1:]:
        if not row or not str(row[0]).strip(): continue
        out.append({"code": str(row[0]).strip(), "name": str(row[1]).strip(),
                    "capacity": row[4] if len(row)>4 else "", "examCapacity": row[5] if len(row)>5 else ""})
    return out

def parse_exams(path):
    r = rows_of(path); out = []
    for row in r[1:]:
        if not row or not str(row[0]).strip(): continue
        out.append({"subject": str(row[0]).strip(), "grade": str(row[1]).strip(),
                    "date": str(row[2]).strip(), "start": str(row[3]).strip(), "end": str(row[4]).strip(),
                    "roomCode": str(row[5]).strip(), "seats": row[6] if len(row)>6 else "",
                    "supervisors": row[7] if len(row)>7 else ""})
    return out

def parse_export(path):
    if not os.path.exists(path): return []
    try:
        r = rows_of(path)
    except Exception as e:
        return [{"_error": str(e)}]
    if not r: return []
    hdr = [str(c).strip() for c in r[0]]
    out = []
    for row in r[1:]:
        out.append({hdr[i] if i < len(hdr) else f"c{i}": (str(row[i]) if i < len(row) else "") for i in range(len(hdr))})
    return out

def load(path, default=None):
    try:
        with open(path, encoding="utf-8") as f: return json.load(f)
    except Exception:
        return default

for lbl in ["iter1", "iter2", "iter3"]:
    job = load(f"{OUT}/{lbl}_job.json", {})
    assign = load(f"{OUT}/{lbl}_assign.json", {})
    validate = load(f"{OUT}/{lbl}_validate.json", {})
    stats = (job.get("stats") or {})
    rows = assign.get("rows") if isinstance(assign, dict) else None
    if rows is None: rows = assign if isinstance(assign, list) else []
    combined = {
        "label": lbl,
        "status": job.get("status"),
        "validate_overview": (validate.get("report") or {}).get("overview"),
        "job_stats": {k: stats.get(k) for k in ("solverStatus","teachers","teacherPool","assignments","exams","rooms","solveMillis","seed")},
        "fairness": stats.get("fairness") or assign.get("fairness"),
        "assignments": rows,
        "assignments_count": len(rows),
        "input_teachers": parse_teachers(f"{OUT}/{lbl}_in_teacher.xlsx"),
        "input_rooms": parse_rooms(f"{OUT}/{lbl}_in_room.xlsx"),
        "input_exams": parse_exams(f"{OUT}/{lbl}_in_exam.xlsx"),
        "export_rows": parse_export(f"{OUT}/{lbl}_export.xlsx"),
    }
    with open(f"{OUT}/{lbl}_combined.json", "w", encoding="utf-8") as f:
        json.dump(combined, f, ensure_ascii=False, indent=1)
    # sanity print (ascii-safe)
    print(f"{lbl}: status={combined['status']} assignments={combined['assignments_count']} "
          f"export_rows={len(combined['export_rows'])} in_teachers={len(combined['input_teachers'])} "
          f"in_rooms={len(combined['input_rooms'])} in_exams={len(combined['input_exams'])} "
          f"overview={json.dumps(combined['validate_overview'], ensure_ascii=True)}")
print("POSTPROCESS DONE")
