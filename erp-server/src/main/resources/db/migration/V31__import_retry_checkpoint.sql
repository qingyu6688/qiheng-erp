ALTER TABLE import_job
  ADD COLUMN attempts INT UNSIGNED NOT NULL DEFAULT 0,
  ADD COLUMN next_attempt_at DATETIME(3) NULL,
  ADD KEY idx_import_retry(status,next_attempt_at,lease_until);
