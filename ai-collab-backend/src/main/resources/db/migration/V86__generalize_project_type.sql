-- AC-06 业务中性化:项目类型扩展为通用业务分类
-- 前向迁移:仅放宽 CHECK 约束,新增通用类型;不修改、不重解释任何历史数据。

ALTER TABLE project DROP CONSTRAINT chk_project_type;

ALTER TABLE project ADD CONSTRAINT chk_project_type
    CHECK (type IN (
        'PRODUCT_DEVELOPMENT', 'CLIENT_DELIVERY', 'INTERNAL_PROJECT',
        'RESEARCH_EXPLORATION', 'OTHER',
        'COMPETITION', 'COURSE_DESIGN', 'SOFTWARE_TRAINING'
    ));
