package com.shitulelv.aicollab.document.domain.service;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import org.apache.tika.Tika;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class DocumentFilePolicy {
    public static final long MAX_BYTES = 20L * 1024 * 1024;
    private static final Map<String, Set<String>> TYPES = Map.of(
            "pdf", Set.of("application/pdf"),
            "docx", Set.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            "md", Set.of("text/markdown", "text/plain", "text/x-web-markdown"),
            "markdown", Set.of("text/markdown", "text/plain", "text/x-web-markdown"),
            "txt", Set.of("text/plain"));
    private final Tika tika = new Tika();

    public ValidatedFile validate(MultipartFile file, String displayName) {
        if (file == null || file.isEmpty() || file.getSize() <= 0) {
            throw new BusinessException(ErrorCode.DOCUMENT_EMPTY);
        }
        if (file.getSize() > MAX_BYTES) throw new BusinessException(ErrorCode.DOCUMENT_TOO_LARGE);
        String original = safeFilename(file.getOriginalFilename());
        if (original.length() > 180) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "文件名不能超过 180 个字符");
        }
        String extension = extension(original);
        Set<String> expected = TYPES.get(extension);
        if (expected == null) throw new BusinessException(ErrorCode.DOCUMENT_UNSUPPORTED_TYPE);
        String declared = normalizeType(file.getContentType());
        if (!expected.contains(declared)) throw new BusinessException(ErrorCode.DOCUMENT_INVALID_FILE);
        try (InputStream input = file.getInputStream()) {
            String detected = normalizeType(tika.detect(input, original));
            if (!expected.contains(detected)) throw new BusinessException(ErrorCode.DOCUMENT_INVALID_FILE);
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.DOCUMENT_INVALID_FILE);
        }
        String name = displayName == null || displayName.isBlank() ? original : displayName.trim();
        if (name.length() > 180) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "显示名称不能超过 180 个字符");
        }
        return new ValidatedFile(original, name, declared, extension);
    }

    private static String safeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new BusinessException(ErrorCode.DOCUMENT_INVALID_FILE);
        }
        String normalized = filename.replace('\\', '/');
        if (normalized.contains("../") || normalized.contains("/") || normalized.indexOf('\0') >= 0) {
            throw new BusinessException(ErrorCode.DOCUMENT_INVALID_FILE);
        }
        return normalized.trim();
    }
    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
    private static String normalizeType(String value) {
        if (value == null) return "";
        int semicolon = value.indexOf(';');
        return (semicolon < 0 ? value : value.substring(0, semicolon)).trim().toLowerCase(Locale.ROOT);
    }

    public record ValidatedFile(String originalFilename, String displayName, String mimeType, String extension) {
    }
}
