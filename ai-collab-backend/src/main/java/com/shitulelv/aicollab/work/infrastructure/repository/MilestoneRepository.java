package com.shitulelv.aicollab.work.infrastructure.repository;

import com.shitulelv.aicollab.work.infrastructure.entity.MilestoneEntity;
import com.shitulelv.aicollab.work.infrastructure.mapper.MilestoneMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class MilestoneRepository {
    private final MilestoneMapper mapper;
    public MilestoneRepository(MilestoneMapper mapper) { this.mapper = mapper; }
    public List<MilestoneEntity> list(UUID projectId) { return mapper.listByProject(projectId); }
    public Optional<MilestoneEntity> find(UUID projectId, UUID id) { return mapper.findScoped(projectId, id); }
    public void create(MilestoneEntity entity) { mapper.insert(entity); }
    public boolean update(UUID projectId, MilestoneEntity entity) { return mapper.updateScoped(projectId, entity) == 1; }
    public boolean delete(UUID projectId, UUID id) { return mapper.deleteScoped(projectId, id) == 1; }
}
