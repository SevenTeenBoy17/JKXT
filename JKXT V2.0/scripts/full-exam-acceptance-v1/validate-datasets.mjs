#!/usr/bin/env node

import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

const SCRIPT_DIR = path.dirname(fileURLToPath(import.meta.url));
const PROJECT_DIR = path.resolve(SCRIPT_DIR, '..', '..');
const DATA_ROOT = path.resolve(PROJECT_DIR, 'test-data', 'full-exam-acceptance-v1');
const DEFAULT_INPUT = path.resolve(DATA_ROOT, 'generated');

function parseArgs(argv) {
  const args = { input: DEFAULT_INPUT, report: null };
  for (let i = 0; i < argv.length; i += 1) {
    if (argv[i] === '--input') args.input = path.resolve(argv[++i]);
    else if (argv[i] === '--report') args.report = path.resolve(argv[++i]);
    else throw new Error(`Unknown argument: ${argv[i]}`);
  }
  args.report ??= path.resolve(args.input, 'offline-validation-report.json');
  return args;
}

function assertInside(candidate, root) {
  const resolved = path.resolve(candidate);
  const resolvedRoot = path.resolve(root);
  if (resolved !== resolvedRoot && !resolved.startsWith(`${resolvedRoot}${path.sep}`)) {
    throw new Error(`Path is outside ${resolvedRoot}: ${resolved}`);
  }
  return resolved;
}

function sha256(buffer) {
  return crypto.createHash('sha256').update(buffer).digest('hex');
}

function parseCsv(text) {
  const rows = [];
  let row = [];
  let cell = '';
  let quoted = false;
  for (let index = 0; index < text.length; index += 1) {
    const char = text[index];
    if (quoted) {
      if (char === '"' && text[index + 1] === '"') {
        cell += '"';
        index += 1;
      } else if (char === '"') quoted = false;
      else cell += char;
    } else if (char === '"') quoted = true;
    else if (char === ',') {
      row.push(cell);
      cell = '';
    } else if (char === '\n') {
      row.push(cell.replace(/\r$/, ''));
      if (row.some((value) => value !== '')) rows.push(row);
      row = [];
      cell = '';
    } else cell += char;
  }
  if (cell || row.length) {
    row.push(cell.replace(/\r$/, ''));
    rows.push(row);
  }
  if (quoted) throw new Error('CSV contains an unterminated quoted field');
  const [headers, ...values] = rows;
  if (!headers?.length) return [];
  return values.map((items, rowIndex) => {
    if (items.length !== headers.length) throw new Error(`CSV row ${rowIndex + 2} has ${items.length} fields; expected ${headers.length}`);
    return Object.fromEntries(headers.map((header, index) => [header, items[index]]));
  });
}

function readCsv(root, relativePath) {
  return parseCsv(fs.readFileSync(path.resolve(root, relativePath), 'utf8'));
}

function readJson(root, relativePath) {
  return JSON.parse(fs.readFileSync(path.resolve(root, relativePath), 'utf8'));
}

function readJsonl(root, relativePath) {
  return fs.readFileSync(path.resolve(root, relativePath), 'utf8').split(/\r?\n/).filter(Boolean).map((line) => JSON.parse(line));
}

function number(value) {
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) throw new Error(`Expected a finite number, received ${value}`);
  return parsed;
}

function bool(value) {
  if (value === true || value === 'true') return true;
  if (value === false || value === 'false') return false;
  throw new Error(`Expected a boolean, received ${value}`);
}

function list(value) {
  return String(value || '').split('|').map((item) => item.trim()).filter(Boolean);
}

function overlaps(leftStart, leftEnd, rightStart, rightEnd) {
  return Date.parse(leftStart) < Date.parse(rightEnd) && Date.parse(leftEnd) > Date.parse(rightStart);
}

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

function unique(rows, key, label) {
  const seen = new Set();
  for (const row of rows) {
    assert(row[key], `${label} has a blank ${key}`);
    assert(!seen.has(row[key]), `${label} contains duplicate ${key}: ${row[key]}`);
    seen.add(row[key]);
  }
  return seen;
}

function check(name, fn, checks) {
  try {
    const observed = fn();
    checks.push({ id: name, status: 'PASS', observed });
  } catch (error) {
    checks.push({ id: name, status: 'FAIL', message: error.message });
  }
}

function validateManifest(root, manifest) {
  assert(manifest.schemaVersion === 'full-exam-dataset-manifest/v1', 'Unexpected manifest schemaVersion');
  assert(Number.isInteger(manifest.seed), 'Manifest seed must be an integer');
  assert(manifest.synthetic === true && manifest.pii === false, 'Dataset must be explicitly synthetic and PII-free');
  assert(manifest.oraclePolicy?.uploadAllowed === false, 'Manifest must forbid oracle uploads');
  assert(Array.isArray(manifest.oraclePolicy?.files) && manifest.oraclePolicy.files.length === 3, 'Manifest oracle file policy is incomplete');
  for (const oraclePath of manifest.oraclePolicy.files) assert(/^shared\/oracle-/.test(oraclePath), `Unexpected oracle path ${oraclePath}`);
  assert(Array.isArray(manifest.files) && manifest.files.length >= 10, 'Manifest file index is incomplete');
  for (const entry of manifest.files) {
    const target = assertInside(path.resolve(root, entry.path), root);
    assert(fs.existsSync(target), `Manifest file is missing: ${entry.path}`);
    const buffer = fs.readFileSync(target);
    assert(buffer.length === entry.bytes, `Byte length mismatch for ${entry.path}`);
    assert(sha256(buffer) === entry.sha256, `SHA-256 mismatch for ${entry.path}`);
  }
  return { indexedFiles: manifest.files.length, seed: manifest.seed };
}

