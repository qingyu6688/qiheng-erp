CREATE TABLE report_snapshot_watermark (
    id TINYINT UNSIGNED NOT NULL PRIMARY KEY,
    observed_at DATETIME(3) NOT NULL COMMENT '主库最近确认的复制数据时间，UTC',
    CONSTRAINT ck_report_snapshot_watermark_id CHECK (id=1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='报表备库数据水位';

INSERT INTO report_snapshot_watermark(id,observed_at) VALUES(1,UTC_TIMESTAMP(3));
