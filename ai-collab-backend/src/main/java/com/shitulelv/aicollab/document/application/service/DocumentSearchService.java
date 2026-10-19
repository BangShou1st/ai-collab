package com.shitulelv.aicollab.document.application.service;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.document.application.view.DocumentSearchHit;
import com.shitulelv.aicollab.document.domain.model.DocumentStatus;
import com.shitulelv.aicollab.document.infrastructure.entity.DocumentEntity;
import com.shitulelv.aicollab.document.infrastructure.ai.EmbeddingBatch;
import com.shitulelv.aicollab.document.infrastructure.ai.EmbeddingProgressListener;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentRepository;
import com.shitulelv.aicollab.infrastructure.ai.embedding.ProjectEmbeddingGateway;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class DocumentSearchService {
    private static final int MAX_QUERY_CODE_POINTS = 2000;
    private static final int MAX_EXPLICIT_SCOPE_DOCUMENTS = 20;
    private static final String DOCUMENT_TOKEN = "《[^《》]{1,200}》";
    private static final String DOCUMENT_LIST = DOCUMENT_TOKEN
            + "(?:\\s*(?:、|和|与|及|以及|[,，]|&|and)\\s*" + DOCUMENT_TOKEN + ")*";
    private static final Pattern LIMITED_SOURCE_LIST = Pattern.compile(
            "(?:只能|只允许|仅|只)\\s*(?:根据|依据|基于|凭|参考|使用|引用|检索|查阅)\\s*("
                    + DOCUMENT_LIST + ")"
                    + "|(?:仅限于|只限于|限于|限定在|限定为|限定来源为|来源限定为|范围限定为|"
                    + "仅在|只在)\\s*(" + DOCUMENT_LIST + ")"
                    + "|(?:只能|只允许|仅|只)\\s*以\\s*(" + DOCUMENT_LIST
                    + ")\\s*(?:(?:作为|当作|作)\\s*)?(?:为\\s*)?(?:唯一\\s*)?(?:来源|依据|参考)");
    private static final Pattern EXCLUDED_SOURCE_LIST = Pattern.compile(
            "(?:排除(?:掉)?|剔除|忽略|不要根据|不得根据|不根据|不要依据|不得依据|不依据|"
                    + "不要参考|不得参考|不参考|不要使用|不得使用|不使用|不要引用|不得引用|不引用|"
                    + "不允许引用|不能引用|禁止引用|不要检索|不得检索|不检索)\\s*(" + DOCUMENT_LIST + ")"
                    + "|(?:不要|不得|不允许|不能)\\s*(?:把)?\\s*(" + DOCUMENT_LIST
                    + ")\\s*(?:作为|当作)\\s*(?:来源|依据|参考|引用)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DOCUMENT_TOKEN_PATTERN = Pattern.compile(DOCUMENT_TOKEN);
    private final DocumentRepository documents;
    private final ProjectEmbeddingGateway embeddings;

    public DocumentSearchService(DocumentRepository documents, ProjectEmbeddingGateway embeddings) {
        this.documents = documents;
        this.embeddings = embeddings;
    }

    public List<DocumentSearchHit> search(UUID projectId, String query,
                                          List<UUID> documentIds, int topK) {
        String normalized = validateQuery(query);
        if (topK < 1 || topK > 20) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "topK 必须在 1 到 20 之间");
        }
        List<UUID> scopedIds = documentIds == null
                ? List.of() : documentIds.stream().distinct().toList();
        if (!scopedIds.isEmpty()) {
            if (documents.countDocuments(projectId, scopedIds) != scopedIds.size()) {
                throw new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND);
            }
            if (documents.countReadyDocuments(projectId, scopedIds) != scopedIds.size()) {
                throw new BusinessException(ErrorCode.DOCUMENT_NOT_READY);
            }
        }
        EmbeddingBatch queryEmbedding = embeddings.embed(projectId, List.of(normalized),
                EmbeddingProgressListener.NONE);
        if (queryEmbedding.vectors().size() != 1) {
            throw new BusinessException(ErrorCode.DOCUMENT_EMBEDDING_FAILED);
        }
        if (queryEmbedding.generationId() != null) return documents.search(projectId, queryEmbedding.vectors().getFirst(),
                queryEmbedding.provider(), queryEmbedding.model(), queryEmbedding.dimension(),
                queryEmbedding.fingerprint(), scopedIds, topK, queryEmbedding.generationId());
        return documents.search(projectId, queryEmbedding.vectors().getFirst(),
                queryEmbedding.provider(), queryEmbedding.model(), queryEmbedding.dimension(),
                queryEmbedding.fingerprint(), scopedIds, topK);
    }

    /** Resolves only exact document names from the current project, never model-generated IDs. */
    public List<UUID> resolveNamedDocumentIds(UUID projectId, List<String> explicitNames) {
        if (projectId == null || explicitNames == null || explicitNames.isEmpty()) return List.of();
        Set<String> requested = explicitNames.stream()
                .map(DocumentSearchService::normalizedDocumentName)
                .filter(name -> !name.isEmpty())
                .collect(java.util.stream.Collectors.toSet());
        if (requested.isEmpty()) return List.of();
        return documents.list(projectId).stream()
                .filter(document -> document.getStatus() == DocumentStatus.READY)
                .filter(document -> requested.contains(normalizedDocumentName(document.getOriginalFilename()))
                        || requested.contains(normalizedDocumentName(document.getDisplayName()))
                        || requested.contains(normalizedDocumentName(withoutExtension(document.getOriginalFilename())))
                        || requested.contains(normalizedDocumentName(withoutExtension(document.getDisplayName()))))
                .map(com.shitulelv.aicollab.document.infrastructure.entity.DocumentEntity::getId)
                .distinct()
                .toList();
    }

    /**
     * Parses only explicit source limits from the original user request and resolves every named
     * document exactly. A title mention, a comparison, or "根据《x》" without an exclusivity
     * marker does not create a hard scope.
     */
    public UserDocumentScope resolveUserDocumentScope(UUID projectId, String userGoal) {
        ScopeIntent intent = parseScopeIntent(userGoal);
        if (!intent.constrained()) return UserDocumentScope.unrestricted();

        LinkedHashSet<String> allNames = new LinkedHashSet<>();
        allNames.addAll(intent.includedNames());
        allNames.addAll(intent.excludedNames());
        List<String> unresolved = new ArrayList<>();
        if (allNames.size() > MAX_EXPLICIT_SCOPE_DOCUMENTS) {
            unresolved.addAll(allNames.stream().skip(MAX_EXPLICIT_SCOPE_DOCUMENTS).toList());
        }

        List<DocumentEntity> readyDocuments = projectId == null ? List.of() : documents.list(projectId).stream()
                .filter(document -> document.getStatus() == DocumentStatus.READY)
                .toList();
        Map<String, UUID> resolvedByName = new java.util.LinkedHashMap<>();
        for (String name : allNames.stream().limit(MAX_EXPLICIT_SCOPE_DOCUMENTS).toList()) {
            Set<UUID> matches = readyDocuments.stream()
                    .filter(document -> matchesName(document, name))
                    .map(DocumentEntity::getId)
                    .collect(java.util.stream.Collectors.toSet());
            if (matches.size() == 1) resolvedByName.put(name, matches.iterator().next());
            else unresolved.add(name);
        }

        List<UUID> includedIds = resolveIntentNames(intent.includedNames(), resolvedByName);
        List<UUID> excludedIds = resolveIntentNames(intent.excludedNames(), resolvedByName);
        List<UUID> effectiveIncludedIds = includedIds.stream()
                .filter(id -> !excludedIds.contains(id))
                .distinct()
                .toList();
        ScopeStatus status = !unresolved.isEmpty()
                ? ScopeStatus.UNRESOLVED_INCOMPLETE
                : !intent.includedNames().isEmpty() && effectiveIncludedIds.isEmpty()
                        ? ScopeStatus.EMPTY
                        : ScopeStatus.ENFORCED;
        return new UserDocumentScope(status, intent.includedNames(), intent.excludedNames(),
                includedIds, excludedIds, effectiveIncludedIds, unresolved.stream().distinct().toList());
    }

    private static ScopeIntent parseScopeIntent(String userGoal) {
        if (userGoal == null || userGoal.isBlank()) return ScopeIntent.unrestricted();
        List<String> included = namesInMatches(LIMITED_SOURCE_LIST, userGoal);
        List<String> excluded = namesInMatches(EXCLUDED_SOURCE_LIST, userGoal);
        return new ScopeIntent(distinct(included), distinct(excluded));
    }

    private static List<String> namesInMatches(Pattern pattern, String userGoal) {
        List<String> names = new ArrayList<>();
        Matcher matcher = pattern.matcher(userGoal);
        while (matcher.find()) {
            String bundle = null;
            for (int group = 1; group <= matcher.groupCount(); group++) {
                if (matcher.group(group) != null) {
                    bundle = matcher.group(group);
                    break;
                }
            }
            if (bundle == null) continue;
            Matcher token = DOCUMENT_TOKEN_PATTERN.matcher(bundle);
            while (token.find()) {
                String name = token.group().substring(1, token.group().length() - 1).strip();
                if (!name.isEmpty()) names.add(name);
            }
        }
        return names;
    }

    private static List<String> distinct(List<String> names) {
        return List.copyOf(new LinkedHashSet<>(names));
    }

    private static List<UUID> resolveIntentNames(List<String> names, Map<String, UUID> resolvedByName) {
        return names.stream().map(resolvedByName::get).filter(java.util.Objects::nonNull).distinct().toList();
    }

    private static boolean matchesName(DocumentEntity document, String requestedName) {
        String requested = normalizedDocumentName(requestedName);
        return requested.equals(normalizedDocumentName(document.getOriginalFilename()))
                || requested.equals(normalizedDocumentName(document.getDisplayName()))
                || requested.equals(normalizedDocumentName(withoutExtension(document.getOriginalFilename())))
                || requested.equals(normalizedDocumentName(withoutExtension(document.getDisplayName())));
    }

    private static String normalizedDocumentName(String name) {
        if (name == null) return "";
        return name.strip().replace('\\', '/').replaceAll("^.*/", "").toLowerCase(Locale.ROOT);
    }

    private static String withoutExtension(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String validateQuery(String query) {
        if (query == null || query.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "检索内容不能为空");
        }
        String normalized = query.trim();
        if (normalized.codePointCount(0, normalized.length()) > MAX_QUERY_CODE_POINTS) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "检索内容不能超过 2000 个字符");
        }
        return normalized;
    }

    private record ScopeIntent(List<String> includedNames, List<String> excludedNames) {
        private ScopeIntent {
            includedNames = List.copyOf(includedNames);
            excludedNames = List.copyOf(excludedNames);
        }

        static ScopeIntent unrestricted() { return new ScopeIntent(List.of(), List.of()); }
        boolean constrained() { return !includedNames.isEmpty() || !excludedNames.isEmpty(); }
    }

    public enum ScopeStatus { UNRESTRICTED, ENFORCED, EMPTY, UNRESOLVED_INCOMPLETE }

    public record UserDocumentScope(
            ScopeStatus status,
            List<String> includedNames,
            List<String> excludedNames,
            List<UUID> includedDocumentIds,
            List<UUID> excludedDocumentIds,
            List<UUID> effectiveIncludedDocumentIds,
            List<String> unresolvedNames) {
        public UserDocumentScope {
            includedNames = List.copyOf(includedNames);
            excludedNames = List.copyOf(excludedNames);
            includedDocumentIds = List.copyOf(includedDocumentIds);
            excludedDocumentIds = List.copyOf(excludedDocumentIds);
            effectiveIncludedDocumentIds = List.copyOf(effectiveIncludedDocumentIds);
            unresolvedNames = List.copyOf(unresolvedNames);
        }

        public static UserDocumentScope unrestricted() {
            return new UserDocumentScope(ScopeStatus.UNRESTRICTED, List.of(), List.of(),
                    List.of(), List.of(), List.of(), List.of());
        }

        public boolean constrained() { return status != ScopeStatus.UNRESTRICTED; }
        public boolean complete() { return status != ScopeStatus.UNRESOLVED_INCOMPLETE; }
        public boolean hasIncludeList() { return !includedNames.isEmpty(); }
        public boolean canSearch() { return status == ScopeStatus.UNRESTRICTED || status == ScopeStatus.ENFORCED; }

        public boolean allowsDocument(UUID documentId) {
            if (status == ScopeStatus.UNRESTRICTED) return true;
            if (status != ScopeStatus.ENFORCED || documentId == null) return false;
            return (includedNames.isEmpty() || effectiveIncludedDocumentIds.contains(documentId))
                    && !excludedDocumentIds.contains(documentId);
        }

        public Map<String, Object> diagnostics() {
            return Map.of(
                    "status", status.name(),
                    "includedNames", includedNames,
                    "excludedNames", excludedNames,
                    "unresolvedNames", unresolvedNames);
        }
    }
}
