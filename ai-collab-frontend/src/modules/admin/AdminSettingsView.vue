<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Check, Refresh } from '@element-plus/icons-vue'
import { showApiError } from '../../api/api-result'
import {
  systemConfigApi,
  type SystemConfig,
  type SystemConfigAudit,
  type UpdateSystemConfigRequest,
} from './system-config-api'

const loading = ref(false)
const saving = ref(false)
const config = ref<SystemConfig | null>(null)
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

async function load(): Promise<void> {
  loading.value = true
  try {
    const [cfgRes, auditRes] = await Promise.all([
      systemConfigApi.getConfig(),
      systemConfigApi.getAuditLogs(10),
    ])
    config.value = cfgRes.data
    audits.value = auditRes.data

    const c = cfgRes.data
    Object.assign(form, {
      agentEnabled: c.agentEnabled,
      chatEnabled: c.chatEnabled,
      workerConcurrency: c.workerConcurrency,
      workerDelayMs: c.workerDelayMs,
      scheduleDelayMs: c.scheduleDelayMs,
      systemMaintenanceMode: c.systemMaintenanceMode,
      systemMaintenanceMessage: c.systemMaintenanceMessage || '',
      publicRegistrationEnabled: c.publicRegistrationEnabled,
      defaultChatModel: c.defaultChatModel || '',
      defaultAgentModel: c.defaultAgentModel || '',
      ragRetrievalThreshold: c.ragRetrievalThreshold,
      ragMaxSources: c.ragMaxSources,
      expectedVersion: c.version,
    })
  } catch (err) {
    showApiError(err, '系统设置加载')
  } finally {
    loading.value = false
  }
}

