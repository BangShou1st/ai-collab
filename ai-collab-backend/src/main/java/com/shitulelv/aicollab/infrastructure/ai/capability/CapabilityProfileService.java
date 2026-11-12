package com.shitulelv.aicollab.infrastructure.ai.capability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.infrastructure.ai.capability.api.CapabilityProfileRequest;
import com.shitulelv.aicollab.infrastructure.ai.capability.api.CapabilityProfileView;
import com.shitulelv.aicollab.infrastructure.ai.capability.api.CapabilityTestDocument;
import com.shitulelv.aicollab.infrastructure.ai.capability.api.CapabilityTestRequest;
import com.shitulelv.aicollab.infrastructure.ai.capability.api.CapabilityTestResponse;
import com.shitulelv.aicollab.infrastructure.ai.capability.api.CapabilityTestScoreView;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelSecretCipher;
import com.shitulelv.aicollab.infrastructure.ai.model.profile.ModelProfile;
import com.shitulelv.aicollab.infrastructure.ai.model.profile.ModelProfileRepository;
import com.shitulelv.aicollab.project.application.service.AuditService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 能力档案生命周期：创建 DRAFT → 测试 TESTED → 发布 PUBLISHED → 回滚/禁用。
 *
 * <p>settings 只在 DRAFT 创建时写入；PUBLISHED 行由数据库触发器保护，不能原地改
 * settings。测试必须经过真实 {@link LlamaCppRerankAdapter}，失败不得进入 TESTED。</p>
 */
@Service
public class CapabilityProfileService {

    private static final String DEFAULT_QUERY = "What is the capital of France?";
    private static final List<CapabilityTestDocument> DEFAULT_DOCUMENTS = List.of(
            new CapabilityTestDocument("relevant", "Geography",
                    "Paris is the capital of France."),
            new CapabilityTestDocument("irrelevant", "Fruit",
                    "Bananas are yellow and grow in tropical climates."));

    private final CapabilityProfileRepository profiles;
    private final CapabilityProviderRepository providers;
    private final CapabilityAssignmentRepository assignments;
    private final ModelProfileRepository modelProfiles;
    private final RerankSettingsSchema settingsSchema;
    private final LlamaCppRerankAdapter adapter;
    private final ModelSecretCipher secrets;
    private final AuditService audit;
    private final ObjectMapper objectMapper;

    public CapabilityProfileService(CapabilityProfileRepository profiles,
            CapabilityProviderRepository providers, CapabilityAssignmentRepository assignments,
            ModelProfileRepository modelProfiles, RerankSettingsSchema settingsSchema,
            LlamaCppRerankAdapter adapter, ModelSecretCipher secrets, AuditService audit,
            ObjectMapper objectMapper) {
        this.profiles = profiles;
        this.providers = providers;
        this.assignments = assignments;
        this.modelProfiles = modelProfiles;
        this.settingsSchema = settingsSchema;
        this.adapter = adapter;
        this.secrets = secrets;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    public List<CapabilityProfileView> list() {
        return profiles.findAll().stream().map(this::view).toList();
    }

    public CapabilityProfileView get(UUID id) {
        return view(require(id));
    }

    @Transactional
    public CapabilityProfileView create(UUID adminId, CapabilityProfileRequest request) {
        if (request.capability() != CapabilityType.RERANK) {
            throw invalid("本波只支持 RERANK capability");
        }
        if (request.protocol() != CapabilityProtocol.LLAMACPP_RERANK) {
            throw invalid("RERANK 当前只支持 LLAMACPP_RERANK 协议");
        }
        RerankSettings settings = validateSettings(request.settings(), request.protocol());
        ModelProfile modelProfile = modelProfiles.find(request.modelProfileId())
                .orElseThrow(() -> new BusinessException(ErrorCode.MODEL_PROFILE_NOT_FOUND));
        CapabilityProvider provider = providers.findById(request.providerId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CAPABILITY_PROVIDER_NOT_FOUND));
        if (!provider.protocol().equals(request.protocol().name())) {
            throw invalid("Provider 主协议与 capability protocol 不一致");
        }
        int version = profiles.nextVersion(modelProfile.id(), request.capability());
        String source = request.source() == null ? "ADMIN_VERIFIED" : request.source();
        try {
            CapabilityProfile created = profiles.create(adminId, modelProfile.id(), provider.id(),
                    request.capability(), request.protocol().name(),
                    settings.toJson(objectMapper).toString(), version, source);
            audit(adminId, "AI_CAPABILITY_PROFILE_CREATED", created, request.reason());
            return view(created);
        } catch (DataIntegrityViolationException conflict) {
            throw new BusinessException(ErrorCode.CAPABILITY_PROFILE_CONFLICT,
                    "能力档案版本冲突，请刷新后重试");
        }
    }