function validateCore(root, manifest) {
  const students = readCsv(root, 'shared/students.csv');
  const teachers = readCsv(root, 'shared/teachers.csv');
  const rooms = readCsv(root, 'shared/rooms.csv');
  const sessions = readCsv(root, 'shared/sessions.csv');
  const enrollments = readCsv(root, 'shared/enrollments.csv');
  const roomAllocations = readCsv(root, 'shared/oracle-room-allocations.csv');
  const seats = readCsv(root, 'shared/oracle-seats.csv');
  const duties = readCsv(root, 'shared/oracle-duty-assignments.csv');
  assert(students.length === 3000, `Expected 3000 students, found ${students.length}`);
  assert(teachers.length === 150, `Expected 150 teachers, found ${teachers.length}`);
  assert(rooms.length === 60, `Expected 60 rooms, found ${rooms.length}`);
  assert(sessions.length === 300, `Expected 300 sessions, found ${sessions.length}`);
  assert(enrollments.length === 15000, `Expected 15000 enrollments, found ${enrollments.length}`);
  assert(seats.length === 15000, `Expected 15000 oracle seats, found ${seats.length}`);
  assert(duties.length === 600, `Expected 600 oracle duties, found ${duties.length}`);
  assert(manifest.counts.students === students.length, 'Manifest student count disagrees with data');
  assert(manifest.counts.sessions === sessions.length, 'Manifest session count disagrees with data');

  const studentIds = unique(students, 'studentId', 'students');
  const teacherIds = unique(teachers, 'teacherId', 'teachers');
  const roomCodes = unique(rooms, 'roomCode', 'rooms');
  const sessionCodes = unique(sessions, 'sessionCode', 'sessions');
  const roomByCode = new Map(rooms.map((room) => [room.roomCode, room]));
  const sessionByCode = new Map(sessions.map((session) => [session.sessionCode, session]));
  const enrollmentCounts = new Map();
  const perStudent = new Map();
  const enrollmentPairs = new Set();
  for (const enrollment of enrollments) {
    assert(studentIds.has(enrollment.studentId), `Enrollment references missing student ${enrollment.studentId}`);
    assert(sessionCodes.has(enrollment.sessionCode), `Enrollment references missing session ${enrollment.sessionCode}`);
    const pair = `${enrollment.studentId}|${enrollment.sessionCode}`;
    assert(!enrollmentPairs.has(pair), `Duplicate enrollment ${pair}`);
    enrollmentPairs.add(pair);
    enrollmentCounts.set(enrollment.sessionCode, (enrollmentCounts.get(enrollment.sessionCode) || 0) + 1);
    perStudent.set(enrollment.studentId, (perStudent.get(enrollment.studentId) || 0) + 1);
  }
  for (const session of sessions) {
    assert(Date.parse(session.endsAt) > Date.parse(session.startsAt), `Invalid interval for ${session.sessionCode}`);
    assert(number(session.candidateCount) === enrollmentCounts.get(session.sessionCode), `Candidate count mismatch for ${session.sessionCode}`);
  }
  for (const [studentId, count] of perStudent) assert(count === 5, `${studentId} has ${count} exams; expected 5`);

  assert(roomAllocations.length === sessions.length, 'Room oracle must cover every session exactly once');
  const allocatedSessions = unique(roomAllocations, 'sessionCode', 'room allocations');
  assert(allocatedSessions.size === sessionCodes.size, 'Room oracle session coverage is not 100%');
  for (const allocation of roomAllocations) {
    const room = roomByCode.get(allocation.roomCode);
    const session = sessionByCode.get(allocation.sessionCode);
    assert(room, `Room allocation references missing room ${allocation.roomCode}`);
    assert(session, `Room allocation references missing session ${allocation.sessionCode}`);
    assert(number(room.capacity) >= number(allocation.allocatedCandidates), `Room ${room.roomCode} is too small for ${session.sessionCode}`);
    assert(number(allocation.allocatedCandidates) === number(session.candidateCount), `Allocation coverage mismatch for ${session.sessionCode}`);
  }

  const seatKeys = new Set();
  const seatedCandidates = new Set();
  const seatCounts = new Map();
  for (const seat of seats) {
    assert(sessionCodes.has(seat.sessionCode), `Seat references missing session ${seat.sessionCode}`);
    assert(studentIds.has(seat.studentId), `Seat references missing student ${seat.studentId}`);
    assert(roomCodes.has(seat.roomCode), `Seat references missing room ${seat.roomCode}`);
    const seatKey = `${seat.sessionCode}|${seat.roomCode}|${seat.seatLabel}`;
    const candidateKey = `${seat.sessionCode}|${seat.studentId}`;
    assert(!seatKeys.has(seatKey), `Duplicate physical seat ${seatKey}`);
    assert(!seatedCandidates.has(candidateKey), `Candidate seated twice in session ${candidateKey}`);
    seatKeys.add(seatKey);
    seatedCandidates.add(candidateKey);
    seatCounts.set(seat.sessionCode, (seatCounts.get(seat.sessionCode) || 0) + 1);
  }
  for (const session of sessions) assert(seatCounts.get(session.sessionCode) === number(session.candidateCount), `Seat coverage mismatch for ${session.sessionCode}`);

  const dutyCounts = new Map();
  const teacherTime = new Set();
  for (const duty of duties) {
    assert(sessionCodes.has(duty.sessionCode), `Duty references missing session ${duty.sessionCode}`);
    assert(teacherIds.has(duty.teacherId), `Duty references missing teacher ${duty.teacherId}`);
    const key = `${duty.teacherId}|${duty.startsAt}`;
    assert(!teacherTime.has(key), `Teacher double-booked at ${key}`);
    teacherTime.add(key);
    dutyCounts.set(duty.sessionCode, (dutyCounts.get(duty.sessionCode) || 0) + 1);
  }
  for (const session of sessions) assert(dutyCounts.get(session.sessionCode) === number(session.requiredInvigilators), `Duty coverage mismatch for ${session.sessionCode}`);

  return {
    students: students.length,
    teachers: teachers.length,
    rooms: rooms.length,
    sessions: sessions.length,
    enrollments: enrollments.length,
    seatCoverage: seats.length / enrollments.length,
    roomCoverage: allocatedSessions.size / sessions.length,
    dutyCoverage: duties.length / sessions.reduce((sum, session) => sum + number(session.requiredInvigilators), 0),
  };
}

