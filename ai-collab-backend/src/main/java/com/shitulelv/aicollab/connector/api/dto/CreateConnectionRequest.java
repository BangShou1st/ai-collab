package com.shitulelv.aicollab.connector.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateConnectionRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(max = 120) String appId,
        @NotBlank @Size(max = 200) String appSecret,
        // 可选：提供时创建项目级连接（需项目 Admin），缺省为系统级连接（需系统管理员）。
        UUID projectId) {
}
