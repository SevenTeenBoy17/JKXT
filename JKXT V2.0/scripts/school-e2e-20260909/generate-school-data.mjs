#!/usr/bin/env node
// Generates synthetic OOXML inputs only. Never calls an API or writes to a database.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const OUT = path.resolve(ROOT, 'artifacts/school-e2e-20260909/data');
const CORRECTION = process.argv.slice(2).includes('--correct-reviewed-seat-enum');
assert.ok(process.argv.slice(2).every(arg => arg === '--correct-reviewed-seat-enum'), 'Unsupported generator argument');
const XLSX = require(path.join(ROOT, 'assets/vendor/xlsx.full.0.18.5.min.js'));
const FIXED_DATE = new Date('2026-09-09T00:00:00Z');
const SYNTHETIC = '合成资料 · 仅用于隔离验收 · 不是已核实的真实个人信息';
const HEADERS = {
  teachers: ['工号', '姓名', '性别', '年级', '任教学科', '手机号', '邮箱', '班主任', '所带班级'],
  classrooms: ['考场编号', '考场名称', '楼栋', '楼层', '容量', '考试容量', '考场类型', '设施标签'],
  classes: ['班级编号', '班级名称', '年级', '学段', '学生数', '开设科目', '班主任工号'],
  subjects: ['科目编号', '科目名称', '类别', '考试类型', '周课时', '颜色'],
  exams: ['科目', '年级', '考试日期', '开始时间', '结束时间', '考场编号', '人数', '监考人数', '班级'],
};
const files = [];
const checks = [];
const sha = b => crypto.createHash('sha256').update(b).digest('hex');
const pad = n => String(n).padStart(3, '0');
const safe = rel => {
  const target = path.resolve(OUT, rel);
  if (!target.startsWith(`${OUT}${path.sep}`)) throw new Error(`Outside data lease: ${rel}`);
  return target;
};
function checkedWrite(rel, bytes) {
  const target = safe(rel);
  if (!fs.existsSync(target)) return fs.writeFileSync(target, bytes, { flag: 'wx' });
  const existingHash = sha(fs.readFileSync(target));
  if (existingHash === sha(bytes)) return;
  const correctedPaths = ['joint-round-1-by-code.json', 'joint-round-2-by-code.json', 'manifest.json'];
  const checkpoint = safe(`checkpoints/pre-seat-enum-20260909/${rel}`);
  assert.ok(CORRECTION && correctedPaths.includes(rel) && fs.existsSync(checkpoint), `Refusing to overwrite changed artifact: ${rel}`);
  assert.equal(existingHash, sha(fs.readFileSync(checkpoint)), `Corrected target differs from frozen checkpoint: ${rel}`);
  fs.writeFileSync(target, bytes);
}
function save(rel, contents) {
  const target = safe(rel);
  fs.mkdirSync(path.dirname(target), { recursive: true });
  const bytes = Buffer.isBuffer(contents) ? contents : Buffer.from(contents, 'utf8');
  // A normal repeat must not overwrite historical or edited evidence silently.
  checkedWrite(rel, bytes);
  files.push({ path: rel, bytes: bytes.length, sha256: sha(bytes) });
  return target;
}
const saveJson = (rel, data) => save(rel, `${JSON.stringify(data, null, 2)}\n`);
function check(id, condition, details) {
  checks.push({ id, pass: Boolean(condition), details });
  assert.ok(condition, `${id}: ${JSON.stringify(details)}`);
}
function workbook(rel, sheets) {
  const wb = XLSX.utils.book_new();
  wb.Props = { Title: `隔离学校验收 ${SYNTHETIC}`, Subject: SYNTHETIC, Author: 'JKXT synthetic acceptance', CreatedDate: FIXED_DATE, ModifiedDate: FIXED_DATE };
  for (const [name, values] of sheets) {
    const ws = XLSX.utils.aoa_to_sheet(values);
    ws['!cols'] = values[0].map((_, i) => ({ wch: Math.min(58, Math.max(14, ...values.slice(0, 121).map(r => String(r[i] ?? '').length * 1.55 + 2))) }));
    if (values.length > 1) ws['!autofilter'] = { ref: XLSX.utils.encode_range({ r: 0, c: 0 }, { r: values.length - 1, c: values[0].length - 1 }) };
    XLSX.utils.book_append_sheet(wb, ws, name);
  }
  const bytes = XLSX.write(wb, { type: 'buffer', bookType: 'xlsx', compression: true });
  const target = save(rel, bytes);
  const read = XLSX.read(fs.readFileSync(target), { type: 'buffer', cellDates: false, cellFormula: true });
  check(`${rel}:ooxml`, bytes[0] === 0x50 && bytes[1] === 0x4b, 'ZIP OOXML header');
  check(`${rel}:sheets`, JSON.stringify(read.SheetNames) === JSON.stringify(sheets.map(s => s[0])), read.SheetNames);
  for (const [name, values] of sheets) {
    const actual = XLSX.utils.sheet_to_json(read.Sheets[name], { header: 1, defval: '', raw: true });
    assert.deepEqual(actual, values, `${rel}/${name} exact readback`);
    check(`${rel}:${name}:values`, true, { rows: actual.length - 1, columns: values[0].length });
    check(`${rel}:${name}:no-formulas`, Object.values(read.Sheets[name]).every(cell => !cell || typeof cell !== 'object' || !('f' in cell)), 'No executable spreadsheet formula');
  }
  return XLSX.utils.sheet_to_json(read.Sheets[read.SheetNames[0]], { header: 1, defval: '', raw: true }).slice(1);
}
function importWorkbook(rel, kind, rows, warning = '') {
  return workbook(rel, [
    ['导入数据', [HEADERS[kind], ...rows]],
    ['使用说明', [['项目', '说明'], ['数据属性', SYNTHETIC], ['导入类型', kind], ['注意', warning || '首个工作表为标准导入字段；本说明页不应作为数据导入。'], ['权限', '只能用于本轮隔离学校；禁止写入原有学校或真实人员账户。']]],
  ]);
}