function validateJointInputs(root, manifest) {
  const subjects = readCsv(root, 'shared/subjects.csv');
  const classes = readCsv(root, 'shared/classes.csv');
  const demands = readCsv(root, 'shared/exam-demands.csv');
  const demandClasses = readCsv(root, 'shared/demand-classes.csv');
  const demandWindows = readCsv(root, 'shared/demand-windows.csv');
  const candidates = readCsv(root, 'shared/candidates.csv');
  const enrollments = readCsv(root, 'shared/candidate-enrollments.csv');
  const candidateWindows = readCsv(root, 'shared/candidate-windows.csv');
  const requirements = readCsv(root, 'shared/special-requirements.csv');
  const roomPool = readCsv(root, 'shared/room-pool.csv');
  const roomWindows = readCsv(root, 'shared/room-windows.csv');
  const topology = readCsv(root, 'shared/seat-topology.csv');
  const teacherPool = readCsv(root, 'shared/teacher-pool.csv');
  const avoidances = readCsv(root, 'shared/teacher-avoidances.csv');
  const travel = readCsv(root, 'shared/campus-travel.csv');
  const teachers = readCsv(root, 'shared/teachers.csv');
  const rooms = readCsv(root, 'shared/rooms.csv');
  const sessions = readCsv(root, 'shared/sessions.csv');
  const oracleRooms = readCsv(root, 'shared/oracle-room-allocations.csv');
  const oracleSeats = readCsv(root, 'shared/oracle-seats.csv');
  const impossible = readJsonl(root, 'round-2/infeasible-cases.jsonl');

  assert(subjects.length === 5, `Expected 5 subjects, found ${subjects.length}`);
  assert(classes.length === 60, `Expected 60 classes, found ${classes.length}`);
  assert(demands.length === 300, `Expected 300 exam demands, found ${demands.length}`);
  assert(demandClasses.length === 300, `Expected 300 demand-class links, found ${demandClasses.length}`);
  assert(demandWindows.length === 900, `Expected 900 demand windows, found ${demandWindows.length}`);
  assert(candidates.length === 3000, `Expected 3000 candidates, found ${candidates.length}`);
  assert(enrollments.length === 15000, `Expected 15000 candidate enrollments, found ${enrollments.length}`);
  assert(roomPool.length === 60, `Expected 60 room-pool entries, found ${roomPool.length}`);
  assert(teacherPool.length === 150, `Expected 150 teacher-pool entries, found ${teacherPool.length}`);
  assert(manifest.counts.examDemands === demands.length, 'Manifest joint demand count disagrees with data');
  assert(manifest.counts.seatTopology === topology.length, 'Manifest seat-topology count disagrees with data');

  const subjectCodes = unique(subjects, 'subjectCode', 'subjects');
  const classCodes = unique(classes, 'classCode', 'classes');
  const demandCodes = unique(demands, 'demandCode', 'exam demands');
  const candidateCodes = unique(candidates, 'candidateCode', 'candidates');
  const roomCodes = unique(roomPool, 'roomCode', 'room pool');
  const teacherIds = unique(teacherPool, 'teacherId', 'teacher pool');
  const demandByCode = new Map(demands.map((item) => [item.demandCode, item]));
  const candidateByCode = new Map(candidates.map((item) => [item.candidateCode, item]));
  const sessionByCode = new Map(sessions.map((item) => [item.sessionCode, item]));
  const teacherById = new Map(teachers.map((item) => [item.teacherId, item]));
  const roomByCode = new Map(rooms.map((item) => [item.roomCode, item]));

  const lockLevels = new Set();
  for (const demand of demands) {
    assert(subjectCodes.has(demand.subjectCode), `Demand ${demand.demandCode} references missing subject ${demand.subjectCode}`);
    assert(number(demand.durationMinutes) > 0, `Demand ${demand.demandCode} has invalid duration`);
    assert(number(demand.candidateCount) === 50, `Demand ${demand.demandCode} must contain 50 candidates`);
    assert(number(demand.invigilatorCount) >= number(demand.chiefInvigilatorCount), `Demand ${demand.demandCode} chief count exceeds total invigilators`);
    assert(number(demand.minRooms) <= number(demand.maxRooms), `Demand ${demand.demandCode} room bounds are invalid`);
    assert(['UNLOCKED', 'TIME_LOCKED', 'TIME_ROOM_LOCKED'].includes(demand.lockLevel), `Demand ${demand.demandCode} has invalid lock level`);
    lockLevels.add(demand.lockLevel);
    if (demand.lockLevel === 'TIME_ROOM_LOCKED') assert(roomCodes.has(demand.lockedRoomCode), `Demand ${demand.demandCode} lacks a valid locked room`);
    else assert(!demand.lockedRoomCode, `Demand ${demand.demandCode} unexpectedly locks a room`);
  }
  assert(lockLevels.size === 3, 'Demand lock-level diversity is incomplete');

  const classesByDemand = new Map();
  for (const link of demandClasses) {
    assert(demandCodes.has(link.demandCode), `Demand-class link references missing demand ${link.demandCode}`);
    assert(classCodes.has(link.classCode), `Demand-class link references missing class ${link.classCode}`);
    const values = classesByDemand.get(link.demandCode) || [];
    values.push(link);
    classesByDemand.set(link.demandCode, values);
  }
  for (const demand of demands) {
    const links = classesByDemand.get(demand.demandCode) || [];
    assert(links.length >= 1, `Demand ${demand.demandCode} has no class`);
    assert(links.reduce((sum, item) => sum + number(item.expectedCandidates), 0) === number(demand.candidateCount), `Demand-class candidate count mismatch for ${demand.demandCode}`);
  }

  const windowsByDemand = new Map();
  const windowKeys = new Set();
  for (const window of demandWindows) {
    assert(demandCodes.has(window.demandCode), `Demand window references missing demand ${window.demandCode}`);
    assert(Date.parse(window.endsAt) > Date.parse(window.startsAt), `Demand window ${window.windowCode} has an invalid interval`);
    const key = `${window.demandCode}|${window.windowCode}`;
    assert(!windowKeys.has(key), `Duplicate demand window ${key}`);
    windowKeys.add(key);
    const values = windowsByDemand.get(window.demandCode) || [];
    values.push(window);
    windowsByDemand.set(window.demandCode, values);
  }
  for (const demand of demands) {
    const windows = windowsByDemand.get(demand.demandCode) || [];
    assert(windows.length >= 3, `Demand ${demand.demandCode} lacks multiple candidate windows`);
    const locked = windows.filter((window) => bool(window.locked));
    if (demand.lockLevel === 'UNLOCKED') assert(locked.length === 0 && !demand.lockedWindowCode, `Unlocked demand ${demand.demandCode} contains a locked window`);
    else assert(locked.length === 1 && locked[0].windowCode === demand.lockedWindowCode, `Locked demand ${demand.demandCode} has an inconsistent locked window`);
  }

  for (const candidate of candidates) assert(classCodes.has(candidate.classCode), `Candidate ${candidate.candidateCode} references missing class ${candidate.classCode}`);
  const enrollmentKeys = new Set();
  const enrollmentsByCandidate = new Map();
  const enrollmentsByDemand = new Map();
  for (const enrollment of enrollments) {
    assert(candidateCodes.has(enrollment.candidateCode), `Enrollment references missing candidate ${enrollment.candidateCode}`);
    assert(demandCodes.has(enrollment.demandCode), `Enrollment references missing demand ${enrollment.demandCode}`);
    assert(enrollment.status === 'ENROLLED', `Baseline enrollment ${enrollment.candidateCode}|${enrollment.demandCode} is not active`);
    const key = `${enrollment.candidateCode}|${enrollment.demandCode}`;
    assert(!enrollmentKeys.has(key), `Duplicate candidate enrollment ${key}`);
    enrollmentKeys.add(key);
    const byCandidate = enrollmentsByCandidate.get(enrollment.candidateCode) || [];
    byCandidate.push(enrollment.demandCode);
    enrollmentsByCandidate.set(enrollment.candidateCode, byCandidate);
    const byDemand = enrollmentsByDemand.get(enrollment.demandCode) || [];
    byDemand.push(enrollment.candidateCode);
    enrollmentsByDemand.set(enrollment.demandCode, byDemand);
    const candidateClass = candidateByCode.get(enrollment.candidateCode).classCode;
    assert((classesByDemand.get(enrollment.demandCode) || []).some((item) => item.classCode === candidateClass), `Candidate ${enrollment.candidateCode} is enrolled outside its demand class`);
  }
  for (const candidate of candidates) assert((enrollmentsByCandidate.get(candidate.candidateCode) || []).length === 5, `Candidate ${candidate.candidateCode} must have 5 exams`);
  for (const demand of demands) assert((enrollmentsByDemand.get(demand.demandCode) || []).length === number(demand.candidateCount), `Demand enrollment coverage mismatch for ${demand.demandCode}`);

  const primaryWindow = new Map([...windowsByDemand].map(([code, windows]) => [code, windows.find((item) => item.windowCode === `${code}-W1`)]));
  const demandCodesByClass = new Map();
  for (const link of demandClasses) {
    const values = demandCodesByClass.get(link.classCode) || [];
    values.push(link.demandCode);
    demandCodesByClass.set(link.classCode, values);
  }
  for (const [classCode, codes] of demandCodesByClass) {
    const selected = codes.map((code) => primaryWindow.get(code));
    for (let left = 0; left < selected.length; left += 1) {
      for (let right = left + 1; right < selected.length; right += 1) {
        assert(!overlaps(selected[left].startsAt, selected[left].endsAt, selected[right].startsAt, selected[right].endsAt), `Class ${classCode} has a primary-window conflict`);
      }
    }
  }
  for (const [candidateCode, codes] of enrollmentsByCandidate) {
    const selected = codes.map((code) => primaryWindow.get(code));
    for (let left = 0; left < selected.length; left += 1) {
      for (let right = left + 1; right < selected.length; right += 1) {
        assert(!overlaps(selected[left].startsAt, selected[left].endsAt, selected[right].startsAt, selected[right].endsAt), `Candidate ${candidateCode} has a primary-window conflict`);
      }
    }
  }
  for (const window of candidateWindows) {
    assert(candidateCodes.has(window.candidateCode), `Candidate window references missing candidate ${window.candidateCode}`);
    assert(['AVAILABLE', 'UNAVAILABLE'].includes(window.windowType), `Candidate window ${window.candidateCode} has invalid type`);
    assert(Date.parse(window.endsAt) > Date.parse(window.startsAt), `Candidate window ${window.candidateCode} has invalid interval`);
    if (window.windowType === 'UNAVAILABLE') {
      for (const demandCode of enrollmentsByCandidate.get(window.candidateCode) || []) {
        const primary = primaryWindow.get(demandCode);
        assert(!overlaps(window.startsAt, window.endsAt, primary.startsAt, primary.endsAt), `Candidate ${window.candidateCode} is unavailable during primary demand ${demandCode}`);
      }
    }
  }

  const poolByRoom = new Map(roomPool.map((item) => [item.roomCode, item]));
  for (const room of roomPool) {
    assert(roomByCode.has(room.roomCode), `Room pool references missing classroom ${room.roomCode}`);
    assert(['EAST', 'WEST'].includes(room.campusCode), `Room pool ${room.roomCode} has invalid campus`);
    assert(number(room.examCapacity) > 0 && list(room.features).includes(`CAMPUS_${room.campusCode}`), `Room pool ${room.roomCode} has invalid capacity/features`);
  }
  const topologyKeys = new Set();
  const topologyByRoom = new Map();
  for (const seat of topology) {
    assert(roomCodes.has(seat.roomCode), `Seat topology references missing room ${seat.roomCode}`);
    const key = `${seat.roomCode}|${seat.seatCode}`;
    assert(!topologyKeys.has(key), `Duplicate topology seat ${key}`);
    topologyKeys.add(key);
    const values = topologyByRoom.get(seat.roomCode) || [];
    values.push(seat);
    topologyByRoom.set(seat.roomCode, values);
  }
  for (const room of roomPool) {
    const activeSeats = (topologyByRoom.get(room.roomCode) || []).filter((seat) => seat.status === 'ACTIVE');
    assert(activeSeats.length >= number(room.examCapacity), `Room ${room.roomCode} topology has fewer active seats than exam capacity`);
  }
  const oracleSeatByCandidate = new Map();
  for (const seat of oracleSeats) {
    assert(topologyKeys.has(`${seat.roomCode}|${seat.seatLabel}`), `Oracle seat ${seat.roomCode}|${seat.seatLabel} is absent from topology`);
    const values = oracleSeatByCandidate.get(seat.studentId) || [];
    values.push(seat);
    oracleSeatByCandidate.set(seat.studentId, values);
  }
  const topologyByKey = new Map(topology.map((seat) => [`${seat.roomCode}|${seat.seatCode}`, seat]));
  for (const requirement of requirements) {
    assert(candidateCodes.has(requirement.candidateCode), `Special requirement references missing candidate ${requirement.candidateCode}`);
    if (requirement.demandCode) assert(demandCodes.has(requirement.demandCode), `Special requirement references missing demand ${requirement.demandCode}`);
    for (const seat of oracleSeatByCandidate.get(requirement.candidateCode) || []) {
      const room = poolByRoom.get(seat.roomCode);
      for (const feature of list(requirement.requiredFeatures)) assert(list(room.features).includes(feature), `Special requirement ${requirement.requirementCode} is not supported by room ${seat.roomCode}`);
      if (['WHEELCHAIR', 'HEARING_ASSIST'].includes(requirement.requirementType)) {
        assert(list(topologyByKey.get(`${seat.roomCode}|${seat.seatLabel}`).features).includes(requirement.requiredFeatures), `Special requirement ${requirement.requirementCode} is not supported by seat ${seat.seatLabel}`);
      }
    }
  }

  for (const downtime of roomWindows) {
    assert(roomCodes.has(downtime.roomCode), `Room window references missing room ${downtime.roomCode}`);
    assert(downtime.windowType === 'UNAVAILABLE', `Baseline room window ${downtime.roomCode} must describe downtime`);
    assert(Date.parse(downtime.endsAt) > Date.parse(downtime.startsAt), `Room window ${downtime.roomCode} has invalid interval`);
    for (const allocation of oracleRooms.filter((item) => item.roomCode === downtime.roomCode)) {
      const session = sessionByCode.get(allocation.sessionCode);
      assert(!overlaps(downtime.startsAt, downtime.endsAt, session.startsAt, session.endsAt), `Room ${downtime.roomCode} is down during oracle session ${session.sessionCode}`);
    }
  }

  for (const pool of teacherPool) {
    const teacher = teacherById.get(pool.teacherId);
    assert(teacher, `Teacher pool references missing teacher ${pool.teacherId}`);
    const roles = list(pool.eligibleRoles);
    assert(roles.includes('INVIGILATOR'), `Teacher ${pool.teacherId} lacks INVIGILATOR eligibility`);
    if (Date.parse(pool.qualificationExpiry) < Date.parse('2026-11-16')) assert(!roles.includes('CHIEF'), `Expired teacher ${pool.teacherId} remains CHIEF eligible`);
    assert(number(pool.maxDailySessions) >= 1 && number(pool.minRestMinutes) >= 0, `Teacher pool limits are invalid for ${pool.teacherId}`);
  }
  for (const avoidance of avoidances) {
    assert(teacherIds.has(avoidance.teacherId), `Avoidance references missing teacher ${avoidance.teacherId}`);
    assert(['HARD', 'SOFT'].includes(avoidance.severity), `Avoidance ${avoidance.teacherId} has invalid severity`);
    if (avoidance.targetType === 'DEMAND') assert(demandCodes.has(avoidance.targetCode), `Avoidance references missing demand ${avoidance.targetCode}`);
    else if (avoidance.targetType === 'CLASS') assert(classCodes.has(avoidance.targetCode), `Avoidance references missing class ${avoidance.targetCode}`);
    else if (avoidance.targetType === 'CANDIDATE') assert(candidateCodes.has(avoidance.targetCode), `Avoidance references missing candidate ${avoidance.targetCode}`);
    else throw new Error(`Avoidance has unsupported target type ${avoidance.targetType}`);
  }
  assert(travel.length === 2, `Expected two directed cross-campus travel rows, found ${travel.length}`);
  for (const item of travel) {
    assert(item.fromCampus !== item.toCampus, 'Campus travel must be cross-campus');
    assert(number(item.travelMinutes) >= 30 && number(item.bufferMinutes) >= 0, 'Campus travel duration/buffer is invalid');
  }

  const conflictCase = impossible.find((item) => item.caseId === 'INF-CANDIDATE-001');
  assert(conflictCase && conflictCase.expectedCodes.includes('CANDIDATE_TIME_CONFLICT'), 'Candidate-conflict infeasible oracle is missing');
  const conflictDemands = conflictCase.change.forceDemandWindow.demandCodes;
  assert(conflictDemands.every((code) => demandCodes.has(code)), 'Candidate-conflict oracle references missing demands');
  const sharedCandidates = new Set(enrollmentsByDemand.get(conflictDemands[0]));
  assert((enrollmentsByDemand.get(conflictDemands[1]) || []).some((code) => sharedCandidates.has(code)), 'Candidate-conflict oracle demands have no shared candidate');

  return {
    subjects: subjects.length,
    classes: classes.length,
    demands: demands.length,
    demandWindows: demandWindows.length,
    candidates: candidates.length,
    enrollments: enrollments.length,
    candidateWindows: candidateWindows.length,
    roomDowntimes: roomWindows.length,
    seatTopology: topology.length,
    teacherPool: teacherPool.length,
    teacherAvoidances: avoidances.length,
    lockLevels: [...lockLevels].sort(),
  };
}

