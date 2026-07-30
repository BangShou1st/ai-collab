-- 添加项目类型字段
ALTER TABLE project ADD COLUMN type VARCHAR(20) NOT NULL DEFAULT 'OTHER';

-- 历史数据回填：所有现有项目默认为 'OTHER'
UPDATE project SET type = 'OTHER' WHERE type = 'OTHER';

-- 添加类型约束
ALTER TABLE project ADD CONSTRAINT chk_project_type
    CHECK (type IN ('COMPETITION', 'COURSE_DESIGN', 'SOFTWARE_TRAINING', 'OTHER'));
