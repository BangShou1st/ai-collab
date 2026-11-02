"""CI 容器测试守卫：核对关键 Testcontainers 用例真实执行而非整类被跳过。

从 target/surefire-reports/TEST-*.xml 读取统计（CI 中由分片作业上传后聚合）。
"""

import glob
import sys
import xml.etree.ElementTree as ET

# 覆盖上下文压缩、Flyway 迁移与运行恢复的关键真实 PostgreSQL 用例。
# RetrievalCoveragePostgresTest 是 D-RAG-01A 的检索覆盖基线：用真实 pgvector
# 观察向量候选与上下文保留；整类被跳过时同样判失败。
# RetrievalQualityHardGatePostgresTest 是 D-RAG-01C 第二轮的确定性质量硬门槛，
# AgentResearchQualityPostgresTest 是直接研究/委派同题对照（真实 PostgreSQL + 真实工具），
# 两者都必须真实执行，不得整类跳过（真实 Ollama 门槛为 Opt-in，不在此列）。
# KiloGatewayPresetIsolationIntegrationTest 验证 V65 迁移与双预设隔离：同样不得整类跳过。
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
    "com.shitulelv.aicollab.retrieval.RetrievalCoveragePostgresTest",
    "com.shitulelv.aicollab.retrieval.RetrievalQualityHardGatePostgresTest",
    "com.shitulelv.aicollab.agent.application.runtime.AgentDelegationPostgresTest",
    "com.shitulelv.aicollab.agent.infrastructure.tool.AgentResearchQualityPostgresTest",
    "com.shitulelv.aicollab.agent.infrastructure.repository.AgentFinalCitationContractPostgresTest",
    "com.shitulelv.aicollab.infrastructure.ai.user.KiloGatewayPresetIsolationIntegrationTest",
    "com.shitulelv.aicollab.notification.NotificationNavigationPostgresTest",
]

stats = {}
for path in glob.glob("target/surefire-reports/TEST-*.xml"):
    root = ET.parse(path).getroot()
    stats[root.get("name")] = {
        key: int(root.get(key, 0))
        for key in ("tests", "failures", "errors", "skipped")
    }

total = sum(item["tests"] for item in stats.values())
skipped = sum(item["skipped"] for item in stats.values())
print(
    f"Surefire: {total} tests, {skipped} skipped, "
    f"{len(stats)} test classes reported"
)

problems = []
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
