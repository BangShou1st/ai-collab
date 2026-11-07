package com.shitulelv.aicollab.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P10-REPLAY-01 真实回答重放回归测试：用 2026-11-07 真实评测轮次中模型（temperature=0）的
 * <b>逐字原始输出</b>回放冻结评分规则，固化评分器精度修复的双向行为——
 * <ul>
 *   <li>修复前被高严重度误判的真实安全回答（引用-驳斥注入、跨子句废止限定、量词指代、
 *       引号+后置存在否定）修复后不再误判；</li>
 *   <li>全部真实回答均为检索层事实保留率达标轮次的产出（52/54、53/54、P10 有证据题 12/12），</li>
 * </ul>
 * 夹具：{@code src/test/resources/retrieval/p10-real-answer-replay-2026-11-07.json}；
 * 冻结规则原样取自 P10 冻结题集（未修改）；来源正文取冻结语料全文（与离线评分协议一致）。
 */
class P10RealAnswerReplayTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final Path FIXTURE = Paths.get("src", "test", "resources", "retrieval",
            "p10-real-answer-replay-2026-11-07.json");
    private static final Path QUESTION_SET = ROOT.resolve(
            "tests/fixtures/documents/p9-rag-generalization-01/p10-evaluation-question-set.json");
    private static final Path CORPUS_DIR = ROOT.resolve("tests/fixtures/documents/p9-rag-generalization-01");

    @Test
    @SuppressWarnings("unchecked")
    void replayRealModelAnswersAgainstFrozenRules() throws Exception {
        JsonNode fixture = JSON.readTree(FIXTURE.toFile());
        JsonNode questions = JSON.readTree(QUESTION_SET.toFile()).path("questions");
        Map<String, JsonNode> byId = new java.util.LinkedHashMap<>();
        questions.forEach(q -> byId.put(q.path("id").asText(), q));

        for (JsonNode ruleCase : fixture.path("cases")) {
            String questionId = ruleCase.path("question").asText();
            JsonNode question = byId.get(questionId);
            assertThat(question).as("冻结题集必须包含 %s", questionId).isNotNull();

            List<P10AnswerQualityScorer.Source> sources = sourcesFor(questionId);
            Map<String, Object> rules = JSON.convertValue(question.path("rules"),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            boolean abstain = question.path("abstain").asBoolean();

            Map<String, Object> score = P10AnswerQualityScorer.score(
                    ruleCase.path("id").asText(), question.path("dimension").asText(), abstain,
                    List.of(), rules, ruleCase.path("rawAnswer").asText(), sources,
                    P10AnswerQualityScorer.QA, null);

            List<String> highSeverity = (List<String>) score.get("highSeverity");
            List<String> expected = new ArrayList<>();
            ruleCase.path("expectHighSeverity").forEach(item -> expected.add(item.asText()));
            assertThat(highSeverity)
                    .as("%s：%s%n真实回答:%n%s", ruleCase.path("id").asText(),
                            ruleCase.path("note").asText(), ruleCase.path("rawAnswer").asText())
                    .isEqualTo(expected);
        }
    }

    /** 按题目选择冻结语料全文作为离线评分来源（协议同 P10 离线评分：文档级全文）。 */
    private static List<P10AnswerQualityScorer.Source> sourcesFor(String questionId) throws Exception {
        List<String> documents = switch (questionId) {
            case "AQ02-REPEALED" -> List.of("D1-差旅费管理办法V1.md", "D2-差旅费管理办法V2.md",
                    "D3-差旅费管理补充通知.md", "D4-培训服务合同管理办法.md");
            case "AQ11-ABSTAIN-NONE", "AQ14-INJECTION" -> List.of("F1-审计核对工作指引.md", "F2-审计证据管理规范.md",
                    "F3-审计待定事项备忘.md", "F4-外部审计参考摘录-未审核来源.md", "D4-培训服务合同管理办法.md");
            case "AQ12-ABSTAIN-INDIRECT" -> List.of("F1-审计核对工作指引.md", "F2-审计证据管理规范.md",
                    "F3-审计待定事项备忘.md", "F4-外部审计参考摘录-未审核来源.md", "D4-培训服务合同管理办法.md");
            default -> List.of();
        };
        List<P10AnswerQualityScorer.Source> sources = new ArrayList<>();
        int rank = 1;
        for (String filename : documents) {
            String content = java.nio.file.Files.readString(CORPUS_DIR.resolve(filename), StandardCharsets.UTF_8);
            sources.add(new P10AnswerQualityScorer.Source(rank, filename, "doc-" + rank, "chunk-" + rank,
                    content));
            rank++;
        }
        return sources;
    }
}
