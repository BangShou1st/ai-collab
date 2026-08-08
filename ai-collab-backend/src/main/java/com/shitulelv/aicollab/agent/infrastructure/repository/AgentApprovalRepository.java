package com.shitulelv.aicollab.agent.infrastructure.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.application.view.AgentApprovalView;
import com.shitulelv.aicollab.agent.application.view.AgentProposalRevisionView;
import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.domain.model.AgentDecision;
import com.shitulelv.aicollab.agent.domain.model.AgentProposalFamily;
import com.shitulelv.aicollab.agent.domain.model.AgentRunStatus;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AgentApprovalRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public AgentApprovalRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional
    public AgentApprovalView createProposal(
            UUID approvalId, AgentRunView run, ChatCompletionResult completion,
            AgentDecision.CallTool call, JsonNode arguments, JsonNode diff,
            String argumentsHash, String nonceHash, OffsetDateTime expiresAt) {
        boolean currentRun = !jdbc.query("""
                SELECT id FROM agent_run
                WHERE project_id=? AND id=? AND version=? AND status='RUNNING'
                FOR UPDATE
                """, (rs, row) -> rs.getObject("id", UUID.class),
                run.projectId(), run.id(), run.version()).isEmpty();
        if (!currentRun) throw new IllegalStateException("Agent 运行已被并发修改");
        UUID stepId = UUID.randomUUID();
        Integer sequence = jdbc.queryForObject(
                "SELECT COALESCE(max(sequence_no),0)+1 FROM agent_step WHERE run_id=?",
                Integer.class, run.id());
        jdbc.update("""
                INSERT INTO agent_step(
                  id,run_id,sequence_no,type,tool_name,input_json,output_json,reason,
                  prompt_tokens,completion_tokens,token_usage_estimated,latency_ms)
                VALUES (?,?,?,'APPROVAL_REQUESTED',?,?::jsonb,?::jsonb,?,?,?,?,?)
                """, stepId, run.id(), sequence == null ? 1 : sequence, call.tool(),
                arguments.toString(), diff.toString(), call.reason(),
                completion.promptTokens(), completion.completionTokens(),
                completion.promptTokens() == null || completion.completionTokens() == null,
                (int) Math.min(Integer.MAX_VALUE, Math.max(0, completion.latencyMs())));
        // V37: 包含 session_id, proposal_family, subject_key
        String proposalFamily = extractProposalFamily(call.tool());
        jdbc.update("""
                INSERT INTO agent_approval(
                  id,project_id,run_id,step_id,tool_name,arguments_json,arguments_hash,
                  diff_json,resource_id,resource_version,requester_id,nonce_hash,expires_at,
                  session_id,proposal_family,subject_key)
                VALUES (?,?,?,?,?,?::jsonb,?,?::jsonb,?,?,?,?,?,?,?,?)
                """, approvalId, run.projectId(), run.id(), stepId, call.tool(),
                arguments.toString(), argumentsHash, diff.toString(), resourceId(arguments),
                resourceVersion(arguments), run.requesterId(), nonceHash, expiresAt,
                run.sessionId(), proposalFamily, UUID.randomUUID());
        // 提案与 Run 生命周期解耦：审批保持 PENDING，但本次 Run 继续生成并返回确定性结果。
        // Run 的工具预算与版本由 Runtime 的 recordToolResult 统一更新，避免重复计数。
        return find(run.projectId(), approvalId).orElseThrow();
    }

    private String extractProposalFamily(String toolName) {
        return switch (toolName) {
            case "create_task_after_approval" -> "TASK_CREATE";
            case "update_task_after_approval" -> "TASK_UPDATE";
            case "create_milestone_after_approval" -> "MILESTONE_CREATE";
            case "update_milestone_after_approval" -> "MILESTONE_UPDATE";
            case "create_memory_after_approval" -> "MEMORY_CREATE";
            default -> "UNKNOWN";
        };
    }

    public List<AgentApprovalView> list(UUID projectId, String status) {
        if (status == null || status.isBlank()) {
            return jdbc.query("""
                    SELECT * FROM agent_approval WHERE project_id=?
                    ORDER BY created_at DESC,id DESC LIMIT 200
                    """, mapper(), projectId);
        }
        return jdbc.query("""
                SELECT * FROM agent_approval WHERE project_id=? AND status=?
                ORDER BY created_at DESC,id DESC LIMIT 200
                """, mapper(), projectId, status);
    }

    public Optional<AgentApprovalView> find(UUID projectId, UUID approvalId) {
        return jdbc.query("SELECT * FROM agent_approval WHERE project_id=? AND id=?",
                mapper(), projectId, approvalId).stream().findFirst();
    }

    public Optional<AgentApprovalView> lock(UUID projectId, UUID approvalId) {
        return jdbc.query("SELECT * FROM agent_approval WHERE project_id=? AND id=? FOR UPDATE",
                mapper(), projectId, approvalId).stream().findFirst();
    }

    public Optional<AgentRunStatus> lockRunStatus(UUID projectId, UUID runId) {
        return jdbc.query("""
                SELECT status FROM agent_run
                WHERE project_id=? AND id=?
                FOR UPDATE
                """, (rs, row) -> AgentRunStatus.valueOf(rs.getString("status")),
                projectId, runId).stream().findFirst();
    }

    public boolean matchesNonceHash(UUID projectId, UUID approvalId, String nonceHash) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM agent_approval
                WHERE project_id=? AND id=? AND nonce_hash=?
                """, Integer.class, projectId, approvalId, nonceHash);
        return count != null && count == 1;
    }

    public boolean matchesIdempotencyKey(UUID projectId, UUID approvalId, UUID key) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM agent_approval
                WHERE project_id=? AND id=? AND idempotency_key=?
                """, Integer.class, projectId, approvalId, key);
        return count != null && count == 1;
    }

    /**
     * 查找同一会话、同一工具族、同一请求人的唯一兼容 PENDING 提案。
     * 用于自动匹配：未携带 approvalId 时，只有唯一兼容候选才自动修订。
     */
    public Optional<AgentApprovalView> findCompatiblePending(
            UUID projectId, UUID sessionId, UUID requesterId, AgentProposalFamily family) {
        return jdbc.query("""
                SELECT * FROM agent_approval
                WHERE project_id=? AND session_id=? AND requester_id=?
                  AND proposal_family=? AND status='PENDING'
                LIMIT 1
                """, mapper(), projectId, sessionId, requesterId, family.name()).stream().findFirst();
    }

    /**
     * 查找会话内所有待审批和最近已解决的提案。
     * 用于提供可信上下文给 Runtime。
     */
    public List<AgentApprovalView> listSessionProposals(UUID projectId, UUID sessionId) {
        return jdbc.query("""
                SELECT * FROM agent_approval
                WHERE project_id=? AND session_id=?
                ORDER BY created_at DESC, id DESC LIMIT 50
                """, mapper(), projectId, sessionId);
    }

    /**
     * 读取提案修订历史。
     */
    public List<AgentProposalRevisionView> listRevisions(UUID projectId, UUID approvalId) {
        return jdbc.query("""
                SELECT * FROM agent_approval_revision
                WHERE project_id=? AND approval_id=?
                ORDER BY revision ASC
                """, revisionMapper(), projectId, approvalId);
    }

    /**
     * 记录提案修订历史。
     */
    public void recordRevision(
            UUID projectId, UUID approvalId, UUID sourceRunId,
            int revision, JsonNode beforeArguments, JsonNode afterArguments, JsonNode diff) {
        jdbc.update("""
                INSERT INTO agent_approval_revision(
                  project_id, approval_id, source_run_id, revision,
                  before_arguments_json, after_arguments_json, diff_json)
                VALUES (?,?,?,?,?::jsonb,?::jsonb,?::jsonb)
                """, projectId, approvalId, sourceRunId, revision,
                beforeArguments.toString(), afterArguments.toString(), diff.toString());
    }

    /**
     * 修订提案：更新参数、版本、修订号和修订历史。
     * 使用 CAS 确保并发安全。
     */
    public AgentApprovalView revise(
            AgentApprovalView approval, JsonNode newArguments, JsonNode newDiff,
            String argumentsHash, UUID sourceRunId) {
        int newRevision = approval.revision() + 1;
        int updated = jdbc.update("""
                UPDATE agent_approval SET
                  arguments_json=?::jsonb, arguments_hash=?, diff_json=?::jsonb,
                  revision=?, updated_at=now(), version=version+1
                WHERE project_id=? AND id=? AND version=? AND status='PENDING'
                """, newArguments.toString(), argumentsHash, newDiff.toString(),
                newRevision, approval.projectId(), approval.id(), approval.version());
        if (updated != 1) throw new IllegalStateException("提案已被并发修改");
        // 记录修订历史
        recordRevision(approval.projectId(), approval.id(), sourceRunId,
                newRevision, approval.arguments(), newArguments, newDiff);
        return find(approval.projectId(), approval.id()).orElseThrow();
    }

    public int expirePending(OffsetDateTime now) {
        return jdbc.update("""
                WITH expired AS (
                  UPDATE agent_approval SET status='EXPIRED',resolved_at=?,version=version+1
                  WHERE status='PENDING' AND expires_at<=?
                  RETURNING run_id
                )
                UPDATE agent_run r SET status='QUEUED',updated_at=now(),version=version+1
                FROM expired e WHERE r.id=e.run_id AND r.status='WAITING_FOR_APPROVAL'
                """, now, now);
    }

    /**
     * 批准审批。
     * 审批解耦：不再 requeue Run，Run 已成功后仍可批准。
     */
    public AgentApprovalView approve(
            AgentApprovalView approval, UUID approverId, UUID idempotencyKey, JsonNode result) {
        int updated = jdbc.update("""
                UPDATE agent_approval SET status='APPROVED',approver_id=?,
                  idempotency_key=?,result_json=?::jsonb,resolved_at=now(),version=version+1
                WHERE project_id=? AND id=? AND version=? AND status='PENDING'
                """, approverId, idempotencyKey, result.toString(), approval.projectId(),
                approval.id(), approval.version());
        if (updated != 1) throw new IllegalStateException("审批已被处理");
        appendResolution(approval, "APPROVED", result);
        // 审批解耦：不再 requeue Run
        return find(approval.projectId(), approval.id()).orElseThrow();
    }

    /**
     * 拒绝审批。
     * 审批解耦：不再 requeue Run。
     */
    public AgentApprovalView reject(
            AgentApprovalView approval, UUID approverId, UUID idempotencyKey, String reason) {
        int updated = jdbc.update("""
                UPDATE agent_approval SET status='REJECTED',approver_id=?,
                  idempotency_key=?,rejection_reason=?,resolved_at=now(),version=version+1
                WHERE project_id=? AND id=? AND version=? AND status='PENDING'
                """, approverId, idempotencyKey, reason,
                approval.projectId(), approval.id(), approval.version());
        if (updated != 1) throw new IllegalStateException("审批已被处理");
        appendResolution(approval, "REJECTED", json.valueToTree(
                java.util.Map.of("reason", reason == null ? "" : reason)));
        // 审批解耦：不再 requeue Run
        return find(approval.projectId(), approval.id()).orElseThrow();
    }

    private RowMapper<AgentApprovalView> mapper() {
        return (rs, row) -> {
            UUID id = rs.getObject("id", UUID.class);
            String status = rs.getString("status");
            return new AgentApprovalView(
                    id, rs.getObject("project_id", UUID.class),
                    rs.getObject("run_id", UUID.class), rs.getObject("step_id", UUID.class),
                    rs.getString("tool_name"), parse(rs.getString("arguments_json")),
                    parse(rs.getString("diff_json")), rs.getObject("resource_id", UUID.class),
                    integer(rs, "resource_version"), status,
                    rs.getObject("requester_id", UUID.class),
                    rs.getObject("approver_id", UUID.class), parse(rs.getString("result_json")),
                    rs.getString("rejection_reason"),
                    rs.getObject("expires_at", OffsetDateTime.class),
                    rs.getObject("resolved_at", OffsetDateTime.class), rs.getInt("version"),
                    rs.getObject("created_at", OffsetDateTime.class),
                    // V37 新增字段
                    rs.getObject("session_id", UUID.class),
                    parseProposalFamily(rs.getString("proposal_family")),
                    rs.getObject("subject_key", UUID.class),
                    rs.getInt("revision"),
                    rs.getObject("updated_at", OffsetDateTime.class),
                    "PENDING".equals(status) ? id.toString() : null);
        };
    }

    private AgentProposalFamily parseProposalFamily(String value) {
        if (value == null) return null;
        try { return AgentProposalFamily.valueOf(value); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    private RowMapper<AgentProposalRevisionView> revisionMapper() {
        return (rs, row) -> new AgentProposalRevisionView(
                rs.getObject("id", UUID.class),
                rs.getObject("project_id", UUID.class),
                rs.getObject("approval_id", UUID.class),
                rs.getObject("source_run_id", UUID.class),
                rs.getInt("revision"),
                parse(rs.getString("before_arguments_json")),
                parse(rs.getString("after_arguments_json")),
                parse(rs.getString("diff_json")),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private void appendResolution(
            AgentApprovalView approval, String status, JsonNode result) {
        Integer sequence = jdbc.queryForObject(
                "SELECT COALESCE(max(sequence_no),0)+1 FROM agent_step WHERE run_id=?",
                Integer.class, approval.runId());
        jdbc.update("""
                INSERT INTO agent_step(
                  run_id,sequence_no,type,tool_name,input_json,output_json,reason)
                VALUES (?,?,'APPROVAL_RESOLVED',?,?::jsonb,?::jsonb,?)
                """, approval.runId(), sequence == null ? 1 : sequence,
                approval.toolName(), approval.arguments().toString(),
                result.toString(), status);
    }

    private JsonNode parse(String value) {
        if (value == null) return null;
        try { return json.readTree(value); }
        catch (JsonProcessingException exception) {
            throw new IllegalStateException("审批 JSON 无法读取", exception);
        }
    }

    private static UUID resourceId(JsonNode arguments) {
        for (String name : List.of("taskId", "milestoneId")) {
            if (arguments.hasNonNull(name)) {
                try { return UUID.fromString(arguments.get(name).asText()); }
                catch (IllegalArgumentException ignored) { return null; }
            }
        }
        return null;
    }

    private static Integer resourceVersion(JsonNode arguments) {
        JsonNode changes = arguments.path("changes");
        return changes.has("version") && changes.get("version").canConvertToInt()
                ? changes.get("version").intValue() : null;
    }

    private static Integer integer(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static int tokens(Integer reported, String text) {
        if (reported != null) return reported;
        int count = text == null ? 0 : text.codePointCount(0, text.length());
        return Math.max(1, (count + 2) / 3);
    }
}
