CREATE TABLE auth_device_token (
    id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
    tenant_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    session_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    device_name VARCHAR(80) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    last_used_at DATETIME(3) NOT NULL,
    revoked_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_auth_device_token_hash (token_hash),
    UNIQUE KEY uk_auth_device_session_hash (session_hash),
    KEY idx_auth_device_user (tenant_id,user_id,expires_at),
    CONSTRAINT fk_auth_device_user FOREIGN KEY (tenant_id,user_id) REFERENCES sys_user(tenant_id,id)
);

INSERT INTO sys_permission(id,code,name,perm_type,sort_no) VALUES
    (16980,'device:pair','配对仓储作业设备','action',16980),
    (16981,'label:print','打印仓储标签','action',16981);

INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id)
SELECT base.max_id+ROW_NUMBER() OVER(ORDER BY r.tenant_id,r.id,p.id),r.tenant_id,r.id,p.id
FROM sys_role r JOIN sys_permission p ON p.id IN (16980,16981)
CROSS JOIN (SELECT COALESCE(MAX(id),0) max_id FROM sys_role_permission) base
WHERE r.status='active' AND r.code='SYSTEM_ADMIN'
  AND EXISTS (SELECT 1 FROM sys_role_permission rp JOIN sys_permission old ON old.id=rp.permission_id
              WHERE rp.tenant_id=r.tenant_id AND rp.role_id=r.id AND old.code='role:assign_permission');
