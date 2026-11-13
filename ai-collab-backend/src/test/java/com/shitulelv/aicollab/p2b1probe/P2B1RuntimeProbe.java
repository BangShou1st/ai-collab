package com.shitulelv.aicollab.p2b1probe;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.domain.model.AgentExecutionContext;
import com.shitulelv.aicollab.agent.domain.model.AgentPageContext;
import com.shitulelv.aicollab.agent.domain.model.AgentRuntimeLimits;
import com.shitulelv.aicollab.agent.domain.tool.AgentTool;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolContext;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolResult;
import com.shitulelv.aicollab.agent.infrastructure.mcp.McpAgentToolProvider;
import com.shitulelv.aicollab.agent.infrastructure.mcp.gateway.McpGatewayProperties;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * M-12 运行时探针：在应用真正 ready 后通过生产 Provider 查询并执行一次本地只读工具。
 *
 * <p>探针只存在于 test classpath；输出文件由编排器指定，不写生产数据。</p>
 */
final class P2B1RuntimeProbe implements ApplicationRunner {

    private static final UUID PROJECT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID SESSION_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID RUN_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID CONNECTION_ID =
            UUID.fromString("44444444-4444-4444-8444-444444444401");
    private static final String HASH = "a".repeat(64);
    private static final String DISCOVERED = """
            [{"name":"echo_text","description":"只读回显合成文本",
              "inputSchema":{"type":"object","properties":{"text":{"type":"string","maxLength":256}},
              "required":["text"],"additionalProperties":false},
              "annotations":{"readOnlyHint":true}}]
            """;

    private final ApplicationContext context;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final McpAgentToolProvider provider;
    private final McpGatewayProperties gatewayProperties;

    P2B1RuntimeProbe(
            ApplicationContext context,
            JdbcTemplate jdbc,
            ObjectMapper json,
            McpAgentToolProvider provider,
            McpGatewayProperties gatewayProperties) {
        this.context = context;
        this.jdbc = jdbc;
        this.json = json;
        this.provider = provider;
        this.gatewayProperties = gatewayProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        Thread worker = new Thread(this::probe, "p2b1-runtime-probe");
        worker.setDaemon(true);
        worker.start();
    }

