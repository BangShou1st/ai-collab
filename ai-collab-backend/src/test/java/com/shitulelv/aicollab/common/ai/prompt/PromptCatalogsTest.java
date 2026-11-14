package com.shitulelv.aicollab.common.ai.prompt;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 无容器工厂：domain 层与测试可直接拿到与 production 同一份内置 PromptPack。 */
class PromptCatalogsTest {

    @Test
    void bundledReturnsKnowledgeSystemFromFrozenPack() {
        PromptCatalog catalog = PromptCatalogs.bundled();

        PromptTemplate system = catalog.require(PromptKeys.KNOWLEDGE_SYSTEM);
        assertThat(system.version()).isEqualTo("2026-11-11.2");
        assertThat(system.sha256())
                .isEqualTo("5b04499561a3493b29017ca613c67b7d47f091a11fb8abf64e9b4e239d38b936");
        assertThat(system.chars()).isEqualTo(1352);
        assertThat(catalog.source()).isEqualTo("BUNDLED");
        assertThat(catalog.versions()).hasSize(14);
    }

    @Test
    void bundledReturnsFreshInstances() {
        assertThat(PromptCatalogs.bundled()).isNotSameAs(PromptCatalogs.bundled());
    }
}
