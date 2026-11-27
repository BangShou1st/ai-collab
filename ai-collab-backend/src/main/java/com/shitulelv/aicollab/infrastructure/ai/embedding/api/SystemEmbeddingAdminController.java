package com.shitulelv.aicollab.infrastructure.ai.embedding.api;

import com.shitulelv.aicollab.common.api.ApiResponse;
import com.shitulelv.aicollab.infrastructure.ai.embedding.SystemEmbeddingService;
import com.shitulelv.aicollab.infrastructure.ai.embedding.SystemIndexGenerationService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.List;
import java.util.UUID;

/**
 * 系统 Embedding 管理接口：仅 systemAdmin，位于 Admin→AI Infrastructure。
 * API Key 只写不读，读只返回 hasApiKey。
 */
@RestController
@RequestMapping("/api/v1/admin/embedding-config")
public class SystemEmbeddingAdminController {
    private final SystemEmbeddingService embeddings;
    private final SystemIndexGenerationService indexes;

    public SystemEmbeddingAdminController(SystemEmbeddingService embeddings, SystemIndexGenerationService indexes) {
        this.embeddings = embeddings;
        this.indexes = indexes;
    }

    @GetMapping
    public ApiResponse<SystemEmbeddingConfigView> get(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(embeddings.get(userId(jwt)));
    }

    @PutMapping
    public ApiResponse<SystemEmbeddingConfigView> update(
            @Valid @RequestBody SystemEmbeddingConfigRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(embeddings.update(userId(jwt), request));
    }

    @PostMapping("/test")
    public ApiResponse<Void> test(@AuthenticationPrincipal Jwt jwt) {
        embeddings.test(userId(jwt));
        return ApiResponse.success(null);
    }

    @PostMapping("/test-candidate")
    public ApiResponse<SystemEmbeddingService.TestResult> testCandidate(
            @Valid @RequestBody SystemEmbeddingConfigRequest request, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(embeddings.testCandidate(userId(jwt), request));
    }

    @PostMapping("/reindex")
    public ApiResponse<Map<String, Integer>> reindex(
            @Valid @RequestBody SystemEmbeddingConfigRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(Map.of("documents", embeddings.reindex(userId(jwt), request)));
    }

    @GetMapping("/generations")
    public ApiResponse<List<SystemIndexGenerationService.GenerationView>> generations(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(indexes.list(userId(jwt)));
    }
    @PostMapping("/generations/{id}/activate")
    public ApiResponse<Void> activate(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        indexes.activate(userId(jwt), id);
        return ApiResponse.success(null);
    }
    @DeleteMapping("/generations/{id}")
    public ApiResponse<Void> discard(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        indexes.discard(userId(jwt), id);
        return ApiResponse.success(null);
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