    private void probe() {
        Path output = Path.of(requiredEnv("P2B1_PROBE_FILE"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", 1);
        result.put("evidenceLevel", "REAL_PROCESS");
        result.put("pid", ProcessHandle.current().pid());
        result.put("generatedAt", OffsetDateTime.now(ZoneOffset.ofHours(8)).toString());
        result.put("gatewayEnabled", gatewayProperties.enabled());
        try {
            String username = env("DEMO_OWNER_USERNAME", "owner");
            String ownerId = waitForOwner(username);
            if (ownerId == null || ownerId.isBlank()) {
                throw new IllegalStateException("demo owner 不存在: " + username);
            }
            seed(ownerId);

            UUID requester = UUID.fromString(ownerId);
            AgentExecutionContext executionContext = new AgentExecutionContext(
                    RUN_ID,
                    SESSION_ID,
                    PROJECT_ID,
                    requester,
                    "OWNER",
                    false,
                    AgentPageContext.empty(),
                    AgentRuntimeLimits.defaults(),
                    0,
                    List.of());
            List<AgentTool> tools = provider.tools(executionContext);
            List<String> toolNames = tools.stream().map(AgentTool::name).toList();
            AgentTool echo = tools.stream()
                    .filter(tool -> "mcp.local_readonly.echo_text".equals(tool.name()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("旧 Provider 未返回 echo_text"));

            AgentToolResult toolResult = echo.execute(
                    new AgentToolContext(RUN_ID, PROJECT_ID, requester, "OWNER", false, 0),
                    json.createObjectNode().put("text", "p2b1-gateway-off"));
            Map<String, Object> execution = new LinkedHashMap<>();
            execution.put("toolName", echo.name());
            execution.put("status", toolResult.status().name());
            execution.put("data", toolResult.data());
            execution.put("error", toolResult.error());

            result.put("legacyProvider", Map.of(
                    "beanPresent", context.containsBean("mcpAgentToolProvider"),
                    "toolCount", tools.size(),
                    "toolNames", toolNames));
            result.put("gatewayBeans", Map.of(
                    "controller", context.containsBean("mcpGatewayController"),
                    "service", context.containsBean("mcpGatewayService"),
                    "catalogService", context.containsBean("mcpGatewayCatalogService"),
                    "authorizer", context.containsBean("mcpGatewayAuthorizer")));
            result.put("execution", execution);
            result.put("status", "PASS");
        } catch (Exception failure) {
            result.put("status", "FAIL");
            result.put("error", failure.getClass().getName() + ": " + failure.getMessage());
        }
        write(output, result);
    }

    private String waitForOwner(String username) throws InterruptedException {
        long deadline = System.nanoTime() + 120_000_000_000L;
        while (System.nanoTime() < deadline) {
            List<String> ids = jdbc.query(
                    "SELECT id::text FROM app_user WHERE username = ? LIMIT 1",
                    (resultSet, rowNum) -> resultSet.getString(1),
                    username);
            if (!ids.isEmpty() && ids.getFirst() != null && !ids.getFirst().isBlank()) {
                return ids.getFirst();
            }
            Thread.sleep(250);
        }
        throw new IllegalStateException("等待 demo owner 超时: " + username);
    }

    private void seed(String ownerId) {
        UUID owner = UUID.fromString(ownerId);
        String endpoint = requiredEnv("P2B1_MCP_URL");
        String allowlist = "[\"echo_text\"]";
        jdbc.update("DELETE FROM project WHERE id = ?", PROJECT_ID);
        jdbc.update("""
                INSERT INTO project(id,name,description,owner_id,created_by)
                VALUES (?,?,?,?,?)
                """, PROJECT_ID, "P2-B1 Gateway Off", "synthetic gateway-off probe", owner, owner);
        jdbc.update("""
                INSERT INTO project_member(project_id,user_id,role,invited_by)
                VALUES (?,?,'OWNER',?)
                """, PROJECT_ID, owner, owner);
        jdbc.update("""
                INSERT INTO agent_session(id,project_id,creator_id,title,status)
                VALUES (?,?,?,'P2-B1 Gateway Off','ACTIVE')
                """, SESSION_ID, PROJECT_ID, owner);
        jdbc.update("""
                INSERT INTO agent_run(id,session_id,project_id,requester_id,role,depth,goal,status)
                VALUES (?,?,?,?,'SUPERVISOR',0,'P2-B1 Gateway Off','RUNNING')
                """, RUN_ID, SESSION_ID, PROJECT_ID, owner);
        jdbc.update("""
                INSERT INTO agent_mcp_connection(
                    id,project_id,code,name,transport,endpoint,auth_type,timeout_ms,
                    max_result_bytes,tool_allowlist_json,discovered_tools_json,
                    schema_hash,confirmed_schema_hash,enabled,created_by,version)
                VALUES (?,?,?,?, ?,?,?,?,?, ?::jsonb,?::jsonb,?, ?,true,?,1)
                """,
                CONNECTION_ID, PROJECT_ID, "local_readonly", "local_readonly",
                "STREAMABLE_HTTP", endpoint, "NONE", 3000, 32768,
                allowlist, DISCOVERED, HASH, HASH, owner);
        jdbc.update("""
                INSERT INTO agent_mcp_provider_admission(
                    connection_id,provider_type,provider_owner,data_scope,retention_policy,
                    terms_url,least_privilege_scope,read_only_endpoint,status,
                    reviewed_by,reviewed_at,version)
                VALUES (?, 'LOCAL_FIXTURE','p2b1-security','synthetic gateway-off metadata',
                    'ephemeral test data','urn:ai-collab:local-fixture','read-only',true,
                    'APPROVED',?,now(),1)
                """, CONNECTION_ID, owner);
    }

    private void write(Path output, Map<String, Object> result) {
        try {
            Files.createDirectories(output.getParent());
            Files.writeString(output, json.writerWithDefaultPrettyPrinter().writeValueAsString(result));
        } catch (Exception failure) {
            throw new IllegalStateException("无法写 M-12 探针输出: " + output, failure);
        }
    }

    private static String requiredEnv(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("缺少环境变量 " + key);
        }
        return value;
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
