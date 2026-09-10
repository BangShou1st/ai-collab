<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { showApiError } from '../../api/api-result'
import PageHeader from '../../shared/PageHeader.vue'
import { projectApi } from './project-api'
import type { Project } from './types'
import {
  projectModelApi,
  canToggleMcpConnection,
  mcpEditorValues,
  toMcpConnectionInput,
  type McpConnection,
  type McpConnectionInput,
  type McpEditorValues,
} from './project-model-api'

const route = useRoute()
const projectId = computed(() =>
  typeof route.params.projectId === 'string' ? route.params.projectId : '',
)

const project = ref<Project | null>(null)
const mcpConnections = ref<McpConnection[]>([])
const loading = ref(false)
const saving = ref(false)
const mcpDialog = ref(false)
const editingMcpId = ref<string | null>(null)
const mcpActionId = ref('')
const mcpForm = reactive<McpEditorValues>({
  code: '', name: '', endpoint: '', authType: 'BEARER',
  credential: '', timeoutMs: 10000, maxResultBytes: 65536,
  toolAllowlist: '', resourceAllowlist: '', version: 0,
})

async function load(): Promise<void> {
  if (!projectId.value) return
  loading.value = true
  try {
    const [projectResult, mcpResult] = await Promise.all([
      projectApi.get(projectId.value),
      projectModelApi.listMcpConnections(projectId.value),
    ])
    project.value = projectResult.data
    mcpConnections.value = mcpResult.data
  } catch (error) {
    showApiError(error, '集成配置加载')
  } finally {
    loading.value = false
  }
}

function openCreateMcp(): void {
  editingMcpId.value = null
  Object.assign(mcpForm, {
    code: '', name: '', endpoint: '', authType: 'BEARER', credential: '',
    timeoutMs: 10000, maxResultBytes: 65536, toolAllowlist: '',
    resourceAllowlist: '', version: 0,
  })
  mcpDialog.value = true
}

function openEditMcp(item: McpConnection): void {
  editingMcpId.value = item.id
  Object.assign(mcpForm, mcpEditorValues(item))
  mcpDialog.value = true
}

async function saveMcp(): Promise<void> {
  if (!mcpForm.code.trim() || !mcpForm.name.trim() || !mcpForm.endpoint.trim()) return
  const input: McpConnectionInput = toMcpConnectionInput(mcpForm)
  saving.value = true
  try {
    if (editingMcpId.value) await projectModelApi.updateMcpConnection(projectId.value, editingMcpId.value, input)
    else await projectModelApi.createMcpConnection(projectId.value, input)
    mcpDialog.value = false
    ElMessage.success('MCP 连接已保存；请重新发现并确认 Schema 后启用')
    await load()
  } catch (error) {
    showApiError(error, 'MCP 连接保存')
  } finally {
    saving.value = false
  }
}

async function runMcpAction(item: McpConnection, action: 'test' | 'discover' | 'toggle'): Promise<void> {
  mcpActionId.value = `${item.id}:${action}`
  try {
    if (action === 'test') await projectModelApi.testMcpConnection(projectId.value, item.id)
    else if (action === 'discover') await projectModelApi.discoverMcpConnection(projectId.value, item.id)
    else await projectModelApi.setMcpConnectionEnabled(projectId.value, item, !item.enabled)
    await load()
  } catch (error) {
    const actionLabel = action === 'test' ? 'MCP 连接测试'
      : action === 'discover' ? 'MCP 工具发现'
        : item.enabled ? 'MCP 连接停用' : 'MCP 连接启用'
    showApiError(error, actionLabel)
  } finally {
    mcpActionId.value = ''
  }
}

onMounted(load)
</script>

