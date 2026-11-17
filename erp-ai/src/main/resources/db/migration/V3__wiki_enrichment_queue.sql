ALTER TABLE wiki_task
    ADD COLUMN enrich_revision_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin,
    ADD COLUMN enrich_claim_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin,
    ADD COLUMN enrich_status VARCHAR(16) NOT NULL DEFAULT 'none',
    ADD COLUMN enrich_after TIMESTAMP(6),
    ADD KEY ix_wiki_enrich (tenant_id, enrich_status, enrich_after);

ALTER TABLE wiki_page ADD KEY ix_wiki_current (tenant_id, current_revision_id);
