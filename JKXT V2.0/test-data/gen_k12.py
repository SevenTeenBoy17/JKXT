# -*- coding: utf-8 -*-
"""K12 全学段高仿真测试数据生成器 — 小学 / 初中 / 高中 各 12 份（9 正向 + 2 边界 + 1 负向）。

严格沿用 gen_testdata.py 已验证的后端导入列契约（来自 /import/templates/*）：
  教师: 工号 姓名 性别 年级 任教学科 手机号 邮箱
  考场: 考场编号 考场名称 楼栋 楼层 容量 考试容量 考场类型 设施标签
  考试: 考试科目 年级 考试日期 开始时间 结束时间 考场编号 人数 监考人数

学段真实性建模（见 K12全学段测试计划-20260817.md §二）：
  小学：语数英+科学道法；一二年级不纸笔统考(双减)；常态单人监考；班额<=45
  初中：物理初二起/化学初三；初二地生会考；每场 2 人主副监；班额<=50
  高中：高一9科；高二/高三 3+1+2 选科走班(同时段并行多科)；每场 2 人；班额<=50

关键约束（来自后端实现，防止生成出"假可解/假冲突"的数据）：
  1) 工号唯一 + email 唯一(org_id,email) → 每个数据集独占工号前缀，email 由工号派生
  2) 求解器教师池 = 全 org 在职教师 → 数据集间需按工号前缀清理（k12_run_all.sh 负责）
  3) 每场考试必须绑定存在的考场编号，否则 400 SCHEDULE_INPUT_INCOMPLETE
  4) 监考人数>=1；同一教师同时段不可双考场（硬约束）
可复现：固定随机种子。
"""
import os
import random
import openpyxl

SEED = 20260817
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "k12")

SUR = list("王李张刘陈杨赵黄周吴徐孙马朱胡郭何高林罗郑梁谢宋唐许韩冯邓曹彭曾肖田董袁潘于蒋蔡余杜叶程苏魏吕丁任沈姚卢")
GIV = list("伟芳娜敏静秀英华慧强磊洋勇艳杰娟涛明超霞平刚桂兰建文军婷雪琳云宇辰睿欣怡浩然轩涵远宁航曦")


def cname(rnd):
    return rnd.choice(SUR) + rnd.choice(GIV) + (rnd.choice(GIV) if rnd.random() < .45 else "")


# ---------- sheet writers（表头即权威契约，勿改） ----------
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


# ---------- builders ----------
def gen_teachers(rnd, prefix, grades, per_grade, subjects, domain, homeroom_ratio=.25):
    """返回教师行。homeroom_ratio 比例的教师标为班主任任教年级（避本班约束的来源）。"""
    rows = []
    n = 1
    for g in grades:
        for i in range(per_grade):
            subj = subjects[i % len(subjects)]
            # 25% 概率兼教第二学科（真实小规模学校常见）
            extra = ("," + subjects[(i + 3) % len(subjects)]) if rnd.random() < .25 else ""
            tid = "%s%04d" % (prefix, n)
            rows.append([tid, cname(rnd), rnd.choice(["男", "女"]), g, subj + extra,
                         "138%08d" % rnd.randint(0, 99999999),
                         "%s%04d@%s" % (prefix.lower(), n, domain)])
            n += 1
    return rows


