ALTER TABLE ai_route DROP CHECK ai_route_chk_1,
  ADD CONSTRAINT ck_ai_route_scene CHECK (scene IN ('kb_chat','wiki_maintain','customer_service'));

CREATE TABLE ai_portal_intent_nonce (
  nonce CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  expires_at DATETIME(3) NOT NULL,
  KEY idx_ai_portal_intent_expiry (expires_at)
);