const subjectNames = ['语文', '数学', '英语', '物理', '化学', '生物', '政治', '历史', '地理'];
const subjects = subjectNames.map((name, i) => ({ code: `SYN-S${pad(i + 1)}`, name, category: i < 3 ? 'main' : 'secondary', examType: 'written', hours: i < 3 ? 5 : 3, color: ['rose', 'indigo', 'sky', 'violet', 'emerald', 'lime', 'amber', 'orange', 'cyan'][i] }));
const grades = ['高一', '高二', '高三'];
const classes = Array.from({ length: 30 }, (_, i) => ({
  code: `SYN-G${10 + i % 3}-C${String(Math.floor(i / 3) + 1).padStart(2, '0')}`,
  name: `合成${grades[i % 3]}${Math.floor(i / 3) + 1}班`, grade: grades[i % 3], stage: '高中',
  studentCount: 36 + i % 10, homeroomTeacherNo: `SYN-T${pad(i + 1)}`,
}));
const rooms = Array.from({ length: 36 }, (_, i) => ({
  code: `SYN-R${pad(i + 1)}`, name: `${i < 30 ? '合成常规' : '合成备用'}考场${pad(i + 1)}`,
  building: i < 18 ? '合成明德楼' : '合成求实楼', floor: `${1 + Math.floor((i % 18) / 6)}F`,
  capacity: i >= 34 ? 30 : 54, examCapacity: i >= 34 ? 24 : 48,
  roomType: i === 34 ? 'lab' : i === 35 ? 'meeting' : 'standard',
  features: ['广播', '监控', ...(i % 6 === 0 ? ['ACCESSIBLE'] : [])], campus: 'MAIN',
}));
const roundDates = [['2026-11-16', '2026-11-17', '2026-11-18'], ['2026-11-23', '2026-11-24', '2026-11-25']];
const intervals = roundDates.flatMap(dates => dates.flatMap(date => [
  { start: `${date}T08:30:00`, end: `${date}T10:30:00`, reason: '合成考试工作窗口' },
  { start: `${date}T14:00:00`, end: `${date}T16:00:00`, reason: '合成考试工作窗口' },
]));
const surnames = ['张', '王', '李', '赵', '陈', '刘', '周', '吴', '郑', '孙', '徐', '林'];
const given = ['明远', '知行', '静怡', '文博', '雅宁', '思齐', '景然', '书涵', '嘉言', '安和'];
const teachers = Array.from({ length: 120 }, (_, i) => {
  const n = i + 1;
  const teacherNo = `SYN-T${pad(n)}`;
  const subjectIndexes = [i % 9, ...(n % 20 === 0 ? [(i + 4) % 9] : [])];
  const leaveWindows = n % 10 === 1 ? [intervals[0], intervals[6]].map(x => ({ ...x, reason: '合成请假：教研活动' })) : [];
  if (n % 19 === 0) leaveWindows.push({ ...intervals[9], reason: '合成第二轮新增请假' });
  const qualifications = ['INVIGILATOR', ...(n % 5 ? ['CHIEF'] : [])];
  const profile = {
    teacherNo, name: `合成·${surnames[i % 12]}${given[Math.floor(i / 12)]}${pad(n)}`,
    gender: n % 2 ? 'female' : 'male', grade: grades[i % 3], phone: null, email: null,
    status: n > 118 ? 'left' : n > 116 ? 'disabled' : 'active',
    age: 24 + (n * 7) % 39,
    position: n % 30 === 0 ? '副校长' : n % 20 === 0 ? '教务主任' : n % 12 === 0 ? '年级组长' : n % 9 === 0 ? '教研组长' : i < 30 ? '班主任' : '教师',
    leaveWindows,
    dutyWindows: n % 15 === 0 ? intervals.filter(x => x.start.includes('T08:30')).map(x => ({ ...x, reason: '合成仅上午可用' })) : [],
    functionalAccommodations: n % 10 === 0 ? ['合成需求：不连续站立，日监考最多一场'] : [],
    qualifications, qualificationExpiry: n % 17 === 0 ? '2026-09-01' : '2028-12-31',
    maxDailySessions: n % 10 === 0 ? 1 : 2, preferredCampus: 'MAIN', isHomeroom: i < 30,
  };
  return { teacherNo, subjectCodes: subjectIndexes.map(s => subjects[s].code), subjectNames: subjectIndexes.map(s => subjects[s].name), homeroomClassCode: i < 30 ? classes[i].code : null, payload: profile };
});
const candidates = classes.flatMap(c => Array.from({ length: c.studentCount }, (_, i) => ({ candidateCode: `${c.code}-S${pad(i + 1)}`, name: `合成考生${c.code.slice(4)}-${pad(i + 1)}`, classCode: c.code, attributes: { synthetic: true } })));

