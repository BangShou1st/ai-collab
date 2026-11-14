package com.shitulelv.aicollab.common.ai.token;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 统一文本 → token 计量的精确样本回归（J0-1）。
 *
 * <p>公式固定为：ASCII 可打印/空白 0.25、CJK 统一表意文字（含扩展 A/B）0.75、
 * 其余 codepoint 0.5，累加后 {@link Math#round(double)} 四舍五入。本测试把关键
 * 场景钉成精确数值，防止系数、字符分类或取整方式被无意改动。</p>
 */
class TokenEstimatorSamplesTest {

    @Test
    void pureChineseSampleIsPinnedExactly() {
        // 8 个 CJK 统一表意文字 × 0.75 = 6.0
        assertThat(TokenEstimator.estimate("人工智能协作平台")).isEqualTo(6);
    }

    @Test
    void pureEnglishSampleIsPinnedExactly() {
        // 43 个 ASCII 字符 × 0.25 = 10.75 → 四舍五入 11
        assertThat(TokenEstimator.estimate("The quick brown fox jumps over the lazy dog")).isEqualTo(11);
    }

    @Test
    void mixedChineseEnglishSampleIsPinnedExactly() {
        String mixed = "在 Spring Boot 中使用 pgvector 做向量检索";
        // 9 个 CJK × 0.75 = 6.75；23 个 ASCII × 0.25 = 5.75；合计 12.5 → 13
        assertThat(TokenEstimator.estimate(mixed)).isEqualTo(13);
    }

    @Test
    void toolSchemaJsonSampleIsPinnedExactly() {
        String schema = "{\"name\":\"search_project_knowledge\",\"parameters\":{\"type\":\"object\","
                + "\"properties\":{\"query\":{\"type\":\"string\"}}}}";
        // 107 个 ASCII 字符 × 0.25 = 26.75 → 27
        assertThat(TokenEstimator.estimate(schema)).isEqualTo(27);
    }

    @Test
    void veryLongTextSampleIsPinnedExactly() {
        String longText = "长文本".repeat(4000);
        assertThat(longText.length()).isGreaterThanOrEqualTo(10_000);
        // 12000 个 CJK 字符 × 0.75 = 9000
        assertThat(TokenEstimator.estimate(longText)).isEqualTo(9000);
    }

    @Test
    void nullAndEmptyReturnZero() {
        assertThat(TokenEstimator.estimate((String) null)).isZero();
        assertThat(TokenEstimator.estimate("")).isZero();
        assertThat(TokenEstimator.estimate((Iterable<String>) null)).isZero();
        assertThat(TokenEstimator.estimate(List.of())).isZero();
    }

    @Test
    void emojiAndCjkExtensionArePinnedExactly() {
        // 😀 = other 0.5；㐀 = Ext A 0.75；𠀀 = Ext B（增补平面）0.75；合计 2.0 → 2
        assertThat(TokenEstimator.estimate("😀㐀𠀀")).isEqualTo(2);
        // 4 个扩展 A 区字符 × 0.75 = 3
        assertThat(TokenEstimator.estimate("㐀㐁㐂㐃")).isEqualTo(3);
    }

    @Test
    void iterableSumsEachText() {
        assertThat(TokenEstimator.estimate(List.of("人工智能", "abc", ""))).isEqualTo(3 + 1);
    }

    @Test
    void emptyTokenCountIsNeutralInAddition() {
        TokenCount measured = TokenCount.estimate(5, "CJK_WEIGHTED_V1");
        TokenCount empty = TokenCount.zero();

        TokenCount right = measured.add(empty);
        assertThat(right.count()).isEqualTo(5);
        assertThat(right.source()).isEqualTo(TokenCountSource.ESTIMATE);
        assertThat(right.estimated()).isTrue();

        TokenCount left = empty.add(measured);
        assertThat(left.count()).isEqualTo(5);
        assertThat(left.source()).isEqualTo(TokenCountSource.ESTIMATE);
        assertThat(left.estimated()).isTrue();

        TokenCount bothEmpty = empty.add(TokenCount.zero());
        assertThat(bothEmpty.count()).isZero();
        assertThat(bothEmpty.source()).isEqualTo(TokenCountSource.UNKNOWN);
        assertThat(bothEmpty.estimated()).isTrue();
    }

    @Test
    void tokenCountSourceMergingIsConservative() {
        TokenCount provider = TokenCount.provider(10, "USAGE");
        TokenCount tokenizer = TokenCount.tokenizer(10, "REAL_BPE");
        TokenCount estimate = TokenCount.estimate(10, "CJK_WEIGHTED_V1");
        TokenCount unknown = TokenCount.unknown(0, "NO_EVIDENCE");

        assertThat(provider.add(provider).source()).isEqualTo(TokenCountSource.PROVIDER);
        assertThat(tokenizer.add(tokenizer).source()).isEqualTo(TokenCountSource.TOKENIZER);
        assertThat(provider.add(estimate).source()).isEqualTo(TokenCountSource.ESTIMATE);
        assertThat(tokenizer.add(estimate).source()).isEqualTo(TokenCountSource.ESTIMATE);
        assertThat(provider.add(unknown).source()).isEqualTo(TokenCountSource.UNKNOWN);
        assertThat(provider.add(estimate).estimated()).isTrue();
        assertThat(provider.add(provider).estimated()).isFalse();
    }
}
