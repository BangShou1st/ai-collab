package com.shitulelv.aicollab.infrastructure.storage;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(MinioProperties.class)
public class MinioConfiguration {
    @Bean
    MinioClient minioClient(MinioProperties properties,
                            @Value("${storage.minio.require-public-endpoint:false}") boolean requirePublicEndpoint) {
        MinioPublicEndpointValidator.validate(properties.publicEndpoint(), requirePublicEndpoint);
        return MinioClient.builder()
                .endpoint(properties.endpoint())
                .credentials(properties.accessKey(), properties.secretKey())
                // Native Windows proxy bypass can omit loopback literals; preserve SDK defaults,
                // but keep local object storage out of the proxy without a global selector change.
                .httpClient(io.minio.Http.newDefaultClient().newBuilder()
                        .proxySelector(com.shitulelv.aicollab.common.net.EnvironmentProxySelector.system()).build())
                .build();
    }
}
