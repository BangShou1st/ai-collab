package com.shitulelv.aicollab.infrastructure.ai.model.profile;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelProviderType;
import com.shitulelv.aicollab.infrastructure.ai.model.profile.api.ModelProfileRequest;
import com.shitulelv.aicollab.infrastructure.ai.model.profile.api.ModelProfileView;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** AC-P1：V89 全局模型档案的真实库生命周期、归一化身份与唯一约束。 */
@Testcontainers(disabledWithoutDocker = true)
class ModelProfilePostgresIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    static JdbcTemplate jdbc;
    static ModelProfileService service;
    static UUID admin;

    @BeforeAll static void setup() {
        var dataSource = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        service = new ModelProfileService(new ModelProfileRepository(jdbc));
        admin = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,?,'unused','test')",
                admin, "profile-admin");
    }

    @Test
    void lifecycleNormalizedResolutionAndVerifiedSemantics() {
        ModelProfileView created = service.create(admin, request("https://Gateway.Example/v1/", "GPT-4o",
                128_000, ModelProfileSource.ADMIN_VERIFIED));

        assertThat(created.verified()).isTrue();
        assertThat(created.verifiedAt()).isNotNull();
        assertThat(created.verifiedBy()).isEqualTo(admin);
        assertThat(service.list()).extracting(ModelProfileView::id).contains(created.id());

        // 归一化命中：大小写、尾部斜杠、路径写法差异不产生第二份档案
        assertThat(service.resolve("OPENAI_COMPATIBLE", "https://gateway.example/v1", "/v1/chat/completions", "gpt-4o"))
                .map(ModelProfile::id).contains(created.id());
        assertThat(service.resolve("OPENAI_COMPATIBLE", "https://gateway.example/v2", "/v1/chat/completions", "gpt-4o"))
                .isEmpty();
        assertThat(service.resolve("ANTHROPIC", "https://gateway.example/v1", "/v1/chat/completions", "gpt-4o"))
                .isEmpty();

        // 降级为 UNVERIFIED 时必须清空核实记录，运行时按估算处理
        ModelProfileView unverified = service.update(admin, created.id(),
                request("https://gateway.example/v1", "/v1/chat/completions", "gpt-4o", 64_000,
                        ModelProfileSource.UNVERIFIED));
        assertThat(unverified.verified()).isFalse();
        assertThat(unverified.verifiedAt()).isNull();
        assertThat(unverified.verifiedBy()).isNull();
        assertThat(service.resolve("OPENAI_COMPATIBLE", "https://gateway.example/v1", "/v1/chat/completions", "gpt-4o"))
                .map(ModelProfile::verified).contains(false);

        service.delete(created.id());
        assertThat(service.list()).extracting(ModelProfileView::id).doesNotContain(created.id());
        assertThatThrownBy(() -> service.delete(created.id()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MODEL_PROFILE_NOT_FOUND));
    }

    @Test
    void duplicateIdentityAcrossCaseAndTrailingSlashIsRejected() {
        ModelProfileView first = service.create(admin, request("https://dup.example/v1",
                "/v1/chat/completions", "gpt-4o", 32_000, ModelProfileSource.PROVIDER_DOCUMENT));

        assertThatThrownBy(() -> service.create(admin,
                request("https://DUP.example/v1/", "/v1/chat/completions/", "gpt-4o", 64_000,
                        ModelProfileSource.UNVERIFIED)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MODEL_PROFILE_EXISTS));

        // 更新到已存在的身份同样拒绝
        ModelProfileView second = service.create(admin, request("https://dup.example/v1",
                "/v1/chat/completions", "other-model", 16_000, ModelProfileSource.UNVERIFIED));
        assertThatThrownBy(() -> service.update(admin, second.id(),
                request("https://dup.example/v1", "/v1/chat/completions", "GPT-4O", 16_000,
                        ModelProfileSource.UNVERIFIED)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MODEL_PROFILE_EXISTS));

        service.delete(first.id());
        service.delete(second.id());
    }

    private static ModelProfileRequest request(String baseUrl, String modelName, int window,
                                               ModelProfileSource source) {
        return request(baseUrl, "/v1/chat/completions", modelName, window, source);
    }

    private static ModelProfileRequest request(String baseUrl, String apiPath, String modelName, int window,
                                               ModelProfileSource source) {
        return new ModelProfileRequest(ModelProviderType.OPENAI_COMPATIBLE, baseUrl, apiPath, modelName,
                window, 8_192, true, "cl100k", "2026-11-11", source);
    }
}