const profileJson = {
  synthetic: true, label: SYNTHETIC, schoolName: '合成明德中学·隔离验收', timezone: 'Asia/Shanghai',
  mappings: { teacherNo: 'GET /teachers → uuid', subjectCodes: 'GET /subjects code → uuid → payload.subjectIds', homeroomClassCode: 'GET /classes code → uuid → payload.homeroomClassId' },
  instruction: 'payload尚未包含关系UUID。必须从同一隔离租户读回并补上subjectIds和homeroomClassId再调用正规API；不得跳过关系或把code写为UUID。',
  teachers,
};
const roundExams = roundDates.map((dates, round) => Array.from({ length: 6 }, (_, slot) => classes.map((c, i) => {
  const subject = subjects[(slot + (i % 3) * 3 + round) % 9];
  const date = dates[Math.floor(slot / 2)];
  return { code: `SYN-R${round + 1}-E${pad(slot * 30 + i + 1)}`, subjectCode: subject.code, subjectName: subject.name, grade: c.grade, examDate: date, startTime: slot % 2 ? '14:00' : '08:30', endTime: slot % 2 ? '16:00' : '10:30', roomCode: rooms[(i + round * 3) % 30].code, seatDemand: c.studentCount, supervisorsPerRoom: 2, classCode: c.code, slot };
})).flat());
const examRows = exams => exams.map(e => [e.subjectName, e.grade, e.examDate, e.startTime, e.endTime, e.roomCode, e.seatDemand, e.supervisorsPerRoom, e.classCode]);

