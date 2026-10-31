package com.shitulelv.aicollab.project.application.view;

import com.shitulelv.aicollab.project.domain.model.ProjectRole;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 邀请创建结果。invitationUrl 基于可信 APP_PUBLIC_URL 生成；未配置时为 null，
 * 由前端回退当前页面 Origin 拼接。
 */
public record InvitationView(
        UUID id, String code, UUID projectId, ProjectRole role, OffsetDateTime expiresAt,
        String invitationUrl) {
}
