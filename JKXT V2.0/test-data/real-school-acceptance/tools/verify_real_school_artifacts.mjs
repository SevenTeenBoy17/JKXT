import fs from "node:fs/promises";
import path from "node:path";
import crypto from "node:crypto";
import { fileURLToPath } from "node:url";
import { FileBlob, SpreadsheetFile } from "@oai/artifact-tool";

const SCRIPT_DIR = path.dirname(fileURLToPath(import.meta.url));
const PACKAGE_ROOT = path.resolve(SCRIPT_DIR, "..");
const GENERATED_DIR = path.join(PACKAGE_ROOT, "generated");
const CONFIG_PATH = path.join(PACKAGE_ROOT, "config", "generation-config.json");
const ORACLE_CONFIG_PATH = path.join(PACKAGE_ROOT, "config", "oracle-policy.json");
const REPORT_PATH = path.join(GENERATED_DIR, "verification-report.json");

let config;
let oracleConfig;
let manifest;
const failures = [];
const checks = [];

function check(id, condition, detail) {
  checks.push({ id, status: condition ? "PASS" : "FAIL", detail });
  if (!condition) failures.push({ id, detail });
}

async function readJsonl(name) {
  const text = await fs.readFile(path.join(GENERATED_DIR, name), "utf8");
  return text.split(/\r?\n/).filter(Boolean).map((line, index) => {
    try {
      return JSON.parse(line);
    } catch (error) {
      throw new Error(`${name} line ${index + 1}: ${error.message}`);
    }
  });
}

const sha256File = async (filePath) => crypto.createHash("sha256").update(await fs.readFile(filePath)).digest("hex");
const uniqueCount = (items) => new Set(items).size;
const stableSort = (items, key) => [...items].sort((a, b) => String(a[key]).localeCompare(String(b[key]), "en"));
const dutyWindowOverlapsExam = (window, exam) => {
  const [windowStart, windowEnd] = String(window).split("/");
  const examStart = exam ? `${exam.date}T${exam.start_time}` : null;
  const examEnd = exam ? `${exam.date}T${exam.end_time}` : null;
  return Boolean(windowStart && windowEnd && examStart && examEnd && windowStart < examEnd && windowEnd > examStart);
};

function schemaViolationCount(value, schema) {
  if (!schema || typeof schema !== "object") return 0;
  let violations = 0;
  const serialized = JSON.stringify(value);
  if (Object.hasOwn(schema, "const") && serialized !== JSON.stringify(schema.const)) violations += 1;
  if (schema.enum && !schema.enum.some((candidate) => serialized === JSON.stringify(candidate))) violations += 1;

  const declaredTypes = Array.isArray(schema.type) ? schema.type : schema.type ? [schema.type] : [];
  const actualTypes = value === null
    ? ["null"]
    : Array.isArray(value)
      ? ["array"]
      : Number.isInteger(value)
        ? ["integer", "number"]
        : [typeof value];
  if (declaredTypes.length > 0 && !declaredTypes.some((type) => actualTypes.includes(type))) return violations + 1;

  if (typeof value === "string") {
    if (schema.pattern && !(new RegExp(schema.pattern).test(value))) violations += 1;
    if (schema.minLength !== undefined && value.length < schema.minLength) violations += 1;
    if (schema.format === "date-time" && Number.isNaN(Date.parse(value))) violations += 1;
  }
  if (typeof value === "number") {
    if (schema.minimum !== undefined && value < schema.minimum) violations += 1;
    if (schema.maximum !== undefined && value > schema.maximum) violations += 1;
  }
  if (Array.isArray(value)) {
    if (schema.minItems !== undefined && value.length < schema.minItems) violations += 1;
    if (schema.maxItems !== undefined && value.length > schema.maxItems) violations += 1;
    if (schema.uniqueItems && uniqueCount(value.map((item) => JSON.stringify(item))) !== value.length) violations += 1;
    if (schema.items) violations += value.reduce((sum, item) => sum + schemaViolationCount(item, schema.items), 0);
    if (schema.contains && !value.some((item) => schemaViolationCount(item, schema.contains) === 0)) violations += 1;
  }
  if (value !== null && typeof value === "object" && !Array.isArray(value)) {
    const required = schema.required || [];
    violations += required.filter((key) => !Object.hasOwn(value, key)).length;
    if (schema.minProperties !== undefined && Object.keys(value).length < schema.minProperties) violations += 1;
    if (schema.properties) {
      for (const [key, definition] of Object.entries(schema.properties)) {
        if (Object.hasOwn(value, key)) violations += schemaViolationCount(value[key], definition);
      }
      if (schema.additionalProperties === false) {
        const allowed = new Set(Object.keys(schema.properties));
        violations += Object.keys(value).filter((key) => !allowed.has(key)).length;
      }
    }
  }
  if (schema.not && schemaViolationCount(value, schema.not) === 0) violations += 1;
  if (schema.anyOf && !schema.anyOf.some((branch) => schemaViolationCount(value, branch) === 0)) violations += 1;
  if (schema.allOf) violations += schema.allOf.reduce((sum, branch) => sum + schemaViolationCount(value, branch), 0);
  if (schema.if) {
    const branch = schemaViolationCount(value, schema.if) === 0 ? schema.then : schema.else;
    if (branch) violations += schemaViolationCount(value, branch);
  }
  return violations;
}

function schemaFailures(records, schema) {
  return records.reduce((sum, record) => sum + schemaViolationCount(record, schema), 0);
}

async function workbookContentDigestFromFile(filePath) {
  const workbook = await SpreadsheetFile.importXlsx(await FileBlob.load(filePath));
  const sheets = [];
  for (let index = 0; index < workbook.worksheets.getSheetCount(); index += 1) {
    const sheet = workbook.worksheets.getItemAt(index);
    const used = sheet.getUsedRange();
    sheets.push({
      name: sheet.name,
      values: used ? used.values : [],
      formulas: used ? used.formulas : [],
    });
  }
  return crypto.createHash("sha256").update(Buffer.from(JSON.stringify({ sheets }), "utf8")).digest("hex");
}

