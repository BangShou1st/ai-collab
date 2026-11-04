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
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
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
    @Mock ConnectorOAuthTokenService userTokens;
    @Mock ConnectorVisibilityService visibility;
    @Mock ConnectorSyncMetrics metrics;

    private final UUID projectId = UUID.randomUUID();
    private final UUID sourceId = UUID.randomUUID();
    private final UUID connectionId = UUID.randomUUID();

    private ConnectorSyncService service;

    @BeforeEach
    void setUp() {
        TaskExecutor directExecutor = Runnable::run;
        org.springframework.transaction.PlatformTransactionManager txManager =
                org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class);
        service = new ConnectorSyncService(sources, connections, jobs, externalDocs, revisions,
                feishu, secrets, documents, registration, storage, dispatcher, directExecutor, userTokens,
                visibility, txManager, metrics, 60000);
        when(jobs.renewLease(any())).thenReturn(1);
        when(jobs.lockIfOwned(any())).thenReturn(1);
        when(visibility.restoreSource(any(), any())).thenReturn(true);
        when(visibility.withActiveSource(any(), any(), any())).thenAnswer(invocation -> {
            invocation.<Runnable>getArgument(2).run();
            return true;
        });
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
    void dispatchRejectionCompensatesRunningJobAsFailed() {
        stubHappyPath();
        TaskExecutor rejectingExecutor = task -> {
            throw new org.springframework.core.task.TaskRejectedException("executor queue full");
        };
        ConnectorSyncService rejecting = new ConnectorSyncService(sources, connections, jobs, externalDocs,
                revisions, feishu, secrets, documents, registration, storage, dispatcher, rejectingExecutor,
                userTokens, visibility,
                org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class),
                metrics, 60000);

        assertThatThrownBy(() -> rejecting.trigger(sourceId, "MANUAL"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONNECTOR_SYNC_DISPATCH_FAILED);
        // 数据库不再残留无 Worker 的 RUNNING：立即收口为 FAILED + 派发失败摘要。
        verify(jobs).finish(any(), eq("FAILED"), eq(0), eq(0), eq(0), eq(0), eq(0),
                contains("派发失败"));
        verify(metrics).dispatchRejected();
        // 派发失败不等于执行失败，不得误标其余计数。
        verify(sources, never()).markSynced(any());
    }

    @Test
    void lostOwnershipAbortsScanWithoutFinishingOrMarkingSynced() throws Exception {
        stubHappyPath();
        when(feishu.listFolderDocx(anyString(), eq("fld123"))).thenReturn(List.of(
                new FeishuFile("docToken1", "项目A", "docx", "https://feishu/docx/docToken1", 1000L)));
        when(externalDocs.listBySource(sourceId)).thenReturn(List.of());
        // 心跳判定失权（renewLease 返回 0）：写入前的所有权检查应中止扫描。
        when(jobs.renewLease(any())).thenReturn(0);
        // 异步执行器 + 阻塞点：让心跳在扫描进行期间真实触发。
        CountDownLatch inScan = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(feishu.docxRawContent(anyString(), eq("docToken1"))).thenAnswer(invocation -> {
            inScan.countDown();
            release.await(10, java.util.concurrent.TimeUnit.SECONDS);
            return "正文内容";
        });
        java.util.concurrent.ExecutorService async = java.util.concurrent.Executors.newSingleThreadExecutor();
        ConnectorSyncService losing = new ConnectorSyncService(sources, connections, jobs, externalDocs,
                revisions, feishu, secrets, documents, registration, storage, dispatcher, async::execute,
                userTokens, visibility,
                org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class),
                metrics, 20);
        try {
            losing.trigger(sourceId, "MANUAL");
            assertThat(inScan.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            // 等待心跳真实触发过至少一次（renewLease 返回 0 → 失权已置位）再放行，
            // 保证写入前的所有权检查确定性中止。
            verify(jobs, org.mockito.Mockito.timeout(5000).atLeastOnce()).renewLease(any());
            release.countDown();
            // 失权中止后再断言。
            verify(metrics, org.mockito.Mockito.timeout(5000)).ownershipLost();
        } finally {
            async.shutdown();
        }

        verify(externalDocs, never()).insert(any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(registration, never()).registerUploadedDocument(any());
        verify(jobs, never()).finish(any(), anyString(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), any());
        verify(sources, never()).markSynced(any());
    }

    @Test
    void staleLocalFlagCannotBypassDatabaseOwnershipGate() {
        // D1.1-T04：本地失权标志尚未更新（心跳一切正常），但数据库已判失权
        //（租约过期并被接管）——Fencing 门必须阻止写入，先查后写不再可信。
        stubHappyPath();
        when(feishu.listFolderDocx(anyString(), eq("fld123"))).thenReturn(List.of(
                new FeishuFile("docToken1", "项目A", "docx", "https://feishu/docx/docToken1", 1000L)));
        when(feishu.docxRawContent(anyString(), eq("docToken1"))).thenReturn("正文内容");
        when(externalDocs.listBySource(sourceId)).thenReturn(List.of());
        when(jobs.renewLease(any())).thenReturn(1);
        when(jobs.lockIfOwned(any())).thenReturn(null);

        service.trigger(sourceId, "MANUAL");

        verify(registration, never()).registerUploadedDocument(any());
        verify(externalDocs, never()).insert(any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(jobs, never()).finish(any(), anyString(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), any());
        verify(sources, never()).markSynced(any());
        verify(metrics).ownershipLost();
    }

    @Test
    void onlyTheWorkerThatClosedTheRunningJobMarksSourceSynced() {
        stubHappyPath();
        UUID documentId = UUID.randomUUID();
        when(feishu.listFolderDocx(anyString(), eq("fld123"))).thenReturn(List.of(
                new FeishuFile("docToken1", "项目A", "docx", null, 1000L)));
        when(externalDocs.listBySource(sourceId)).thenReturn(List.of(knownDoc("docToken1", 1000L, documentId)));
        // finish 被数据库拒绝（0 行）：迟到收口不允许刷新 last_synced_at。
        when(jobs.finish(any(), anyString(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), any())).thenReturn(0);

        service.trigger(sourceId, "MANUAL");

        verify(sources, never()).markSynced(any());
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
        verify(revisions).insert(any(), any(), eq(1), eq(1000L), anyString(), any(), anyString(), eq("ACTIVE"));
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
        when(documents.resetForReindexWithSource(eq(projectId), eq(documentId), anyString(), anyLong())).thenReturn(true);

        service.trigger(sourceId, "MANUAL");

        verify(documents).resetForReindexWithSource(eq(projectId), eq(documentId), anyString(), anyLong());
        verify(dispatcher).dispatch(projectId, documentId);
        verify(revisions).supersedeActive(any());
    }

    @Test
    void missingFromCompleteListingIsRemovedImmediately() {
        stubHappyPath();
        UUID documentId = UUID.randomUUID();
        when(externalDocs.listBySource(sourceId)).thenReturn(List.of(knownDoc("docGone", 1000L, documentId)));

        service.trigger(sourceId, "MANUAL");

        verify(visibility).blockDocument(eq(projectId), eq(sourceId), any());
        verify(documents, never()).deleteRows(any(), any());
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
    void forbiddenListingBlocksSourceWithoutDeletingDocuments() {
        stubHappyPath();
        UUID documentId = UUID.randomUUID();
        when(externalDocs.listBySource(sourceId)).thenReturn(List.of(knownDoc("docToken1", 1000L, documentId)));
        when(feishu.listFolderDocx(anyString(), eq("fld123")))
                .thenThrow(new FeishuApiException(1061004, "forbidden"));

        service.trigger(sourceId, "MANUAL");

        verify(visibility).blockSource(projectId, sourceId, false);
        verify(documents, never()).deleteRows(any(), any());
        verify(jobs).finish(any(), eq("FAILED"), eq(0), eq(0), eq(0), eq(0), eq(0), anyString());
    }

    @Test
    void invalidUserTokenBlocksAllSourcesAndAttemptsRefreshWithoutDeletion() {
        stubHappyPath();
        UUID authorization = UUID.randomUUID();
        ConnectorRows.SourceRow source = source();
        source.userAuthorizationId = authorization;
        when(sources.find(sourceId)).thenReturn(source);
        when(userTokens.accessToken(projectId, authorization)).thenReturn("synthetic-user-token");
        when(feishu.listFolderDocx(anyString(), eq("fld123")))
                .thenThrow(new FeishuApiException(99991677, "expired"));

        service.trigger(sourceId, "MANUAL");

        verify(visibility).blockAuthorization(projectId, authorization, false);
        verify(userTokens).forceRefresh(projectId, authorization);
        verify(documents, never()).deleteRows(any(), any());
        verify(storage, never()).delete(any());
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
        ConnectorRows.ExternalDocRow blocked = knownDoc("docToken1", 3000L, documentId);
        blocked.status = "BLOCKED";
        blocked.documentStatus = null;
        blocked.contentHash = ConnectorSyncServiceTestAccess.sha256("正文内容");
        when(feishu.listFolderDocx(anyString(), eq("fld123"))).thenReturn(List.of(
                new FeishuFile("docToken1", "项目A", "docx", null, 3000L)));
        when(externalDocs.listBySource(sourceId)).thenReturn(List.of(blocked));
        when(feishu.docxRawContent(anyString(), eq("docToken1"))).thenReturn("正文内容");
        when(revisions.maxRevisionNo(blocked.id)).thenReturn(2);

        service.trigger(sourceId, "MANUAL");

        verify(registration).registerUploadedDocument(any());
        verify(externalDocs).updateDocumentRef(eq(blocked.id), any(), eq("ACTIVE"), eq("项目A"), any());
        verify(revisions).insert(any(), eq(blocked.id), eq(3), eq(3000L), anyString(), any(), anyString(), eq("ACTIVE"));
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
