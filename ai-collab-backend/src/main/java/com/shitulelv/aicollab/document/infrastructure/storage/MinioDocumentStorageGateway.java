package com.shitulelv.aicollab.document.infrastructure.storage;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.document.application.view.DownloadUrlView;
import com.shitulelv.aicollab.infrastructure.storage.MinioProperties;
import io.minio.BucketExistsArgs;
import io.minio.DeleteBucketPolicyArgs;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.Http.Method;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.errors.ErrorResponseException;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;

@Component
public class MinioDocumentStorageGateway implements DocumentStorageGateway {
    private final MinioClient client;
    private final MinioProperties properties;

    public MinioDocumentStorageGateway(MinioClient client, MinioProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ensurePrivateBucket() {
        try {
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(properties.bucket()).build())) {
                client.makeBucket(MakeBucketArgs.builder().bucket(properties.bucket()).build());
            }
            removePublicBucketPolicy();
        } catch (Exception exception) {
            throw new IllegalStateException("MinIO bucket 初始化失败", exception);
        }
    }

    private void removePublicBucketPolicy() throws Exception {
        try {
            client.deleteBucketPolicy(DeleteBucketPolicyArgs.builder()
                    .bucket(properties.bucket()).build());
        } catch (ErrorResponseException exception) {
            if (!"NoSuchBucketPolicy".equals(exception.errorResponse().code())) {
                throw exception;
            }
        }
    }

    @Override
    public void put(String objectKey, InputStream input, long size, String contentType) {
        try {
            client.putObject(PutObjectArgs.builder()
                    .bucket(properties.bucket()).object(objectKey)
                    .stream(input, size, -1L).contentType(contentType).build());
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.DOCUMENT_STORAGE_UNAVAILABLE);
        }
    }

    @Override
    public InputStream open(String objectKey) {
        try {
            return client.getObject(GetObjectArgs.builder()
                    .bucket(properties.bucket()).object(objectKey).build());
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.DOCUMENT_STORAGE_UNAVAILABLE);
        }
    }

    @Override
    public DownloadUrlView presign(String objectKey, String filename, Duration duration) {
        try {
            int seconds = Math.toIntExact(duration.toSeconds());
            String disposition = "attachment; filename*=UTF-8''"
                    + URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
            String url = client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET).bucket(properties.bucket()).object(objectKey)
                    .expiry(seconds).extraQueryParams(Map.of("response-content-disposition", disposition))
                    .build());
            return new DownloadUrlView(url, OffsetDateTime.now().plusSeconds(seconds));
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.DOCUMENT_STORAGE_UNAVAILABLE);
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            client.removeObject(RemoveObjectArgs.builder()
                    .bucket(properties.bucket()).object(objectKey).build());
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.DOCUMENT_STORAGE_UNAVAILABLE);
        }
    }
}
