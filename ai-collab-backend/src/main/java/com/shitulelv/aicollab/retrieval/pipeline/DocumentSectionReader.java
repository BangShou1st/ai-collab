package com.shitulelv.aicollab.retrieval.pipeline;

import java.util.Optional;
import java.util.UUID;

/**
 * 文档章节/正文内容读取器接口，用于句子窗口恢复与父子块回读。
 */
@FunctionalInterface
public interface DocumentSectionReader {

    /**
     * 读取指定文档在指定版本和章节下的完整文本内容。
     *
     * @param projectId      项目 ID
     * @param documentId     文档 ID
     * @param heading        章节标题（null 表示文档开头或无标题节）
     * @param bodySnapshotId 文档正文快照版本 ID
     * @return 章节正文内容（如果存在）
     */
    Optional<String> getSectionText(UUID projectId, UUID documentId, String heading, String bodySnapshotId);
}
