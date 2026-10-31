<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { showApiError } from '../../api/api-result'
import { documentApi } from '../document/document-api'
import { projectApi } from '../project/project-api'
import type { Project } from '../project/types'
import { useAuthStore } from '../../stores/auth-store'
import PageHeader from '../../shared/PageHeader.vue'
import {
  connectorApi,
  type ConnectorConnection,
  type ConnectorSource,
  type ConnectorGrant,
  type ConnectorConnectionOption,
  type ExternalDocument,
  type ConnectorSourceDocuments,
  type ConnectorEventStatus,
} from '../../api/connector-api'
import { parseFolderInput, folderInputErrorText } from './feishu-folder-url'

const route = useRoute()
const auth = useAuthStore()
const projectId = computed(() =>
  typeof route.params.projectId === 'string' ? route.params.projectId : '',
)
const isSystemAdmin = computed(() => auth.currentUser?.systemAdmin === true)
const project = ref<Project | null>(null)
const canManageConnections = computed(() => {
  const role = project.value?.role
  return isSystemAdmin.value || role === 'OWNER' || role === 'ADMIN'
})
const callbackHint = computed(() => `${window.location.origin}/api/v1/connector/oauth/callback`)

const loading = ref(false)
const sources = ref<ConnectorSource[]>([])
const connections = ref<ConnectorConnection[]>([])
const availableConnections = ref<ConnectorConnectionOption[]>([])
const grants = ref<ConnectorGrant[]>([])
const eventStatuses = ref<Record<string, ConnectorEventStatus>>({})

const createDialog = ref(false)
const createForm = reactive({ connectionId: '', folderToken: '', name: '', authorizationConfirmed: false, userAuthorizationId: '' })
const creating = ref(false)

const docsDrawer = ref(false)
const docsLoading = ref(false)
const currentSource = ref<ConnectorSource | null>(null)
const documents = ref<ExternalDocument[]>([])

const connectionDialog = ref(false)
const connectionForm = reactive({ name: '', appId: '', appSecret: '' })
const connectionSubmitting = ref(false)
const projectConnectionForm = reactive({ name: '', appId: '', appSecret: '' })
const creatingProjectConnection = ref(false)
const eventDialog = ref(false)
const eventConnection = ref<ConnectorConnection | null>(null)
const eventForm = reactive({ encryptKey: '', verificationToken: '' })
const eventSubmitting = ref(false)
const syncingIds = ref<string[]>([])
const authPolling = ref(false)
let authPollingTimer: number | null = null
let authPollDeadline = 0
let authPollSnapshot: { ids: Set<string>; activeIds: Set<string> } | null = null

const parsedFolder = computed(() => parseFolderInput(createForm.folderToken))
const folderError = computed(() =>
  parsedFolder.value.ok || parsedFolder.value.reason === 'empty' ? '' : folderInputErrorText[parsedFolder.value.reason])
const folderHint = computed(() => {
  if (!parsedFolder.value.ok) return ''
  return parsedFolder.value.source === 'url'
    ? `已识别文件夹 Token：${parsedFolder.value.token}`
    : '将使用输入的原始 folderToken'
})
const projectManagedConnections = computed(() =>
  availableConnections.value.filter(connection => connection.visibility === 'PROJECT'))

const statusLabel: Record<string, string> = {
  ACTIVE: '正常', BLOCKED: '已阻断（需重新验证）',
  REFRESHING: '正在刷新', REFRESH_UNCERTAIN: '刷新结果未知，请重新授权',
  REAUTH_REQUIRED: '需要重新授权', REVOKED: '已断开',
  RUNNING: '同步中', SUCCESS: '同步成功', PARTIAL_FAILED: '部分失败', FAILED: '同步失败',
}

