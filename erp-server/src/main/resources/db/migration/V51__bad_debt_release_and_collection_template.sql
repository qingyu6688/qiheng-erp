-- 允许按财务认定转回多提准备，保留原计提和转回的各自凭证事实。
ALTER TABLE ar_bad_debt DROP CHECK ck_ar_bad_debt,
ADD CONSTRAINT ck_ar_bad_debt_v51 CHECK(amount>0 AND kind IN('provision','release','write_off') AND method IN('aging','individual','actual') AND status IN('draft','pending','posted','rejected') AND (approved_by IS NULL OR approved_by<>created_by) AND (kind<>'write_off' OR open_item_id IS NOT NULL));
INSERT INTO notify_template(id,tenant_id,code,channel,title,content,params) VALUES(10,0,'collection.remind','site','{title}','{message}',JSON_ARRAY('title','message'));
