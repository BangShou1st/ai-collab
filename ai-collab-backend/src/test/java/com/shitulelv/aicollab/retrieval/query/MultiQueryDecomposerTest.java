package com.shitulelv.aicollab.retrieval.query;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MultiQueryDecomposerTest {

    private MultiQueryDecomposer decomposer;

    @BeforeEach
    void setUp() {
        decomposer = new MultiQueryDecomposer();
    }

    @Test
    @DisplayName("多问号复杂问题拆分为互补子查询（2~4个）并补全省略指代")
    void shouldDecomposeMultiQuestionQuery() {
        String query = "项目上线前有哪些门禁？回滚演练失败时能否继续发布？责任人是谁？";
        List<String> subQueries = decomposer.decompose(query);

        assertThat(subQueries).hasSize(3);
        assertThat(subQueries.get(0)).contains("门禁");
        assertThat(subQueries.get(1)).contains("回滚演练");
        // 省略句继承主旨主题
        assertThat(subQueries.get(2)).contains("责任人是谁");
    }

    @Test
    @DisplayName("对比型问题拆分为针对各实体的互补子查询")
    void shouldDecomposeComparativeQuery() {
        String query = "pgvector与Elasticsearch的优缺点对比";
        List<String> subQueries = decomposer.decompose(query);

        assertThat(subQueries).hasSize(3);
        assertThat(subQueries.get(0)).contains("pgvector");
        assertThat(subQueries.get(1)).contains("Elasticsearch");
        assertThat(subQueries.get(2)).contains("区别与对比");
    }

    @Test
    @DisplayName("严格有界：子查询上限最多 4 个，禁止无限分解")
    void shouldStrictlyBoundSubqueriesToMaxFour() {
        String unboundedQuery = "问题1是什么？问题2怎么做？问题3在哪看？问题4是谁负责？问题5如何排查？问题6怎么回滚？";
        List<String> subQueries = decomposer.decompose(unboundedQuery);

        assertThat(subQueries).hasSize(4);
    }

    @Test
    @DisplayName("结构化查询分解：每个子查询继承原始项目权限与文档范围约束")
    void shouldInheritMetadataFiltersAcrossDecomposedSubqueries() {
        List<MetadataFilter> originalFilters = List.of(
                MetadataFilter.eq("originalFilename", "core_arch.md"),
                MetadataFilter.eq("status", "READY")
        );
        StructuredQuery structured = new StructuredQuery(
                "上线前有哪些门禁？回滚演练失败能否发布？责任人是谁？",
                originalFilters
        );

        List<StructuredQuery> decomposed = decomposer.decomposeStructured(structured);

        assertThat(decomposed).hasSize(3);
        for (StructuredQuery sq : decomposed) {
            assertThat(sq.filters()).isEqualTo(originalFilters);
            assertThat(sq.getFiltersForField("originalFilename")).hasSize(1);
            assertThat(sq.semanticQuery()).isNotEmpty();
        }
    }

    @Test
    @DisplayName("简单单意图或无法分解时安全回退为原查询")
    void shouldFallbackToSingleOriginalQueryWhenNotDecomposable() {
        String singleQuery = "系统支持哪些文件格式解析？";
        List<String> subQueries = decomposer.decompose(singleQuery);

        assertThat(subQueries).containsExactly(singleQuery);
    }

    @Test
    @DisplayName("分解异常时安全回退为原查询列表，绝不抛出异常导致检索阻断")
    void shouldFallbackSafelyOnException() {
        MultiQueryDecomposer failingDecomposer = new MultiQueryDecomposer() {
            @Override
            public List<String> decompose(String complexQuery) {
                try {
                    throw new RuntimeException("Simulated decomposer crash");
                } catch (Exception e) {
                    return List.of(complexQuery);
                }
            }
        };

        List<String> result = failingDecomposer.decompose("复杂问题？拆分？");
        assertThat(result).containsExactly("复杂问题？拆分？");
    }
}
