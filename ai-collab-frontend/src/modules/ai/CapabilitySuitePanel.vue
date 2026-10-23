<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { userAiApi, type UserAiProvider } from './user-ai-api'
import {
  PROBE_MODES,
  PROBE_STATUS_LABELS,
  probeResultsFromViews,
  type ProbeMode,
  type ProbeResultState,
} from './ai-purpose-status'
import { SUITE_STEPS, useCapabilitySuite } from './use-capability-suite'

/**
 * 统一“测试连接与能力”面板：自定义 Provider 与 Zen/Kilo 预设共用。
 * 一次确认后串行执行静态检查 → 模型发现/准入 → 真实生成 → 流式（→ 规划 / 原生工具，按需勾选）。
 * 全部真实出站请求只由本面板的按钮触发。
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

const runState = computed(() => suite.runs[props.provider.id] ?? null)
const running = computed(() => runState.value?.running ?? false)

/** 可选步骤默认按能力声明预勾：声明了 NATIVE_TOOLS 的配置默认参与工具测试。 */
watch(
  () => props.provider.id,
  () => {
    options.planning = false
    options.nativeTools = props.provider.capabilities.includes('NATIVE_TOOLS')
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

async function runSuite(): Promise<void> {
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

function stepStatusType(status: string): 'success' | 'danger' | 'warning' | 'info' {
  if (status === 'PASS') return 'success'
  if (status === 'FAIL') return 'danger'
  if (status === 'INCONCLUSIVE' || status === 'STALE') return 'warning'
  return 'info'
}

function statusText(status: string): string {
  if (status === 'RUNNING') return '测试中…'
  if (status === 'PENDING') return '等待中'
  if (status === 'SKIPPED') return '已跳过'
  return PROBE_STATUS_LABELS[status as keyof typeof PROBE_STATUS_LABELS] ?? status
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
      <el-button size="small" type="primary" plain :loading="running" @click="runSuite">测试连接与能力</el-button>
      <el-checkbox v-model="options.planning" size="small" :disabled="running">含规划测试</el-checkbox>
      <el-checkbox v-model="options.nativeTools" size="small" :disabled="running">含原生工具测试</el-checkbox>
      <span class="suite-hint">将发起真实模型请求（连接 1 次、流式 1 次；勾选项另计）</span>
    </div>

    <ul v-if="runState" class="suite-steps" :aria-busy="running">
      <li v-for="(state, key) in runState.steps" :key="key" class="suite-step" :data-status="state.status">
        <span class="suite-step__label">{{ stepLabel(String(key)) }}</span>
        <el-tag :type="stepStatusType(state.status)" size="small" effect="light">
          {{ statusText(state.status) }}
        </el-tag>
        <span v-if="state.status === 'PASS' && 'latencyMs' in state && state.latencyMs" class="suite-step__detail">
          {{ state.latencyMs }}ms
        </span>
        <span v-if="'detail' in state && state.detail" class="suite-step__detail">{{ state.detail }}</span>
      </li>
    </ul>

    <div v-if="!persistedLoading" class="suite-persisted">
      <span class="suite-persisted__title">已保存的验证结论：</span>
      <span v-for="mode in PROBE_MODES" :key="mode.value" class="suite-persisted__item">
        {{ mode.label }}：
        <template v-if="persisted[mode.value]">
          <el-tag :type="stepStatusType(persisted[mode.value]!.status)" size="small">
            {{ statusText(persisted[mode.value]!.status) }}
          </el-tag>
          <span v-if="testedAtText(persisted[mode.value]!)" class="suite-persisted__at">
            {{ testedAtText(persisted[mode.value]!) }}
          </span>
        </template>
        <template v-else><el-tag type="info" size="small">未测试</el-tag></template>
      </span>
    </div>
  </div>
</template>

<style scoped>
.suite-panel { display: flex; flex-direction: column; gap: 8px; }
.suite-actions { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.suite-hint { color: var(--color-text-secondary); font-size: 12px; }
.suite-steps { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 4px; }
.suite-step { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; font-size: 13px; }
.suite-step__label { min-width: 150px; color: var(--color-text-primary); }
.suite-step__detail { color: var(--color-text-secondary); font-size: 12px; }
.suite-persisted { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; font-size: 12px; color: var(--color-text-secondary); border-top: 1px dashed var(--color-border); padding-top: 8px; }
.suite-persisted__item { display: inline-flex; align-items: center; gap: 4px; }
.suite-persisted__at { color: var(--color-text-secondary); }
</style>
