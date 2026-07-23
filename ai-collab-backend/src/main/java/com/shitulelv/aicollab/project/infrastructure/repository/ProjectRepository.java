package com.shitulelv.aicollab.project.infrastructure.repository;

import com.shitulelv.aicollab.project.application.view.ProjectView;
import com.shitulelv.aicollab.project.domain.model.ProjectStatus;
import com.shitulelv.aicollab.project.infrastructure.entity.ProjectEntity;
import com.shitulelv.aicollab.project.infrastructure.mapper.ProjectMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ProjectRepository {
    private final ProjectMapper mapper;

    public ProjectRepository(ProjectMapper mapper) {
        this.mapper = mapper;
    }

    public void create(ProjectEntity entity) {
        mapper.insert(entity);
    }

    public List<ProjectView> listForUser(UUID userId) {
        return mapper.listForUser(userId);
    }

    public Optional<ProjectView> findForMember(UUID projectId, UUID userId) {
        return mapper.findForMember(projectId, userId);
    }

    public Optional<String> findNameById(UUID projectId) {
        return mapper.findNameById(projectId);
    }

    public boolean updateWithVersion(
            UUID projectId,
            String name,
            String description,
            LocalDate startDate,
            LocalDate dueDate,
            ProjectStatus status,
            int version) {
        return mapper.updateWithVersion(projectId, name, description, startDate, dueDate, status, version) == 1;
    }

    public boolean delete(UUID projectId) {
        return mapper.deleteById(projectId) == 1;
    }
}
