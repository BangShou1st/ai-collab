package com.shitulelv.aicollab.agent.infrastructure.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.application.view.AgentApprovalView;
import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.domain.model.AgentDecision;
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
        jdbc.update("""
                INSERT INTO agent_approval(
                  id,project_id,run_id,step_id,tool_name,arguments_json,arguments_hash,
                  diff_json,resource_id,resource_version,requester_id,nonce_hash,expires_at)
                VALUES (?,?,?,?,?,?::jsonb,?,?::jsonb,?,?,?,?,?)
                """, approvalId, run.projectId(), run.id(), stepId, call.tool(),
                arguments.toString(), argumentsHash, diff.toString(), resourceId(arguments),
                resourceVersion(arguments), run.requesterId(), nonceHash, expiresAt);
        int updated = jdbc.update("""
                UPDATE agent_run SET status='WAITING_FOR_APPROVAL',
                  steps_used=steps_used+1,tool_calls_used=tool_calls_used+1,
                  input_tokens_used=input_tokens_used+?,output_tokens_used=output_tokens_used+?,
                  token_usage_estimated=?,model_provider=?,model_name=?,
                  lease_owner=NULL,lease_expires_at=NULL,updated_at=now(),version=version+1
                WHERE project_id=? AND id=? AND version=? AND status='RUNNING'
                """, tokens(completion.promptTokens(), ""),
                tokens(completion.completionTokens(), completion.content()),
                completion.promptTokens() == null || completion.completionTokens() == null,
                completion.provider(), completion.model(), run.projectId(), run.id(), run.version());
        if (updated != 1) throw new IllegalStateException("Agent 运行已被并发修改");
        return find(run.projectId(), approvalId).orElseThrow();
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
        jdbc.update("""
                UPDATE agent_run SET status='QUEUED',updated_at=now(),version=version+1
                WHERE project_id=? AND id=? AND status='WAITING_FOR_APPROVAL'
                """, approval.projectId(), approval.runId());
        return find(approval.projectId(), approval.id()).orElseThrow();
    }

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
        jdbc.update("""
                UPDATE agent_run SET status='QUEUED',updated_at=now(),version=version+1
                WHERE project_id=? AND id=? AND status='WAITING_FOR_APPROVAL'
                """, approval.projectId(), approval.runId());
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
                    "PENDING".equals(status) ? id.toString() : null);
        };
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
