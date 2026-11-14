-- V97: 删除项目时回收该项目下的 AI Job。
--
-- 权限与审计仍由 ProjectApplicationService 强制执行；本迁移只修复 V91 外键缺少
-- ON DELETE CASCADE 导致“存在 Job 的项目删除返回 500”的问题。

ALTER TABLE ai_job DROP CONSTRAINT ai_job_project_id_fkey;
ALTER TABLE ai_job
    ADD CONSTRAINT ai_job_project_id_fkey
    FOREIGN KEY (project_id) REFERENCES project(id) ON DELETE CASCADE;
