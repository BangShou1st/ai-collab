package com.shitulelv.aicollab.retrieval.query;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 复杂度 × 检索策略分离的映射契约（RAG_V2_ROUTING_CLOSURE-01 任务一更新）。
 * 规则为语言无关的结构特征（长度/问号/连接词/精确标识符模式），无中文短语硬编码路由主逻辑。
 *
 * <p>本收尾阶段的两处边界修正：</p>
 * <ul>
 *   <li>精确标识符检测<b>先于</b> FAST：短精确标识符（如 JWT）不再被 Dense 快速路径截获；</li>
 *   <li>普通单一意图语义问题默认 DENSE_WITH_BOUNDED_FALLBACK（Dense 优先 + 有界证据回退），
 *       跳过不必要的词法检索；STRUCTURED/COMPLEX 与安全兜底保持原策略。</li>
 * </ul>
 */
class QueryRouterStrategyMappingTest {

    private final QueryRouter router = new QueryRouter();

    @Test
    void fastQuery_mapsToDenseWithBoundedFallback() {
        QueryRouteDecision decision = router.route("pgvector是什么");
        assertThat(decision.complexity()).isEqualTo(QueryComplexity.FAST);
        assertThat(decision.strategy()).isEqualTo(RetrievalStrategy.DENSE_WITH_BOUNDED_FALLBACK);
    }

    @Test
    void shortExactIdentifier_notInterceptedByFastPath() {
        // 任务一问题 A：短精确标识符不再被 FAST 提前截获 → HYBRID（词法精确匹配保留）
        QueryRouteDecision decision = router.route("JWT");
        assertThat(decision.complexity()).isEqualTo(QueryComplexity.HYBRID);
        assertThat(decision.strategy()).isEqualTo(RetrievalStrategy.HYBRID);
        assertThat(decision.reason()).contains("identifier");

        // 标识符优先于 FAST 同样适用于 FAST 形态的定义型短问句
        assertThat(router.route("什么是RRF").strategy()).isEqualTo(RetrievalStrategy.HYBRID);
        assertThat(router.route("JWT的全称是什么").strategy()).isEqualTo(RetrievalStrategy.HYBRID);
    }

    @Test
    void nonIdentifierShortQueries_stayFast() {
        // 混合大小写代号（MinIO）与小写术语（pgvector）不构成精确标识符特征，保持 FAST
        assertThat(router.route("MinIO默认端口").complexity()).isEqualTo(QueryComplexity.FAST);
        assertThat(router.route("pgvector").complexity()).isEqualTo(QueryComplexity.FAST);
    }

    @Test
    void englishQueryWithIdentifier_keepsHybrid() {
        QueryRouteDecision decision = router.route("what is JWT");
        assertThat(decision.complexity()).isEqualTo(QueryComplexity.HYBRID);
        assertThat(decision.strategy()).isEqualTo(RetrievalStrategy.HYBRID);
        assertThat(decision.reason()).contains("identifier");
    }

    @Test
    void metadataFilterQuery_mapsToHybrid() {
        QueryRouteDecision decision = router.route("查一下 type:任务 状态为 OPEN 的条目");
        assertThat(decision.complexity()).isEqualTo(QueryComplexity.STRUCTURED);
        assertThat(decision.strategy()).isEqualTo(RetrievalStrategy.HYBRID);
    }

