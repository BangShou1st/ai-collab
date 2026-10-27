package com.shitulelv.aicollab.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P10-REAL-GENERATION-03 离线重评分回放：对本轮已落盘的真实回答以冻结的评分器 v6 重新评分。
 * 零外部模型调用（默认 CI 安全）；产物缺失时整类跳过。
 * 修复说明：真实批次内嵌评分时 Agent sources 解析缺失 quote 字段（测量工具对接缺陷，非评分器缺陷），
 * 本回放以修正后的解析重评分并输出权威 scorer-results.json；两版差异如实保留于 scorer-results 的 notes。
 */
class P10ScorerReplay03Test {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final Path FIXTURES = ROOT.resolve("tests/fixtures/documents/p9-rag-generalization-01");
    private static final Path QA_RESULTS = Path.of("target", "p10-real-generation-03", "real", "generation-results.json");
    private static final Path QA_CONTEXT = Path.of("target", "p10-real-generation-03", "real", "retrieval-context.json");
    private static final Path AGENT_RESULTS = Path.of("target", "p10-real-agent-03", "real", "agent-execution-results.json");
    private static final Path OUTPUT = Path.of("target", "p10-real-generation-03", "scorer-results.json");

    @Test
    void replayScorerV6OverRealBatchAnswers() throws Exception {
        Assumptions.assumeTrue(Files.exists(QA_RESULTS) && Files.exists(AGENT_RESULTS),
                "real batch outputs not present; nothing to replay");
        JsonNode questions = JSON.readTree(FIXTURES.resolve("p10-evaluation-question-set.json").toFile());
        JsonNode agentScenarios = JSON.readTree(FIXTURES.resolve("p10-real-generation-03-agent-scenarios.json").toFile()).path("scenarios");
        List<Map<String, Object>> qa = (List<Map<String, Object>>) JSON.convertValue(
                JSON.readTree(QA_RESULTS.toFile()), new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
        List<Map<String, Object>> rc = (List<Map<String, Object>>) JSON.convertValue(
                JSON.readTree(QA_CONTEXT.toFile()), new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
        List<Map<String, Object>> agent = (List<Map<String, Object>>) JSON.convertValue(
                JSON.readTree(AGENT_RESULTS.toFile()), new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});

        List<Map<String, Object>> scored = new ArrayList<>();
        for (Map<String, Object> row : qa) {
            String runId = String.valueOf(row.get("runId"));
            String questionId = String.valueOf(row.get("id"));
            String answer = String.valueOf(row.get("rawAnswer"));
            if (answer == null || answer.isBlank() || "null".equals(answer)) continue;
            JsonNode q = find(questions.path("questions"), "id", questionId);
            Map<String, Object> context = rc.stream().filter(x -> runId.equals(String.valueOf(x.get("runId")))).findFirst().orElseThrow();
            List<P10AnswerQualityScorer.Source> sources = qaSources(context);
            List<Map<String, Object>> facts = qaFacts(q, sources);
            Map<String, Object> rules = JSON.convertValue(q.path("rules"), new com.fasterxml.jackson.core.type.TypeReference<>() {});
            Map<String, Object> score = P10AnswerQualityScorer.score(runId + ":" + questionId, q.path("dimension").asText(),
                    q.path("abstain").asBoolean(), facts, rules, answer, sources, P10AnswerQualityScorer.QA, null);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("kind", "QA");
            item.put("runId", runId);
            item.put("questionId", questionId);
            item.put("score", score);
            scored.add(item);
        }
        for (Map<String, Object> row : agent) {
            String id = String.valueOf(row.get("id"));
            String answer = String.valueOf(row.get("finalAnswer"));
            if (answer == null || answer.isBlank() || "null".equals(answer)) continue;
            JsonNode scenario = find(agentScenarios, "id", id);
            List<P10AnswerQualityScorer.Source> sources = agentSources(row);
            List<Map<String, String>> citations = agentCitationPairs(row);
            List<Map<String, Object>> facts = agentFacts(scenario, sources);
            Map<String, Object> rules = agentRules(scenario);
            Map<String, Object> score = P10AnswerQualityScorer.score(id, scenario.path("dimension").asText(),
                    scenario.path("abstain").asBoolean(false), facts, rules, answer, sources, P10AnswerQualityScorer.AGENT, citations);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("kind", "AGENT");
            item.put("runId", id);
            item.put("runStatus", String.valueOf(row.get("runStatus")));
            item.put("score", score);
            scored.add(item);
        }

        long high = scored.stream().mapToLong(s -> {
            Object value = ((Map<?, ?>) s.get("score")).get("highSeverity");
            return value instanceof List<?> list ? list.size() : 0;
        }).sum();
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("batch", "p10-real-generation-03");
        document.put("scorerVersion", "2.1-scoped-contextual-v6（源文件 SHA-256 见证据目录 scorer-and-corpus.sha256）");
        document.put("note", "对真实批次回答的离线重评分回放：QA 用真实检索入选来源，Agent 用工具真实返回来源（quote 字段解析修正）。"
                + "真实批次内嵌评分中 Agent sources 曾因 quote 字段缺失判 CONTEXT_MISS，产生失真的泄漏/幻觉观察；本回放为权威版本，差异已在报告中说明。");
        document.put("totalScored", scored.size());
        document.put("totalHighSeveritySignals", high);
        document.put("results", scored);
        Files.createDirectories(OUTPUT.getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(OUTPUT.toFile(), document);
        assertThat(scored).isNotEmpty();
    }

    @SuppressWarnings("unchecked")
    private List<P10AnswerQualityScorer.Source> qaSources(Map<String, Object> context) {
        List<P10AnswerQualityScorer.Source> sources = new ArrayList<>();
        Map<String, Object> ctx = (Map<String, Object>) context.get("context");
        int rank = 1;
        for (Map<String, Object> s : (List<Map<String, Object>>) ctx.get("sources")) {
            sources.add(new P10AnswerQualityScorer.Source(rank++, String.valueOf(s.get("originalFilename")),
                    String.valueOf(s.get("documentId")), String.valueOf(s.get("chunkId")), String.valueOf(s.get("content"))));
        }
        return sources;
    }

    private List<Map<String, Object>> qaFacts(JsonNode question, List<P10AnswerQualityScorer.Source> sources) {
        List<Map<String, Object>> facts = new ArrayList<>();
        for (JsonNode f : question.path("expectedFacts")) {
            Map<String, Object> fact = JSON.convertValue(f, new com.fasterxml.jackson.core.type.TypeReference<>() {});
            boolean present = sources.stream().anyMatch(s -> s.document().equals(f.path("document").asText()) && s.content().contains(f.path("marker").asText()));
            fact.put("contextAvailability", present ? "RETAINED" : "CONTEXT_MISS");
            facts.add(fact);
        }
        for (JsonNode marker : question.path("rules").path("requiredMarkers")) {
            String text = marker.asText();
            if (facts.stream().anyMatch(f -> String.valueOf(f.get("marker")).contains(text))) continue;
            var matches = sources.stream().filter(s -> s.content().contains(text)).toList();
            Map<String, Object> fact = new LinkedHashMap<>();
            fact.put("marker", text);
            fact.put("document", matches.isEmpty() ? "UNAVAILABLE" : matches.getFirst().document());
            fact.put("contextAvailability", matches.isEmpty() ? "CONTEXT_MISS" : "RETAINED");
            fact.put("origin", "RUBRIC_REQUIRED_MARKER_OBSERVED_IN_CURRENT_CONTEXT");
            facts.add(fact);
        }
        return facts;
    }

    @SuppressWarnings("unchecked")
    private List<P10AnswerQualityScorer.Source> agentSources(Map<String, Object> row) {
        List<P10AnswerQualityScorer.Source> sources = new ArrayList<>();
        int rank = 1;
        // read_document_section 的 items 不含 filename/documentId，用 list_project_documents 的目录建立映射。
        Map<String, String> filenameById = new LinkedHashMap<>();
        for (Map<String, Object> invocation : (List<Map<String, Object>>) row.get("toolInvocations")) {
            Object output = invocation.get("output");
            if (output == null) continue;
            try {
                JsonNode root = JSON.readTree(String.valueOf(output));
                for (JsonNode item : root.path("data").path("items")) {
                    if (item.hasNonNull("documentId") && item.hasNonNull("filename"))
                        filenameById.put(item.path("documentId").asText(), item.path("filename").asText());
                }
            } catch (Exception parse) { /* skip */ }
        }
        for (Map<String, Object> invocation : (List<Map<String, Object>>) row.get("toolInvocations")) {
            Object output = invocation.get("output");
            if (output == null) continue;
            JsonNode items;
            String rootDocumentId;
            try {
                JsonNode root = JSON.readTree(String.valueOf(output));
                items = root.path("data").path("sources");
                if (!items.isArray()) items = root.path("sources");
                if (!items.isArray()) items = root.path("data").path("items"); // read_document_section 返回 data.items
                if (!items.isArray()) items = root.path("results");
                rootDocumentId = root.path("data").path("documentId").asText(""); // items 内无 documentId，取根级
            } catch (Exception parse) { continue; }
            for (JsonNode item : items) {
                String documentId = item.hasNonNull("documentId") ? item.path("documentId").asText() : rootDocumentId;
                String filename = item.path("originalFilename").asText(item.path("filename").asText(filenameById.getOrDefault(documentId, "")));
                if (filename.isBlank()) continue;
                String content = item.path("content").asText(item.path("quote").asText(item.path("excerpt").asText("")));
                sources.add(new P10AnswerQualityScorer.Source(rank++, filename,
                        documentId, item.path("chunkId").asText(""), content));
            }
        }
        return sources;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> agentCitationPairs(Map<String, Object> row) {
        // 成对引用身份取工具真实返回的 citations（AgentCitation 结构）；扁平 UUID 列表无法成对，不在此构造。
        List<Map<String, String>> pairs = new ArrayList<>();
        for (Map<String, Object> invocation : (List<Map<String, Object>>) row.get("toolInvocations")) {
            Object output = invocation.get("output");
            if (output == null) continue;
            try {
                JsonNode root = JSON.readTree(String.valueOf(output));
                for (JsonNode citation : root.path("citations")) {
                    if (citation.hasNonNull("documentId") && citation.hasNonNull("chunkId")) {
                        Map<String, String> pair = new LinkedHashMap<>();
                        pair.put("documentId", citation.path("documentId").asText());
                        pair.put("chunkId", citation.path("chunkId").asText());
                        pairs.add(pair);
                    }
                }
            } catch (Exception parse) { /* skip */ }
        }
        return pairs;
    }

    private List<Map<String, Object>> agentFacts(JsonNode scenario, List<P10AnswerQualityScorer.Source> sources) {
        List<Map<String, Object>> facts = new ArrayList<>();
        for (JsonNode f : scenario.path("expectedFacts")) {
            Map<String, Object> fact = JSON.convertValue(f, new com.fasterxml.jackson.core.type.TypeReference<>() {});
            boolean present = sources.stream().anyMatch(s -> s.document().equals(f.path("document").asText()) && s.content().contains(f.path("marker").asText()));
            fact.put("contextAvailability", present ? "RETAINED" : "CONTEXT_MISS");
            facts.add(fact);
        }
        return facts;
    }

    private Map<String, Object> agentRules(JsonNode scenario) {
        Map<String, Object> rules = new LinkedHashMap<>();
        List<String> forbidden = new ArrayList<>();
        scenario.path("forbidden").forEach(f -> forbidden.add(f.asText()));
        rules.put("forbiddenAffirmations", forbidden);
        rules.put("requiredMarkers", List.of());
        rules.put("legacyMarkers", List.of());
        rules.put("legacyContextWords", List.of());
        return rules;
    }

    private static JsonNode find(JsonNode array, String field, String value) {
        for (JsonNode item : array) if (value.equals(item.path(field).asText())) return item;
        throw new IllegalStateException("frozen item missing: " + value);
    }
}
