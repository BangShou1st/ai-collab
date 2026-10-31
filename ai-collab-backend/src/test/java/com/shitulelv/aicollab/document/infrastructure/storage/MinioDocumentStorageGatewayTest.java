package com.shitulelv.aicollab.document.infrastructure.storage;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.infrastructure.storage.MinioProperties;
import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.GetObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.ErrorResponse;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class MinioDocumentStorageGatewayTest {

    @Test
    void productionAccountDoesNotManageBucketPolicy() {
        MinioClient client = mock(MinioClient.class);
        MinioDocumentStorageGateway gateway = new MinioDocumentStorageGateway(client,
                new MinioProperties("http://minio:9000", "access", "secret", "documents", "", false));
        gateway.ensurePrivateBucket();
        verifyNoInteractions(client);
    }

    @Test
    void publicProxyPrefixPreservesSignedPathAndQuery() {
        String signed = "https://collab.example.org/documents/a%20b.txt?X-Amz-Signature=synthetic";
        assertEquals("https://collab.example.org/storage/documents/a%20b.txt?X-Amz-Signature=synthetic",
                MinioDocumentStorageGateway.addProxyPrefix(signed));
    }

    @Test
    void publicSigningChecksObjectInternallyWithoutCallingExternalHost() throws Exception {
        MinioClient client = mock(MinioClient.class);
        MinioDocumentStorageGateway gateway = new MinioDocumentStorageGateway(client,
                new MinioProperties("http://minio:9000", "access", "secret", "documents",
                        "https://collab.example.org", false));
        String url = gateway.presign("projects/synthetic/source.txt", "report.txt", Duration.ofMinutes(5)).url();
        assertTrue(url.startsWith("https://collab.example.org/storage/documents/projects/synthetic/source.txt?"));
        assertTrue(url.contains("X-Amz-Signature="));
        verify(client).statObject(any(StatObjectArgs.class));
    }

    @Test
    void invalidPublicOriginIsAConfigurationFailureWithoutEchoingItsValue() {
        MinioClient client = mock(MinioClient.class);
        MinioDocumentStorageGateway gateway = new MinioDocumentStorageGateway(client,
                new MinioProperties("http://minio:9000", "access", "secret", "documents",
                        "https://one.example.org,https://two.example.org", false));
        BusinessException failure = assertThrows(BusinessException.class,
                () -> gateway.presign("object-key", "report.txt", Duration.ofMinutes(5)));
        assertEquals(ErrorCode.DOCUMENT_STORAGE_CONFIGURATION_ERROR, failure.getErrorCode());
        assertTrue(!failure.getMessage().contains("example.org"));
        verifyNoInteractions(client);
    }

    @Test
    void signingFailureIsNotReportedAsNetworkFailure() {
        MinioDocumentStorageGateway gateway = gateway(mock(MinioClient.class));
        BusinessException failure = assertThrows(BusinessException.class,
                () -> gateway.presign("object-key", "report.txt", Duration.ofSeconds((long) Integer.MAX_VALUE + 1)));
        assertEquals(ErrorCode.DOCUMENT_STORAGE_SIGNING_FAILED, failure.getErrorCode());
    }

    @Test
    void storageAuthenticationAndMissingObjectHaveDifferentErrors() throws Exception {
        MinioClient client = mock(MinioClient.class);
        ErrorResponseException denied = mock(ErrorResponseException.class);
        ErrorResponseException missing = mock(ErrorResponseException.class);
        when(denied.errorResponse()).thenReturn(new ErrorResponse("InvalidAccessKeyId", "ignored", null, null, null, null, null));
        when(missing.errorResponse()).thenReturn(new ErrorResponse("NoSuchKey", "ignored", null, null, null, null, null));
        when(client.getObject(any(GetObjectArgs.class))).thenThrow(denied, missing);
        MinioDocumentStorageGateway gateway = gateway(client);
        assertEquals(ErrorCode.DOCUMENT_STORAGE_AUTHENTICATION_FAILED,
                assertThrows(BusinessException.class, () -> gateway.open("object-key")).getErrorCode());
        assertEquals(ErrorCode.DOCUMENT_STORAGE_OBJECT_NOT_FOUND,
                assertThrows(BusinessException.class, () -> gateway.open("object-key")).getErrorCode());
    }

    @Test
    void presignDistinguishesInternalCredentialsMissingObjectAndConnectionFailure() throws Exception {
        MinioClient client = mock(MinioClient.class);
        ErrorResponseException denied = mock(ErrorResponseException.class);
        ErrorResponseException missing = mock(ErrorResponseException.class);
        when(denied.errorResponse()).thenReturn(new ErrorResponse("InvalidAccessKeyId", "ignored", null, null, null, null, null));
        when(missing.errorResponse()).thenReturn(new ErrorResponse("NoSuchKey", "ignored", null, null, null, null, null));
        var wrappedDenied = new io.minio.errors.MinioException(denied);
        var wrappedMissing = new io.minio.errors.MinioException(missing);
        var wrappedOutage = new io.minio.errors.MinioException(new java.io.IOException("synthetic outage"));
        when(client.statObject(any(StatObjectArgs.class))).thenThrow(wrappedDenied, wrappedMissing, wrappedOutage);
        MinioDocumentStorageGateway gateway = gateway(client);
        assertEquals(ErrorCode.DOCUMENT_STORAGE_AUTHENTICATION_FAILED,
                assertThrows(BusinessException.class, () -> gateway.presign("object-key","file.txt",Duration.ofMinutes(5))).getErrorCode());
        assertEquals(ErrorCode.DOCUMENT_STORAGE_OBJECT_NOT_FOUND,
                assertThrows(BusinessException.class, () -> gateway.presign("object-key","file.txt",Duration.ofMinutes(5))).getErrorCode());
        assertEquals(ErrorCode.DOCUMENT_STORAGE_UNAVAILABLE,
                assertThrows(BusinessException.class, () -> gateway.presign("object-key","file.txt",Duration.ofMinutes(5))).getErrorCode());
    }

    @Test
    void unavailableMinioDoesNotAbortApplicationReadyEvent() throws Exception {
        MinioClient client = mock(MinioClient.class);
        when(client.bucketExists(any(BucketExistsArgs.class)))
                .thenThrow(new RuntimeException("storage unavailable"));
        MinioDocumentStorageGateway gateway = gateway(client);

        assertDoesNotThrow(gateway::ensurePrivateBucket);
    }

    @Test
    void unavailableMinioStillReturnsStableBusinessErrorForDocumentOperations() throws Exception {
        MinioClient client = mock(MinioClient.class);
        when(client.putObject(any(PutObjectArgs.class)))
                .thenThrow(new RuntimeException("storage unavailable"));
        MinioDocumentStorageGateway gateway = gateway(client);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> gateway.put(
                        "object-key",
                        new ByteArrayInputStream(new byte[] {1}),
                        1,
                        "text/plain"));

        assertEquals(ErrorCode.DOCUMENT_STORAGE_UNAVAILABLE, exception.getErrorCode());
        assertEquals(
                "无法连接文件存储服务，请稍后重试；若持续出现，请联系管理员检查 MinIO 服务",
                exception.getMessage());
    }

    private MinioDocumentStorageGateway gateway(MinioClient client) {
        return new MinioDocumentStorageGateway(
                client,
                new MinioProperties("http://localhost:9000", "access", "secret", "documents", "", true));
    }
}
