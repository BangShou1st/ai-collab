package com.shitulelv.aicollab.document.infrastructure.storage;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.infrastructure.storage.MinioProperties;
import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
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
    void publicSigningUsesConfiguredRegionWithoutCallingExternalHost() {
        MinioClient client = mock(MinioClient.class);
        MinioDocumentStorageGateway gateway = new MinioDocumentStorageGateway(client,
                new MinioProperties("http://minio:9000", "access", "secret", "documents",
                        "https://collab.example.org", false));
        String url = gateway.presign("projects/synthetic/source.txt", "report.txt", Duration.ofMinutes(5)).url();
        assertTrue(url.startsWith("https://collab.example.org/storage/documents/projects/synthetic/source.txt?"));
        assertTrue(url.contains("X-Amz-Signature="));
        verifyNoInteractions(client);
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
