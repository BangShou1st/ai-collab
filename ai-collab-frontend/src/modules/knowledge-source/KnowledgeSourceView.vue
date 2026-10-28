<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { showApiError } from '../../api/api-result'
import { useAuthStore } from '../../stores/auth-store'
import PageHeader from '../../shared/PageHeader.vue'
import {
  connectorApi,
  type ConnectorConnection,
  type ConnectorSource,
  type ExternalDocument,
} from '../../api/connector-api'

const route = useRoute()
const auth = useAuthStore()
const projectId = computed(() =>
  typeof route.params.projectId === 'string' ? route.params.projectId : '',
)
const isSystemAdmin = computed(() => auth.currentUser?.systemAdmin === true)

const loading = ref(false)
const sources = ref<ConnectorSource[]>([])
const connections = ref<ConnectorConnection[]>([])

const createDialog = ref(false)
const createForm = reactive({ connectionId: '', folderToken: '', name: '', authorizationConfirmed: false })
const creating = ref(false)

const docsDrawer = ref(false)
const docsLoading = ref(false)
const currentSource = ref<ConnectorSource | null>(null)
const documents = ref<ExternalDocument[]>([])

const connectionDialog = ref(false)
const connectionForm = reactive({ name: '', appId: '', appSecret: '' })
const connectionSubmitting = ref(false)
const syncingIds = ref<string[]>([])

const statusLabel: Record<string, string> = {
  ACTIVE: '正常', BLOCKED: '已阻断（需重新验证）',
  RUNNING: '同步中', SUCCESS: '同步成功', PARTIAL_FAILED: '部分失败', FAILED: '同步失败',
}

async function load(): Promise<void> {
  if (!projectId.value) return
  loading.value = true
  try {
    const result = await connectorApi.listSources(projectId.value)
    sources.value = result.data ?? []
    if (isSystemAdmin.value) {
      const connResult = await connectorApi.listConnections()
      connections.value = connResult.data ?? []
    }
  } catch (error) {
    showApiError(error, '知识源加载')
  } finally {
    loading.value = false
  }
}

async function openCreate(): Promise<void> {
  createForm.connectionId = ''
  createForm.folderToken = ''
  createForm.name = ''
  createForm.authorizationConfirmed = false
  if (isSystemAdmin.value && connections.value.length === 0) {
    try {
      const result = await connectorApi.listConnections()
      connections.value = result.data ?? []
    } catch { /* 列表加载失败时允许用户手动处理 */ }
  }
  createDialog.value = true
}

async function submitCreate(): Promise<void> {
  if (!projectId.value) return
  creating.value = true
  try {
    await connectorApi.createSource(projectId.value, {
      connectionId: createForm.connectionId,
      folderToken: createForm.folderToken.trim(),
      name: createForm.name.trim(),
      authorizationConfirmed: createForm.authorizationConfirmed,
    })
    createDialog.value = false
    ElMessage.success('知识源已添加，开始首次同步前请点击“同步”')
    await load()
  } catch (error) {
    showApiError(error, '添加知识源')
  } finally {
    creating.value = false
  }
}

async function sync(source: ConnectorSource): Promise<void> {
  if (!projectId.value) return
  syncingIds.value = [...syncingIds.value, source.id]
  try {
    await connectorApi.syncSource(projectId.value, source.id)
    ElMessage.success('同步任务已开始，稍后刷新查看状态')
    window.setTimeout(() => { void load() }, 3000)
  } catch (error) {
    showApiError(error, '触发同步')
  } finally {
    syncingIds.value = syncingIds.value.filter(id => id !== source.id)
  }
}

async function openDocuments(source: ConnectorSource): Promise<void> {
  if (!projectId.value) return
  currentSource.value = source
  docsDrawer.value = true
  docsLoading.value = true
  try {
    const result = await connectorApi.listSourceDocuments(projectId.value, source.id)
    documents.value = result.data ?? []
  } catch (error) {
    showApiError(error, '同步文档加载')
  } finally {
    docsLoading.value = false
  }
}

