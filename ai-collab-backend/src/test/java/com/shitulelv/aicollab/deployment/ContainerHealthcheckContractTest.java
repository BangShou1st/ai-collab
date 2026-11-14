package com.shitulelv.aicollab.deployment;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 镜像健康检查与 Actuator 管理口的一致性契约（D2）。
 *
 * <p>背景：Actuator 监听 {@code management.server.port}（默认 8081），业务端口 8080 上没有
 * {@code /actuator/health}。Dockerfile 若探 8080 会稳定 404，容器恒为 unhealthy
 * （staging 曾观测到 {@code FailingStreak=10}）。本测试直接读文件，锁死
 * "HEALTHCHECK 端口 == application.yml 的管理口默认值"，不需要启动容器或发网络请求。</p>
 *
 * <p>compose staging/prod 已显式写 8081；base compose 不覆盖 healthcheck，因此 Dockerfile
 * 是唯一需要与 {@code management.server.port} 对齐的镜像层默认。</p>
 */
class ContainerHealthcheckContractTest {

    private static final Pattern HEALTHCHECK_URL =
            Pattern.compile("http://127\\.0\\.0\\.1:(\\d+)/actuator/health");
    private static final Pattern MANAGEMENT_PORT_DEFAULT =
            Pattern.compile("MANAGEMENT_SERVER_PORT:(\\d+)");

    @Test
    void dockerfileHealthcheckUsesActuatorManagementPort() throws IOException {
        String dockerfile = readProjectFile("Dockerfile");
        String applicationYml = readProjectFile("src/main/resources/application.yml");

        Matcher healthcheck = HEALTHCHECK_URL.matcher(dockerfile);
        assertThat(healthcheck.find())
                .as("Dockerfile HEALTHCHECK 必须包含 http://127.0.0.1:<port>/actuator/health")
                .isTrue();
        int healthcheckPort = Integer.parseInt(healthcheck.group(1));

        Matcher managementPort = MANAGEMENT_PORT_DEFAULT.matcher(applicationYml);
        assertThat(managementPort.find())
                .as("application.yml 必须声明 management.server.port 的默认值")
                .isTrue();
        int actuatorPort = Integer.parseInt(managementPort.group(1));

        assertThat(healthcheckPort)
                .as("HEALTHCHECK 端口必须与 management.server.port 默认值一致")
                .isEqualTo(actuatorPort);
        assertThat(healthcheckPort).isEqualTo(8081);
    }

    @Test
    void dockerfileExposesBothBusinessAndManagementPorts() throws IOException {
        String dockerfile = readProjectFile("Dockerfile");

        assertThat(dockerfile).contains("EXPOSE 8080 8081");
        assertThat(dockerfile)
                .as("8080/actuator/health 是已知的恒 404 探针，不能出现在 Dockerfile 中")
                .doesNotContain("127.0.0.1:8080/actuator/health");
    }

    /**
     * 兼容两种工作目录：Maven 在模块根运行（{@code ai-collab-backend/}），IDE 可能从仓库根运行。
     */
    private static String readProjectFile(String relativePath) throws IOException {
        Path moduleRelative = Path.of(relativePath);
        if (Files.isRegularFile(moduleRelative)) {
            return Files.readString(moduleRelative, StandardCharsets.UTF_8);
        }
        Path repoRelative = Path.of("ai-collab-backend").resolve(relativePath);
        return Files.readString(repoRelative, StandardCharsets.UTF_8);
    }
}
