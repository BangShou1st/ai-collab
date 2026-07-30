-- 文档版本号：上传新文件时 version=1，重新索引时 version+1
ALTER TABLE project_document ADD COLUMN version integer NOT NULL DEFAULT 1;

-- 重新索引端点需要将 READY 文档重置为 UPLOADED 并递增版本
-- 通过 resetForReindex 方法实现，不需要额外的数据库对象