function validateRound2(root) {
  const teachers = readCsv(root, 'shared/teachers.csv');
  const rooms = readCsv(root, 'shared/rooms.csv');
  const students = readCsv(root, 'shared/students.csv');
  const seats = readCsv(root, 'shared/oracle-seats.csv');
  const specialNeeds = readCsv(root, 'round-2/special-needs.csv');
  const leaves = readCsv(root, 'round-2/leave-requests.csv');
  const avoidances = readCsv(root, 'round-2/avoidances.csv');
  const travel = readCsv(root, 'round-2/travel-matrix.csv');
  const campuses = readJson(root, 'round-2/campuses.json');
  const infeasible = readJsonl(root, 'round-2/infeasible-cases.jsonl');
  const teacherIds = new Set(teachers.map((teacher) => teacher.teacherId));
  const studentIds = new Set(students.map((student) => student.studentId));
  assert(teachers.some((teacher) => number(teacher.age) >= 60), 'Age accommodation data is missing');
  assert(teachers.some((teacher) => teacher.healthFlag), 'Health accommodation data is missing');
  assert(new Set(teachers.map((teacher) => teacher.position)).size >= 4, 'Position diversity is insufficient');
  assert(teachers.some((teacher) => teacher.homeroomClass), 'Homeroom data is missing');
  assert(teachers.some((teacher) => Date.parse(teacher.qualificationExpiry) < Date.parse('2026-11-16')), 'Expired qualifications are missing');
  assert(teachers.every((teacher) => ['EAST', 'WEST'].includes(teacher.campus)), 'Teacher campus data is invalid');
  assert(rooms.some((room) => bool(room.accessible)), 'Accessible rooms are missing');
  assert(rooms.some((room) => bool(room.hearingAssist)), 'Hearing-assist rooms are missing');
  assert(specialNeeds.length >= 20, 'Special-needs cases are insufficient');
  assert(new Set(specialNeeds.map((need) => need.needType)).size >= 4, 'Special-needs type diversity is insufficient');
  assert(specialNeeds.every((need) => studentIds.has(need.studentId)), 'Special-needs data references a missing student');
  const roomByCode = new Map(rooms.map((room) => [room.roomCode, room]));
  const seatsByStudent = new Map();
  for (const seat of seats) {
    const values = seatsByStudent.get(seat.studentId) || [];
    values.push(seat);
    seatsByStudent.set(seat.studentId, values);
  }
  for (const need of specialNeeds) {
    if (!need.requiredRoomFeature) continue;
    const assignedSeats = seatsByStudent.get(need.studentId) || [];
    assert(assignedSeats.length > 0, `Special-needs candidate ${need.studentId} has no oracle seat`);
    for (const seat of assignedSeats) {
      const room = roomByCode.get(seat.roomCode);
      assert(roomFeatures(room).has(need.requiredRoomFeature),
        `Special-needs candidate ${need.studentId} requires ${need.requiredRoomFeature}, but ${seat.roomCode} does not provide it`);
    }
  }
  assert(leaves.length >= 10 && leaves.every((leave) => teacherIds.has(leave.teacherId)), 'Approved leave coverage is insufficient');
  for (const type of ['HOMEROOM', 'TEACHING_RELATION', 'SUBJECT']) assert(avoidances.some((item) => item.type === type), `Avoidance type ${type} is missing`);
  assert(campuses.length === 2 && travel.length === 4, 'Campus travel matrix is incomplete');
  assert(travel.some((item) => item.from !== item.to && number(item.minutes) >= 30), 'Cross-campus travel time is not realistic');
  assert(infeasible.length >= 2, 'At least two infeasible diagnostic cases are required');
  assert(infeasible.every((item) => Array.isArray(item.expectedCodes) && item.expectedCodes.length > 0), 'Infeasible case lacks expected diagnostic codes');
  return {
    olderTeachers: teachers.filter((teacher) => number(teacher.age) >= 60).length,
    healthAccommodations: teachers.filter((teacher) => teacher.healthFlag).length,
    expiredQualifications: teachers.filter((teacher) => Date.parse(teacher.qualificationExpiry) < Date.parse('2026-11-16')).length,
    specialNeeds: specialNeeds.length,
    leaves: leaves.length,
    avoidanceRules: avoidances.length,
    infeasibleCases: infeasible.length,
  };
}

