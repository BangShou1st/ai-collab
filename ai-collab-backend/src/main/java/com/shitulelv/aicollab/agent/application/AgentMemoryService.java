package com.shitulelv.aicollab.agent.application;

import com.shitulelv.aicollab.agent.api.dto.AgentMemoryRequest;
import com.shitulelv.aicollab.agent.application.view.AgentMemoryView;
import com.shitulelv.aicollab.agent.domain.model.AgentMemorySourceType;
import com.shitulelv.aicollab.agent.domain.model.AgentMemoryVerificationState;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentMemoryRepository;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import com.shitulelv.aicollab.project.application.service.AuditService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class AgentMemoryService {
    private final AgentMemoryRepository repository;
    private final ProjectAccessGuard access;
    private final AgentMemorySourceVerifier sourceVerifier;
    private final AuditService audit;
    @org.springframework.beans.factory.annotation.Value("${agent.context.relevant-memory:true}")
    private boolean relevantMemory = true;
    public AgentMemoryService(AgentMemoryRepository repository, ProjectAccessGuard access,
                              AgentMemorySourceVerifier sourceVerifier) {
        this(repository, access, sourceVerifier, null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public AgentMemoryService(AgentMemoryRepository repository, ProjectAccessGuard access,
                              AgentMemorySourceVerifier sourceVerifier, AuditService audit) {
        this.repository = repository; this.access = access; this.sourceVerifier = sourceVerifier; this.audit = audit;
    }

    /** activeOnly=true(召回/工具路径)同时过滤 STALE/CONFLICTED/SOURCE_REVOKED;=false 为管理视图,不过滤便于复核。 */
    public List<AgentMemoryView> list(UUID projectId, UUID userId, boolean activeOnly) {
        access.requireMember(projectId, userId);
        return repository.list(projectId, activeOnly, 100, activeOnly);
    }

    public List<AgentMemoryView> context(UUID projectId) { return repository.list(projectId, true, 10, true); }

    public List<AgentMemoryView> context(UUID projectId, UUID userId, String request) {
        access.requireMember(projectId,userId);
        if(!relevantMemory) return context(projectId);
        var terms=terms(request);
        return repository.list(projectId,true,100,true).stream()
                .filter(memory -> score(memory,terms)>0)
                .sorted(java.util.Comparator.<AgentMemoryView>comparingInt(memory -> score(memory,terms)).reversed()
                        .thenComparing(AgentMemoryView::updatedAt,java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder()))
                        .thenComparing(memory -> memory.id().toString()))
                .limit(10).toList();
    }
    private static int score(AgentMemoryView memory, java.util.Set<String> terms) {
        String title=memory.title().toLowerCase(java.util.Locale.ROOT),content=memory.content().toLowerCase(java.util.Locale.ROOT);
        return ("PREFERENCE".equals(memory.type())?1:0)+terms.stream().mapToInt(term -> title.contains(term)?4:content.contains(term)?1:0).sum();
    }
    private static java.util.Set<String> terms(String request) {
        var result=new java.util.HashSet<String>();
        var matcher=java.util.regex.Pattern.compile("[a-z0-9_]{2,}|[\\p{IsHan}]{2,}").matcher(java.util.Objects.toString(request,"").toLowerCase(java.util.Locale.ROOT));
        while(matcher.find()) {
            String token=matcher.group();
            if(Character.UnicodeScript.of(token.codePointAt(0))==Character.UnicodeScript.HAN) {
                int[] points=token.codePoints().toArray();
                for(int i=0;i+1<points.length;i++) result.add(new String(points,i,2));
            } else result.add(token);
        }
        result.removeAll(java.util.Set.of("项目","目标","现在","换成","换个","改成","生成","规划","任务","继续","一个","根据","请问"));
        return result;
    }

    /** 创建:来源类型必须在允许集合内,可核验来源必须通过权威校验;验证状态恒为 UNVERIFIED,不接受客户端指定。 */
    @Transactional public AgentMemoryView create(UUID projectId, UUID userId, AgentMemoryRequest request) {
        access.requireAdmin(projectId, userId);
        AgentMemorySourceType.parse(request.sourceType());
        sourceVerifier.verify(projectId, request.sourceType(), request.sourceId());
        if (request.verificationState() != null) throw new BusinessException(ErrorCode.AGENT_MEMORY_VERIFICATION_INVALID);
        AgentMemoryView created = repository.create(projectId, userId, request.type(), request.title().strip(),
                request.content().strip(), request.sourceType().strip(), request.sourceId());
        audit(projectId, userId, "AGENT_MEMORY_CREATED", created.id(), created.type());
        return created;
    }

    /** 更新:来源类型/来源核验同创建;验证状态转移见 {@link #resolveTransition},来源变更时强制重置为 UNVERIFIED。 */
    @Transactional public AgentMemoryView update(UUID projectId, UUID id, UUID userId, AgentMemoryRequest request) {
        access.requireAdmin(projectId, userId);
        AgentMemoryView current = require(projectId, id);
        AgentMemorySourceType.parse(request.sourceType());
        sourceVerifier.verify(projectId, request.sourceType(), request.sourceId());
        boolean sourceChanged = !Objects.equals(current.sourceType(), request.sourceType().strip())
                || !Objects.equals(current.sourceId(), request.sourceId());
        Transition transition = resolveTransition(current, request.verificationState(), sourceChanged, userId);
        if (!repository.update(projectId, id, userId, request.version(), request.type(),
                request.title().strip(), request.content().strip(), request.sourceType().strip(), request.sourceId(),
                transition.state(), transition.verifiedAt(), transition.verifiedBy()))
            throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        audit(projectId, userId, "AGENT_MEMORY_UPDATED", id, request.type());
        return require(projectId, id);
    }

    @Transactional public AgentMemoryView disable(UUID projectId, UUID id, UUID userId, int version) {
        return disable(projectId, id, userId, version, null);
    }

    /** disable 可选携带验证状态转移(VERIFIED/UNVERIFIED/STALE),单条 CAS 更新完成。 */
    @Transactional public AgentMemoryView disable(UUID projectId, UUID id, UUID userId, int version, String verificationState) {
        access.requireAdmin(projectId, userId);
        AgentMemoryView current = require(projectId, id);
        if (verificationState == null) {
            if (!repository.disable(projectId, id, userId, version)) throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        } else {
            Transition transition = resolveTransition(current, verificationState, false, userId);
            if (!repository.disable(projectId, id, userId, version,
                    transition.state(), transition.verifiedAt(), transition.verifiedBy()))
                throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        }
        audit(projectId, userId, "AGENT_MEMORY_DISABLED", id, "DISABLED");
        return require(projectId, id);
    }

    /**
     * 验证状态转移规则:
     * <ul>
     *   <li>来源(sourceType/sourceId)变更 → 强制 UNVERIFIED 并清空核验记录,即使请求要求 VERIFIED(新来源必须重新核验);</li>
     *   <li>请求为 null → 维持现值;</li>
     *   <li>VERIFIED → 记录 verified_at/verified_by(管理员显式核验,不伪造"模型已验证");</li>
     *   <li>UNVERIFIED → 清空核验记录;STALE → 保留最近一次核验记录;</li>
     *   <li>CONFLICTED/SOURCE_REVOKED/未知值 → 拒绝(系统派生状态,不开放 API 写入)。</li>
     * </ul>
     */
    private static Transition resolveTransition(AgentMemoryView current, String requested,
                                                boolean sourceChanged, UUID userId) {
        if (sourceChanged) return new Transition(AgentMemoryVerificationState.UNVERIFIED, null, null);
        if (requested == null) return new Transition(parseState(current.verificationState()),
                current.verifiedAt(), current.verifiedBy());
        AgentMemoryVerificationState target = AgentMemoryVerificationState.adminSettable(requested)
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_MEMORY_VERIFICATION_INVALID));
        return switch (target) {
            case VERIFIED -> new Transition(target, OffsetDateTime.now(), userId);
            case UNVERIFIED -> new Transition(target, null, null);
            case STALE -> new Transition(target, current.verifiedAt(), current.verifiedBy());
            default -> throw new BusinessException(ErrorCode.AGENT_MEMORY_VERIFICATION_INVALID);
        };
    }

    private static AgentMemoryVerificationState parseState(String raw) {
        try { return AgentMemoryVerificationState.valueOf(raw); }
        catch (RuntimeException exception) { return AgentMemoryVerificationState.UNVERIFIED; }
    }

    private record Transition(AgentMemoryVerificationState verificationState, OffsetDateTime verifiedAt, UUID verifiedBy) {
        private String state() { return verificationState.name(); }
    }

    private AgentMemoryView require(UUID projectId, UUID id) {
        return repository.find(projectId, id).orElseThrow(() -> new BusinessException(ErrorCode.AGENT_MEMORY_NOT_FOUND));
    }
    private void audit(UUID projectId, UUID userId, String action, UUID id, String type) {
        if (audit != null) audit.write(projectId, userId, action, "AGENT_MEMORY", id,
                java.util.Map.of("type", type));
    }
}
