package com.shitulelv.aicollab.project.infrastructure.repository;

import com.shitulelv.aicollab.project.application.view.MemberView;
import com.shitulelv.aicollab.project.domain.model.ProjectRole;
import com.shitulelv.aicollab.project.infrastructure.mapper.ProjectMemberMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ProjectMemberRepository {
    private final ProjectMemberMapper mapper;

    public ProjectMemberRepository(ProjectMemberMapper mapper) {
        this.mapper = mapper;
    }

    public void create(UUID projectId, UUID userId, ProjectRole role, UUID invitedBy) {
        mapper.insertMember(projectId, userId, role, invitedBy);
    }

    public boolean createIfAbsent(UUID projectId, UUID userId, ProjectRole role, UUID invitedBy) {
        return mapper.insertMemberIfAbsent(projectId, userId, role, invitedBy) == 1;
    }

    public Optional<ProjectRole> findRole(UUID projectId, UUID userId) {
        return mapper.findRole(projectId, userId);
    }

    public List<MemberView> list(UUID projectId) {
        return mapper.listMembers(projectId);
    }

    public Optional<MemberView> find(UUID projectId, UUID userId) {
        return mapper.findMember(projectId, userId);
    }

    public boolean changeNonOwnerRole(UUID projectId, UUID userId, ProjectRole role) {
        return mapper.updateNonOwnerRole(projectId, userId, role) == 1;
    }

    public boolean removeNonOwner(UUID projectId, UUID userId) {
        return mapper.deleteNonOwner(projectId, userId) == 1;
    }
}