    /**
     * 执行一次真实能力测试。自定义 query/documents 用于 Lane B 的 8/20/50 候选测量；
     * 不传时执行冻结的两文档相关性 smoke test。一次请求只调用一次 provider /rerank。
     */
    @Transactional
    public CapabilityTestResponse test(UUID adminId, UUID profileId, CapabilityTestRequest request) {
        CapabilityProfile profile = require(profileId);
        CapabilityProvider provider = providers.findById(profile.providerId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CAPABILITY_PROVIDER_NOT_FOUND));
        RerankSettings settings = parseSettings(profile);
        TestInput input = testInput(request);
        String apiKey = resolveApiKey(provider);

        RerankCallResult result = adapter.rerank(provider, settings, input.query(), input.documents(),
                input.topK(), apiKey);
        boolean relevanceOrderProven = true;
        if (result.success() && input.defaultDocuments()) {
            double relevant = result.scores().stream()
                    .filter(score -> "relevant".equals(score.id()))
                    .mapToDouble(RerankScore::score).findFirst().orElse(Double.NaN);
            double irrelevant = result.scores().stream()
                    .filter(score -> "irrelevant".equals(score.id()))
                    .mapToDouble(RerankScore::score).findFirst().orElse(Double.NaN);
            relevanceOrderProven = Double.isFinite(relevant) && Double.isFinite(irrelevant)
                    && relevant > irrelevant;
        }

