package com.shitulelv.aicollab.retrieval.application;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetrievalQaPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(BindingConfiguration.class);

    @Test
    void defaultIsDenseLegacy() {
        assertThat(RetrievalQaProperties.defaults().mode())
                .isEqualTo(ProjectKnowledgeRetrieval.Mode.DENSE_LEGACY);
    }

    @Test
    void acceptsStrategyAwareCaseInsensitively() {
        assertThat(new RetrievalQaProperties(" strategy_aware ").mode())
                .isEqualTo(ProjectKnowledgeRetrieval.Mode.STRATEGY_AWARE);
    }

    @Test
    void rejectsUnknownModeAtStartupBinding() {
        assertThatThrownBy(() -> new RetrievalQaProperties("SOMETHING_ELSE"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retrieval.qa-mode");
    }

    @Test
    void springBindingUsesDefaultAndExplicitProperty() {
        contextRunner.run(context -> assertThat(context.getBean(RetrievalQaProperties.class).mode())
                .isEqualTo(ProjectKnowledgeRetrieval.Mode.DENSE_LEGACY));

        contextRunner.withPropertyValues("retrieval.qa-mode=STRATEGY_AWARE")
                .run(context -> assertThat(context.getBean(RetrievalQaProperties.class).mode())
                        .isEqualTo(ProjectKnowledgeRetrieval.Mode.STRATEGY_AWARE));
    }

    @Test
    void invalidPropertyFailsContextStartup() {
        contextRunner.withPropertyValues("retrieval.qa-mode=INVALID")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RetrievalQaProperties.class)
    static class BindingConfiguration {
    }
}
