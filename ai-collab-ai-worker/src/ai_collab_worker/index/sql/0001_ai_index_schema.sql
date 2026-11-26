-- P2 契约 §5.1：ai_index 表结构（属主 ai_worker）。
-- 已发布：只能新增迁移文件，不得修改本文件（P2-R1 第 1 条；校验和由 schema_migrations 固定）。
-- vector / pg_trgm 扩展由超级用户预装在 public（db-init.sql），这里只引用、不创建。

CREATE TABLE ai_index.index_generation (
  id              uuid PRIMARY KEY,
  embedding_model varchar(120) NOT NULL,
  dimension       integer NOT NULL CHECK (dimension = 1024),
  status          varchar(16) NOT NULL CHECK (status IN ('BUILDING','ACTIVE','RETIRED','FAILED')),
  created_at      timestamptz NOT NULL DEFAULT now(),
  activated_at    timestamptz
);

-- 全站只有一个 ACTIVE、最多一个 BUILDING（Q5）。
CREATE UNIQUE INDEX uk_index_generation_active
  ON ai_index.index_generation ((true)) WHERE status = 'ACTIVE';
CREATE UNIQUE INDEX uk_index_generation_building
  ON ai_index.index_generation ((true)) WHERE status = 'BUILDING';

-- 每个 (generation, document) 一行；0 个切块的文档也有记录，:reconcile 以本表为准。
CREATE TABLE ai_index.indexed_document (
  generation_id    uuid NOT NULL REFERENCES ai_index.index_generation(id) ON DELETE CASCADE,
  project_id       uuid NOT NULL,
  document_id      uuid NOT NULL,
  body_snapshot_id uuid NOT NULL,
  row_count        integer NOT NULL CHECK (row_count >= 0),
  indexed_at       timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (generation_id, document_id)
);
CREATE INDEX idx_indexed_document_project
  ON ai_index.indexed_document (project_id, document_id);

CREATE TABLE ai_index.chunk (
  id               uuid PRIMARY KEY,
  generation_id    uuid NOT NULL REFERENCES ai_index.index_generation(id) ON DELETE CASCADE,
  project_id       uuid NOT NULL,
  document_id      uuid NOT NULL,
  body_snapshot_id uuid NOT NULL,
  chunk_no         integer NOT NULL,
  chunk_type       varchar(16) NOT NULL
                   CHECK (chunk_type IN ('STANDARD','PARENT','CHILD','SENTENCE')),
  parent_chunk_no  integer,
  heading          varchar(300),
  content          text NOT NULL,
  content_hash     char(64) NOT NULL,
  metadata         jsonb NOT NULL DEFAULT '{}',
  embedding        public.vector(1024),
  created_at       timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uk_chunk_generation_document_no UNIQUE (generation_id, document_id, chunk_no),
  -- PARENT 行不向量化，其余行必须有向量。
  CONSTRAINT ck_chunk_parent_embedding CHECK ((chunk_type = 'PARENT') = (embedding IS NULL))
);

-- HNSW 先用 pgvector 默认参数（Q10）；iterative scan 在每个检索事务里 SET LOCAL 开启。
CREATE INDEX idx_chunk_hnsw
  ON ai_index.chunk USING hnsw (embedding public.vector_cosine_ops) WITH (m = 16, ef_construction = 64);
CREATE INDEX idx_chunk_content_trgm
  ON ai_index.chunk USING gin (content public.gin_trgm_ops);
CREATE INDEX idx_chunk_heading_trgm
  ON ai_index.chunk USING gin (heading public.gin_trgm_ops);
CREATE INDEX idx_chunk_scope
  ON ai_index.chunk (generation_id, project_id, document_id);
CREATE INDEX idx_chunk_parent
  ON ai_index.chunk (generation_id, document_id, parent_chunk_no)
  WHERE parent_chunk_no IS NOT NULL;
