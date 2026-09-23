#!/usr/bin/env node

import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

const SCRIPT_DIR = path.dirname(fileURLToPath(import.meta.url));
const PROJECT_DIR = path.resolve(SCRIPT_DIR, '..', '..');
const DATA_ROOT = path.resolve(PROJECT_DIR, 'test-data', 'full-exam-acceptance-v1');
const DEFAULT_OUTPUT = path.resolve(DATA_ROOT, 'generated');
const SEED = Number(process.env.ACCEPTANCE_SEED || 20260831);
const FIXED_GENERATED_AT = '2026-08-31T00:00:00.000Z';

function parseArgs(argv) {
  const args = { output: DEFAULT_OUTPUT, clean: false };
  for (let i = 0; i < argv.length; i += 1) {
    if (argv[i] === '--output') args.output = path.resolve(argv[++i]);
    else if (argv[i] === '--clean') args.clean = true;
    else throw new Error(`Unknown argument: ${argv[i]}`);
  }
  return args;
}

function assertInside(candidate, root) {
  const resolved = path.resolve(candidate);
  const resolvedRoot = path.resolve(root);
  if (resolved !== resolvedRoot && !resolved.startsWith(`${resolvedRoot}${path.sep}`)) {
    throw new Error(`Refusing to write outside ${resolvedRoot}: ${resolved}`);
  }
  return resolved;
}

function mulberry32(seed) {
  let state = seed >>> 0;
  return () => {
    state += 0x6d2b79f5;
    let value = state;
    value = Math.imul(value ^ (value >>> 15), value | 1);
    value ^= value + Math.imul(value ^ (value >>> 7), value | 61);
    return ((value ^ (value >>> 14)) >>> 0) / 4294967296;
  };
}

