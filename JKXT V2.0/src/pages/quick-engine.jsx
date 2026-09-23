// ════════════════════════════════════════════════════════════════
// quick-engine — 一键排监考「真实前端闭环」内核
//   解析 (parse) → 校验 (validate) → 排班 (schedule) → 导出 (xlsx) → 任务 store
//   全部在浏览器内完成，依赖全局 XLSX (SheetJS)。不连接学校服务端/数据库。
//   暴露为 window.JKXT。
// ════════════════════════════════════════════════════════════════
(function () {
  const hasXLSX = () => typeof window.XLSX !== 'undefined';

  // ── 关键词匹配（容忍真实表头的各种写法）─────────────────────
  const matchCol = (headers, keys) => {
    const idx = headers.findIndex((h) =>
      keys.some((k) => String(h || '').replace(/\s/g, '').includes(k)));
    return idx;
  };

  // 三张表的字段契约：required 缺失→error；soft 缺失→warning
  const SLOT_SCHEMA = {
    t01: {
      label: '教师安排',
      required: [{ keys: ['姓名', '教师', 'name'], name: '姓名' }],
      soft:     [{ keys: ['性别', 'sex', 'gender'], name: '性别', note: '空缺时男女混合监考策略对该教师不生效' }],
    },
    t02: {
      label: '考场信息',
      required: [
        { keys: ['房间', '教室', '考场', 'room'], name: '房间' },
        { keys: ['容量', '座位', 'capacity', 'seat'], name: '容量' },
      ],
      soft: [{ keys: ['楼栋', '楼道', '楼', 'building'], name: '楼栋', note: '空缺时使用默认值，精确楼道分配需补全' }],
    },
    t03: {
      label: '考试安排',
      required: [
        { keys: ['科目', '课程', 'subject'], name: '科目' },
        { keys: ['日期', '考期', 'date'], name: '考试日期' },
      ],
      soft: [{ keys: ['人数', '考生', 'count', 'student'], name: '人数', note: '空缺时按考场容量估算座位需求' }],
    },
  };

  // ── 解析：File → { headers, rows, rowCount } ────────────────
  async function parseWorkbook(file) {
    if (!hasXLSX()) throw new Error('XLSX 库未就绪');
    const buf = await file.arrayBuffer();
    const wb = window.XLSX.read(buf, { type: 'array' });
    const ws = wb.Sheets[wb.SheetNames[0]];
    const aoa = window.XLSX.utils.sheet_to_json(ws, { header: 1, blankrows: false, defval: '' });
    if (!aoa.length) return { headers: [], rows: [], rowCount: 0 };
    const headers = aoa[0].map((h) => String(h).trim());
    const rows = aoa.slice(1).filter((r) => r.some((c) => String(c).trim() !== ''));
    return { headers, rows, rowCount: rows.length };
  }

  // ── 校验：真实结构检查 → { state, issue, meta } ─────────────
  function validateSlot(slotKey, parsed) {
    const schema = SLOT_SCHEMA[slotKey];
    const { headers, rows, rowCount } = parsed;

    if (!rowCount) {
      return { state: 'error', issue: { summary: '表内没有数据行', detail: '请确认上传的工作表第一行为表头、后续为数据。' } };
    }

    // 必填列缺失 → error
    const missingReq = schema.required.filter((c) => matchCol(headers, c.keys) === -1);
    if (missingReq.length) {
      return {
        state: 'error',
        issue: {
          summary: `缺少必填字段「${missingReq.map((c) => c.name).join('、')}」`,
          detail: `已识别表头：${headers.join(' / ') || '（空）'}\n请在源表补全后重新上传，否则无法排入。`,
        },
        meta: { rowCount, headers },
      };
    }

    // 必填列存在但有空单元格 → error（定位前几行）
    for (const c of schema.required) {
      const ci = matchCol(headers, c.keys);
      const emptyRows = [];
      rows.forEach((r, ri) => { if (String(r[ci] ?? '').trim() === '') emptyRows.push(ri + 2); });
      if (emptyRows.length) {
        return {
          state: 'error',
          issue: {
            summary: `「${c.name}」存在 ${emptyRows.length} 处空值`,
            detail: `第 ${emptyRows.slice(0, 6).join('、')}${emptyRows.length > 6 ? ' 等' : ''} 行为空，影响排入。请补全后重新上传。`,
          },
          meta: { rowCount, headers },
        };
      }
    }

    // soft 列缺失 / 空值 → warning（不阻断）
    const warns = [];
    for (const c of schema.soft) {
      const ci = matchCol(headers, c.keys);
      if (ci === -1) { warns.push(`未识别「${c.name}」列 · ${c.note}`); continue; }
      const empties = rows.filter((r) => String(r[ci] ?? '').trim() === '').length;
      if (empties) warns.push(`「${c.name}」有 ${empties} 处空值 · ${c.note}`);
    }
    if (warns.length) {
      return {
        state: 'warning',
        issue: { summary: warns[0].split(' · ')[0] + (warns.length > 1 ? ` 等 ${warns.length} 项提示` : ''), detail: warns.join('\n') },
        meta: { rowCount, headers },
      };
    }

    return { state: 'uploaded', issue: null, meta: { rowCount, headers } };
  }

  // ── 从已解析的三张表抽取结构化数据 ──────────────────────────
  function extractEntities(parsedBySlot) {
    const t01 = parsedBySlot.t01, t02 = parsedBySlot.t02, t03 = parsedBySlot.t03;
    const pick = (parsed, keys) => {
      if (!parsed) return -1;
      return matchCol(parsed.headers, keys);
    };

    // 教师
    let teachers = [];
    if (t01) {
      const ni = pick(t01, ['姓名', '教师', 'name']);
      const gi = pick(t01, ['年级', 'grade']);
      const si = pick(t01, ['性别', 'sex']);
      teachers = t01.rows.map((r, i) => ({
        name: String(r[ni] ?? `教师${i + 1}`).trim() || `教师${i + 1}`,
        grade: gi > -1 ? String(r[gi] ?? '').trim() : '',
        sex: si > -1 ? String(r[si] ?? '').trim() : '',
        count: 0,
      })).filter((t) => t.name);
    }

    // 考场
    let rooms = [];
    if (t02) {
      const ri = pick(t02, ['房间', '教室', '考场', 'room']);
      const bi = pick(t02, ['楼栋', '楼', 'building']);
      const ci = pick(t02, ['容量', '座位', 'capacity']);
      rooms = t02.rows.map((r, i) => ({
        room: String(r[ri] ?? `R-${i + 1}`).trim() || `R-${i + 1}`,
        building: bi > -1 ? String(r[bi] ?? '').trim() : '',
        capacity: ci > -1 ? Number(String(r[ci]).replace(/[^\d]/g, '')) || 40 : 40,
      }));
    }

    // 考试
    let exams = [];
    if (t03) {
      const di = pick(t03, ['日期', '考期', 'date']);
      const pi = pick(t03, ['时段', '时间', 'period']);
      const sj = pick(t03, ['科目', '课程', 'subject']);
      const cl = pick(t03, ['班级', 'class']);
      const cn = pick(t03, ['人数', '考生', 'count']);
      exams = t03.rows.map((r, i) => ({
        date: di > -1 ? String(r[di] ?? '').trim() : `11-${26 + (i % 2)}`,
        period: pi > -1 ? String(r[pi] ?? '').trim() : ['上午1', '上午2', '下午1'][i % 3],
        subject: sj > -1 ? String(r[sj] ?? '').trim() : `科目${i + 1}`,
        klass: cl > -1 ? String(r[cl] ?? '').trim() : '',
        students: cn > -1 ? Number(String(r[cn]).replace(/[^\d]/g, '')) || 40 : 40,
      }));
    }
    return { teachers, rooms, exams };
  }

  // ── 合成数据（载入示例 / 无真实文件时）──────────────────────
  function syntheticEntities(volume) {
    const big = String(volume) === '240';
    const surnames = '王李张刘陈杨黄赵周吴徐孙马朱胡郭何高林郑谢罗梁宋唐许韩冯邓曹彭'.split('');
    const givens = ['志强', '慧', '红', '睿明', '建国', '晓梅', '文涛', '思琪', '伟明', '慧君', '丽华', '志远', '明远', '雪', '磊', '婷', '浩然', '佳怡'];
    const nT = big ? 96 : 22;
    const teachers = Array.from({ length: nT }, (_, i) => ({
      name: surnames[i % surnames.length] + givens[i % givens.length],
      grade: ['初一', '初二', '初三', '高一', '高二', '高三'][i % 6],
      sex: i % 3 === 0 ? '女' : '男', count: 0,
    }));
    const nR = big ? 18 : 9;
    const rooms = Array.from({ length: nR }, (_, i) => ({
      room: `${'ABCD'[i % 4]}-${101 + i}`, building: `${'ABCD'[i % 4]} 栋`, capacity: [40, 36, 48, 30][i % 4],
    }));
    const subjects = ['语文', '数学', '英语', '物理', '化学', '生物', '政治', '历史', '地理'];
    const periods = ['上午1', '上午2', '下午1', '下午2'];
    const nE = big ? 24 : 8;
    const exams = Array.from({ length: nE }, (_, i) => ({
      date: `11-${26 + Math.floor(i / 4)}`, period: periods[i % 4],
      subject: subjects[i % subjects.length], klass: `${['初二', '初三', '高三'][i % 3]}${(i % 6) + 1}班`,
      students: 35 + (i % 4) * 4,
    }));
    return { teachers, rooms, exams };
  }

  // ── 排班：真实贪心分配（每场 2 名监考，工作量均衡轮转）──────
  function generateSchedule({ teachers, rooms, exams }, opts = {}) {
    const perExam = opts.perExam || 2;
    const T = teachers.length ? teachers.map((t) => ({ ...t, count: 0 })) : syntheticEntities('48').teachers;
    const R = rooms.length ? rooms : syntheticEntities('48').rooms;
    const E = exams.length ? exams : syntheticEntities('48').exams;

    // 监考教师池：按目标人均 ~2.3 场收敛规模，产生真实的工作量分布（而非人人 1 场）
    const totalSlots = E.length * perExam;
    const poolSize = Math.max(perExam, Math.min(T.length, Math.ceil(totalSlots / 2.3)));
    const pool = T.slice(0, poolSize);

    const rows = [];
    E.forEach((ex, ei) => {
      const room = R[ei % R.length];
      // 选当前监考次数最少的 perExam 名教师（工作量均衡贪心）
      const sorted = [...pool].sort((a, b) => a.count - b.count || (a._r = (a._r ?? Math.random())) - (b._r ?? (b._r = Math.random())));
      const chosen = sorted.slice(0, perExam);
      chosen.forEach((c) => { c.count += 1; });
      rows.push({
        date: ex.date, period: ex.period, subject: ex.subject,
        room: room.room, building: room.building,
        klass: ex.klass, students: ex.students,
        supervisors: chosen.map((c) => c.name),
      });
    });

    // 指标
    const counts = T.map((t) => t.count);
    const used = counts.filter((c) => c > 0);
    const avg = used.length ? (used.reduce((a, b) => a + b, 0) / used.length) : 0;
    const spread = used.length ? (Math.max(...used) - Math.min(...used)) : 0;
    const variance = used.length ? used.reduce((a, b) => a + (b - avg) ** 2, 0) / used.length : 0;
    const std = Math.sqrt(variance);

    return {
      rows,
      teachers: T,
      stats: {
        exams: E.length,
        supervisions: rows.reduce((a, r) => a + r.supervisors.length, 0),
        teachersUsed: used.length,
        rooms: new Set(rows.map((r) => r.room)).size,
        avg: avg.toFixed(1),
        spread,
        std: std.toFixed(2),
      },
    };
  }

  // ── 导出：schedule → 真实多 sheet xlsx → 浏览器下载 ─────────
  function _wb(schedule, task) {
    const XLSX = window.XLSX;
    const wb = XLSX.utils.book_new();

    // Sheet 1 · 监考安排总表
    const overall = [['日期', '时段', '科目', '考场', '楼栋', '班级', '考生数', '监考教师']];
    schedule.rows.forEach((r) => overall.push([r.date, r.period, r.subject, r.room, r.building, r.klass, r.students, r.supervisors.join('、')]));
    XLSX.utils.book_append_sheet(wb, XLSX.utils.aoa_to_sheet(overall), '监考安排总表');

    // Sheet 2 · 教师监考分表
    const byT = [['教师', '任教年级', '监考场次']];
    schedule.teachers.filter((t) => t.count > 0).sort((a, b) => b.count - a.count)
      .forEach((t) => byT.push([t.name, t.grade || '—', t.count]));
    XLSX.utils.book_append_sheet(wb, XLSX.utils.aoa_to_sheet(byT), '教师监考分表');

    // Sheet 3 · 考场安排分表
    const byR = {};
    schedule.rows.forEach((r) => { (byR[r.room] = byR[r.room] || []).push(`${r.date} ${r.period} ${r.subject}`); });
    const roomAoa = [['考场', '占用场次', '明细']];
    Object.entries(byR).forEach(([room, list]) => roomAoa.push([room, list.length, list.join('；')]));
    XLSX.utils.book_append_sheet(wb, XLSX.utils.aoa_to_sheet(roomAoa), '考场安排分表');

    // Sheet 4 · 考试安排分表
    const byE = [['日期', '时段', '科目', '班级', '考生数', '考场', '监考人数']];
    schedule.rows.forEach((r) => byE.push([r.date, r.period, r.subject, r.klass, r.students, r.room, r.supervisors.length]));
    XLSX.utils.book_append_sheet(wb, XLSX.utils.aoa_to_sheet(byE), '考试安排分表');

    // 元信息页
    const meta = [
      ['排监考结果', ''], ['任务编号', task?.id || '—'], ['学校', task?.school || orgName()],
      ['学期', task?.semester || '学期未提供'], ['生成时间', task?.createdAt || '生成时间未提供'],
      ['考试场次', schedule.stats.exams], ['监考人次', schedule.stats.supervisions],
      ['涉及教师', schedule.stats.teachersUsed], ['工作量标准差', schedule.stats.std],
      ['', ''], ['说明', '本文件由 JKXT v2.0 前端演示在浏览器内生成，数据来自本次上传/示例，未连接学校服务端。'],
    ];
    XLSX.utils.book_append_sheet(wb, XLSX.utils.aoa_to_sheet(meta), '说明');
    return wb;
  }

  // 单表导出（按 download card id）
  const SHEET_BY_ID = { overall: '监考安排总表', teacher: '教师监考分表', room: '考场安排分表', exam: '考试安排分表' };
  function downloadOne(schedule, task, cardId, title) {
    if (!hasXLSX()) { window.ui?.toast?.('导出组件未就绪', { kind: 'warning' }); return false; }
    const full = _wb(schedule, task);
    const name = SHEET_BY_ID[cardId];
    const single = window.XLSX.utils.book_new();
    if (name && full.Sheets[name]) window.XLSX.utils.book_append_sheet(single, full.Sheets[name], name);
    window.XLSX.utils.book_append_sheet(single, full.Sheets['说明'], '说明');
    window.XLSX.writeFile(single, `${title || name}_${task?.id || '排监考'}.xlsx`);
    return true;
  }
  // 全部导出（一个工作簿多 sheet）
  function downloadAll(schedule, task) {
    if (!hasXLSX()) { window.ui?.toast?.('导出组件未就绪', { kind: 'warning' }); return false; }
    window.XLSX.writeFile(_wb(schedule, task), `排监考结果_${task?.id || 'JK'}.xlsx`);
    return true;
  }

  // ── 任务 store（localStorage 持久化，按 id）──────────────────
  const KEY = 'jkxt:tasks';
  const readAll = () => { try { return JSON.parse(localStorage.getItem(KEY) || '[]'); } catch { return []; } };
  const writeAll = (list) => { try { localStorage.setItem(KEY, JSON.stringify(list)); } catch {} };

  function genTaskId() {
    const y = new Date().getFullYear();
    const list = readAll();
    const seq = String(list.length + 1).padStart(3, '0');
    return `JK-${y}-${seq}`;
  }

  function saveTask(task) {
    const list = readAll();
    const i = list.findIndex((t) => t.id === task.id);
    if (i > -1) list[i] = task; else list.unshift(task);
    writeAll(list);
    return task;
  }
  const getTask = (id) => readAll().find((t) => t.id === id) || null;
  const listTasks = () => readAll();
  const removeTask = (id) => { writeAll(readAll().filter((t) => t.id !== id)); };

  window.JKXT = {
    hasXLSX, parseWorkbook, validateSlot, extractEntities, syntheticEntities,
    generateSchedule, downloadOne, downloadAll, buildWorkbook: _wb,
    genTaskId, saveTask, getTask, listTasks, removeTask, SLOT_SCHEMA,
  };
})();
