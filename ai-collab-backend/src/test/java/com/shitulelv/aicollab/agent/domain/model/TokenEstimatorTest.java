package com.shitulelv.aicollab.agent.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TokenEstimator 样本回归测试（设计文档 §4.3：估算偏差需被钉住）。
 *
 * <p>样本覆盖纯中文/纯英文/中英混合/emoji/代码片段等 20+ 组，
 * 断言估算值落在合理区间：纯中文每字 0.6-0.9 token，纯英文每 3-5 字符 1 token；
 * 并对照旧估算（chars/3）验证中文场景的低估已被修正。</p>
 */
class TokenEstimatorTest {

    // ========== 空输入与 null ==========

    @Test
    void nullReturnsZero() {
        assertThat(TokenEstimator.estimate(null)).isZero();
    }

    @Test
    void emptyReturnsZero() {
        assertThat(TokenEstimator.estimate("")).isZero();
    }

    // ========== 纯中文：每字 0.6-0.9 token ==========

    @Test
    void pureChineseSamples() {
        // 长度 >= 12 的样本才套用严格区间（短样本四舍五入会放大偏差）
        assertPureChinese("人工智能协作平台为高校竞赛团队与软件课程项目提供任务看板");
        assertPureChinese("预算治理引入软阈值分级收敛机制避免一次性耗尽全部额度");
        assertPureChinese("数据库迁移必须保证版本号连续递增并且不得修改历史文件");
        assertPureChinese("检索增强生成先把文档分块再向量化最后按引用作答");
        assertPureChinese("系统在运行租约过期后按存活子运行重算待完成计数");
        assertPureChinese("今天天气很好");
    }

    private void assertPureChinese(String text) {
        int chars = text.codePointCount(0, text.length());
        int tokens = TokenEstimator.estimate(text);
        assertThat(tokens)
                .as("纯中文每字 0.6-0.9 token：%s", text)
                .isBetween((int) Math.floor(chars * 0.6), (int) Math.ceil(chars * 0.9));
    }

    // ========== 纯英文：每 3-5 字符 1 token ==========

    @Test
    void pureEnglishSamples() {
        assertPureAscii("The quick brown fox jumps over the lazy dog near the river bank");
        assertPureAscii("Agent budget governance introduces soft thresholds for convergence");
        assertPureAscii("Spring Boot MyBatis pgvector Redis MinIO Docker Compose Flyway");
        assertPureAscii("This is a plain english sentence used for token estimation checks");
        assertPureAscii("abcdefghijklmnopqrstuvwxyz");
    }

    private void assertPureAscii(String text) {
        int len = text.length();
        int tokens = TokenEstimator.estimate(text);
        assertThat(tokens)
                .as("纯英文每 3-5 字符 1 token：%s", text)
                .isBetween((int) Math.ceil(len / 5.0), (int) Math.floor(len / 3.0));
    }

    // ========== 中英混合 ==========

    @Test
    void mixedSamplesAreReasonable() {
        // 估算应介于"纯 ASCII 下限"与"纯 CJK 上限"之间，且每字符 0.25-1.0 token
        assertMixed("在 Spring Boot 中使用 pgvector 做向量检索");
        assertMixed("调用 POST /api/agent/runs 创建运行并等待 SSE 事件流");
        assertMixed("Flyway 迁移 V81 起新增 agent_delegation_ledger 台账表");
        assertMixed("运行租约 AgentLeaseRenewer 每 10 秒续期一次");
        assertMixed("错误码 AI_PROVIDER_OUTPUT_TRUNCATED 表示模型输出被截断");
        assertMixed("pnpm test 覆盖 487 个前端用例，后端 1600+ 测试用例");
        assertMixed("用户 32962 在 2026-11-08 提交了 PR #1234");
    }

    private void assertMixed(String text) {
        int chars = text.codePointCount(0, text.length());
        int tokens = TokenEstimator.estimate(text);
        assertThat(tokens)
                .as("混合样本每字符 0.25-1.0 token：%s", text)
                .isBetween((int) Math.floor(chars * 0.25), (int) Math.ceil(chars * 1.0));
        assertThat(tokens).as("含 CJK 的非空样本必须为正：%s", text).isPositive();
    }

