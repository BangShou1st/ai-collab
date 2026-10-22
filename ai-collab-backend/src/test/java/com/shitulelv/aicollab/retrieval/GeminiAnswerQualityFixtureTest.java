package com.shitulelv.aicollab.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.document.domain.model.DocumentChunk;
import com.shitulelv.aicollab.document.domain.service.DocumentChunker;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Locks the historical corpus provenance and the Windows materialization boundary, without model calls. */
class GeminiAnswerQualityFixtureTest {
    private static final Path ROOT = Path.of("..");
    private static final Path CORPUS = ROOT.resolve("tests/fixtures/documents/p4-rag-answer-quality-01");
    private static final Path EVIDENCE = ROOT.resolve("docs/acceptance-evidence/2026-10-22/p4-rag-answer-quality-01");
    private final ObjectMapper json = new ObjectMapper();

    @Test void archivedFilesMatchAllHistoricalByteHashesAndContainNoBom() throws Exception {
        var historical = json.readTree(ROOT.resolve("docs/acceptance-evidence/2026-10-22/p4-rag-quality-02/paired-chunking.json").toFile()).path("rawDocumentHashes");
        var manifest = json.readTree(EVIDENCE.resolve("corpus-manifest.json").toFile());
        int historicalCount = 0;
        for (var document : manifest.path("documents")) {
            byte[] bytes = Files.readAllBytes(CORPUS.resolve(document.path("filename").asText()));
            String text = new String(bytes, StandardCharsets.UTF_8);
            assertThat(text).doesNotStartWith("\uFEFF").doesNotContain("\r");
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            assertThat(digest).isEqualTo(document.path("sha256").asText());
            if (document.has("historicalIndex")) {
                assertThat(digest).isEqualTo(historical.get(document.path("historicalIndex").asInt()).asText());
                historicalCount++;
            }
        }
        assertThat(historicalCount).isEqualTo(13);
        assertThat(manifest.path("documents").size()).isEqualTo(14);
    }

    @Test void productionChunkBodiesAndHeadingsAreIdenticalAcrossWindowsAndArchivedLineEndings() throws Exception {
        var chunker = new DocumentChunker();
        try (var files = Files.list(CORPUS)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".md")).toList()) {
                String lf = Files.readString(file, StandardCharsets.UTF_8);
                assertThat(bodies(chunker.split(lf.replace("\n", "\r\n"))))
                        .as(file.getFileName().toString()).isEqualTo(bodies(chunker.split(lf)));
            }
        }
    }

    private static List<List<Object>> bodies(List<DocumentChunk> chunks) {
        // Source offsets legitimately differ; headings, bodies, content hashes and chunk numbers must not.
        return chunks.stream().map(chunk -> List.<Object>of(chunk.chunkNo(), chunk.heading(), chunk.content(), chunk.contentHash())).toList();
    }
}
