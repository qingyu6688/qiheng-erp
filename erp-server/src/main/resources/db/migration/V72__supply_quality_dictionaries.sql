INSERT INTO sys_dict_type(id,tenant_id,code,name) VALUES
    (14140,0,'quality_defect','检验缺陷'),
    (14141,0,'stock_misc_purpose','其他出入库用途');

INSERT INTO sys_dict_item(id,tenant_id,type_code,value,label,color,sort_no) VALUES
    (14140,0,'quality_defect','appearance','外观缺陷','warning',10),
    (14141,0,'quality_defect','dimension','尺寸不合格','warning',20),
    (14142,0,'quality_defect','function','功能不合格','danger',30),
    (14143,0,'quality_defect','other','其他缺陷','neutral',40),
    (14144,0,'stock_misc_purpose','gift','赠品入库','neutral',10),
    (14145,0,'stock_misc_purpose','sample','样品领用','neutral',20),
    (14146,0,'stock_misc_purpose','loan','借出','neutral',30),
    (14147,0,'stock_misc_purpose','loan_return','借出归还','neutral',40),
    (14148,0,'stock_misc_purpose','internal','内部领用','neutral',50),
    (14149,0,'stock_misc_purpose','other','其他','neutral',60);

UPDATE sys_dict_revision SET version=version+1 WHERE tenant_id=0;

CREATE TABLE qc_result_round (
    tenant_id BIGINT UNSIGNED NOT NULL,
    company_id BIGINT UNSIGNED NOT NULL,
    inspection_id BIGINT UNSIGNED NOT NULL,
    round_no INT UNSIGNED NOT NULL,
    qualified_qty DECIMAL(24,6) NOT NULL,
    unqualified_qty DECIMAL(24,6) NOT NULL,
    concession_qty DECIMAL(24,6) NOT NULL,
    concession_reason VARCHAR(500) NULL,
    inspector_id BIGINT UNSIGNED NOT NULL,
    approver_id BIGINT UNSIGNED NOT NULL,
    approved_at DATETIME(3) NOT NULL,
    PRIMARY KEY(tenant_id,company_id,inspection_id,round_no),
    CONSTRAINT fk_qcrr_inspection FOREIGN KEY(tenant_id,company_id,inspection_id) REFERENCES qc_inspection(tenant_id,company_id,id),
    CONSTRAINT ck_qcrr_review CHECK(inspector_id<>approver_id)
);

CREATE VIEW qc_supplier_score_monthly AS
SELECT q.tenant_id,q.company_id,q.supplier_id,DATE_FORMAT(q.business_date,'%Y-%m') month_key,
    COUNT(*) inspection_count,SUM(q.received_qty) received_qty,SUM(r.qualified_qty) first_pass_qty
FROM qc_inspection q JOIN qc_result_round r ON r.tenant_id=q.tenant_id AND r.company_id=q.company_id AND r.inspection_id=q.id AND r.round_no=1
GROUP BY q.tenant_id,q.company_id,q.supplier_id,DATE_FORMAT(q.business_date,'%Y-%m');
