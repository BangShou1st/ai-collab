package com.shitulelv.aicollab.work.infrastructure.repository;

import com.shitulelv.aicollab.work.domain.model.TaskDependencyEdge;
import com.shitulelv.aicollab.work.domain.model.TaskStatus;
import com.shitulelv.aicollab.work.infrastructure.entity.TaskEntity;
import com.shitulelv.aicollab.work.infrastructure.mapper.TaskMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class TaskRepository {
    private final TaskMapper mapper;
    public TaskRepository(TaskMapper mapper) { this.mapper = mapper; }
    public List<TaskEntity> list(UUID projectId, TaskStatus status, UUID assigneeId, UUID milestoneId) {
        return mapper.listScoped(projectId, status, assigneeId, milestoneId);
    }
    public PageTasks page(UUID projectId, TaskStatus status, UUID assigneeId, UUID milestoneId,
                          int limit, long offset) {
        List<TaskEntity> rows = mapper.listPageScoped(projectId, status, assigneeId, milestoneId, limit, offset);
        if (rows.isEmpty()) {
            return new PageTasks(List.of(), 0);
        }
        long total = rows.getFirst().getTotalCount() == null ? 0 : rows.getFirst().getTotalCount();
        List<TaskEntity> items = rows.getFirst().getId() == null ? List.of() : rows;
        return new PageTasks(items, total);
    }
    public Optional<TaskEntity> find(UUID projectId, UUID id) { return mapper.findScoped(projectId, id); }
    public void create(TaskEntity entity) { mapper.insert(entity); }
    public boolean update(UUID projectId, TaskEntity entity) { return mapper.updateScoped(projectId, entity) == 1; }
    public boolean delete(UUID projectId, UUID id) { return mapper.deleteScoped(projectId, id) == 1; }
    public List<UUID> listIds(UUID projectId) { return mapper.listIds(projectId); }
    public List<UUID> lockProjectTaskIds(UUID projectId) { return mapper.lockProjectTaskIds(projectId); }
    public List<TaskDependencyEdge> listEdges(UUID projectId) { return mapper.listEdges(projectId); }
    public List<UUID> dependencyIds(UUID projectId, UUID taskId) {
        return mapper.listDependencyIds(projectId, taskId);
    }
    public Map<UUID, List<UUID>> dependencyIdsByTask(UUID projectId, List<UUID> taskIds) {
        if (taskIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<UUID>> result = new LinkedHashMap<>();
        for (int start = 0; start < taskIds.size(); start += 500) {
            int end = Math.min(start + 500, taskIds.size());
            for (TaskDependencyEdge edge : mapper.listDependencyEdges(projectId, taskIds.subList(start, end))) {
                result.computeIfAbsent(edge.taskId(), ignored -> new java.util.ArrayList<>())
                        .add(edge.dependsOnTaskId());
            }
        }
        return result;
    }
    public void replaceDependencies(UUID projectId, UUID taskId, List<UUID> dependencyIds) {
        mapper.deleteDependencies(projectId, taskId);
        for (UUID dependencyId : dependencyIds) {
            if (mapper.insertDependency(projectId, taskId, dependencyId) != 1) {
                throw new IllegalStateException("任务依赖写入失败");
            }
        }
    }
    public List<TaskEntity> findDependents(UUID projectId, UUID taskId) {
        return mapper.findDependents(projectId, taskId);
    }

    public record PageTasks(List<TaskEntity> items, long total) {
    }
}
