package com.shitulelv.aicollab.planning.draft;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.planning.domain.StructuredValidationIssue;
import com.shitulelv.aicollab.planning.domain.TaskPlanDraft;
import com.shitulelv.aicollab.planning.domain.TaskPlanDraftValidator;
import com.shitulelv.aicollab.planning.domain.TaskPlanDraftValidator.ValidationMode;
import com.shitulelv.aicollab.planning.domain.ValidationAssessment;
import com.shitulelv.aicollab.planning.domain.ValidationContext;
import com.shitulelv.aicollab.planning.domain.ValidationIssueSeverity;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanRecord;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanRepository;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Planning draft 的 <b>只读影子观察</b>服务（W5 冻结 §2）。
 *
 * <p><b>边界</b>：只读取已生成的 Java 草稿与项目有界事实，构造有界请求并异步调用 Python
 * worker；Python 草稿<b>必须重跑</b> {@link TaskPlanDraftValidator#assess(ValidationContext,
 * TaskPlanDraft, ValidationMode, boolean)} 后才记指标。服务<b>不落库、不发事件、不改用户结果、
 * 不合并 Python 草稿</b>；任何异常都在本服务内吞掉，调用方行为不变。</p>
 *
 * <p><b>默认零成本</b>：{@code planning.draft.mode=java}（默认）时在方法入口直接返回，
 * 不读数据库、不构造快照、不取信号量、不记录任何指标。</p>
 *
 * <p><b>日志红线</b>：只记录失败原因与耗时；不记录 goal、项目事实、响应正文或令牌。</p>
 */
@Service
public class PlanningDraftShadowService {

    private static final Logger log = LoggerFactory.getLogger(PlanningDraftShadowService.class);

    static final String METRIC_CALLS = "planning.draft.shadow.calls";
    static final String METRIC_SCHEMA_PASS = "planning.draft.shadow.schema_pass";
    static final String METRIC_ISSUES = "planning.draft.shadow.issues";
    static final String METRIC_LATENCY = "planning.draft.shadow.latency";
    static final String METRIC_FAILURE = "planning.draft.shadow.failure";
    static final String METRIC_SKIPPED = "planning.draft.shadow.skipped";
    static final String METRIC_TRUNCATED = "planning.draft.shadow.truncated";

    static final String LOCALE = "zh-CN";
    static final String TEMPLATE = "ITERATION";
    static final int MAX_GOAL_CHARS = 4000;
    static final int MAX_PROJECT_NAME_CHARS = 200;
    static final int MAX_DESCRIPTION_CHARS = 4000;
    static final int MAX_MEMBERS = 50;
    static final int MAX_MEMBER_NAME_CHARS = 100;
    static final int MAX_TITLES = 40;
    static final int MAX_TITLE_CHARS = 200;
    static final int MAX_TASKS = 40;
    static final int MAX_MILESTONES = 8;

    private final PlanningDraftProperties properties;
    private final PlanningDraftGatewayClient gatewayClient;
    private final TaskPlanRepository taskPlanRepository;
    private final PlanningDraftFactsSource factsSource;
    private final TaskPlanDraftValidator validator;
    private final PlanningDraftParser parser = new PlanningDraftParser();
    private final ObjectMapper json;
    private final MeterRegistry meterRegistry;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore permits;

