package com.shitulelv.aicollab.system.infrastructure.repository;

import com.shitulelv.aicollab.system.domain.model.SystemConfig;
import com.shitulelv.aicollab.system.domain.model.SystemConfigAudit;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SystemConfigRepository {
    Optional<SystemConfig> findCurrent();
    void saveInitial(SystemConfig config);
    boolean updateWithVersion(SystemConfig config, long expectedVersion);
    void saveAudit(SystemConfigAudit audit);
    List<SystemConfigAudit> listRecentAudits(int limit);
    Optional<SystemConfigAudit> findAuditById(UUID id);
}
