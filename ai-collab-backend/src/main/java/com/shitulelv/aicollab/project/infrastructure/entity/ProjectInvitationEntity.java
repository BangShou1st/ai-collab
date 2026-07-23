package com.shitulelv.aicollab.project.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.shitulelv.aicollab.project.domain.model.InvitationStatus;
import com.shitulelv.aicollab.project.domain.model.ProjectRole;

import java.time.OffsetDateTime;
import java.util.UUID;

@TableName("project_invitation")
public class ProjectInvitationEntity {
    @TableId(type = IdType.INPUT)
    private UUID id;
    private UUID projectId;
    @TableField("invite_code_hash")
    private String codeHash;
    private String invitedEmail;
    private ProjectRole role;
    private InvitationStatus status;
    private OffsetDateTime expiresAt;
    private UUID acceptedBy;
    private OffsetDateTime acceptedAt;
    private UUID createdBy;
    private OffsetDateTime createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getProjectId() { return projectId; }
    public void setProjectId(UUID projectId) { this.projectId = projectId; }
    public String getCodeHash() { return codeHash; }
    public void setCodeHash(String codeHash) { this.codeHash = codeHash; }
    public String getInvitedEmail() { return invitedEmail; }
    public void setInvitedEmail(String invitedEmail) { this.invitedEmail = invitedEmail; }
    public ProjectRole getRole() { return role; }
    public void setRole(ProjectRole role) { this.role = role; }
    public InvitationStatus getStatus() { return status; }
    public void setStatus(InvitationStatus status) { this.status = status; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(OffsetDateTime expiresAt) { this.expiresAt = expiresAt; }
    public UUID getAcceptedBy() { return acceptedBy; }
    public void setAcceptedBy(UUID acceptedBy) { this.acceptedBy = acceptedBy; }
    public OffsetDateTime getAcceptedAt() { return acceptedAt; }
    public void setAcceptedAt(OffsetDateTime acceptedAt) { this.acceptedAt = acceptedAt; }
    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
