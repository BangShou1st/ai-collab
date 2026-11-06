package com.shitulelv.aicollab.retrieval.query;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QueryRouterTest {

    private QueryRouter router;

    @BeforeEach
    void setUp() {
        router = new QueryRouter();
    }

    @Test
    @DisplayName("FAST 路由：简单直接短文本与术语定义")
    void shouldRouteFastQuery() {
        List<String> fastQueries = List.of(
                "什么是RRF",
                "pgvector",
                "MinIO默认端口",
                "JWT的全称是什么",
                "什么是向量检索"
        );

        for (String q : fastQueries) {
            QueryRouteDecision decision = router.route(q);
            assertThat(decision.complexity())
                    .as("Query '%s' should be FAST", q)
                    .isEqualTo(QueryComplexity.FAST);
            assertThat(decision.reason()).isNotEmpty();
            assertThat(decision.subQueries()).containsExactly(decision.structuredQuery().semanticQuery());
        }
    }

    @Test
    @DisplayName("FAST 路由排除：含有深度推理或复杂阐述关键词的问题不能判定为 FAST")
    void shouldDisqualifyFastQueryWhenReasoningRequired() {
        String query = "为什么使用pgvector？";
        QueryRouteDecision decision = router.route(query);

        assertThat(decision.complexity()).isNotEqualTo(QueryComplexity.FAST);
        assertThat(decision.complexity()).isEqualTo(QueryComplexity.HYBRID);
    }

    @Test
    @DisplayName("STRUCTURED 路由：显式元数据过滤条件")
    void shouldRouteStructuredQuery() {
        String query1 = "在《上线发布门禁.md》中 涉及的安全审计要求有哪些？";
        QueryRouteDecision decision1 = router.route(query1);

        assertThat(decision1.complexity()).isEqualTo(QueryComplexity.STRUCTURED);
        assertThat(decision1.structuredQuery().hasFilters()).isTrue();
        assertThat(decision1.reason()).contains("originalFilename");

        String query2 = "type:pdf 2026年终总结报告";
        QueryRouteDecision decision2 = router.route(query2);
        assertThat(decision2.complexity()).isEqualTo(QueryComplexity.STRUCTURED);
        assertThat(decision2.structuredQuery().hasFilters()).isTrue();
    }

    @Test
    @DisplayName("COMPLEX 路由：包含多个并列问题（多问号）")
    void shouldRouteComplexMultipleQuestions() {
        String complexQuery = "项目上线前有哪些门禁？回滚演练失败时能否继续发布？责任人是谁？";
        QueryRouteDecision decision = router.route(complexQuery);

        assertThat(decision.complexity()).isEqualTo(QueryComplexity.COMPLEX);
        assertThat(decision.subQueries()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(decision.subQueries()).hasSizeLessThanOrEqualTo(4);
        assertThat(decision.reason()).contains("Multiple distinct questions");
    }

    @Test
    @DisplayName("COMPLEX 路由：对比分析多主体（A和B有什么区别）")
    void shouldRouteComplexComparison() {
        String compareQuery = "PostgreSQL和MySQL有什么区别";
        QueryRouteDecision decision = router.route(compareQuery);

        assertThat(decision.complexity()).isEqualTo(QueryComplexity.COMPLEX);
        assertThat(decision.subQueries()).hasSize(3);
        assertThat(decision.subQueries().get(0)).contains("PostgreSQL");
        assertThat(decision.subQueries().get(1)).contains("MySQL");
        assertThat(decision.reason()).contains("Comparative analysis query");
    }

    @Test
    @DisplayName("HYBRID 路由：常规语义问答默认走向量+词项混合检索")
    void shouldRouteHybridStandardQuery() {
        String standardQuery = "如何配置系统权限策略并在用户角色变更时动态刷新？";
        QueryRouteDecision decision = router.route(standardQuery);

        assertThat(decision.complexity()).isEqualTo(QueryComplexity.HYBRID);
        assertThat(decision.reason()).contains("Standard semantic question");
        assertThat(decision.subQueries()).hasSize(1);
    }

    @Test
    @DisplayName("安全回退：路由失败或异常时安全回退到 HYBRID")
    void shouldFallbackSafelyToHybridOnError() {
        QueryRouter failingRouter = new QueryRouter(
                new MetadataFilterParser() {
                    @Override
                    public StructuredQuery parse(String rawQuery) {
                        throw new RuntimeException("Simulated parser failure");
                    }
                },
                new MultiQueryDecomposer()
        );

        QueryRouteDecision decision = failingRouter.route("任何用户问题");
        assertThat(decision.complexity()).isEqualTo(QueryComplexity.HYBRID);
        assertThat(decision.reason()).contains("Fallback to HYBRID");
        assertThat(decision.structuredQuery().semanticQuery()).isEqualTo("任何用户问题");
    }
}
