package com.shitulelv.aicollab.infrastructure.storage;

import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MinioHealthIndicatorTest {

    private final MinioClient client = mock(MinioClient.class);
    private final MinioProperties properties = new MinioProperties(
            "http://localhost:9000", "ak", "sk", "ai-collab");
    private final MinioHealthIndicator indicator = new MinioHealthIndicator(client, properties);

    @Test
    void bucketExists_reportsUp_withBucketDetail() throws Exception {
        when(client.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("bucket", "ai-collab");
    }

    @Test
    void missingBucket_reportsDown_insteadOfUp() throws Exception {
        // 服务可达但 bucket 名配错是常见生产事故：必须报 DOWN，不能被当作健康。
        when(client.bucketExists(any(BucketExistsArgs.class))).thenReturn(false);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("reason", "bucket 不存在");
    }

    @Test
    void connectionFailure_reportsDown_withoutLeakingCredentials() throws Exception {
        when(client.bucketExists(any(BucketExistsArgs.class)))
                .thenThrow(new RuntimeException("Connection refused: localhost:9000"));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        // 健康明细里不允许出现密钥；异常只保留类型摘要用于分类。
        assertThat(health.getDetails()).containsEntry("reason", "RuntimeException");
        assertThat(health.getDetails().toString()).doesNotContain("sk");
    }
}
