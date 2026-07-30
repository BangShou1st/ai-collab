-- 扩展项目状态为四态：PREPARING, ACTIVE, COMPLETED, ARCHIVED
-- 先删除旧约束
ALTER TABLE project DROP CONSTRAINT IF EXISTS ck_project_status;

-- 添加新约束
ALTER TABLE project ADD CONSTRAINT ck_project_status
    CHECK (status IN ('PREPARING', 'ACTIVE', 'COMPLETED', 'ARCHIVED'));

-- 历史数据回填：将现有 ACTIVE 项目保持为 ACTIVE
-- PREPARING 和 COMPLETED 状态需要用户手动设置
