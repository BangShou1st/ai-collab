package com.shitulelv.aicollab.work.infrastructure;

import com.shitulelv.aicollab.work.domain.model.TaskDependencyEdge;
import com.shitulelv.aicollab.work.infrastructure.mapper.TaskMapper;
import com.shitulelv.aicollab.work.infrastructure.repository.TaskRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TaskRepositoryFailureInjectionTest {

    private final TaskMapper mapper = mock(TaskMapper.class);
    private final TaskRepository repository = new TaskRepository(mapper);
    private final UUID projectId = UUID.randomUUID();

    @Test
    void pageQueryFailureIsNotConvertedToAnEmptySuccessfulPage() {
        when(mapper.listPageScoped(eq(projectId), eq(null), eq(null), eq(null), eq(50), eq(0L)))
                .thenThrow(new DataAccessResourceFailureException("injected page failure"));

        assertThatThrownBy(() -> repository.page(projectId, null, null, null, 50, 0))
                .isInstanceOf(DataAccessResourceFailureException.class)
                .hasMessageContaining("injected page failure");
    }

    @Test
    void secondDependencyChunkFailureIsNotPartiallyReturned() {
        List<UUID> taskIds = IntStream.range(0, 501)
                .mapToObj(index -> UUID.randomUUID())
                .toList();
        when(mapper.listDependencyEdges(eq(projectId), anyList()))
                .thenReturn(List.of(new TaskDependencyEdge(taskIds.get(1), taskIds.get(0))))
                .thenThrow(new DataAccessResourceFailureException("injected dependency chunk failure"));

        assertThatThrownBy(() -> repository.dependencyIdsByTask(projectId, taskIds))
                .isInstanceOf(DataAccessResourceFailureException.class)
                .hasMessageContaining("injected dependency chunk failure");
    }
}
