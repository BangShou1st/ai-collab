package com.shitulelv.aicollab.agent.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.application.runtime.AgentEventService;
import com.shitulelv.aicollab.agent.application.runtime.AgentEventStreamService;
import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.application.view.AgentSessionSummaryView;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentEventRepository;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRepository;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRunEventRecorder;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelFinishReason;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnResult;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelUsage;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模型成本台账（非计费，设计文档 agent-fanout-budget-and-agentic-planning.md §4.4，V85）
 * 集成回归（真实 PostgreSQL）：
 * <ul>
 *   <li>provider 上报与估算两种取数下，成本 = bookedTokens/1000 × 单价，挂在 MODEL_COMPLETED 事件行；</li>
 *   <li>价格 NULL 或运行未关联配置 → cost_amount NULL（不计成本）；</li>
 *   <li>摘要调用（无独立事件行）的成本累加到最近一次 MODEL_COMPLETED，聚合求和不受挂载点影响；</li>
 *   <li>UNKNOWN 用量（无任何可结算证据）不虚构零费用；</li>
 *   <li>runTotalCost / 会话列表 totalCost 聚合正确。</li>
 * </ul>
 * 不覆盖账单/扣费/配额——非目标是计费系统。
 */
@Testcontainers(disabledWithoutDocker = true)
class AgentModelCostLedgerPostgresTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    static JdbcTemplate jdbc;
    static AgentRepository repository;
    static AgentRunEventRecorder recorder;

    @BeforeAll
    static void migrate() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        // 事件走真实持久化路径（成本挂在 agent_run_event 行上，必须有事件写入），
        // SSE 投递用 mock（本测试不订阅流）
        var events = new AgentEventService(new AgentEventRepository(jdbc, json),
                Mockito.mock(AgentEventStreamService.class));
        recorder = new AgentRunEventRecorder(jdbc, json);
        ReflectionTestUtils.setField(recorder, "events", events);
        repository = new AgentRepository(jdbc, json, recorder);
    }

    @BeforeEach
    void clearFixtures() {
        // app_user 级联清掉 project / agent_* / user_ai_provider
        jdbc.execute("TRUNCATE app_user CASCADE");
    }

    private record Fixture(UUID user, UUID project, UUID sessionId, AgentRunView run) {
    }

    /** 建用户/项目/会话/运行并置为 RUNNING；价格为 null 时同样建配置行（验证"价格 NULL"分支）。 */
    private Fixture fixture(BigDecimal priceInput, BigDecimal priceOutput) {
        UUID user = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,?,?,?)",
                user, "cost-" + user.toString().substring(0, 8), "test-only-hash", "Cost");
        jdbc.update("INSERT INTO project(id,name,owner_id,created_by) VALUES (?,?,?,?)",
                project, "Cost ledger", user, user);
        jdbc.update("INSERT INTO project_member(project_id,user_id,role) VALUES (?,?,'OWNER')",
                project, user);
        UUID provider = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO user_ai_provider(id,user_id,name,provider_type,base_url,api_path,model_name,
                    price_per_1k_input,price_per_1k_output)
                VALUES (?,?,?,?,?,?,?,?,?)
                """, provider, user, "costed", "OPENAI_COMPATIBLE", "https://api.example.com",
                "/v1/chat/completions", "cost-model", priceInput, priceOutput);
        var session = repository.createSession(project, user, "成本台账");
        var created = repository.createRun(project, session.id(), user, "统计成本", false, null, null);
        jdbc.update("""
                UPDATE agent_run SET status='RUNNING',lease_owner='test',
                  lease_expires_at=now()+interval '6 minutes',claim_version=1,
                  model_configuration_id=?
                WHERE id=?
                """, provider, created.id());
        return new Fixture(user, project, session.id(),
                repository.findRun(project, created.id()).orElseThrow());
    }

    private static ModelTurnResult providerTurn(int input, int output) {
        return new ModelTurnResult("收到资料", List.of(),
                ModelFinishReason.STOP, new ModelUsage(input, output),
                "OPENAI_COMPATIBLE", "cost-model", 1200L);
    }

    private static ModelTurnResult estimatedTurn(String content) {
        return new ModelTurnResult(content, List.of(), ModelFinishReason.STOP, null,
                "OPENAI_COMPATIBLE", "cost-model", 1200L);
    }

    private static final BigDecimal PRICE_IN = new BigDecimal("0.002000");
    private static final BigDecimal PRICE_OUT = new BigDecimal("0.006000");

    /** 期望成本 = in/1000 × priceIn + out/1000 × priceOut（6 位小数，HALF_UP）。 */
    private static BigDecimal expectedCost(long input, long output) {
        return PRICE_IN.multiply(BigDecimal.valueOf(input))
                .divide(BigDecimal.valueOf(1000), 6, java.math.RoundingMode.HALF_UP)
                .add(PRICE_OUT.multiply(BigDecimal.valueOf(output))
                        .divide(BigDecimal.valueOf(1000), 6, java.math.RoundingMode.HALF_UP));
    }

    private BigDecimal eventCost(UUID runId) {
        return jdbc.queryForObject("""
                SELECT cost_amount FROM agent_run_event
                WHERE run_id=? AND type='MODEL_COMPLETED' AND sequence_no=(
                    SELECT max(sequence_no) FROM agent_run_event WHERE run_id=? AND type='MODEL_COMPLETED')
                """, BigDecimal.class, runId, runId);
    }

    /** 插入一行 ATTEMPTED 状态的摘要步骤，返回其 id（即 completeSummaryAttempt 的调用身份）。 */
    private UUID insertSummaryStep(UUID runId, int sequenceNo) {
        return jdbc.queryForObject("""
                INSERT INTO agent_step(run_id,sequence_no,type,tool_name,reason,output_json)
                VALUES (?,?,'MODEL_REQUEST','context_summary','CONTEXT_SUMMARY',?::jsonb)
                RETURNING id
                """, UUID.class, runId, sequenceNo, "{\"status\":\"ATTEMPTED\"}");
    }

    @Test
    void providerReportedUsageBooksExactCostOnModelCompletedEvent() {
        Fixture fixture = fixture(PRICE_IN, PRICE_OUT);

        repository.recordModelTurn(fixture.run(), providerTurn(10000, 1500));

        // 0.002×10 + 0.006×1.5 = 0.029000
        assertThat(eventCost(fixture.run().id())).isEqualByComparingTo("0.029000");
        assertThat(repository.runTotalCost(fixture.project(), fixture.run().id()))
                .isEqualByComparingTo("0.029000");
    }

    @Test
    void estimatedUsageBooksCostFromBookedTokens() {
        Fixture fixture = fixture(PRICE_IN, PRICE_OUT);

        AgentRunView updated = repository.recordModelTurn(fixture.run(), estimatedTurn("请总结以下项目进展并给出建议"));

        // 估算取数：成本按实际入账（booked）的估算 token 计算，与 run 的 actual 累计同源
        BigDecimal expected = expectedCost(updated.inputTokensActual(), updated.outputTokensActual());
        assertThat(eventCost(fixture.run().id())).isEqualByComparingTo(expected);
        assertThat(updated.tokenUsageEstimated()).isTrue();
    }

    @Test
    void missingPriceLeavesCostNull() {
        // 配置存在但价格未填：cost_amount 保持 NULL，聚合也无成本数据（null，而非 0）
        Fixture fixture = fixture(null, null);

        repository.recordModelTurn(fixture.run(), providerTurn(10000, 1500));

        assertThat(eventCost(fixture.run().id())).isNull();
        assertThat(repository.runTotalCost(fixture.project(), fixture.run().id())).isNull();
        List<AgentSessionSummaryView> summaries = repository.listSessionSummaries(fixture.project(), 10);
        assertThat(summaries).hasSize(1);
        assertThat(summaries.getFirst().totalCost()).isNull();
    }

    @Test
    void summaryCallCostAccumulatesAndSessionAggregationSumsAllEvents() {
        Fixture fixture = fixture(PRICE_IN, PRICE_OUT);

        AgentRunView afterFirst = repository.recordModelTurn(fixture.run(), providerTurn(10000, 1500)); // 0.029000
        repository.recordModelTurn(afterFirst, providerTurn(2500, 500)); // 0.008000（用返回的最新版本运行继续记账）

        // 摘要调用：无独立事件行，成本累加到最近一次 MODEL_COMPLETED
        UUID attemptId = insertSummaryStep(fixture.run().id(), 900);
        recorder.completeSummaryAttempt(attemptId, "SETTLED", "cost-model",
                new AgentRunEventRecorder.UsageSettlement(8000, 2000,
                        AgentRunEventRecorder.UsageSettlement.PROVIDER,
                        AgentRunEventRecorder.UsageSettlement.PROVIDER, 100L), null);

        // 0.029000 + 0.008000 + (0.002×8 + 0.006×2 = 0.028000) = 0.065000
        assertThat(repository.runTotalCost(fixture.project(), fixture.run().id()))
                .isEqualByComparingTo("0.065000");
        // 最近一次 MODEL_COMPLETED 事件承载 0.008000 + 0.028000
        assertThat(eventCost(fixture.run().id())).isEqualByComparingTo("0.036000");

        List<AgentSessionSummaryView> summaries = repository.listSessionSummaries(fixture.project(), 10);
        assertThat(summaries).hasSize(1);
        assertThat(summaries.getFirst().totalCost()).isEqualByComparingTo("0.065000");
    }

    @Test
    void unknownUsageDoesNotInventZeroCost() {
        Fixture fixture = fixture(PRICE_IN, PRICE_OUT);
        repository.recordModelTurn(fixture.run(), providerTurn(10000, 1500));

        // 无任何可结算证据（UNKNOWN）：不虚构零费用，成本不追加
        UUID attemptId = insertSummaryStep(fixture.run().id(), 901);
        recorder.completeSummaryAttempt(attemptId, "FAILED", "cost-model",
                AgentRunEventRecorder.UsageSettlement.unknown(), null);

        assertThat(repository.runTotalCost(fixture.project(), fixture.run().id()))
                .isEqualByComparingTo("0.029000");
    }

    @Test
    void runWithoutModelConfigurationLeavesCostNull() {
        Fixture fixture = fixture(PRICE_IN, PRICE_OUT);
        // 运行未关联模型配置（model_configuration_id 为空的存量运行）：不计成本，不报错
        jdbc.update("UPDATE agent_run SET model_configuration_id=NULL WHERE id=?", fixture.run().id());

        repository.recordModelTurn(fixture.run(), providerTurn(10000, 1500));

        assertThat(eventCost(fixture.run().id())).isNull();
    }
}