function makeJoint(exams, round) {
  const byClass = new Map(classes.map(c => [c.code, candidates.filter(s => s.classCode === c.code)]));
  return {
    synthetic: true, label: SYNTHETIC, mappingRequired: true, round,
    mappings: { teacherNo: 'teacherId', subjectCode: 'subjectId', classCode: 'classId', roomCode: 'classroomId' },
    note: 'Each resource item uses stable code references where the API requires UUIDs. Resolve via this isolated tenant, remove code-only fields, then send through supported bulk API. No output assignments are included.',
    resources: {
      jointDemands: exams.map(e => ({ demandCode: e.code, name: `合成${e.grade}${e.subjectName}-${e.code}`, subjectCode: e.subjectCode, durationMinutes: 120, candidateCount: e.seatDemand, invigilatorCount: 2, minRooms: 1, maxRooms: 1, splitAllowed: false, requiredRoomFeatures: [], settings: { chiefInvigilatorCount: 1, preferredCampus: 'MAIN', lockLevel: 'TIME_LOCKED', lockedWindowCode: `${e.code}-W1`, synthetic: true } })),
      jointDemandClasses: exams.map(e => ({ demandCode: e.code, classCode: e.classCode, expectedCandidates: e.seatDemand, settings: {} })),
      jointDemandWindows: exams.map(e => ({ demandCode: e.code, windowCode: `${e.code}-W1`, startsAt: `${e.examDate}T${e.startTime}:00+08:00`, endsAt: `${e.examDate}T${e.endTime}:00+08:00`, preferenceWeight: 100, locked: true, settings: {} })),
      jointCandidates: candidates,
      jointEnrollments: exams.flatMap(e => byClass.get(e.classCode).map(c => ({ candidateCode: c.candidateCode, demandCode: e.code, status: 'ENROLLED', settings: {} }))),
      jointCandidateWindows: [], jointSpecialRequirements: [],
      jointRoomPool: rooms.map(r => ({ roomCode: r.code, campusCode: r.campus, examCapacity: r.examCapacity, features: r.features, status: 'INCLUDED', settings: {} })),
      jointRoomWindows: [],
      jointSeatTopology: rooms.flatMap(r => Array.from({ length: r.examCapacity }, (_, i) => ({ roomCode: r.code, seatCode: `S${pad(i + 1)}`, rowNo: Math.floor(i / 6) + 1, columnNo: i % 6 + 1, features: [], status: 'ACTIVE' }))),
      jointTeacherPool: teachers.map(t => ({ teacherNo: t.teacherNo, eligibleRoles: t.payload.qualifications, maxDailySessions: t.payload.maxDailySessions, minRestMinutes: t.payload.maxDailySessions === 1 ? 120 : 30, preferredCampus: 'MAIN', status: t.payload.status === 'active' ? 'INCLUDED' : 'EXCLUDED', preferences: { synthetic: true, sourceTeacherNo: t.teacherNo } })),
      jointAvoidances: teachers.flatMap(t => exams.filter(e => t.payload.grade === e.grade || t.subjectCodes.includes(e.subjectCode) || t.homeroomClassCode === e.classCode).map(e => ({ teacherNo: t.teacherNo, targetType: 'DEMAND', demandCode: e.code, severity: 'HARD', reason: t.homeroomClassCode === e.classCode ? '合成硬约束：班主任不监本班' : t.payload.grade === e.grade ? '合成硬约束：同年级回避' : '合成硬约束：任教学科回避', details: {} }))),
      jointCampusTravel: [],
    },
  };
}

