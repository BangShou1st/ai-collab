package com.shitulelv.aicollab.project.application.service;

import com.shitulelv.aicollab.project.infrastructure.mapper.AuditLogMapper;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class AuditService {
    private final AuditLogMapper mapper;

    public AuditService(AuditLogMapper mapper) {
        this.mapper = mapper;
    }

    public void write(UUID projectId, UUID userId, String action, String entityType, UUID entityId) {
        mapper.insert(UUID.randomUUID(), projectId, userId, action, entityType, entityId, UUID.randomUUID());
    }
}
