package com.shitulelv.aicollab.system.infrastructure.repository;

import com.shitulelv.aicollab.system.domain.model.SystemConfig;
import com.shitulelv.aicollab.system.domain.model.SystemConfigAudit;
import com.shitulelv.aicollab.system.infrastructure.entity.SystemConfigAuditEntity;
import com.shitulelv.aicollab.system.infrastructure.entity.SystemConfigEntity;
import com.shitulelv.aicollab.system.infrastructure.mapper.SystemConfigAuditMapper;
import com.shitulelv.aicollab.system.infrastructure.mapper.SystemConfigMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class SystemConfigRepositoryImpl implements SystemConfigRepository {
    private final SystemConfigMapper configMapper;
    private final SystemConfigAuditMapper auditMapper;

    public SystemConfigRepositoryImpl(SystemConfigMapper configMapper, SystemConfigAuditMapper auditMapper) {
        this.configMapper = configMapper;
        this.auditMapper = auditMapper;
    }

    @Override
    public Optional<SystemConfig> findCurrent() {
        SystemConfigEntity entity = configMapper.selectById(SystemConfig.DEFAULT_ID);
        return Optional.ofNullable(entity).map(SystemConfigEntity::toDomain);
    }

    @Override
    public void saveInitial(SystemConfig config) {
        configMapper.insert(SystemConfigEntity.fromDomain(config));
    }

    @Override
    public boolean updateWithVersion(SystemConfig config, long expectedVersion) {
        return configMapper.updateWithOptimisticLock(SystemConfigEntity.fromDomain(config), expectedVersion) > 0;
    }

    @Override
    public void saveAudit(SystemConfigAudit audit) {
        auditMapper.insertWithJsonb(SystemConfigAuditEntity.fromDomain(audit));
    }

    @Override
    public List<SystemConfigAudit> listRecentAudits(int limit) {
        return auditMapper.selectRecent(Math.min(Math.max(limit, 1), 100))
                .stream()
                .map(SystemConfigAuditEntity::toDomain)
                .toList();
    }

    @Override
    public Optional<SystemConfigAudit> findAuditById(UUID id) {
        SystemConfigAuditEntity entity = auditMapper.selectByIdCustom(id);
        return Optional.ofNullable(entity).map(SystemConfigAuditEntity::toDomain);
    }
}
