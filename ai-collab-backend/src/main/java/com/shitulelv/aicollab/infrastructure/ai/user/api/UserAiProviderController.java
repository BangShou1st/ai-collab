package com.shitulelv.aicollab.infrastructure.ai.user.api;

import com.shitulelv.aicollab.common.api.ApiResponse;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelPurpose;
import com.shitulelv.aicollab.infrastructure.ai.user.ProviderProfileCatalog;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProviderService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * 个人 AI Provider 接口：Credential 归属永远是当前登录用户。
 */
@RestController
@RequestMapping("/api/v1/user/ai-providers")
public class UserAiProviderController {
    private final UserAiProviderService providers;
    private final com.shitulelv.aicollab.infrastructure.ai.user.AiCapabilityProbeService probes;
    private final com.shitulelv.aicollab.infrastructure.ai.user.ProviderModelDiscoveryService discovery;

    public UserAiProviderController(
            UserAiProviderService providers,
            com.shitulelv.aicollab.infrastructure.ai.user.AiCapabilityProbeService probes,
            com.shitulelv.aicollab.infrastructure.ai.user.ProviderModelDiscoveryService discovery) {
        this.providers = providers;
        this.probes = probes;
        this.discovery = discovery;
    }

    /** 三种自定义协议的接入档案：前端表单默认端点与能力候选的唯一事实源。 */
    @GetMapping("/profiles")
    public ApiResponse<List<ProviderProfileCatalog.ProviderProfile>> profiles() {
        return ApiResponse.success(ProviderProfileCatalog.all());
    }

    @GetMapping
    public ApiResponse<List<UserAiProviderView>> list(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(providers.list(userId(jwt)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<UserAiProviderView> create(
            @Valid @RequestBody UserAiProviderRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(providers.create(userId(jwt), request));
    }

    @PatchMapping("/{id}")
    public ApiResponse<UserAiProviderView> update(
            @PathVariable UUID id,
            @Valid @RequestBody UserAiProviderRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(providers.update(userId(jwt), id, request));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        providers.delete(userId(jwt), id);
    }

    @PostMapping("/{id}/test")
    public ApiResponse<ChatCompletionResult> test(
            @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(providers.test(userId(jwt), id));
    }
    @PostMapping("/{id}/test-capability")
    public ApiResponse<com.shitulelv.aicollab.infrastructure.ai.user.AiCapabilityProbeService.Evidence> testCapability(
            @PathVariable UUID id,
            @RequestParam com.shitulelv.aicollab.infrastructure.ai.user.AiCapabilityProbeService.Mode mode,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(probes.test(userId(jwt),id,mode));
    }

    /** 各探针模式最新结果；配置变更后的旧结论以 stale 标记（前端展示为“已过期”）。 */
    @GetMapping("/{id}/probe-results")
    public ApiResponse<List<com.shitulelv.aicollab.infrastructure.ai.user.AiCapabilityProbeService.ProbeResultView>> probeResults(
            @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(probes.results(userId(jwt), id));
    }

    /**
     * 一键「检测并配置」：静态绑定版本 → 串行真实探针 → PASS 能力 CAS 写回。
     * 用户一次确认即授权本次列出的全部请求（连接 1 + 流式 1 + 规划最多 4 + 工具 1）。
     */
    @PostMapping("/{id}/detect-and-configure")
    public ApiResponse<com.shitulelv.aicollab.infrastructure.ai.user.AiCapabilityProbeService.DetectAndConfigureResult> detectAndConfigure(
            @PathVariable UUID id,
            @RequestBody DetectAndConfigureRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(probes.detectAndConfigure(
                userId(jwt), id, Boolean.TRUE.equals(request.planning()), Boolean.TRUE.equals(request.nativeTools())));
    }

    /** 未保存配置的模型发现：输入 Key 后即可一键获取模型列表；Key 只用于本次只读请求，不落库。 */
    @PostMapping("/model-discovery")
    public ApiResponse<List<String>> discoverUnsavedModels(
            @Valid @RequestBody UnsavedModelDiscoveryRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(discovery.discoverUnsaved(
                userId(jwt), request.providerType(), request.baseUrl(), request.apiPath(), request.apiKey()));
    }

    /** 模型列表发现（只读 GET，不消耗推理）；失败时前端降级为手动填写模型 ID。 */
    @GetMapping("/{id}/models")
    public ApiResponse<List<String>> models(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "false") boolean refresh,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(discovery.models(userId(jwt), id, refresh));
    }

    @PutMapping("/defaults")
    public ApiResponse<Void> setDefault(
            @RequestBody SetDefaultRequest request, @AuthenticationPrincipal Jwt jwt) {
        providers.setDefault(userId(jwt), request.providerId());
        return ApiResponse.success(null);
    }

    @GetMapping("/purposes")
    public ApiResponse<java.util.Map<ModelPurpose, UUID>> listPurposes(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(providers.listAssignments(userId(jwt)));
    }

    @PutMapping("/purposes/{purpose}")
    public ApiResponse<Void> assignPurpose(
            @PathVariable ModelPurpose purpose,
            @RequestBody AssignPurposeRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        providers.assignPurpose(userId(jwt), purpose, request.providerId());
        return ApiResponse.success(null);
    }

    @DeleteMapping("/purposes/{purpose}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unassignPurpose(
            @PathVariable ModelPurpose purpose, @AuthenticationPrincipal Jwt jwt) {
        providers.unassignPurpose(userId(jwt), purpose);
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    public record SetDefaultRequest(UUID providerId) {
    }

    public record AssignPurposeRequest(UUID providerId) {
    }

    /** 可选深度能力测试开关：规划契约与原生工具探针。 */
    public record DetectAndConfigureRequest(Boolean planning, Boolean nativeTools) {
    }

    public record UnsavedModelDiscoveryRequest(
            @jakarta.validation.constraints.NotNull com.shitulelv.aicollab.infrastructure.ai.model.ModelProviderType providerType,
            @jakarta.validation.constraints.NotBlank String baseUrl,
            @jakarta.validation.constraints.NotBlank String apiPath,
            @jakarta.validation.constraints.NotBlank String apiKey) {
    }
}