// Independent feasibility oracle: bipartite matching by exam period; not product code
// and never supplied as planner output. Daily quotas are carried between periods.
function witness(exams) {
  const result = [];
  const total = new Map();
  const daily = new Map();
  const minEligible = [];
  for (let slot = 0; slot < 6; slot++) {
    const duties = exams.filter(e => e.slot === slot).flatMap(e => ['CHIEF', 'INVIGILATOR'].map(role => ({ e, role })));
    const choices = duties.map(({ e, role }) => teachers.filter(t => {
      const p = t.payload, start = `${e.examDate}T${e.startTime}:00`, end = `${e.examDate}T${e.endTime}:00`;
      return p.status === 'active' && p.qualifications.includes(role) && p.qualificationExpiry >= e.examDate
        && p.grade !== e.grade && !t.subjectCodes.includes(e.subjectCode) && t.homeroomClassCode !== e.classCode
        && !p.leaveWindows.some(w => w.start < end && w.end > start)
        && (!p.dutyWindows.length || p.dutyWindows.some(w => w.start <= start && w.end >= end))
        && (daily.get(`${t.teacherNo}|${e.examDate}`) || 0) < p.maxDailySessions;
    }).sort((a, b) => (total.get(a.teacherNo) || 0) - (total.get(b.teacherNo) || 0) || a.teacherNo.localeCompare(b.teacherNo)));
    minEligible.push(Math.min(...choices.map(c => c.length)));
    const teacherToDuty = new Map();
    function augment(index, visited) {
      for (const t of choices[index]) {
        if (visited.has(t.teacherNo)) continue;
        visited.add(t.teacherNo);
        if (!teacherToDuty.has(t.teacherNo) || augment(teacherToDuty.get(t.teacherNo), visited)) {
          teacherToDuty.set(t.teacherNo, index); return true;
        }
      }
      return false;
    }
    const order = duties.map((_, i) => i).sort((a, b) => choices[a].length - choices[b].length);
    for (const index of order) assert.ok(augment(index, new Set()), `No independent matching for ${duties[index].e.code}/${duties[index].role}`);
    for (const [teacherNo, index] of teacherToDuty) {
      const { e, role } = duties[index];
      result.push({ examCode: e.code, teacherNo, role, date: e.examDate, start: e.startTime, end: e.endTime, roomCode: e.roomCode });
      total.set(teacherNo, (total.get(teacherNo) || 0) + 1);
      daily.set(`${teacherNo}|${e.examDate}`, (daily.get(`${teacherNo}|${e.examDate}`) || 0) + 1);
    }
  }
  return { assignments: result, minEligibleBySlot: minEligible, usedTeachers: total.size, range: [Math.min(...teachers.map(t => total.get(t.teacherNo) || 0)), Math.max(...total.values())] };
}

function verifyReadbacks(read) {
  check('teachers:120-unique', read.teachers.length === 120 && new Set(read.teachers.map(r => r[0])).size === 120, 120);
  check('teachers:synthetic-contact-free', read.teachers.every(r => String(r[1]).startsWith('合成·') && r[5] === '' && r[6] === ''), SYNTHETIC);
  check('teachers:grade-balance', grades.every(g => read.teachers.filter(r => r[3] === g).length === 40), '40 per grade');
  check('teachers:homeroom', read.teachers.filter(r => r[7] === '是').length === 30, 30);
  const classCodes = new Set(read.classes.map(r => r[0])), roomCodes = new Map(read.rooms.map(r => [r[0], r[5]])), subjectSet = new Set(read.subjects.map(r => r[1]));
  const teacherCodes = new Set(read.teachers.map(r => r[0]));
  check('classes:unique-and-homeroom', classCodes.size === 30 && read.classes.every(r => teacherCodes.has(r[6])), 30);
  check('teachers:class-reference', read.teachers.every(r => !r[8] || classCodes.has(r[8])), '0 orphan homeroom classes');
  check('subjects:9-and-linked', subjectSet.size === 9 && read.teachers.every(r => r[4].split(',').every(s => subjectSet.has(s))), 9);
  check('rooms:36-valid-capacities', roomCodes.size === 36 && read.rooms.every(r => Number.isInteger(r[4]) && r[4] >= r[5] && r[5] > 0), '36 rooms; 30 ordinary,6 reserve');
  for (const [index, rows] of read.rounds.entries()) {
    check(`round-${index + 1}:180-rows-360-duties`, rows.length === 180 && rows.reduce((sum, r) => sum + r[7], 0) === 360, { exams: rows.length, duties: rows.reduce((s, r) => s + r[7], 0) });
    check(`round-${index + 1}:references-and-capacities`, rows.every(r => subjectSet.has(r[0]) && classCodes.has(r[8]) && roomCodes.has(r[5]) && Number.isInteger(r[6]) && r[6] <= roomCodes.get(r[5])), '0 orphan references; 0 capacity overflow');
    const slots = new Map();
    for (const r of rows) {
      assert.ok(/^2026-11-\d{2}$/.test(r[2]) && r[3] < r[4]);
      const key = `${r[2]}|${r[3]}`;
      slots.set(key, (slots.get(key) || 0) + 1);
    }
    check(`round-${index + 1}:6-slots-30-rooms`, slots.size === 6 && [...slots.values()].every(v => v === 30), Object.fromEntries(slots));
    let conflicts = 0;
    for (let i = 0; i < rows.length; i++) for (let j = i + 1; j < rows.length; j++) {
      const a = rows[i], b = rows[j];
      if (a[2] === b[2] && (a[5] === b[5] || a[8] === b[8]) && a[3] < b[4] && b[3] < a[4]) conflicts++;
    }
    check(`round-${index + 1}:room-class-no-overlap`, conflicts === 0, conflicts);
  }
}

