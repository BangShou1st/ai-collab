package com.shitulelv.aicollab.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.document.application.view.DocumentSearchHit;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceValueExperimentTest {
    private static DocumentSearchHit hit(int id, int doc, String title, String body, String hash, double score) {
        return new DocumentSearchHit(new UUID(0, id), new UUID(0, doc), "文件<&>😀.md", title,
                body, hash, score, Map.of("projectId", "isolated-project"));
    }

    @Test
    void fixedBoundaryMatrixMeasuresQualitySeparatelyFromMechanicalConstraints() throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int limit : List.of(8, 12)) {
            List<DocumentSearchHit> competition = new ArrayList<>();
            competition.add(hit(1, 100, "版本甲", "发布审批由经理负责。", "old", .95));
            competition.add(hit(2, 100, "版本乙", "发布审批由委员会负责。", "new", .94));
            for (int i = 1; i <= limit; i++) competition.add(hit(10 + i, 100 + i,
                    "独立来源", "条件" + i + "必须满足。", "h" + i, .8 - i * .01));
            competition.add(hit(80, 100, "空正文", "", "empty", .99));
            competition.add(hit(81, 100, "同文档重复", "发布审批由经理负责。", "old", .93));
            competition.add(hit(82, 200, "极低分", "发布审批秘密规则。", "low", .01));
            List<List<DocumentSearchHit>> fixtures = List.of(competition,
                    List.of(hit(1, 100, "长标题<&>😀".repeat(100), "正文<&>😀".repeat(4000), "a", .9),
                            hit(2, 200, "另一标题", "其他<&>😀".repeat(4000), "b", .9)),
                    List.of(hit(1, 100, "版本甲", "发布审批由经理负责。", "same", .9),
                            hit(2, 200, "版本乙", "发布审批由经理负责。", "same", .9)),
                    List.of());
            int index = 0;
            for (List<DocumentSearchHit> hits : fixtures) {
                for (var strategy : EvidenceValueExperiment.Strategy.values()) {
                    var selected = EvidenceValueExperiment.select(strategy, "发布审批由谁负责？", hits, limit);
                    List<DocumentSearchHit> reversed = new ArrayList<>(hits);
                    Collections.reverse(reversed);
                    assertThat(EvidenceValueExperiment.select(strategy, "发布审批由谁负责？", reversed, limit))
                            .containsExactlyElementsOf(selected);
                    assertThat(selected).allMatch(h -> hits.contains(h) && h.similarity() >= .15 && !h.content().isBlank());
                    assertThat(selected.stream().map(h -> h.documentId() + "/" + h.contentHash()).distinct().count())
                            .isEqualTo(selected.size());
                    // Neither method can invent a chunk outside the already isolated/scoped input.
                    assertThat(selected).allMatch(h -> h.metadata().get("projectId").equals("isolated-project"));
                    var metric = EvidenceValueExperiment.measure(strategy, "发布审批由谁负责？", hits, limit,
                            index == 0 ? List.of(new EvidenceValueExperiment.Target(new UUID(0, 100), "发布审批由经理负责。"),
                                    new EvidenceValueExperiment.Target(new UUID(0, 100), "发布审批由委员会负责。")) : List.of());
                    rows.add(Map.of("fixture", index, "limit", limit, "strategy", strategy, "metric", metric));
                }
                index++;
            }
        }
        Path path = Path.of("target/retrieval-baseline/p4-rag-quality-03/boundaries.json");
        Files.createDirectories(path.getParent());
        Files.writeString(path, new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(rows));
    }

    @Test
    void representativeReplacementCanLoseAnotherImportantFactAndSpareSlotPolicyFallsBackWhenFull() {
        var background = hit(1, 100, "旧要求", "经理签字依然是必需条件。", "one", .95);
        var lexical = hit(2, 100, "发布审批", "发布审批由委员会负责。", "two", .7);
        List<DocumentSearchHit> hits = List.of(background, lexical, hit(3, 200, "独立要求", "弱相关但必要的限制。", "three", .4));
        assertThat(EvidenceValueExperiment.select(EvidenceValueExperiment.Strategy.D, "发布审批由谁负责？", hits, 2))
                .contains(lexical).doesNotContain(background);
        assertThat(EvidenceValueExperiment.select(EvidenceValueExperiment.Strategy.E, "发布审批由谁负责？", hits, 2))
                .containsExactlyElementsOf(EvidenceValueExperiment.select(EvidenceValueExperiment.Strategy.A, "", hits, 2));
        // Query overlap is a heuristic, not proof that the omitted signature condition is unimportant.
    }

    @Test
    void noveltyCanPreferLexicalBackgroundOverNecessaryComplementaryEvidence() throws Exception {
        var representative = hit(1, 100, "主要求", "操作需获授权。", "one", .95);
        var necessary = hit(2, 100, "补充条件", "经理签字之后才能操作。", "two", .94);
        var distractor = hit(3, 100, "流程背景", "发布审批流程背景介绍，无强制规则。", "three", .6);
        var minority = hit(4, 200, "审计", "保留审计记录。", "four", .4);
        List<DocumentSearchHit> hits = new ArrayList<>(List.of(representative, necessary, distractor, minority));
        var targets = List.of(new EvidenceValueExperiment.Target(necessary.documentId(), necessary.content()),
                new EvidenceValueExperiment.Target(minority.documentId(), minority.content()));
        Map<String, Object> results = new LinkedHashMap<>();
        for (int limit : List.of(3, 8, 12)) {
            List<DocumentSearchHit> bounded = new ArrayList<>(hits);
            for (int i = 0; i < limit - 3; i++) bounded.add(hit(20 + i, 300 + i,
                    "独立要求", "必要审计条件" + i, "extra" + i, .3));
            Map<String, Object> comparison = new LinkedHashMap<>();
            for (var strategy : EvidenceValueExperiment.Strategy.values()) {
                comparison.put(strategy.name(), EvidenceValueExperiment.measure(strategy,
                        "发布审批流程有哪些要求？", bounded, limit, targets));
            }
            results.put(String.valueOf(limit), comparison);
            assertThat(EvidenceValueExperiment.select(EvidenceValueExperiment.Strategy.E,
                    "发布审批流程有哪些要求？", bounded, limit)).doesNotContain(necessary);
        }
        assertThat(EvidenceValueExperiment.select(EvidenceValueExperiment.Strategy.A, "", hits, 3)).contains(necessary);
        assertThat(EvidenceValueExperiment.select(EvidenceValueExperiment.Strategy.E, "发布审批流程有哪些要求？", hits, 3))
                .contains(distractor, minority).doesNotContain(necessary);
        Path path = Path.of("target/retrieval-baseline/p4-rag-quality-03/novelty-regression.json");
        Files.createDirectories(path.getParent());
        Files.writeString(path, new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(results));
    }
}
