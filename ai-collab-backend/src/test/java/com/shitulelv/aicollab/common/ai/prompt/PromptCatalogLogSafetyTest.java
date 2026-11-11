package com.shitulelv.aicollab.common.ai.prompt;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 日志红线：任何 Prompt 正文、变量值、响应体都不得出现在日志或异常消息里。 */
class PromptCatalogLogSafetyTest {

    /** 取 knowledge.system 正文里一段 >=20 字的稳定片段（不含换行），用于泄漏断言。 */
    private static final String BODY_SNIPPET = bodySnippet();

    @TempDir
    Path tempDir;

    @Test
    void bundledCorruptionLogsNoPromptBody() throws Exception {
        PromptPackFixtures.copyRealPackTo(tempDir);
        Path template = tempDir.resolve("promptpack/texts/knowledge.system/2026-11-11.2.md");
        String raw = Files.readString(template, StandardCharsets.UTF_8);
        Files.writeString(template, raw.substring(0, raw.length() - 1) + "X\n",
                StandardCharsets.UTF_8);

        try (LogCapture capture = new LogCapture(BundledPromptCatalog.class);
             URLClassLoader loader = PromptPackFixtures.classLoaderFor(tempDir)) {
            BundledPromptCatalog catalog = new BundledPromptCatalog(loader);

            assertThat(catalog.readinessFailure())
                    .isNotNull()
                    .doesNotContain(BODY_SNIPPET);
            assertThatThrownBy(() -> catalog.require("knowledge.system"))
                    .hasMessageNotContaining(BODY_SNIPPET);
            assertThat(capture.messages()).isNotEmpty()
                    .doesNotContain(BODY_SNIPPET);
        }
    }

    @Test
    void remoteRefreshFailureLogsNoPromptBody() throws Exception {
        PromptPackFixtures.Pack real = PromptPackFixtures.realPack();
        try (LogCapture capture = new LogCapture(RemotePromptCatalog.class);
             PromptWorkerStub stub = new PromptWorkerStub(real);
             RemotePromptCatalog catalog = new RemotePromptCatalog(
                     new PromptRegistryProperties(true, stub.baseUrl(), java.time.Duration.ZERO,
                             java.time.Duration.ofMillis(500), java.time.Duration.ofMillis(300)),
                     restClient())) {
            assertThat(catalog.require("knowledge.system").text()).contains(BODY_SNIPPET);

            // 篡改正文：失败原因只能记录固定分类，不得把响应体写进日志
            stub.tamperText("knowledge.system", BODY_SNIPPET + " TAMPERED");
            catalog.refreshNow();

            assertThat(catalog.readinessFailure())
                    .isNotNull()
                    .doesNotContain(BODY_SNIPPET)
                    .doesNotContain("TAMPERED");
            assertThat(capture.messages()).isNotEmpty()
                    .doesNotContain(BODY_SNIPPET)
                    .doesNotContain("TAMPERED");
        }
    }

    private static org.springframework.web.client.RestClient restClient() {
        org.springframework.http.client.JdkClientHttpRequestFactory factory =
                new org.springframework.http.client.JdkClientHttpRequestFactory(
                        java.net.http.HttpClient.newBuilder()
                                .connectTimeout(java.time.Duration.ofMillis(500))
                                .build());
        factory.setReadTimeout(java.time.Duration.ofMillis(300));
        return org.springframework.web.client.RestClient.builder().requestFactory(factory).build();
    }

    private static String bodySnippet() {
        String text = PromptPackFixtures.realPack().texts().get("knowledge.system");
        String anchor = "你的唯一职责是基于下方提供的项目资料回答用户的问题。";
        int index = text.indexOf(anchor);
        if (index < 0) {
            index = 0;
        }
        return text.substring(index, Math.min(text.length(), index + 24));
    }

    /** 临时挂载 ListAppender，确保捕获 INFO/WARN/ERROR 全部目录日志。 */
    private static final class LogCapture implements AutoCloseable {

        private final Logger logger;
        private final Level previousLevel;
        private final ListAppender<ILoggingEvent> appender;

        private LogCapture(Class<?> type) {
            this.logger = (Logger) LoggerFactory.getLogger(type);
            this.previousLevel = logger.getLevel();
            this.appender = new ListAppender<>();
            this.appender.start();
            logger.setLevel(Level.DEBUG);
            logger.addAppender(appender);
        }

        private String messages() {
            return appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .collect(Collectors.joining("\n"));
        }

        @Override
        public void close() {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
        }
    }
}
