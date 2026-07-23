package com.shitulelv.aicollab.project.infrastructure.repository;

import com.shitulelv.aicollab.project.infrastructure.entity.ProjectInvitationEntity;
import com.shitulelv.aicollab.project.infrastructure.mapper.ProjectInvitationMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ProjectInvitationRepository {
    private final ProjectInvitationMapper mapper;

    public ProjectInvitationRepository(ProjectInvitationMapper mapper) {
        this.mapper = mapper;
    }

    public void create(ProjectInvitationEntity entity) {
        mapper.insert(entity);
    }

    public Optional<ProjectInvitationEntity> findByHash(String codeHash) {
        return mapper.findByCodeHash(codeHash);
    }

    public Optional<ProjectInvitationEntity> findByHashForUpdate(String codeHash) {
        return mapper.findByCodeHashForUpdate(codeHash);
    }

    public boolean markAccepted(UUID id, UUID userId, OffsetDateTime acceptedAt) {
        return mapper.markAccepted(id, userId, acceptedAt) == 1;
    }
}