function roomFeatures(room) {
  return new Set([
    `CAMPUS_${String(room.campus).toUpperCase()}`,
    bool(room.accessible) ? 'ACCESSIBLE' : null,
    bool(room.hearingAssist) ? 'HEARING_ASSIST' : null,
    bool(room.separateRoom) ? 'SEPARATE_ROOM' : null,
    bool(room.computerReady) ? 'COMPUTER_READY' : null,
  ].filter(Boolean));
}

function validateRound3(root) {
  const teachers = readCsv(root, 'shared/teachers.csv');
  const rooms = readCsv(root, 'shared/rooms.csv');
  const sessions = readCsv(root, 'shared/sessions.csv');
  const roomAllocations = readCsv(root, 'shared/oracle-room-allocations.csv');
  const teacherIds = new Set(teachers.map((teacher) => teacher.teacherId));
  const roomByCode = new Map(rooms.map((room) => [room.roomCode, room]));
  const sessionByCode = new Map(sessions.map((session) => [session.sessionCode, session]));
  const disruptions = readJsonl(root, 'round-3/disruptions.jsonl');
  const byType = new Map(disruptions.map((item) => [item.type, item]));
  for (const type of [
    'TEACHER_SICK_LEAVE', 'TEACHER_MASS_ABSENCE', 'ROOM_UNAVAILABLE',
    'CANDIDATE_COUNT_INCREASE', 'NOTIFICATION_CHANNEL_FAILURE', 'NETWORK_INTERRUPTION_RETRY',
  ]) assert(byType.has(type), `Round 3 disruption ${type} is missing`);
  const single = byType.get('TEACHER_SICK_LEAVE');
  const mass = byType.get('TEACHER_MASS_ABSENCE');
  const roomDown = byType.get('ROOM_UNAVAILABLE');
  assert(single.payload.teacherIds.length === 1 && teacherIds.has(single.payload.teacherIds[0]), 'Single illness case is invalid');
  assert(mass.payload.teacherIds.length === 15, `10% absence must contain 15 teachers, found ${mass.payload.teacherIds.length}`);
  assert(new Set(mass.payload.teacherIds).size === 15 && mass.payload.teacherIds.every((id) => teacherIds.has(id)), 'Mass absence references invalid or duplicate teachers');
  assert(single.minimumRetentionRate >= 0.95, 'Single illness retention threshold is below 95%');
  assert(mass.minimumRetentionRate >= 0.8, 'Mass absence retention threshold is below 80%');
  const slotCount = new Set(sessions.map((session) => `${session.startsAt}|${session.endsAt}`)).size;
  assert(slotCount >= 6, `ROOM_UNAVAILABLE requires at least 6 distributed slots, found ${slotCount}`);
  const downCodes = new Set(roomDown.payload.roomCodes || []);
  const replacementCode = roomDown.payload.replacementRoomCode;
  assert(downCodes.size > 0 && replacementCode && !downCodes.has(replacementCode), 'ROOM_UNAVAILABLE lacks a distinct replacement room');
  const replacement = roomByCode.get(replacementCode);
  assert(replacement && replacement.status === 'ACTIVE', `Replacement room ${replacementCode} is missing or inactive`);
  const replacementFeatures = roomFeatures(replacement);
  const replacementAllocations = roomAllocations.filter((allocation) => allocation.roomCode === replacementCode);
  const affectedAllocations = roomAllocations.filter((allocation) => downCodes.has(allocation.roomCode));
  assert(affectedAllocations.length > 0, 'ROOM_UNAVAILABLE does not affect an allocated room');
  for (const allocation of affectedAllocations) {
    const target = roomByCode.get(allocation.roomCode);
    const session = sessionByCode.get(allocation.sessionCode);
    assert(target && session, `ROOM_UNAVAILABLE references incomplete oracle data for ${allocation.sessionCode}`);
    assert(number(replacement.capacity) >= number(target.capacity), `${replacementCode} capacity is below ${target.roomCode}`);
    for (const feature of roomFeatures(target)) {
      assert(replacementFeatures.has(feature), `${replacementCode} lacks required feature ${feature} from ${target.roomCode}`);
    }
    const overlaps = replacementAllocations.some((candidate) => {
      const other = sessionByCode.get(candidate.sessionCode);
      return Date.parse(other.startsAt) < Date.parse(session.endsAt) && Date.parse(other.endsAt) > Date.parse(session.startsAt);
    });
    assert(!overlaps, `${replacementCode} is occupied during ${allocation.sessionCode}`);
  }
  assert(byType.get('NOTIFICATION_CHANNEL_FAILURE').maximumDuplicateDeliveries === 0, 'Notification duplicate threshold must be zero');
  assert(byType.get('NETWORK_INTERRUPTION_RETRY').maximumDuplicateResources === 0, 'Network retry duplicate threshold must be zero');
  return {
    disruptions: disruptions.length,
    massAbsenceTeachers: mass.payload.teacherIds.length,
    singleRetention: single.minimumRetentionRate,
    massRetention: mass.minimumRetentionRate,
    slots: slotCount,
    roomDownAssignments: affectedAllocations.length,
    replacementRoom: replacementCode,
  };
}

