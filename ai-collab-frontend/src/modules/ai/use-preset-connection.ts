import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { showApiError } from '../../api/api-result'
import { aiPresetApi, type PresetStatus } from './ai-preset-api'

export interface PresetConnectionOptions {
  code: 'OPENCODE_ZEN_FREE' | 'KILO_GATEWAY_FREE'
  displayName: string
  /** Zen 特有：可选 API Key（免费模型可匿名；留空保留已保存 Key）。 */
  supportsApiKey: boolean
  /** 断开确认弹窗文案。 */
  disconnectWarning: string
  onSaved: () => Promise<void>
}

/**
 * Zen / Kilo 预设连接的统一状态机：两个预设的保存/发现/断开流程完全同构，
 * 仅 code、文案与是否支持 API Key 不同——差异留在这里的配置，逻辑只写一份。
 *
 * 真实模型请求不走本 composable（统一由 CapabilitySuitePanel 的能力套件发起）；
 * 这里只负责模型列表发现（只读 GET）与连接保存/断开。
 */
export function usePresetConnection(options: PresetConnectionOptions) {
  const models = ref<string[]>([])
  const modelsLoading = ref(false)
  const model = ref('')
  const apiKey = ref('')
  const setDefault = ref(false)
  const saving = ref(false)
  const disconnecting = ref(false)

  async function loadModels(refresh: boolean): Promise<void> {
    modelsLoading.value = true
    try {
      models.value = (await aiPresetApi.models(options.code, refresh)).data ?? []
      if (!model.value && models.value[0]) model.value = models.value[0]
    } catch (error) {
      showApiError(error, refresh ? '模型列表刷新' : '免费模型加载，可点击重试')
    } finally {
      modelsLoading.value = false
    }
  }

  async function save(): Promise<void> {
    if (!model.value || saving.value) return
    saving.value = true
    try {
      await aiPresetApi.save(options.code, {
        ...(options.supportsApiKey && apiKey.value ? { apiKey: apiKey.value } : {}),
        modelName: model.value,
        enabled: true,
        setDefault: setDefault.value,
      })
      apiKey.value = ''
      await options.onSaved()
      ElMessage.success(`${options.displayName} 已保存并启用`)
    } catch (error) {
      showApiError(error, `${options.displayName} 保存`)
    } finally {
      saving.value = false
    }
  }

  async function disconnect(): Promise<void> {
    if (disconnecting.value) return
    try {
      await ElMessageBox.confirm(options.disconnectWarning, `断开 ${options.displayName}`, {
        confirmButtonText: '断开连接',
        cancelButtonText: '取消',
        type: 'warning',
      })
    } catch {
      return
    }
    disconnecting.value = true
    try {
      await aiPresetApi.disconnect(options.code)
      apiKey.value = ''
      await options.onSaved()
      ElMessage.success(`已断开 ${options.displayName} 连接`)
    } catch (error) {
      showApiError(error, '断开连接')
    } finally {
      disconnecting.value = false
    }
  }

  /** 从 PresetStatus 回显当前连接状态。 */
  function syncFromStatus(status: PresetStatus | null, fallbackModel: string): void {
    if (status?.modelName) model.value = status.modelName
    else if (!model.value) model.value = fallbackModel
    setDefault.value = Boolean(status?.isDefault)
  }

  return { models, modelsLoading, model, apiKey, setDefault, saving, disconnecting, loadModels, save, disconnect, syncFromStatus }
}
