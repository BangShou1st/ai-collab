package com.shitulelv.aicollab.home.api.controller;

import com.shitulelv.aicollab.common.api.ApiResponse;
import com.shitulelv.aicollab.home.application.service.HomeApplicationService;
import com.shitulelv.aicollab.home.application.view.HomeView;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/home")
public class HomeController {
    private final HomeApplicationService home;

    public HomeController(HomeApplicationService home) {
        this.home = home;
    }

    @GetMapping
    public ApiResponse<HomeView> home(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(home.home(UUID.fromString(jwt.getSubject())));
    }
}
