-- V065: Add lock_status to idempotency_keys for concurrency-safe idempotency.
--
-- lock_status = 'locked'   : claimed by an in-flight request (business logic running)
-- lock_status = 'complete' : response has been stored and is ready to replay
--
-- The application INSERT a row with status='locked' before executing business logic,
-- then UPDATE to 'complete' with the captured response.  If a concurrent request
-- arrives for the same key it sees 'locked' and returns a 503 (retry later).
-- A 'locked' row that is past expires_at is treated as a stale lock (request died).

ALTER TABLE idempotency_keys
  ADD COLUMN lock_status TEXT NOT NULL DEFAULT 'complete'
  CHECK (lock_status IN ('locked', 'complete'));

-- Index for finding stale locked records during cleanup
CREATE INDEX idx_idempotency_lock_status
  ON idempotency_keys(lock_status, expires_at)
  WHERE lock_status = 'locked';