<template>
  <main class="workspace-page">
    <PageHeader
      eyebrow="项目设置"
      title="集成与 MCP"
      :context="project?.name"
      description="项目工具与外部资源集成，由项目管理员维护"
    />

    <section v-loading="loading" class="settings-stack">
      <el-card>
        <template #header>
          <div class="card-header">
            <strong>MCP 连接</strong>
            <el-button type="primary" @click="openCreateMcp">添加 MCP 连接</el-button>
          </div>
        </template>
        <el-table :data="mcpConnections" empty-text="还没有 MCP 连接">
          <el-table-column label="连接" min-width="190">
            <template #default="{ row }">
              <strong>{{ row.name }}</strong>
              <small class="cell-subtitle">{{ row.code }}</small>
              <small class="cell-subtitle">{{ row.endpoint }}</small>
            </template>
          </el-table-column>
          <el-table-column label="健康" width="110">
            <template #default="{ row }">{{ row.lastHealthStatus || '未测试' }}</template>
          </el-table-column>
          <el-table-column label="Schema Hash" min-width="180">
            <template #default="{ row }">
              <code>{{ row.schemaHash || '尚未发现' }}</code>
              <small v-if="row.schemaHash && !row.schemaConfirmed" class="cell-warning">待确认</small>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? '启用' : '停用' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="300" fixed="right">
            <template #default="{ row }">
              <el-button text type="primary" @click="openEditMcp(row)">编辑/轮换凭据</el-button>
              <el-button text :loading="mcpActionId === `${row.id}:test`" @click="runMcpAction(row, 'test')">测试</el-button>
              <el-button text :loading="mcpActionId === `${row.id}:discover`" @click="runMcpAction(row, 'discover')">发现</el-button>
              <el-button text :type="row.enabled ? 'danger' : 'success'" :disabled="!canToggleMcpConnection(row)"
                :loading="mcpActionId === `${row.id}:toggle`" @click="runMcpAction(row, 'toggle')">
                {{ row.enabled ? '停用' : '确认并启用' }}
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </section>

    <el-dialog v-model="mcpDialog" :title="editingMcpId ? '编辑 MCP 连接' : '添加 MCP 连接'" width="680px">
      <el-alert title="当前后端仅允许 HTTPS 的 STREAMABLE_HTTP；保存更新会停用连接，需重新发现并确认 Schema。" type="info" show-icon />
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="连接代码"><el-input v-model="mcpForm.code" placeholder="github-readonly" /></el-form-item>
          <el-form-item label="显示名称"><el-input v-model="mcpForm.name" /></el-form-item>
          <el-form-item label="HTTPS Endpoint"><el-input v-model="mcpForm.endpoint" placeholder="https://mcp.example.com/mcp" /></el-form-item>
          <el-form-item label="认证方式"><el-select v-model="mcpForm.authType"><el-option label="无认证" value="NONE" /><el-option label="Bearer Token" value="BEARER" /></el-select></el-form-item>
          <el-form-item :label="editingMcpId ? '凭据（留空保持不变）' : '凭据'"><el-input v-model="mcpForm.credential" type="password" show-password autocomplete="new-password" /></el-form-item>
          <el-form-item label="超时（毫秒）"><el-input-number v-model="mcpForm.timeoutMs" :min="1000" :max="60000" /></el-form-item>
          <el-form-item label="最大结果字节数"><el-input-number v-model="mcpForm.maxResultBytes" :min="1024" :max="262144" /></el-form-item>
          <el-form-item label="确认只读工具白名单（逗号分隔）"><el-input v-model="mcpForm.toolAllowlist" /></el-form-item>
          <el-form-item label="连接级资源白名单（逗号分隔）"><el-input v-model="mcpForm.resourceAllowlist" /></el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="mcpDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveMcp">保存</el-button>
      </template>
    </el-dialog>
  </main>
</template>

<style scoped>
.settings-stack { display: grid; gap: 18px; }
.card-header { display: flex; align-items: center; justify-content: space-between; }
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
.cell-subtitle { display: block; color: var(--color-text-muted); }
.cell-warning { color: var(--el-color-warning); margin-left: 6px; }
@media (max-width: 720px) { .form-grid { grid-template-columns: 1fr; } }
</style>