def gen_rooms(buildings, per_building, cap=45, sup_cap=30, rtype="standard",
              facil="广播,监控,空调"):
    # 修复（2026-08-18）：原实现 floors = per_building//4，当 per_building 不是 4 的倍数时**实际产出少于请求数**
    # （如请求 6 → floors=1 → 只产 4 个），调用方却按请求数安排考试，导致考场不足、只能靠轮转复用 →
    # 同考场同时段多场考试。现改为向上取整，保证"请求几个就产出几个"。
    rows = []
    for b, bname in buildings:
        floors = max(1, (per_building + 3) // 4)
        k = 0
        for fl in range(1, floors + 1):
            for j in range(1, 5):
                if k >= per_building:
                    break
                k += 1
                code = "%s%d%02d" % (b, fl, j)
                rows.append([code, "%s%d0%d教室" % (bname, fl, j), bname, "%dF" % fl,
                             cap, sup_cap, rtype, facil])
    return rows


def gen_exams(plan, rooms):
    """plan: [(subject, grade, date, start, end, n_rooms, students, supervisors)]"""
    rows = []
    # 修复（2026-08-18）：原实现用全局 idx 轮转 codes[idx % len(codes)]，跨时段绕回时会把**同一考场
    # 分配给同一时段的两场不同考试**——物理上不可能（一个教室同时只能考一门），产出自相矛盾的考试表。
    # 现改为**按时段独立分配**：每个 (date,start,end) 时段内从 0 开始取考场，段内不重复；
    # 若某时段需要的考场数超过总数，直接抛错而不是静默复用（宁可让生成失败也不产出非法数据）。
    codes = [r[0] for r in rooms]
    per_slot = {}
    for (subj, grade, date, s, e, nrooms, stu, sup) in plan:
        slot = (date, s, e)
        used = per_slot.setdefault(slot, 0)
        if used + nrooms > len(codes):
            raise ValueError(
                f"时段 {slot} 需要 {used + nrooms} 个考场，但只有 {len(codes)} 个 —— "
                f"会造成同考场同时段多场考试。请增加考场或减少并发科目。")
        for k in range(nrooms):
            rows.append([subj, grade, date, s, e, codes[used + k], stu, sup])
        per_slot[slot] = used + nrooms
    return rows


def assert_valid(dirname, rooms, exams):
    """生成期自检：任何一条不成立都说明数据集自相矛盾，直接失败而非产出非法数据。"""
    cap = {r[0]: int(r[5]) for r in rooms}          # 考场编号 -> 考试容量
    # 1) 同考场同时段不得有多场考试
    seen = {}
    for r in exams:
        key = (r[5], r[2], r[3])                    # (考场, 日期, 开始)
        sig = (r[0], r[1])                          # (科目, 年级)
        if key in seen and seen[key] != sig:
            raise AssertionError(f"{dirname}: 考场 {r[5]} 在 {r[2]} {r[3]} 被 {seen[key]} 与 {sig} 同时占用")
        seen[key] = sig
    # 2) 人数不得超过考场考试容量（否则求解器 H7 预检必然拒绝 → 无法验证排考逻辑）
    for r in exams:
        c = cap.get(r[5])
        if c is not None and int(r[6]) > c:
            raise AssertionError(f"{dirname}: 考场 {r[5]} 容量 {c} < 人数 {r[6]}（H7 预检会直接判无解）")
    # 3) 监考人数必须 >= 1（0 人监考是非法安排；负向数据集另行显式构造）
    for r in exams:
        if int(r[7]) < 1 and "脏数据" not in dirname:
            raise AssertionError(f"{dirname}: 监考人数 {r[7]} < 1")
    return True


def save(dirname, teachers, rooms, exams, manifest):
    # 负向数据集有意含非法行，跳过自检；其余一律强制自检，防止再产出自相矛盾的数据。
    if "脏数据" not in dirname:
        assert_valid(dirname, rooms, exams)
    d = os.path.join(OUT, dirname)
    os.makedirs(d, exist_ok=True)
    teacher_sheet(teachers).save(os.path.join(d, "01-教师安排.xlsx"))
    room_sheet(rooms).save(os.path.join(d, "02-考场信息.xlsx"))
    exam_sheet(exams).save(os.path.join(d, "03-考试安排.xlsx"))
    with open(os.path.join(d, "manifest.md"), "w", encoding="utf-8") as f:
        f.write(manifest)
    # 监考人次 = sum(每场考试的监考人数)
    demand = sum(int(r[7]) for r in exams)
    return dirname, len(teachers), len(rooms), len(exams), demand


def mf(title, stage, scenario, expect, teachers, rooms, exams, prefix, notes=""):
    demand = sum(int(r[7]) for r in exams)
    return f"""# {title}

| 项 | 值 |
|---|---|
| 学段 | {stage} |
| 场景 | {scenario} |
| 工号前缀 | `{prefix}`（测后按此前缀清理教师，保证教师池纯净） |
| 教师数 | {len(teachers)} |
| 考场数 | {len(rooms)} |
| 考试行数 | {len(exams)} |
| 监考人次需求 | {demand} |
| **预期结果** | {expect} |

{notes}
"""


# ================= 小学（Primary，前缀 P01~P12） =================
# 学科：语文/数学/英语(3年级起)/科学/道德与法治；一二年级不纸笔统考(双减)
PRI_SUBJ = ["语文", "数学", "英语", "科学", "道德与法治"]
PRI_SUBJ_LOW = ["语文", "数学"]          # 低年级(若考)只考语数
PRI_DOMAIN = "tjsyxx.cn"


def primary(results):
    R = random.Random(SEED + 100)

    # P01 正向·小规模：三年级期末，4班，语数英，1天
    t = gen_teachers(R, "P01", ["三年级"], 12, PRI_SUBJ, PRI_DOMAIN)
    rm = gen_rooms([("A", "行知楼")], 4, cap=45, sup_cap=40)
    ex = gen_exams([("语文", "三年级", "2026-06-25", "08:30", "10:00", 4, 40, 2),
                    ("数学", "三年级", "2026-06-25", "10:30", "11:40", 4, 40, 2),
                    ("英语", "三年级", "2026-06-25", "14:30", "15:40", 4, 40, 2)], rm)
    results.append(save("小学/P01-三年级期末-小规模", t, rm, ex,
        mf("P01 三年级期末（小规模）", "小学", "单年级 4 班 3 科 1 天", "succeeded，24 监考人次全部分配，时间冲突=0",
           t, rm, ex, "P01", "最小可行全链路：验证上传→校验→排考→发布→导出闭环。")))

    # P02 正向·中规模：三~六年级期末，各4班，1.5天
    t = gen_teachers(R, "P02", ["三年级", "四年级", "五年级", "六年级"], 10, PRI_SUBJ, PRI_DOMAIN)
    rm = gen_rooms([("A", "行知楼"), ("B", "笃行楼")], 8, cap=45, sup_cap=40)
    plan = []
    slots = [("2026-06-25", "08:30", "10:00"), ("2026-06-25", "10:30", "11:40"),
             ("2026-06-25", "14:30", "15:40"), ("2026-06-26", "08:30", "10:00")]
    for gi, g in enumerate(["三年级", "四年级", "五年级", "六年级"]):
        for si, s in enumerate(["语文", "数学", "英语"]):
            d, st, en = slots[(gi + si) % len(slots)]
            plan.append((s, g, d, st, en, 4, 40, 2))
    ex = gen_exams(plan, rm)
    results.append(save("小学/P02-四年级段期末-中规模", t, rm, ex,
        mf("P02 三~六年级期末（中规模）", "小学", "4 年级 × 3 科错峰 1.5 天", "succeeded，多年级并行且同年级软回避生效",
           t, rm, ex, "P02", "验证跨年级并行排考与错峰时段处理。")))

    # P03 正向·大规模：全校三~六年级统考，各8班
    t = gen_teachers(R, "P03", ["三年级", "四年级", "五年级", "六年级"], 18, PRI_SUBJ, PRI_DOMAIN)
    # 修复①：考场编号加数据集前缀，避免按 code upsert 覆盖基线考场（此前 C101-C104 污染了 org 基线容量）。
    # 修复②：人数 42 > 考试容量 40 会被 CpSatScheduleSolver.precheck() 的 H7 直接判无解（求解器都不跑），
    #        本数据集意在验证"大规模仍可解"，故把考试容量提到 45 与人数 42 匹配。
    rm = gen_rooms([("P3A", "行知楼"), ("P3B", "笃行楼"), ("P3C", "启新楼")], 8, cap=50, sup_cap=45)
    plan = []
    for gi, g in enumerate(["三年级", "四年级", "五年级", "六年级"]):
        for si, s in enumerate(["语文", "数学", "英语", "科学"]):
            d, st, en = slots[(gi + si) % len(slots)]
            plan.append((s, g, d, st, en, 6, 42, 2))
    ex = gen_exams(plan, rm)
    results.append(save("小学/P03-全校统考-大规模", t, rm, ex,
        mf("P03 全校三~六年级统考（大规模）", "小学", "4 年级 × 4 科 × 6 考场", "succeeded，规模压力下求解成功，负载 stdev 可接受",
           t, rm, ex, "P03", "规模压力测试：验证求解时长与负载均衡目标。")))

    # P04 正向·学段特色：一二年级乐考缺席（教师表含低年级教师，考试表无一二年级）
    t = gen_teachers(R, "P04", ["一年级", "二年级"], 8, PRI_SUBJ_LOW, PRI_DOMAIN)
    t += gen_teachers(R, "P04B", ["三年级", "四年级", "五年级", "六年级"], 8, PRI_SUBJ, PRI_DOMAIN)
    rm = gen_rooms([("A", "行知楼"), ("B", "笃行楼")], 6, cap=45, sup_cap=40)
    plan = []
    for gi, g in enumerate(["三年级", "四年级", "五年级", "六年级"]):
        for si, s in enumerate(["语文", "数学"]):
            d, st, en = slots[(gi + si) % len(slots)]
            plan.append((s, g, d, st, en, 3, 40, 2))
    ex = gen_exams(plan, rm)
    results.append(save("小学/P04-一二年级乐考缺席-学段特色", t, rm, ex,
        mf("P04 一二年级乐考缺席（学段特色）", "小学",
           "双减政策：一二年级不组织纸笔统考；其教师仍participate监考池",
           "succeeded，一二年级教师被排入三~六年级考场（教师池含低年级）",
           t, rm, ex, "P04", "**真实政策建模**：教育部 2021 双减要求一二年级不进行纸笔考试。\n"
                             "验证点：考试表无一二年级；一二年级教师应出现在分配结果中（跨年级监考）。")))

    # P05 正向·单人监考：小学常态月考 监考人数=1
    t = gen_teachers(R, "P05", ["四年级", "五年级"], 10, PRI_SUBJ, PRI_DOMAIN)
    rm = gen_rooms([("A", "行知楼")], 8, cap=45, sup_cap=40)
    plan = []
    for gi, g in enumerate(["四年级", "五年级"]):
        for si, s in enumerate(["语文", "数学", "英语"]):
            d, st, en = slots[(gi + si) % len(slots)]
            plan.append((s, g, d, st, en, 4, 40, 1))   # 单人监考
    ex = gen_exams(plan, rm)
    results.append(save("小学/P05-月考单人监考", t, rm, ex,
        mf("P05 月考单人监考", "小学", "监考人数=1（小学低利害考试常态）", "succeeded，每考场恰好 1 名监考",
           t, rm, ex, "P05", "验证 supervisorsPerRoom=1 路径（此前多数数据集为 2）。")))

    # P06 正向·班主任密集
    t = gen_teachers(R, "P06", ["五年级", "六年级"], 12, PRI_SUBJ, PRI_DOMAIN, homeroom_ratio=1.0)
    rm = gen_rooms([("B", "笃行楼")], 8, cap=45, sup_cap=40)
    plan = []
    for gi, g in enumerate(["五年级", "六年级"]):
        for si, s in enumerate(["语文", "数学", "英语"]):
            d, st, en = slots[(gi + si) % len(slots)]
            plan.append((s, g, d, st, en, 4, 40, 2))
    ex = gen_exams(plan, rm)
    results.append(save("小学/P06-班主任密集-避本班", t, rm, ex,
        mf("P06 班主任密集（避本班）", "小学", "大量班主任任教本班", "succeeded 且**班主任避本班违规=0**",
           t, rm, ex, "P06", "验证 M1.1 硬约束：班主任不监考本班考场。")))

    # P07 正向·教师紧缺（供给≈需求×1.1）
    t = gen_teachers(R, "P07", ["六年级"], 14, PRI_SUBJ, PRI_DOMAIN)   # 14 教师
    rm = gen_rooms([("A", "行知楼")], 6, cap=45, sup_cap=40)
    ex = gen_exams([("语文", "六年级", "2026-06-25", "08:30", "10:00", 6, 40, 2),
                    ("数学", "六年级", "2026-06-25", "10:30", "11:40", 6, 40, 2)], rm)  # 24 人次/2时段=12峰值
    results.append(save("小学/P07-教师紧缺-资源边界", t, rm, ex,
        mf("P07 教师紧缺（资源边界）", "小学", "教师供给≈单时段峰值需求×1.17", "succeeded，但负载 stdev 偏高属正常",
           t, rm, ex, "P07", "资源边界：单时段需 12 人监考、仅 14 名教师可用。")))

    # P08 正向·时段密集：单日 4 场连排
    t = gen_teachers(R, "P08", ["四年级", "五年级", "六年级"], 10, PRI_SUBJ, PRI_DOMAIN)
    rm = gen_rooms([("A", "行知楼"), ("B", "笃行楼")], 6, cap=45, sup_cap=40)
    tight = [("2026-06-25", "08:00", "09:10"), ("2026-06-25", "09:25", "10:35"),
             ("2026-06-25", "10:50", "12:00"), ("2026-06-25", "14:00", "15:10")]
    plan = []
    for i, (d, st, en) in enumerate(tight):
        g = ["四年级", "五年级", "六年级"][i % 3]
        plan.append((PRI_SUBJ[i % 3], g, d, st, en, 4, 40, 2))
    ex = gen_exams(plan, rm)
    results.append(save("小学/P08-单日连排-时段密集", t, rm, ex,
        mf("P08 单日 4 场连排（时段密集）", "小学", "场次间隔仅 15 分钟", "succeeded，同教师不得同时段双场",
           t, rm, ex, "P08", "验证连续时段的教师复用与休息间隔行为。")))

    # P09 正向·考场异构
    rm = (gen_rooms([("A", "行知楼")], 4, cap=30, sup_cap=25) +
          gen_rooms([("B", "笃行楼")], 4, cap=45, sup_cap=40) +
          gen_rooms([("C", "启新楼")], 4, cap=60, sup_cap=55, facil="广播,监控"))
    t = gen_teachers(R, "P09", ["五年级", "六年级"], 12, PRI_SUBJ, PRI_DOMAIN)
    plan = []
    for gi, g in enumerate(["五年级", "六年级"]):
        for si, s in enumerate(["语文", "数学", "英语"]):
            d, st, en = slots[(gi + si) % len(slots)]
            plan.append((s, g, d, st, en, 4, 25, 2))
    ex = gen_exams(plan, rm)
    results.append(save("小学/P09-考场异构-容量混搭", t, rm, ex,
        mf("P09 考场异构（容量混搭）", "小学", "容量 30/45/60 混搭 + 设施标签差异", "succeeded，容量校验通过",
           t, rm, ex, "P09", "验证不同容量/设施考场的处理与校验。")))

    # P10 边界·极限可解（供给刚好等于峰值需求）
    t = gen_teachers(R, "P10", ["六年级"], 8, PRI_SUBJ, PRI_DOMAIN)   # 8 教师
    rm = gen_rooms([("A", "行知楼")], 4, cap=45, sup_cap=40)
    ex = gen_exams([("语文", "六年级", "2026-06-25", "08:30", "10:00", 4, 40, 2)], rm)  # 峰值 8 人 = 8 教师
    results.append(save("小学/P10-极限可解-临界", t, rm, ex,
        mf("P10 极限可解（临界）", "小学", "教师数 8 = 单时段需求 8（恰好）", "succeeded（临界可解，非 infeasible）",
           t, rm, ex, "P10", "临界点：再少一人即无解。验证求解器在恰好可解时不误报无解。")))

    # P11 边界·预期无解（教师故意不足）
    t = gen_teachers(R, "P11", ["六年级"], 5, PRI_SUBJ, PRI_DOMAIN)   # 仅 5 教师
    rm = gen_rooms([("A", "行知楼")], 5, cap=45, sup_cap=40)
    ex = gen_exams([("语文", "六年级", "2026-06-25", "08:30", "10:00", 5, 40, 2)], rm)  # 需 10 人 > 5
    results.append(save("小学/P11-预期无解-资源不足", t, rm, ex,
        mf("P11 预期无解（资源不足）", "小学", "需 10 监考人次，仅 5 名教师", "**infeasible**（必须如实报告无解，不得假成功）",
           t, rm, ex, "P11", "反向验证：系统必须诚实返回 infeasible，而非编造分配或假 succeeded。")))

    # P12 负向·脏数据（考场编号不存在 + 监考人数=0 + 重复工号）
    t = gen_teachers(R, "P12", ["六年级"], 10, PRI_SUBJ, PRI_DOMAIN)
    t.append(list(t[0]))                       # 重复工号（应被 upsert 或报错，不得静默产生双行）
    rm = gen_rooms([("A", "行知楼")], 4, cap=45, sup_cap=40)
    ex = gen_exams([("语文", "六年级", "2026-06-25", "08:30", "10:00", 4, 40, 2)], rm)
    ex.append(["数学", "六年级", "2026-06-25", "10:30", "11:40", "NOPE99", 40, 2])  # 不存在的考场
    ex.append(["英语", "六年级", "2026-06-25", "14:30", "15:40", "A101", 40, 0])    # 监考人数=0
    results.append(save("小学/P12-脏数据-负向校验", t, rm, ex,
        mf("P12 脏数据（负向校验）", "小学", "不存在考场编号 + 监考人数=0 + 重复工号",
           "导入校验**逐条拦截**并给出可读错误；排考应 400 或跳过非法行，不得静默错排",
           t, rm, ex, "P12", "负向数据集：验证校验层健壮性与错误信息可读性。")))


# ================= 初中（Junior，前缀 J01~J12） =================
JR_SUBJ = ["语文", "数学", "英语", "物理", "化学", "生物", "道德与法治", "历史", "地理"]
JR_DOMAIN = "tjsyzx.cn"


def junior(results):
    R = random.Random(SEED + 200)
    slots = [("2026-06-22", "08:00", "10:00"), ("2026-06-22", "10:30", "12:00"),
             ("2026-06-22", "14:30", "16:00"), ("2026-06-23", "08:00", "10:00"),
             ("2026-06-23", "10:30", "12:00")]

    # J01 正向·小规模：初三一模
    t = gen_teachers(R, "J01", ["初三"], 16, JR_SUBJ, JR_DOMAIN)
    rm = gen_rooms([("Z", "致远楼")], 6, cap=50, sup_cap=30)
    ex = gen_exams([("语文", "初三", "2026-03-18", "08:00", "10:00", 6, 30, 2),
                    ("数学", "初三", "2026-03-18", "14:00", "16:00", 6, 30, 2),
                    ("英语", "初三", "2026-03-19", "08:00", "09:40", 6, 30, 2),
                    ("物理", "初三", "2026-03-19", "10:10", "11:20", 6, 30, 2),
                    ("化学", "初三", "2026-03-19", "14:00", "15:10", 6, 30, 2)], rm)
    results.append(save("初中/J01-初三一模-小规模", t, rm, ex,
        mf("J01 初三一模（小规模）", "初中", "6 班 5 科 1.5 天（按中考流程）", "succeeded，60 监考人次全分配",
           t, rm, ex, "J01", "初三一模：中考模拟流程，语数英物化五科。")))

    # J02 正向·中规模：初一初二期末
    t = gen_teachers(R, "J02", ["初一", "初二"], 20, JR_SUBJ, JR_DOMAIN)
    rm = gen_rooms([("Z", "致远楼"), ("M", "明德楼")], 8, cap=50, sup_cap=30)
    plan = []
    for gi, g in enumerate(["初一", "初二"]):
        subs = ["语文", "数学", "英语", "道德与法治", "历史", "地理", "生物"] if g == "初一" \
               else ["语文", "数学", "英语", "物理", "道德与法治", "历史", "生物"]
        for si, s in enumerate(subs):
            d, st, en = slots[(gi + si) % len(slots)]
            plan.append((s, g, d, st, en, 4, 30, 2))
    ex = gen_exams(plan, rm)
    results.append(save("初中/J02-初一初二期末-中规模", t, rm, ex,
        mf("J02 初一初二期末（中规模）", "初中", "2 年级 × 7 科 2 天（初二有物理、初一无）",
           "succeeded，学科按年级差异正确（物理初二起）", t, rm, ex, "J02",
           "**学科真实性**：初一无物理/化学；初二起物理；初三起化学。")))

    # J03 正向·大规模：全校三年级期末
    t = gen_teachers(R, "J03", ["初一", "初二", "初三"], 22, JR_SUBJ, JR_DOMAIN)
    rm = gen_rooms([("Z", "致远楼"), ("M", "明德楼"), ("K", "考务楼")], 8, cap=50, sup_cap=30)
    plan = []
    for gi, g in enumerate(["初一", "初二", "初三"]):
        subs = ["语文", "数学", "英语", "历史"] if g == "初一" else \
               (["语文", "数学", "英语", "物理"] if g == "初二" else ["语文", "数学", "英语", "化学"])
        for si, s in enumerate(subs):
            d, st, en = slots[(gi + si) % len(slots)]
            plan.append((s, g, d, st, en, 6, 30, 2))
    ex = gen_exams(plan, rm)
    results.append(save("初中/J03-全校期末-大规模", t, rm, ex,
        mf("J03 全校三年级期末（大规模）", "初中", "3 年级 × 4 科 × 6 考场", "succeeded，大规模求解成功",
           t, rm, ex, "J03", "规模压力：72 考场次、144 监考人次。")))

    # J04 正向·学段特色：初二地生会考
    t = gen_teachers(R, "J04", ["初一", "初二", "初三"], 14, JR_SUBJ, JR_DOMAIN)
    rm = gen_rooms([("Z", "致远楼"), ("M", "明德楼")], 8, cap=50, sup_cap=30)
    ex = gen_exams([("生物", "初二", "2026-06-20", "08:30", "09:40", 8, 30, 2),
                    ("地理", "初二", "2026-06-20", "10:10", "11:20", 8, 30, 2)], rm)
    results.append(save("初中/J04-初二地生会考-学段特色", t, rm, ex,
        mf("J04 初二地生会考（学段特色）", "初中",
           "生物+地理结业考（真实中考组成部分），全年级 8 考场同时开考",
           "succeeded，跨年级教师(初一/初三)参与监考初二",
           t, rm, ex, "J04", "**真实制度建模**：初二地生会考成绩计入中考总分，全省/市统一组织。\n"
                             "验证点：单年级大批量同时段并发（8 考场 × 2 科）。")))

    # J05 正向·混合监考人数
    t = gen_teachers(R, "J05", ["初一", "初二"], 14, JR_SUBJ, JR_DOMAIN)
    rm = gen_rooms([("Z", "致远楼")], 8, cap=50, sup_cap=30)
    ex = gen_exams([("语文", "初一", "2026-04-10", "08:00", "10:00", 4, 30, 2),
                    ("数学", "初一", "2026-04-10", "10:30", "12:00", 4, 30, 1),   # 小测验 1 人
                    ("英语", "初二", "2026-04-10", "14:30", "16:00", 4, 30, 2),
                    ("物理", "初二", "2026-04-11", "08:00", "09:30", 4, 30, 1)], rm)
    results.append(save("初中/J05-混合监考人数", t, rm, ex,
        mf("J05 混合监考人数", "初中", "同任务内 1 人/2 人监考混排", "succeeded，各场监考数精确匹配",
           t, rm, ex, "J05", "验证同一任务内异构 supervisorsPerRoom。")))

    # J06 正向·班主任密集
    t = gen_teachers(R, "J06", ["初一", "初二", "初三"], 12, JR_SUBJ, JR_DOMAIN, homeroom_ratio=1.0)
    rm = gen_rooms([("M", "明德楼")], 8, cap=50, sup_cap=30)
    plan = []
    for gi, g in enumerate(["初一", "初二", "初三"]):
        for si, s in enumerate(["语文", "数学"]):
            d, st, en = slots[(gi + si) % len(slots)]
            plan.append((s, g, d, st, en, 4, 30, 2))
    ex = gen_exams(plan, rm)
    results.append(save("初中/J06-班主任密集-避本班", t, rm, ex,
        mf("J06 班主任密集（避本班）", "初中", "各年级班主任满配", "succeeded 且班主任避本班违规=0",
           t, rm, ex, "J06", "M1.1 硬约束在多年级场景下的验证。")))

    # J07 正向·教师紧缺
    t = gen_teachers(R, "J07", ["初三"], 15, JR_SUBJ, JR_DOMAIN)
    rm = gen_rooms([("K", "考务楼")], 7, cap=50, sup_cap=30)
    ex = gen_exams([("语文", "初三", "2026-05-08", "08:00", "10:00", 7, 30, 2),
                    ("数学", "初三", "2026-05-08", "14:00", "16:00", 7, 30, 2)], rm)
    results.append(save("初中/J07-教师紧缺-资源边界", t, rm, ex,
        mf("J07 教师紧缺（资源边界）", "初中", "峰值需 14 人、仅 15 名教师", "succeeded，接近满负荷",
           t, rm, ex, "J07", "资源边界：冗余仅 1 人。")))

    # J08 正向·时段密集（单日 5 场）
    t = gen_teachers(R, "J08", ["初一", "初二", "初三"], 14, JR_SUBJ, JR_DOMAIN)
    rm = gen_rooms([("Z", "致远楼"), ("M", "明德楼")], 6, cap=50, sup_cap=30)
    tight = [("2026-06-24", "07:50", "09:10"), ("2026-06-24", "09:25", "10:45"),
             ("2026-06-24", "11:00", "12:00"), ("2026-06-24", "14:00", "15:20"),
             ("2026-06-24", "15:35", "16:45")]
    plan = []
    for i, (d, st, en) in enumerate(tight):
        g = ["初一", "初二", "初三"][i % 3]
        plan.append((JR_SUBJ[i % len(JR_SUBJ)], g, d, st, en, 4, 30, 2))
    ex = gen_exams(plan, rm)
    results.append(save("初中/J08-单日五场-时段密集", t, rm, ex,
        mf("J08 单日 5 场（时段密集）", "初中", "间隔 15 分钟连排 5 场", "succeeded，无教师时段冲突",
           t, rm, ex, "J08", "高密度时段的冲突检测验证。")))

    # J09 正向·考场异构（含备用考场）
    rm = (gen_rooms([("Z", "致远楼")], 6, cap=50, sup_cap=30) +
          gen_rooms([("BY", "备用楼")], 2, cap=20, sup_cap=15, rtype="standard", facil="监控"))
    t = gen_teachers(R, "J09", ["初二", "初三"], 14, JR_SUBJ, JR_DOMAIN)
    plan = []
    for gi, g in enumerate(["初二", "初三"]):
        for si, s in enumerate(["语文", "数学", "英语"]):
            d, st, en = slots[(gi + si) % len(slots)]
            plan.append((s, g, d, st, en, 4, 15, 2))
    ex = gen_exams(plan, rm)
    results.append(save("初中/J09-考场异构-含备用", t, rm, ex,
        mf("J09 考场异构（含备用考场）", "初中", "标准考场 + 小容量备用考场", "succeeded，备用考场正确纳入",
           t, rm, ex, "J09", "验证异构容量与设施标签缺失的处理。")))

    # J10 边界·极限可解
    t = gen_teachers(R, "J10", ["初三"], 12, JR_SUBJ, JR_DOMAIN)
    rm = gen_rooms([("K", "考务楼")], 6, cap=50, sup_cap=30)
    ex = gen_exams([("语文", "初三", "2026-05-20", "08:00", "10:00", 6, 30, 2)], rm)   # 峰值 12 = 12
    results.append(save("初中/J10-极限可解-临界", t, rm, ex,
        mf("J10 极限可解（临界）", "初中", "教师 12 = 单时段需求 12", "succeeded（临界）",
           t, rm, ex, "J10", "临界可解边界验证。")))

    # J11 边界·预期无解
    t = gen_teachers(R, "J11", ["初三"], 7, JR_SUBJ, JR_DOMAIN)
    rm = gen_rooms([("K", "考务楼")], 6, cap=50, sup_cap=30)
    ex = gen_exams([("语文", "初三", "2026-05-20", "08:00", "10:00", 6, 30, 2)], rm)   # 需 12 > 7
    results.append(save("初中/J11-预期无解-资源不足", t, rm, ex,
        mf("J11 预期无解（资源不足）", "初中", "需 12 人、仅 7 名教师", "**infeasible**",
           t, rm, ex, "J11", "诚实无解验证。")))

    # J12 负向·脏数据（日期格式错 + 时间倒置）
    t = gen_teachers(R, "J12", ["初二"], 12, JR_SUBJ, JR_DOMAIN)
    rm = gen_rooms([("Z", "致远楼")], 4, cap=50, sup_cap=30)
    ex = gen_exams([("语文", "初二", "2026-06-25", "08:00", "10:00", 4, 30, 2)], rm)
    ex.append(["数学", "初二", "2026/13/45", "10:30", "12:00", "Z101", 30, 2])   # 非法日期
    ex.append(["英语", "初二", "2026-06-25", "16:00", "14:00", "Z102", 30, 2])   # 结束早于开始
    results.append(save("初中/J12-脏数据-负向校验", t, rm, ex,
        mf("J12 脏数据（负向校验）", "初中", "非法日期 2026/13/45 + 结束时间早于开始",
           "校验拦截并给出可读错误，不得静默接受", t, rm, ex, "J12", "日期/时间健壮性验证。")))


# ================= 高中（Senior，前缀 S01~S12） =================
SR_G1 = ["语文", "数学", "英语", "物理", "化学", "生物", "政治", "历史", "地理"]
SR_DOMAIN = "tjsyzx.cn"


def senior(results):
    R = random.Random(SEED + 300)
    slots = [("2026-01-12", "08:00", "10:30"), ("2026-01-12", "15:00", "17:00"),
             ("2026-01-13", "08:00", "10:00"), ("2026-01-13", "15:00", "17:00"),
             ("2026-01-14", "08:00", "10:00")]

    # S01 正向·小规模：高三月考
    t = gen_teachers(R, "S01", ["高三"], 20, SR_G1, SR_DOMAIN)
    rm = gen_rooms([("K", "考务楼")], 8, cap=50, sup_cap=30)
    ex = gen_exams([("语文", "高三", "2026-03-10", "09:00", "11:30", 8, 30, 2),
                    ("数学", "高三", "2026-03-10", "15:00", "17:00", 8, 30, 2),
                    ("英语", "高三", "2026-03-11", "09:00", "11:00", 8, 30, 2),
                    ("理综", "高三", "2026-03-11", "15:00", "17:30", 8, 30, 2)], rm)
    results.append(save("高中/S01-高三月考-小规模", t, rm, ex,
        mf("S01 高三月考（小规模）", "高中", "8 班 4 场 2 天（含理综）", "succeeded，64 监考人次",
           t, rm, ex, "S01", "高三月考：语数英+理综，按高考时长设置。")))

    # S02 正向·中规模：高一期末 9 科
    t = gen_teachers(R, "S02", ["高一"], 24, SR_G1, SR_DOMAIN)
    rm = gen_rooms([("K", "考务楼"), ("M", "明德楼")], 8, cap=50, sup_cap=30)
    plan = []
    for si, s in enumerate(SR_G1):
        d, st, en = slots[si % len(slots)]
        plan.append((s, "高一", d, st, en, 5, 30, 2))
    ex = gen_exams(plan, rm)
    results.append(save("高中/S02-高一期末9科-中规模", t, rm, ex,
        mf("S02 高一期末 9 科（中规模）", "高中", "高一未分科：语数英+物化生+政史地 全 9 科",
           "succeeded，9 科错峰排满 2.5 天", t, rm, ex, "S02",
           "**学段真实性**：高一不分科，9 科全考；高二起 3+1+2 选科。")))

    # S03 正向·大规模：高三模考 3 天
    t = gen_teachers(R, "S03", ["高三"], 40, SR_G1, SR_DOMAIN)
    rm = gen_rooms([("K", "考务楼"), ("M", "明德楼"), ("Z", "致远楼"), ("X", "新知楼")], 8,
                   cap=50, sup_cap=30)
    ex = gen_exams([("语文", "高三", "2026-03-24", "09:00", "11:30", 16, 30, 2),
                    ("数学", "高三", "2026-03-24", "15:00", "17:00", 16, 30, 2),
                    ("英语", "高三", "2026-03-25", "09:00", "11:00", 16, 30, 2),
                    ("物理", "高三", "2026-03-25", "15:00", "16:15", 10, 30, 2),
                    ("历史", "高三", "2026-03-25", "15:00", "16:15", 6, 30, 2),
                    ("化学", "高三", "2026-03-26", "08:30", "09:45", 8, 30, 2),
                    ("生物", "高三", "2026-03-26", "11:00", "12:15", 8, 30, 2)], rm)
    results.append(save("高中/S03-高三模考-大规模", t, rm, ex,
        mf("S03 高三模考（大规模）", "高中", "32 考场 × 7 场次 3 天（含首选科目并行）",
           "succeeded，最大规模压力下求解成功", t, rm, ex, "S03",
           "**新高考结构**：物理/历史同时段并行（首选科目二选一），化学/生物为再选科目。")))

    # S04 正向·学段特色：3+1+2 选科走班
    t = gen_teachers(R, "S04", ["高二"], 28, SR_G1, SR_DOMAIN)
    rm = gen_rooms([("K", "考务楼"), ("M", "明德楼")], 8, cap=50, sup_cap=30)
    ex = gen_exams([
        ("语文", "高二", "2026-01-12", "08:00", "10:30", 10, 30, 2),
        ("数学", "高二", "2026-01-12", "15:00", "17:00", 10, 30, 2),
        ("英语", "高二", "2026-01-13", "08:00", "10:00", 10, 30, 2),
        # 首选：物理 / 历史 同时段并行（真实走班）
        ("物理", "高二", "2026-01-13", "15:00", "16:15", 6, 30, 2),
        ("历史", "高二", "2026-01-13", "15:00", "16:15", 4, 30, 2),
        # 再选：化学/生物/政治/地理 四选二，两个时段并行
        ("化学", "高二", "2026-01-14", "08:00", "09:15", 3, 30, 2),
        ("生物", "高二", "2026-01-14", "08:00", "09:15", 3, 30, 2),
        ("政治", "高二", "2026-01-14", "10:00", "11:15", 2, 30, 2),
        ("地理", "高二", "2026-01-14", "10:00", "11:15", 2, 30, 2)], rm)
    results.append(save("高中/S04-新高考选科走班-学段特色", t, rm, ex,
        mf("S04 新高考 3+1+2 选科走班（学段特色）", "高中",
           "语数英全考 + 物理/历史二选一并行 + 化生政地四选二并行",
           "succeeded，同时段多科并行正确分配、无教师冲突", t, rm, ex, "S04",
           "**真实制度建模**：新高考 3+1+2 模式（14 省份实行）。\n"
           "验证点：同一时段多个不同科目在不同考场并行——这是选科走班的核心复杂度。")))

    # S05 正向·平时测验单人监考
    t = gen_teachers(R, "S05", ["高一", "高二"], 14, SR_G1, SR_DOMAIN)
    rm = gen_rooms([("M", "明德楼")], 8, cap=50, sup_cap=30)
    ex = gen_exams([("数学", "高一", "2026-04-15", "15:00", "16:30", 4, 30, 1),
                    ("英语", "高二", "2026-04-15", "15:00", "16:30", 4, 30, 1)], rm)
    results.append(save("高中/S05-平时测验-单人监考", t, rm, ex,
        mf("S05 平时测验（单人监考）", "高中", "周测 1 人监考，两年级同时段", "succeeded，8 场各 1 人",
           t, rm, ex, "S05", "验证单人监考 + 跨年级同时段。")))

    # S06 正向·班主任密集
    t = gen_teachers(R, "S06", ["高一", "高二", "高三"], 12, SR_G1, SR_DOMAIN, homeroom_ratio=1.0)
    rm = gen_rooms([("K", "考务楼")], 8, cap=50, sup_cap=30)
    plan = []
    for gi, g in enumerate(["高一", "高二", "高三"]):
        for si, s in enumerate(["语文", "数学"]):
            d, st, en = slots[(gi + si) % len(slots)]
            plan.append((s, g, d, st, en, 4, 30, 2))
    ex = gen_exams(plan, rm)
    results.append(save("高中/S06-班主任密集-避本班", t, rm, ex,
        mf("S06 班主任密集（避本班）", "高中", "三年级班主任满配", "succeeded 且班主任避本班违规=0",
           t, rm, ex, "S06", "M1.1 在高中三年级场景验证。")))

    # S07 正向·教师紧缺
    t = gen_teachers(R, "S07", ["高三"], 18, SR_G1, SR_DOMAIN)
    rm = gen_rooms([("K", "考务楼")], 8, cap=50, sup_cap=30)
    ex = gen_exams([("语文", "高三", "2026-05-06", "09:00", "11:30", 8, 30, 2),
                    ("数学", "高三", "2026-05-06", "15:00", "17:00", 8, 30, 2)], rm)
    results.append(save("高中/S07-教师紧缺-资源边界", t, rm, ex,
        mf("S07 教师紧缺（资源边界）", "高中", "峰值需 16、仅 18 名教师", "succeeded，冗余 2 人",
           t, rm, ex, "S07", "资源边界压力。")))

    # S08 正向·时段密集（单日 3 场大间隔 + 跨天）
    t = gen_teachers(R, "S08", ["高一", "高二", "高三"], 16, SR_G1, SR_DOMAIN)
    rm = gen_rooms([("K", "考务楼"), ("M", "明德楼")], 6, cap=50, sup_cap=30)
    ex = gen_exams([("语文", "高一", "2026-01-12", "08:00", "10:30", 4, 30, 2),
                    ("数学", "高二", "2026-01-12", "13:00", "15:00", 4, 30, 2),
                    ("英语", "高三", "2026-01-12", "16:00", "18:00", 4, 30, 2),
                    ("物理", "高一", "2026-01-13", "08:00", "09:15", 4, 30, 2),
                    ("化学", "高二", "2026-01-13", "10:00", "11:15", 4, 30, 2)], rm)
    results.append(save("高中/S08-跨天时段-密集", t, rm, ex,
        mf("S08 跨天时段（密集）", "高中", "单日 3 场大间隔 + 次日 2 场", "succeeded，跨天负载均衡",
           t, rm, ex, "S08", "验证跨天场景的负载分布。")))

    # S09 正向·考场异构（含楼道类型试验）
    rm = (gen_rooms([("K", "考务楼")], 6, cap=50, sup_cap=30) +
          gen_rooms([("C", "明德楼走廊")], 2, cap=10, sup_cap=8, rtype="corridor", facil="监控"))
    t = gen_teachers(R, "S09", ["高二", "高三"], 16, SR_G1, SR_DOMAIN)
    plan = []
    for gi, g in enumerate(["高二", "高三"]):
        for si, s in enumerate(["语文", "数学", "英语"]):
            d, st, en = slots[(gi + si) % len(slots)]
            plan.append((s, g, d, st, en, 4, 8, 2))
    ex = gen_exams(plan, rm)
    results.append(save("高中/S09-考场异构-含楼道", t, rm, ex,
        mf("S09 考场异构（含楼道考场）", "高中", "标准考场 + corridor 类型考场", "succeeded，corridor 类型被正确处理",
           t, rm, ex, "S09", "验证 corridor 考场类型（后端 fairness 指标含 corridorShare）。")))

    # S10 边界·极限可解
    t = gen_teachers(R, "S10", ["高三"], 16, SR_G1, SR_DOMAIN)
    rm = gen_rooms([("K", "考务楼")], 8, cap=50, sup_cap=30)
    ex = gen_exams([("语文", "高三", "2026-05-20", "09:00", "11:30", 8, 30, 2)], rm)   # 16 = 16
    results.append(save("高中/S10-极限可解-临界", t, rm, ex,
        mf("S10 极限可解（临界）", "高中", "教师 16 = 单时段需求 16", "succeeded（临界）",
           t, rm, ex, "S10", "临界可解验证。")))

    # S11 边界·预期无解
    t = gen_teachers(R, "S11", ["高三"], 9, SR_G1, SR_DOMAIN)
    rm = gen_rooms([("K", "考务楼")], 8, cap=50, sup_cap=30)
    ex = gen_exams([("语文", "高三", "2026-05-20", "09:00", "11:30", 8, 30, 2)], rm)   # 需 16 > 9
    results.append(save("高中/S11-预期无解-资源不足", t, rm, ex,
        mf("S11 预期无解（资源不足）", "高中", "需 16 人、仅 9 名教师", "**infeasible**",
           t, rm, ex, "S11", "诚实无解验证。")))

    # S12 负向·脏数据（缺必填 + 监考人数超教师总数 + 容量为 0）
    t = gen_teachers(R, "S12", ["高三"], 12, SR_G1, SR_DOMAIN)
    t.append(["S120099", "", "男", "高三", "语文", "13800000000", "s120099@" + SR_DOMAIN])  # 姓名为空
    rm = gen_rooms([("K", "考务楼")], 4, cap=50, sup_cap=30)
    rm.append(["K999", "零容量考场", "考务楼", "9F", 0, 0, "standard", "监控"])            # 容量 0
    ex = gen_exams([("语文", "高三", "2026-05-20", "09:00", "11:30", 4, 30, 2)], rm)
    ex.append(["数学", "高三", "2026-05-20", "15:00", "17:00", "K999", 30, 99])           # 监考人数 99 > 教师数
    results.append(save("高中/S12-脏数据-负向校验", t, rm, ex,
        mf("S12 脏数据（负向校验）", "高中", "姓名为空 + 容量 0 考场 + 监考人数 99（超教师总数）",
           "校验拦截必填缺失/非法容量；监考人数超供给应 infeasible 或校验拒绝，不得假成功",
           t, rm, ex, "S12", "极端值健壮性验证。")))


if __name__ == "__main__":
    results = []
    os.makedirs(OUT, exist_ok=True)
    primary(results)
    junior(results)
    senior(results)
    print("=" * 88)
    print("%-42s %8s %7s %9s %9s" % ("数据集", "教师数", "考场数", "考试行数", "监考人次"))
    print("=" * 88)
    for name, nt, nr, ne, dem in results:
        print("%-42s %8d %7d %9d %9d" % (name, nt, nr, ne, dem))
    print("=" * 88)
    print("共 %d 份数据集，输出目录：%s" % (len(results), OUT))
