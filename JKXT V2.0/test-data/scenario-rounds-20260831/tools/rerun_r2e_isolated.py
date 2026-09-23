# -*- coding: utf-8 -*-
"""Isolated R2-E rerun after circular-FK cleanup is known. Restores demo roster afterwards."""
import json
import subprocess
import time
from pathlib import Path

import requests

BASE = "http://127.0.0.1:18180/api/v1"
ROOT = Path(__file__).resolve().parents[1]
XDIR = ROOT / "school-B-junior" / "import" / "R2-E-infeasible"
OUT = ROOT / "runs" / "R2-E-isolated"
SNAP = ROOT / "control" / "teacher-snapshot.json"
PG = ["docker", "exec", "jkxt-private-postgres", "psql", "-U", "jkxt", "-d", "jkxt_private", "-Atc"]


def pg(sql):
    r = subprocess.run(PG + [sql], capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError(r.stderr or r.stdout)
    return r.stdout.strip()


def scrub_rj():
    pg("UPDATE teacher SET homeroom_class_id = NULL WHERE teacher_no LIKE 'RJ%';")
    pg("UPDATE school_class SET homeroom_teacher_id = NULL WHERE code LIKE 'RJ-%';")
    pg("DELETE FROM exam_class WHERE class_id IN (SELECT id FROM school_class WHERE code LIKE 'RJ-%');")
    pg("DELETE FROM school_class_subject WHERE class_id IN (SELECT id FROM school_class WHERE code LIKE 'RJ-%');")
    pg("DELETE FROM teacher_subject WHERE teacher_id IN (SELECT id FROM teacher WHERE teacher_no LIKE 'RJ%');")
    pg("DELETE FROM school_class WHERE code LIKE 'RJ-%';")
    pg("DELETE FROM teacher WHERE teacher_no LIKE 'RJ%';")
    pg("DELETE FROM classroom WHERE code LIKE 'RJ-%';")


def restore():
    rows = json.loads(SNAP.read_text(encoding="utf-8"))["rows"]
    for row in rows:
        pg("UPDATE teacher SET status = '%s' WHERE id = '%s';" % (row["status"], row["id"]))


def main():
    s = requests.Session()
    cap = s.post(f"{BASE}/auth/captcha", timeout=20).json()
    login = s.post(f"{BASE}/auth/login", json={
        "username": "jwc.demo", "password": "Demo@2025",
        "captchaId": cap["captchaId"], "captcha": cap.get("debugAnswer"),
    }, timeout=20).json()
    print("login", login["user"]["username"])
    snapshot = json.loads(SNAP.read_text(encoding="utf-8"))["rows"]
    try:
        scrub_rj()
        pg("UPDATE teacher SET status = 'disabled';")
        task = s.post(f"{BASE}/exam-tasks", json={"name": "rs-R2-E-isolated", "source": "scenario-test"}, timeout=20).json()
        tid = task["id"]
        print("task", tid)
        for kind, name in (("teacher", "01-教师安排.xlsx"), ("classroom", "02-考场信息.xlsx"), ("exam", "03-考试安排.xlsx")):
            p = XDIR / name
            with p.open("rb") as f:
                r = s.post(f"{BASE}/exam-tasks/{tid}/uploads", params={"kind": kind},
                           files={"file": (name, f, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")},
                           timeout=120)
            print("upload", kind, r.status_code)
        cls = XDIR / "04-班级.xlsx"
        if cls.exists():
            with cls.open("rb") as f:
                r = s.post(f"{BASE}/import/classes/commit", files={"file": (cls.name, f, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")}, timeout=120)
            print("classes", r.status_code, r.text[:200])
        pg("UPDATE teacher SET status = 'disabled' WHERE teacher_no NOT LIKE 'RJ%';")
        pg("UPDATE teacher SET status = 'active' WHERE teacher_no LIKE 'RJ%';")
        active = pg("SELECT count(*) FROM teacher WHERE status='active';")
        print("active", active)
        v = s.post(f"{BASE}/exam-tasks/{tid}/imports/validate", timeout=60)
        print("validate", v.status_code, v.text[:300])
        job = s.post(f"{BASE}/exam-tasks/{tid}/schedule-jobs", json={}, timeout=30).json()
        print("job", job)
        jid = job.get("jobId")
        final = job
        if jid:
            for _ in range(90):
                final = s.get(f"{BASE}/schedule-jobs/{jid}", timeout=30).json()
                if final.get("status") in ("succeeded", "failed", "infeasible"):
                    break
                time.sleep(2)
        assign = s.get(f"{BASE}/exam-tasks/{tid}/assignments", timeout=60).json()
        rows = assign.get("rows") or []
        env = {
            "active_after_quarantine": int(active or 0),
            "job": job,
            "final_status": final.get("status"),
            "assignment_count": len(rows),
            "task_id": tid,
            "expected": "infeasible because 4 teachers vs 6 concurrent seats",
        }
        OUT.mkdir(parents=True, exist_ok=True)
        (OUT / "run-envelope.json").write_text(json.dumps(env, ensure_ascii=False, indent=2), encoding="utf-8")
        (OUT / "assignments.json").write_text(json.dumps(rows, ensure_ascii=False, indent=2), encoding="utf-8")
        print("RESULT", env["final_status"], "n=", env["assignment_count"], "active=", env["active_after_quarantine"])
        s.delete(f"{BASE}/exam-tasks/{tid}", timeout=30)
    finally:
        scrub_rj()
        restore()
        print("restored active", pg("SELECT count(*) FROM teacher WHERE status='active';"))


if __name__ == "__main__":
    main()
