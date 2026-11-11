package com.shitulelv.aicollab.agent.job;

import com.shitulelv.aicollab.internal.ai.token.Ed25519Keys;
import com.shitulelv.aicollab.internal.ai.token.InternalTokenIssuer;
import com.shitulelv.aicollab.internal.ai.token.InternalTokenProperties;
import org.springframework.stereotype.Component;

import java.security.PrivateKey;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Job-scoped Ed25519 令牌签发（W5 冻结 §3.4/§7.8）。
 *
 * <p>claims 固定包含 {@code jobId/attempt/leaseEpoch/projectRef/snapshotHash}，
 * scope 为 {@code job:execute job:snapshot job:heartbeat job:results model-call}，
 * TTL 900s。scope 只用于内部端点绑定，<b>不是业务权限</b>；Python 不因此获得任何写库能力。</p>
 *
 * <p>{@code internal.ai.token.mode=static} 时没有 Ed25519 私钥，本服务返回
 * {@link Optional#empty()}；Job 内部端点因此对无 claims 请求 fail closed（503）。</p>
 *
 * <p><b>日志红线</b>：本类不打印令牌、claims 值或私钥。</p>
 */
@Component
public class AiJobTokenService {

    static final List<String> AUDIENCES = List.of(
            InternalTokenIssuer.AUDIENCE_AI_WORKER, InternalTokenIssuer.AUDIENCE_BACKEND);
    static final List<String> SCOPES = List.of(
            "job:execute", "job:snapshot", "job:heartbeat", "job:results",
            InternalTokenIssuer.SCOPE_MODEL_CALL);

    /** 冻结 §3.4：job token TTL 固定 900s。 */
    static final long JOB_TOKEN_TTL_SECONDS = 900L;

    private final InternalTokenProperties properties;
    private volatile InternalTokenIssuer issuer;
    private volatile boolean issuerInitialized;

    public AiJobTokenService(InternalTokenProperties properties) {
        this.properties = properties;
    }

    /**
     * 为一次已租约的 Job 签发令牌。
     *
     * @param snapshotHash 派发时的快照哈希（只作审计/一致性线索，控制器不用它做路径绑定）
     */
    public Optional<String> jobToken(UUID jobId, int attempt, long leaseEpoch, UUID projectRef,
            String snapshotHash) {
        InternalTokenIssuer signer = issuer();
        if (signer == null) {
            return Optional.empty();
        }
        Map<String, Object> extraClaims = new LinkedHashMap<>();
        extraClaims.put("jobId", jobId == null ? "" : jobId.toString());
        extraClaims.put("attempt", Math.max(0, attempt));
        extraClaims.put("leaseEpoch", Math.max(0L, leaseEpoch));
        extraClaims.put("projectRef", projectRef == null ? "" : projectRef.toString());
        extraClaims.put("snapshotHash", snapshotHash == null ? "" : snapshotHash);
        return Optional.of(signer.issue(AUDIENCES, SCOPES, extraClaims, JOB_TOKEN_TTL_SECONDS));
    }

    /** static 模式或私钥缺失时返回 null（不缓存失败之外的状态，也不打印密钥）。 */
    private InternalTokenIssuer issuer() {
        if (issuerInitialized) {
            return issuer;
        }
        synchronized (this) {
            if (issuerInitialized) {
                return issuer;
            }
            if (properties.mode().requiresPrivateKey() && !properties.privateKey().isBlank()) {
                PrivateKey key = Ed25519Keys.parsePrivateKey(properties.privateKey());
                issuer = new InternalTokenIssuer(key, properties.kid(),
                        properties.ttlSeconds());
            }
            issuerInitialized = true;
            return issuer;
        }
    }
}
