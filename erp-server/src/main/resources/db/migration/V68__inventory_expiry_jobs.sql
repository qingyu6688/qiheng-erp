ALTER TABLE inventory_reservation_change DROP CHECK ck_inventory_reservation_change_type;
ALTER TABLE inventory_reservation_change ADD CONSTRAINT ck_inventory_reservation_change_type CHECK (change_type IN (
    'reserve','consume','release_close','release_cancel','release_change','release_revoke','expire','release_batch'));

INSERT INTO job_definition(tenant_id,code,name,schedule)
VALUES(0,'inventory.batch-expiry','批次过期冻结及临期提醒','interval:60000');

ALTER TABLE shipment_line ADD COLUMN signed_serials JSON NULL;
