package com.shitulelv.aicollab.infrastructure.ai.shared.api;

import com.shitulelv.aicollab.common.api.ApiResponse;
import com.shitulelv.aicollab.infrastructure.ai.shared.SharedAiProviderQueryService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/ai/shared-providers")
public class SharedAiProviderController {
    private final SharedAiProviderQueryService queryService;

    public SharedAiProviderController(SharedAiProviderQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    public ApiResponse<List<SharedAiProviderView>> listPublished(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(queryService.listPublished());
    }
}
