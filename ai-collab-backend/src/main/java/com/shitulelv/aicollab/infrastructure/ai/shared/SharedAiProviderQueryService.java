package com.shitulelv.aicollab.infrastructure.ai.shared;

import com.shitulelv.aicollab.infrastructure.ai.shared.api.SharedAiProviderView;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class SharedAiProviderQueryService {
    private final SharedAiProviderRepository repository;

    public SharedAiProviderQueryService(SharedAiProviderRepository repository) {
        this.repository = repository;
    }

    public List<SharedAiProviderView> listPublished() {
        return repository.findAllPublished().stream()
                .map(SharedAiProviderView::from)
                .toList();
    }

    public Optional<SharedAiProvider> findPublishedById(UUID id) {
        return repository.findById(id).filter(SharedAiProvider::isPublished);
    }

    public Optional<SharedAiProvider> findById(UUID id) {
        return repository.findById(id);
    }
}