const read = {};
read.subjects = importWorkbook('shared/subjects.xlsx', 'subjects', subjects.map(s => [s.code, s.name, s.category, s.examType, s.hours, s.color]));
read.teachers = importWorkbook('shared/teachers.xlsx', 'teachers', teachers.map(t => [t.teacherNo, t.payload.name, t.payload.gender, t.payload.grade, t.subjectNames.join(','), '', '', t.payload.isHomeroom ? '是' : '否', t.homeroomClassCode || '']), '资格/请假/日上限等不在此导入器支持范围。必须另用正规API按 teacher-profiles.json 配置并读回。');
read.classes = importWorkbook('shared/classes.xlsx', 'classes', classes.map(c => [c.code, c.name, c.grade, c.stage, c.studentCount, subjectNames.join(','), c.homeroomTeacherNo]));
read.rooms = importWorkbook('shared/classrooms.xlsx', 'classrooms', rooms.map(r => [r.code, r.name, r.building, r.floor, r.capacity, r.examCapacity, r.roomType, r.features.join(',')]));
read.rounds = roundExams.map((exams, i) => importWorkbook(`round-${i + 1}/exams.xlsx`, 'exams', examRows(exams)));
verifyReadbacks(read);
saveJson('teacher-profiles.json', profileJson);
for (let i = 0; i < 2; i++) {
  const graph = makeJoint(roundExams[i], i + 1);
  check(`round-${i + 1}:joint-seat-api-enum`, graph.resources.jointSeatTopology.every(s => ['ACTIVE', 'DISABLED'].includes(s.status)), 'JointInputWriteService.seats contract');
  check(`round-${i + 1}:joint-counts`, graph.resources.jointDemands.length === 180 && graph.resources.jointCandidates.length === 1215 && graph.resources.jointEnrollments.length === 7290, '180 demands;1215 candidates;7290 enrollments');
  check(`round-${i + 1}:joint-demands-references`, graph.resources.jointDemands.every(d => subjects.some(s => s.code === d.subjectCode)), 'all subject code references resolve');
  check(`round-${i + 1}:joint-avoidances-unique`, new Set(graph.resources.jointAvoidances.map(a => `${a.teacherNo}|${a.demandCode}`)).size === graph.resources.jointAvoidances.length, 'one avoidance per teacher/demand');
  const feasible = witness(roundExams[i]);
  check(`round-${i + 1}:independent-witness-360`, feasible.assignments.length === 360, { minimumRoleCandidates: feasible.minEligibleBySlot, usedTeachers: feasible.usedTeachers });
  saveJson(`joint-round-${i + 1}-by-code.json`, graph);
  saveJson(`round-${i + 1}/feasibility-witness-NOT-SYSTEM-OUTPUT.json`, { synthetic: true, label: '独立输入可行性见证；非系统输出，禁止导入系统', ...feasible });
  saveJson(`round-${i + 1}/exams-by-code.json`, roundExams[i]);
}

