package com.shitulelv.aicollab.retrieval;

import com.shitulelv.aicollab.document.application.view.DocumentSearchHit;
import com.shitulelv.aicollab.knowledge.domain.model.KnowledgeSource;
import com.shitulelv.aicollab.knowledge.domain.service.KnowledgeContextBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SourceCapStrategyExperimentParityTest {
    @Test
    void strategyAAssemblyMatchesProductionForTextEscapingTruncationAndBoundaryCases() {
        assertParity(List.of(
                hit("普通文档.md", "普通标题", "普通正文包含一条上线验收事实。", 0.95),
                hit("另一个文档.md", "回滚", "指标连续两次不达标时暂停发布并回滚。", 0.9)));

        assertParity(List.of(
                hit("a<&>.md", "章<&>😀", "正文含 <tag>、A&B、😀 和可见文本。", 0.95),
                hit("b.md", "标题", "第二条正文。", 0.9)));

        assertParity(List.of(
                hit("长文件名".repeat(100) + ".md", "长标题".repeat(120), "长标题截断后仍保留正文。", 0.95),
                hit("短文件.md", "短标题", "第二条来源。", 0.9)));

        assertParity(List.of(
                hit("长正文甲.md", "正文", "甲内容<&>😀".repeat(2_000), 0.95),
                hit("长正文乙.md", "正文", "乙内容&<😀".repeat(2_000), 0.9)));

        String filename = "边界.md";
        String heading = "预算临界";
        String prefix = "[S1]\n文件：" + filename + "\n标题：" + heading + "\n内容：";
        int prefixCodePoints = prefix.codePointCount(0, prefix.length());
        DocumentSearchHit boundary = hit(filename, heading,
                "文".repeat(SourceCapStrategyExperiment.MAX_PROMPT_SOURCE_CODE_POINTS - prefixCodePoints), 0.95);
        assertParity(List.of(boundary));
        KnowledgeContextBuilder.BuildResult production = new KnowledgeContextBuilder()
                .buildDiverseWithDiagnostics(List.of(boundary), 12);
        assertThat(codePoints(production.context().promptSources())).isEqualTo(8000);
    }

    private static void assertParity(List<DocumentSearchHit> candidates) {
        List<DocumentSearchHit> selectedByA = SourceCapStrategyExperiment.selectCurrent(candidates, 12);
        SourceCapStrategyExperiment.Assembled experiment = SourceCapStrategyExperiment.assemble(selectedByA, 12);
        var production = new KnowledgeContextBuilder().buildDiverseWithDiagnostics(candidates, 12).context();
        List<UUID> productionChunks = production.sources().stream().map(KnowledgeSource::chunkId).toList();
        java.util.Set<UUID> productionDocuments = production.sources().stream()
                .map(KnowledgeSource::documentId).collect(java.util.stream.Collectors.toSet());

        assertThat(experiment.chunkIds()).containsExactlyElementsOf(productionChunks);
        assertThat(experiment.documentIds()).isEqualTo(productionDocuments);
        assertThat(experiment.sourceCount()).isEqualTo(production.sources().size());
        assertThat(experiment.promptCodePoints()).isEqualTo(codePoints(production.promptSources()));
        assertThat(experiment.promptCodePoints()).isLessThanOrEqualTo(8000);
    }

    private static int codePoints(String value) {
        return value.codePointCount(0, value.length());
    }

    private static DocumentSearchHit hit(String filename, String heading, String content, double similarity) {
        UUID documentId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        return new DocumentSearchHit(chunkId, documentId, filename, heading, content,
                "hash-" + chunkId, similarity, Map.of());
    }
}
