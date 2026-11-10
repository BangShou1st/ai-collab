package com.shitulelv.aicollab.knowledge.domain.service;

import com.shitulelv.aicollab.knowledge.domain.model.KnowledgeSource;
import com.shitulelv.aicollab.knowledge.domain.model.ValidatedKnowledgeAnswer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FactEntityCoverageCheckerTest {

    private final FactEntityCoverageChecker checker = new FactEntityCoverageChecker();
    private final KnowledgeCitationValidator validator = new KnowledgeCitationValidator(checker);

    private KnowledgeSource source(int rank, String heading, String content) {
        return new KnowledgeSource(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "deploy-guide.md",
                heading,
                content,
                "hash_" + rank,
                0.9,
                rank
        );
    }

    @Test
    void detectsUnsupportedNumericClaims() {
        KnowledgeSource s1 = source(1, "端口配置", "系统服务部署在 8080 端口，最大连接数为 500。");

        // 回答中捏造了未在资料中出现的数值 9999
        String answer = "系统服务监听端口为 9999 [S1]。";

        List<String> warnings = checker.check(answer, List.of(s1));
        assertThat(warnings).isNotEmpty();
        assertThat(warnings.getFirst()).contains("9999");
    }

    @Test
    void detectsUnsupportedQuotedSpecialEntities() {
        KnowledgeSource s1 = source(1, "安全要求", "所有接口调用需经网关鉴权。");

        // 强行声称某特定外部机制
        String answer = "鉴权采用 “UltraSecretKeyVault” 模块完成 [S1]。";

        List<String> warnings = checker.check(answer, List.of(s1));
        assertThat(warnings).isNotEmpty();
        assertThat(warnings.getFirst()).contains("ultrasecretkeyvault");
    }

    @Test
    void supportedClaimsPassWithoutWarnings() {
        KnowledgeSource s1 = source(1, "端口配置", "系统服务部署在 8080 端口，最大连接数为 500。");

        String answer = "系统服务部署在 8080 端口 [S1]。";

        List<String> warnings = checker.check(answer, List.of(s1));
        assertThat(warnings).isEmpty();
    }

    @Test
    void citationValidatorDetectsShortSourceContentWarning() {
        // s1 正文只有 5 个字符，不足 20 字符阈值
        KnowledgeSource shortSource = source(1, "摘要", "内容过短");
        String answer = "这是依据 [S1] 得出的结论。";

        ValidatedKnowledgeAnswer validated = validator.validate(answer, List.of(shortSource));

        assertThat(validated.insufficientEvidence()).isFalse();
        assertThat(validated.unsupportedClaims()).isNotEmpty();
        assertThat(validated.unsupportedClaims().getFirst()).contains("正文内容过短截断");
    }

    @Test
    void citationValidatorIntegratesCoverageCheckerWarnings() {
        KnowledgeSource s1 = source(1, "架构规范", "系统采用微服务架构设计，前后端分离，数据持久化至 PostgreSQL。");

        // 包含捏造的数字 100000
        String answer = "数据并发承载量必须达到 100000 QPS [S1]。";

        ValidatedKnowledgeAnswer validated = validator.validate(answer, List.of(s1));

        assertThat(validated.insufficientEvidence()).isFalse();
        assertThat(validated.unsupportedClaims()).isNotEmpty();
        assertThat(validated.unsupportedClaims().stream().anyMatch(w -> w.contains("100000"))).isTrue();
    }
}
