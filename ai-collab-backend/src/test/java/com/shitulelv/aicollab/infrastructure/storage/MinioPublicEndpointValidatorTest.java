package com.shitulelv.aicollab.infrastructure.storage;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MinioPublicEndpointValidatorTest {
    @Test
    void productionProfilesEnforcePublicOriginEvenWithoutComposeFlag() {
        var environment = new MockEnvironment();
        var config = new MinioConfiguration();
        var properties = new MinioProperties("http://minio:9000", "synthetic-access", "synthetic-secret", "documents", "", false);
        for (String profile : new String[]{"prod", "production", "staging"}) {
            environment.setActiveProfiles(profile);
            assertThrows(IllegalArgumentException.class, () -> config.minioClient(properties, false, environment));
        }
        environment.setActiveProfiles("local");
        assertDoesNotThrow(() -> config.minioClient(properties, false, environment));
    }
    @Test
    void productionRequiresOneHttpsOrigin() {
        assertDoesNotThrow(() -> MinioPublicEndpointValidator.validate("https://storage.example.org:8443", true));
        for (String value : new String[] {"", "http://storage.example.org", "https://a.example.org,https://b.example.org",
                "https://user:password@storage.example.org", "https://storage.example.org/storage",
                "https://storage.example.org?query=value", "https://storage.example.org#fragment",
                "https://localhost:9000", "https://127.0.0.1:9000", "https://minio:9000", "https://minio.local"}) {
            assertThrows(IllegalArgumentException.class, () -> MinioPublicEndpointValidator.validate(value, true));
        }
    }

    @Test
    void localDevelopmentMayUseItsInternalClientOrLoopbackHttp() {
        assertDoesNotThrow(() -> MinioPublicEndpointValidator.validate("", false));
        assertDoesNotThrow(() -> MinioPublicEndpointValidator.validate("http://localhost:9000", false));
        assertThrows(IllegalArgumentException.class,
                () -> MinioPublicEndpointValidator.validate("http://storage.example.org", false));
    }
}