    public PlanningDraftShadowService(PlanningDraftProperties properties,
            PlanningDraftGatewayClient gatewayClient, TaskPlanRepository taskPlanRepository,
            PlanningDraftFactsSource factsSource, TaskPlanDraftValidator validator,
            ObjectMapper json, MeterRegistry meterRegistry) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.gatewayClient = Objects.requireNonNull(gatewayClient, "gatewayClient");
        this.taskPlanRepository = Objects.requireNonNull(taskPlanRepository, "taskPlanRepository");
        this.factsSource = Objects.requireNonNull(factsSource, "factsSource");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.json = Objects.requireNonNull(json, "json");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
        this.permits = new Semaphore(properties.maxConcurrency());
    }

    /**
     * 观察一份已通过代码校验、即将提交的 Java 草稿。
     *
     * <p>本方法是<b>尽力而为</b>的：不抛异常、不阻塞等待 worker、不改动任何入参；
     * 是否真正发起由 {@code planning.draft.mode}、项目可解析性、信号量共同决定。
     * {@code javaAssessment}/{@code draft} 只作为“正在提交的 Java 结果”上下文保留，
     * 本波不做 Java/Python 逐字段对比，也绝不修改它们。</p>
     */
    public void observe(TaskPlanRecord plan, TaskPlanDraft draft, ValidationAssessment javaAssessment) {
        try {
            // 默认 java：零外呼、零快照、零指标（连 skipped{disabled} 也不记）。
            if (properties.mode() != PlanningDraftMode.SHADOW) {
                return;
            }
            if (plan == null || plan.projectId() == null) {
                recordSkipped("empty");
                return;
            }
            ValidationContext context;
            try {
                context = taskPlanRepository.validationContext(plan);
            } catch (RuntimeException readFailure) {
                recordSkipped("empty");
                return;
            }
            BoundedRequest built = buildRequest(plan, context);
            if (built == null) {
                recordSkipped("empty");
                return;
            }
            if (built.truncated()) {
                recordTruncated();
            }
            if (!permits.tryAcquire()) {
                recordSkipped("saturated");
                return;
            }
            try {
                executor.execute(() -> {
                    try {
                        runShadow(plan, context, built.request());
                    } catch (RuntimeException ignored) {
                        // runShadow 已把可分类失败记成指标；这里只防信号量泄漏。
                    } finally {
                        permits.release();
                    }
                });
            } catch (RejectedExecutionException rejected) {
                permits.release();
                recordSkipped("concurrency");
            }
        } catch (RuntimeException ignored) {
            // 影子观察绝不能影响业务调用方。
            log.debug("planning draft shadow 观察失败（已忽略）: type={}",
                    ignored.getClass().getSimpleName());
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    // ------------------------------------------------------------------
    // 请求构造（§2 触发条件与请求映射）
    // ------------------------------------------------------------------

    /** 构造有界请求；返回 null 表示快照为空/不可构造（调用方记 skipped{empty}）。 */
    private BoundedRequest buildRequest(TaskPlanRecord plan, ValidationContext context) {
        return buildRequest(plan, context, properties.deadlineMs());
    }

    /**
     * 为 python skeleton 主链构造同一份有界请求；deadline 由调用方按运行预算传入。
     */
    BoundedRequest buildRequest(TaskPlanRecord plan, ValidationContext context, int deadlineMs) {
        String goal = goal(plan);
        if (goal.isEmpty()) {
            return null;
        }
        PlanningDraftFactsSource.PlanFacts facts;
        try {
            facts = factsSource.load(plan, context);
        } catch (RuntimeException readFailure) {
            return null;
        }
        PlanningDraftRequest request = request(plan, goal, facts, deadlineMs);
        boolean truncated = false;
        while (serializedLength(request) > properties.snapshotMaxChars()) {
            PlanningDraftRequest trimmed = trimOnce(request);
            if (trimmed == null) {
                break;
            }
            request = trimmed;
            truncated = true;
        }
        return new BoundedRequest(request, truncated);
    }

    private PlanningDraftRequest request(TaskPlanRecord plan, String goal,
            PlanningDraftFactsSource.PlanFacts facts, int deadlineMs) {
        int maxTasks = clamp(plan.maxTaskCount(), 1, MAX_TASKS);
        int maxMilestones = Math.min(MAX_MILESTONES, maxTasks);
        List<PlanningDraftRequest.Member> members = new ArrayList<>();
        for (PlanningDraftFactsSource.MemberFact member : facts.members()) {
            if (member == null || member.id() == null) {
                continue;
            }
            String name = bound(member.name(), MAX_MEMBER_NAME_CHARS).strip();
            if (name.isEmpty()) {
                name = member.id().toString();
            }
            members.add(new PlanningDraftRequest.Member(member.id().toString(), name));
            if (members.size() >= MAX_MEMBERS) {
                break;
            }
        }
        return new PlanningDraftRequest(
                1,
                goal,
                LOCALE,
                new PlanningDraftRequest.ProjectFacts(
                        bound(facts.projectName(), MAX_PROJECT_NAME_CHARS),
                        bound(facts.description(), MAX_DESCRIPTION_CHARS),
                        List.copyOf(members),
                        boundedTitles(facts.existingMilestoneTitles()),
                        boundedTitles(facts.existingTaskTitles())),
                TEMPLATE,
                new PlanningDraftRequest.Constraints(
                        maxTasks,
                        maxMilestones,
                        plan.planStartDate() == null ? null : plan.planStartDate().toString(),
                        plan.planDueDate() == null ? null : plan.planDueDate().toString()),
                deadlineMs);
    }

    private static List<String> boundedTitles(List<String> titles) {
        if (titles == null || titles.isEmpty()) {
            return List.of();
        }
        List<String> bounded = new ArrayList<>();
        for (String title : titles) {
            if (title == null || title.isBlank()) {
                continue;
            }
            bounded.add(bound(title, MAX_TITLE_CHARS));
            if (bounded.size() >= MAX_TITLES) {
                break;
            }
        }
        return List.copyOf(bounded);
    }

    /**
     * 快照超限时按“先既有标题、再成员、再描述、最后 goal”的顺序裁剪；
     * 每次裁剪后重新序列化计算长度。返回 null 表示已无可裁剪字段。
     */
    private PlanningDraftRequest trimOnce(PlanningDraftRequest request) {
        PlanningDraftRequest.ProjectFacts facts = request.projectFacts();
        List<String> milestoneTitles = new ArrayList<>(facts.existingMilestoneTitles());
        List<String> taskTitles = new ArrayList<>(facts.existingTaskTitles());
        List<PlanningDraftRequest.Member> members = new ArrayList<>(facts.members());
        String description = facts.description();
        String goal = request.goal();
        boolean changed = false;
        if (!milestoneTitles.isEmpty()) {
            milestoneTitles.removeLast();
            changed = true;
        } else if (!taskTitles.isEmpty()) {
            taskTitles.removeLast();
            changed = true;
        } else if (!members.isEmpty()) {
            members.removeLast();
            changed = true;
        } else if (!description.isEmpty()) {
            description = half(description);
            changed = true;
        } else if (!goal.isEmpty()) {
            goal = half(goal);
            changed = true;
        }
        if (!changed) {
            return null;
        }
        return new PlanningDraftRequest(request.schemaVersion(), goal, request.locale(),
                new PlanningDraftRequest.ProjectFacts(facts.projectName(), description,
                        List.copyOf(members), List.copyOf(milestoneTitles), List.copyOf(taskTitles)),
                request.template(), request.constraints(), request.deadlineMs());
    }

    private int serializedLength(PlanningDraftRequest request) {
        try {
            return json.writeValueAsString(request).length();
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("planning draft 请求序列化失败", impossible);
        }
    }

    private static String goal(TaskPlanRecord plan) {
        String goal = plan.goal();
        if (goal == null || goal.isBlank()) {
            goal = plan.title();
        }
        if (goal == null || goal.isBlank()) {
            return "";
        }
        return bound(goal.strip(), MAX_GOAL_CHARS);
    }

    // ------------------------------------------------------------------
    // 执行与指标（§2 响应处理）
    // ------------------------------------------------------------------

    private void runShadow(TaskPlanRecord plan, ValidationContext context,
            PlanningDraftRequest request) {
        long startNanos = System.nanoTime();
        String outcome = "failure";
        try {
            PlanningDraftResponse response;
            try {
                response = gatewayClient.draft(request);
            } catch (PlanningDraftGatewayException failure) {
                recordCall("failure");
                recordFailure(failure.reason());
                log.warn("planning draft shadow 调用失败丢弃: reason={} elapsedMs={}",
                        failure.reason().tag(), elapsedMillis(startNanos));
                return;
            }

            TaskPlanDraft pythonDraft;
            try {
                pythonDraft = parser.parse(response.plan(), fallbackSummary(plan));
            } catch (PlanningDraftParser.InvalidDraftException invalid) {
                recordCall("schema_invalid");
                recordSchemaPass(false);
                recordFailure(PlanningDraftGatewayErrorCode.SCHEMA);
                log.warn("planning draft shadow 草稿结构非法丢弃: elapsedMs={}",
                        elapsedMillis(startNanos));
                return;
            }

            ValidationAssessment assessment;
            try {
                // 契约硬要求：Python 草稿必须重跑 Java Validator（COMPLETE + aiGenerated）。
                assessment = validator.assess(context, pythonDraft, ValidationMode.COMPLETE, true);
            } catch (RuntimeException validatorFailure) {
                recordCall("failure");
                recordFailure(PlanningDraftGatewayErrorCode.UNEXPECTED);
                log.warn("planning draft shadow 重跑校验失败丢弃: type={} elapsedMs={}",
                        validatorFailure.getClass().getSimpleName(), elapsedMillis(startNanos));
                return;
            }

            boolean pass = assessment.ready() && !assessment.hasHardIssues();
            recordCall("success");
            recordSchemaPass(pass);
            recordIssues(assessment);
            outcome = "success";
        } finally {
            recordLatency(outcome, System.nanoTime() - startNanos);
        }
    }

    /** 解析器所需的回退摘要：只取有界 goal/title，不引入任何 Python 内容。 */
    private static String fallbackSummary(TaskPlanRecord plan) {
        String goal = plan.goal();
        if (goal == null || goal.isBlank()) {
            goal = plan.title();
        }
        return goal == null ? "" : bound(goal.strip(), 2000);
    }

    private void recordCall(String outcome) {
        counter(METRIC_CALLS, "outcome", outcome);
    }

    private void recordSchemaPass(boolean pass) {
        counter(METRIC_SCHEMA_PASS, "result", pass ? "pass" : "fail");
    }

    /**
     * 按每条草稿 issue 数记录一次（每次调用各记 HARD/SOFT 一条分布样本，
     * 包括 0）。Validator 的 BLOCKING_EDITABLE/WARNING 归入 SOFT。
     */
    private void recordIssues(ValidationAssessment assessment) {
        int hard = 0;
        int soft = 0;
        for (StructuredValidationIssue issue : assessment.issues()) {
            if (issue.severity() == ValidationIssueSeverity.HARD) {
                hard++;
            } else {
                soft++;
            }
        }
        summary(METRIC_ISSUES, "severity", "HARD", hard);
        summary(METRIC_ISSUES, "severity", "SOFT", soft);
    }

    private void recordFailure(PlanningDraftGatewayErrorCode reason) {
        counter(METRIC_FAILURE, "reason", reason.tag());
    }

    private void recordSkipped(String reason) {
        counter(METRIC_SKIPPED, "reason", reason);
    }

    private void recordTruncated() {
        counter(METRIC_TRUNCATED);
    }

    private void recordLatency(String outcome, long nanos) {
        try {
            meterRegistry.timer(METRIC_LATENCY, "outcome", outcome)
                    .record(nanos, TimeUnit.NANOSECONDS);
        } catch (RuntimeException ignored) {
            // 指标失败不影响业务。
        }
    }

    private void counter(String name, String... tags) {
        try {
            meterRegistry.counter(name, tags).increment();
        } catch (RuntimeException ignored) {
            // 指标失败不影响业务。
        }
    }

    private void summary(String name, String tagKey, String tagValue, int amount) {
        try {
            DistributionSummary summary = meterRegistry.summary(name, tagKey, tagValue);
            summary.record(amount);
        } catch (RuntimeException ignored) {
            // 指标失败不影响业务。
        }
    }

    // ------------------------------------------------------------------
    // 工具方法
    // ------------------------------------------------------------------

    static String bound(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxChars ? value : value.substring(0, maxChars);
    }

    private static String half(String value) {
        int nextLength = Math.max(0, value.length() / 2);
        return value.substring(0, nextLength);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /** 已构造好、可直接发送的有界请求（含是否发生截断）。 */
    record BoundedRequest(PlanningDraftRequest request, boolean truncated) {
    }
}