const negatives = [
  { id: 'missing-teacher-key', kind: 'teachers', rows: read.teachers.map((r, i) => i === 0 ? ['', ...r.slice(1)] : r), expected: { phase: 'validate', blocking: true, code: 'IMPORT_REQUIRED_EMPTY' } },
  { id: 'duplicate-teacher-key', kind: 'teachers', rows: [...read.teachers, read.teachers[0]], expected: { phase: 'commit', blocking: false, warning: true, rows: 121, uniqueTeachers: 120, skipped: 1, rule: 'duplicate-natural-key warning, no 121st teacher' } },
  { id: 'duplicate-exam-row', kind: 'exams', rows: [...read.rounds[1], read.rounds[1][0]], expected: { phase: 'validate', blocking: true, code: 'EXAM_ROOM_ROW_DUPLICATE' } },
  { id: 'overlapping-room', kind: 'exams', rows: [...read.rounds[1], ['语文', '高二', roundDates[1][0], '09:00', '11:00', 'SYN-R004', 40, 2, classes[1].code]], expected: { phase: 'validate', blocking: true, code: 'EXAM_ROOM_TIME_CONFLICT' } },
  { id: 'negative-room-capacity', kind: 'classrooms', rows: read.rooms.map((r, i) => i === 0 ? [...r.slice(0, 4), -1, ...r.slice(5)] : r), expected: { phase: 'validate', blocking: true, code: 'NEGATIVE_NUMBER' } },
  { id: 'unknown-room-all', kind: 'exams', rows: read.rounds[1].map(r => [...r.slice(0, 5), `BAD-${r[5]}`, ...r.slice(6)]), expected: { phase: 'commit', blocking: true, code: 'EXAM_IMPORT_NO_VALID_ROW', invariant: 'existing task exams and assignments unchanged' } },
  { id: 'unknown-class', kind: 'exams', rows: [[...read.rounds[1][0].slice(0, 8), 'SYN-NOT-A-CLASS']], expected: { phase: 'commit', blocking: false, warning: true, invariant: 'surface missing binding; do not claim homeroom avoidance is supported for missing class', productRisk: 'bad class reference currently warns and skips class linkage' } },
  { id: 'inverted-exam-time', kind: 'exams', rows: [[...read.rounds[1][0].slice(0, 3), '16:00', '14:00', ...read.rounds[1][0].slice(5)]], expected: { phase: 'validate', blocking: true, code: 'EXAM_TIME_RANGE_INVALID' } },
  { id: 'infeasible-144-same-time-duties', kind: 'exams', rows: rooms.map((r, i) => ['语文', grades[i % 3], '2026-12-01', '08:30', '10:30', r.code, Math.min(24, r.examCapacity), 4, '']), expected: { phase: 'solve', blocking: true, requiredDuties: 144, rosterUpperBound: 120, mathematicalProof: '144 simultaneous distinct posts exceed the full 120-person roster; no double-booking allowed' } },
];
for (const n of negatives) importWorkbook(`negative-cases/${n.id}.xlsx`, n.kind, n.rows, `负例，不用于正常导入。预期阶段=${n.expected.phase}；${JSON.stringify(n.expected)}`);
saveJson('negative-cases/expected-results.json', negatives.map(({ id, kind, expected }) => ({ id, kind, file: `negative-cases/${id}.xlsx`, expected })));
saveJson('round-2/scenarios.json', {
  synthetic: true, round: 2,
  executionNote: '全部仅在隔离学校，通过公开业务API运行；先收集实际分配UUID，再选择改派/替补对象。不得直接SQL写审批或把见证当输出。',
  cases: [
    { id: 'R2-01', title: '文件内重复工号不增人', fixture: 'negative-cases/duplicate-teacher-key.xlsx', verify: ['counts+warning', 'GET教师数仍120'] },
    { id: 'R2-02', title: '无效考试重导入保护已存记录', fixture: 'negative-cases/unknown-room-all.xlsx', verify: ['HTTP400', '考试ID与排班ID集合未变'] },
    { id: 'R2-03', title: '同一时段超额岗位必须无解', fixture: 'negative-cases/infeasible-144-same-time-duties.xlsx', verify: ['无解或阻断', '无发布和0冲突伪成功'] },
    { id: 'R2-04', title: '无主监考资格者不能改派为CHIEF', chooseTeacher: '资格仅INVIGILATOR且当前可用', verify: ['服务器拒绝', '原岗位教师ID保持'] },
    { id: 'R2-05', title: '请假/仅上午教师不能改派下午', chooseTeacher: 'teacher-profiles对应leaveWindows/dutyWindows', verify: ['拒绝码', '原岗位不变'] },
    { id: 'R2-06', title: '有效候选合法改派并刷新读回', chooseTeacher: '根据实际求解空闲且全部约束通过的候选，不固定伪造ID', verify: ['版本游标增加', 'assignment teacherId刷新后相同'] },
    { id: 'R2-07', title: '独立审批人批准后才能发布', verify: ['提交人/审批人不同', '变更后重新审批', 'PUBLISHED版本与审计一致'] },
    { id: 'R2-08', title: '发布后请假触发替补并通知站内收件箱', verify: ['真实leave/replacement状态', '旧岗位更新与新教师读回', '通知不对外投递'] },
    { id: 'R2-09', title: '当前组织以外的ID必须拒绝', verify: ['HTTP403或404', '两租户数据不变'] },
  ],
});

