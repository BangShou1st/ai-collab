package com.shitulelv.aicollab.infrastructure.ai.shared;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.common.security.OutboundEndpointPolicy;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionCommand;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelProviderAdapter;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelProviderType;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelPurpose;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelSecretCipher;
import com.shitulelv.aicollab.infrastructure.ai.shared.api.AdminAiProviderTestView;
import com.shitulelv.aicollab.infrastructure.ai.shared.api.AdminSharedAiProviderView;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProvider;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProviderRepository;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProviderProbeResultRepository;
import com.shitulelv.aicollab.infrastructure.ai.user.api.UserAiProviderRequest;
import com.shitulelv.aicollab.notification.application.service.AdminNotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class AdminAiProviderService {
    private static final Logger log = LoggerFactory.getLogger(AdminAiProviderService.class);

    private final UserAiProviderRepository userProviderRepository;
    private final SharedAiProviderRepository sharedRepository;
    private final ModelSecretCipher secrets;
    private final OutboundEndpointPolicy endpoints;
    private final Map<ModelProviderType, ModelProviderAdapter> adapters;
    private final AdminNotificationService notificationService;
    private final UserAiProviderProbeResultRepository probeResults;
    private final ObjectMapper json;
    private final ProviderAdmissionProperties admission;

    public AdminAiProviderService(
            UserAiProviderRepository userProviderRepository,
            SharedAiProviderRepository sharedRepository,
            ModelSecretCipher secrets,
            OutboundEndpointPolicy endpoints,
            List<ModelProviderAdapter> adapters,
            AdminNotificationService notificationService,
            UserAiProviderProbeResultRepository probeResults,
            ObjectMapper json,
            ProviderAdmissionProperties admission) {
        this.userProviderRepository = userProviderRepository;
        this.sharedRepository = sharedRepository;
        this.secrets = secrets;
        this.endpoints = endpoints;
        this.adapters = new EnumMap<>(ModelProviderType.class);
        adapters.forEach(adapter -> this.adapters.put(adapter.providerType(), adapter));
        this.notificationService = notificationService;
        this.probeResults = probeResults;
        this.json = json;
        this.admission = admission;
    }

    public List<AdminSharedAiProviderView> listAdminProviders(UUID adminUserId) {
        List<UserAiProvider> sources = userProviderRepository.findAllByUserId(adminUserId);
        Map<UUID, SharedAiProvider> sharedMap = sharedRepository.findAll().stream()
                // 能力 Provider（RERANK 等）直接存在于 shared_ai_provider，source_provider_id 为空；
                // 它们不是 Chat 共享项，也不能作为 Collectors.toMap 的 null key。
                .filter(shared -> shared.sourceProviderId() != null)
                .collect(java.util.stream.Collectors.toMap(
                        SharedAiProvider::sourceProviderId, s -> s, (s1, s2) -> s1));

        return sources.stream()
                .filter(p -> p.presetCode() == null) // 过滤掉Zen等内置预设
                .map(p -> AdminSharedAiProviderView.from(p, sharedMap.get(p.id())))
                .toList();
    }

    @Transactional
    public AdminSharedAiProviderView create(UUID adminUserId, UserAiProviderRequest request) {
        requireChatCompatible(request.providerType());
        validateEndpoint(request.baseUrl(), request.apiPath());
        if (request.apiKey() == null || request.apiKey().isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "API Key 不能为空");
        }
        OffsetDateTime now = OffsetDateTime.now();
        UserAiProvider created = userProviderRepository.save(new UserAiProvider(
                UUID.randomUUID(),
                adminUserId,
                request.name().strip(),
                request.providerType(),
                request.baseUrl().strip(),
                request.apiPath().strip(),
                secrets.encrypt(request.apiKey().strip()),
                request.modelName().strip(),
                request.enabled(),
                request.temperature(),
                request.maxOutputTokens(),
                EnumSet.copyOf(request.capabilities()),
                false,
                now,
                now,
                null,
                0));

        return AdminSharedAiProviderView.from(created, null);
    }

    @Transactional
    public AdminSharedAiProviderView update(UUID adminUserId, UUID id, UserAiProviderRequest request) {
        requireChatCompatible(request.providerType());
        UserAiProvider existing = requireSource(adminUserId, id);
        validateEndpoint(request.baseUrl(), request.apiPath());
        String encrypted = resolveEncryptedKey(existing.encryptedApiKey(), request.apiKey());

        UserAiProvider updated = userProviderRepository.save(new UserAiProvider(
                id,
                adminUserId,
                request.name().strip(),
                request.providerType(),
                request.baseUrl().strip(),
                request.apiPath().strip(),
                encrypted,
                request.modelName().strip(),
                request.enabled(),
                request.temperature(),
                request.maxOutputTokens(),
                EnumSet.copyOf(request.capabilities()),
                existing.isDefault(),
                existing.createdAt(),
                OffsetDateTime.now(),
                null,
                existing.capabilityConfigVersion()));

        // 如果该配置之前已经发布为全站共享，同步更新共享表里的内容（保持幂等关联）
        Optional<SharedAiProvider> sharedOpt = sharedRepository.findBySourceProviderId(id);
        SharedAiProvider shared = null;
        if (sharedOpt.isPresent()) {
            SharedAiProvider prev = sharedOpt.get();
            // 请求参数变化会推进 capability_config_version，旧准入不再描述新配置。
            // 已共享项必须回到 REVOKED，待管理员重新测试并发布，不能借编辑绕过 D4 准入。
            boolean requestConfigChanged =
                    updated.capabilityConfigVersion() != existing.capabilityConfigVersion();
            String sharedStatus = requestConfigChanged
                    && SharedAiProvider.STATUS_PUBLISHED.equals(prev.status())
                    ? SharedAiProvider.STATUS_REVOKED
                    : prev.status();
            shared = sharedRepository.saveOrUpdate(new SharedAiProvider(
                    prev.id(),
                    id,
                    adminUserId,
                    updated.name(),
                    updated.providerType(),
                    updated.baseUrl(),
                    updated.apiPath(),
                    updated.encryptedApiKey(),
                    updated.modelName(),
                    updated.enabled(),
                    sharedStatus,
                    updated.temperature(),
                    updated.maxOutputTokens(),
                    updated.capabilities(),
                    prev.capabilityConfigVersion() + 1,
                    prev.createdAt(),
                    OffsetDateTime.now(),
                    prev.publishedAt()));
        }

        return AdminSharedAiProviderView.from(updated, shared);
    }

    @Transactional
    public void delete(UUID adminUserId, UUID id) {
        requireSource(adminUserId, id);
        sharedRepository.deleteBySourceProviderId(id);
        userProviderRepository.deleteByIdAndUserId(id, adminUserId);
    }

    /**
     * 多次采样连接测试并记录发布准入摘要。
     *
     * <p>原有响应字段保持不变，新增 {@code admission}。任一样本失败、超时超过阈值，
     * 或 P95 超过阈值时抛出分类错误并持久化失败摘要；不允许把单次成功当成稳定，
     * 也不把部分成功洗成整体成功。</p>
     */
    public AdminAiProviderTestView test(UUID adminUserId, UUID id) {
        UserAiProvider provider = requireSource(adminUserId, id);
        if (!provider.enabled()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "不能测试已停用的模型配置");
        }
        ModelProviderAdapter adapter = adapters.get(provider.providerType());
        if (adapter == null) throw new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE);

        log.info("Admin testing AI provider: {} (type: {}, model: {})",
                provider.name(), provider.providerType(), provider.modelName());

        ChatCompletionCommand command = new ChatCompletionCommand(
                provider.id(),
                "只回答测试请求，不要输出其他内容。",
                "回复：连接成功",
                ChatCompletionCommand.OutputFormat.TEXT,
                ModelPurpose.KNOWLEDGE_CHAT,
                null,
                List.of(),
                adminUserId);

        List<Long> latencies = new ArrayList<>(admission.samples());
        List<Exception> failures = new ArrayList<>();
        ChatCompletionResult firstSuccess = null;
        int timeoutCount = 0;
        for (int sample = 0; sample < admission.samples(); sample++) {
            long startedAt = System.nanoTime();
            try {
                ChatCompletionResult result = adapter.complete(
                        provider.toModelConfiguration(),
                        secrets.decrypt(provider.encryptedApiKey()),
                        command);
                if (result == null) {
                    throw new BusinessException(ErrorCode.AI_PROVIDER_INVALID_RESPONSE,
                            "模型服务返回了空响应");
                }
                if (firstSuccess == null) firstSuccess = result;
            } catch (Exception failure) {
                failures.add(failure);
                if (isTimeout(failure)) timeoutCount++;
            } finally {
                latencies.add(Math.max(1L, (System.nanoTime() - startedAt) / 1_000_000L));
            }
        }

        long p50Ms = percentile(latencies, 50);
        long p95Ms = percentile(latencies, 95);
        boolean latencyExceeded = p95Ms > admission.p95MaxMs();
        boolean accepted = firstSuccess != null
                && failures.size() <= admission.maxFailures()
                && timeoutCount <= admission.maxTimeouts()
                && !latencyExceeded;
        String status = accepted
                ? ProviderAdmissionSnapshot.STATUS_PASS
                : (failures.size() > 0 && failures.size() == timeoutCount
                        ? ProviderAdmissionSnapshot.STATUS_INCONCLUSIVE
                        : ProviderAdmissionSnapshot.STATUS_FAIL);
        ProviderAdmissionSnapshot snapshot = new ProviderAdmissionSnapshot(
                ProviderAdmissionSnapshot.SCHEMA_VERSION,
                status,
                admission.samples(),
                p50Ms,
                p95Ms,
                timeoutCount,
                failures.size(),
                provider.capabilityConfigVersion(),
                admission.p95MaxMs());
        String errorCode = failures.isEmpty() ? null : errorCodeOf(failures.getFirst());
        boolean persisted = recordAdmission(provider, snapshot, errorCode);

        if (!accepted) {
            BusinessException failure = admissionFailure(snapshot, failures);
            log.warn("Admin test provider admission failed: {}", failure.getMessage());
            notificationService.publishEvent(
                    "AI_PROVIDER",
                    "ERROR",
                    "AI 供应商测试失败: " + provider.name(),
                    "模型 " + provider.modelName() + " 连接测试失败: " + failure.getMessage(),
                    "/admin/ai-providers",
                    "test_fail_" + provider.id());
            throw failure;
        }
        if (!persisted) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "供应商准入结果未能保存，当前配置禁止发布；请重试");
        }
        return AdminAiProviderTestView.from(firstSuccess, snapshot, true);
    }

    /**
     * 将管理员供应商发布为全站共享。
     * 重复调用保持幂等，更新同一共享项。
     */
    @Transactional
    public AdminSharedAiProviderView publishShared(UUID adminUserId, UUID sourceProviderId) {
        UserAiProvider source = requireSource(adminUserId, sourceProviderId);
        if (!source.enabled()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "已停用的供应商不能发布为全站共享");
        }
        requirePublishAdmission(source);

        OffsetDateTime now = OffsetDateTime.now();
        UUID sharedId = sharedRepository.findBySourceProviderId(sourceProviderId)
                .map(SharedAiProvider::id)
                .orElseGet(UUID::randomUUID);

        SharedAiProvider shared = sharedRepository.saveOrUpdate(new SharedAiProvider(
                sharedId,
                source.id(),
                adminUserId,
                source.name(),
                source.providerType(),
                source.baseUrl(),
                source.apiPath(),
                source.encryptedApiKey(),
                source.modelName(),
                true,
                SharedAiProvider.STATUS_PUBLISHED,
                source.temperature(),
                source.maxOutputTokens(),
                source.capabilities(),
                0,
                now,
                now,
                now));

        notificationService.publishEvent(
                "AI_PROVIDER",
                "INFO",
                "全站共享 AI 供应商已发布",
                "管理员已将供应商「" + source.name() + "」（模型：" + source.modelName() + "）发布为全站共享。",
                "/admin/ai-providers",
                "publish_" + source.id());

        return AdminSharedAiProviderView.from(source, shared);
    }

    /**
     * 撤销全站共享。
     */
    @Transactional
    public AdminSharedAiProviderView revokeShared(UUID adminUserId, UUID sourceProviderId) {
        UserAiProvider source = requireSource(adminUserId, sourceProviderId);
        sharedRepository.revokeBySourceProviderId(sourceProviderId);
        SharedAiProvider revoked = sharedRepository.findBySourceProviderId(sourceProviderId).orElse(null);

        notificationService.publishEvent(
                "AI_PROVIDER",
                "WARNING",
                "全站共享 AI 供应商已撤销",
                "管理员已撤销供应商「" + source.name() + "」的全站共享。",
                "/admin/ai-providers",
                "revoke_" + source.id());

        return AdminSharedAiProviderView.from(source, revoked);
    }

    private void requirePublishAdmission(UserAiProvider source) {
        UserAiProviderProbeResultRepository.ProbeResultRow row = probeResults
                .find(source.id(), ProviderAdmissionSnapshot.MODE)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "发布前必须完成多次连接测试；当前没有可用的准入记录"));
        if (row.testedConfigVersion() == null
                || row.testedConfigVersion() != source.capabilityConfigVersion()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "准入测试结果已过期：配置版本已变化，请重新执行连接测试后再发布");
        }
        ProviderAdmissionSnapshot snapshot = parseAdmission(row.detail());
        if (snapshot == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "准入记录格式无效，请重新执行连接测试");
        }
        if (snapshot.configVersion() != source.capabilityConfigVersion()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "准入记录的配置版本与当前配置不一致，请重新执行连接测试");
        }
        if (!ProviderAdmissionSnapshot.STATUS_PASS.equals(row.status())
                || !snapshot.acceptedBy(admission)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "准入未通过，不能发布为全站共享：" + metricSummary(snapshot));
        }
    }

    private ProviderAdmissionSnapshot parseAdmission(String detail) {
        if (detail == null || detail.isBlank()) return null;
        try {
            return ProviderAdmissionSnapshot.fromJson(json.readTree(detail));
        } catch (Exception malformed) {
            return null;
        }
    }

    private boolean recordAdmission(UserAiProvider provider,
            ProviderAdmissionSnapshot snapshot, String errorCode) {
        ObjectNode detail = json.createObjectNode();
        detail.put("schemaVersion", snapshot.schemaVersion());
        detail.put("status", snapshot.status());
        detail.put("samples", snapshot.samples());
        detail.put("p50Ms", snapshot.p50Ms());
        detail.put("p95Ms", snapshot.p95Ms());
        detail.put("timeoutCount", snapshot.timeoutCount());
        detail.put("failureCount", snapshot.failureCount());
        detail.put("configVersion", snapshot.configVersion());
        detail.put("p95LimitMs", snapshot.p95LimitMs());
        try {
            probeResults.upsert(new UserAiProviderProbeResultRepository.ProbeResultRow(
                    provider.id(),
                    ProviderAdmissionSnapshot.MODE,
                    snapshot.status(),
                    provider.modelName(),
                    detail.toString(),
                    errorCode,
                    snapshot.p95Ms(),
                    provider.updatedAt(),
                    provider.capabilityConfigVersion(),
                    null));
            return true;
        } catch (RuntimeException persistenceFailure) {
            log.warn("Failed to persist provider admission for {}: {}",
                    provider.id(), persistenceFailure.getMessage());
            return false;
        }
    }

    private static BusinessException admissionFailure(
            ProviderAdmissionSnapshot snapshot, List<Exception> failures) {
        String summary = metricSummary(snapshot);
        if (!failures.isEmpty()) {
            Exception first = failures.getFirst();
            String message = first.getMessage() == null || first.getMessage().isBlank()
                    ? "供应商连接测试失败"
                    : first.getMessage();
            ErrorCode code = first instanceof BusinessException business
                    ? business.getErrorCode()
                    : ErrorCode.AI_PROVIDER_ERROR;
            return new BusinessException(code, message + "（" + summary + "）");
        }
        return new BusinessException(ErrorCode.VALIDATION_ERROR,
                "供应商延迟准入未通过：" + summary + "；请检查网络或稍后重试");
    }

    private static String metricSummary(ProviderAdmissionSnapshot snapshot) {
        return "samples=" + snapshot.samples()
                + ", p50=" + snapshot.p50Ms() + "ms"
                + ", p95=" + snapshot.p95Ms() + "ms"
                + " (limit " + snapshot.p95LimitMs() + "ms)"
                + ", timeouts=" + snapshot.timeoutCount()
                + ", failures=" + snapshot.failureCount()
                + ", configVersion=" + snapshot.configVersion();
    }

    private static boolean isTimeout(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 8; depth++) {
            if (current instanceof BusinessException business
                    && business.getErrorCode() == ErrorCode.AI_MODEL_TIMEOUT) {
                return true;
            }
            if (current instanceof java.net.http.HttpTimeoutException
                    || current instanceof java.net.SocketTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String errorCodeOf(Exception failure) {
        return failure instanceof BusinessException business
                ? business.getErrorCode().name()
                : ErrorCode.AI_PROVIDER_ERROR.name();
    }

    /** 最近秩法：P95 取升序样本中第 ceil(0.95*n) 个，样本少时不外推容量。 */
    private static long percentile(List<Long> values, int percentile) {
        if (values.isEmpty()) return 0;
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compareTo);
        int rank = (int) Math.ceil(percentile / 100.0 * sorted.size());
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, rank - 1)));
    }

    private UserAiProvider requireSource(UUID adminUserId, UUID id) {
        return userProviderRepository.findByIdAndUserId(id, adminUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_ERROR, "指定的供应商配置不存在"));
    }

    private static void requireChatCompatible(ModelProviderType providerType) {
        if (providerType == null || !providerType.chatCompatible()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "LOCAL_CUSTOM 只能通过能力 Provider 管理入口配置");
        }
    }

    private String resolveEncryptedKey(String existingEncrypted, String newApiKey) {
        if (newApiKey == null || newApiKey.isBlank()) return existingEncrypted;
        String normalized = newApiKey.strip();
        String storedPlain;
        try {
            storedPlain = secrets.decryptOptional(existingEncrypted);
        } catch (RuntimeException unreadableStoredKey) {
            storedPlain = null;
        }
        if (normalized.equals(storedPlain)) return existingEncrypted;
        return secrets.encrypt(normalized);
    }

    private void validateEndpoint(String baseUrl, String apiPath) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Base URL 不能为空");
        }
        URI base;
        try {
            base = URI.create(baseUrl.strip());
        } catch (RuntimeException malformed) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Base URL 格式无效，请填写完整的 HTTPS 地址");
        }
        try {
            endpoints.requirePublicHttps(base);
        } catch (RuntimeException unsafe) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Base URL 不符合出站安全策略：仅允许公网 HTTPS 地址（禁止内网、环回与链路本地地址）");
        }
    }
}
