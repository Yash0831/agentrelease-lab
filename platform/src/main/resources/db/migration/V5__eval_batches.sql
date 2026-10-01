-- V5: evaluation batches. Every eval run belongs to a batch so repeated
-- evaluations of the same (version, dataset, scenario, trial, mode) land in
-- distinct batches instead of colliding on the old global uniqueness.
-- Release decisions are scoped to the batch they were computed from.
ALTER TABLE eval_runs ADD COLUMN batch_id TEXT;
UPDATE eval_runs SET batch_id = 'batch-legacy-' || mode WHERE batch_id IS NULL;
ALTER TABLE eval_runs ALTER COLUMN batch_id SET NOT NULL;

-- Drop the V1 global uniqueness (auto-named; resolve it dynamically) and
-- replace it with batch-scoped uniqueness.
DO $$
DECLARE cname TEXT;
BEGIN
  SELECT conname INTO cname FROM pg_constraint
  WHERE conrelid = 'eval_runs'::regclass AND contype = 'u'
    AND conname LIKE 'eval_runs_agent_version%';
  IF cname IS NOT NULL THEN
    EXECUTE 'ALTER TABLE eval_runs DROP CONSTRAINT ' || quote_ident(cname);
  END IF;
END $$;

ALTER TABLE eval_runs ADD CONSTRAINT uq_eval_run_batch
  UNIQUE (tenant_id, batch_id, agent_version_id, dataset_id, scenario_id, trial_index, mode);
CREATE INDEX IF NOT EXISTS idx_eval_runs_batch ON eval_runs (tenant_id, batch_id);

ALTER TABLE release_decisions ADD COLUMN batch_id TEXT;
