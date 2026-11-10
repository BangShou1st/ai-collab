package com.shitulelv.aicollab.agent.evals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Agent 评估套件入口：加载 golden case 集（畸形 case 在装配阶段即失败），逐 case 执行
 * 并以动态测试呈现，最终输出汇总报告到 stdout，并把结构化结果写入
 * {@code target/eval-report.json}（受控目录，不污染源码树；version 字段 + case 结果数组，
 * 为后续"通过率 X%→Y%"的基线对比保留格式）。
 *
 * <p>单独执行：{@code bash ./mvnw -B -ntp test -Dtest='AgentEval*,GoldenCase*'}。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentEvalSuiteTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17");

    static AgentEvalRunner runner;
    static List<GoldenCase> cases;
    static final List<AgentEvalRunner.CaseResult> results = new ArrayList<>();

    @BeforeAll
    static void setUp() {
        // 装配阶段即加载并校验：任何畸形 case 都让套件启动失败，而不是静默跳过
        cases = new GoldenCaseLoader().load();
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        runner = new AgentEvalRunner(new JdbcTemplate(dataSource));
    }

    @TestFactory
    Stream<DynamicTest> goldenCases() {
        assertThat(cases).as("golden case 数量应达到起步规模").hasSize(20);
        return cases.stream().map(c -> DynamicTest.dynamicTest(
                c.category() + " / " + c.id(),
                () -> {
                    AgentEvalRunner.CaseResult result = runner.run(c);
                    results.add(result);
                    // 失败明细足够定位：逐条断言名 + 期望/实际差异
                    assertThat(result.passed())
                            .as("case %s 失败：%s%n明细：%s", c.id(), result.failureReasons(), result.details())
                            .isTrue();
                }));
    }

    /** 汇总守门测试：所有 case 必须全部通过，否则套件整体失败。 */
    @Test
    void summaryAllCasesMustPass() {
        assertThat(results).as("动态测试未产生任何结果").isNotEmpty();
        List<String> failed = results.stream()
                .filter(r -> !r.passed())
                .map(r -> r.caseId() + ": " + r.failureReasons())
                .toList();
        assertThat(failed).as("失败的 golden case").isEmpty();
    }

    @AfterAll
    static void writeReport() {
        printSummary();
        writeReportJson();
    }

    // ========== stdout 汇总报告 ==========

    private static void printSummary() {
        long passed = results.stream().filter(AgentEvalRunner.CaseResult::passed).count();
        Map<String, List<AgentEvalRunner.CaseResult>> byCategory = new LinkedHashMap<>();
        for (AgentEvalRunner.CaseResult r : results) {
            byCategory.computeIfAbsent(r.category(), k -> new ArrayList<>()).add(r);
        }
        StringBuilder out = new StringBuilder();
        out.append("\n===== Agent 评估套件汇总 =====\n");
        out.append(String.format("总计 %d 个 case，通过 %d，失败 %d，通过率 %.1f%%%n",
                results.size(), passed, results.size() - passed, 100.0 * passed / results.size()));
        for (Map.Entry<String, List<AgentEvalRunner.CaseResult>> entry : byCategory.entrySet()) {
            long categoryPassed = entry.getValue().stream()
                    .filter(AgentEvalRunner.CaseResult::passed).count();
            out.append(String.format("  [%s] %d/%d%n", entry.getKey(), categoryPassed, entry.getValue().size()));
            for (AgentEvalRunner.CaseResult r : entry.getValue()) {
                out.append(String.format("    %s %s（%d ms）%n", r.passed() ? "PASS" : "FAIL",
                        r.caseId(), r.durationMs()));
                if (!r.passed()) {
                    for (String reason : r.failureReasons()) {
                        out.append("      - ").append(reason).append('\n');
                    }
                }
            }
        }
        out.append("报告已写入 target/eval-report.json\n");
        System.out.println(out);
    }

    // ========== 结构化报告（基线格式：version + case 结果数组） ==========

    private static void writeReportJson() {
        try {
            ObjectMapper json = new ObjectMapper();
            ObjectNode report = json.createObjectNode();
            report.put("version", 1);
            report.put("suite", "agent-eval-golden-set");
            report.put("generatedAt", OffsetDateTime.now().toString());
            long passed = results.stream().filter(AgentEvalRunner.CaseResult::passed).count();
            ObjectNode summary = report.putObject("summary");
            summary.put("total", results.size());
            summary.put("passed", passed);
            summary.put("failed", results.size() - passed);
            summary.put("passRate", results.isEmpty() ? 0.0 : (double) passed / results.size());
            Map<String, List<AgentEvalRunner.CaseResult>> byCategory = new LinkedHashMap<>();
            for (AgentEvalRunner.CaseResult r : results) {
                byCategory.computeIfAbsent(r.category(), k -> new ArrayList<>()).add(r);
            }
            ObjectNode categories = summary.putObject("byCategory");
            byCategory.forEach((category, list) -> {
                ObjectNode node = categories.putObject(category);
                node.put("total", list.size());
                node.put("passed", list.stream().filter(AgentEvalRunner.CaseResult::passed).count());
            });
            ArrayNode caseArray = report.putArray("cases");
            for (AgentEvalRunner.CaseResult r : results) {
                ObjectNode node = caseArray.addObject();
                node.put("caseId", r.caseId());
                node.put("category", r.category());
                node.put("guards", r.guards());
                node.put("passed", r.passed());
                node.put("finalStatus", r.finalStatus());
                node.put("durationMs", r.durationMs());
                var reasons = node.putArray("failureReasons");
                r.failureReasons().forEach(reasons::add);
                var details = node.putArray("assertions");
                for (AgentEvalRunner.AssertionDetail d : r.details()) {
                    ObjectNode detail = details.addObject();
                    detail.put("name", d.name());
                    detail.put("passed", d.passed());
                    detail.put("message", d.message());
                }
            }
            // 受控输出目录：target/ 下，不污染源码树；任务交付前无需清理（构建产物）
            Path target = Path.of("target", "eval-report.json");
            Files.createDirectories(target.getParent());
            Files.writeString(target, json.writerWithDefaultPrettyPrinter().writeValueAsString(report),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 报告写盘失败不影响测试判定，但要在 stdout 显式提示
            System.out.println("[agent-evals] 写入 target/eval-report.json 失败: " + e);
        }
    }
}