async function load(): Promise<void> {
  if (!projectId.value) return
  loading.value = true
  try {
    const projectResult = await projectApi.get(projectId.value)
    project.value = projectResult.data
    const result = await connectorApi.listSources(projectId.value)
    sources.value = result.data ?? []
    await refreshAuthorizations()
    if (canManageConnections.value) {
      await loadAvailableConnections()
      await loadEventStatuses()
    }
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
  createForm.userAuthorizationId = ''
  try {
    await Promise.all([loadAvailableConnections(), refreshAuthorizations()])
  } catch (error) {
    showApiError(error, '加载可用连接和授权')
    return
  }
  if (isSystemAdmin.value && connections.value.length === 0) {
    try {
      const result = await connectorApi.listConnections()
      connections.value = result.data ?? []
    } catch { /* 列表加载失败时允许用户手动处理 */ }
  }
  createDialog.value = true
}

async function loadAvailableConnections(): Promise<void> {
  const result = await connectorApi.availableConnections(projectId.value)
  availableConnections.value = result.data ?? []
}

async function loadEventStatuses(): Promise<void> {
  const entries = await Promise.all(availableConnections.value
    .filter(connection => connection.visibility === 'PROJECT')
    .map(async connection => {
      try {
        const result = await connectorApi.eventStatus(projectId.value, connection.id)
        return [connection.id, result.data ?? { configured: false, verified: false }] as const
      } catch {
        return [connection.id, { configured: false, verified: false }] as const
      }
    }))
  eventStatuses.value = Object.fromEntries(entries)
}

async function createProjectConnection(): Promise<void> {
  if (!projectId.value || creatingProjectConnection.value) return
  creatingProjectConnection.value = true
  try {
    await connectorApi.createConnection({
      name: projectConnectionForm.name.trim(),
      appId: projectConnectionForm.appId.trim(),
      appSecret: projectConnectionForm.appSecret.trim(),
      projectId: projectId.value,
    })
    projectConnectionForm.name = ''
    projectConnectionForm.appId = ''
    projectConnectionForm.appSecret = ''
    ElMessage.success('连接已创建（App Secret 已加密保存，仅本项目可用）')
    createForm.connectionId = ''
    await loadAvailableConnections()
  } catch (error) {
    showApiError(error, '创建连接')
  } finally {
    creatingProjectConnection.value = false
  }
}

async function submitCreate(): Promise<void> {
  if (!projectId.value) return
  if (!parsedFolder.value.ok) {
    ElMessage.warning(folderError.value || '请先粘贴有效的飞书文件夹链接或 folderToken')
    return
  }
  creating.value = true
  try {
    const created = await connectorApi.createSource(projectId.value, {
      connectionId: createForm.connectionId,
      folderToken: parsedFolder.value.token,
      name: createForm.name.trim(),
      authorizationConfirmed: createForm.authorizationConfirmed,
      userAuthorizationId: createForm.userAuthorizationId || null,
    })
    createDialog.value = false
    try {
      await connectorApi.syncSource(projectId.value, created.data.id)
      ElMessage.success('知识源已添加，首次同步已开始')
    } catch {
      ElMessage.warning('知识源已添加，但首次同步启动失败，可在列表中点击“同步”重试')
    }
    await load()
  } catch (error) {
    showApiError(error, '添加知识源')
  } finally {
    creating.value = false
  }
}

function stopAuthorizationPolling(): void {
  if (authPollingTimer !== null) {
    window.clearInterval(authPollingTimer)
    authPollingTimer = null
  }
  authPolling.value = false
}

async function pollAuthorizationResult(): Promise<void> {
  if (!projectId.value || !authPollSnapshot) return
  try {
    const result = await connectorApi.authorizations(projectId.value)
    grants.value = result.data ?? []
    const active = grants.value.filter(grant =>
      grant.connectionId === createForm.connectionId && grant.status === 'ACTIVE')
    const fresh = active.find(grant => !authPollSnapshot!.ids.has(grant.id))
    const revived = active.find(grant =>
      authPollSnapshot!.ids.has(grant.id) && !authPollSnapshot!.activeIds.has(grant.id))
    if (fresh || revived) {
      stopAuthorizationPolling()
      createForm.userAuthorizationId = (fresh ?? revived)!.id
      ElMessage.success('飞书授权成功，可以继续添加知识源')
    }
  } catch {
    // 单次轮询失败不终止等待，由下一轮重试；超时由 deadline 停止。
  }
  if (authPolling.value && Date.now() > authPollDeadline) {
    stopAuthorizationPolling()
    ElMessage.info('暂未检测到新的授权，可完成授权后点击“刷新授权状态”')
  }
}

function beginAuthorizationPolling(): void {
  stopAuthorizationPolling()
  authPollSnapshot = {
    ids: new Set(grants.value.map(grant => grant.id)),
    activeIds: new Set(grants.value.filter(grant => grant.status === 'ACTIVE').map(grant => grant.id)),
  }
  authPollDeadline = Date.now() + 5 * 60 * 1000
  authPolling.value = true
  authPollingTimer = window.setInterval(() => { void pollAuthorizationResult() }, 3000)
}

async function refreshAuthorizations(): Promise<void> {
  try { grants.value = (await connectorApi.authorizations(projectId.value)).data ?? [] }
  catch (error) { showApiError(error, '刷新飞书授权') }
}

async function startAuthorization(): Promise<void> {
  if (!projectId.value || !createForm.connectionId) return
  const existingActive = grants.value.some(grant =>
    grant.connectionId === createForm.connectionId && grant.status === 'ACTIVE')
  const popup = window.open('about:blank', '_blank')
  if (!popup) { ElMessage.warning('请允许弹出窗口后重试'); return }
  popup.opener = null
  try {
    const result = await connectorApi.startAuthorization(projectId.value, createForm.connectionId)
    popup.location.assign(result.data.authorizationUrl)
    if (existingActive) {
      ElMessage.info('当前连接已有有效授权；如需更新，请在新窗口完成授权后点击“刷新授权状态”')
    } else {
      beginAuthorizationPolling()
      ElMessage.info('请在新窗口完成授权，本页面将自动检测授权结果')
    }
  } catch (error) {
    popup.close()
    showApiError(error, '发起飞书授权')
  }
}

async function disconnectAuthorization(grant: ConnectorGrant): Promise<void> {
  try {
    await ElMessageBox.confirm('断开后将立即屏蔽该授权导入的知识，并安排清理文本和索引。', '断开飞书授权',
      { confirmButtonText: '断开并清理', cancelButtonText: '取消', type: 'warning' })
    await connectorApi.disconnectAuthorization(projectId.value, grant.id)
    createForm.userAuthorizationId = ''
    await load()
    ElMessage.success('授权已断开，清理任务已安排')
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') showApiError(error, '断开飞书授权')
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
    const payload: ConnectorSourceDocuments | undefined = result.data
    documents.value = payload?.documents ?? []
  } catch (error) {
    showApiError(error, '同步文档加载')
  } finally {
    docsLoading.value = false
  }
}

async function downloadIndexed(doc: ExternalDocument): Promise<void> {
  if (!projectId.value || !doc.documentId) return
  try {
    const result = await documentApi.downloadUrl(projectId.value, doc.documentId)
    window.location.assign(result.data.url)
  } catch (error) {
    showApiError(error, '下载已索引文本')
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

function openEventConfig(connection: ConnectorConnection): void {
  eventConnection.value = connection
  eventForm.encryptKey = ''
  eventForm.verificationToken = ''
  eventDialog.value = true
}

async function submitEventConfig(): Promise<void> {
  if (!eventConnection.value) return
  eventSubmitting.value = true
  try {
    await connectorApi.configureEvents(eventConnection.value.id, eventForm.encryptKey, eventForm.verificationToken)
    eventForm.encryptKey = ''
    eventForm.verificationToken = ''
    eventDialog.value = false
    ElMessage.success('事件凭据已加密保存，请在飞书后台完成事件订阅并验证')
    await loadEventStatuses()
  } catch (error) {
    showApiError(error, '配置飞书撤权事件')
  } finally {
    eventSubmitting.value = false
  }
}

watch(createDialog, open => { if (!open) stopAuthorizationPolling() })
onUnmounted(stopAuthorizationPolling)

function formatTime(value: string | null): string {
  if (!value) return '—'
  return new Date(value).toLocaleString()
}

onMounted(load)

defineExpose({ createForm, parsedFolder, submitCreate, startAuthorization, createDialog, eventStatuses })
</script>

<template>
  <main class="workspace-page knowledge-source-page">
    <PageHeader
      eyebrow="项目知识库"
      title="飞书知识源"
      description="把已授权的飞书文件夹同步到项目知识库。飞书原文可在飞书中打开；平台保存和下载的是已索引文本副本，不提供原始二进制文件。"
      :context="project?.name"
    >
      <template #actions>
      <el-button type="primary" @click="openCreate">添加飞书文件夹</el-button>
      </template>
    </PageHeader>

    <section v-if="grants.some(grant => grant.status !== 'REVOKED')" class="connections">
      <h3>我的飞书授权</h3>
      <div v-for="grant in grants.filter(item => item.status !== 'REVOKED')" :key="grant.id">
        {{ sources.filter(source => source.connectionId === grant.connectionId).map(source => source.name).join('、') || '飞书连接' }} ·
        {{ statusLabel[grant.status] || grant.status }}
        <el-button text type="danger" @click="disconnectAuthorization(grant)">断开并清理</el-button>
      </div>
    </section>

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
        两种授权身份：应用身份使用飞书应用的凭据读取已授权给该应用的文件夹，无需个人授权；
        用户身份通过飞书 OAuth 使用你本人的读取权限，需要完成事件验证后授权。
        可以随时切换身份，切换不影响项目隔离。
      </el-alert>
      <el-form label-width="120px">
        <el-form-item label="连接" required>
          <el-select v-model="createForm.connectionId" placeholder="选择飞书应用连接" style="width: 100%" @change="createForm.userAuthorizationId = ''">
            <el-option
                       v-for="connection in availableConnections"
                       :key="connection.id" :value="connection.id" :label="connection.name" />
          </el-select>
          <div v-if="availableConnections.length === 0" class="hint-text">
            <template v-if="canManageConnections">
              暂无可用连接。可在下方录入你自己飞书应用的凭据，创建仅本项目可见的专属连接。
            </template>
            <template v-else>暂无可用连接，请联系系统管理员在「管理中心」创建。</template>
          </div>
          <template v-if="canManageConnections">
            <el-divider content-position="left">新建本项目专属连接</el-divider>
            <el-form label-width="120px" @submit.prevent>
              <el-form-item label="连接名称" required>
                <el-input v-model="projectConnectionForm.name" maxlength="100" placeholder="如：我的社团飞书应用" />
              </el-form-item>
              <el-form-item label="App ID" required>
                <el-input v-model="projectConnectionForm.appId" placeholder="cli_xxxxxxxx" />
              </el-form-item>
              <el-form-item label="App Secret" required>
                <el-input v-model="projectConnectionForm.appSecret" type="password" show-password placeholder="仅在创建时输入一次" />
              </el-form-item>
              <div class="hint-text">
                创建前请在该飞书应用后台完成：开通文档相关权限，并把
                <code>{{ callbackHint }}</code> 加入重定向 URL 白名单。
                凭据加密存储，任何人都无法再次查看。
              </div>
              <el-button
                text type="primary" :loading="creatingProjectConnection"
                :disabled="!projectConnectionForm.name || !projectConnectionForm.appId || !projectConnectionForm.appSecret"
                @click="createProjectConnection"
              >创建并使用该连接</el-button>
            </el-form>
          </template>
        </el-form-item>
        <el-form-item label="授权身份">
          <el-select v-model="createForm.userAuthorizationId" style="width: 100%">
            <el-option label="应用身份（已有方式）" value="" />
            <el-option
              v-for="grant in grants.filter(g => g.connectionId === createForm.connectionId && g.status === 'ACTIVE')"
              :key="grant.id" :value="grant.id" label="我的飞书用户授权" />
          </el-select>
          <div>
            <el-button text type="primary" :disabled="!createForm.connectionId" @click="startAuthorization">授权我的飞书账号</el-button>
            <el-button text @click="refreshAuthorizations">刷新授权状态</el-button>
          </div>
          <div v-for="grant in grants.filter(g => g.connectionId === createForm.connectionId && g.status !== 'REVOKED')" :key="grant.id">
            授权状态：{{ statusLabel[grant.status] || grant.status }}
            <el-button text type="danger" @click="disconnectAuthorization(grant)">断开并清理</el-button>
          </div>
        </el-form-item>
        <el-form-item label="文件夹链接" required>
          <el-input v-model="createForm.folderToken" data-test="folder-input"
                    placeholder="粘贴完整飞书文件夹链接（https://xxx.feishu.cn/drive/folder/xxx），或直接输入原始 folderToken" />
          <div v-if="folderHint" class="hint-text">{{ folderHint }}</div>
          <div v-if="folderError" class="folder-error" data-test="folder-error">{{ folderError }}</div>
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
        <el-button
                   type="primary" :loading="creating"
                   :disabled="!createForm.connectionId || !parsedFolder.ok || !createForm.name || !createForm.authorizationConfirmed"
                   @click="submitCreate">验证并添加</el-button>
      </template>
    </el-dialog>

    <section v-if="canManageConnections && availableConnections.length > 0" class="connections">
      <h3>本项目可用连接</h3>
      <el-table :data="availableConnections" size="small" data-test="project-connections">
        <el-table-column prop="name" label="名称" min-width="140" />
        <el-table-column label="类型" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="row.visibility === 'PROJECT' ? 'success' : 'info'">
              {{ row.visibility === 'PROJECT' ? '本项目专属' : '系统级共享' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'danger'">
              {{ row.status === 'ACTIVE' ? '正常' : '凭据失效' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="事件验证" width="110">
          <template #default="{ row }">
            <template v-if="row.visibility === 'PROJECT'">
              <el-tag v-if="eventStatuses[row.id]?.verified" size="small" type="success">已验证</el-tag>
              <el-tag v-else-if="eventStatuses[row.id]?.configured" size="small" type="warning">待验证</el-tag>
              <el-tag v-else size="small" type="info">未配置</el-tag>
            </template>
            <span v-else>由系统管理员管理</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <template v-if="row.visibility === 'PROJECT'">
              <el-button size="small" @click="verifyConnection(row as ConnectorConnection)">验证凭据</el-button>
              <el-button size="small" @click="openEventConfig(row as ConnectorConnection)">撤权事件</el-button>
            </template>
            <span v-else class="hint-text">系统管理员统一管理</span>
          </template>
        </el-table-column>
      </el-table>
      <div class="hint-text">
        用户身份授权要求事件验证完成：配置 Encrypt Key / Verification Token 后，到飞书开放平台把
        <code>/api/v1/connector/events/{连接ID}</code> 加入事件订阅并保存，验证通过后此处变为“已验证”。
        仅使用应用身份时可以跳过该步骤。
      </div>
    </section>

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
          <el-button v-if="doc.documentId" text type="primary" @click="downloadIndexed(doc)">下载已索引文本</el-button>
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
        <el-table-column label="操作" width="200">
          <template #default="{ row }">
            <el-button size="small" @click="verifyConnection(row)">验证</el-button>
            <el-button size="small" @click="openEventConfig(row)">撤权事件</el-button>
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
        <el-button
                   type="primary" :loading="connectionSubmitting"
                   :disabled="!connectionForm.name || !connectionForm.appId || !connectionForm.appSecret"
                   @click="submitConnection">验证并保存</el-button>
      </template>
    </el-dialog>

    <el-dialog
               v-model="eventDialog" title="配置飞书撤权事件" width="520"
               @closed="eventForm.encryptKey = ''; eventForm.verificationToken = ''">
      <el-alert type="info" :closable="false" class="hint">
        在飞书后台订阅用户令牌撤销事件，并将请求地址设为后端 HTTPS 网关加以下路径：
        <code v-if="eventConnection">/api/v1/connector/events/{{ eventConnection.id }}</code>。
        两项凭据仅加密保存，不会回显。
        更换凭据会暂时屏蔽关联的用户授权知识，并要求重新授权；已有数据不会因此删除。
      </el-alert>
      <el-form label-width="150px">
        <el-form-item label="Encrypt Key" required>
          <el-input v-model="eventForm.encryptKey" type="password" show-password />
        </el-form-item>
        <el-form-item label="Verification Token" required>
          <el-input v-model="eventForm.verificationToken" type="password" show-password />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="eventDialog = false">取消</el-button>
        <el-button
                   type="primary" :loading="eventSubmitting"
                   :disabled="eventForm.encryptKey.length < 16 || eventForm.verificationToken.length < 16"
                   @click="submitEventConfig">加密保存</el-button>
      </template>
    </el-dialog>
  </main>
</template>

<style scoped>
.knowledge-source-page { display: flex; flex-direction: column; gap: 16px; }
.connections h3 { margin: 0 0 8px; font-size: 15px; }
.job-summary { margin-left: 8px; color: var(--el-text-color-secondary); font-size: 12px; }
.job-error { color: var(--el-color-danger); font-size: 12px; margin-top: 2px; word-break: break-all; }
.hint { margin-bottom: 12px; }
.hint-text { color: var(--el-text-color-secondary); font-size: 12px; word-break: break-word; }
.hint-text code { word-break: break-all; }
.knowledge-source-page :deep(.el-dialog) { max-width: calc(100vw - 32px); }
.knowledge-source-page :deep(.el-form-item__content) { min-width: 0; }
.folder-error { color: var(--el-color-danger); font-size: 12px; margin-top: 4px; }
.doc-row { display: flex; justify-content: space-between; align-items: center; padding: 8px 0; border-bottom: 1px solid var(--el-border-color-lighter); gap: 8px; }
.doc-title { display: flex; align-items: center; gap: 6px; }
.connections-header { display: flex; justify-content: space-between; align-items: center; margin-top: 8px; }
.connections-header h3 { margin: 0; font-size: 15px; }
</style>
