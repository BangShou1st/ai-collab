package com.shitulelv.aicollab.project.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.shitulelv.aicollab.project.domain.model.ProjectRole;

import java.time.OffsetDateTime;
import java.util.UUID;

@TableName("project_member")
public class ProjectMemberEntity {
    private UUID projectId;
    private UUID userId;
    private ProjectRole role;
    private UUID invitedBy;
    private OffsetDateTime joinedAt;

    public UUID getProjectId() { return projectId; }
    public void setProjectId(UUID projectId) { this.projectId = projectId; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public ProjectRole getRole() { return role; }
    public void setRole(ProjectRole role) { this.role = role; }
    public UUID getInvitedBy() { return invitedBy; }
    public void setInvitedBy(UUID invitedBy) { this.invitedBy = invitedBy; }
    public OffsetDateTime getJoinedAt() { return joinedAt; }
    public void setJoinedAt(OffsetDateTime joinedAt) { this.joinedAt = joinedAt; }
}