workbook('配置指导-不可直接导入.xlsx', [
  ['教师资格与约束', [['数据标签', '工号', '姓名', '职务', '年龄', '状态', '资格', '资格有效期', '每日最多场次', '仅上午可用', '请假区间', '功能性安排'], ...teachers.map(t => [SYNTHETIC, t.teacherNo, t.payload.name, t.payload.position, t.payload.age, t.payload.status, t.payload.qualifications.join(','), t.payload.qualificationExpiry, t.payload.maxDailySessions, t.payload.dutyWindows.length ? '是' : '否', t.payload.leaveWindows.map(w => `${w.start}~${w.end}`).join(';'), t.payload.functionalAccommodations.join(';')])]],
  ['两轮执行清单', [['轮次', '文件', '考试场数', '岗位人次', '性质'], ['第一轮', 'round-1/exams.xlsx', 180, 360, '正常流程，合成输入'], ['第二轮', 'round-2/exams.xlsx', 180, 360, '调整/审批/替补，合成输入'], ...negatives.map(n => ['负例', `${n.id}.xlsx`, n.rows.length, '', JSON.stringify(n.expected)])]],
  ['字段边界', [['项目', '说明'], ['标准导入', '五类标准表头；数据在首个工作表'], ['教师扩展字段', '必须通过TeacherRequest正规API补齐；此文件不是导入模板'], ['资格失效', 'JOINT检查资格有效期；不得把过期教师当作有效监考'], ['Quick边界', 'Quick不消费主監资格/个别日上限，应明确能力缺口'], ['数学见证', 'feasibility-witness仅证明输入可解，不是系统求解结果'], ['人工安全', '合成记录、无电话邮箱、不对真实学校或人员发送通知']]],
]);

const currentSource = fs.readFileSync(fileURLToPath(import.meta.url));
const design = {
  synthetic: true, label: SYNTHETIC, teacherCount: teachers.length, classCount: classes.length, roomCount: rooms.length, subjectCount: subjects.length, candidateCount: candidates.length,
  qualificationProfiles: {
    withChief: teachers.filter(t => t.payload.qualifications.includes('CHIEF')).length,
    invigilatorOnly: teachers.filter(t => t.payload.qualifications.length === 1).length,
    expired: teachers.filter(t => t.payload.qualificationExpiry < roundDates[0][0]).length,
    active: teachers.filter(t => t.payload.status === 'active').length,
    activeValidChief: teachers.filter(t => t.payload.status === 'active' && t.payload.qualifications.includes('CHIEF') && t.payload.qualificationExpiry >= roundDates[0][0]).length,
    dailyLimitOne: teachers.filter(t => t.payload.maxDailySessions === 1).length,
    morningOnly: teachers.filter(t => t.payload.dutyWindows.length).length,
    hasLeave: teachers.filter(t => t.payload.leaveWindows.length).length,
  },
  rounds: roundExams.map((exams, i) => ({ round: i + 1, dates: roundDates[i], examCount: exams.length, dutyCount: 360, slots: 6, allInput: `round-${i + 1}/exams.xlsx`, noActualApiExecuted: true })),
  notes: ['120教师为合成记录；不是用户现有学校个人数据', '存储完整性+可行性离线测试，不代表真实API、审批、发布、UI已通过', 'XXL旧数据未改动；没有执行既有生成器', '校区单MAIN，本轮未声称覆盖多校区通勤或特殊考生安排'],
};
saveJson('dataset-profile.json', design);
const manifest = { schema: 'school-e2e-dataset/v1', fixedContentDate: FIXED_DATE.toISOString(), generatorSha256: sha(currentSource), xlsxVendorSha256: sha(fs.readFileSync(path.join(ROOT, 'assets/vendor/xlsx.full.0.18.5.min.js'))), ...design, checks, files };
const target = safe('manifest.json');
const manifestBytes = Buffer.from(`${JSON.stringify(manifest, null, 2)}\n`);
checkedWrite('manifest.json', manifestBytes);
console.log(JSON.stringify({ status: 'PASS_OFFLINE_DATA_ONLY', checks: checks.length, failed: checks.filter(c => !c.pass).length, files: files.length, generatorSha256: manifest.generatorSha256, manifestSha256: sha(manifestBytes), dataset: design }, null, 2));
