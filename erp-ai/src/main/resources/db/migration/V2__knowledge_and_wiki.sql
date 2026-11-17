CREATE TABLE kb_library (
    id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    name VARCHAR(160) NOT NULL,
    description VARCHAR(1000) NOT NULL DEFAULT '',
    visibility VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    access_version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (tenant_id, id),
    UNIQUE KEY uk_kb_library_name (tenant_id, name)
);

CREATE TABLE kb_library_acl (
    tenant_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    library_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_type VARCHAR(16) NOT NULL,
    target_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (tenant_id, library_id, target_type, target_id),
    CONSTRAINT fk_kb_acl_library FOREIGN KEY (tenant_id, library_id) REFERENCES kb_library (tenant_id, id)
);

CREATE TABLE kb_document (
    id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    library_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_no INT NOT NULL,
    live_version INT,
    disabled BOOLEAN NOT NULL DEFAULT FALSE,
    seed_key VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (tenant_id, id),
    UNIQUE KEY uk_kb_document_seed (tenant_id, seed_key),
    KEY ix_kb_document_library (tenant_id, library_id, updated_at),
    CONSTRAINT fk_kb_document_library FOREIGN KEY (tenant_id, library_id) REFERENCES kb_library (tenant_id, id)
);

CREATE TABLE kb_document_version (
    tenant_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    document_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_no INT NOT NULL,
    title VARCHAR(200) NOT NULL,
    file_name VARCHAR(200) NOT NULL,
    file_object_key VARCHAR(150) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    mime VARCHAR(100) NOT NULL,
    size BIGINT NOT NULL,
    hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) NOT NULL,
    error VARCHAR(255) NOT NULL DEFAULT '',
    uploaded_by VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    claim_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (tenant_id, document_id, version_no),
    KEY ix_kb_version_work (status, updated_at),
    CONSTRAINT fk_kb_version_document FOREIGN KEY (tenant_id, document_id) REFERENCES kb_document (tenant_id, id)
);

CREATE TABLE kb_segment (
    id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    document_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_no INT NOT NULL,
    seq INT NOT NULL,
    heading_path VARCHAR(500) NOT NULL,
    page_from INT,
    page_to INT,
    char_count INT NOT NULL,
    content TEXT NOT NULL,
    status VARCHAR(16) NOT NULL,
    PRIMARY KEY (tenant_id, id),
    UNIQUE KEY uk_kb_segment_sequence (tenant_id, document_id, version_no, seq),
    CONSTRAINT fk_kb_segment_version FOREIGN KEY (tenant_id, document_id, version_no)
        REFERENCES kb_document_version (tenant_id, document_id, version_no)
);

CREATE TABLE wiki_page (
    id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    slug VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    title VARCHAR(200) NOT NULL,
    summary VARCHAR(500) NOT NULL,
    status VARCHAR(24) NOT NULL,
    current_revision_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin,
    pending_revision_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin,
    view_count BIGINT NOT NULL DEFAULT 0,
    last_viewed_at TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (tenant_id, id),
    UNIQUE KEY uk_wiki_slug (tenant_id, slug),
    KEY ix_wiki_review (tenant_id, pending_revision_id, updated_at)
);

CREATE TABLE wiki_revision (
    id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    page_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_md MEDIUMTEXT NOT NULL,
    author_type VARCHAR(16) NOT NULL,
    author_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    change_note VARCHAR(500) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (tenant_id, id),
    KEY ix_wiki_revision_page (tenant_id, page_id, created_at),
    CONSTRAINT fk_wiki_revision_page FOREIGN KEY (tenant_id, page_id) REFERENCES wiki_page (tenant_id, id)
);

CREATE TABLE wiki_revision_source (
    tenant_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    seq INT NOT NULL,
    segment_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    access_version BIGINT NOT NULL,
    PRIMARY KEY (tenant_id, revision_id, seq),
    UNIQUE KEY uk_wiki_source (tenant_id, revision_id, segment_id),
    KEY ix_wiki_source_segment (tenant_id, segment_id),
    CONSTRAINT fk_wiki_source_revision FOREIGN KEY (tenant_id, revision_id) REFERENCES wiki_revision (tenant_id, id),
    CONSTRAINT fk_wiki_source_segment FOREIGN KEY (tenant_id, segment_id) REFERENCES kb_segment (tenant_id, id)
);

CREATE TABLE wiki_link (
    tenant_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    from_page_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    to_slug VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (tenant_id, from_page_id, to_slug),
    KEY ix_wiki_link_target (tenant_id, to_slug),
    CONSTRAINT fk_wiki_link_page FOREIGN KEY (tenant_id, from_page_id) REFERENCES wiki_page (tenant_id, id)
);

CREATE TABLE wiki_task (
    id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    type VARCHAR(16) NOT NULL,
    trigger_code VARCHAR(32) NOT NULL,
    document_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin,
    version_no INT,
    status VARCHAR(16) NOT NULL,
    result_page_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (tenant_id, id),
    UNIQUE KEY uk_wiki_document_task (tenant_id, document_id, version_no),
    KEY ix_wiki_task_work (status, created_at)
);

CREATE TABLE wiki_lint_issue (
    id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    page_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    type VARCHAR(32) NOT NULL,
    detail VARCHAR(500) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'open',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (tenant_id, id),
    UNIQUE KEY uk_wiki_lint_type (tenant_id, page_id, type),
    CONSTRAINT fk_wiki_lint_page FOREIGN KEY (tenant_id, page_id) REFERENCES wiki_page (tenant_id, id)
);

CREATE TABLE kb_index_cleanup (
    tenant_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    index_type VARCHAR(16) NOT NULL,
    object_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (tenant_id, index_type, object_id),
    KEY ix_kb_cleanup_work (created_at)
);
