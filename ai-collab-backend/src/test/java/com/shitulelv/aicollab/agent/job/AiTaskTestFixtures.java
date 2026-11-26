package com.shitulelv.aicollab.agent.job;

import org.flywaydb.core.Flyway;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * P1 任务链路真库测试的共用夹具：PostgreSQL 17 + pgvector（Flyway 全量迁移，只碰 public）、
 * Redis 7（Lettuce 连接，与生产 {@code spring-boot-starter-data-redis} 同一客户端）。
 */
public final class AiTaskTestFixtures {

    public static final String POSTGRES_IMAGE = "pgvector/pgvector:pg17";
    public static final String REDIS_IMAGE = "redis:7-alpine";

    private AiTaskTestFixtures() {
    }

    public static PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>(POSTGRES_IMAGE);
    }

    public static GenericContainer<?> redis() {
        return new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE)).withExposedPorts(6379);
    }

    public static DataSource migratedDataSource(PostgreSQLContainer<?> postgres) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        return dataSource;
    }

    public static LettuceConnectionFactory lettuce(GenericContainer<?> redis) {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(redis.getHost(), redis.getMappedPort(6379)));
        factory.afterPropertiesSet();
        if (!factory.isRunning()) {
            factory.start();
        }
        return factory;
    }

    public static UUID user(JdbcTemplate jdbc, String prefix) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,?,?,?)",
                id, prefix + "-" + id.toString().substring(0, 8), "test-only-hash", "P1 任务");
        return id;
    }

    public static UUID project(JdbcTemplate jdbc, UUID owner, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO project(id,name,description,owner_id,created_by,start_date,due_date)
                VALUES (?,?,?,?,?,DATE '2026-11-01',DATE '2026-12-31')
                """, id, name, "项目描述", owner, owner);
        return id;
    }

    /** Stream 里属于某任务的全部消息（按写入顺序）。 */
    public static List<Map<Object, Object>> streamEntries(StringRedisTemplate redis, UUID taskId) {
        List<MapRecord<String, Object, Object>> records =
                redis.opsForStream().range(AiTaskStreamPublisher.STREAM_KEY, Range.unbounded());
        if (records == null) {
            return List.of();
        }
        return records.stream().map(MapRecord::getValue)
                .filter(fields -> taskId.toString().equals(fields.get("taskId")))
                .toList();
    }

    public static long epoch(JdbcTemplate jdbc, UUID taskId) {
        Long value = jdbc.queryForObject("SELECT lease_epoch FROM ai_job WHERE id=?", Long.class, taskId);
        return value == null ? -1 : value;
    }

    public static String status(JdbcTemplate jdbc, UUID taskId) {
        return jdbc.queryForObject("SELECT status FROM ai_job WHERE id=?", String.class, taskId);
    }
}
