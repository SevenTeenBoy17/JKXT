#!/usr/bin/env node

import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

const SCRIPT_DIR = path.dirname(fileURLToPath(import.meta.url));
const PROJECT_DIR = path.resolve(SCRIPT_DIR, '..', '..');
const DATA_ROOT = path.resolve(PROJECT_DIR, 'test-data', 'full-exam-acceptance-v1');

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

function assertInside(candidate, root) {
  const resolved = path.resolve(candidate);
  const resolvedRoot = path.resolve(root);
  assert(resolved === resolvedRoot || resolved.startsWith(`${resolvedRoot}${path.sep}`), `Path is outside ${resolvedRoot}: ${resolved}`);
  return resolved;
}

function sha256(value) {
  return crypto.createHash('sha256').update(value).digest('hex');
}

function derivedStatus(statuses, requireThreeRounds, roundCount) {
  if (statuses.includes('FAIL')) return 'FAIL';
  if (statuses.includes('UNAVAILABLE') || (requireThreeRounds && roundCount !== 3)) return 'UNAVAILABLE';
  return 'PASS';
}

function validateCheck(check, evidenceIds) {
  assert(check && typeof check === 'object', 'Check entry must be an object');
  assert(typeof check.id === 'string' && check.id.length > 0, 'Check id is missing');
  assert(['PASS', 'FAIL', 'UNAVAILABLE'].includes(check.status), `Check ${check.id} has invalid status ${check.status}`);
  if (check.evidenceId != null) assert(evidenceIds.has(check.evidenceId), `Check ${check.id} references unknown evidence ${check.evidenceId}`);
}

function requireChecks(round, checkIds, requiredIds) {
  for (const id of requiredIds) {
    assert(checkIds.has(id), `Round ${round.round} is missing required check ${id}`);
  }
}

function main() {
  const argument = process.argv[2];
  if (!argument) throw new Error('Usage: node validate-live-result.mjs <live-evidence/.../result.json>');
  const resultPath = assertInside(path.resolve(argument), DATA_ROOT);
  const result = JSON.parse(fs.readFileSync(resultPath, 'utf8'));
  assert(result.schemaVersion === 'full-exam-live-acceptance-result/v1', 'Unexpected result schemaVersion');
  assert(result.mode === 'live-http', 'Result mode must be live-http');
  assert(['PASS', 'FAIL', 'UNAVAILABLE'].includes(result.overallStatus), 'Invalid overallStatus');
  assert(result.safety?.isolatedTestTenantRequired === true, 'Isolated test tenant guard is missing');
  assert(result.safety?.mocksOrFallbacksAccepted === false, 'Mock/fallback acceptance must be false');
  assert(result.safety?.syntheticDataset === true, 'Synthetic dataset guard is missing');
  assert(Array.isArray(result.preflightChecks), 'preflightChecks must be an array');
  assert(Array.isArray(result.rounds), 'rounds must be an array');

  const evidenceIndexPath = assertInside(result.evidenceIndex?.path, DATA_ROOT);
  const evidenceIndexBuffer = fs.readFileSync(evidenceIndexPath);
  assert(sha256(evidenceIndexBuffer) === result.evidenceIndex.sha256, 'Evidence index SHA-256 mismatch');
  const evidenceIndex = JSON.parse(evidenceIndexBuffer.toString('utf8'));
  assert(evidenceIndex.runId === result.runId, 'Evidence index runId mismatch');
  assert(Array.isArray(evidenceIndex.records), 'Evidence records must be an array');
  assert(evidenceIndex.records.length === result.evidenceIndex.records, 'Evidence record count mismatch');
  const evidenceIds = new Set();
  for (const entry of evidenceIndex.records) {
    assert(!evidenceIds.has(entry.evidenceId), `Duplicate evidence id ${entry.evidenceId}`);
    evidenceIds.add(entry.evidenceId);
    const evidencePath = assertInside(path.resolve(path.dirname(evidenceIndexPath), entry.file), path.dirname(evidenceIndexPath));
    const buffer = fs.readFileSync(evidencePath);
    assert(sha256(buffer) === entry.sha256, `Evidence SHA-256 mismatch for ${entry.evidenceId}`);
    const evidence = JSON.parse(buffer.toString('utf8'));
    assert(evidence.evidenceId === entry.evidenceId, `Evidence id mismatch in ${entry.file}`);
  }

  for (const check of result.preflightChecks) validateCheck(check, evidenceIds);
  const seenRounds = new Set();
  for (const round of result.rounds) {
    assert(Number.isInteger(round.round) && round.round >= 1 && round.round <= 3, `Invalid round id ${round.round}`);
    assert(!seenRounds.has(round.round), `Duplicate round ${round.round}`);
    seenRounds.add(round.round);
    assert(Array.isArray(round.checks), `Round ${round.round} checks must be an array`);
    const checkIds = new Set();
    for (const check of round.checks) {
      validateCheck(check, evidenceIds);
      assert(!checkIds.has(check.id), `Round ${round.round} contains duplicate check ${check.id}`);
      checkIds.add(check.id);
    }
    if (round.round === 2 && round.status === 'PASS') {
      requireChecks(round, checkIds, [
        'api.constraintsBulk',
        'round2.INF-ACCESS-001',
        'round2.INF-STAFF-001',
      ]);
    }
    if (round.round === 3 && round.status === 'PASS') {
      const disruptionIds = [
        'R3-SINGLE-SICK',
        'R3-TEN-PERCENT-ABSENT',
        'R3-ROOM-DOWN',
        'R3-NOTIFY-FAIL',
        'R3-NETWORK-RETRY',
      ];
      requireChecks(round, checkIds, [
        'round3.assignment-stable-keys',
        'round3.successor-revisions',
        'round3.successor-validation-complete',
        'round3.deliveries-per-successor',
        'round3.notification-duplicates',
        'round3.R3-CANDIDATE-INCREASE.capacity-gap',
        ...disruptionIds.map((id) => `round3.${id}.successor-validation`),
      ]);
    }
    const expectedRoundStatus = derivedStatus(round.checks.map((check) => check.status), false, 0);
    assert(round.status === expectedRoundStatus, `Round ${round.round} status ${round.status} disagrees with checks (${expectedRoundStatus})`);
  }

  const expectedOverall = derivedStatus(
    [...result.preflightChecks.map((check) => check.status), ...result.rounds.map((round) => round.status)],
    true,
    result.rounds.length,
  );
  assert(result.overallStatus === expectedOverall, `overallStatus ${result.overallStatus} disagrees with evidence (${expectedOverall})`);
  const expectedOnlinePass = expectedOverall === 'PASS' && result.rounds.length === 3 && result.rounds.every((round) => round.status === 'PASS');
  assert(result.onlineAcceptancePassed === expectedOnlinePass, `onlineAcceptancePassed must be ${expectedOnlinePass}`);
  process.stdout.write(`${JSON.stringify({ status: 'PASS', runId: result.runId, overallStatus: result.overallStatus, onlineAcceptancePassed: result.onlineAcceptancePassed, evidenceRecords: evidenceIndex.records.length, rounds: result.rounds.length, resultSha256: sha256(fs.readFileSync(resultPath)) })}\n`);
}

try {
  main();
} catch (error) {
  process.stderr.write(`${error.stack || error.message}\n`);
  process.exitCode = 1;
}
