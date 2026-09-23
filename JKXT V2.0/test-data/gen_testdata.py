# -*- coding: utf-8 -*-
"""高仿真排监考测试数据生成器 — 严格匹配后端导入列（与 import-backend-columns 模板一致）。
列定义（权威表头，来自后端 /import/templates/*）：
  教师: 工号 姓名 性别 年级 任教学科 手机号 邮箱
  考场: 考场编号 考场名称 楼栋 楼层 容量 考试容量 考场类型 设施标签
  考试: 考试科目 年级 考试日期 开始时间 结束时间 考场编号 人数 监考人数
"""
import os, random
import openpyxl

random.seed(20260618)
SUR = list("王李张刘陈杨赵黄周吴徐孙马朱胡郭何高林罗郑梁谢宋唐许韩冯邓曹彭曾肖田董袁潘于蒋蔡余杜叶程苏魏吕丁任沈姚卢")
GIV = list("伟芳娜敏静秀英华慧强磊洋勇艳杰娟涛明超霞平刚桂兰建文军婷雪琳云宇辰睿欣怡浩然轩涵远宁航曦")

def name():
    n = random.choice(SUR) + random.choice(GIV) + (random.choice(GIV) if random.random() < .45 else "")
    return n

def teacher_sheet(rows):
    wb = openpyxl.Workbook(); ws = wb.active; ws.title = "教师表"
    ws.append(["工号", "姓名", "性别", "年级", "任教学科", "手机号", "邮箱"])
    for r in rows: ws.append(r)
    return wb

def room_sheet(rows):
    wb = openpyxl.Workbook(); ws = wb.active; ws.title = "考场表"
    ws.append(["考场编号", "考场名称", "楼栋", "楼层", "容量", "考试容量", "考场类型", "设施标签"])
    for r in rows: ws.append(r)
    return wb

def exam_sheet(rows):
    wb = openpyxl.Workbook(); ws = wb.active; ws.title = "考试表"
    ws.append(["考试科目", "年级", "考试日期", "开始时间", "结束时间", "考场编号", "人数", "监考人数"])
    for r in rows: ws.append(r)
    return wb

def gen_teachers(grades, per_grade, subjects, idprefix):
    rows = []; n = 1
    for g in grades:
        homerooms = set(random.sample(range(per_grade), max(1, per_grade // 4)))
        for i in range(per_grade):
            subj = subjects[i % len(subjects)]
            sex = random.choice(["男", "女"])
            tid = "%s%04d" % (idprefix, n)
            phone = "138%08d" % (random.randint(0, 99999999))
            # email is UNIQUE(org_id, email); derive it from the unique 工号 so it can never
            # collide across grades, across the two gen_teachers() calls, or across iterations.
            email = "%s%04d@tjsyzx.cn" % (idprefix.lower(), n)
            extra = ("," + subjects[(i + 3) % len(subjects)]) if random.random() < .25 else ""
            rows.append([tid, name(), sex, g, subj + extra, phone, email])
            n += 1
    return rows

def gen_rooms(buildings, per_building, cap=48, sup_cap=36):
    rows = []
    for b, bname in buildings:
        for fl in range(1, per_building // 4 + 1):
            for k in range(1, 5):
                code = "%s%d%02d" % (b, fl, k)
                rows.append([code, "%s %d0%d 教室" % (bname, fl, k), bname, "%dF" % fl,
                             cap, sup_cap, "standard", "广播,监控,空调"])
    return rows

def gen_exams(plan, rooms):
    # plan: list of (subject, grade, date, start, end, n_rooms, students, supervisors)
    rows = []; codes = [r[0] for r in rooms]; idx = 0
    for (subj, grade, date, s, e, nrooms, stu, sup) in plan:
        for _ in range(nrooms):
            rows.append([subj, grade, date, s, e, codes[idx % len(codes)], stu, sup])
            idx += 1
    return rows

OUT = os.path.dirname(os.path.abspath(__file__))

# ============ 第2次：完全中学多年级期末考（跨年级、12考场、6年级） ============
def iter2():
    d = os.path.join(OUT, "02-多年级期末-完全中学")
    os.makedirs(d, exist_ok=True)
    grades = ["初一", "初二", "初三", "高一", "高二", "高三"]
    subj_jr = ["语文", "数学", "英语", "物理", "化学", "生物", "道德与法治", "历史", "地理"]
    subj_sr = ["语文", "数学", "英语", "物理", "化学", "生物", "政治", "历史", "地理"]
    teachers = gen_teachers(grades, 9, subj_jr, "T26")            # 54 teachers
    rooms = gen_rooms([("M", "明德楼"), ("Z", "致远楼")], 8)      # 16 rooms
    # 错峰：每年级在不同时段考，避免同一时段过多并发；每场 2 间考场、2 监考。
    slots = [("2026-06-22", "08:00", "10:00"), ("2026-06-22", "10:30", "12:00"),
             ("2026-06-23", "08:00", "10:00"), ("2026-06-23", "10:30", "12:00"),
             ("2026-06-24", "08:00", "10:00")]
    plan = []
    for gi, g in enumerate(grades):
        subs = (subj_sr if g.startswith("高") else subj_jr)[:4]
        for si, subj in enumerate(subs):
            date, s, e = slots[(gi + si) % len(slots)]
            plan.append((subj, g, date, s, e, 2, 36, 2))
    exams = gen_exams(plan, rooms)
    teacher_sheet(teachers).save(os.path.join(d, "01-教师安排-第2次-多年级期末.xlsx"))
    room_sheet(rooms).save(os.path.join(d, "02-考场信息-第2次-多年级期末.xlsx"))
    exam_sheet(exams).save(os.path.join(d, "03-考试安排-第2次-多年级期末.xlsx"))
    return d, len(teachers), len(rooms), len(exams)

# ============ 第3次：高三大型模考（单年级、验证同年级修复、16考场） ============
def iter3():
    d = os.path.join(OUT, "03-高三大型模考-单年级")
    os.makedirs(d, exist_ok=True)
    # 36 教师：28 高三 + 8 其他年级（修复后高三教师也可监考高三，仅避监本班）
    t = gen_teachers(["高三"], 28, ["语文", "数学", "英语", "物理", "化学", "生物", "政治", "历史", "地理"], "G3")
    t += gen_teachers(["高二", "高一"], 4, ["语文", "数学", "英语", "物理"], "GX")
    rooms = gen_rooms([("K", "考务楼")], 16)                      # 16 rooms
    # 高三一模：6 科目，2 天，每科 8 考场，2 监考 → 96 监考人次 / ~6 时段。
    plan = [
        ("语文", "高三", "2026-03-10", "09:00", "11:30", 8, 30, 2),
        ("数学", "高三", "2026-03-10", "15:00", "17:00", 8, 30, 2),
        ("英语", "高三", "2026-03-11", "09:00", "11:00", 8, 30, 2),
        ("理综", "高三", "2026-03-11", "15:00", "17:30", 8, 30, 2),
    ]
    exams = gen_exams(plan, rooms)
    teacher_sheet(t).save(os.path.join(d, "01-教师安排-第3次-高三模考.xlsx"))
    room_sheet(rooms).save(os.path.join(d, "02-考场信息-第3次-高三模考.xlsx"))
    exam_sheet(exams).save(os.path.join(d, "03-考试安排-第3次-高三模考.xlsx"))
    return d, len(t), len(rooms), len(exams)

for fn in (iter2, iter3):
    d, nt, nr, ne = fn()
    print("OK %-40s teachers=%d rooms=%d exam_rows=%d" % (os.path.basename(d), nt, nr, ne))
print("DONE")
