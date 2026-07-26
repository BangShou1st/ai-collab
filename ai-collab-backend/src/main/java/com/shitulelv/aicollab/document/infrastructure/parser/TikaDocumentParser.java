package com.shitulelv.aicollab.document.infrastructure.parser;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import jakarta.annotation.PreDestroy;
import org.apache.tika.exception.TikaException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class TikaDocumentParser implements DocumentParser {
    private static final int MAX_CHARACTERS = 2_000_000;
    private static final long PARSE_TIMEOUT_SECONDS = 60;
    private static final long SLOT_WAIT_SECONDS = 5;
    private static final AtomicInteger THREAD_NUMBER = new AtomicInteger();
    private final Semaphore parserSlots = new Semaphore(2);
    private final ExecutorService parserExecutor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable,
                "document-parser-" + THREAD_NUMBER.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });

    @Override
    public ParsedDocument parse(byte[] content, String filename, String mimeType) {
        try {
            if (!parserSlots.tryAcquire(SLOT_WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new BusinessException(ErrorCode.DOCUMENT_PARSE_FAILED, "文档解析服务繁忙，请稍后重试");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.DOCUMENT_PARSE_FAILED);
        }
        Future<ParsedDocument> future;
        try {
            future = parserExecutor.submit(() -> {
                try {
                    try (InputStream input = new ByteArrayInputStream(content)) {
                        return parseInternal(input, filename, mimeType);
                    }
                } finally {
                    parserSlots.release();
                }
            });
        } catch (RuntimeException exception) {
            parserSlots.release();
            throw new BusinessException(ErrorCode.DOCUMENT_PARSE_FAILED);
        }
        try {
            return future.get(PARSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw new BusinessException(ErrorCode.DOCUMENT_PARSE_FAILED, "文档解析超时");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new BusinessException(ErrorCode.DOCUMENT_PARSE_FAILED);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof BusinessException businessException) {
                throw businessException;
            }
            throw new BusinessException(ErrorCode.DOCUMENT_PARSE_FAILED);
        }
    }

    @PreDestroy
    public void shutdownParserExecutor() {
        parserExecutor.shutdownNow();
    }

    private ParsedDocument parseInternal(InputStream input, String filename, String mimeType) {
        String extension = extension(filename);
        try {
            String text = switch (extension) {
                case "txt", "md", "markdown" -> decodeUtf8(input);
                case "pdf", "docx" -> parseWithTika(input, filename, mimeType);
                default -> throw new BusinessException(ErrorCode.DOCUMENT_UNSUPPORTED_TYPE);
            };
            String cleaned = clean(text);
            if (cleaned.isBlank()) {
                throw new BusinessException(ErrorCode.DOCUMENT_PARSE_FAILED,
                        "未提取到可索引文本，暂不支持扫描版文档");
            }
            return new ParsedDocument(extension.toUpperCase(Locale.ROOT), cleaned);
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException | TikaException | SAXException exception) {
            throw new BusinessException(ErrorCode.DOCUMENT_PARSE_FAILED);
        }
    }

    private String parseWithTika(InputStream input, String filename, String mimeType)
            throws IOException, TikaException, SAXException {
        AutoDetectParser parser = new AutoDetectParser();
        BodyContentHandler handler = new BodyContentHandler(MAX_CHARACTERS);
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, filename);
        metadata.set(Metadata.CONTENT_TYPE, mimeType);
        ParseContext context = new ParseContext();
        context.set(EmbeddedDocumentExtractor.class, new EmbeddedDocumentExtractor() {
            @Override public boolean shouldParseEmbedded(Metadata embeddedMetadata) { return false; }
            @Override public void parseEmbedded(InputStream stream, ContentHandler embeddedHandler,
                                                Metadata embeddedMetadata, boolean outputHtml) {
                // 本阶段不解析附件，防止压缩包递归和外部资源扩张。
            }
        });
        parser.parse(input, handler, metadata, context);
        return handler.toString();
    }

    private static String decodeUtf8(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(MAX_CHARACTERS * 4 + 1);
        if (bytes.length > MAX_CHARACTERS * 4) {
            throw new BusinessException(ErrorCode.DOCUMENT_PARSE_FAILED, "文档文本超过安全解析上限");
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw new BusinessException(ErrorCode.DOCUMENT_PARSE_FAILED, "TXT 与 Markdown 必须使用 UTF-8 编码");
        }
    }

    private static String clean(String value) {
        String normalized = value.replace("\r\n", "\n").replace('\r', '\n')
                .replace("\u0000", "").replace("\uFEFF", "");
        normalized = normalized.replaceAll("[\\p{Cc}&&[^\\n\\t]]", "")
                .replaceAll("[ \\t]+", " ")
                .replaceAll(" *\\n *", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
        if (normalized.length() > MAX_CHARACTERS) {
            throw new BusinessException(ErrorCode.DOCUMENT_PARSE_FAILED, "文档文本超过安全解析上限");
        }
        return normalized;
    }

    private static String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
