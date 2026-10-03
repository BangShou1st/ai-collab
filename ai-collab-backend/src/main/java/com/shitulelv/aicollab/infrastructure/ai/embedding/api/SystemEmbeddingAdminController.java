package com.shitulelv.aicollab.infrastructure.ai.embedding.api;

import com.shitulelv.aicollab.common.api.ApiResponse;
import com.shitulelv.aicollab.infrastructure.ai.embedding.SystemEmbeddingService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * 系统 Embedding 管理接口：仅 systemAdmin，位于 Admin→AI Infrastructure。
 * API Key 只写不读，读只返回 hasApiKey。
 */
@RestController
@RequestMapping("/api/v1/admin/embedding-config")
public class SystemEmbeddingAdminController {
    private final SystemEmbeddingService embeddings;
    private com.shitulelv.aicollab.infrastructure.ai.embedding.EmbeddingIndexService indexes;
    private com.shitulelv.aicollab.user.service.UserService users;
    @org.springframework.beans.factory.annotation.Autowired
    void configureIndexes(com.shitulelv.aicollab.infrastructure.ai.embedding.EmbeddingIndexService indexes,
            com.shitulelv.aicollab.user.service.UserService users) { this.indexes = indexes; this.users = users; }

    public SystemEmbeddingAdminController(SystemEmbeddingService embeddings) {
        this.embeddings = embeddings;
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
    public ApiResponse<java.util.List<Map<String,Object>>> generations(@AuthenticationPrincipal Jwt jwt) {
        users.requireSystemAdmin(userId(jwt));
        return ApiResponse.success(indexes.list());
    }
    @PostMapping("/generations/{id}/activate")
    public ApiResponse<Void> activate(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        users.requireSystemAdmin(userId(jwt)); indexes.activate(id); return ApiResponse.success(null);
    }
    @DeleteMapping("/generations/{id}")
    public ApiResponse<Void> discard(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        users.requireSystemAdmin(userId(jwt)); indexes.discard(id); return ApiResponse.success(null);
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
