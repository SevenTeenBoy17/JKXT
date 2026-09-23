# -*- coding: utf-8 -*-
"""Reversibly remove scenario-test prefixes and restore snapshotted teacher.status."""
import json
import subprocess
from pathlib import Path

PG = ["docker", "exec", "jkxt-private-postgres", "psql", "-U", "jkxt", "-d", "jkxt_private", "-v", "ON_ERROR_STOP=1", "-c"]
SNAP = Path(__file__).resolve().parents[1] / "control" / "teacher-snapshot.json"

SQL = r"""
BEGIN;
UPDATE teacher SET homeroom_class_id = NULL WHERE teacher_no ~ '^(RP|RJ|RS|RH)';
UPDATE school_class SET homeroom_teacher_id = NULL WHERE code ~ '^(RP-|RJ-|RS-|RH-)';
DELETE FROM exam_class WHERE class_id IN (SELECT id FROM school_class WHERE code ~ '^(RP-|RJ-|RS-|RH-)');
DELETE FROM school_class_subject WHERE class_id IN (SELECT id FROM school_class WHERE code ~ '^(RP-|RJ-|RS-|RH-)');
DELETE FROM teacher_subject WHERE teacher_id IN (SELECT id FROM teacher WHERE teacher_no ~ '^(RP|RJ|RS|RH)');
DELETE FROM school_class WHERE code ~ '^(RP-|RJ-|RS-|RH-)';
DELETE FROM teacher WHERE teacher_no ~ '^(RP|RJ|RS|RH)';
DELETE FROM classroom WHERE code ~ '^(RP-|RJ-|RS-|RH-)';
COMMIT;
"""


def pg(sql: str):
    r = subprocess.run(PG + [sql], capture_output=True, text=True)
    if r.returncode != 0:
        raise SystemExit(r.stderr or r.stdout)
    return r.stdout.strip()


def main():
    print(pg(SQL))
    rows = json.loads(SNAP.read_text(encoding="utf-8"))["rows"]
    for row in rows:
        pg("UPDATE teacher SET status = '%s' WHERE id = '%s';" % (row["status"], row["id"]))
    print(pg("SELECT count(*) AS teachers, count(*) FILTER (WHERE status='active') AS active FROM teacher;"))
    print(pg("SELECT left(teacher_no,3) pfx, count(*) FROM teacher GROUP BY 1 ORDER BY 2 DESC;"))


if __name__ == "__main__":
    main()