    @Test
    void complexComparativeQuery_mapsToMultiQueryHybrid() {
        QueryRouteDecision decision = router.route("Redis和Memcached的区别是什么？各自适用什么场景？");
        assertThat(decision.complexity()).isEqualTo(QueryComplexity.COMPLEX);
        assertThat(decision.strategy()).isEqualTo(RetrievalStrategy.MULTI_QUERY_HYBRID);
        assertThat(decision.subQueries().size()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void exactIdentifierInNormalQuery_keepsHybridWithEvidence() {
        // 错误码 / 版本号 / 文件名：词法精确匹配有真实收益 → HYBRID（语言无关结构特征）
        for (String query : new String[]{
                "部署时出现 ERR-503 应该怎么排查",
                "如何升级到 2.10.0 版本",
                "application.yml 里怎么配置连接池",
                "JWT token 过期时间在哪里配置"}) {
            QueryRouteDecision decision = router.route(query);
            assertThat(decision.strategy()).as("query=%s", query).isEqualTo(RetrievalStrategy.HYBRID);
            assertThat(decision.reason()).as("query=%s", query).contains("identifier");
        }
    }

    @Test
    void plainSemanticQuery_denseFirstWithBoundedFallback() {
        // 任务一问题 B：普通单一意图语义问题默认 Dense 优先 + 有界回退，跳过不必要的词法检索；
        // 复杂度语义仍是 HYBRID（非 FAST 短查询），仅执行策略变化
        QueryRouteDecision decision = router.route("解释一下混合检索中倒数排名融合的数学原理与工程取舍");
        assertThat(decision.complexity()).isEqualTo(QueryComplexity.HYBRID);
        assertThat(decision.strategy()).isEqualTo(RetrievalStrategy.DENSE_WITH_BOUNDED_FALLBACK);
        assertThat(decision.reason()).contains("dense-first");

        // 英文普通语义问句同样生效（语言无关）
        QueryRouteDecision english = router.route("explain how the citation validator works");
        assertThat(english.strategy()).isEqualTo(RetrievalStrategy.DENSE_WITH_BOUNDED_FALLBACK);
    }

    @Test
    void reasoningDisqualifiedQuery_defaultsToDenseFirst() {
        // 含深度推理标记的问句不是 FAST，但单一意图 → 默认 Dense 优先 + 有界回退
        QueryRouteDecision decision = router.route("为什么使用pgvector？");
        assertThat(decision.complexity()).isEqualTo(QueryComplexity.HYBRID);
        assertThat(decision.strategy()).isEqualTo(RetrievalStrategy.DENSE_WITH_BOUNDED_FALLBACK);
    }

    @Test
    void blankAndNull_safelyDefaultToHybrid() {
        // 空查询/异常兜底保持保守 HYBRID 完整管线
        assertThat(router.route("").strategy()).isEqualTo(RetrievalStrategy.HYBRID);
        assertThat(router.route(null).strategy()).isEqualTo(RetrievalStrategy.HYBRID);
    }

    @Test
    void routingError_reasonContainsNoExceptionMessage() {
        // 任务三：路由异常兜底的 reason 不携带异常消息（可能内嵌用户输入片段）
        QueryRouter failingRouter = new QueryRouter(
                new MetadataFilterParser() {
                    @Override
                    public StructuredQuery parse(String rawQuery) {
                        throw new RuntimeException("leaked-input fragment");
                    }
                },
                new MultiQueryDecomposer());
        QueryRouteDecision decision = failingRouter.route("包含敏感词的问题");
        assertThat(decision.reason()).contains("Fallback to HYBRID");
        assertThat(decision.reason()).doesNotContain("leaked-input");
        assertThat(decision.reason()).contains("RuntimeException");
    }

    @Test
    void defaultStrategyMapping_isTotal() {
        for (QueryComplexity complexity : QueryComplexity.values()) {
            assertThat(QueryRouteDecision.defaultStrategy(complexity)).isNotNull();
        }
        // HYBRID 复杂度默认执行策略与路由器一致：Dense 优先 + 有界回退
        assertThat(QueryRouteDecision.defaultStrategy(QueryComplexity.HYBRID))
                .isEqualTo(RetrievalStrategy.DENSE_WITH_BOUNDED_FALLBACK);
    }

    @Test
    void exactIdentifierDetector_isLanguageNeutral() {
        assertThat(QueryRouter.containsExactIdentifier("ERR-503")).isTrue();
        assertThat(QueryRouter.containsExactIdentifier("v1.2.3")).isTrue();
        assertThat(QueryRouter.containsExactIdentifier("README.md")).isTrue();
        assertThat(QueryRouter.containsExactIdentifier("JWT")).isTrue();
        assertThat(QueryRouter.containsExactIdentifier("为什么会这样")).isFalse();
        assertThat(QueryRouter.containsExactIdentifier("explain the architecture")).isFalse();
    }
}
