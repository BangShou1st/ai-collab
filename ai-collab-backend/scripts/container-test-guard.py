"""CI 容器测试守卫：核对关键 Testcontainers 用例真实执行而非整类被跳过。

从 target/surefire-reports/TEST-*.xml 读取统计（CI 中由分片作业上传后聚合）。
"""

import glob
import os
import sys
import xml.etree.ElementTree as ET

# 覆盖上下文压缩、Flyway 迁移与运行恢复的关键真实 PostgreSQL 用例。
# RetrievalCoveragePostgresTest 是 D-RAG-01A 的检索覆盖基线：用真实 pgvector
# 观察向量候选与上下文保留；整类被跳过时同样判失败。
# RetrievalQualityHardGatePostgresTest 是 D-RAG-01C 第二轮的确定性质量硬门槛，
# AgentResearchQualityPostgresTest 是直接研究/委派同题对照（真实 PostgreSQL + 真实工具），
# 两者都必须真实执行，不得整类跳过（真实 Ollama 门槛为 Opt-in，不在此列）。
# SystemConfigTransactionPostgresIntegrationTest 验证真实 PostgreSQL + Redis 动态配置事务一致性：
# 包含提交与回滚可见性，不得整类跳过。
required = [
    "com.shitulelv.aicollab.agent.infrastructure.diagnostics.AgentObservabilityPostgresTest",
    "com.shitulelv.aicollab.agent.infrastructure.diagnostics.AgentRuntimeMetricsTest",
    "com.shitulelv.aicollab.agent.infrastructure.diagnostics.AgentActuatorSecurityTest",
    "com.shitulelv.aicollab.agent.infrastructure.diagnostics.AgentModelTransportMetricsTest",
    "com.shitulelv.aicollab.agent.application.runtime.AgentRemoteOutcomePostgresTest",
    "com.shitulelv.aicollab.agent.application.runtime.AgentRemoteOutcomeMigrationPostgresTest",
    "com.shitulelv.aicollab.agent.application.runtime.AgentToolSchedulerTest",
    "com.shitulelv.aicollab.agent.application.runtime.AgentPauseResumePostgresTest",
    "com.shitulelv.aicollab.agent.application.runtime.PersistedModelTurnRecoveryPostgresTest",
    "com.shitulelv.aicollab.acceptance.LegacyApprovalCitationUpgradeTest",
    "com.shitulelv.aicollab.agent.application.runtime.AgentRunContextCommitPostgresTest",
    "com.shitulelv.aicollab.agent.application.AgentRunRetryPostgresIntegrationTest",
    "com.shitulelv.aicollab.agent.infrastructure.AgentMigrationIntegrationTest",
    "com.shitulelv.aicollab.acceptance.NextStageMigrationPostgresTest",
    "com.shitulelv.aicollab.infrastructure.ai.user.V102LegacyProviderMigrationPostgresTest",
    "com.shitulelv.aicollab.retrieval.RetrievalCoveragePostgresTest",
    "com.shitulelv.aicollab.retrieval.RetrievalQualityHardGatePostgresTest",
    "com.shitulelv.aicollab.agent.application.runtime.AgentDelegationPostgresTest",
    "com.shitulelv.aicollab.agent.infrastructure.tool.AgentResearchQualityPostgresTest",
    "com.shitulelv.aicollab.agent.infrastructure.repository.AgentFinalCitationContractPostgresTest",
    "com.shitulelv.aicollab.notification.NotificationNavigationPostgresTest",
    "com.shitulelv.aicollab.system.application.service.SystemConfigTransactionPostgresIntegrationTest",
]

problems = []

# 1. 分片报告聚合完整性校验（若为分片环境，检查 3 个分片标记是否齐备且状态正常）
marker_pattern = "target/surefire-reports/shard-*-marker.txt"
found_markers = glob.glob(marker_pattern)
expected_shard_count = int(os.environ.get("CI_SHARD_COUNT", "3"))

# CI 聚合环境下应产生分片 marker；若已检测到 marker 或显式声明 CI_SHARD_COUNT 门禁，验证分片完整性
if found_markers or "CI_SHARD_COUNT" in os.environ:
    for shard in range(1, expected_shard_count + 1):
        marker_file = f"target/surefire-reports/shard-{shard}-marker.txt"
        if not os.path.exists(marker_file):
            problems.append(f"Shard {shard} completion marker missing: {marker_file} (shard artifact was not aggregated)")
        else:
            try:
                with open(marker_file, "r", encoding="utf-8") as f:
                    content = f.read()
                if "status=failure" in content or "status=cancelled" in content:
                    problems.append(f"Shard {shard} marker recorded failure: {content.strip()}")
            except Exception as e:
                problems.append(f"Shard {shard} marker unreadable: {e}")

# 2. 读取 Surefire 报告统计
stats = {}
xml_files = glob.glob("target/surefire-reports/TEST-*.xml")
if not xml_files:
    problems.append("No Surefire XML reports found under target/surefire-reports/")

for path in xml_files:
    try:
        root = ET.parse(path).getroot()
        cls_name = root.get("name")
        if not cls_name:
            continue
        stats[cls_name] = {
            key: int(root.get(key, 0))
            for key in ("tests", "failures", "errors", "skipped")
        }
    except Exception as e:
        problems.append(f"Failed to parse Surefire report {path}: {e}")

total = sum(item["tests"] for item in stats.values())
skipped = sum(item["skipped"] for item in stats.values())
failures = sum(item["failures"] for item in stats.values())
errors = sum(item["errors"] for item in stats.values())

print(
    f"Surefire: {total} tests, {skipped} skipped, "
    f"{failures} failures, {errors} errors, "
    f"{len(stats)} test classes reported"
)

# 3. 全局测试失败与错误校验：聚合逻辑绝不能忽略任何失败或错误
if failures > 0 or errors > 0:
    for cls_name, item in sorted(stats.items()):
        if item["failures"] > 0 or item["errors"] > 0:
            problems.append(
                f"{cls_name}: {item['failures']} failures, {item['errors']} errors"
            )

# 4. 必需集成测试真实执行校验
for name in required:
    item = stats.get(name)
    if item is None:
        problems.append(f"{name}: no Surefire report (class did not run)")
    elif item["tests"] == 0:
        problems.append(f"{name}: recorded 0 tests")
    elif item["skipped"] == item["tests"]:
        problems.append(
            f"{name}: all {item['tests']} tests skipped — "
            "container runtime unavailable?"
        )

# 5. 本地 Ollama 等明确 Opt-in 测试的跳过策略验证
# Local Ollama experiments must stay explicitly opt-in; a superclass
# EnabledIfEnvironmentVariable does not gate a concrete subclass.
for name in [
    "com.shitulelv.aicollab.retrieval.P6RagQualityBaselinePostgresTest",
    "com.shitulelv.aicollab.retrieval.P8AgentRagCrossLayerPostgresTest",
    "com.shitulelv.aicollab.retrieval.P8RagQualityFixExperimentPostgresTest",
]:
    item = stats.get(name)
    if item is None or item["tests"] == 0 or item["skipped"] != item["tests"]:
        problems.append(f"{name}: local-model opt-in guard did not skip the class")

if problems:
    print("Container test guard FAILED:", file=sys.stderr)
    for problem in problems:
        print(f"  - {problem}", file=sys.stderr)
    sys.exit(1)

print("Container test guard passed for all required classes.")
