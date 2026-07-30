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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger log = LoggerFactory.getLogger(MinioDocumentStorageGateway.class);

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
            log.warn("MinIO bucket 初始化失败，文档存储功能将不可用", exception);
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
            throw storageUnavailable("上传", objectKey, exception);
        }
    }

    @Override
    public InputStream open(String objectKey) {
        try {
            return client.getObject(GetObjectArgs.builder()
                    .bucket(properties.bucket()).object(objectKey).build());
        } catch (Exception exception) {
            throw storageUnavailable("读取", objectKey, exception);
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
            throw storageUnavailable("生成下载地址", objectKey, exception);
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            client.removeObject(RemoveObjectArgs.builder()
                    .bucket(properties.bucket()).object(objectKey).build());
        } catch (Exception exception) {
            throw storageUnavailable("删除", objectKey, exception);
        }
    }

    private BusinessException storageUnavailable(
            String operation,
            String objectKey,
            Exception exception) {
        log.warn("MinIO {}对象失败，objectKey={}", operation, objectKey, exception);
        if (exception instanceof ErrorResponseException responseException
                && isCredentialFailure(responseException)) {
            return new BusinessException(
                    ErrorCode.DOCUMENT_STORAGE_UNAVAILABLE,
                    "文件存储凭据无效，请联系管理员检查 MinIO 配置");
        }
        return new BusinessException(
                ErrorCode.DOCUMENT_STORAGE_UNAVAILABLE,
                "无法连接文件存储服务，请稍后重试；若持续出现，请联系管理员检查 MinIO 服务");
    }

    private boolean isCredentialFailure(ErrorResponseException exception) {
        String code = exception.errorResponse() == null
                ? null
                : exception.errorResponse().code();
        return "AccessDenied".equals(code)
                || "InvalidAccessKeyId".equals(code)
                || "SignatureDoesNotMatch".equals(code);
    }
}
