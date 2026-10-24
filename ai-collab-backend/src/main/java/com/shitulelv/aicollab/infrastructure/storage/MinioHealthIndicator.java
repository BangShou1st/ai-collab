package com.shitulelv.aicollab.infrastructure.storage;

import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * 把对象存储纳入 /actuator/health 聚合：MinIO 是文档与知识库的核心依赖，
 * 其不可用应让整体健康检查变为 DOWN（liveness 分组不受影响，进程不因此误判死亡）。
 * AI 模型（Chat/Planning/Embedding）是按需外部服务，刻意不建 HealthIndicator，
 * 避免上游限流或网络抖动把应用整体判死；其故障经错误码与 ai_call_log 诊断。
 * 输出仅含 bucket 名称与异常摘要，不含访问密钥。
 */
@Component
public class MinioHealthIndicator implements HealthIndicator {

    private final MinioClient client;
    private final MinioProperties properties;

    public MinioHealthIndicator(MinioClient client, MinioProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public Health health() {
        String bucket = properties.bucket();
        try {
            boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (exists) {
                return Health.up().withDetail("bucket", bucket).build();
            }
            return Health.down().withDetail("bucket", bucket).withDetail("reason", "bucket 不存在").build();
        } catch (Exception exception) {
            // AbstractHealthIndicator 语义之外手工兜底：网络/凭据类异常统一归类为 DOWN，
            // 摘要信息不含密钥，仅暴露端点与 bucket，足以定位"连不上"还是"配置错 bucket"。
            return Health.down()
                    .withDetail("bucket", bucket)
                    .withDetail("reason", exception.getClass().getSimpleName())
                    .build();
        }
    }
}