function csvCell(value) {
  const text = value == null ? '' : String(value);
  return /[",\r\n]/.test(text) ? `"${text.replaceAll('"', '""')}"` : text;
}

function csv(headers, rows) {
  return `${headers.join(',')}\n${rows.map((row) => headers.map((header) => csvCell(row[header])).join(',')).join('\n')}\n`;
}

function json(value) {
  return `${JSON.stringify(value, null, 2)}\n`;
}

function jsonl(values) {
  return `${values.map((value) => JSON.stringify(value)).join('\n')}\n`;
}

function sha256(buffer) {
  return crypto.createHash('sha256').update(buffer).digest('hex');
}

function writeFile(outputRoot, relativePath, content, index) {
  const target = assertInside(path.resolve(outputRoot, relativePath), outputRoot);
  fs.mkdirSync(path.dirname(target), { recursive: true });
  fs.writeFileSync(target, content, 'utf8');
  const buffer = fs.readFileSync(target);
  index.push({ path: relativePath.replaceAll('\\', '/'), bytes: buffer.length, sha256: sha256(buffer) });
}

function pad(value, width) {
  return String(value).padStart(width, '0');
}

function isoSlot(slot) {
  const starts = [
    '2026-11-16T08:00:00+08:00',
    '2026-11-16T14:00:00+08:00',
    '2026-11-17T08:00:00+08:00',
    '2026-11-17T14:00:00+08:00',
    '2026-11-18T08:00:00+08:00',
    '2026-11-18T14:00:00+08:00',
    '2026-11-19T08:00:00+08:00',
    '2026-11-19T14:00:00+08:00',
    '2026-11-20T08:00:00+08:00',
    '2026-11-20T14:00:00+08:00',
  ];
  const ends = [
    '2026-11-16T10:00:00+08:00',
    '2026-11-16T16:00:00+08:00',
    '2026-11-17T10:00:00+08:00',
    '2026-11-17T16:00:00+08:00',
    '2026-11-18T10:00:00+08:00',
    '2026-11-18T16:00:00+08:00',
    '2026-11-19T10:00:00+08:00',
    '2026-11-19T16:00:00+08:00',
    '2026-11-20T10:00:00+08:00',
    '2026-11-20T16:00:00+08:00',
  ];
  return { startsAt: starts[slot], endsAt: ends[slot] };
}

function buildDataset() {
  const rng = mulberry32(SEED);
  const subjectCatalog = [
    { subjectCode: 'SUBJ-CHN', name: '语文', category: 'LANGUAGE' },
    { subjectCode: 'SUBJ-MATH', name: '数学', category: 'STEM' },
    { subjectCode: 'SUBJ-ENG', name: '英语', category: 'LANGUAGE' },
    { subjectCode: 'SUBJ-PHY', name: '物理', category: 'STEM' },
    { subjectCode: 'SUBJ-CHEM', name: '化学', category: 'STEM' },
  ];
  const subjects = subjectCatalog.map((subject) => subject.name);
  const grades = ['七年级', '八年级', '九年级', '高一', '高二', '高三'];
  const positions = ['教师', '教师', '教师', '年级组长', '教研组长', '中层干部', '校级干部'];
  const classes = [];
  const students = [];
  const teachers = [];
  const rooms = [];
  const sessions = [];
  const enrollments = [];
  const examDemands = [];
  const demandClasses = [];
  const demandWindows = [];
  const candidates = [];
  const candidateWindows = [];
  const specialRequirements = [];
  const roomPool = [];
  const roomWindows = [];
  const seatTopology = [];
  const teacherPool = [];
  const teacherAvoidances = [];
  const campusTravel = [];
  const oracleRooms = [];
  const oracleSeats = [];
  const oracleDuties = [];

  for (let roomIndex = 1; roomIndex <= 60; roomIndex += 1) {
    const gradeIndex = Math.floor((roomIndex - 1) / 10);
    const section = ((roomIndex - 1) % 10) + 1;
    const campus = gradeIndex < 3 ? 'EAST' : 'WEST';
    const accessible = section <= 4;
    const roomCode = `R${pad(roomIndex, 3)}`;
    const capacity = 52 + (roomIndex % 4);
    rooms.push({
      roomCode,
      name: `${campus === 'EAST' ? '东' : '西'}校区${gradeIndex + 1}号考场`,
      campus,
      building: `${campus}-B${Math.floor((section - 1) / 5) + 1}`,
      floor: ((section - 1) % 5) + 1,
      capacity,
      accessible,
      // The first four rooms in every grade are certified special-arrangement
      // rooms. Keep those capabilities explicit so the generated positive
      // oracle never relies on an implied accessibility feature.
      hearingAssist: accessible,
      separateRoom: accessible,
      computerReady: roomIndex % 3 === 0,
      status: 'ACTIVE',
    });
    roomPool.push({
      roomCode,
      campusCode: campus,
      examCapacity: capacity,
      features: [
        `CAMPUS_${campus}`,
        accessible ? 'ACCESSIBLE' : null,
        accessible ? 'HEARING_ASSIST' : null,
        accessible ? 'SEPARATE_ROOM' : null,
        roomIndex % 3 === 0 ? 'COMPUTER_READY' : null,
      ].filter(Boolean).join('|'),
      status: 'INCLUDED',
    });
    for (let seatIndex = 1; seatIndex <= capacity; seatIndex += 1) {
      const seatFeatures = [];
      if (accessible && seatIndex <= 2) seatFeatures.push('ACCESSIBLE');
      if (accessible && seatIndex <= 4) seatFeatures.push('HEARING_ASSIST');
      seatTopology.push({
        roomCode,
        seatCode: `A${pad(seatIndex, 2)}`,
        rowNo: Math.ceil(seatIndex / 10),
        columnNo: ((seatIndex - 1) % 10) + 1,
        features: seatFeatures.join('|'),
        status: 'ACTIVE',
      });
    }
  }

  for (let roomIndex = 1; roomIndex <= 12; roomIndex += 1) {
    const downtime = isoSlot(roomIndex % 2 === 0 ? 0 : 1);
    roomWindows.push({
      roomCode: `R${pad(roomIndex, 3)}`,
      windowType: 'UNAVAILABLE',
      startsAt: downtime.startsAt,
      endsAt: downtime.endsAt,
      reason: roomIndex % 2 === 0 ? '设备保养' : '楼宇例行检修',
    });
  }

  for (let gradeIndex = 0; gradeIndex < grades.length; gradeIndex += 1) {
    for (let section = 1; section <= 10; section += 1) {
      const classCode = `G${gradeIndex + 7}-C${pad(section, 2)}`;
      classes.push({
        classCode,
        name: `${grades[gradeIndex]}${section}班`,
        grade: grades[gradeIndex],
        stage: gradeIndex < 3 ? 'JUNIOR' : 'SENIOR',
        campus: gradeIndex < 3 ? 'EAST' : 'WEST',
        studentCount: 50,
      });
      for (let ordinal = 1; ordinal <= 50; ordinal += 1) {
        const studentNumber = gradeIndex * 500 + (section - 1) * 50 + ordinal;
        const specialNeed = section <= 4 && ordinal === 1
          ? ['WHEELCHAIR', 'EXTRA_TIME', 'HEARING_ASSIST', 'SEPARATE_ROOM'][(gradeIndex + section) % 4]
          : '';
        const student = {
          studentId: `S${pad(studentNumber, 4)}`,
          name: `考生${pad(studentNumber, 4)}`,
          grade: grades[gradeIndex],
          classCode,
          campus: gradeIndex < 3 ? 'EAST' : 'WEST',
          specialNeed,
        };
        students.push(student);
        candidates.push({
          candidateCode: student.studentId,
          name: student.name,
          classCode,
          campus: student.campus,
          attributes: specialNeed ? `specialNeed=${specialNeed}` : '',
        });
        if (studentNumber % 25 === 0) {
          // Keep the positive baseline feasible: this absence is on the
          // opposite cohort slot, never on the class's preferred W1 slot.
          const unavailable = isoSlot(section % 2);
          candidateWindows.push({
            candidateCode: student.studentId,
            windowType: 'UNAVAILABLE',
            startsAt: unavailable.startsAt,
            endsAt: unavailable.endsAt,
            reason: studentNumber % 50 === 0 ? '校队比赛' : '医疗复诊',
          });
        }
      }
    }
  }

  for (let index = 1; index <= 150; index += 1) {
    const subject = subjects[(index - 1) % subjects.length];
    const age = 25 + ((index * 17) % 43);
    const gradeIndex = (index - 1) % grades.length;
    const section = ((index * 7) % 10) + 1;
    const isHomeroom = index <= 60;
    const healthFlag = index % 13 === 0 ? 'NO_LONG_STANDING' : index % 29 === 0 ? 'RECENT_SURGERY' : '';
    const qualificationExpiry = index % 17 === 0 ? '2026-06-30' : '2028-12-31';
    const teacher = {
      teacherId: `T${pad(index, 4)}`,
      name: `教师${pad(index, 3)}`,
      gender: index % 2 === 0 ? 'F' : 'M',
      age,
      position: positions[(index - 1) % positions.length],
      subject,
      homeroomClass: isHomeroom ? `G${gradeIndex + 7}-C${pad(section, 2)}` : '',
      campus: gradeIndex < 3 ? 'EAST' : 'WEST',
      healthFlag,
      chiefQualified: index % 5 !== 0,
      qualificationExpiry,
      maxDailySessions: age >= 60 || healthFlag ? 1 : 2,
      active: true,
    };
    teachers.push(teacher);
    const qualificationValid = Date.parse(qualificationExpiry) >= Date.parse('2026-11-16');
    teacherPool.push({
      teacherId: teacher.teacherId,
      eligibleRoles: ['INVIGILATOR', ...(teacher.chiefQualified && qualificationValid ? ['CHIEF'] : [])].join('|'),
      qualificationExpiry,
      maxDailySessions: teacher.maxDailySessions,
      minRestMinutes: teacher.age >= 60 || teacher.healthFlag ? 120 : 60,
      preferredCampus: teacher.campus,
      status: 'INCLUDED',
      preferences: [
        teacher.age >= 60 ? 'NO_BACK_TO_BACK' : null,
        teacher.healthFlag || null,
        ['中层干部', '校级干部'].includes(teacher.position) ? 'REDUCED_LOAD' : null,
      ].filter(Boolean).join('|'),
    });
  }

  for (let slot = 0; slot < subjects.length; slot += 1) {
    for (let gradeIndex = 0; gradeIndex < grades.length; gradeIndex += 1) {
      for (let section = 1; section <= 10; section += 1) {
        // Split each subject across two non-overlapping cohorts. Each class still has
        // one session per subject, while only 30 of 60 rooms are occupied in a slot.
        // This preserves the 300-session oracle and leaves real replacement capacity.
        const time = isoSlot(slot * 2 + ((section - 1) % 2));
        const sessionNumber = slot * 60 + gradeIndex * 10 + section;
        const sessionCode = `EX-${pad(sessionNumber, 3)}`;
        const classCode = `G${gradeIndex + 7}-C${pad(section, 2)}`;
        const roomIndex = gradeIndex * 10 + section;
        const roomCode = `R${pad(roomIndex, 3)}`;
        const subject = subjectCatalog[slot];
        const lockLevel = sessionNumber % 20 === 0
          ? 'TIME_ROOM_LOCKED'
          : sessionNumber % 10 === 0
            ? 'TIME_LOCKED'
            : 'UNLOCKED';
        sessions.push({
          sessionCode,
          examType: slot === 4 ? '期末考' : slot === 3 ? '模拟考' : '正考',
          grade: grades[gradeIndex],
          classCode,
          subject: subjects[slot],
          startsAt: time.startsAt,
          endsAt: time.endsAt,
          candidateCount: 50,
          requiredInvigilators: 2,
          requiredChiefInvigilators: 1,
          preferredCampus: gradeIndex < 3 ? 'EAST' : 'WEST',
        });
        examDemands.push({
          demandCode: sessionCode,
          name: `${grades[gradeIndex]}${section}班${subject.name}`,
          subjectCode: subject.subjectCode,
          examType: slot === 4 ? 'FINAL' : slot === 3 ? 'MOCK' : 'REGULAR',
          durationMinutes: 120,
          candidateCount: 50,
          invigilatorCount: 2,
          chiefInvigilatorCount: 1,
          minRooms: 1,
          maxRooms: lockLevel === 'TIME_ROOM_LOCKED' ? 1 : 2,
          splitAllowed: lockLevel !== 'TIME_ROOM_LOCKED',
          preferredCampus: gradeIndex < 3 ? 'EAST' : 'WEST',
          requiredRoomFeatures: '',
          lockLevel,
          lockedWindowCode: lockLevel === 'UNLOCKED' ? '' : `${sessionCode}-W1`,
          lockedRoomCode: lockLevel === 'TIME_ROOM_LOCKED' ? roomCode : '',
        });
        demandClasses.push({ demandCode: sessionCode, classCode, expectedCandidates: 50 });
        const preferredSlot = slot * 2 + ((section - 1) % 2);
        const alternativeSlot = slot * 2 + (section % 2);
        const reserveSlot = ((slot + 1) % subjectCatalog.length) * 2 + ((section - 1) % 2);
        [
          { slot: preferredSlot, weight: 100 },
          { slot: alternativeSlot, weight: 55 },
          { slot: reserveSlot, weight: 15 },
        ].forEach((candidate, windowIndex) => {
          const window = isoSlot(candidate.slot);
          demandWindows.push({
            demandCode: sessionCode,
            windowCode: `${sessionCode}-W${windowIndex + 1}`,
            startsAt: window.startsAt,
            endsAt: window.endsAt,
            preferenceWeight: candidate.weight,
            locked: lockLevel !== 'UNLOCKED' && windowIndex === 0,
          });
        });
        oracleRooms.push({ sessionCode, roomCode, allocatedCandidates: 50, locked: false });
        for (let ordinal = 1; ordinal <= 50; ordinal += 1) {
          const studentNumber = gradeIndex * 500 + (section - 1) * 50 + ordinal;
          const studentId = `S${pad(studentNumber, 4)}`;
          enrollments.push({ studentId, sessionCode, subject: subjects[slot] });
          oracleSeats.push({ sessionCode, roomCode, studentId, seatLabel: `A${pad(ordinal, 2)}` });
        }

        const concurrentIndex = gradeIndex * 10 + section - 1;
        for (let duty = 0; duty < 2; duty += 1) {
          const teacherNumber = ((slot * 11 + concurrentIndex * 2 + duty) % 150) + 1;
          oracleDuties.push({
            sessionCode,
            role: duty === 0 ? 'CHIEF' : 'INVIGILATOR',
            teacherId: `T${pad(teacherNumber, 4)}`,
            startsAt: time.startsAt,
            endsAt: time.endsAt,
          });
        }
      }
    }
  }

  const specialNeeds = students
    .filter((student) => student.specialNeed)
    .map((student, index) => ({
      studentId: student.studentId,
      needType: student.specialNeed,
      extraMinutes: student.specialNeed === 'EXTRA_TIME' ? 30 : 0,
      requiredRoomFeature: student.specialNeed === 'WHEELCHAIR'
        ? 'ACCESSIBLE'
        : student.specialNeed === 'HEARING_ASSIST'
          ? 'HEARING_ASSIST'
          : student.specialNeed === 'SEPARATE_ROOM'
            ? 'SEPARATE_ROOM'
            : '',
      privateNoteRef: `SN-${pad(index + 1, 3)}`,
    }));

  for (const need of specialNeeds) {
    specialRequirements.push({
      candidateCode: need.studentId,
      demandCode: '',
      requirementCode: need.privateNoteRef,
      requirementType: need.needType,
      requiredFeatures: need.requiredRoomFeature,
      extraMinutes: need.extraMinutes,
      separateRoom: need.needType === 'SEPARATE_ROOM',
      privateNoteRef: need.privateNoteRef,
    });
  }

  const leaves = teachers.slice(0, 12).map((teacher, index) => ({
    leaveId: `L${pad(index + 1, 3)}`,
    teacherId: teacher.teacherId,
    startsAt: index < 6 ? isoSlot(1).startsAt : isoSlot(2).startsAt,
    endsAt: index < 6 ? isoSlot(1).endsAt : isoSlot(2).endsAt,
    reasonCode: index % 2 === 0 ? 'SICK' : 'APPROVED_LEAVE',
    approved: true,
  }));

  const avoidances = [];
  for (const teacher of teachers) {
    if (teacher.homeroomClass) {
      avoidances.push({ teacherId: teacher.teacherId, type: 'HOMEROOM', target: teacher.homeroomClass, severity: 'HARD' });
      avoidances.push({ teacherId: teacher.teacherId, type: 'TEACHING_RELATION', target: teacher.homeroomClass, severity: 'SOFT' });
      teacherAvoidances.push({
        teacherId: teacher.teacherId,
        targetType: 'CLASS',
        targetCode: teacher.homeroomClass,
        severity: 'HARD',
        reason: '班主任回避本班监考',
      });
      teacherAvoidances.push({
        teacherId: teacher.teacherId,
        targetType: 'CLASS',
        targetCode: teacher.homeroomClass,
        severity: 'SOFT',
        reason: '任课关系负载平衡',
      });
    }
    avoidances.push({ teacherId: teacher.teacherId, type: 'SUBJECT', target: teacher.subject, severity: 'HARD' });
    for (const demand of examDemands.filter((item) => item.subjectCode === subjectCatalog.find((subject) => subject.name === teacher.subject)?.subjectCode)) {
      teacherAvoidances.push({
        teacherId: teacher.teacherId,
        targetType: 'DEMAND',
        targetCode: demand.demandCode,
        severity: 'HARD',
        reason: '任教学科回避',
      });
    }
  }

  const campuses = [
    { campusCode: 'EAST', name: '东校区' },
    { campusCode: 'WEST', name: '西校区' },
  ];
  const travel = [
    { from: 'EAST', to: 'EAST', minutes: 0 },
    { from: 'WEST', to: 'WEST', minutes: 0 },
    { from: 'EAST', to: 'WEST', minutes: 45 },
    { from: 'WEST', to: 'EAST', minutes: 45 },
  ];
  campusTravel.push(
    { fromCampus: 'EAST', toCampus: 'WEST', travelMinutes: 45, bufferMinutes: 15 },
    { fromCampus: 'WEST', toCampus: 'EAST', travelMinutes: 45, bufferMinutes: 15 },
  );

  const infeasibleCases = [
    {
      caseId: 'INF-ACCESS-001',
      change: { disableRoomFeature: 'ACCESSIBLE', startsAt: isoSlot(0).startsAt, endsAt: isoSlot(0).endsAt },
      expectedCodes: ['ACCESSIBLE_ROOM_CAPACITY_UNAVAILABLE'],
      expectedAffectedResourceType: 'student',
    },
    {
      caseId: 'INF-STAFF-001',
      change: { unavailableTeacherIds: teachers.slice(0, 100).map((teacher) => teacher.teacherId), startsAt: isoSlot(0).startsAt, endsAt: isoSlot(0).endsAt },
      expectedCodes: ['INVIGILATOR_SHORTAGE', 'DUTIES_UNCOVERED'],
      expectedAffectedResourceType: 'duty',
    },
    {
      caseId: 'INF-CANDIDATE-001',
      change: {
        forceDemandWindow: { demandCodes: ['EX-001', 'EX-061'], windowCode: 'FORCED-CONFLICT', startsAt: isoSlot(0).startsAt, endsAt: isoSlot(0).endsAt },
      },
      expectedCodes: ['CANDIDATE_TIME_CONFLICT', 'CLASS_TIME_CONFLICT'],
      expectedAffectedResourceType: 'candidate',
    },
  ];

  const tenPercentAbsence = teachers.slice(0, 15).map((teacher) => teacher.teacherId);
  const disruptions = [
    {
      disruptionId: 'R3-SINGLE-SICK',
      type: 'TEACHER_SICK_LEAVE',
      payload: { teacherIds: ['T0007'], effectiveAt: isoSlot(1).startsAt },
      minimumRetentionRate: 0.95,
    },
    {
      disruptionId: 'R3-TEN-PERCENT-ABSENT',
      type: 'TEACHER_MASS_ABSENCE',
      payload: { teacherIds: tenPercentAbsence, effectiveAt: isoSlot(2).startsAt },
      minimumRetentionRate: 0.8,
    },
    {
      disruptionId: 'R3-ROOM-DOWN',
      type: 'ROOM_UNAVAILABLE',
      payload: { roomCodes: ['R006'], replacementRoomCode: 'R003', effectiveAt: isoSlot(7).startsAt },
      minimumRetentionRate: 0.95,
    },
    {
      disruptionId: 'R3-CANDIDATE-INCREASE',
      type: 'CANDIDATE_COUNT_INCREASE',
      payload: { sessionCode: 'EX-241', delta: 10 },
      minimumRetentionRate: 0.95,
    },
    {
      disruptionId: 'R3-NOTIFY-FAIL',
      type: 'NOTIFICATION_CHANNEL_FAILURE',
      payload: { channel: 'SMS', recipientIds: ['T0021', 'T0042', 'T0063'], failAttempts: 2 },
      maximumDuplicateDeliveries: 0,
    },
    {
      disruptionId: 'R3-NETWORK-RETRY',
      type: 'NETWORK_INTERRUPTION_RETRY',
      payload: { operation: 'EMERGENCY_CHANGE', idempotencyKey: 'full-exam-r3-network-retry' },
      maximumDuplicateResources: 0,
    },
  ];

  const randomSample = Array.from({ length: 32 }, () => Number(rng().toFixed(8)));
  return {
    subjectCatalog,
    subjects,
    grades,
    classes,
    students,
    teachers,
    rooms,
    sessions,
    enrollments,
    examDemands,
    demandClasses,
    demandWindows,
    candidates,
    candidateWindows,
    specialRequirements,
    roomPool,
    roomWindows,
    seatTopology,
    teacherPool,
    teacherAvoidances,
    campusTravel,
    oracleRooms,
    oracleSeats,
    oracleDuties,
    specialNeeds,
    leaves,
    avoidances,
    campuses,
    travel,
    infeasibleCases,
    disruptions,
    randomSample,
  };
}

function main() {
  const args = parseArgs(process.argv.slice(2));
  const outputRoot = assertInside(args.output, DATA_ROOT);
  if (args.clean && fs.existsSync(outputRoot)) {
    if (path.basename(outputRoot) !== 'generated') throw new Error('The --clean option is restricted to a directory named generated');
    fs.rmSync(outputRoot, { recursive: true, force: false });
  }
  fs.mkdirSync(outputRoot, { recursive: true });

  const dataset = buildDataset();
  const fileIndex = [];
  writeFile(outputRoot, 'shared/subjects.csv', csv(
    ['subjectCode', 'name', 'category'], dataset.subjectCatalog), fileIndex);
  writeFile(outputRoot, 'shared/classes.csv', csv(
    ['classCode', 'name', 'grade', 'stage', 'campus', 'studentCount'], dataset.classes), fileIndex);
  writeFile(outputRoot, 'shared/students.csv', csv(
    ['studentId', 'name', 'grade', 'classCode', 'campus', 'specialNeed'], dataset.students), fileIndex);
  writeFile(outputRoot, 'shared/teachers.csv', csv(
    ['teacherId', 'name', 'gender', 'age', 'position', 'subject', 'homeroomClass', 'campus', 'healthFlag', 'chiefQualified', 'qualificationExpiry', 'maxDailySessions', 'active'], dataset.teachers), fileIndex);
  writeFile(outputRoot, 'shared/rooms.csv', csv(
    ['roomCode', 'name', 'campus', 'building', 'floor', 'capacity', 'accessible', 'hearingAssist', 'separateRoom', 'computerReady', 'status'], dataset.rooms), fileIndex);
  writeFile(outputRoot, 'shared/sessions.csv', csv(
    ['sessionCode', 'examType', 'grade', 'classCode', 'subject', 'startsAt', 'endsAt', 'candidateCount', 'requiredInvigilators', 'requiredChiefInvigilators', 'preferredCampus'], dataset.sessions), fileIndex);
  writeFile(outputRoot, 'shared/enrollments.csv', csv(
    ['studentId', 'sessionCode', 'subject'], dataset.enrollments), fileIndex);
  writeFile(outputRoot, 'shared/exam-demands.csv', csv(
    ['demandCode', 'name', 'subjectCode', 'examType', 'durationMinutes', 'candidateCount', 'invigilatorCount', 'chiefInvigilatorCount', 'minRooms', 'maxRooms', 'splitAllowed', 'preferredCampus', 'requiredRoomFeatures', 'lockLevel', 'lockedWindowCode', 'lockedRoomCode'], dataset.examDemands), fileIndex);
  writeFile(outputRoot, 'shared/demand-classes.csv', csv(
    ['demandCode', 'classCode', 'expectedCandidates'], dataset.demandClasses), fileIndex);
  writeFile(outputRoot, 'shared/demand-windows.csv', csv(
    ['demandCode', 'windowCode', 'startsAt', 'endsAt', 'preferenceWeight', 'locked'], dataset.demandWindows), fileIndex);
  writeFile(outputRoot, 'shared/candidates.csv', csv(
    ['candidateCode', 'name', 'classCode', 'campus', 'attributes'], dataset.candidates), fileIndex);
  writeFile(outputRoot, 'shared/candidate-enrollments.csv', csv(
    ['candidateCode', 'demandCode', 'status'], dataset.enrollments.map((item) => ({
      candidateCode: item.studentId,
      demandCode: item.sessionCode,
      status: 'ENROLLED',
    }))), fileIndex);
  writeFile(outputRoot, 'shared/candidate-windows.csv', csv(
    ['candidateCode', 'windowType', 'startsAt', 'endsAt', 'reason'], dataset.candidateWindows), fileIndex);
  writeFile(outputRoot, 'shared/special-requirements.csv', csv(
    ['candidateCode', 'demandCode', 'requirementCode', 'requirementType', 'requiredFeatures', 'extraMinutes', 'separateRoom', 'privateNoteRef'], dataset.specialRequirements), fileIndex);
  writeFile(outputRoot, 'shared/room-pool.csv', csv(
    ['roomCode', 'campusCode', 'examCapacity', 'features', 'status'], dataset.roomPool), fileIndex);
  writeFile(outputRoot, 'shared/room-windows.csv', csv(
    ['roomCode', 'windowType', 'startsAt', 'endsAt', 'reason'], dataset.roomWindows), fileIndex);
  writeFile(outputRoot, 'shared/seat-topology.csv', csv(
    ['roomCode', 'seatCode', 'rowNo', 'columnNo', 'features', 'status'], dataset.seatTopology), fileIndex);
  writeFile(outputRoot, 'shared/teacher-pool.csv', csv(
    ['teacherId', 'eligibleRoles', 'qualificationExpiry', 'maxDailySessions', 'minRestMinutes', 'preferredCampus', 'status', 'preferences'], dataset.teacherPool), fileIndex);
  writeFile(outputRoot, 'shared/teacher-avoidances.csv', csv(
    ['teacherId', 'targetType', 'targetCode', 'severity', 'reason'], dataset.teacherAvoidances), fileIndex);
  writeFile(outputRoot, 'shared/campus-travel.csv', csv(
    ['fromCampus', 'toCampus', 'travelMinutes', 'bufferMinutes'], dataset.campusTravel), fileIndex);
  writeFile(outputRoot, 'shared/oracle-room-allocations.csv', csv(
    ['sessionCode', 'roomCode', 'allocatedCandidates', 'locked'], dataset.oracleRooms), fileIndex);
  writeFile(outputRoot, 'shared/oracle-seats.csv', csv(
    ['sessionCode', 'roomCode', 'studentId', 'seatLabel'], dataset.oracleSeats), fileIndex);
  writeFile(outputRoot, 'shared/oracle-duty-assignments.csv', csv(
    ['sessionCode', 'role', 'teacherId', 'startsAt', 'endsAt'], dataset.oracleDuties), fileIndex);

  writeFile(outputRoot, 'round-1/scenario.json', json({
    round: 1,
    name: '基础大规模排程',
    seed: SEED,
    datasetRef: '../shared',
    scale: { students: 3000, teachers: 150, rooms: 60, sessions: 300, enrollments: 15000 },
    jointInputScale: {
      subjects: dataset.subjectCatalog.length,
      classes: dataset.classes.length,
      demands: dataset.examDemands.length,
      demandWindows: dataset.demandWindows.length,
      candidates: dataset.candidates.length,
      seatTopology: dataset.seatTopology.length,
      teacherAvoidances: dataset.teacherAvoidances.length,
    },
    requiredAssertions: {
      hardViolationCount: 0,
      sessionCoverage: 1,
      roomCoverage: 1,
      seatCoverage: 1,
      dutyCoverage: 1,
      jointInputGraphCoverage: 1,
      oracleUploadCount: 0,
      maxSolveSeconds: 120,
    },
  }), fileIndex);

  writeFile(outputRoot, 'round-2/special-needs.csv', csv(
    ['studentId', 'needType', 'extraMinutes', 'requiredRoomFeature', 'privateNoteRef'], dataset.specialNeeds), fileIndex);
  writeFile(outputRoot, 'round-2/leave-requests.csv', csv(
    ['leaveId', 'teacherId', 'startsAt', 'endsAt', 'reasonCode', 'approved'], dataset.leaves), fileIndex);
  writeFile(outputRoot, 'round-2/avoidances.csv', csv(
    ['teacherId', 'type', 'target', 'severity'], dataset.avoidances), fileIndex);
  writeFile(outputRoot, 'round-2/campuses.json', json(dataset.campuses), fileIndex);
  writeFile(outputRoot, 'round-2/travel-matrix.csv', csv(
    ['from', 'to', 'minutes'], dataset.travel), fileIndex);
  writeFile(outputRoot, 'round-2/infeasible-cases.jsonl', jsonl(dataset.infeasibleCases), fileIndex);
  writeFile(outputRoot, 'round-2/scenario.json', json({
    round: 2,
    name: '复杂约束与不可行诊断',
    seed: SEED,
    datasetRef: '../shared',
    requiredDimensions: [
      'age', 'health', 'position', 'homeroom', 'teachingRelation', 'subjectAvoidance',
      'leave', 'qualificationExpiry', 'multiCampusTravel', 'chiefQualification', 'accessibility',
      'multipleCandidateWindows', 'candidateUnavailability', 'roomDowntime', 'seatTopology',
      'demandLockLevels', 'candidateAndClassConflictDiagnosis',
    ],
    requiredAssertions: {
      feasibleHardViolationCount: 0,
      infeasibleDiagnosticCoverage: 1,
      fakeSuccessCount: 0,
    },
  }), fileIndex);

  writeFile(outputRoot, 'round-3/disruptions.jsonl', jsonl(dataset.disruptions), fileIndex);
  writeFile(outputRoot, 'round-3/scenario.json', json({
    round: 3,
    name: '发布后扰动与应急闭环',
    seed: SEED,
    datasetRef: '../shared',
    requiredAssertions: {
      singleAbsenceRetentionRate: 0.95,
      massAbsenceRetentionRate: 0.8,
      duplicateTasks: 0,
      duplicateNotifications: 0,
      immutablePublishedRevision: true,
      targetedNotification: true,
    },
  }), fileIndex);

  const manifest = {
    schemaVersion: 'full-exam-dataset-manifest/v1',
    generatedAt: FIXED_GENERATED_AT,
    seed: SEED,
    synthetic: true,
    pii: false,
    intendedTenant: 'isolated-test-tenant-only',
    oraclePolicy: {
      uploadAllowed: false,
      purpose: 'offline-validation-and-output-comparison-only',
      files: [
        'shared/oracle-room-allocations.csv',
        'shared/oracle-seats.csv',
        'shared/oracle-duty-assignments.csv',
      ],
    },
    counts: {
      students: dataset.students.length,
      teachers: dataset.teachers.length,
      rooms: dataset.rooms.length,
      sessions: dataset.sessions.length,
      enrollments: dataset.enrollments.length,
      subjects: dataset.subjectCatalog.length,
      classes: dataset.classes.length,
      examDemands: dataset.examDemands.length,
      demandClasses: dataset.demandClasses.length,
      demandWindows: dataset.demandWindows.length,
      candidates: dataset.candidates.length,
      candidateWindows: dataset.candidateWindows.length,
      specialRequirements: dataset.specialRequirements.length,
      roomPool: dataset.roomPool.length,
      roomWindows: dataset.roomWindows.length,
      seatTopology: dataset.seatTopology.length,
      teacherPool: dataset.teacherPool.length,
      teacherAvoidances: dataset.teacherAvoidances.length,
      campusTravel: dataset.campusTravel.length,
      oracleSeats: dataset.oracleSeats.length,
      oracleDutyAssignments: dataset.oracleDuties.length,
      specialNeeds: dataset.specialNeeds.length,
      leaveRequests: dataset.leaves.length,
      infeasibleCases: dataset.infeasibleCases.length,
      disruptions: dataset.disruptions.length,
    },
    deterministicProbe: dataset.randomSample,
    files: fileIndex.sort((a, b) => a.path.localeCompare(b.path)),
  };
  writeFile(outputRoot, 'manifest.json', json(manifest), []);
  process.stdout.write(`${JSON.stringify({ status: 'PASS', outputRoot, seed: SEED, counts: manifest.counts, files: manifest.files.length + 1 })}\n`);
}

try {
  main();
} catch (error) {
  process.stderr.write(`${error.stack || error.message}\n`);
  process.exitCode = 1;
}
