package com.shitulelv.aicollab.work.api.controller;

import com.shitulelv.aicollab.common.api.PageView;
import com.shitulelv.aicollab.work.application.service.TaskApplicationService;
import com.shitulelv.aicollab.work.application.view.TaskView;
import com.shitulelv.aicollab.work.domain.model.TaskPriority;
import com.shitulelv.aicollab.work.domain.model.TaskStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TaskControllerPaginationContractTest {

    private final TaskApplicationService service = mock(TaskApplicationService.class);
    private final UUID projectId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void defaultsToFirstPageAndCapsResponseShapeToPageView() throws Exception {
        TaskView task = new TaskView(UUID.randomUUID(), projectId, "分页任务", "",
                null, null, null, null, TaskStatus.TODO, TaskPriority.MEDIUM,
                null, null, null, 0, 0, List.of(), null);
        when(service.list(eq(projectId), isNull(), isNull(), isNull(), eq(0), eq(50), eq(userId)))
                .thenReturn(new PageView<>(List.of(task), 1, 0, 50, false));

        jwtAuthentication();
        mockMvc().perform(get("/api/v1/projects/{projectId}/tasks", projectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].title").value("分页任务"))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(50))
                .andExpect(jsonPath("$.data.hasNext").value(false));
        verify(service).list(projectId, null, null, null, 0, 50, userId);
    }

    @Test
    void forwardsExplicitPageAndFilters() throws Exception {
        when(service.list(eq(projectId), eq(TaskStatus.TODO), isNull(), isNull(), eq(2), eq(200), eq(userId)))
                .thenReturn(new PageView<>(List.of(), 10, 2, 200, false));

        jwtAuthentication();
        mockMvc().perform(get("/api/v1/projects/{projectId}/tasks", projectId)
                        .param("status", "TODO")
                        .param("page", "2")
                        .param("size", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.size").value(200));
    }

    private MockMvc mockMvc() {
        return MockMvcBuilders.standaloneSetup(new TaskController(service))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    private void jwtAuthentication() {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject(userId.toString())
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }
}
