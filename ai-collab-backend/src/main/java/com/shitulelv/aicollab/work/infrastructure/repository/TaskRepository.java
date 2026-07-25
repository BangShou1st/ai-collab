package com.shitulelv.aicollab.work.infrastructure.repository;

import com.shitulelv.aicollab.work.domain.model.TaskDependencyEdge;
import com.shitulelv.aicollab.work.domain.model.TaskStatus;
import com.shitulelv.aicollab.work.infrastructure.entity.TaskEntity;
import com.shitulelv.aicollab.work.infrastructure.mapper.TaskMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class TaskRepository {
    private final TaskMapper mapper;
    public TaskRepository(TaskMapper mapper) { this.mapper = mapper; }
    public List<TaskEntity> list(UUID projectId, TaskStatus status, UUID assigneeId, UUID milestoneId) {
        return mapper.listScoped(projectId, status, assigneeId, milestoneId);
    }
    public Optional<TaskEntity> find(UUID projectId, UUID id) { return mapper.findScoped(projectId, id); }
    public void create(TaskEntity entity) { mapper.insert(entity); }
    public boolean update(UUID projectId, TaskEntity entity) { return mapper.updateScoped(projectId, entity) == 1; }
    public boolean delete(UUID projectId, UUID id) { return mapper.deleteScoped(projectId, id) == 1; }
    public List<UUID> listIds(UUID projectId) { return mapper.listIds(projectId); }
    public List<TaskDependencyEdge> listEdges(UUID projectId) { return mapper.listEdges(projectId); }
    public List<UUID> dependencyIds(UUID projectId, UUID taskId) {
        return mapper.listDependencyIds(projectId, taskId);
    }
    public void replaceDependencies(UUID projectId, UUID taskId, List<UUID> dependencyIds) {
        mapper.deleteDependencies(projectId, taskId);
        for (UUID dependencyId : dependencyIds) {
            if (mapper.insertDependency(projectId, taskId, dependencyId) != 1) {
                throw new IllegalStateException("任务依赖写入失败");
            }
        }
    }
}