function main() {
  const args = parseArgs(process.argv.slice(2));
  const root = assertInside(args.input, DATA_ROOT);
  const reportPath = assertInside(args.report, DATA_ROOT);
  const manifestPath = path.resolve(root, 'manifest.json');
  assert(fs.existsSync(manifestPath), `Missing manifest: ${manifestPath}`);
  const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
  const checks = [];
  check('manifest.integrity', () => validateManifest(root, manifest), checks);
  check('round1.scale-and-references', () => validateCore(root, manifest), checks);
  check('joint-inputs.reference-integrity-and-feasibility', () => validateJointInputs(root, manifest), checks);
  check('round2.complex-constraints', () => validateRound2(root), checks);
  check('round3.disruptions', () => validateRound3(root), checks);
  const status = checks.every((item) => item.status === 'PASS') ? 'PASS' : 'FAIL';
  const manifestBuffer = fs.readFileSync(manifestPath);
  const report = {
    schemaVersion: 'full-exam-offline-validation/v1',
    status,
    inputRoot: path.relative(DATA_ROOT, root).replaceAll('\\', '/') || '.',
    manifestSha256: sha256(manifestBuffer),
    checks,
    limitations: [
      'Offline validation proves deterministic structure and oracle consistency only.',
      'Oracle room, seat and duty files are validation-only expected outcomes; they are not JOINT planning inputs.',
      'It does not prove that any HTTP API, solver, database migration, approval, publication, notification, or disturbance workflow is operational.',
    ],
  };
  fs.mkdirSync(path.dirname(reportPath), { recursive: true });
  fs.writeFileSync(reportPath, `${JSON.stringify(report, null, 2)}\n`, 'utf8');
  process.stdout.write(`${JSON.stringify({ status, manifestSha256: report.manifestSha256, reportPath, checks: checks.map((item) => ({ id: item.id, status: item.status })) })}\n`);
  if (status !== 'PASS') process.exitCode = 1;
}

try {
  main();
} catch (error) {
  process.stderr.write(`${error.stack || error.message}\n`);
  process.exitCode = 1;
}
