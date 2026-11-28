package com.shitulelv.aicollab.knowledge;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P3 契约 §7 删除后的 grep 断言：{@code src/main} 不再出现迁走类与问答链路的 Java 痕迹。
 *
 * <p>与 P2 §8 同风格。两个已知保留项不在断言之列：{@code RetrievalStrategy} 名称仍是
 * {@code /v1/knowledge/search} 协议字段（门面 Result 类型使用）；{@code MetadataFilter}/
 * {@code DocumentFilterCriteria} 仍被门面与可见集合计算使用。{@code KnowledgeContextBuilder}
 * / {@code CrossDocumentSourceSelector} 为 Agent 工具（P4 前不动）保留，QA 路径不再调用。</p>
 */
class P3DeletionProofTest {

    private static final Pattern JAVA = Pattern.compile(".*\\.java");

    private static final List<String> FORBIDDEN_SYMBOLS = List.of(
            "QueryRouter",
            "QueryPlanGatewayClient",
            "query.plan.enabled",
            "KnowledgeOutputSafetyGuard",
            "KnowledgePromptAssembler",
            "KnowledgeConversationContext",
            "KnowledgeQuestionApplicationService",
            "KnowledgeStreamQuestionService");

    @Test
    void migratedClassesAreGoneFromMainSourceTree() throws IOException {
        List<String> fileNames = srcMainFiles().stream()
                .map(path -> path.getFileName().toString()).toList();
        assertThat(fileNames).noneMatch(name -> List.of(
                "QueryRouter.java",
                "QueryUnderstandingService.java",
                "MultiQueryDecomposer.java",
                "MetadataFilterParser.java",
                "StructuredQuery.java",
                "QueryRouteDecision.java",
                "QueryComplexity.java",
                "QueryIntentType.java",
                "QueryUnderstandingResult.java",
                "ConversationMessage.java",
                "ConversationTurn.java",
                "QueryPlanGatewayClient.java",
                "QueryPlanProperties.java",
                "QueryPlanRequest.java",
                "QueryPlanResponse.java",
                "QueryPlanConfiguration.java",
                "QueryPlanGatewayException.java",
                "KnowledgeQuestionApplicationService.java",
                "KnowledgeStreamQuestionService.java",
                "KnowledgeConversationContext.java",
                "KnowledgePromptAssembler.java",
                "KnowledgeOutputSafetyGuard.java",
                "KnowledgeCitationValidator.java",
                "FactEntityCoverageChecker.java",
                "ValidatedKnowledgeAnswer.java").contains(name));
    }

    @Test
    void deletedSymbolsAndConfigNoLongerAppearInMainSources() throws IOException {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Path path : srcMainFiles()) {
            String text = Files.readString(path);
            for (String symbol : FORBIDDEN_SYMBOLS) {
                if (text.contains(symbol)) {
                    counts.merge(symbol, 1L, Long::sum);
                }
            }
        }
        assertThat(counts).allSatisfy((symbol, count) -> assertThat(count)
                .as("symbol %s still referenced in src/main", symbol).isZero());
    }

    /**
     * {@code ModelPurpose.KNOWLEDGE_CHAT} 在网关/能力探测/租借路由层（{@code infrastructure/ai}）
     * 按契约保留（“网关本体保留（Agent/规划/能力探测仍用，P4/P5 处理）”）；问答调用点已删，
     * knowledge/retrieval 模块不得再引用它。
     */
    @Test
    void knowledgeModuleNoLongerReferencesKnowledgeChatPurpose() throws IOException {
        List<Path> scoped = srcMainFiles().stream()
                .filter(path -> path.toString().contains(
                        java.nio.file.Path.of("knowledge").toString()
                                + "") || path.toString().contains("retrieval"))
                .toList();
        assertThat(scoped).allSatisfy(path -> {
            try {
                assertThat(Files.readString(path))
                        .as("file %s", path)
                        .doesNotContain("ModelPurpose.KNOWLEDGE_CHAT")
                        .doesNotContain("completeStream");
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
        });
    }

    private List<Path> srcMainFiles() throws IOException {
        Path root = Path.of("src", "main", "java");
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(path -> JAVA.matcher(path.toString()).matches()).toList();
        }
    }
}
