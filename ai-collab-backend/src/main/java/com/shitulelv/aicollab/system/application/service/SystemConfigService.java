package com.shitulelv.aicollab.system.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.application.AgentWorkerConcurrencyManager;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.system.api.dto.UpdateSystemConfigRequest;
import com.shitulelv.aicollab.system.application.view.SystemConfigAuditView;
import com.shitulelv.aicollab.system.application.view.SystemConfigView;
import com.shitulelv.aicollab.system.application.view.SystemRuntimeStatusView;
import com.shitulelv.aicollab.system.domain.model.SystemConfig;
import com.shitulelv.aicollab.system.domain.model.SystemConfigAudit;
import com.shitulelv.aicollab.system.domain.policy.SystemConfigValidator;
import com.shitulelv.aicollab.system.infrastructure.repository.SystemConfigRepository;
import com.shitulelv.aicollab.user.service.UserService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class SystemConfigService {
    private static final Logger log = LoggerFactory.getLogger(SystemConfigService.class);
    private static final String REDIS_CHANNEL = "system:config:change";

    private final SystemConfigRepository repository;
    private final UserService userService;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final boolean agentRuntimeAllowed;

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    @Autowired(required = false)
    private AgentWorkerConcurrencyManager concurrencyManager;

    private final AtomicReference<SystemConfig> activeConfig = new AtomicReference<>(SystemConfig.createDefault());

    public SystemConfigService(
            SystemConfigRepository repository,
            UserService userService,
            ObjectMapper objectMapper,
            JdbcTemplate jdbcTemplate,
            @Value("${agent.runtime-allowed:true}") boolean agentRuntimeAllowed) {
        this.repository = repository;
        this.userService = userService;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.agentRuntimeAllowed = agentRuntimeAllowed;
    }

    @PostConstruct
    public void init() {
        try {
            SystemConfig config = repository.findCurrent().orElseGet(() -> {
                SystemConfig initial = SystemConfig.createDefault();
                repository.saveInitial(initial);
                return initial;
            });
            activeConfig.set(config);
            if (concurrencyManager != null) {
                concurrencyManager.setConcurrency(config.workerConcurrency());
            }
            log.info("系统动态配置中心初始化成功: agentEnabled={}, chatEnabled={}, workerConcurrency={}",
                    config.agentEnabled(), config.chatEnabled(), config.workerConcurrency());
        } catch (Exception e) {
            log.warn("动态配置加载初始化失败，保持内存安全默认值: {}", e.getMessage());
        }
    }

    public boolean isAgentEnabled() {
        return agentRuntimeAllowed && activeConfig.get().agentEnabled();
    }

    public boolean isChatEnabled() {
        return activeConfig.get().chatEnabled();
    }

    public int getWorkerConcurrency() {
        return activeConfig.get().workerConcurrency();
    }

    public long getWorkerDelayMs() {
        return activeConfig.get().workerDelayMs();
    }

    public long getScheduleDelayMs() {
        return activeConfig.get().scheduleDelayMs();
    }

    public boolean isMaintenanceMode() {
        return activeConfig.get().systemMaintenanceMode();
    }

    public String getMaintenanceMessage() {
        return activeConfig.get().systemMaintenanceMessage();
    }

    public boolean isPublicRegistrationEnabled() {
        return activeConfig.get().publicRegistrationEnabled();
    }

    public String getDefaultChatModel() {
        return activeConfig.get().defaultChatModel();
    }

    public String getDefaultAgentModel() {
        return activeConfig.get().defaultAgentModel();
    }

    public double getRagRetrievalThreshold() {
        return activeConfig.get().ragRetrievalThreshold();
    }

    public int getRagMaxSources() {
        return activeConfig.get().ragMaxSources();
    }

    public SystemConfig getActiveConfig() {
        return activeConfig.get();
    }

    public SystemConfigView getCurrentView(UUID userId) {
        userService.requireSystemAdmin(userId);
        return SystemConfigView.from(activeConfig.get());
    }

    public void validateRequest(UUID userId, UpdateSystemConfigRequest request) {
        userService.requireSystemAdmin(userId);
        SystemConfigValidator.validate(
                request.workerConcurrency(),
                request.workerDelayMs(),
                request.scheduleDelayMs(),
                request.systemMaintenanceMessage(),
                request.defaultChatModel(),
                request.defaultAgentModel(),
                request.ragRetrievalThreshold(),
                request.ragMaxSources()
        );
    }

    @Transactional
    public SystemConfigView updateConfig(UUID userId, UpdateSystemConfigRequest request, String username) {
        validateRequest(userId, request);

        SystemConfig current = repository.findCurrent()
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_ERROR, "系统配置不存在"));

        if (!Objects.equals(current.version(), request.expectedVersion())) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT,
                    "配置已被修改（当前版本: " + current.version() + "），请刷新后重试");
        }

        long nextVersion = current.version() + 1;
        OffsetDateTime now = OffsetDateTime.now();

        SystemConfig updated = new SystemConfig(
                current.id(),
                request.agentEnabled(),
                request.chatEnabled(),
                request.workerConcurrency(),
                request.workerDelayMs(),
                request.scheduleDelayMs(),
                request.systemMaintenanceMode(),
                request.systemMaintenanceMessage() != null ? request.systemMaintenanceMessage().strip() : "",
                request.publicRegistrationEnabled(),
                request.defaultChatModel() != null ? request.defaultChatModel().strip() : "",
                request.defaultAgentModel() != null ? request.defaultAgentModel().strip() : "",
                request.ragRetrievalThreshold(),
                request.ragMaxSources(),
                nextVersion,
                userId,
                current.createdAt(),
                now
        );

        boolean success = repository.updateWithVersion(updated, request.expectedVersion());
        if (!success) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT, "并发修改冲突，请刷新后重试");
        }

        String summary = buildDiffSummary(current, updated);
        String snapshotJson;
        try {
            snapshotJson = objectMapper.writeValueAsString(updated);
        } catch (JsonProcessingException e) {
            snapshotJson = "{}";
        }

        SystemConfigAudit audit = new SystemConfigAudit(
                UUID.randomUUID(),
                nextVersion,
                "UPDATE",
                userId,
                username,
                summary,
                snapshotJson,
                "APPLIED",
                null,
                now
        );
        repository.saveAudit(audit);

        applyToRuntime(updated);
        broadcastConfigChange(nextVersion);

        return SystemConfigView.from(updated);
    }

    @Transactional
    public SystemConfigView restoreConfig(UUID userId, UUID auditId, String username) {
        userService.requireSystemAdmin(userId);

        SystemConfigAudit audit = repository.findAuditById(auditId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_ERROR, "指定的配置历史版本不存在"));

        SystemConfig snapshot;
        try {
            snapshot = objectMapper.readValue(audit.configSnapshot(), SystemConfig.class);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "配置快照解析失败: " + e.getMessage());
        }

        SystemConfigValidator.validate(
                snapshot.workerConcurrency(),
                snapshot.workerDelayMs(),
                snapshot.scheduleDelayMs(),
                snapshot.systemMaintenanceMessage(),
                snapshot.defaultChatModel(),
                snapshot.defaultAgentModel(),
                snapshot.ragRetrievalThreshold(),
                snapshot.ragMaxSources()
        );

        SystemConfig current = repository.findCurrent()
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_ERROR, "当前系统配置不存在"));

        long nextVersion = current.version() + 1;
        OffsetDateTime now = OffsetDateTime.now();

        SystemConfig restored = new SystemConfig(
                current.id(),
                snapshot.agentEnabled(),
                snapshot.chatEnabled(),
                snapshot.workerConcurrency(),
                snapshot.workerDelayMs(),
                snapshot.scheduleDelayMs(),
                snapshot.systemMaintenanceMode(),
                snapshot.systemMaintenanceMessage(),
                snapshot.publicRegistrationEnabled(),
                snapshot.defaultChatModel(),
                snapshot.defaultAgentModel(),
                snapshot.ragRetrievalThreshold(),
                snapshot.ragMaxSources(),
                nextVersion,
                userId,
                current.createdAt(),
                now
        );

        boolean success = repository.updateWithVersion(restored, current.version());
        if (!success) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT, "恢复时发生并发冲突，请刷新后重试");
        }

        String summary = "恢复至版本 " + audit.configVersion() + " (" + audit.action() + "): " + buildDiffSummary(current, restored);
        String snapshotJson;
        try {
            snapshotJson = objectMapper.writeValueAsString(restored);
        } catch (JsonProcessingException e) {
            snapshotJson = "{}";
        }

        SystemConfigAudit restoreAudit = new SystemConfigAudit(
                UUID.randomUUID(),
                nextVersion,
                "RESTORE",
                userId,
                username,
                summary,
                snapshotJson,
                "APPLIED",
                null,
                now
        );
        repository.saveAudit(restoreAudit);

        applyToRuntime(restored);
        broadcastConfigChange(nextVersion);

        return SystemConfigView.from(restored);
    }

    public List<SystemConfigAuditView> listAudits(UUID userId, int limit) {
        userService.requireSystemAdmin(userId);
        return repository.listRecentAudits(limit).stream()
                .map(SystemConfigAuditView::from)
                .toList();
    }

    public SystemRuntimeStatusView getRuntimeStatus(UUID userId) {
        userService.requireSystemAdmin(userId);
        SystemConfig config = activeConfig.get();

        int queuedRuns = 0;
        int runningRuns = 0;
        int failedRuns = 0;
        String lastError = null;

        try {
            Integer q = jdbcTemplate.queryForObject("SELECT count(*) FROM agent_run WHERE status = 'QUEUED'", Integer.class);
            if (q != null) queuedRuns = q;
        } catch (Exception ignored) {}

        try {
            Integer r = jdbcTemplate.queryForObject("SELECT count(*) FROM agent_run WHERE status = 'RUNNING'", Integer.class);
            if (r != null) runningRuns = r;
        } catch (Exception ignored) {}

        try {
            Integer f = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM agent_run WHERE status = 'FAILED' AND created_at > now() - interval '24 hours'",
                    Integer.class);
            if (f != null) failedRuns = f;
        } catch (Exception ignored) {}

        try {
            List<String> errors = jdbcTemplate.query(
                    "SELECT error_message FROM agent_run WHERE status = 'FAILED' AND error_message IS NOT NULL ORDER BY updated_at DESC LIMIT 1",
                    (rs, rowNum) -> rs.getString("error_message"));
            if (!errors.isEmpty()) {
                lastError = errors.getFirst();
            }
        } catch (Exception ignored) {}

        int activeThreads = concurrencyManager != null ? concurrencyManager.getActiveCount() : 0;

        return new SystemRuntimeStatusView(
                config.agentEnabled(),
                config.chatEnabled(),
                agentRuntimeAllowed,
                config.workerConcurrency(),
                activeThreads,
                queuedRuns,
                runningRuns,
                failedRuns,
                lastError,
                config.systemMaintenanceMode(),
                OffsetDateTime.now()
        );
    }

    public void reloadFromDatabase() {
        try {
            repository.findCurrent().ifPresent(this::applyToRuntime);
        } catch (Exception e) {
            log.error("从数据库重新加载配置失败，保留当前有效配置: {}", e.getMessage());
        }
    }

    /**
     * 周期性轻量对账同步：防止 Redis Pub/Sub 消息丢失或多实例网络分区恢复后持有旧配置。
     */
    @org.springframework.scheduling.annotation.Scheduled(
            fixedDelayString = "${system.config.sync-interval-ms:30000}",
            initialDelay = 30000
    )
    public void scheduledSync() {
        try {
            repository.findCurrent().ifPresent(dbConfig -> {
                SystemConfig localConfig = activeConfig.get();
                if (dbConfig.version() > localConfig.version()) {
                    log.info("检测到数据库更高配置版本 (本地: {}, 数据库: {})，执行周期同步对齐",
                            localConfig.version(), dbConfig.version());
                    applyToRuntime(dbConfig);
                }
            });
        } catch (Exception e) {
            log.warn("动态配置周期性对齐检查失败: {}", e.getMessage());
        }
    }

    private synchronized void applyToRuntime(SystemConfig newConfig) {
        SystemConfig current = activeConfig.get();
        if (newConfig.version() < current.version()) {
            log.warn("收到过期的配置版本 (当前: {}, 收到: {})，放弃应用以防止乱序覆盖",
                    current.version(), newConfig.version());
            return;
        }
        activeConfig.set(newConfig);
        if (concurrencyManager != null) {
            concurrencyManager.setConcurrency(newConfig.workerConcurrency());
        }
        log.info("系统运行时配置已更新: version={}, agentEnabled={}, chatEnabled={}, concurrency={}",
                newConfig.version(), newConfig.agentEnabled(), newConfig.chatEnabled(), newConfig.workerConcurrency());
    }

    private void broadcastConfigChange(long version) {
        if (redisTemplate != null) {
            try {
                redisTemplate.convertAndSend(REDIS_CHANNEL, String.valueOf(version));
            } catch (Exception e) {
                log.warn("Redis 广播配置变更通知失败（单机继续生效）: {}", e.getMessage());
            }
        }
    }

    private String buildDiffSummary(SystemConfig oldC, SystemConfig newC) {
        List<String> diffs = new ArrayList<>();
        if (oldC.agentEnabled() != newC.agentEnabled()) {
            diffs.add("Agent开关: " + (oldC.agentEnabled() ? "开" : "关") + " -> " + (newC.agentEnabled() ? "开" : "关"));
        }
        if (oldC.chatEnabled() != newC.chatEnabled()) {
            diffs.add("Chat开关: " + (oldC.chatEnabled() ? "开" : "关") + " -> " + (newC.chatEnabled() ? "开" : "关"));
        }
        if (oldC.workerConcurrency() != newC.workerConcurrency()) {
            diffs.add("Worker并发: " + oldC.workerConcurrency() + " -> " + newC.workerConcurrency());
        }
        if (oldC.workerDelayMs() != newC.workerDelayMs()) {
            diffs.add("调度间隔: " + oldC.workerDelayMs() + "ms -> " + newC.workerDelayMs() + "ms");
        }
        if (oldC.systemMaintenanceMode() != newC.systemMaintenanceMode()) {
            diffs.add("维护模式: " + (oldC.systemMaintenanceMode() ? "开" : "关") + " -> " + (newC.systemMaintenanceMode() ? "开" : "关"));
        }
        if (oldC.publicRegistrationEnabled() != newC.publicRegistrationEnabled()) {
            diffs.add("公开注册: " + (oldC.publicRegistrationEnabled() ? "允许" : "关闭") + " -> " + (newC.publicRegistrationEnabled() ? "允许" : "关闭"));
        }
        if (diffs.isEmpty()) {
            return "参数微调";
        }
        return String.join("; ", diffs);
    }
}
