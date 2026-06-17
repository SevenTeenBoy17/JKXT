ALTER TABLE ai_call_log
  ADD COLUMN IF NOT EXISTS masked_input jsonb NOT NULL DEFAULT '{}'::jsonb,
  ADD COLUMN IF NOT EXISTS masked_output jsonb NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE ai_call_log
  DROP CONSTRAINT IF EXISTS ai_call_log_purpose_check;

ALTER TABLE ai_call_log
  ADD CONSTRAINT ai_call_log_purpose_check
  CHECK (purpose IN ('nl2dsl', 'refine', 'summary', 'repair', 'explain', 'other'));

ALTER TABLE ai_call_log
  DROP CONSTRAINT IF EXISTS ai_call_log_status_check;

ALTER TABLE ai_call_log
  ADD CONSTRAINT ai_call_log_status_check
  CHECK (status IN ('pending', 'succeeded', 'failed', 'fallback', 'schema_invalid', 'timeout'));