async function save(): Promise<void> {
  saving.value = true
  try {
    const res = await systemConfigApi.updateConfig({ ...form })
    config.value = res.data
    form.expectedVersion = res.data.version
    ElMessage.success('系统设置已更新并即时生效')
    await load()
  } catch (err) {
    showApiError(err, '保存系统设置')
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <div v-loading="loading" class="settings-container">
    <header class="page-header">
      <div>
        <h1 class="page-title">系统设置</h1>
        <p class="page-desc">平台全局开关、注册准入策略、Worker 调度与全局 RAG 检索参数</p>
      </div>
      <div class="header-actions">
        <el-button :icon="Refresh" @click="load">重载配置</el-button>
        <el-button type="primary" :icon="Check" :loading="saving" @click="save">保存生效</el-button>
      </div>
    </header>

    <div class="settings-stack">
      <!-- 基础功能与运营开关 -->
      <el-card class="box-card" shadow="never">
        <template #header><strong>基础运营与功能开关</strong></template>
        <el-form label-position="top">
          <div class="switch-grid">
            <div class="switch-card">
              <div class="switch-meta">
                <strong>公开注册</strong>
                <small>关闭后全站进入邀请制，仅凭管理员或成员项目邀请链接注册</small>
              </div>
              <el-switch v-model="form.publicRegistrationEnabled" />
            </div>

            <div class="switch-card">
              <div class="switch-meta">
                <strong>协作 Agent 引擎</strong>
                <small>全局控制项目中的 AI Agent 运行与任务自动拆解</small>
              </div>
              <el-switch v-model="form.agentEnabled" />
            </div>

            <div class="switch-card">
              <div class="switch-meta">
                <strong>AI Chat 对话功能</strong>
                <small>全局控制知识库与上下文对话通道</small>
              </div>
              <el-switch v-model="form.chatEnabled" />
            </div>

            <div class="switch-card maintenance">
              <div class="switch-meta">
                <strong>系统维护模式</strong>
                <small>启用后普通用户将被拦截并展示维护公告，管理员依然可正常操作</small>
              </div>
              <el-switch v-model="form.systemMaintenanceMode" active-color="#ef4444" />
            </div>
          </div>

          <el-form-item v-if="form.systemMaintenanceMode" label="维护公告说明" class="mt-4">
            <el-input
              v-model="form.systemMaintenanceMessage"
              type="textarea"
              :rows="2"
              placeholder="系统正在进行关键升级维护，预计 30 分钟后恢复..."
            />
          </el-form-item>
        </el-form>
      </el-card>

      <!-- 高级并发与检索参数（折叠面板按需展开） -->
      <el-collapse class="advanced-collapse">
        <el-collapse-item title="高级参数：Worker 调度与全局 RAG 检索" name="advanced">
          <div class="advanced-grid">
            <div class="advanced-section">
              <h4>Worker 调度与并发</h4>
              <el-form label-position="top">
                <el-form-item label="并发处理 Worker 数量">
                  <el-input-number v-model="form.workerConcurrency" :min="1" :max="16" style="width: 100%" />
                </el-form-item>
                <el-form-item label="工作轮询间隔 (毫秒)">
                  <el-input-number v-model="form.workerDelayMs" :min="100" :max="60000" :step="500" style="width: 100%" />
                </el-form-item>
              </el-form>
            </div>

            <div class="advanced-section">
              <h4>全局 RAG 语义检索参数</h4>
              <el-form label-position="top">
                <el-form-item label="语义相似度阈值 (0 - 1)">
                  <el-input-number v-model="form.ragRetrievalThreshold" :min="0.1" :max="0.99" :step="0.05" :precision="2" style="width: 100%" />
                </el-form-item>
                <el-form-item label="最大引用文档源数量">
                  <el-input-number v-model="form.ragMaxSources" :min="1" :max="50" style="width: 100%" />
                </el-form-item>
              </el-form>
            </div>
          </div>
        </el-collapse-item>

        <el-collapse-item title="配置变更历史审计（最近 10 次）" name="audits">
          <el-table :data="audits" size="small" empty-text="暂无历史审计记录">
            <el-table-column prop="version" label="版本" width="70" />
            <el-table-column prop="operatorUsername" label="操作者" width="130" />
            <el-table-column prop="createdAt" label="变更时间" min-width="160" />
            <el-table-column prop="changeSummary" label="变更摘要" min-width="200" />
          </el-table>
        </el-collapse-item>
      </el-collapse>
    </div>
  </div>
</template>

<style scoped>
.settings-container {
  max-width: 1000px;
  margin: 0 auto;
}
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 20px;
  gap: 16px;
  flex-wrap: wrap;
}
.page-title {
  font-size: 22px;
  font-weight: 700;
  color: #111827;
  margin: 0 0 4px;
}
.page-desc {
  font-size: 13px;
  color: #6b7280;
  margin: 0;
}
.header-actions {
  display: flex;
  gap: 10px;
}

.settings-stack {
  display: flex;
  flex-direction: column;
  gap: 20px;
}
.box-card {
  border-radius: 12px;
  border: 1px solid var(--el-border-color-lighter, #e5e7eb);
}

.switch-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}
.switch-card {
  background: #f9fafb;
  border: 1px solid #f3f4f6;
  border-radius: 8px;
  padding: 14px 16px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
}
.switch-card.maintenance {
  background: #fff5f5;
  border-color: #fee2e2;
}
.switch-meta {
  display: flex;
  flex-direction: column;
}
.switch-meta strong {
  font-size: 14px;
  color: #1f2937;
}
.switch-meta small {
  font-size: 12px;
  color: #6b7280;
  margin-top: 2px;
}

.mt-4 {
  margin-top: 16px;
}

.advanced-collapse {
  background: #ffffff;
  border: 1px solid var(--el-border-color-lighter, #e5e7eb);
  border-radius: 12px;
  overflow: hidden;
  padding: 0 16px;
}
.advanced-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 24px;
  padding: 12px 0;
}
.advanced-section h4 {
  font-size: 14px;
  font-weight: 600;
  color: #374151;
  margin: 0 0 12px;
}

@media (max-width: 768px) {
  .switch-grid, .advanced-grid {
    grid-template-columns: 1fr;
  }
}
</style>
