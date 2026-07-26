package com.shitulelv.aicollab.work.infrastructure.repository;

import com.shitulelv.aicollab.work.infrastructure.entity.TaskCommentEntity;
import com.shitulelv.aicollab.work.infrastructure.mapper.TaskCommentMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class TaskCommentRepository {
    private final TaskCommentMapper mapper;
    public TaskCommentRepository(TaskCommentMapper mapper) { this.mapper = mapper; }
    public List<TaskCommentEntity> list(UUID projectId, UUID taskId) {
        return mapper.listScoped(projectId, taskId);
    }
    public Optional<TaskCommentEntity> find(UUID projectId, UUID taskId, UUID commentId) {
        return mapper.findScoped(projectId, taskId, commentId);
    }
    public void create(TaskCommentEntity entity) { mapper.insert(entity); }
    public boolean updateByAuthor(UUID projectId, UUID taskId, UUID commentId, UUID authorId, String content) {
        return mapper.updateByAuthor(projectId, taskId, commentId, authorId, content) == 1;
    }
    public boolean delete(UUID projectId, UUID taskId, UUID commentId) {
        return mapper.deleteScoped(projectId, taskId, commentId) == 1;
    }
}