    // ========== emoji / 增补平面 ==========

    @Test
    void emojiCountsAsOther() {
        // 每个 emoji（含变体选择符）按 other 分类逐 codepoint 计 0.5
        assertThat(TokenEstimator.estimate("😀😀😀😀")).isEqualTo(2);
        // 与 ASCII 混排
        assertThat(TokenEstimator.estimate("ok😀")).isEqualTo(1); // 0.5 + 0.5
    }

    @Test
    void cjkExtensionAAndBCountAsCjk() {
        // 扩展 A 区（U+3400）与扩展 B 区（U+20000，增补平面代理对）都按 CJK 计 0.75
        assertThat(TokenEstimator.estimate("㐀㐁㐂㐃")).isEqualTo(3);   // 4×0.75
        assertThat(TokenEstimator.estimate("𠀀𠀁𠀂𠀃")).isEqualTo(3); // 代理对各 0.75
    }

    @Test
    void cjkPunctuationIsOtherNotCjk() {
        // CJK 符号与标点区（，。：）不属于统一表意文字区，按 other 计 0.5
        assertThat(TokenEstimator.estimate("，，，，")).isEqualTo(2); // 4×0.5
    }

    // ========== 代码片段与空白 ==========

    @Test
    void codeSnippetBehavesLikeAscii() {
        String code = "if (run.depth() == 0) { return requeueRun(run); } // requeue";
        assertPureAscii(code);
        String sql = "SELECT id FROM agent_run WHERE run_id = ? AND status <> 'DELETING';";
        assertPureAscii(sql);
    }

    @Test
    void whitespaceOnlyIsPositiveButSmall() {
        String spaces = " ".repeat(40);
        // 纯空白按 ASCII 计：40×0.25 = 10
        assertThat(TokenEstimator.estimate(spaces)).isEqualTo(10);
    }

    @Test
    void newlineAndTabsCountAsAscii() {
        assertThat(TokenEstimator.estimate("\n\n\n\n")).isEqualTo(1); // 4×0.25
    }

    // ========== 相对旧估算的修正方向 ==========

    @Test
    void chineseUnderestimateOfLegacyFormulaIsFixed() {
        String cn = "人工智能协作平台为高校竞赛团队与软件课程项目提供任务看板里程碑与成员负载管理能力";
        int legacy = Math.max(1, (cn.length() + 2) / 3);
        assertThat(TokenEstimator.estimate(cn)).as("中文场景新估算必须显著高于旧 chars/3")
                .isGreaterThan(legacy);
    }

    @Test
    void estimateIsDeterministicAndMonotonicInLength() {
        String unit = "预算收敛";
        int one = TokenEstimator.estimate(unit);
        assertThat(TokenEstimator.estimate(unit + unit)).isEqualTo(one * 2);
        assertThat(TokenEstimator.estimate(unit + unit + unit)).isEqualTo(one * 3);
    }

    // ========== 短样本：不爆炸、不越过合理上界 ==========

    @Test
    void shortSamplesAreBounded() {
        for (String sample : new String[]{
                "a", "中", "ok", "你好", "\t", "é", "ü", "Ω", "½", "→",
                "!!", "。。", "the", "引号\"x\"", "JSON:{\"k\":1}", "$99.99"}) {
            // 极短纯 ASCII 样本四舍五入后可能为 0（调用方按 max(1, ...) 保底），只验上界
            assertThat(TokenEstimator.estimate(sample))
                    .as("样本 %s", sample).isBetween(0, sample.length() + 1);
        }
        // 含 CJK/emoji 的短样本必须至少估出 1
        for (String sample : new String[]{"中", "你好", "。。", "引号\"x\"", "é"}) {
            assertThat(TokenEstimator.estimate(sample)).as("样本 %s", sample).isPositive();
        }
    }
}
