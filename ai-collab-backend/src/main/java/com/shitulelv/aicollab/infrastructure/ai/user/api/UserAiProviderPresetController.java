package com.shitulelv.aicollab.infrastructure.ai.user.api;

import com.shitulelv.aicollab.common.api.ApiResponse;
import com.shitulelv.aicollab.infrastructure.ai.user.KiloGatewayPresetService;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProviderPresetService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/user/ai-provider-presets")
public class UserAiProviderPresetController {
    private final UserAiProviderPresetService presets;
    private final KiloGatewayPresetService kilo;
    public UserAiProviderPresetController(UserAiProviderPresetService presets, KiloGatewayPresetService kilo) {
        this.presets = presets;
        this.kilo = kilo;
    }
    @GetMapping
    public ApiResponse<List<UserAiProviderPresetService.PresetStatus>> list(@AuthenticationPrincipal Jwt jwt) {
        List<UserAiProviderPresetService.PresetStatus> all = new java.util.ArrayList<>(presets.presets(userId(jwt)));
        all.add(kilo.presets(userId(jwt)));
        return ApiResponse.success(all);
    }
    @GetMapping("/OPENCODE_ZEN_FREE/models")
    public ApiResponse<List<String>> models(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "false") boolean refresh) {
        return ApiResponse.success(presets.models(userId(jwt), refresh));
    }
    @PutMapping("/OPENCODE_ZEN_FREE")
    public ApiResponse<UserAiProviderPresetService.PresetStatus> save(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody SaveZenRequest request) {
        return ApiResponse.success(presets.save(userId(jwt), request.apiKey(), request.modelName(),
                request.enabled() == null || request.enabled(), request.setDefault() != null && request.setDefault()));
    }
    @DeleteMapping("/OPENCODE_ZEN_FREE")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disconnect(@AuthenticationPrincipal Jwt jwt) {
        presets.disconnect(userId(jwt));
    }
    @GetMapping("/KILO_GATEWAY_FREE/models")
    public ApiResponse<List<String>> kiloModels(@RequestParam(defaultValue = "false") boolean refresh) {
        return ApiResponse.success(kilo.models(refresh));
    }
    @PutMapping("/KILO_GATEWAY_FREE")
    public ApiResponse<UserAiProviderPresetService.PresetStatus> saveKilo(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody SaveKiloRequest request) {
        return ApiResponse.success(kilo.save(userId(jwt), request.modelName(),
                request.enabled() == null || request.enabled(), request.setDefault() != null && request.setDefault()));
    }
    @DeleteMapping("/KILO_GATEWAY_FREE")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disconnectKilo(@AuthenticationPrincipal Jwt jwt) {
        kilo.disconnect(userId(jwt));
    }
    private static UUID userId(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
    public record SaveZenRequest(@Size(max = 1000) String apiKey, @NotBlank @Size(max = 160) String modelName, Boolean enabled, Boolean setDefault) {}
    public record SaveKiloRequest(@NotBlank @Size(max = 160) String modelName, Boolean enabled, Boolean setDefault) {}
}
