<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import { userAiApi, type UserAiProvider } from './user-ai-api'
import {
  CAPABILITY_EVIDENCE_LABELS,
  CAPABILITY_EVIDENCE_ROWS,
  capabilityEvidence,
  PROBE_STATUS_LABELS,
  probeResultsFromViews,
  type CapabilityEvidenceStatus,
  type ProbeMode,
  type ProbeResultState,
} from './ai-purpose-status'
import { SUITE_STEPS, suiteRequestBudget, useCapabilitySuite } from './use-capability-suite'

/**
 * 统一“检测并配置”面板：自定义 Provider 与 Zen/Kilo 预设共用。
 * 一次确认后由后端串行执行：静态检查 → 模型发现/准入 → 真实生成 → 流式
 * →（可选）规划 →（可选）原生工具，PASS 能力自动 CAS 写回。
 * 全部真实出站请求只由本面板的按钮触发；同一 Provider 同时只允许一个检测任务（后端互斥）。
 */
const props = defineProps<{
  provider: UserAiProvider
  /** 预设准入模型列表（预设卡片传入；自定义 Provider 省略）。 */
  admittedModels?: string[]
}>()
const emit = defineEmits<{ completed: [] }>()

const suite = useCapabilitySuite()
const options = reactive({ planning: false, nativeTools: false })
const persisted = ref<Record<ProbeMode, ProbeResultState | null>>(probeResultsFromViews([]))
const persistedLoading = ref(false)
const confirming = ref(false)

const runState = computed(() => suite.runs[props.provider.id] ?? null)
const running = computed(() => runState.value?.running ?? false)

const isPreset = computed(() => Boolean(props.provider.presetCode))
const isCustom = computed(() => !isPreset.value)

/** 可选步骤默认按能力声明预勾；配置版本变化（编辑模型/Key 等）后重新拉取落库结论，旧结果按 STALE 展示。 */
watch(
  () => [props.provider.id, props.provider.updatedAt],
  () => {
    options.planning = false
    options.nativeTools = props.provider.capabilities.includes('NATIVE_TOOLS')
    suite.reset(props.provider.id)
    void loadPersisted()
  },
  { immediate: true },
)

async function loadPersisted(): Promise<void> {
  persistedLoading.value = true
  try {
    persisted.value = probeResultsFromViews((await userAiApi.probeResults(props.provider.id)).data ?? [])
  } catch {
    // 读取失败保持空态：面板主要展示本次套件结果
  } finally {
    persistedLoading.value = false
  }
}

/** 按用途推荐检测组合：只预选深度能力，不自动发起请求。 */
function applyPreset(preset: 'KNOWLEDGE_CHAT' | 'PLANNING' | 'AGENT' | 'ALL'): void {
  if (preset === 'KNOWLEDGE_CHAT') {
    options.planning = false
    options.nativeTools = false
  } else if (preset === 'PLANNING') {
    options.planning = true
    options.nativeTools = false
  } else if (preset === 'AGENT') {
    options.planning = false
    options.nativeTools = true
  } else {
    options.planning = true
    options.nativeTools = true
  }
}

const budgetText = computed(() => {
  const parts = ['连接 1 次', '流式 1 次']
  if (options.planning) parts.push('规划最多 4 次')
  if (options.nativeTools) parts.push('原生工具 1 次')
  return `真实模型请求共 ${suiteRequestBudget(options)} 次（${parts.join(' + ')}）；模型发现为只读请求另计`
})

/** 确认一次即授权本次列出的全部请求；确认对话框列明消耗。 */
async function runSuite(): Promise<void> {
  if (running.value || confirming.value) return
  confirming.value = true
  try {
    await ElMessageBox.confirm(
      `将对「${props.provider.name}」执行检测并配置：${budgetText.value}。`
        + (isCustom.value ? '探针通过的能力将自动写入该模型配置。' : ''),
      '检测并配置',
      { confirmButtonText: '开始检测', cancelButtonText: '取消', type: 'info' },
    )
  } catch {
    confirming.value = false
    return
  }
  confirming.value = false
  await suite.run(props.provider.id, { planning: options.planning, nativeTools: options.nativeTools }, {
    provider: props.provider,
    admittedModels: props.admittedModels,
    staticConfig: {
      providerType: props.provider.providerType,
      baseUrl: props.provider.baseUrl,
      apiPath: props.provider.apiPath,
      modelName: props.provider.modelName,
      capabilities: props.provider.capabilities,
    },
  })
  await loadPersisted()
  emit('completed')
}

const STEP_LABELS: Record<string, string> = Object.fromEntries(SUITE_STEPS.map((step) => [step.key, step.label]))

function stepLabel(key: string): string {
  return STEP_LABELS[key] ?? key
}