        boolean lifecycleSuccess = result.success() && relevanceOrderProven;
        if (lifecycleSuccess && (profile.status() == CapabilityProfileStatus.DRAFT
                || profile.status() == CapabilityProfileStatus.TESTED)) {
            profiles.markTested(profile.id(), adminId);
            profile = require(profile.id());
        }
        CapabilityTestResponse response = response(profile, result, relevanceOrderProven);
        auditTest(adminId, profile, response, request == null ? null : request.reason());
        return response;
    }

    @Transactional
    public CapabilityProfileView publish(UUID adminId, UUID profileId, String reason) {
        CapabilityProfile target = lock(profileId);
        if (target.status() != CapabilityProfileStatus.TESTED) {
            throw conflict("只有 TESTED 能力档案可以发布");
        }
        CapabilityProvider provider = requireProvider(target.providerId());
        parseSettings(target);
        CapabilityProfile previous = profiles.findPublishedForUpdate(target.modelProfileId(),
                target.capability()).orElse(null);
        if (previous != null && !previous.id().equals(target.id())
                && !profiles.demotePublished(previous.id(), adminId)) {
            throw conflict("上一发布版本状态已被其它请求修改");
        }
        try {
            if (!profiles.markPublished(target.id(), adminId)) {
                throw conflict("能力档案状态已被其它请求修改");
            }
        } catch (DataIntegrityViolationException duplicatePublished) {
            throw conflict("同一模型/能力已有其它 PUBLISHED 版本");
        }
        int repointed = previous == null ? 0
                : assignments.repointProfile(previous.id(), target.id());
        CapabilityProfile published = require(target.id());
        Map<String, Object> detail = commonDetail(published);
        detail.put("fromVersion", previous == null ? null : previous.version());
        detail.put("toVersion", published.version());
        detail.put("repointedAssignments", repointed);
        detail.put("providerName", provider.name());
        if (reason != null && !reason.isBlank()) {
            detail.put("reason", reason.strip());
        }
        writeAudit(adminId, "AI_CAPABILITY_PROFILE_PUBLISHED", published, detail);
        return view(published);
    }

    @Transactional
    public CapabilityProfileView rollback(UUID adminId, UUID currentPublishedId, String reason) {
        CapabilityProfile current = lock(currentPublishedId);
        if (current.status() != CapabilityProfileStatus.PUBLISHED) {
            throw conflict("只有当前 PUBLISHED 版本可以回滚");
        }
        CapabilityProfile previous = profiles.findPreviousVerifiedForUpdate(current.modelProfileId(),
                        current.capability(), current.version())
                .orElseThrow(() -> conflict("没有可回滚的历史已发布版本"));
        if (!profiles.demotePublished(current.id(), adminId)) {
            throw conflict("当前发布版本状态已被其它请求修改");
        }
        if (!profiles.publishRollbackTarget(previous.id(), adminId)) {
            throw conflict("回滚目标状态已被其它请求修改");
        }
        int repointed = assignments.repointProfile(current.id(), previous.id());
        CapabilityProfile restored = require(previous.id());
        Map<String, Object> detail = commonDetail(restored);
        detail.put("fromVersion", current.version());
        detail.put("toVersion", restored.version());
        detail.put("repointedAssignments", repointed);
        if (reason != null && !reason.isBlank()) {
            detail.put("reason", reason.strip());
        }
        writeAudit(adminId, "AI_CAPABILITY_PROFILE_ROLLED_BACK", restored, detail);
        return view(restored);
    }

    @Transactional
    public CapabilityProfileView disable(UUID adminId, UUID profileId, String reason) {
        CapabilityProfile profile = lock(profileId);
        if (profile.status() != CapabilityProfileStatus.DISABLED) {
            if (!profiles.markDisabled(profile.id(), adminId)) {
                throw conflict("能力档案状态已被其它请求修改");
            }
            assignments.disableForProfile(profile.id());
        }
        CapabilityProfile disabled = require(profile.id());
        Map<String, Object> detail = commonDetail(disabled);
        if (reason != null && !reason.isBlank()) {
            detail.put("reason", reason.strip());
        }
        writeAudit(adminId, "AI_CAPABILITY_PROFILE_DISABLED", disabled, detail);
        return view(disabled);
    }

    private TestInput testInput(CapabilityTestRequest request) {
        if (request == null || (isBlank(request.query())
                && (request.documents() == null || request.documents().isEmpty()))) {
            return new TestInput(DEFAULT_QUERY, defaultDocuments(), request == null ? null : request.topK(),
                    true);
        }
        if (isBlank(request.query()) || request.documents() == null || request.documents().isEmpty()) {
            throw invalid("自定义测试必须同时提供 query 和 1-50 条 documents");
        }
        if (request.documents().size() > 50) {
            throw invalid("documents 不能超过 50 条");
        }
        Set<String> ids = new HashSet<>();
        List<RerankDocument> documents = new ArrayList<>(request.documents().size());
        for (CapabilityTestDocument document : request.documents()) {
            if (document == null || isBlank(document.id()) || isBlank(document.text())) {
                throw invalid("每条 document 必须包含非空 id 与 text");
            }
            if (!ids.add(document.id())) {
                throw invalid("documents.id 不能重复");
            }
            documents.add(new RerankDocument(document.id().strip(), document.title(), document.text()));
        }
        return new TestInput(request.query().strip(), documents, request.topK(), false);
    }

    private static List<RerankDocument> defaultDocuments() {
        return DEFAULT_DOCUMENTS.stream()
                .map(document -> new RerankDocument(document.id(), document.title(), document.text()))
                .toList();
    }

    private CapabilityTestResponse response(CapabilityProfile profile, RerankCallResult result,
            boolean relevanceOrderProven) {
        String outcome = result.success() && relevanceOrderProven ? "SUCCESS" : result.outcome();
        String reason = result.success() && !relevanceOrderProven
                ? "relevance_order_not_proven"
                : result.reason();
        List<CapabilityTestScoreView> scores = result.scores().stream()
                .map(score -> new CapabilityTestScoreView(score.index(), score.id(), score.score()))
                .toList();
        return new CapabilityTestResponse(profile.id(), profile.providerId(), profile.status().name(),
                profile.version(), outcome, reason, scores, result.modelLatencyMs(), result.totalMs(),
                result.overheadMs(), result.candidateCount(), result.topK());
    }

    private CapabilityProfileView view(CapabilityProfile profile) {
        ModelProfile model = modelProfiles.find(profile.modelProfileId()).orElse(null);
        CapabilityProvider provider = providers.findById(profile.providerId()).orElse(null);
        RerankSettings settings;
        try {
            settings = parseSettings(profile);
        } catch (BusinessException invalidSettings) {
            throw invalidSettings;
        }
        return CapabilityProfileView.from(profile,
                model == null ? null : model.modelKey(),
                model == null ? null : model.displayName(),
                provider == null ? null : provider.name(),
                settings.toJson(objectMapper));
    }

    private RerankSettings parseSettings(CapabilityProfile profile) {
        try {
            return settingsSchema.parse(profile.settingsJson());
        } catch (RerankSettingsSchema.RerankSettingsInvalidException invalid) {
            throw invalid("已保存的 settings_json 不合法: " + invalid.getMessage());
        }
    }

    private RerankSettings validateSettings(JsonNode raw, CapabilityProtocol protocol) {
        try {
            RerankSettings settings = settingsSchema.validate(raw);
            if (!settings.protocol().equals(protocol.name())) {
                throw invalid("settings.protocol 必须与 capability protocol 一致");
            }
            return settings;
        } catch (RerankSettingsSchema.RerankSettingsInvalidException invalid) {
            throw invalid(invalid.getMessage());
        }
    }

    private String resolveApiKey(CapabilityProvider provider) {
        if (!provider.requiresAuth()) {
            return null;
        }
        try {
            return secrets.decrypt(provider.encryptedApiKey());
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    private CapabilityProfile require(UUID id) {
        return profiles.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.CAPABILITY_PROFILE_NOT_FOUND));
    }

    private CapabilityProfile lock(UUID id) {
        return profiles.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.CAPABILITY_PROFILE_NOT_FOUND));
    }

    private CapabilityProvider requireProvider(UUID id) {
        return providers.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.CAPABILITY_PROVIDER_NOT_FOUND));
    }

    private void auditTest(UUID adminId, CapabilityProfile profile, CapabilityTestResponse response,
            String reason) {
        Map<String, Object> detail = commonDetail(profile);
        detail.put("outcome", response.outcome());
        if (response.reason() != null) {
            detail.put("fallbackReason", response.reason());
        }
        detail.put("candidateCount", response.candidateCount());
        detail.put("modelLatencyMs", response.modelLatencyMs());
        detail.put("totalMs", response.totalMs());
        if (reason != null && !reason.isBlank()) {
            detail.put("reason", reason.strip());
        }
        writeAudit(adminId, "AI_CAPABILITY_PROFILE_TESTED", profile, detail);
    }

    private void audit(UUID adminId, String action, CapabilityProfile profile, String reason) {
        Map<String, Object> detail = commonDetail(profile);
        if (reason != null && !reason.isBlank()) {
            detail.put("reason", reason.strip());
        }
        writeAudit(adminId, action, profile, detail);
    }

    private static Map<String, Object> commonDetail(CapabilityProfile profile) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("profileId", profile.id().toString());
        detail.put("modelProfileId", profile.modelProfileId().toString());
        detail.put("providerId", profile.providerId().toString());
        detail.put("capability", profile.capability().name());
        detail.put("protocol", profile.protocol());
        detail.put("version", profile.version());
        detail.put("status", profile.status().name());
        return detail;
    }

    private void writeAudit(UUID adminId, String action, CapabilityProfile profile,
            Map<String, Object> detail) {
        if (audit != null) {
            audit.write(null, adminId, action, "AI_MODEL_CAPABILITY_PROFILE", profile.id(), detail);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.CAPABILITY_PROFILE_INVALID, message);
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CAPABILITY_PROFILE_CONFLICT, message);
    }

    private record TestInput(String query, List<RerankDocument> documents, Integer topK,
            boolean defaultDocuments) {
    }
}
