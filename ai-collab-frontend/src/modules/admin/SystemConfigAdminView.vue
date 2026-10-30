<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { showApiError } from '../../api/api-result'
import PageHeader from '../../shared/PageHeader.vue'
import {
  systemConfigApi,
  type SystemConfig,
  type SystemConfigAudit,
  type SystemRuntimeStatus,
  type UpdateSystemConfigRequest,
} from './system-config-api'

const loading = ref(false)
const saving = ref(false)
const refreshingStatus = ref(false)
const activeTab = ref('features')

const config = ref<SystemConfig | null>(null)
const runtimeStatus = ref<SystemRuntimeStatus | null>(null)
const audits = ref<SystemConfigAudit[]>([])

const form = reactive<UpdateSystemConfigRequest>({
  agentEnabled: false,
  chatEnabled: false,
  workerConcurrency: 2,
  workerDelayMs: 1000,
  scheduleDelayMs: 30000,
  systemMaintenanceMode: false,
  systemMaintenanceMessage: '',
  publicRegistrationEnabled: true,
  defaultChatModel: '',
  defaultAgentModel: '',
  ragRetrievalThreshold: 0.55,
  ragMaxSources: 12,
  expectedVersion: 1,
})

async function loadData(): Promise<void> {
  loading.value = true
  try {
    const [configRes, statusRes, auditRes] = await Promise.all([
      systemConfigApi.getConfig(),
      systemConfigApi.getRuntimeStatus(),
      systemConfigApi.getAuditLogs(20),
    ])
    config.value = configRes.data
    runtimeStatus.value = statusRes.data
    audits.value = auditRes.data

    syncFormWithConfig(configRes.data)
  } catch (error) {
    showApiError(error, '配置中心数据加载')
  } finally {
    loading.value = false
  }
}

function syncFormWithConfig(c: SystemConfig): void {
  form.agentEnabled = c.agentEnabled
  form.chatEnabled = c.chatEnabled
  form.workerConcurrency = c.workerConcurrency
  form.workerDelayMs = c.workerDelayMs
  form.scheduleDelayMs = c.scheduleDelayMs
  form.systemMaintenanceMode = c.systemMaintenanceMode
  form.systemMaintenanceMessage = c.systemMaintenanceMessage || ''
  form.publicRegistrationEnabled = c.publicRegistrationEnabled
  form.defaultChatModel = c.defaultChatModel || ''
  form.defaultAgentModel = c.defaultAgentModel || ''
  form.ragRetrievalThreshold = c.ragRetrievalThreshold
  form.ragMaxSources = c.ragMaxSources
  form.expectedVersion = c.version
}

async function refreshRuntimeStatus(): Promise<void> {
  refreshingStatus.value = true
  try {
    const res = await systemConfigApi.getRuntimeStatus()
    runtimeStatus.value = res.data
    ElMessage.success('运行状态已刷新')
  } catch (error) {
    showApiError(error, '刷新运行时状态')
  } finally {
    refreshingStatus.value = false
  }
}

async function handleSave(): Promise<void> {
  if (config.value && config.value.agentEnabled && !form.agentEnabled) {
    const runningCount = runtimeStatus.value?.runningAgentRuns ?? 0
    if (runningCount > 0) {
      try {
        await ElMessageBox.confirm(
          `系统当前有 ${runningCount} 个正在运行的 Agent 任务！\n关闭服务后，将立即停止派发并拦截续跑。确定要关闭 Agent 协作服务吗？`,
          '关闭运行中服务警告',
          {
            confirmButtonText: '确定关闭',
            cancelButtonText: '取消',
            type: 'warning',
          },
        )
      } catch {
        return
      }
    }
  }

  saving.value = true
  try {
    const res = await systemConfigApi.updateConfig({ ...form })
    config.value = res.data
    syncFormWithConfig(res.data)
    ElMessage.success(`配置已保存并实时生效（新版本: v${res.data.version}）`)
    // Reload audits and runtime status
    const [statusRes, auditRes] = await Promise.all([
      systemConfigApi.getRuntimeStatus(),
      systemConfigApi.getAuditLogs(20),
    ])
    runtimeStatus.value = statusRes.data
    audits.value = auditRes.data
  } catch (error) {
    showApiError(error, '保存配置')
  } finally {
    saving.value = false
  }
}