function statusType(status: string): 'success' | 'danger' | 'warning' | 'info' {
  if (status === 'PASS' || status === 'VERIFIED') return 'success'
  if (status === 'FAIL' || status === 'NOT_SUPPORTED') return 'danger'
  if (status === 'INCONCLUSIVE' || status === 'STALE' || status === 'DECLARED_UNVERIFIED') return 'warning'
  return 'info'
}

function statusText(status: string): string {
  if (status === 'RUNNING') return '测试中…'
  if (status === 'PENDING') return '等待中'
  if (status === 'SKIPPED') return '已跳过'
  return PROBE_STATUS_LABELS[status as keyof typeof PROBE_STATUS_LABELS]
    ?? CAPABILITY_EVIDENCE_LABELS[status as CapabilityEvidenceStatus]
    ?? status
}

function evidenceStatus(key: (typeof CAPABILITY_EVIDENCE_ROWS)[number]['key']): CapabilityEvidenceStatus {
  const row = CAPABILITY_EVIDENCE_ROWS.find((item) => item.key === key)!
  return capabilityEvidence(row, props.provider, persisted.value)
}

function testedAtText(state: ProbeResultState): string {
  if (!state.testedAt) return ''
  try {
    return new Date(state.testedAt).toLocaleString()
  } catch {
    return ''
  }
}
</script>

<template>
  <div class="suite-panel">
    <div class="suite-actions">
      <el-button size="small" type="primary" :loading="running || confirming" data-test="detect-and-configure" @click="runSuite">
        检测并配置
      </el-button>
      <span class="suite-presets">
        <el-button size="small" text :disabled="running" @click="applyPreset('KNOWLEDGE_CHAT')">知识问答</el-button>
        <el-button size="small" text :disabled="running" @click="applyPreset('PLANNING')">AI 规划</el-button>
        <el-button size="small" text :disabled="running" @click="applyPreset('AGENT')">Agent</el-button>
        <el-button size="small" text :disabled="running" @click="applyPreset('ALL')">全面检测</el-button>
      </span>
      <el-checkbox v-model="options.planning" size="small" :disabled="running">含规划测试</el-checkbox>
      <el-checkbox v-model="options.nativeTools" size="small" :disabled="running">含原生工具测试</el-checkbox>
      <span class="suite-hint">{{ budgetText }}</span>
    </div>

    <ul v-if="runState" class="suite-steps" :aria-busy="running">
      <li v-for="(state, key) in runState.steps" :key="key" class="suite-step" :data-status="state.status">
        <span class="suite-step__label">{{ stepLabel(String(key)) }}</span>
        <el-tag :type="statusType(state.status)" size="small" effect="light">
          {{ statusText(state.status) }}
        </el-tag>
        <span v-if="state.status === 'PASS' && 'latencyMs' in state && state.latencyMs" class="suite-step__detail">
          {{ state.latencyMs }}ms
        </span>
        <span v-if="'detail' in state && state.detail" class="suite-step__detail">{{ state.detail }}</span>
      </li>
    </ul>

    <el-alert
      v-if="runState?.writeBack && !runState.writeBack.applied && runState.writeBack.reason"
      type="warning"
      :title="runState.writeBack.reason"
      :closable="false"
      show-icon
    />
    <el-alert
      v-if="runState?.writeBack?.applied && runState.writeBack.reason"
      type="info"
      :title="runState.writeBack.reason"
      :closable="false"
      show-icon
    />

    <div v-if="!persistedLoading" class="suite-evidence">
      <span class="suite-evidence__title">能力验证状态：</span>
      <table class="evidence-table">
        <thead>
          <tr><th>能力</th><th>状态</th><th>说明</th></tr>
        </thead>
        <tbody>
          <tr v-for="row in CAPABILITY_EVIDENCE_ROWS" :key="row.key">
            <td>{{ row.label }}</td>
            <td><el-tag :type="statusType(evidenceStatus(row.key))" size="small">{{ statusText(evidenceStatus(row.key)) }}</el-tag></td>
            <td class="evidence-table__desc">{{ row.description }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<style scoped>
.suite-panel { display: flex; flex-direction: column; gap: 8px; }
.suite-actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.suite-presets { display: inline-flex; gap: 0; }
.suite-hint { color: var(--color-text-secondary); font-size: 12px; }
.suite-steps { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 4px; }
.suite-step { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; font-size: 13px; }
.suite-step__label { min-width: 150px; color: var(--color-text-primary); }
.suite-step__detail { color: var(--color-text-secondary); font-size: 12px; }
.suite-evidence { display: flex; flex-direction: column; gap: 4px; border-top: 1px dashed var(--color-border); padding-top: 8px; }
.suite-evidence__title { font-size: 12px; color: var(--color-text-secondary); }
.evidence-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.evidence-table th { text-align: left; font-weight: normal; color: var(--color-text-secondary); font-size: 12px; padding: 2px 8px 2px 0; }
.evidence-table td { padding: 3px 8px 3px 0; }
.evidence-table__desc { color: var(--color-text-secondary); font-size: 12px; }
</style>
