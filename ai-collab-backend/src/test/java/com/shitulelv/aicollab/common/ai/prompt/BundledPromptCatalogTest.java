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

/** classpath 只读 fallback：14 条冻结资产、逐条指纹校验、损坏条目快速失败。 */
class BundledPromptCatalogTest {

    private static final String KNOWLEDGE_SYSTEM_SHA =
            "5b04499561a3493b29017ca613c67b7d47f091a11fb8abf64e9b4e239d38b936";

    @TempDir
    Path tempDir;

    @Test
    void loadsAllFrozenEntriesFromClasspath() {
        BundledPromptCatalog catalog = new BundledPromptCatalog();

        assertThat(catalog.source()).isEqualTo("BUNDLED");
        assertThat(catalog.readinessFailure()).isNull();
        assertThat(catalog.versions()).hasSize(14)
                .containsEntry("knowledge.system", "2026-11-11.2")
                .containsEntry("knowledge.user", "2026-11-11.1");

        PromptTemplate system = catalog.require("knowledge.system");
        assertThat(system.version()).isEqualTo("2026-11-11.2");
        assertThat(system.sha256()).isEqualTo(KNOWLEDGE_SYSTEM_SHA);
        assertThat(system.chars()).isEqualTo(1352);
        assertThat(system.variables()).containsExactly("timeContext");
        assertThat(system.text()).startsWith("{{ timeContext }}\n\n");
        assertThat(PromptAssetSupport.sha256Hex(system.text())).isEqualTo(system.sha256());
        assertThat(PromptAssetSupport.codePointCount(system.text())).isEqualTo(system.chars());
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
        assertThat(catalog.require("knowledge.user").text()).doesNotEndWith("\n");
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
                "promptpack/texts/knowledge.system/2026-11-11.2.md");
        String raw = Files.readString(template, StandardCharsets.UTF_8);
        Files.writeString(template, raw.substring(0, raw.length() - 1) + "X\n",
                StandardCharsets.UTF_8);

        try (URLClassLoader loader = PromptPackFixtures.classLoaderFor(tempDir)) {
            BundledPromptCatalog catalog = new BundledPromptCatalog(loader);

            assertThat(catalog.readinessFailure())
                    .contains("knowledge.system")
                    .contains("sha256 mismatch");
            assertThat(catalog.versions()).hasSize(13).doesNotContainKey("knowledge.system");
            assertThatThrownBy(() -> catalog.require("knowledge.system"))
                    .isInstanceOfSatisfying(BusinessException.class,
                            failure -> assertThat(failure.getErrorCode())
                                    .isEqualTo(ErrorCode.AI_PROMPT_UNAVAILABLE));
            // 其余条目不受影响
            assertThat(catalog.require("knowledge.user").version()).isEqualTo("2026-11-11.1");
        }
    }

    @Test
    void missingManifestDoesNotThrowOnConstruction_butEveryRequireFails() throws Exception {
        Files.createDirectories(tempDir.resolve("promptpack"));

        try (URLClassLoader loader = PromptPackFixtures.classLoaderFor(tempDir)) {
            BundledPromptCatalog catalog = new BundledPromptCatalog(loader);

            assertThat(catalog.versions()).isEmpty();
            assertThat(catalog.readinessFailure()).contains("manifest missing");
            assertThatThrownBy(() -> catalog.require("knowledge.system"))
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
            assertThatThrownBy(() -> catalog.require("knowledge.user"))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Test
    void schemaVersionMustBeOne() throws Exception {
        writeManifest("{\"schemaVersion\":2,\"prompts\":[]}");

        try (URLClassLoader loader = PromptPackFixtures.classLoaderFor(tempDir)) {
            BundledPromptCatalog catalog = new BundledPromptCatalog(loader);

            assertThat(catalog.readinessFailure()).contains("manifest schema invalid");
            assertThatThrownBy(() -> catalog.require("knowledge.system"))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Test
    void invalidVersionMarksEntryCorrupt() throws Exception {
        writeManifest("""
                {"schemaVersion":1,"prompts":[
                  {"key":"knowledge.system","version":"2026-11-11","sha256":"%s","chars":1,"variables":[]}
                ]}
                """.formatted("0".repeat(64)));

        try (URLClassLoader loader = PromptPackFixtures.classLoaderFor(tempDir)) {
            BundledPromptCatalog catalog = new BundledPromptCatalog(loader);

            assertThat(catalog.readinessFailure()).contains("invalid version");
            assertThatThrownBy(() -> catalog.require("knowledge.system"))
                    .isInstanceOf(BusinessException.class);
        }
    }

    private void writeManifest(String json) throws Exception {
        Files.createDirectories(tempDir.resolve("promptpack"));
        Files.writeString(tempDir.resolve("promptpack/manifest.json"), json,
                StandardCharsets.UTF_8);
    }
}