async function submitConnection(): Promise<void> {
  connectionSubmitting.value = true
  try {
    await connectorApi.createConnection({
      name: connectionForm.name.trim(),
      appId: connectionForm.appId.trim(),
      appSecret: connectionForm.appSecret.trim(),
    })
    connectionDialog.value = false
    connectionForm.name = ''
    connectionForm.appId = ''
    connectionForm.appSecret = ''
    ElMessage.success('连接已创建（App Secret 已加密保存）')
    await load()
  } catch (error) {
    showApiError(error, '创建连接')
  } finally {
    connectionSubmitting.value = false
  }
}

async function verifyConnection(connection: ConnectorConnection): Promise<void> {
  try {
    await connectorApi.verifyConnection(connection.id)
    ElMessage.success('连接验证通过')
    await load()
  } catch (error) {
    showApiError(error, '连接验证')
  }
}

function formatTime(value: string | null): string {
  if (!value) return '—'
  return new Date(value).toLocaleString()
}

onMounted(load)
</script>

<template>
  <div class="knowledge-source">
    <PageHeader title="飞书知识源" description="把已授权的飞书文件夹同步到项目知识库，检索结果保留飞书来源链接。" />
    <div class="actions">
      <el-button type="primary" @click="openCreate">添加飞书文件夹</el-button>
    </div>

    <el-table v-loading="loading" :data="sources" data-test="source-list">
      <el-table-column prop="name" label="名称" min-width="160" />
      <el-table-column label="状态" width="180">
        <template #default="{ row }">
          <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'danger'">{{ statusLabel[row.status] ?? row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="documentCount" label="已同步文档" width="110" />
      <el-table-column label="最近验证" width="170">
        <template #default="{ row }">{{ formatTime(row.lastVerifiedAt) }}</template>
      </el-table-column>
      <el-table-column label="最近同步" width="170">
        <template #default="{ row }">{{ formatTime(row.lastSyncedAt) }}</template>
      </el-table-column>
      <el-table-column label="最近同步结果" min-width="220">
        <template #default="{ row }">
          <template v-if="row.lastJob">
            <el-tag size="small" :type="row.lastJob.status === 'SUCCESS' ? 'success' : row.lastJob.status === 'RUNNING' ? 'primary' : 'danger'">
              {{ statusLabel[row.lastJob.status] ?? row.lastJob.status }}
            </el-tag>
            <span class="job-summary">
              新增/更新 {{ row.lastJob.updated }} · 跳过 {{ row.lastJob.skipped }} · 失败 {{ row.lastJob.failed }} · 移除 {{ row.lastJob.removed }}
            </span>
            <div v-if="row.lastJob.errorSummary" class="job-error">{{ row.lastJob.errorSummary }}</div>
          </template>
          <span v-else>尚未同步</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="220" fixed="right">
        <template #default="{ row }">
          <el-button size="small" type="primary" :loading="syncingIds.includes(row.id)" @click="sync(row)">同步</el-button>
          <el-button size="small" @click="openDocuments(row)">已同步文档</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="createDialog" title="添加飞书文件夹知识源" width="520">
      <el-alert type="info" :closable="false" class="hint">
        前置条件：文件夹已把应用「AI-Collab 知识库连接器」添加为协作者（可阅读）。
      </el-alert>
      <el-form label-width="120px">
        <el-form-item label="连接" required>
          <el-select v-model="createForm.connectionId" placeholder="选择飞书应用连接" style="width: 100%">
            <el-option v-for="connection in connections.filter(c => c.status === 'ACTIVE')"
                       :key="connection.id" :value="connection.id" :label="connection.name" />
          </el-select>
          <div v-if="!isSystemAdmin && connections.length === 0" class="hint-text">
            暂无可用连接，请联系系统管理员在「管理中心」创建。
          </div>
        </el-form-item>
        <el-form-item label="文件夹 Token" required>
          <el-input v-model="createForm.folderToken" placeholder="飞书文件夹 URL 中 folder/ 之后的字符串" />
        </el-form-item>
        <el-form-item label="显示名称" required>
          <el-input v-model="createForm.name" maxlength="200" placeholder="如：赛事资料同步" />
        </el-form-item>
        <el-form-item>
          <el-checkbox v-model="createForm.authorizationConfirmed">
            我确认该文件夹内容已授权给本项目全体成员访问，并愿意为此授权记录负责
          </el-checkbox>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialog = false">取消</el-button>
        <el-button type="primary" :loading="creating" :disabled="!createForm.connectionId || !createForm.folderToken || !createForm.name || !createForm.authorizationConfirmed"
                   @click="submitCreate">验证并添加</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="docsDrawer" :title="`已同步文档 · ${currentSource?.name ?? ''}`" size="480">
      <div v-loading="docsLoading">
        <div v-for="doc in documents" :key="doc.id" class="doc-row">
          <div class="doc-title">
            {{ doc.title ?? doc.externalKey }}
            <el-tag v-if="doc.status !== 'ACTIVE'" size="small" type="warning">{{ doc.status }}</el-tag>
            <el-tag v-else-if="doc.documentStatus && doc.documentStatus !== 'READY'" size="small">
              {{ doc.documentStatus === 'READY' ? '已索引' : doc.documentStatus }}
            </el-tag>
          </div>
          <a v-if="doc.sourceUrl" :href="doc.sourceUrl" target="_blank" rel="noopener noreferrer">在飞书中打开</a>
        </div>
        <el-empty v-if="!docsLoading && documents.length === 0" description="尚未同步任何文档" />
      </div>
    </el-drawer>

    <section v-if="isSystemAdmin" class="connections">
      <div class="connections-header">
        <h3>飞书应用连接（系统管理员）</h3>
        <el-button size="small" type="primary" @click="connectionDialog = true">新增连接</el-button>
      </div>
      <el-table :data="connections" size="small">
        <el-table-column prop="name" label="名称" min-width="140" />
        <el-table-column prop="appId" label="App ID" min-width="180" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'danger'">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="最近验证" width="170">
          <template #default="{ row }">{{ formatTime(row.lastVerifiedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="100">
          <template #default="{ row }">
            <el-button size="small" @click="verifyConnection(row)">验证</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <el-dialog v-model="connectionDialog" title="新增飞书应用连接" width="480">
      <el-alert type="info" :closable="false" class="hint">
        App Secret 仅用于调用飞书官方接口，将以加密形式存储，不会回显。
      </el-alert>
      <el-form label-width="100px">
        <el-form-item label="名称" required>
          <el-input v-model="connectionForm.name" maxlength="100" placeholder="如：社团主应用" />
        </el-form-item>
        <el-form-item label="App ID" required>
          <el-input v-model="connectionForm.appId" placeholder="cli_xxxxxxxx" />
        </el-form-item>
        <el-form-item label="App Secret" required>
          <el-input v-model="connectionForm.appSecret" type="password" show-password placeholder="仅在创建时输入一次" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="connectionDialog = false">取消</el-button>
        <el-button type="primary" :loading="connectionSubmitting"
                   :disabled="!connectionForm.name || !connectionForm.appId || !connectionForm.appSecret"
                   @click="submitConnection">验证并保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.knowledge-source { display: flex; flex-direction: column; gap: 16px; }
.actions { display: flex; justify-content: flex-end; }
.job-summary { margin-left: 8px; color: var(--el-text-color-secondary); font-size: 12px; }
.job-error { color: var(--el-color-danger); font-size: 12px; margin-top: 2px; word-break: break-all; }
.hint { margin-bottom: 12px; }
.hint-text { color: var(--el-text-color-secondary); font-size: 12px; }
.doc-row { display: flex; justify-content: space-between; align-items: center; padding: 8px 0; border-bottom: 1px solid var(--el-border-color-lighter); gap: 8px; }
.doc-title { display: flex; align-items: center; gap: 6px; }
.connections-header { display: flex; justify-content: space-between; align-items: center; margin-top: 8px; }
.connections-header h3 { margin: 0; font-size: 15px; }
</style>
