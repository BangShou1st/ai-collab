package com.shitulelv.aicollab.home.infrastructure.repository;

import com.shitulelv.aicollab.home.application.view.HomeActivityView;
import com.shitulelv.aicollab.home.application.view.HomeProjectView;
import com.shitulelv.aicollab.home.application.view.HomeTaskView;
import com.shitulelv.aicollab.home.infrastructure.mapper.HomeMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public class HomeRepository {
    private final HomeMapper mapper;

    public HomeRepository(HomeMapper mapper) {
        this.mapper = mapper;
    }

    public List<HomeProjectView> recentProjects(UUID userId, int limit) {
        return mapper.recentProjects(userId, limit);
    }

    public List<HomeTaskView> myTasks(UUID userId, int limit) {
        return mapper.myTasks(userId, limit);
    }

    public int pendingApprovals(UUID userId) {
        return mapper.pendingApprovals(userId);
    }

    public List<HomeActivityView> recentActivity(UUID userId, int limit) {
        return mapper.recentActivity(userId, limit);
    }
}
