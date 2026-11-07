package com.shitulelv.aicollab.retrieval.query;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 阶段 3｜复杂度 × 检索策略分离的映射契约。
 * 规则为语言无关的结构特征（长度/问号/连接词/精确标识符模式），无中文短语硬编码路由主逻辑。
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
    void shortEnglishQuery_mappingFollowsExistingFastSemantics() {
        // 超短英文词元（≤8 code points）走 FAST；带空格的英文问句按既有语义归 HYBRID，
        // 且 JWT 命中精确标识符特征 → 词法通道保留
        assertThat(router.route("JWT").strategy()).isEqualTo(RetrievalStrategy.DENSE_WITH_BOUNDED_FALLBACK);
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
    void plainSemanticQuery_mapsToHybrid() {
        QueryRouteDecision decision = router.route("解释一下混合检索中倒数排名融合的数学原理与工程取舍");
        assertThat(decision.complexity()).isEqualTo(QueryComplexity.HYBRID);
        assertThat(decision.strategy()).isEqualTo(RetrievalStrategy.HYBRID);
    }

    @Test
    void blankAndNull_safelyDefaultToHybrid() {
        assertThat(router.route("").strategy()).isEqualTo(RetrievalStrategy.HYBRID);
        assertThat(router.route(null).strategy()).isEqualTo(RetrievalStrategy.HYBRID);
    }

    @Test
    void defaultStrategyMapping_isTotal() {
        for (QueryComplexity complexity : QueryComplexity.values()) {
            assertThat(QueryRouteDecision.defaultStrategy(complexity)).isNotNull();
        }
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
