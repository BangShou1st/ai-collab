-- V73: 项目级飞书连接（P7.1 项目管理员自助接入）。
-- SYSTEM 连接为现状（系统管理员创建、全租户项目可用）；
-- PROJECT 连接由项目 Owner/Admin 创建，仅归属项目可见可用。
-- 凭据仍经 MODEL_CONFIG_MASTER_KEY 加密只写不读。
ALTER TABLE connector_connection
    ADD COLUMN owner_project_id UUID REFERENCES project(id) ON DELETE CASCADE,
    ADD COLUMN visibility VARCHAR(20) NOT NULL DEFAULT 'SYSTEM';

ALTER TABLE connector_connection ADD CONSTRAINT ck_connector_connection_scope
    CHECK ((visibility = 'SYSTEM' AND owner_project_id IS NULL)
        OR (visibility = 'PROJECT' AND owner_project_id IS NOT NULL));

CREATE INDEX idx_connector_connection_owner_project
    ON connector_connection(owner_project_id) WHERE owner_project_id IS NOT NULL;