async function handleRestore(audit: SystemConfigAudit): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确定将系统配置恢复至版本 v${audit.configVersion} 吗？\n该操作将创建新的配置版本并立即覆盖当前生效配置。`,
      '恢复历史配置确认',
      {
        confirmButtonText: '确定恢复',
        cancelButtonText: '取消',
        type: 'warning',
      },
    )
  } catch {
    return
  }

  loading.value = true
  try {
    const res = await systemConfigApi.restoreConfig(audit.id)
    config.value = res.data
    syncFormWithConfig(res.data)
    ElMessage.success(`已恢复至历史版本快照，当前生效版本为 v${res.data.version}`)
    await loadData()
  } catch (error) {
    showApiError(error, '恢复历史配置')
  } finally {
    loading.value = false
  }
}

onMounted(loadData)
</script>

<template>
  <main class="workspace-page system-config-page">
    <PageHeader eyebrow="系统设置" title="动态配置中心">
      <template #actions>
        <el-button :icon="Refresh" :loading="refreshingStatus" @click="refreshRuntimeStatus">
          刷新运行状态
        </el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">
          保存全部配置
        </el-button>
      </template>
    </PageHeader>

    <div v-loading="loading" class="config-container">
      <!-- 顶部运行概览卡片 -->
      <section v-if="runtimeStatus" class="runtime-overview-grid">
        <el-card shadow="never" class="overview-card">
          <div class="card-label">Agent 协作状态</div>
          <div class="card-value">
            <el-tag :type="runtimeStatus.agentEnabled ? 'success' : 'danger'" size="large">
              {{ runtimeStatus.agentEnabled ? '已开启' : '已关闭' }}
            </el-tag>
            <el-tag
              v-if="!runtimeStatus.agentRuntimeAllowed"
              type="warning"
              size="small"
              class="ml-2"
            >
              运维环境已熔断
            </el-tag>
          </div>
          <div class="card-sub">
            活跃 Worker 线程: {{ runtimeStatus.activeWorkerThreads }} / {{ runtimeStatus.configuredWorkerConcurrency }}
          </div>
        </el-card>

        <el-card shadow="never" class="overview-card">
          <div class="card-label">AI Chat 问答状态</div>
          <div class="card-value">
            <el-tag :type="runtimeStatus.chatEnabled ? 'success' : 'info'" size="large">
              {{ runtimeStatus.chatEnabled ? '已开启' : '已关闭' }}
            </el-tag>
          </div>
          <div class="card-sub">
            {{ runtimeStatus.chatEnabled ? '允许成员进行项目知识问答' : '新提问已拦截，历史记录可读' }}
          </div>
        </el-card>

        <el-card shadow="never" class="overview-card">
          <div class="card-label">任务队列监控</div>
          <div class="card-value number-highlight">
            <span class="count-item running">
              <strong>{{ runtimeStatus.runningAgentRuns }}</strong> 执行中
            </span>
            <span class="count-divider">/</span>
            <span class="count-item queued">
              <strong>{{ runtimeStatus.queuedAgentRuns }}</strong> 排队中
            </span>
          </div>
          <div class="card-sub">
            24h 异常失败任务: {{ runtimeStatus.recentFailedAgentRuns }}
          </div>
        </el-card>

        <el-card shadow="never" class="overview-card">
          <div class="card-label">当前配置版本</div>
          <div class="card-value">
            <el-tag type="primary" size="large">v{{ config?.version ?? 1 }}</el-tag>
            <el-tag v-if="runtimeStatus.maintenanceMode" type="danger" size="small" class="ml-2">
              维护模式中
            </el-tag>
          </div>
          <div class="card-sub">
            最后更新: {{ config?.updatedAt ? new Date(config.updatedAt).toLocaleString() : '未记录' }}
          </div>
        </el-card>
      </section>

      <!-- 异常告警提示条 -->
      <el-alert
        v-if="runtimeStatus?.lastExecutionError"
        type="warning"
        :closable="false"
        show-icon
        class="mt-4"
        :title="`最近一次 Agent 执行错误: ${runtimeStatus.lastExecutionError}`"
      />

      <!-- 配置标签页 -->
      <el-card shadow="never" class="mt-4 config-card">
        <el-tabs v-model="activeTab">
          <!-- Tab 1: 基础功能开关 -->
          <el-tab-pane label="基础功能开关" name="features">
            <el-form label-position="top" class="config-form">
              <el-row :gutter="24">
                <el-col :span="12">
                  <el-form-item label="Agent 协作服务">
                    <div class="switch-row">
                      <el-switch v-model="form.agentEnabled" active-text="开启" inactive-text="关闭" />
                      <span class="switch-tip">
                        控制项目协作 Agent 的全局可用性。关闭时拦截所有新任务与续跑，安全完成已有工作。
                      </span>
                    </div>
                  </el-form-item>
                </el-col>
                <el-col :span="12">
                  <el-form-item label="AI Chat 知识问答">
                    <div class="switch-row">
                      <el-switch v-model="form.chatEnabled" active-text="开启" inactive-text="关闭" />
                      <span class="switch-tip">
                        控制知识库流式问答与会话提问。关闭后已有历史记录仍可查阅，新提问将被友好拦截。
                      </span>
                    </div>
                  </el-form-item>
                </el-col>
              </el-row>

              <el-divider />

              <el-row :gutter="24">
                <el-col :span="12">
                  <el-form-item label="公开账号注册">
                    <div class="switch-row">
                      <el-switch
                        v-model="form.publicRegistrationEnabled"
                        active-text="开放注册"
                        inactive-text="仅管理员邀请"
                      />
                      <span class="switch-tip">
                        关闭后注册页面将停用，仅允许系统管理员或项目邀请码建号。
                      </span>
                    </div>
                  </el-form-item>
                </el-col>
                <el-col :span="12">
                  <el-form-item label="系统维护模式">
                    <div class="switch-row">
                      <el-switch
                        v-model="form.systemMaintenanceMode"
                        active-text="维护模式"
                        inactive-text="正常运行"
                      />
                      <span class="switch-tip">
                        启用后系统将向普通用户展示维护通知，提示暂停关键写操作。
                      </span>
                    </div>
                  </el-form-item>
                </el-col>
              </el-row>

              <el-form-item
                v-if="form.systemMaintenanceMode"
                label="维护公告信息"
                class="mt-2"
              >
                <el-input
                  v-model="form.systemMaintenanceMessage"
                  type="textarea"
                  :rows="2"
                  maxlength="500"
                  show-word-limit
                  placeholder="例如：系统计划于今晚 23:00 进行底层维护，预计耗时 30 分钟..."
                />
              </el-form-item>
            </el-form>
          </el-tab-pane>

          <!-- Tab 2: Agent 运行与 Worker 调度 -->
          <el-tab-pane label="Agent 与 Worker 调度" name="agent">
            <el-form label-position="top" class="config-form">
              <el-alert
                type="info"
                :closable="false"
                show-icon
                class="mb-4"
                title="动态并发调节说明：并发数修改后由线程池原子调整，无须重启后端。范围为 1~4，避免超过数据库连接池上限。"
              />

              <el-form-item label="Worker 最大执行并发数 (1 ~ 4)">
                <div class="slider-box">
                  <el-slider
                    v-model="form.workerConcurrency"
                    :min="1"
                    :max="4"
                    :step="1"
                    show-stops
                    show-input
                  />
                  <small class="text-muted">当前配置: {{ form.workerConcurrency }} 线程并发执行模型与工具调用</small>
                </div>
              </el-form-item>

              <el-row :gutter="24" class="mt-4">
                <el-col :span="12">
                  <el-form-item label="Worker 任务调度轮询间隔 (ms)">
                    <el-input-number
                      v-model="form.workerDelayMs"
                      :min="200"
                      :max="60000"
                      :step="500"
                      class="w-full"
                    />
                    <small class="text-muted">调度线程检查并派发任务的固定延迟，默认 1000ms</small>
                  </el-form-item>
                </el-col>
                <el-col :span="12">
                  <el-form-item label="Agent 定时任务检测间隔 (ms)">
                    <el-input-number
                      v-model="form.scheduleDelayMs"
                      :min="1000"
                      :max="300000"
                      :step="5000"
                      class="w-full"
                    />
                    <small class="text-muted">后台周期性检测定时 Agent 计划的频率，默认 30000ms</small>
                  </el-form-item>
                </el-col>
              </el-row>
            </el-form>
          </el-tab-pane>

          <!-- Tab 3: 全局 AI 与 RAG 策略 -->
          <el-tab-pane label="全局 AI 与 RAG 策略" name="ai-rag">
            <el-form label-position="top" class="config-form">
              <el-row :gutter="24">
                <el-col :span="12">
                  <el-form-item label="系统默认 Chat 模型标识">
                    <el-input
                      v-model="form.defaultChatModel"
                      placeholder="例如 gpt-4o-mini 或 deepseek-chat（用户未设置时的兜底）"
                    />
                    <small class="text-muted">当用户未配置个人专属 Chat 偏好时作为系统级策略参考</small>
                  </el-form-item>
                </el-col>
                <el-col :span="12">
                  <el-form-item label="系统默认 Agent 模型标识">
                    <el-input
                      v-model="form.defaultAgentModel"
                      placeholder="例如 gpt-4o 或 claude-3-5-sonnet"
                    />
                    <small class="text-muted">当用户未分配 Agent 专用模型时作为系统级策略参考</small>
                  </el-form-item>
                </el-col>
              </el-row>

              <el-divider />

              <el-row :gutter="24">
                <el-col :span="12">
                  <el-form-item label="RAG 知识检索相关度阈值 (0.0 ~ 1.0)">
                    <el-slider
                      v-model="form.ragRetrievalThreshold"
                      :min="0.0"
                      :max="1.0"
                      :step="0.05"
                      show-input
                    />
                    <small class="text-muted">召回文档相似度门槛，低于此值的切片不纳入模型 Prompt 上下文（默认 0.55）</small>
                  </el-form-item>
                </el-col>
                <el-col :span="12">
                  <el-form-item label="RAG 最大片段召回数量 (1 ~ 50)">
                    <el-input-number
                      v-model="form.ragMaxSources"
                      :min="1"
                      :max="50"
                      class="w-full"
                    />
                    <small class="text-muted">单次问答与任务规划组装的最大上下文资料切片数（默认 12）</small>
                  </el-form-item>
                </el-col>
              </el-row>

              <el-card shadow="never" class="mt-4 bg-muted">
                <div class="flex-between">
                  <div>
                    <strong>系统 Embedding 基础向量模型</strong>
                    <p class="text-muted mb-0">文档知识库全局向量索引参数与连接配置，由专属 AI Infrastructure 模块管理。</p>
                  </div>
                  <router-link to="/admin/embedding">
                    <el-button type="primary" plain>前往 AI Infrastructure</el-button>
                  </router-link>
                </div>
              </el-card>
            </el-form>
          </el-tab-pane>

          <!-- Tab 4: 配置变更审计与历史版本恢复 -->
          <el-tab-pane label="配置审计与历史恢复" name="audit">
            <el-table :data="audits" empty-text="暂无变更审计记录" class="w-full">
              <el-table-column prop="configVersion" label="版本" width="80">
                <template #default="{ row }">
                  <el-tag size="small" type="primary">v{{ row.configVersion }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="action" label="操作类型" width="110">
                <template #default="{ row }">
                  <el-tag
                    size="small"
                    :type="row.action === 'INITIALIZE' ? 'info' : row.action === 'RESTORE' ? 'warning' : 'success'"
                  >
                    {{ row.action === 'INITIALIZE' ? '初始化' : row.action === 'RESTORE' ? '恢复版本' : '修改配置' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="changeSummary" label="变更摘要" min-width="200" />
              <el-table-column prop="changedByName" label="操作人" width="120">
                <template #default="{ row }">{{ row.changedByName || '系统管理员' }}</template>
              </el-table-column>
              <el-table-column prop="createdAt" label="变更时间" width="180">
                <template #default="{ row }">{{ new Date(row.createdAt).toLocaleString() }}</template>
              </el-table-column>
              <el-table-column prop="appliedStatus" label="状态" width="100">
                <template #default="{ row }">
                  <el-tag :type="row.appliedStatus === 'APPLIED' ? 'success' : 'danger'" size="small">
                    {{ row.appliedStatus === 'APPLIED' ? '已生效' : '失败' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="120" fixed="right">
                <template #default="{ row }">
                  <el-button
                    v-if="row.configVersion !== config?.version"
                    text
                    type="primary"
                    size="small"
                    @click="handleRestore(row)"
                  >
                    恢复此版本
                  </el-button>
                  <span v-else class="text-muted small">当前版本</span>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>
        </el-tabs>
      </el-card>
    </div>
  </main>
</template>

<style scoped>
.system-config-page {
  padding-bottom: 40px;
}
.runtime-overview-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 16px;
  margin-top: 16px;
}
.overview-card {
  border-radius: 8px;
}
.card-label {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  margin-bottom: 8px;
}
.card-value {
  display: flex;
  align-items: center;
  font-size: 20px;
  font-weight: 600;
  margin-bottom: 6px;
}
.card-sub {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.number-highlight {
  display: flex;
  align-items: baseline;
  gap: 8px;
  font-size: 14px;
}
.number-highlight strong {
  font-size: 22px;
}
.count-item.running strong {
  color: var(--el-color-primary);
}
.count-item.queued strong {
  color: var(--el-color-warning);
}
.count-divider {
  color: var(--el-border-color);
}
.config-card {
  border-radius: 8px;
}
.config-form {
  padding: 8px 0;
}
.switch-row {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.switch-tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.4;
}
.slider-box {
  max-width: 480px;
}
.text-muted {
  color: var(--el-text-color-secondary);
  display: block;
  margin-top: 4px;
}
.bg-muted {
  background-color: var(--el-fill-color-light);
  border: 1px solid var(--el-border-color-lighter);
}
.flex-between {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.w-full {
  width: 100%;
}
.ml-2 {
  margin-left: 8px;
}
.mt-2 {
  margin-top: 8px;
}
.mt-4 {
  margin-top: 16px;
}
.mb-4 {
  margin-bottom: 16px;
}
</style>
