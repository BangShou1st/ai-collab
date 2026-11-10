package com.shitulelv.aicollab.retrieval.query;

import com.shitulelv.aicollab.knowledge.application.service.KnowledgeConversationContext;
import com.shitulelv.aicollab.knowledge.infrastructure.entity.KnowledgeMessageEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QueryUnderstandingServiceTest {

    private QueryUnderstandingService service;

    @BeforeEach
    void setUp() {
        service = new QueryUnderstandingService();
    }

    @Test
    @DisplayName("结构化轮次管理：按 USER/ASSISTANT 完整配对并管理 Turn，不被机械切割")
    void shouldStructureMessagesIntoCompleteTurns() {
        OffsetDateTime t1 = OffsetDateTime.parse("2026-11-06T10:00:00Z");
        OffsetDateTime t2 = OffsetDateTime.parse("2026-11-06T10:00:05Z");
        OffsetDateTime t3 = OffsetDateTime.parse("2026-11-06T10:01:00Z");
        OffsetDateTime t4 = OffsetDateTime.parse("2026-11-06T10:01:10Z");

        List<ConversationMessage> messages = List.of(
                new ConversationMessage("USER", "如何部署 MinIO 服务？", t1, false),
                new ConversationMessage("ASSISTANT", "部署 MinIO 可以使用 Docker Compose [S1]。", t2, false),
                new ConversationMessage("USER", "需要开放哪些端口？", t3, false),
                new ConversationMessage("ASSISTANT", "需要开放 9000 和 9001 端口 [S2]。", t4, false)
        );

        List<ConversationTurn> turns = service.groupIntoTurns(messages);
        assertThat(turns).hasSize(2);

        ConversationTurn turn1 = turns.get(0);
        assertThat(turn1.turnIndex()).isEqualTo(1);
        assertThat(turn1.userQuestion()).isEqualTo("如何部署 MinIO 服务？");
        assertThat(turn1.assistantAnswer()).isEqualTo("部署 MinIO 可以使用 Docker Compose [S1]。");

        ConversationTurn turn2 = turns.get(1);
        assertThat(turn2.turnIndex()).isEqualTo(2);
        assertThat(turn2.userQuestion()).isEqualTo("需要开放哪些端口？");
        assertThat(turn2.assistantAnswer()).isEqualTo("需要开放 9000 和 9001 端口 [S2]。");
    }

    @Test
    @DisplayName("按轮次窗口与字符预算淘汰最旧轮次，保证轮次完整性不被截断")
    void shouldShedOldestCompleteTurnsWhenExceedingBudget() {
        List<ConversationTurn> turns = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            turns.add(new ConversationTurn(
                    i,
                    "用户问题 " + i + " " + "A".repeat(50),
                    "助手回答 " + i + " " + "B".repeat(100),
                    OffsetDateTime.now(),
                    false
            ));
        }

        // maxTurns = 3
        List<ConversationTurn> selected = service.selectTurnsWithinBudget(turns, 3, 2000);
        assertThat(selected).hasSize(3);
        assertThat(selected.get(0).turnIndex()).isEqualTo(4);
        assertThat(selected.get(2).turnIndex()).isEqualTo(6);
    }

    @Test
    @DisplayName("意图识别：准确识别独立新问题 (STANDALONE)")
    void shouldDetectStandaloneIntent() {
        List<ConversationTurn> turns = List.of(
                new ConversationTurn(1, "PostgreSQL 的端口是多少？", "默认端口是 5432。", OffsetDateTime.now(), false)
        );

        QueryIntentType intent = service.detectIntent(turns, "MinIO 对象的生命周期如何配置？");
        assertThat(intent).isEqualTo(QueryIntentType.STANDALONE);
    }

    @Test
    @DisplayName("意图识别：准确识别追问指代与省略问题 (FOLLOW_UP)")
    void shouldDetectFollowUpIntent() {
        List<ConversationTurn> turns = List.of(
                new ConversationTurn(1, "如何配置系统的安全认证机制？", "系统使用 OAuth2 Resource Server [S1]。", OffsetDateTime.now(), false)
        );

        // 省略问题
        assertThat(service.detectIntent(turns, "为什么？")).isEqualTo(QueryIntentType.FOLLOW_UP);
        assertThat(service.detectIntent(turns, "具体怎么做？")).isEqualTo(QueryIntentType.FOLLOW_UP);
        assertThat(service.detectIntent(turns, "代码怎么写？")).isEqualTo(QueryIntentType.FOLLOW_UP);
        assertThat(service.detectIntent(turns, "核心指标呢？")).isEqualTo(QueryIntentType.FOLLOW_UP);

        // 代词与指代词
        assertThat(service.detectIntent(turns, "它支持哪些算法？")).isEqualTo(QueryIntentType.FOLLOW_UP);
        assertThat(service.detectIntent(turns, "该系统的 Token 过期时间是多久？")).isEqualTo(QueryIntentType.FOLLOW_UP);
        assertThat(service.detectIntent(turns, "上述方案在集群环境下如何同步？")).isEqualTo(QueryIntentType.FOLLOW_UP);
    }

    @Test
    @DisplayName("意图识别：准确识别明确的话题切换 (TOPIC_SWITCH)")
    void shouldDetectTopicSwitchIntent() {
        List<ConversationTurn> turns = List.of(
                new ConversationTurn(1, "PostgreSQL 向量插件配置", "需要安装 pgvector 扩展。", OffsetDateTime.now(), false)
        );

        assertThat(service.detectIntent(turns, "换个话题，前端路由守卫怎么配置？")).isEqualTo(QueryIntentType.TOPIC_SWITCH);
        assertThat(service.detectIntent(turns, "不谈这个了，重新问下 Docker 启动命令")).isEqualTo(QueryIntentType.TOPIC_SWITCH);
        assertThat(service.detectIntent(turns, "switch topic, how to run tests?")).isEqualTo(QueryIntentType.TOPIC_SWITCH);
    }

    @Test
    @DisplayName("查询改写：追问场景必须保留原用户问题并拼接上下文主旨")
    void shouldRewriteFollowUpWhilePreservingVerbatimQuestion() {
        List<ConversationTurn> turns = List.of(
                new ConversationTurn(1, "如何配置系统的 JWT 鉴权？", "在 SecurityConfig 中配置 Bearer Token 过滤器 [S1]。", OffsetDateTime.now(), false)
        );

        String currentQuestion = "具体实现代码怎么写？";
        QueryUnderstandingResult result = service.understand(
                List.of(
                        new ConversationMessage("USER", "如何配置系统的 JWT 鉴权？"),
                        new ConversationMessage("ASSISTANT", "在 SecurityConfig 中配置 Bearer Token 过滤器 [S1]。")
                ),
                currentQuestion
        );

        assertThat(result.intentType()).isEqualTo(QueryIntentType.FOLLOW_UP);
        assertThat(result.originalQuestion()).isEqualTo(currentQuestion);
        // 检索改写包含上一轮主旨，且完整保留原用户问题
        assertThat(result.rewrittenQuery()).contains("如何配置系统的 JWT 鉴权？");
        assertThat(result.rewrittenQuery()).contains("具体实现代码怎么写？");
    }

    @Test
    @DisplayName("查询改写：独立问题与话题切换保持原问题，不拼接历史")
    void shouldNotRewriteStandaloneOrTopicSwitch() {
        List<ConversationMessage> messages = List.of(
                new ConversationMessage("USER", "MinIO 是怎么配置的？"),
                new ConversationMessage("ASSISTANT", "通过 MinioConfig 进行注入。")
        );

        QueryUnderstandingResult standaloneResult = service.understand(messages, "系统使用的是哪种密码哈希算法？");
        assertThat(standaloneResult.intentType()).isEqualTo(QueryIntentType.STANDALONE);
        assertThat(standaloneResult.rewrittenQuery()).isEqualTo("系统使用的是哪种密码哈希算法？");

        QueryUnderstandingResult switchResult = service.understand(messages, "换个话题，任务规划的提示词在哪？");
        assertThat(switchResult.intentType()).isEqualTo(QueryIntentType.TOPIC_SWITCH);
        assertThat(switchResult.rewrittenQuery()).isEqualTo("换个话题，任务规划的提示词在哪？");
    }

    @Test
    @DisplayName("证据隔离红线：历史 Assistant 答案及其引用 [S1], [S2] 绝对不能作为本轮证据")
    void shouldStrictlyIsolateAssistantAnswersAndCitationsFromEvidence() {
        List<ConversationTurn> turns = List.of(
                new ConversationTurn(1, "什么是 pgvector？", "pgvector 是 PG 的向量扩展插件 [S1]，支持 HNSW 索引 [S2]。", OffsetDateTime.now(), false)
        );

        String prompt = service.buildContextPrompt(turns);

        // 包含严格隔离声明
        assertThat(prompt).contains("【多轮对话上下文与证据隔离规则】");
        assertThat(prompt).contains("绝对不能作为本轮回答的事实证据");
        assertThat(prompt).contains("严禁复用历史引用");

        // 历史 Assistant 标记明确提示非本轮证据
        assertThat(prompt).contains("ASSISTANT (仅供指代参考，非本轮证据)");

        // 历史回答中的引用标记 [S1], [S2] 必须被剥离，防止模型混淆本轮证据编号
        assertThat(prompt).doesNotContain("[S1]");
        assertThat(prompt).doesNotContain("[S2]");
    }

    @Test
    @DisplayName("KnowledgeConversationContext 适配器兼容性验证")
    void shouldIntegrateWithKnowledgeConversationContextSeamlessly() {
        KnowledgeMessageEntity userMsg = new KnowledgeMessageEntity();
        userMsg.setRole("USER");
        userMsg.setContent("系统中的 RRF 算法权重是多少？");
        userMsg.setCreatedAt(OffsetDateTime.now().minusSeconds(10));

        KnowledgeMessageEntity assistantMsg = new KnowledgeMessageEntity();
        assistantMsg.setRole("ASSISTANT");
        assistantMsg.setContent("Dense 权重是 0.7，Lexical 是 0.3 [S1]。");
        assistantMsg.setCreatedAt(OffsetDateTime.now().minusSeconds(5));

        KnowledgeConversationContext context = KnowledgeConversationContext.from(
                List.of(userMsg, assistantMsg),
                "如何调整这些权重？"
        );

        assertThat(context.retrievalQuery()).contains("系统中的 RRF 算法权重是多少？");
        assertThat(context.retrievalQuery()).contains("如何调整这些权重？");
        assertThat(context.prompt()).contains("【多轮对话上下文与证据隔离规则】");
        assertThat(context.prompt()).doesNotContain("[S1]");
    }
}