async function main() {
config = JSON.parse(await fs.readFile(CONFIG_PATH, "utf8"));
oracleConfig = JSON.parse(await fs.readFile(ORACLE_CONFIG_PATH, "utf8"));
manifest = JSON.parse(await fs.readFile(path.join(GENERATED_DIR, "manifest.json"), "utf8"));
if (process.argv.includes("--self-test-crash")) throw new Error("intentional validator crash self-test");

const [teachers, rooms, exams, scenarios, disruptions, prompts, runs] = await Promise.all([
  readJsonl("teachers.jsonl"),
  readJsonl("rooms.jsonl"),
  readJsonl("exam_sessions.jsonl"),
  readJsonl("scenario_catalog.jsonl"),
  readJsonl("disruptions.jsonl"),
  readJsonl("strategy_prompts.jsonl"),
  readJsonl("run_matrix.jsonl"),
]);
const criteria = JSON.parse(await fs.readFile(path.join(GENERATED_DIR, "acceptance_criteria.json"), "utf8"));
const agentTeam = JSON.parse(await fs.readFile(path.join(GENERATED_DIR, "agent_team.json"), "utf8"));
const travelMatrix = JSON.parse(await fs.readFile(path.join(GENERATED_DIR, "travel-matrix.json"), "utf8"));
const hardConstraintMap = JSON.parse(await fs.readFile(path.join(GENERATED_DIR, "hard-constraint-map.json"), "utf8"));
const evidenceExamples = JSON.parse(await fs.readFile(path.join(GENERATED_DIR, "evidence-contract-examples.json"), "utf8"));
const policyMatrix = JSON.parse(await fs.readFile(path.join(GENERATED_DIR, "exam-policy-matrix.json"), "utf8"));
const regenerationBaseline = JSON.parse(await fs.readFile(path.join(GENERATED_DIR, "regeneration-baseline.json"), "utf8"));
const [teacherSchema, scenarioSchema, disruptionSchema, runSchema, evidenceSchema] = await Promise.all(
  ["teacher-profile.schema.json", "scenario.schema.json", "disruption.schema.json", "run-envelope.schema.json", "evidence-index.schema.json"]
    .map((name) => fs.readFile(path.join(PACKAGE_ROOT, "schemas", name), "utf8").then(JSON.parse)),
);

const expectedTeachers = config.tiers.reduce((sum, tier) => sum + tier.teachers, 0);
const expectedRooms = config.tiers.reduce((sum, tier) => sum + tier.rooms, 0);
const expectedExams = config.tiers.reduce((sum, tier) => sum + tier.exam_rows, 0);
const expectedScenarios = config.tiers.length * config.scenario_family_count * config.severity_levels.length;

check("COUNT_TEACHERS", teachers.length === expectedTeachers, `expected=${expectedTeachers} actual=${teachers.length}`);
check("COUNT_ROOMS", rooms.length === expectedRooms, `expected=${expectedRooms} actual=${rooms.length}`);
check("COUNT_EXAMS", exams.length === expectedExams, `expected=${expectedExams} actual=${exams.length}`);
check("COUNT_SCENARIOS", scenarios.length === expectedScenarios, `expected=${expectedScenarios} actual=${scenarios.length}`);
check("COUNT_DISRUPTIONS", disruptions.length === expectedScenarios * 4, `expected=${expectedScenarios * 4} actual=${disruptions.length}`);
check("COUNT_PROMPTS", prompts.length === config.strategies.length * config.prompt_cases_per_strategy, `expected=${config.strategies.length * config.prompt_cases_per_strategy} actual=${prompts.length}`);
check("COUNT_RUNS", runs.length === config.run_matrix.expected_total_runs, `expected=${config.run_matrix.expected_total_runs} actual=${runs.length}`);
check("COUNT_AGENTS", agentTeam.agents.length === 11, `expected=11 actual=${agentTeam.agents.length}`);
check("A7_ORACLE_PATH_LEASE", agentTeam.agents.some((agent) => agent.id === "A7" && String(agent.owns).includes("config/oracle-policy.json") && String(agent.owns).includes("oracle/**")), "A7 exclusively owns frozen Oracle policy and Oracle outputs");
check("COUNT_CRITERIA", criteria.criteria.length === 32, `expected=32 actual=${criteria.criteria.length}`);
check("SCHEMA_FILES_PARSE", [teacherSchema, scenarioSchema, disruptionSchema, runSchema, evidenceSchema].every((schema) => schema.$schema), "five JSON Schema contracts parsed");
check("TEACHER_SCHEMA_RECURSIVE", schemaFailures(teachers, teacherSchema) === 0, `failures=${schemaFailures(teachers, teacherSchema)}`);
check("SCENARIO_SCHEMA_RECURSIVE", schemaFailures(scenarios, scenarioSchema) === 0, `failures=${schemaFailures(scenarios, scenarioSchema)}`);
check("DISRUPTION_SCHEMA_RECURSIVE", schemaFailures(disruptions, disruptionSchema) === 0, `failures=${schemaFailures(disruptions, disruptionSchema)}`);
check("RUN_SCHEMA_RECURSIVE", schemaFailures(runs, runSchema) === 0, `failures=${schemaFailures(runs, runSchema)}`);
const completedRunEnvelope = structuredClone(runs.find((item) => item.phase !== "ai_prompt"));
if (completedRunEnvelope) {
  completedRunEnvelope.input_manifest_sha256 = "a".repeat(64);
  completedRunEnvelope.app_version = "example-app-version";
  completedRunEnvelope.solver_version = "example-solver-version";
  completedRunEnvelope.started_at = "2026-08-30T00:00:00.000Z";
  completedRunEnvelope.finished_at = "2026-08-30T00:00:01.000Z";
  completedRunEnvelope.raw_result_sha256 = "b".repeat(64);
  completedRunEnvelope.oracle_result_sha256 = "c".repeat(64);
  completedRunEnvelope.application_log_sha256 = "d".repeat(64);
  completedRunEnvelope.terminal_capture_sha256 = "e".repeat(64);
  completedRunEnvelope.baseline_assignment_snapshot_sha256 = "f".repeat(64);
  completedRunEnvelope.terminal_state = "pass";
}
const invalidCompletedRunEnvelope = completedRunEnvelope ? structuredClone(completedRunEnvelope) : null;
if (invalidCompletedRunEnvelope) invalidCompletedRunEnvelope.blocking_capability_gaps = ["must_not_survive_pass"];
check("RUN_SCHEMA_TERMINAL_AND_BASELINE_INVARIANTS",
  Boolean(completedRunEnvelope)
  && schemaViolationCount(completedRunEnvelope, runSchema) === 0
  && schemaViolationCount(invalidCompletedRunEnvelope, runSchema) > 0,
  "completed non-AI run requires a baseline assignment hash and pass rejects blocking capability gaps");
const evidenceExampleList = Array.isArray(evidenceExamples.examples) ? evidenceExamples.examples : [];
const evidenceExampleFailures = schemaFailures(evidenceExampleList, evidenceSchema);
const requiredEvidenceStates = ["pass", "fail", "timeout", "unknown", "blocked", "incomplete"];
const evidenceStateCoverage = requiredEvidenceStates.every((state) => evidenceExampleList.some((item) => item.terminal_state === state));
const negativeEvidenceCases = [];
const addNegativeEvidenceCase = (label, predicate, mutate) => {
  const fixture = evidenceExampleList.find(predicate);
  if (!fixture) {
    negativeEvidenceCases.push({ label, rejected: false, reason: "fixture_missing" });
    return;
  }
  const invalid = structuredClone(fixture);
  mutate(invalid);
  negativeEvidenceCases.push({ label, rejected: schemaViolationCount(invalid, evidenceSchema) > 0 });
};
addNegativeEvidenceCase("pass_missing_assignment", (item) => item.phase === "qualification" && item.terminal_state === "pass", (item) => {
  item.artifacts = item.artifacts.filter((artifact) => artifact.kind !== "assignment_export");
});
addNegativeEvidenceCase("timeout_missing_terminal", (item) => item.terminal_state === "timeout", (item) => {
  item.artifacts = item.artifacts.filter((artifact) => !["sse_terminal_event", "poll_terminal_response"].includes(artifact.kind));
});
addNegativeEvidenceCase("blocked_missing_blocking_gap", (item) => item.terminal_state === "blocked", (item) => {
  item.blocking_capability_gaps = [];
});
addNegativeEvidenceCase("unknown_missing_oracle_reason", (item) => item.terminal_state === "unknown", (item) => {
  item.oracle.reason_code = null;
});
addNegativeEvidenceCase("incomplete_missing_log", (item) => item.terminal_state === "incomplete", (item) => {
  item.artifacts = item.artifacts.filter((artifact) => artifact.kind !== "runtime_log_index");
});
addNegativeEvidenceCase("ai_missing_terminal", (item) => item.phase === "ai_prompt" && item.terminal_state === "pass", (item) => {
  item.artifacts = item.artifacts.filter((artifact) => !["sse_terminal_event", "poll_terminal_response"].includes(artifact.kind));
});
addNegativeEvidenceCase("pass_with_not_evaluable_oracle", (item) => item.terminal_state === "pass", (item) => {
  item.oracle.hard_gate = "NOT_EVALUABLE";
  item.oracle.reason_code = "CONTRADICTORY_PASS";
  Object.keys(item.hard_gate_results).forEach((gate) => { item.hard_gate_results[gate] = "NOT_EVALUABLE"; });
});
addNegativeEvidenceCase("pass_with_blocking_gap", (item) => item.terminal_state === "pass", (item) => {
  item.blocking_capability_gaps = ["contradictory_blocking_gap"];
});
addNegativeEvidenceCase("fail_without_failed_hard_gate", (item) => item.terminal_state === "fail", (item) => {
  Object.keys(item.hard_gate_results).forEach((gate) => { item.hard_gate_results[gate] = "PASS"; });
});
addNegativeEvidenceCase("timeout_with_pass_oracle", (item) => item.terminal_state === "timeout", (item) => {
  item.oracle.hard_gate = "PASS";
  item.oracle.reason_code = null;
  Object.keys(item.hard_gate_results).forEach((gate) => { item.hard_gate_results[gate] = "PASS"; });
});
check("EVIDENCE_SCHEMA_POSITIVE_NEGATIVE", evidenceExampleFailures === 0
  && evidenceStateCoverage
  && negativeEvidenceCases.every((item) => item.rejected),
`valid_failures=${evidenceExampleFailures} state_coverage=${evidenceStateCoverage} negative_cases=${JSON.stringify(negativeEvidenceCases)}`);

check("UNIQUE_TEACHERS", uniqueCount(teachers.map((item) => item.teacher_no)) === teachers.length, "teacher_no values are unique");
check("UNIQUE_EMAILS", uniqueCount(teachers.map((item) => item.email)) === teachers.length, "synthetic emails are unique");
check("UNIQUE_CONTACT_TOKENS", uniqueCount(teachers.map((item) => item.contact_token)) === teachers.length, "non-routable contact tokens are unique");
check("PHONE_FIELDS_BLANK", teachers.every((item) => item.phone === ""), "all phone fields are blank and cannot trigger real-world delivery");
check("UNIQUE_ROOMS", uniqueCount(rooms.map((item) => item.room_code)) === rooms.length, "room_code values are unique");
check("UNIQUE_EXAMS", uniqueCount(exams.map((item) => item.exam_session_id)) === exams.length, "exam_session_id values are unique");
check("UNIQUE_SCENARIOS", uniqueCount(scenarios.map((item) => item.scenario_id)) === scenarios.length, "scenario_id values are unique");
check("UNIQUE_DISRUPTIONS", uniqueCount(disruptions.map((item) => item.disruption_id)) === disruptions.length, "disruption_id values are unique");
check("UNIQUE_PROMPTS", uniqueCount(prompts.map((item) => item.prompt_case_id)) === prompts.length, "prompt_case_id values are unique");
check("UNIQUE_RUNS", uniqueCount(runs.map((item) => item.run_id)) === runs.length, "run_id values are unique");

const roomMap = new Map(rooms.map((item) => [item.room_code, item]));
const teacherMap = new Map(teachers.map((item) => [item.teacher_no, item]));
const examMap = new Map(exams.map((item) => [item.exam_session_id, item]));
const scenarioMap = new Map(scenarios.map((item) => [item.scenario_id, item]));
const promptMap = new Map(prompts.map((item) => [item.prompt_case_id, item]));
const disruptionMap = new Map(disruptions.map((item) => [item.disruption_id, item]));
const missingRooms = exams.filter((item) => !roomMap.has(item.room_code));
const capacityViolations = exams.filter((item) => roomMap.has(item.room_code) && item.students > roomMap.get(item.room_code).exam_capacity);
const duplicateRoomSlots = [];
const roomSlots = new Set();
for (const exam of exams) {
  const key = `${exam.room_code}|${exam.date}|${exam.start_time}`;
  if (roomSlots.has(key)) duplicateRoomSlots.push(key);
  roomSlots.add(key);
}
check("EXAM_ROOM_REFERENCES", missingRooms.length === 0, `missing_room_refs=${missingRooms.length}`);
check("EXAM_CAPACITY", capacityViolations.length === 0, `capacity_violations=${capacityViolations.length}`);
check("ROOM_SLOT_UNIQUENESS", duplicateRoomSlots.length === 0, `duplicate_room_slots=${duplicateRoomSlots.length}`);
check("INVIGILATOR_COUNTS", exams.every((item) => Number.isInteger(item.invigilator_count) && item.invigilator_count >= 1), "every exam requires at least one invigilator");

const missingScenarioForDisruption = disruptions.filter((item) => !scenarioMap.has(item.scenario_id));
const scenarioDisruptionRefs = scenarios.flatMap((scenario) => Array.isArray(scenario.disruption_ids)
  ? scenario.disruption_ids.filter((id) => !disruptionMap.has(id))
  : [`${scenario.scenario_id}:missing-disruption-ids`]);
check("DISRUPTION_SCENARIO_REFERENCES", missingScenarioForDisruption.length === 0, `missing_scenarios=${missingScenarioForDisruption.length}`);
check("SCENARIO_DISRUPTION_REFERENCES", scenarioDisruptionRefs.length === 0, `missing_disruptions=${scenarioDisruptionRefs.length}`);
const mutationPayloadFailures = disruptions.filter((item) => {
  const operations = item.mutation_payload?.operations || [];
  const digest = crypto.createHash("sha256").update(Buffer.from(JSON.stringify(operations), "utf8")).digest("hex");
  const targets = item.targets || [];
  return digest !== item.mutation_payload?.operations_sha256
    || operations.length !== targets.length
    || operations.some((operation, index) => operation.entity_type !== targets[index]?.entity_type || operation.entity_id !== targets[index]?.entity_id)
    || item.mutation_payload?.apply_mode !== "copy_on_write";
});
check("IMMUTABLE_MUTATION_PAYLOADS", mutationPayloadFailures.length === 0, `failures=${mutationPayloadFailures.length}`);
const beforeSnapshotFailures = disruptions.flatMap((item) => item.mutation_payload?.operations || []).filter((operation) => {
  const source = operation.entity_type === "teacher"
    ? teacherMap.get(operation.entity_id)
    : operation.entity_type === "room"
      ? roomMap.get(operation.entity_id)
      : examMap.get(operation.entity_id);
  return !source || !operation.before || typeof operation.before !== "object"
    || Object.entries(operation.before).some(([key, value]) => JSON.stringify(source[key]) !== JSON.stringify(value));
});
check("BEFORE_SNAPSHOTS_MATCH_BASELINE", beforeSnapshotFailures.length === 0, `failures=${beforeSnapshotFailures.length}`);

const realisticTargetFailures = [];
for (const disruption of disruptions) {
  const scenario = scenarioMap.get(disruption.scenario_id);
  const familyId = scenario?.family_id;
  if (!scenario) realisticTargetFailures.push(`${disruption.disruption_id}:missing-scenario`);
  for (const operation of disruption.mutation_payload?.operations || []) {
    if (operation.entity_type !== "teacher") continue;
    const teacher = teacherMap.get(operation.entity_id);
    if (!teacher) {
      realisticTargetFailures.push(`${disruption.disruption_id}:missing-teacher`);
      continue;
    }
    const referenceExamId = operation.changes?.excluded_exam_session_ids?.[0]
      || operation.changes?.forced_exam_session_id
      || operation.changes?.blocked_exam_session_ids?.[0];
    const referenceExam = referenceExamId ? examMap.get(referenceExamId) : null;
    if (familyId === "F03" && (!referenceExam || !teacher.subjects.includes(referenceExam.subject))) realisticTargetFailures.push(`${disruption.disruption_id}:subject`);
    if (familyId === "F04" && (!referenceExam || (!teacher.teaching_classes.some((classId) => referenceExam.class_ids.includes(classId)) && !referenceExam.class_ids.includes(teacher.homeroom_class)))) realisticTargetFailures.push(`${disruption.disruption_id}:class`);
    if (familyId === "F05" && (teacher.leadership_role === "none"
      || !referenceExam
      || !teacher.leadership_duty_windows.some((window) => dutyWindowOverlapsExam(window, referenceExam))
      || !teacher.leadership_duty_windows.includes(operation.changes?.conflict_duty_window)
      || operation.changes?.availability?.includes("NONE"))) realisticTargetFailures.push(`${disruption.disruption_id}:leadership-window`);
    if (["F06", "F15"].includes(familyId) && !teacher.qualified_roles.includes("chief")) realisticTargetFailures.push(`${disruption.disruption_id}:qualification`);
    if (["F07", "F08", "F09", "F10"].includes(familyId) && teacher.employment_status !== "active") realisticTargetFailures.push(`${disruption.disruption_id}:inactive-baseline`);
    if (["F11", "F12", "F13"].includes(familyId) && !teacher.accommodation_codes.includes("NONE")) realisticTargetFailures.push(`${disruption.disruption_id}:preexisting-accommodation`);
    if (familyId === "F21" && !teacher.reserve_eligible) realisticTargetFailures.push(`${disruption.disruption_id}:not-reserve`);
  }
}
check("REALISTIC_TARGET_ATTRIBUTES", realisticTargetFailures.length === 0, `failures=${realisticTargetFailures.length}`);
const baselineSemanticMutationFailures = disruptions.filter((item) => ["F01", "F02"].includes(scenarioMap.get(item.scenario_id)?.family_id)).filter((item) =>
  (item.mutation_payload?.operations || []).some((operation) => Object.hasOwn(operation.changes || {}, "scenario_pressure")
    || (scenarioMap.get(item.scenario_id)?.family_id === "F01" && operation.changes?.invigilator_count !== operation.before?.invigilator_count)
    || (scenarioMap.get(item.scenario_id)?.family_id === "F02" && ["date", "start_time", "end_time"].some((key) => operation.changes?.[key] !== operation.before?.[key]))));
check("BASELINE_PARALLEL_SEMANTIC_MUTATIONS", baselineSemanticMutationFailures.length === 0, `failures=${baselineSemanticMutationFailures.length}`);
const f10Ratios = new Set(disruptions.filter((item) => scenarioMap.get(item.scenario_id)?.family_id === "F10").map((item) => item.affected_ratio));
check("F10_GROUP_ABSENCE_RATIOS", [0.05, 0.10, 0.20, 0.30].every((ratio) => f10Ratios.has(ratio)), `ratios=${JSON.stringify([...f10Ratios].sort())}`);
const f10TargetCountFailures = disruptions.filter((item) => scenarioMap.get(item.scenario_id)?.family_id === "F10")
  .filter((item) => !Array.isArray(item.targets) || item.targets.length !== Math.ceil(item.target_pool_size * item.affected_ratio));
check("F10_TARGET_COUNTS", f10TargetCountFailures.length === 0, `failures=${f10TargetCountFailures.length}`);
const f23Disruptions = disruptions.filter((item) => scenarioMap.get(item.scenario_id)?.family_id === "F23");
check("F23_INVALID_INPUT_OVERLAYS", f23Disruptions.every((item) => item.input_overlay?.overlay_type === "invalid_input"), `covered=${f23Disruptions.filter((item) => item.input_overlay?.overlay_type === "invalid_input").length}/${f23Disruptions.length}`);
const f24Scenarios = scenarios.filter((item) => item.family_id === "F24");
const f24Disruptions = disruptions.filter((item) => scenarioMap.get(item.scenario_id)?.family_id === "F24");
check("F24_ALWAYS_INFEASIBLE", f24Scenarios.every((item) => item.expected_feasibility === "infeasible") && f24Disruptions.every((item) => item.input_overlay?.overlay_type === "resource_capacity"), `scenarios=${f24Scenarios.length} disruptions=${f24Disruptions.length}`);
const activeTeacherCounts = new Map(config.tiers.map((tier) => [tier.id, teachers.filter((teacher) => teacher.teacher_no.startsWith(`RS-${tier.id}-`) && teacher.employment_status === "active").length]));
const f24CapacityFailures = f24Disruptions.filter((item) => {
  const scenario = scenarioMap.get(item.scenario_id);
  const activeCount = scenario ? activeTeacherCounts.get(scenario.tier) : undefined;
  const requestedCount = item.input_overlay?.changes?.invigilator_count;
  return !scenario || item.input_overlay?.overlay_type !== "resource_capacity"
    || !Number.isFinite(requestedCount)
    || !Number.isFinite(activeCount)
    || requestedCount <= activeCount;
});
check("F24_CAPACITY_EXCEEDED", f24CapacityFailures.length === 0, `failures=${f24CapacityFailures.length}`);
const infeasibleProofFailures = disruptions.filter((item) => scenarioMap.get(item.scenario_id)?.expected_feasibility === "infeasible").filter((item) => {
  const scenario = scenarioMap.get(item.scenario_id);
  if (!scenario) return true;
  if (scenario.family_id === "F17") {
    const requiredMinutes = item.input_overlay?.changes?.minimum_travel_minutes;
    const availableMinutes = item.input_overlay?.available_transfer_minutes;
    return item.input_overlay?.overlay_type !== "travel_assignment"
      || !Number.isFinite(requiredMinutes)
      || !Number.isFinite(availableMinutes)
      || requiredMinutes <= availableMinutes;
  }
  const activeCount = activeTeacherCounts.get(scenario.tier);
  const requestedCount = item.input_overlay?.changes?.invigilator_count;
  return item.input_overlay?.overlay_type !== "resource_capacity"
    || item.input_overlay?.target?.entity_type !== "exam_session"
    || !examMap.has(item.input_overlay?.target?.entity_id)
    || !Number.isFinite(requestedCount)
    || !Number.isFinite(activeCount)
    || requestedCount <= activeCount;
});
check("INFEASIBLE_ORACLE_HAS_CONSTRUCTIVE_PROOF", infeasibleProofFailures.length === 0, `failures=${infeasibleProofFailures.length}`);
const travelRouteMap = new Map(travelMatrix.routes.map((route) => [`${route.tier}|${route.severity}|${route.from_campus_id}|${route.to_campus_id}`, route]));
const f17Disruptions = disruptions.filter((item) => scenarioMap.get(item.scenario_id)?.family_id === "F17");
check("F17_TRAVEL_OVERLAYS", f17Disruptions.every((item) => item.input_overlay?.overlay_type === "travel_assignment"
  && scenarioMap.has(item.scenario_id)
  && travelRouteMap.has(`${scenarioMap.get(item.scenario_id)?.tier}|${item.severity}|${item.input_overlay.from_campus_id}|${item.input_overlay.to_campus_id}`)), `covered=${f17Disruptions.filter((item) => item.input_overlay?.overlay_type === "travel_assignment").length}/${f17Disruptions.length}`);
const expectedTravelMinutes = { normal: 30, tense: 45, severe: 60, infeasible: 120 };
const f17MinuteFailures = f17Disruptions.filter((item) => {
  const scenario = scenarioMap.get(item.scenario_id);
  const route = scenario ? travelRouteMap.get(`${scenario.tier}|${item.severity}|${item.input_overlay?.from_campus_id}|${item.input_overlay?.to_campus_id}`) : null;
  return item.input_overlay?.changes?.minimum_travel_minutes !== expectedTravelMinutes[item.severity]
    || route?.travel_minutes !== expectedTravelMinutes[item.severity]
    || (item.severity === "infeasible" && item.input_overlay?.available_transfer_minutes >= expectedTravelMinutes.infeasible);
});
check("F17_SEVERITY_MINUTES", f17MinuteFailures.length === 0, `failures=${f17MinuteFailures.length}`);
const expectedActivationBySuffix = { D1: "T-7d", D2: "T-24h", D3: "T-2h", D4: "T-30m" };
const disruptionBranchFailures = disruptions.filter((item) => {
  const scenario = scenarioMap.get(item.scenario_id);
  const suffix = item.disruption_id.split("-").at(-1);
  return !scenario || item.severity !== scenario.severity || item.activation !== expectedActivationBySuffix[suffix];
});
check("D1_D4_BRANCH_SEMANTICS", disruptionBranchFailures.length === 0, `failures=${disruptionBranchFailures.length}`);

const invalidRunRefs = runs.filter((run) => {
  if (run.phase === "ai_prompt") return !promptMap.has(run.prompt_case_id) || run.disruption_id !== null || run.overlay_application !== "none";
  const scenario = scenarioMap.get(run.scenario_id);
  return !scenario
    || !Array.isArray(scenario.disruption_ids)
    || !scenario.disruption_ids.includes(run.disruption_id)
    || !disruptionMap.has(run.disruption_id)
    || run.overlay_application !== "single_disruption_copy_on_write";
});
check("RUN_REFERENCES", invalidRunRefs.length === 0, `invalid_run_refs=${invalidRunRefs.length}`);
const baselineBindingFailures = (runList) => runList.filter((run) => run.phase !== "ai_prompt"
  && run.baseline_parent_id !== `BASELINE-${run.scenario_id}`);
const completedBaselineHashes = new Map();
for (const run of runs.filter((item) => item.phase !== "ai_prompt" && item.terminal_state !== "pending")) {
  if (!completedBaselineHashes.has(run.scenario_id)) completedBaselineHashes.set(run.scenario_id, new Set());
  completedBaselineHashes.get(run.scenario_id).add(run.baseline_assignment_snapshot_sha256);
}
const divergentCompletedBaselineScenarios = [...completedBaselineHashes.entries()]
  .filter(([, hashes]) => hashes.size !== 1)
  .map(([scenarioId]) => scenarioId);
const corruptedBaselineRuns = runs.filter((run) => run.phase === "smoke").map((run) => ({ ...run }));
if (corruptedBaselineRuns.length > 0) corruptedBaselineRuns[0].baseline_parent_id = "BASELINE-WRONG-PARENT";
check("BASELINE_PARENT_COW_BINDING",
  baselineBindingFailures(runs).length === 0
  && divergentCompletedBaselineScenarios.length === 0
  && baselineBindingFailures(corruptedBaselineRuns).length === 1,
  `binding_failures=${baselineBindingFailures(runs).length} divergent_completed=${divergentCompletedBaselineScenarios.length} negative_fixture_rejected=${baselineBindingFailures(corruptedBaselineRuns).length === 1}`);
const exactOnceDisruptionCoverage = (runList) => {
  const expectedIds = new Set(disruptions.map((item) => item.disruption_id));
  const counts = new Map();
  for (const run of runList) counts.set(run.disruption_id, (counts.get(run.disruption_id) || 0) + 1);
  const unexpected = [...counts.keys()].filter((id) => !expectedIds.has(id));
  const missing = [...expectedIds].filter((id) => !counts.has(id));
  const duplicates = [...counts.entries()].filter(([, count]) => count !== 1).map(([id]) => id);
  return { valid: runList.length === disruptions.length && unexpected.length === 0 && missing.length === 0 && duplicates.length === 0, unexpected, missing, duplicates };
};
const smokeRuns = runs.filter((run) => run.phase === "smoke");
const smokeCoverage = exactOnceDisruptionCoverage(smokeRuns);
const corruptedSmokeRuns = smokeRuns.map((run) => ({ ...run }));
if (corruptedSmokeRuns.length > 1) corruptedSmokeRuns[0].disruption_id = corruptedSmokeRuns[1].disruption_id;
const corruptedSmokeCoverage = exactOnceDisruptionCoverage(corruptedSmokeRuns);
check("SMOKE_EXACT_DISRUPTION_COVERAGE",
  smokeCoverage.valid && !corruptedSmokeCoverage.valid,
  `runs=${smokeRuns.length} disruptions=${disruptions.length} missing=${smokeCoverage.missing.length} duplicates=${smokeCoverage.duplicates.length} negative_fixture_rejected=${!corruptedSmokeCoverage.valid}`);
const uncoveredRunDisruptions = [];
const allCoveredDisruptionIds = new Set(runs.filter((run) => run.phase !== "ai_prompt").map((run) => run.disruption_id));
for (const disruption of disruptions) {
  if (!allCoveredDisruptionIds.has(disruption.disruption_id)) uncoveredRunDisruptions.push(`all:${disruption.disruption_id}`);
}
for (const phase of ["qualification", "resilience"]) {
  const phaseScenarioIds = new Set(runs.filter((run) => run.phase === phase).map((run) => run.scenario_id));
  for (const scenarioId of phaseScenarioIds) {
    const covered = new Set(runs.filter((run) => run.phase === phase && run.scenario_id === scenarioId).map((run) => run.disruption_id));
    for (const disruptionId of scenarioMap.get(scenarioId)?.disruption_ids || []) {
      if (!covered.has(disruptionId)) uncoveredRunDisruptions.push(`${phase}:${disruptionId}`);
    }
  }
}
check("D1_D4_INDEPENDENT_RUN_COVERAGE", uncoveredRunDisruptions.length === 0, `uncovered=${uncoveredRunDisruptions.length}`);
check("RUN_PHASE_COUNTS",
  smokeRuns.length === 1536
  && runs.filter((item) => item.phase === "qualification").length === 11520
  && runs.filter((item) => item.phase === "resilience").length === 15360
  && runs.filter((item) => item.phase === "ai_prompt").length === 960,
  JSON.stringify({
    smoke: smokeRuns.length,
    qualification: runs.filter((item) => item.phase === "qualification").length,
    resilience: runs.filter((item) => item.phase === "resilience").length,
    ai_prompt: runs.filter((item) => item.phase === "ai_prompt").length,
  }));

const privacyViolations = teachers.filter((teacher) => teacher.synthetic !== true
  || !teacher.email.endsWith("@example.invalid")
  || teacher.phone !== ""
  || !teacher.contact_token.startsWith("TEST-PHONE-")
  || Object.hasOwn(teacher, "diagnosis"));
check("SYNTHETIC_PRIVACY", privacyViolations.length === 0, `privacy_violations=${privacyViolations.length}`);
check("OUTBOUND_NOTIFICATIONS_DISABLED", config.privacy.external_email_sms_disabled === true
  && config.privacy.in_app_notifications_only === true
  && policyMatrix.notification_safety?.external_email_sms_disabled === true
  && policyMatrix.notification_safety?.in_app_only === true, "external email/SMS disabled; test notifications stay in-app only");
const seniorTeachers = teachers.filter((teacher) => teacher.age_band === "60_plus");
const nonSeniorTeachers = teachers.filter((teacher) => teacher.age_band !== "60_plus");
const reducedLoad = (teacher) => teacher.max_daily_duties === 1 || teacher.max_consecutive_duties === 1 || teacher.accommodation_codes.some((code) => code !== "NONE");
check("AGE_NOT_DIRECT_RESTRICTION", seniorTeachers.some((teacher) => !reducedLoad(teacher)) && nonSeniorTeachers.some(reducedLoad), `senior_unrestricted=${seniorTeachers.filter((teacher) => !reducedLoad(teacher)).length} non_senior_restricted=${nonSeniorTeachers.filter(reducedLoad).length}`);
const canonicalDataDigest = crypto.createHash("sha256").update(Buffer.from(JSON.stringify({
  teachers: stableSort(teachers, "teacher_no"),
  rooms: stableSort(rooms, "room_code"),
  exams: stableSort(exams, "exam_session_id"),
  scenarios: stableSort(scenarios, "scenario_id"),
  disruptions: stableSort(disruptions, "disruption_id"),
  prompts: stableSort(prompts, "prompt_case_id"),
  runs: stableSort(runs, "run_id"),
  travel_routes: travelMatrix.routes,
}), "utf8")).digest("hex");
check("CANONICAL_DATA_DIGEST", canonicalDataDigest === manifest.canonical_data_digest, `expected=${manifest.canonical_data_digest} actual=${canonicalDataDigest}`);
const regeneration = manifest.regeneration_evidence || {};
const baselinePath = path.join(PACKAGE_ROOT, regeneration.prior_manifest_path || "missing");
let baselineHash = null;
try {
  baselineHash = await sha256File(baselinePath);
} catch {
  baselineHash = null;
}
const independentlyReproducibleRegeneration = regeneration.status === "MATCH"
  && baselineHash === regeneration.prior_manifest_sha256
  && regenerationBaseline.generator_version === regeneration.prior_generator_version
  && regenerationBaseline.seed === regeneration.prior_seed
  && regenerationBaseline.config_sha256 === regeneration.prior_config_sha256
  && regenerationBaseline.oracle_version === regeneration.prior_oracle_version
  && regenerationBaseline.oracle_config_sha256 === regeneration.prior_oracle_config_sha256
  && regeneration.current_oracle_version === oracleConfig.oracle_version
  && regeneration.current_oracle_config_sha256 === manifest.oracle_config_sha256
  && regenerationBaseline.canonical_data_digest === manifest.canonical_data_digest
  && JSON.stringify(regenerationBaseline.workbook_content_digests) === JSON.stringify(manifest.workbook_content_digests)
  && regeneration.generator_version_match === true
  && regeneration.seed_match === true
  && regeneration.config_sha256_match === true
  && regeneration.oracle_version_match === true
  && regeneration.oracle_config_sha256_match === true
  && regeneration.canonical_data_digest_match === true
  && regeneration.workbook_content_digests_match === true;
check("SAME_SEED_REGENERATION", independentlyReproducibleRegeneration, `status=${regeneration.status || "MISSING"} baseline_hash_match=${baselineHash === regeneration.prior_manifest_sha256}`);
const workbookDigestEntries = Object.entries(manifest.workbook_content_digests || {});
const workbookDigestMismatches = [];
for (const [relativePath, expectedDigest] of workbookDigestEntries) {
  const workbookPath = path.join(PACKAGE_ROOT, "outputs", manifest.thread_id, relativePath);
  try {
    const actualDigest = await workbookContentDigestFromFile(workbookPath);
    if (actualDigest !== expectedDigest) workbookDigestMismatches.push({ relativePath, expectedDigest, actualDigest });
  } catch (error) {
    workbookDigestMismatches.push({ relativePath, expectedDigest, error: error.message });
  }
}
check("WORKBOOK_CONTENT_DIGESTS",
  workbookDigestEntries.length === 13 && workbookDigestMismatches.length === 0,
  `expected=13 actual=${workbookDigestEntries.length} mismatches=${workbookDigestMismatches.length}`);
const expectedGateIds = ["G0-01", "G0-02", "G0-03", "G0-04", "G0-05", ...Array.from({ length: 12 }, (_, index) => `H${String(index + 1).padStart(2, "0")}`), ...Array.from({ length: 14 }, (_, index) => `Q${String(index + 1).padStart(2, "0")}`), "G3-01"];
check("GATE_COVERAGE", expectedGateIds.every((id) => criteria.criteria.some((item) => item.id === id)), `expected_ids=${expectedGateIds.length}`);
check("SCORE_CATEGORY_WEIGHTS", criteria.score_categories.reduce((sum, item) => sum + item.weight, 0) === 100 && criteria.score_categories.every((item) => item.criterion_ids.length > 0), "weights sum to 100 and every category is computable");
const expectedReleaseMatrix = {
  feasible_success_rate: { S: 1, M: 1, L: 0.98, XL: "profile_only" },
  solve_p95_seconds: { S: 15, M: 45, L: 120, XL: "profile_only" },
  recovery_p95_seconds: { S: 15, M: 45, L: 120, XL: "profile_only" },
  normal_weighted_load_gini: { S: 0.12, M: 0.12, L: 0.12, XL: 0.18 },
  emergency_weighted_load_gini: { S: 0.18, M: 0.18, L: 0.18, XL: "profile_only" },
  single_absence_preservation_rate: { S: 0.95, M: 0.95, L: 0.95, XL: 0.90 },
  group_absence_preservation_rate: { S: 0.80, M: 0.80, L: 0.80, XL: "profile_only" },
};
check("RELEASE_MATRIX_MACHINE_READABLE", JSON.stringify(criteria.release_matrix) === JSON.stringify(expectedReleaseMatrix), "tier-specific solve/recovery/fairness/preservation thresholds are exact");
const expectedCriterionSelectors = {
  Q04: { tiers: ["S", "M", "L", "XL"], scenario_severity: "normal", threshold_source: "release_matrix.normal_weighted_load_gini", threshold_precedence: "selector_source", profile_only_behavior: "collect_report_exclude_release_denominator" },
  Q05: { tiers: ["S", "M", "L", "XL"], phases: ["resilience"], threshold_source: "release_matrix.emergency_weighted_load_gini", threshold_precedence: "selector_source", profile_only_behavior: "collect_report_exclude_release_denominator" },
  Q06: { tiers: ["S", "M", "L", "XL"], disruption_families: ["F07", "F08", "F09"], threshold_source: "release_matrix.single_absence_preservation_rate", threshold_precedence: "selector_source", profile_only_behavior: "collect_report_exclude_release_denominator" },
  Q07: { tiers: ["S", "M", "L", "XL"], disruption_families: ["F10"], threshold_source: "release_matrix.group_absence_preservation_rate", threshold_precedence: "selector_source", profile_only_behavior: "collect_report_exclude_release_denominator" },
  Q12: { tiers: ["S"], phase_metrics: { qualification: "solve_p95_seconds", resilience: "recovery_p95_seconds" }, aggregate: "max", threshold_sources: { qualification: "release_matrix.solve_p95_seconds.S", resilience: "release_matrix.recovery_p95_seconds.S" }, threshold_precedence: "selector_source" },
  Q13: { tiers: ["M"], phase_metrics: { qualification: "solve_p95_seconds", resilience: "recovery_p95_seconds" }, aggregate: "max", threshold_sources: { qualification: "release_matrix.solve_p95_seconds.M", resilience: "release_matrix.recovery_p95_seconds.M" }, threshold_precedence: "selector_source" },
  Q14: { tiers: ["L"], phase_metrics: { qualification: "solve_p95_seconds", resilience: "recovery_p95_seconds" }, aggregate: "max", threshold_sources: { qualification: "release_matrix.solve_p95_seconds.L", resilience: "release_matrix.recovery_p95_seconds.L" }, threshold_precedence: "selector_source" },
};
const criterionSelectorFailures = Object.entries(expectedCriterionSelectors).filter(([id, selector]) => {
  const criterion = criteria.criteria.find((item) => item.id === id);
  return !criterion || JSON.stringify(criterion.selector) !== JSON.stringify(selector);
});
check("CRITERION_TIER_PHASE_SELECTORS", criterionSelectorFailures.length === 0, `failures=${criterionSelectorFailures.length}`);
const resolveJsonPath = (root, dottedPath) => String(dottedPath).split(".").reduce((value, key) => (value && Object.hasOwn(value, key) ? value[key] : undefined), root);
const tierSelectorExpectations = {
  Q04: { metric: "normal_weighted_load_gini", operator: "<=", values: { S: 0.12, M: 0.12, L: 0.12, XL: 0.18 } },
  Q05: { metric: "emergency_weighted_load_gini", operator: "<=", values: { S: 0.18, M: 0.18, L: 0.18, XL: "profile_only" } },
  Q06: { metric: "single_absence_preservation_rate", operator: ">=", values: { S: 0.95, M: 0.95, L: 0.95, XL: 0.90 } },
  Q07: { metric: "group_absence_preservation_rate", operator: ">=", values: { S: 0.80, M: 0.80, L: 0.80, XL: "profile_only" } },
};
const tierSelectorResolutionErrors = (criteriaRoot, criterion, expectation) => {
  const errors = [];
  const source = criterion?.selector?.threshold_source;
  const thresholdMap = source ? resolveJsonPath(criteriaRoot, source) : undefined;
  if (!criterion || criterion.metric !== expectation.metric || criterion.operator !== expectation.operator) errors.push("criterion_metric_or_operator");
  if (criterion?.threshold_semantics !== "legacy_default_only" || criterion?.selector?.threshold_precedence !== "selector_source") errors.push("threshold_precedence");
  if (criterion?.selector?.profile_only_behavior !== "collect_report_exclude_release_denominator") errors.push("profile_only_behavior");
  if (String(source).split(".").at(-1) !== expectation.metric || !thresholdMap || typeof thresholdMap !== "object" || Array.isArray(thresholdMap)) errors.push("threshold_source");
  for (const tier of criterion?.selector?.tiers || []) {
    const value = thresholdMap?.[tier];
    if (!Object.hasOwn(expectation.values, tier) || JSON.stringify(value) !== JSON.stringify(expectation.values[tier])) errors.push(`tier_value:${tier}`);
    if (value !== "profile_only" && !Number.isFinite(value)) errors.push(`tier_type:${tier}`);
  }
  for (const tier of Object.keys(expectation.values)) {
    if (!criterion?.selector?.tiers?.includes(tier) || !Object.hasOwn(thresholdMap || {}, tier)) errors.push(`tier_missing:${tier}`);
  }
  return errors;
};
const selectorResolutionFailures = Object.entries(tierSelectorExpectations).flatMap(([id, expectation]) => {
  const criterion = criteria.criteria.find((item) => item.id === id);
  return tierSelectorResolutionErrors(criteria, criterion, expectation).map((error) => `${id}:${error}`);
});
const phaseSelectorExpectations = {
  Q12: { tier: "S", metric: "S_solve_and_recovery_p95_seconds_max", operator: "<=", threshold: 15 },
  Q13: { tier: "M", metric: "M_solve_and_recovery_p95_seconds_max", operator: "<=", threshold: 45 },
  Q14: { tier: "L", metric: "L_solve_and_recovery_p95_seconds_max", operator: "<=", threshold: 120 },
};
const phaseSelectorResolutionErrors = (criteriaRoot, criterion, expectation) => {
  const errors = [];
  const expectedMetrics = { qualification: "solve_p95_seconds", resilience: "recovery_p95_seconds" };
  if (!criterion || criterion.metric !== expectation.metric || criterion.operator !== expectation.operator) errors.push("criterion_metric_or_operator");
  if (criterion?.threshold !== expectation.threshold || criterion?.threshold_semantics !== "legacy_default_only") errors.push("aggregate_threshold");
  if (criterion?.selector?.aggregate !== "max" || criterion?.selector?.threshold_precedence !== "selector_source") errors.push("selector_precedence_or_aggregate");
  if (JSON.stringify(criterion?.selector?.tiers) !== JSON.stringify([expectation.tier])) errors.push("tier_selector");
  const values = [];
  for (const phase of ["qualification", "resilience"]) {
    const source = criterion?.selector?.threshold_sources?.[phase];
    const value = source ? resolveJsonPath(criteriaRoot, source) : undefined;
    const parts = String(source).split(".");
    if (criterion?.selector?.phase_metrics?.[phase] !== expectedMetrics[phase]) errors.push(`phase_metric:${phase}`);
    if (parts.at(-2) !== expectedMetrics[phase] || parts.at(-1) !== expectation.tier) errors.push(`threshold_source_shape:${phase}`);
    if (!Number.isFinite(value)) errors.push(`threshold_source_value:${phase}`);
    values.push(value);
  }
  if (values.some((value) => !Number.isFinite(value)) || Math.max(...values) !== expectation.threshold) errors.push("resolved_aggregate_threshold");
  return errors;
};
const phaseSelectorResolutionFailures = Object.entries(phaseSelectorExpectations).flatMap(([id, expectation]) => {
  const criterion = criteria.criteria.find((item) => item.id === id);
  return phaseSelectorResolutionErrors(criteria, criterion, expectation).map((error) => `${id}:${error}`);
});
const q04Criterion = criteria.criteria.find((item) => item.id === "Q04");
const invalidPathCriterion = structuredClone(q04Criterion || { selector: {} });
invalidPathCriterion.selector = invalidPathCriterion.selector || {};
invalidPathCriterion.selector.threshold_source = "release_matrix.missing_metric";
const missingTierCriteria = structuredClone(criteria);
delete missingTierCriteria.release_matrix.normal_weighted_load_gini.XL;
const badTypeCriteria = structuredClone(criteria);
badTypeCriteria.release_matrix.normal_weighted_load_gini.S = "0.12";
const selectorNegativeCasesRejected = tierSelectorResolutionErrors(criteria, invalidPathCriterion, tierSelectorExpectations.Q04).length > 0
  && tierSelectorResolutionErrors(missingTierCriteria, q04Criterion, tierSelectorExpectations.Q04).length > 0
  && tierSelectorResolutionErrors(badTypeCriteria, q04Criterion, tierSelectorExpectations.Q04).length > 0;
const q12Criterion = criteria.criteria.find((item) => item.id === "Q12");
const invalidPhasePathCriterion = structuredClone(q12Criterion || { selector: {} });
invalidPhasePathCriterion.selector = invalidPhasePathCriterion.selector || {};
invalidPhasePathCriterion.selector.threshold_sources = { ...(invalidPhasePathCriterion.selector.threshold_sources || {}), qualification: "release_matrix.missing_metric.S" };
const missingPhaseTierCriteria = structuredClone(criteria);
delete missingPhaseTierCriteria.release_matrix.solve_p95_seconds.S;
const badPhaseTypeCriteria = structuredClone(criteria);
badPhaseTypeCriteria.release_matrix.recovery_p95_seconds.S = "15";
const phaseSelectorNegativeCasesRejected = phaseSelectorResolutionErrors(criteria, invalidPhasePathCriterion, phaseSelectorExpectations.Q12).length > 0
  && phaseSelectorResolutionErrors(missingPhaseTierCriteria, q12Criterion, phaseSelectorExpectations.Q12).length > 0
  && phaseSelectorResolutionErrors(badPhaseTypeCriteria, q12Criterion, phaseSelectorExpectations.Q12).length > 0;
check("CRITERION_SELECTOR_THRESHOLD_RESOLUTION",
  selectorResolutionFailures.length === 0
  && phaseSelectorResolutionFailures.length === 0
  && selectorNegativeCasesRejected
  && phaseSelectorNegativeCasesRejected,
  `tier_failures=${JSON.stringify(selectorResolutionFailures)} phase_failures=${JSON.stringify(phaseSelectorResolutionFailures)} tier_negative_cases_rejected=${selectorNegativeCasesRejected} phase_negative_cases_rejected=${phaseSelectorNegativeCasesRejected}`);
check("POLICY_APPROVAL_FIELDS", policyMatrix.exam_types?.length === 3
  && policyMatrix.jurisdiction === "SYNTHETIC_DEFAULT_REQUIRES_LOCAL_MAPPING"
  && policyMatrix.locality_status === "REQUIRES_PROVINCE_DISTRICT_SCHOOL_REVIEW"
  && policyMatrix.approval_state === "DRAFT_NOT_APPROVED_FOR_PRODUCTION"
  && policyMatrix.approved_by === null
  && policyMatrix.effective_from === null
  && policyMatrix.effective_until === null, "local approval metadata is explicit and intentionally not production-approved");
const scenarioOracleFailures = scenarios.filter((scenario) => {
  const severityIndex = oracleConfig.severity_order.indexOf(scenario.severity);
  return scenario.expected_feasibility !== oracleConfig.family_expectations?.[scenario.family_id]?.[severityIndex];
});
check("INDEPENDENT_SCENARIO_ORACLE", scenarioOracleFailures.length === 0, `failures=${scenarioOracleFailures.length}`);
check("ORACLE_SOURCE_HASH", manifest.oracle_version === oracleConfig.oracle_version
  && manifest.oracle_config_sha256 === await sha256File(ORACLE_CONFIG_PATH)
  && policyMatrix.oracle_version === oracleConfig.oracle_version, `oracle_version=${manifest.oracle_version}`);
check("PROMPT_ADVERSARIAL_COVERAGE", ["ambiguous", "contradictory", "hard_conflict", "silent_relax", "prompt_injection", "unsupported"].every((variant) => prompts.some((item) => item.variant === variant)), "all adversarial prompt classes exist");
const promptOracleFailures = prompts.filter((item) => {
  const oracle = item.expected_oracle;
  const independentExpectation = oracleConfig.prompt_expectations[item.variant];
  const sharedInvalid = oracle?.hard_constraint_action !== "preserve_all"
    || oracle?.required_hard_gate_ids?.length !== 12
    || JSON.stringify(oracle?.required_explanation_fields) !== JSON.stringify(oracleConfig.required_explanation_fields)
    || oracle?.decision !== item.expected_behavior
    || oracle?.decision !== independentExpectation?.decision
    || oracle?.rejection_reason_code !== independentExpectation?.reason_code;
  if (sharedInvalid) return true;
  if (item.expected_behavior === "parse") {
    return oracle.expected_strategy_id !== item.strategy_id
      || !oracle.expected_soft_weights
      || !["fairness", "care", "stability", "efficiency", "reserve"].every((key) => Number.isFinite(oracle.expected_soft_weights[key]));
  }
  return oracle.expected_strategy_id !== null || typeof oracle.rejection_reason_code !== "string";
});
check("PROMPT_ORACLE_COVERAGE", promptOracleFailures.length === 0, `failures=${promptOracleFailures.length}`);
const unmappedHardConstraintCodes = new Set(scenarios.flatMap((scenario) => Array.isArray(scenario.hard_constraints) ? scenario.hard_constraints : ["MISSING_HARD_CONSTRAINTS"]).filter((code) => !hardConstraintMap.mappings?.[code]));
check("HARD_CONSTRAINT_CODE_MAP", unmappedHardConstraintCodes.size === 0, `unmapped=${JSON.stringify([...unmappedHardConstraintCodes])}`);

let manifestHashFailures = 0;
let workbookMagicFailures = 0;
for (const entry of manifest.files) {
  const absolute = path.join(PACKAGE_ROOT, entry.path);
  try {
    const actualHash = await sha256File(absolute);
    if (actualHash !== entry.sha256) manifestHashFailures += 1;
    if (entry.path.endsWith(".xlsx")) {
      const bytes = await fs.readFile(absolute);
      if (bytes.length < 4 || bytes[0] !== 0x50 || bytes[1] !== 0x4B) workbookMagicFailures += 1;
    }
  } catch {
    manifestHashFailures += 1;
  }
}
check("MANIFEST_HASHES", manifestHashFailures === 0, `hash_failures=${manifestHashFailures}`);
check("WORKBOOK_MAGIC", workbookMagicFailures === 0, `invalid_xlsx_magic=${workbookMagicFailures}`);
check("WORKBOOK_COUNT", manifest.counts.workbooks === 13, `expected=13 actual=${manifest.counts.workbooks}`);
check("PREVIEW_COUNT", manifest.counts.previews === 19, `expected=19 actual=${manifest.counts.previews}`);

for (const tier of config.tiers) {
  const tierTeachers = teachers.filter((item) => item.teacher_no.startsWith(`RS-${tier.id}-`));
  const tierRooms = rooms.filter((item) => item.room_code.startsWith(`RS-${tier.id}-`));
  const tierExams = exams.filter((item) => item.exam_session_id.startsWith(`RS-${tier.id}-`));
  check(`TIER_${tier.id}_COUNTS`,
    tierTeachers.length === tier.teachers && tierRooms.length === tier.rooms && tierExams.length === tier.exam_rows,
    `teachers=${tierTeachers.length}/${tier.teachers} rooms=${tierRooms.length}/${tier.rooms} exams=${tierExams.length}/${tier.exam_rows}`);
}

const report = {
  contract: "RealSchoolAcceptanceVerification/v1",
  status: failures.length === 0 ? "ARTIFACT_PASS" : "ARTIFACT_FAIL",
  validation_scope: "static_artifact_only",
  acceptance_execution: {
    status: "NOT_RUN",
    planned_runs: runs.length,
    pending_runs: runs.filter((run) => run.terminal_state === "pending").length,
    completed_runs: runs.filter((run) => run.terminal_state !== "pending").length,
  },
  checked_manifest_sha256: await sha256File(path.join(GENERATED_DIR, "manifest.json")),
  check_count: checks.length,
  pass_count: checks.filter((item) => item.status === "PASS").length,
  fail_count: failures.length,
  checks,
  failures,
};
await fs.writeFile(REPORT_PATH, JSON.stringify(report, null, 2) + "\n", "utf8");
process.stdout.write(JSON.stringify({
  status: report.status,
  checks: report.check_count,
  passed: report.pass_count,
  failed: report.fail_count,
  report: REPORT_PATH,
  failures,
}, null, 2) + "\n");
if (failures.length > 0) process.exitCode = 1;
}

try {
  await main();
} catch (error) {
  const unexpected = { id: "UNEXPECTED_VALIDATOR_ERROR", detail: error instanceof Error ? error.message : String(error) };
  failures.push(unexpected);
  const failureReport = {
    contract: "RealSchoolAcceptanceVerification/v1",
    status: "ARTIFACT_FAIL",
    validation_scope: "static_artifact_only",
    acceptance_execution: {
      status: "NOT_RUN",
      planned_runs: config?.run_matrix?.expected_total_runs ?? null,
      pending_runs: null,
      completed_runs: 0,
    },
    checked_manifest_sha256: null,
    check_count: checks.length + 1,
    pass_count: checks.filter((item) => item.status === "PASS").length,
    fail_count: failures.length,
    checks: [...checks, { ...unexpected, status: "FAIL" }],
    failures,
  };
  await fs.mkdir(GENERATED_DIR, { recursive: true });
  await fs.writeFile(REPORT_PATH, JSON.stringify(failureReport, null, 2) + "\n", "utf8");
  process.stdout.write(JSON.stringify({ status: "ARTIFACT_FAIL", failures }, null, 2) + "\n");
  process.exitCode = 1;
}
