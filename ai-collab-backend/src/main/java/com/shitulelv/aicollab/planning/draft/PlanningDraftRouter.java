package com.shitulelv.aicollab.planning.draft;

import com.shitulelv.aicollab.planning.application.GenerationResult;
import com.shitulelv.aicollab.planning.domain.PlanMilestone;
import com.shitulelv.aicollab.planning.domain.PlanTask;
import com.shitulelv.aicollab.planning.domain.TaskPlanDraft;
import com.shitulelv.aicollab.planning.domain.TaskPlanDraftValidator;
import com.shitulelv.aicollab.planning.domain.TaskPlanDraftValidator.ValidationMode;
import com.shitulelv.aicollab.planning.domain.TaskPlanStatus;
import com.shitulelv.aicollab.planning.domain.ValidationAssessment;
import com.shitulelv.aicollab.planning.domain.ValidationContext;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanRecord;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Planning 的受限 Python 主链路由器。
 *
 * <p>当前只接管 <b>skeleton</b> 阶段：Python 返回的是完整草稿，但该契约没有携带 Java
 * skeleton 的身份与 {@code S1..S12} 来源命名空间；若直接用于 detail 会把“来源映射”与
 * “骨架不可变”变成隐性假设。因此 detail 继续走 Java，Python 结果只经
 * {@link PlanningDraftParser} 转成 skeleton 身份并<b>重跑</b>
 * {@link TaskPlanDraftValidator}({@link ValidationMode#AI_SKELETON})。</p>
 *
 * <p>本类不落库、不创建审批、不改状态机；合法候选只返回给既有
 * {@code TaskPlanGenerationOrchestrator}，由后者沿原有 version/attempt/确认链提交。
 * 任意失败、超时、取消或 Validator 不通过都回退 Java 生成。</p>
 */
@Service
public class PlanningDraftRouter {

    private static final Logger log = LoggerFactory.getLogger(PlanningDraftRouter.class);

    static final String STAGE_SKELETON = "SKELETON";
    static final String PROVIDER = "PYTHON_PLANNING_DRAFT";
    static final String MODEL = "ai-worker";
    static final String METRIC_CALLS = "planning.draft.router.calls";
    static final String METRIC_FALLBACK = "planning.draft.router.fallback";
    static final String METRIC_LATENCY = "planning.draft.router.latency";

    private final PlanningDraftProperties properties;
    private final PlanningDraftGatewayClient gateway;
    private final PlanningDraftShadowService snapshots;
    private final TaskPlanRepository repository;
    private final TaskPlanDraftValidator validator;
    private final MeterRegistry meterRegistry;
    private final PlanningDraftParser parser = new PlanningDraftParser();

    public PlanningDraftRouter(
            PlanningDraftProperties properties,
            PlanningDraftGatewayClient gateway,
            PlanningDraftShadowService snapshots,
            TaskPlanRepository repository,
            TaskPlanDraftValidator validator,
            MeterRegistry meterRegistry) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
    }

    /** java/shadow 不进入主链，也不产生任何额外 DB/外呼/指标。 */
    public boolean enabledForSkeleton() {
        return properties.mode() == PlanningDraftMode.PYTHON;
    }

    /**
     * 尝试用 Python 草稿替换 skeleton 生成。
     *
     * @param remainingMillis 当前规划任务剩余预算；无运行级预算时传 {@link Long#MAX_VALUE}
     */
    public Route routeSkeleton(
            TaskPlanRecord plan,
            UUID attemptId,
            ValidationContext context,
            long remainingMillis) {
        if (!enabledForSkeleton()) {
            return Route.disabled();
        }
        if (plan == null || plan.id() == null || attemptId == null || context == null) {
            return fallback(plan, attemptId, "empty");
        }
        Boolean active = active(plan, attemptId);
        if (active == null) {
            return fallback(plan, attemptId, "active_check");
        }
        if (!active) {
            return canceled(plan, attemptId);
        }
        if (remainingMillis <= 0) {
            return fallback(plan, attemptId, "deadline");
        }

        long boundedDeadline = Math.max(1, Math.min(properties.deadlineMs(), remainingMillis));
        int deadlineMs = (int) Math.min(Integer.MAX_VALUE, boundedDeadline);
        PlanningDraftShadowService.BoundedRequest bounded;
        try {
            bounded = snapshots.buildRequest(plan, context, deadlineMs);
        } catch (RuntimeException snapshotFailure) {
            return fallback(plan, attemptId, "snapshot");
        }
        if (bounded == null) {
            return fallback(plan, attemptId, "snapshot");
        }

        long started = System.nanoTime();
        try {
            PlanningDraftResponse response = gateway.draft(bounded.request());
            Boolean stillActive = active(plan, attemptId);
            if (stillActive == null) {
                recordLatency("failure", System.nanoTime() - started);
                return fallback(plan, attemptId, "active_check");
            }
            if (!stillActive) {
                return canceled(plan, attemptId);
            }

            TaskPlanDraft pythonDraft;
            try {
                pythonDraft = parser.parse(response.plan(), fallbackSummary(plan));
            } catch (PlanningDraftParser.InvalidDraftException invalid) {
                recordLatency("failure", System.nanoTime() - started);
                return fallback(plan, attemptId, "schema");
            }

            TaskPlanDraft skeleton = skeletonOnly(pythonDraft);
            if (!validSkeletonShape(plan, skeleton)) {
                recordLatency("failure", System.nanoTime() - started);
                return fallback(plan, attemptId, "skeleton_shape");
            }

            ValidationAssessment assessment;
            try {
                assessment = validator.assess(context, skeleton, ValidationMode.AI_SKELETON, true);
            } catch (RuntimeException validatorFailure) {
                recordLatency("failure", System.nanoTime() - started);
                log.warn("planning python skeleton Validator 异常，回退 Java: type={}",
                        validatorFailure.getClass().getSimpleName());
                return fallback(plan, attemptId, "validator_exception");
            }
            if (!assessment.toFlat().valid()) {
                recordLatency("failure", System.nanoTime() - started);
                return fallback(plan, attemptId, "validator");
            }

            Integer promptTokens = response.usage() == null ? null : response.usage().inputTokens();
            Integer completionTokens = response.usage() == null ? null : response.usage().outputTokens();
            long elapsedMs = elapsedMillis(started);
            GenerationResult metrics = new GenerationResult(
                    "", PROVIDER, MODEL, elapsedMs, promptTokens, completionTokens);
            recordLatency("success", System.nanoTime() - started);
            recordCall("handled");
            diagnostic(plan, attemptId, "handled", null, "python:SKELETON", elapsedMs);
            return Route.handled(skeleton, metrics, "python:SKELETON");
        } catch (PlanningDraftGatewayException failure) {
            recordLatency("failure", System.nanoTime() - started);
            return fallback(plan, attemptId, "worker_" + failure.reason().tag());
        } catch (RuntimeException failure) {
            recordLatency("failure", System.nanoTime() - started);
            log.warn("planning python skeleton 未预期异常，回退 Java: type={}",
                    failure.getClass().getSimpleName());
            return fallback(plan, attemptId, "unexpected");
        }
    }

    private Route fallback(TaskPlanRecord plan, UUID attemptId, String reason) {
        recordCall("fallback");
        recordFallback(reason);
        diagnostic(plan, attemptId, "fallback", reason, "python_fallback:" + reason, 0L);
        return Route.fallback("python_fallback:" + reason, reason);
    }

    private Route canceled(TaskPlanRecord plan, UUID attemptId) {
        recordCall("canceled");
        diagnostic(plan, attemptId, "canceled", "canceled", "python_canceled", 0L);
        return Route.canceledRoute();
    }

    /**
     * 取消/活跃复核。返回 {@code null} 表示查询本身失败：调用方必须回退 Java，
     * 不能把一次数据库抖动误判成“用户已取消”。
     */
    private Boolean active(TaskPlanRecord plan, UUID attemptId) {
        try {
            return repository.active(
                    plan.id(), plan.generationSeq(), attemptId, TaskPlanStatus.SKELETON_GENERATING);
        } catch (RuntimeException failure) {
            log.warn("planning python skeleton 活跃复核失败，回退 Java: type={}",
                    failure.getClass().getSimpleName());
            return null;
        }
    }

    private void diagnostic(
            TaskPlanRecord plan, UUID attemptId, String outcome, String reason,
            String marker, long elapsedMs) {
        if (plan == null || plan.id() == null || attemptId == null) {
            return;
        }
        try {
            Map<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("kind", "PLANNING_DRAFT_ROUTE");
            entry.put("stage", STAGE_SKELETON);
            entry.put("outcome", outcome);
            if (reason != null) {
                entry.put("reason", reason);
            }
            if (marker != null) {
                entry.put("marker", marker);
            }
            entry.put("latencyMs", elapsedMs);
            repository.appendAttemptDiagnostic(plan.id(), attemptId, entry);
        } catch (RuntimeException ignored) {
            log.debug("planning draft route diagnostic 未写入: type={}",
                    ignored.getClass().getSimpleName());
        }
    }

    private static String fallbackSummary(TaskPlanRecord plan) {
        String goal = plan.goal();
        if (goal == null || goal.isBlank()) {
            goal = plan.title();
        }
        if (goal == null) {
            return "";
        }
        return goal.length() <= 2000 ? goal : goal.substring(0, 2000);
    }

    private static TaskPlanDraft skeletonOnly(TaskPlanDraft draft) {
        List<PlanMilestone> milestones = new ArrayList<>(draft.milestones().size());
        for (PlanMilestone milestone : draft.milestones()) {
            milestones.add(new PlanMilestone(
                    milestone.tempKey(), milestone.title(), milestone.objective(), null,
                    milestone.targetDate(), milestone.sortOrder(), List.of()));
        }
        List<PlanTask> tasks = new ArrayList<>(draft.tasks().size());
        for (PlanTask task : draft.tasks()) {
            tasks.add(new PlanTask(
                    task.tempKey(), task.milestoneTempKey(), task.title(), task.objective(),
                    null, null, null, null, null, null, null, List.of(), List.of(),
                    task.sortOrder()));
        }
        return new TaskPlanDraft(draft.summary(), draft.assumptions(), draft.risks(),
                milestones, tasks, List.of());
    }

    private static boolean validSkeletonShape(TaskPlanRecord plan, TaskPlanDraft draft) {
        if (draft.milestones().isEmpty() || draft.milestones().size() > 8
                || draft.tasks().isEmpty() || draft.tasks().size() > plan.maxTaskCount()) {
            return false;
        }
        Set<String> milestoneKeys = new HashSet<>();
        Set<String> allKeys = new HashSet<>();
        for (PlanMilestone milestone : draft.milestones()) {
            if (milestone == null || blank(milestone.tempKey()) || blank(milestone.title())
                    || blank(milestone.objective()) || !milestone.sourceRefs().isEmpty()
                    || !milestoneKeys.add(milestone.tempKey()) || !allKeys.add(milestone.tempKey())) {
                return false;
            }
        }
        for (PlanTask task : draft.tasks()) {
            if (task == null || blank(task.tempKey()) || blank(task.title())
                    || blank(task.objective()) || !allKeys.add(task.tempKey())
                    || !milestoneKeys.contains(task.milestoneTempKey())
                    || !task.dependencyTempKeys().isEmpty() || !task.sourceRefs().isEmpty()
                    || task.suggestedAssigneeId() != null || task.assigneeId() != null
                    || !blank(task.description()) || task.priority() != null
                    || task.estimatedHours() != null || task.startDate() != null
                    || task.dueDate() != null) {
                return false;
            }
        }
        return true;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private void recordCall(String outcome) {
        counter(METRIC_CALLS, "stage", STAGE_SKELETON, "outcome", outcome);
    }

    private void recordFallback(String reason) {
        counter(METRIC_FALLBACK, "reason", reason);
    }

    private void counter(String name, String... tags) {
        try {
            meterRegistry.counter(name, tags).increment();
        } catch (RuntimeException ignored) {
            // 指标失败不影响业务。
        }
    }

    private void recordLatency(String outcome, long nanos) {
        try {
            meterRegistry.timer(METRIC_LATENCY, "outcome", outcome)
                    .record(nanos, TimeUnit.NANOSECONDS);
        } catch (RuntimeException ignored) {
            // 指标失败不影响业务。
        }
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /**
     * 路由结果。{@code disabled} 表示当前模式不参与主链；{@code canceled} 表示
     * 调用前后发现规划 attempt 已不活跃，调用方必须停止并让既有状态机收口。
     */
    public record Route(
            boolean handled,
            boolean canceled,
            TaskPlanDraft draft,
            GenerationResult metrics,
            String marker,
            String reason) {

        static Route disabled() {
            return new Route(false, false, null, null, null, null);
        }

        static Route handled(TaskPlanDraft draft, GenerationResult metrics, String marker) {
            return new Route(true, false, draft, metrics, marker, "python");
        }

        static Route fallback(String marker, String reason) {
            return new Route(false, false, null, null, marker, reason);
        }

        static Route canceledRoute() {
            return new Route(false, true, null, null, "python_canceled", "canceled");
        }
    }
}
