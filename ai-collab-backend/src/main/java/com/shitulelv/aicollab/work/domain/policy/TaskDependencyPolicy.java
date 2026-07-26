package com.shitulelv.aicollab.work.domain.policy;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class TaskDependencyPolicy {
    public void validateAcyclic(Set<UUID> nodes, Map<UUID, List<UUID>> dependencies) {
        Map<UUID, Integer> indegree = new HashMap<>();
        Map<UUID, List<UUID>> dependents = new HashMap<>();
        nodes.forEach(node -> {
            indegree.put(node, 0);
            dependents.put(node, new ArrayList<>());
        });
        dependencies.forEach((task, required) -> required.forEach(dependency -> {
            indegree.compute(task, (ignored, count) -> count == null ? 1 : count + 1);
            dependents.computeIfAbsent(dependency, ignored -> new ArrayList<>()).add(task);
        }));

        ArrayDeque<UUID> ready = new ArrayDeque<>();
        indegree.forEach((node, count) -> {
            if (count == 0) ready.add(node);
        });
        int processed = 0;
        while (!ready.isEmpty()) {
            UUID node = ready.removeFirst();
            processed++;
            for (UUID dependent : dependents.getOrDefault(node, List.of())) {
                int remaining = indegree.computeIfPresent(dependent, (ignored, count) -> count - 1);
                if (remaining == 0) ready.addLast(dependent);
            }
        }
        if (processed != nodes.size()) {
            throw new BusinessException(ErrorCode.TASK_DEPENDENCY_CYCLE);
        }
    }
}
