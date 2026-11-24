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
    void defaultIsStrategyAwareAndLegacyFallbackDisabled() {
        RetrievalQaProperties defaults = RetrievalQaProperties.defaults();
        assertThat(defaults.mode()).isEqualTo(ProjectKnowledgeRetrieval.Mode.STRATEGY_AWARE);
        assertThat(defaults.forceLegacyDense()).isFalse();
    }

    @Test
    void acceptsDenseLegacyCaseInsensitively() {
        assertThat(new RetrievalQaProperties(" dense_legacy ").mode())
                .isEqualTo(ProjectKnowledgeRetrieval.Mode.DENSE_LEGACY);
    }

    @Test
    void rejectsUnknownModeAtStartupBinding() {
        assertThatThrownBy(() -> new RetrievalQaProperties("SOMETHING_ELSE"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retrieval.qa-mode");
    }

    @Test
    void springBindingUsesDefaultAndExplicitProperties() {
        contextRunner.run(context -> {
            RetrievalQaProperties props = context.getBean(RetrievalQaProperties.class);
            assertThat(props.mode()).isEqualTo(ProjectKnowledgeRetrieval.Mode.STRATEGY_AWARE);
            assertThat(props.forceLegacyDense()).isFalse();
        });

        contextRunner.withPropertyValues(
                        "retrieval.qa-mode=DENSE_LEGACY",
                        "retrieval.force-legacy-dense=true")
                .run(context -> {
                    RetrievalQaProperties props = context.getBean(RetrievalQaProperties.class);
                    assertThat(props.mode()).isEqualTo(ProjectKnowledgeRetrieval.Mode.DENSE_LEGACY);
                    assertThat(props.forceLegacyDense()).isTrue();
                });
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
