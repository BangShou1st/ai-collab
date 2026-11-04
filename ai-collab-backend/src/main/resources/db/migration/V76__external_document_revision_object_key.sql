-- D1.2: 外部文档版本关联不可变对象存储 Key
ALTER TABLE external_document_revision ADD COLUMN IF NOT EXISTS object_key TEXT;
