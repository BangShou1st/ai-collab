package com.shitulelv.aicollab.connector.application;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.connector.infrastructure.feishu.FeishuApiException;
import com.shitulelv.aicollab.connector.infrastructure.feishu.FeishuClient;
import com.shitulelv.aicollab.connector.infrastructure.feishu.FeishuFile;
import com.shitulelv.aicollab.connector.infrastructure.persistence.ConnectorConnectionMapper;
import com.shitulelv.aicollab.connector.infrastructure.persistence.ConnectorRows;
import com.shitulelv.aicollab.connector.infrastructure.persistence.ConnectorSourceMapper;
import com.shitulelv.aicollab.connector.infrastructure.persistence.ConnectorSyncJobMapper;
import com.shitulelv.aicollab.connector.infrastructure.persistence.ExternalDocumentMapper;
import com.shitulelv.aicollab.connector.infrastructure.persistence.ExternalDocumentRevisionMapper;
import com.shitulelv.aicollab.document.application.service.DocumentRegistrationService;
import com.shitulelv.aicollab.document.application.service.DocumentTaskDispatcher;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentRepository;
import com.shitulelv.aicollab.document.infrastructure.storage.DocumentStorageGateway;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelSecretCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.task.TaskExecutor;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConnectorSyncServiceTest {

    @Mock ConnectorSourceMapper sources;
    @Mock ConnectorConnectionMapper connections;
    @Mock ConnectorSyncJobMapper jobs;
    @Mock ExternalDocumentMapper externalDocs;
    @Mock ExternalDocumentRevisionMapper revisions;
    @Mock FeishuClient feishu;
    @Mock ModelSecretCipher secrets;
    @Mock DocumentRepository documents;
    @Mock DocumentRegistrationService registration;
    @Mock DocumentStorageGateway storage;
    @Mock DocumentTaskDispatcher dispatcher;

    private final UUID projectId = UUID.randomUUID();
    private final UUID sourceId = UUID.randomUUID();
    private final UUID connectionId = UUID.randomUUID();

    private ConnectorSyncService service;

    @BeforeEach
    void setUp() {
        TaskExecutor directExecutor = Runnable::run;
        service = new ConnectorSyncService(sources, connections, jobs, externalDocs, revisions,
                feishu, secrets, documents, registration, storage, dispatcher, directExecutor);
    }

    private ConnectorRows.SourceRow source() {
        ConnectorRows.SourceRow row = new ConnectorRows.SourceRow();
        row.id = sourceId;
        row.projectId = projectId;
        row.connectionId = connectionId;
        row.folderToken = "fld123";
        row.status = "ACTIVE";
        row.authorizationDeclaredBy = UUID.randomUUID();
        return row;
    }

    private ConnectorRows.ConnectionRow connection() {
        ConnectorRows.ConnectionRow row = new ConnectorRows.ConnectionRow();
        row.id = connectionId;
        row.appId = "cli_test";
        row.appSecretCipher = "cipher";
        row.status = "ACTIVE";
        return row;
    }

    private ConnectorRows.ExternalDocRow knownDoc(String externalKey, Long mtime, UUID documentId) {
        ConnectorRows.ExternalDocRow row = new ConnectorRows.ExternalDocRow();
        row.id = UUID.randomUUID();
        row.sourceId = sourceId;
        row.projectId = projectId;
        row.externalKey = externalKey;
        row.docType = "docx";
        row.title = "旧标题";
        row.latestModifyTime = mtime;
        row.contentHash = null;
        row.documentId = documentId;
        row.status = "ACTIVE";
        row.documentStatus = "READY";
        return row;
    }

    private void stubHappyPath() {
        when(sources.find(sourceId)).thenReturn(source());
        when(connections.find(connectionId)).thenReturn(connection());
        when(secrets.decrypt("cipher")).thenReturn("secret");
        when(feishu.tenantAccessToken(anyString(), anyString())).thenReturn("tok");
        when(feishu.listFolderDocx(anyString(), eq("fld123"))).thenReturn(List.of());
    }

    @Test
    void triggerRejectsConcurrentSyncWhenLeaseAlive() {
        when(sources.find(sourceId)).thenReturn(source());
        when(jobs.findRunning(sourceId)).thenReturn(runningJob());

        assertThatThrownBy(() -> service.trigger(sourceId, "MANUAL"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONNECTOR_SYNC_ALREADY_RUNNING);
    }

    @Test
    void firstSyncRegistersDocumentThroughManualPipeline() {
        stubHappyPath();
        when(feishu.listFolderDocx(anyString(), eq("fld123"))).thenReturn(List.of(
                new FeishuFile("docToken1", "项目A", "docx", "https://feishu/docx/docToken1", 1000L)));
        when(feishu.docxRawContent(anyString(), eq("docToken1"))).thenReturn("正文内容");
        when(externalDocs.listBySource(sourceId)).thenReturn(List.of());

        UUID jobId = service.trigger(sourceId, "MANUAL");

        verify(registration).registerUploadedDocument(any());
        verify(externalDocs).insert(any(), eq(sourceId), eq("docToken1"), eq("docx"),
                eq("项目A"), eq("https://feishu/docx/docToken1"), eq(1000L), anyString(), any());
        verify(revisions).insert(any(), any(), eq(1), eq(1000L), anyString(), any(), eq("ACTIVE"));
        verify(jobs).finish(eq(jobId), eq("SUCCESS"), eq(1), eq(1), eq(0), eq(0), eq(0), any());
    }

    @Test
    void unchangedModifyTimeSkipsContentFetch() {
        stubHappyPath();
        UUID documentId = UUID.randomUUID();
        when(feishu.listFolderDocx(anyString(), eq("fld123"))).thenReturn(List.of(
                new FeishuFile("docToken1", "项目A", "docx", null, 1000L)));
        when(externalDocs.listBySource(sourceId)).thenReturn(List.of(knownDoc("docToken1", 1000L, documentId)));

        service.trigger(sourceId, "MANUAL");

        verify(feishu, never()).docxRawContent(anyString(), anyString());
        verify(documents, never()).resetForReindex(any(), any());
    }

    @Test
    void changedModifyTimeButSameContentOnlyRefreshesMetadata() {
        stubHappyPath();
        UUID documentId = UUID.randomUUID();
        ConnectorRows.ExternalDocRow known = knownDoc("docToken1", 1000L, documentId);
        String content = "正文内容";
        known.contentHash = ConnectorSyncServiceTestAccess.sha256(content);
        when(feishu.listFolderDocx(anyString(), eq("fld123"))).thenReturn(List.of(
                new FeishuFile("docToken1", "项目A", "docx", null, 2000L)));
        when(externalDocs.listBySource(sourceId)).thenReturn(List.of(known));
        when(feishu.docxRawContent(anyString(), eq("docToken1"))).thenReturn(content);

        service.trigger(sourceId, "MANUAL");

        verify(documents, never()).resetForReindex(any(), any());
        verify(externalDocs).updateContent(known.id, "项目A", null, 2000L, known.contentHash);
    }

    @Test
    void contentChangeTriggersReindexAndDispatch() {
        stubHappyPath();
        UUID documentId = UUID.randomUUID();
        when(feishu.listFolderDocx(anyString(), eq("fld123"))).thenReturn(List.of(
                new FeishuFile("docToken1", "项目A", "docx", null, 2000L)));
        when(externalDocs.listBySource(sourceId)).thenReturn(List.of(knownDoc("docToken1", 1000L, documentId)));
        when(feishu.docxRawContent(anyString(), eq("docToken1"))).thenReturn("新正文");
        when(documents.resetForReindex(projectId, documentId)).thenReturn(true);

        service.trigger(sourceId, "MANUAL");

        verify(documents).resetForReindex(projectId, documentId);
        verify(dispatcher).dispatch(projectId, documentId);
        verify(revisions).supersedeActive(any());
    }

    @Test
    void missingFromCompleteListingIsRemovedImmediately() {
        stubHappyPath();
        UUID documentId = UUID.randomUUID();
        when(externalDocs.listBySource(sourceId)).thenReturn(List.of(knownDoc("docGone", 1000L, documentId)));

        service.trigger(sourceId, "MANUAL");

        ArgumentCaptor<UUID> removed = ArgumentCaptor.forClass(UUID.class);
        verify(documents).markDeleting(projectId, documentId);
        verify(documents).deleteRows(eq(projectId), removed.capture());
        assertThat(removed.getValue()).isEqualTo(documentId);
        verify(jobs).finish(any(), eq("SUCCESS"), eq(0), eq(0), eq(0), eq(0), eq(1), any());
    }

    @Test
    void incompleteScanNeverDeletesDocuments() {
        stubHappyPath();
        UUID documentId = UUID.randomUUID();
        when(externalDocs.listBySource(sourceId)).thenReturn(List.of(knownDoc("docGone", 1000L, documentId)));
        when(feishu.listFolderDocx(anyString(), eq("fld123")))
                .thenThrow(new FeishuApiException(-1, "连接飞书服务失败"));

        service.trigger(sourceId, "MANUAL");

        verify(documents, never()).deleteRows(any(), any());
        verify(externalDocs, never()).updateStatus(any(), eq("REMOVED"));
        verify(jobs).finish(any(), eq("FAILED"), eq(0), eq(0), eq(0), eq(0), eq(0), anyString());
    }

    @Test
    void forbiddenListingBlocksSourceAndRemovesSearchableDocs() {
        stubHappyPath();
        UUID documentId = UUID.randomUUID();
        when(externalDocs.listBySource(sourceId)).thenReturn(List.of(knownDoc("docToken1", 1000L, documentId)));
        when(feishu.listFolderDocx(anyString(), eq("fld123")))
                .thenThrow(new FeishuApiException(1061004, "forbidden"));

        service.trigger(sourceId, "MANUAL");

        verify(documents).markDeleting(projectId, documentId);
        verify(documents).deleteRows(projectId, documentId);
        verify(externalDocs).updateStatus(any(), eq("BLOCKED"));
        verify(sources).updateStatus(sourceId, "BLOCKED", null);
        verify(jobs).finish(any(), eq("FAILED"), eq(0), eq(0), eq(0), eq(0), eq(0), anyString());
    }

    @Test
    void documentStillProcessingIsCountedAsFailureNotRemoval() {
        stubHappyPath();
        UUID documentId = UUID.randomUUID();
        ConnectorRows.ExternalDocRow known = knownDoc("docToken1", 1000L, documentId);
        when(feishu.listFolderDocx(anyString(), eq("fld123"))).thenReturn(List.of(
                new FeishuFile("docToken1", "项目A", "docx", null, 2000L)));
        when(externalDocs.listBySource(sourceId)).thenReturn(List.of(known));
        when(feishu.docxRawContent(anyString(), eq("docToken1"))).thenReturn("新正文");
        when(documents.resetForReindex(projectId, documentId)).thenReturn(false);

        service.trigger(sourceId, "MANUAL");

        verify(dispatcher, never()).dispatch(any(), any());
        verify(jobs).finish(any(), eq("PARTIAL_FAILED"), eq(1), eq(0), eq(0), eq(1), eq(0), anyString());
    }

    @Test
    void blockedDocumentIsReactivatedAfterAccessRestored() {
        stubHappyPath();
        UUID documentId = UUID.randomUUID();
        ConnectorRows.ExternalDocRow blocked = knownDoc("docToken1", 2000L, documentId);
        blocked.status = "BLOCKED";
        blocked.documentStatus = null;
        when(feishu.listFolderDocx(anyString(), eq("fld123"))).thenReturn(List.of(
                new FeishuFile("docToken1", "项目A", "docx", null, 3000L)));
        when(externalDocs.listBySource(sourceId)).thenReturn(List.of(blocked));
        when(feishu.docxRawContent(anyString(), eq("docToken1"))).thenReturn("正文内容");
        when(revisions.maxRevisionNo(blocked.id)).thenReturn(2);

        service.trigger(sourceId, "MANUAL");

        verify(registration).registerUploadedDocument(any());
        verify(externalDocs).updateDocumentRef(eq(blocked.id), any(), eq("ACTIVE"), eq("项目A"), any());
        verify(revisions).insert(any(), eq(blocked.id), eq(3), eq(3000L), anyString(), any(), eq("ACTIVE"));
        verify(jobs).finish(any(), eq("SUCCESS"), eq(1), eq(1), eq(0), eq(0), eq(0), any());
    }

    private ConnectorRows.JobRow runningJob() {
        ConnectorRows.JobRow row = new ConnectorRows.JobRow();
        row.id = UUID.randomUUID();
        row.sourceId = sourceId;
        row.status = "RUNNING";
        return row;
    }
}
