package com.shitulelv.aicollab.project.domain.model;

public enum ProjectRole {
    OWNER, ADMIN, MEMBER;

    public boolean isAdminOrOwner() {
        return this == OWNER || this == ADMIN;
    }
}
