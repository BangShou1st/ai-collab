-- P7-BUSINESS-E2E-01 只读一致性核验（项目 45954343-6a05-49b4-a0fb-9262483b2ab4）
-- 1. 文档与索引
SELECT d.display_name, d.status, d.chunk_count,
       (SELECT count(*) FROM document_chunk c WHERE c.document_id=d.id) AS actual_chunks,
       d.embedding_model, d.embedding_dimension
FROM project_document d WHERE d.project_id='45954343-6a05-49b4-a0fb-9262483b2ab4' ORDER BY 1;
-- 2. 知识问答消息与引用
SELECT (SELECT count(*) FROM knowledge_message m JOIN knowledge_session s ON s.id=m.session_id WHERE s.project_id='45954343-6a05-49b4-a0fb-9262483b2ab4') AS qa_messages,
       (SELECT count(*) FROM knowledge_citation c JOIN knowledge_message m ON m.id=c.message_id JOIN knowledge_session s ON s.id=m.session_id WHERE s.project_id='45954343-6a05-49b4-a0fb-9262483b2ab4') AS qa_citations;
-- 3. Agent 运行与事件序列
SELECT r.status, count(*) AS runs,
       (SELECT count(*) FROM agent_run_event e WHERE e.run_id=r.id) AS events
FROM agent_run r WHERE r.project_id='45954343-6a05-49b4-a0fb-9262483b2ab4' GROUP BY r.id, r.status ORDER BY 2 DESC LIMIT 10;
-- 事件序号无缺口校验
SELECT count(*) AS gap_count FROM (
  SELECT run_id, sequence_no, row_number() OVER (PARTITION BY run_id ORDER BY sequence_no) AS rn
  FROM agent_run_event e WHERE run_id IN (SELECT id FROM agent_run WHERE project_id='45954343-6a05-49b4-a0fb-9262483b2ab4')
) t WHERE sequence_no <> rn;
-- 4. 工具调用结果分布（成功/失败/拒绝/取消/未知）
SELECT status, count(*) FROM agent_tool_invocation WHERE run_id IN
  (SELECT id FROM agent_run WHERE project_id='45954343-6a05-49b4-a0fb-9262483b2ab4') GROUP BY status;
-- 5. UNKNOWN 数量与年龄
SELECT count(*) AS unknown_invocations,
       max(now()-coalesce(dispatch_started_at,updated_at)) AS max_age
FROM agent_tool_invocation WHERE status='UNKNOWN';
-- 6. 审批与业务对象
SELECT status, count(*) FROM agent_approval WHERE project_id='45954343-6a05-49b4-a0fb-9262483b2ab4' GROUP BY status;
SELECT count(*) AS tasks, (SELECT count(*) FROM milestone WHERE project_id='45954343-6a05-49b4-a0fb-9262483b2ab4') AS milestones FROM project_task WHERE project_id='45954343-6a05-49b4-a0fb-9262483b2ab4';
-- 7. 规划确认一致性
SELECT status, latest_version_no, confirmed_by IS NOT NULL AS confirmed, confirmed_at IS NOT NULL AS confirmed_ts
FROM ai_task_plan WHERE project_id='45954343-6a05-49b4-a0fb-9262483b2ab4';
-- 8. 成员与权限
SELECT u.username, m.role FROM project_member m JOIN app_user u ON u.id=m.user_id WHERE m.project_id='45954343-6a05-49b4-a0fb-9262483b2ab4';
-- 9. 过期 Lease 诊断
SELECT count(*) AS expired_leases FROM agent_run WHERE project_id='45954343-6a05-49b4-a0fb-9262483b2ab4' AND status='RUNNING' AND lease_expires_at < now();
