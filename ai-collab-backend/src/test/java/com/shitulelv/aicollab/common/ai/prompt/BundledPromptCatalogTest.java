package com.shitulelv.aicollab.common.ai.prompt;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** classpath 只读 fallback：12 条冻结资产（P3 起 knowledge 两条随问答迁 Python）、逐条指纹校验、损坏条目快速失败。 */
class BundledPromptCatalogTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsAllFrozenEntriesFromClasspath() {
        BundledPromptCatalog catalog = new BundledPromptCatalog();

        assertThat(catalog.source()).isEqualTo("BUNDLED");
        assertThat(catalog.readinessFailure()).isNull();
        assertThat(catalog.versions()).hasSize(12)
                .doesNotContainKey("knowledge.system")
                .doesNotContainKey("knowledge.user")
                .containsKey("scenario.delivery_readiness.instruction");

        PromptTemplate scenario = catalog.require("scenario.delivery_readiness.instruction");
        // 指纹与独立重建的 PromptPackFixtures 对账（不硬编码 sha，避免资产修订时漂移）
        PromptPackFixtures.Entry expected = PromptPackFixtures.realPack()
                .entries().get("scenario.delivery_readiness.instruction");
        assertThat(scenario.version()).isEqualTo(expected.version());
        assertThat(scenario.sha256()).isEqualTo(expected.sha256());
        assertThat(PromptAssetSupport.sha256Hex(scenario.text())).isEqualTo(scenario.sha256());
        assertThat(PromptAssetSupport.codePointCount(scenario.text())).isEqualTo(scenario.chars());
    }

    @Test
    void everyEntryMatchesManifestAndIndependentFixture() {
        PromptPackFixtures.Pack real = PromptPackFixtures.realPack();
        BundledPromptCatalog catalog = new BundledPromptCatalog();

        for (String key : PromptKeys.all()) {
            PromptTemplate template = catalog.require(key);
            PromptPackFixtures.Entry expected = real.entries().get(key);
            assertThat(template.version()).isEqualTo(expected.version());
            assertThat(template.sha256()).isEqualTo(expected.sha256());
            assertThat(template.chars()).isEqualTo(expected.chars());
            assertThat(template.variables()).isEqualTo(expected.variables());
            assertThat(template.text()).isEqualTo(real.texts().get(key));
        }
        // Java 内置目录只加载 PromptKeys.all() 的 key；manifest 还包含 worker 专用 prompt
        // （query_plan/decide/planning/memory），它们由 Python 侧 ai-prompts check 校验。
        PromptPackFixtures.Pack javaPack = PromptPackFixtures.subset(real, PromptKeys.all());
        assertThat(real.entries().keySet()).containsAll(PromptKeys.all());
        assertThat(catalog.manifestFingerprint())
                .isEqualTo(PromptPackFixtures.fingerprint(javaPack));
    }

    @Test
    void trailerNewlineIsStrippedOnlyOnce_bodyMayEndWithNewline() {
        // F2：六个 Scenario 的正文自身以 \n 结尾，文件以 \n\n 结尾；不能把正文末尾换行当损坏。
        BundledPromptCatalog catalog = new BundledPromptCatalog();

        assertThat(catalog.require("scenario.project_research.instruction").text())
                .endsWith("\n");
        // 尾换行只剥离一次：正文自身以 \n 结尾的资产（F2）剥离文件尾换行后仍以 \n 结尾
        assertThat(catalog.require("scenario.delivery_readiness.output_contract").text()).endsWith("\n");
    }

    @Test
    void fingerprintIsStableAcrossInstances() {
        String first = new BundledPromptCatalog().manifestFingerprint();
        String second = new BundledPromptCatalog().manifestFingerprint();

        assertThat(first).matches("[0-9a-f]{64}").isEqualTo(second);
    }

    @Test
    void unknownKeyThrowsPromptUnavailable() {
        BundledPromptCatalog catalog = new BundledPromptCatalog();

        assertThatThrownBy(() -> catalog.require("knowledge.unknown"))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.getErrorCode())
                                .isEqualTo(ErrorCode.AI_PROMPT_UNAVAILABLE));
    }

    @Test
    void versionsViewIsImmutable() {
        BundledPromptCatalog catalog = new BundledPromptCatalog();

        assertThatThrownBy(() -> catalog.versions().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void corruptedEntryFailsOnlyThatKey() throws Exception {
        PromptPackFixtures.copyRealPackTo(tempDir);
        Path template = tempDir.resolve(
                "promptpack/texts/scenario.delivery_readiness.instruction/2026-11-11.1.md");
        String raw = Files.readString(template, StandardCharsets.UTF_8);
        Files.writeString(template, raw.substring(0, raw.length() - 1) + "X\n",
                StandardCharsets.UTF_8);

        try (URLClassLoader loader = PromptPackFixtures.classLoaderFor(tempDir)) {
            BundledPromptCatalog catalog = new BundledPromptCatalog(loader);

            assertThat(catalog.readinessFailure())
                    .contains("scenario.delivery_readiness.instruction")
                    .contains("sha256 mismatch");
            assertThat(catalog.versions()).hasSize(11).doesNotContainKey("scenario.delivery_readiness.instruction");
            assertThatThrownBy(() -> catalog.require("scenario.delivery_readiness.instruction"))
                    .isInstanceOfSatisfying(BusinessException.class,
                            failure -> assertThat(failure.getErrorCode())
                                    .isEqualTo(ErrorCode.AI_PROMPT_UNAVAILABLE));
            // 其余条目不受影响
            assertThat(catalog.require("scenario.delivery_readiness.output_contract").version()).isEqualTo("2026-11-11.1");
        }
    }

    @Test
    void missingManifestDoesNotThrowOnConstruction_butEveryRequireFails() throws Exception {
        Files.createDirectories(tempDir.resolve("promptpack"));

        try (URLClassLoader loader = PromptPackFixtures.classLoaderFor(tempDir)) {
            BundledPromptCatalog catalog = new BundledPromptCatalog(loader);

            assertThat(catalog.versions()).isEmpty();
            assertThat(catalog.readinessFailure()).contains("manifest missing");
            assertThatThrownBy(() -> catalog.require("scenario.delivery_readiness.instruction"))
                    .isInstanceOfSatisfying(BusinessException.class,
                            failure -> assertThat(failure.getErrorCode())
                                    .isEqualTo(ErrorCode.AI_PROMPT_UNAVAILABLE));
        }
    }

    @Test
    void invalidJsonDoesNotThrowOnConstruction_butEveryRequireFails() throws Exception {
        Files.createDirectories(tempDir.resolve("promptpack"));
        Files.writeString(tempDir.resolve("promptpack/manifest.json"), "{ not json",
                StandardCharsets.UTF_8);

        try (URLClassLoader loader = PromptPackFixtures.classLoaderFor(tempDir)) {
            BundledPromptCatalog catalog = new BundledPromptCatalog(loader);

            assertThat(catalog.versions()).isEmpty();
            assertThat(catalog.readinessFailure()).contains("not valid JSON");
            assertThatThrownBy(() -> catalog.require("scenario.delivery_readiness.output_contract"))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Test
    void schemaVersionMustBeOne() throws Exception {
        writeManifest("{\"schemaVersion\":2,\"prompts\":[]}");

        try (URLClassLoader loader = PromptPackFixtures.classLoaderFor(tempDir)) {
            BundledPromptCatalog catalog = new BundledPromptCatalog(loader);

            assertThat(catalog.readinessFailure()).contains("manifest schema invalid");
            assertThatThrownBy(() -> catalog.require("scenario.delivery_readiness.instruction"))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Test
    void invalidVersionMarksEntryCorrupt() throws Exception {
        writeManifest("""
                {"schemaVersion":1,"prompts":[
                  {"key":"scenario.delivery_readiness.instruction","version":"2026-11-11","sha256":"%s","chars":1,"variables":[]}
                ]}
                """.formatted("0".repeat(64)));

        try (URLClassLoader loader = PromptPackFixtures.classLoaderFor(tempDir)) {
            BundledPromptCatalog catalog = new BundledPromptCatalog(loader);

            assertThat(catalog.readinessFailure()).contains("invalid version");
            assertThatThrownBy(() -> catalog.require("scenario.delivery_readiness.instruction"))
                    .isInstanceOf(BusinessException.class);
        }
    }

    private void writeManifest(String json) throws Exception {
        Files.createDirectories(tempDir.resolve("promptpack"));
        Files.writeString(tempDir.resolve("promptpack/manifest.json"), json,
                StandardCharsets.UTF_8);
    }
}

