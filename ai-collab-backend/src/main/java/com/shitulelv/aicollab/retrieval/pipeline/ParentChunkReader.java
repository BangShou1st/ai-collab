package com.shitulelv.aicollab.retrieval.pipeline;

import com.shitulelv.aicollab.document.application.view.DocumentSearchHit;

import java.util.Optional;
import java.util.UUID;

/**
 * 父分块读取接口，用于按父块 ID 回读父级完整证据内容。
 */
@FunctionalInterface
public interface ParentChunkReader {

    /**
     * 根据项目 ID 与父分块 ID 获取分块信息。
     *
     * @param projectId     项目 ID
     * @param parentChunkId 父分块 ID
     * @return 父分块 Hit 信息
     */
    Optional<DocumentSearchHit> findParentChunk(UUID projectId, UUID parentChunkId);
}
