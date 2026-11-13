<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  CircleCheck,
  CircleClose,
  Promotion,
  Refresh,
  RefreshLeft,
  VideoPlay,
} from '@element-plus/icons-vue'
import {
  adminCapabilityApi,
  type CapabilityAssignmentView,
  type CapabilityProfileStatus,
  type CapabilityProfileView,
  type CapabilityTestResponse,
} from '../../../api/admin-capability-api'
import { showApiError } from '../../../api/api-result'

const loading = ref(false)
const busyKey = ref('')
const profiles = ref<CapabilityProfileView[]>([])
const assignments = ref<CapabilityAssignmentView[]>([])
const selectedProfileId = ref('')

const rerankProfiles = computed(() => profiles.value.filter((item) => item.capability === 'RERANK'))
const publishedProfiles = computed(() =>
  rerankProfiles.value.filter((item) => item.status === 'PUBLISHED'),
)
const globalAssignment = computed(() =>
  assignments.value.find(
    (item) =>
      item.scope === 'GLOBAL' &&
      item.capability === 'RERANK' &&
      item.purpose === 'KNOWLEDGE_RERANK',
  ),
)
const boundProfile = computed(
  () => rerankProfiles.value.find((item) => item.id === globalAssignment.value?.profileId) ?? null,
)

const testVisible = ref(false)
const testRunning = ref(false)
const testProfile = ref<CapabilityProfileView | null>(null)
const testResult = ref<CapabilityTestResponse | null>(null)
const testForm = reactive({
  query: '版本发布前需要完成哪些检查？',
  topK: 2,
  documents: [
    {
      id: 'reranker-doc-1',
      title: '发布检查',
      text: '版本发布前需要完成单元测试、回归测试、数据库迁移检查和回滚预案确认。',
    },
    {
      id: 'reranker-doc-2',
      title: '午餐安排',
      text: '团队午餐可以提前一天在行政系统中登记。',
    },
  ],
})

const statusLabels: Record<CapabilityProfileStatus, string> = {
  DRAFT: '草稿',
  TESTED: '已测试',
  PUBLISHED: '已发布',
  DISABLED: '已停用',
}

function statusType(status: CapabilityProfileStatus): 'info' | 'warning' | 'success' | 'danger' {
  if (status === 'PUBLISHED') return 'success'
  if (status === 'TESTED') return 'warning'
  if (status === 'DISABLED') return 'danger'
  return 'info'
}

function settingsSummary(profile: CapabilityProfileView): string {
  const settings = profile.settings || {}
  const values = Object.entries(settings)
    .slice(0, 3)
    .map(([key, value]) => `${key}=${String(value)}`)
  return values.length ? values.join(' · ') : '未填写额外参数'
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const [profileRes, assignmentRes] = await Promise.all([
      adminCapabilityApi.listProfiles(),
      adminCapabilityApi.listAssignments(),
    ])
    profiles.value = profileRes.data
    assignments.value = assignmentRes.data
    if (!selectedProfileId.value && publishedProfiles.value.length > 0) {
      selectedProfileId.value = publishedProfiles.value[0]!.id
    }
  } catch (error) {
    showApiError(error, 'Reranker 管理数据加载')
  } finally {
    loading.value = false
  }
}

function openTest(profile: CapabilityProfileView): void {
  testProfile.value = profile
  testResult.value = null
  testVisible.value = true
}

async function runTest(): Promise<void> {
  if (!testProfile.value) return
  if (!testForm.query.trim()) {
    ElMessage.warning('请填写测试查询')
    return
  }
  testRunning.value = true
  try {
    const res = await adminCapabilityApi.testProfile(testProfile.value.id, {
      query: testForm.query.trim(),
      topK: testForm.topK,
      documents: testForm.documents.map((item) => ({
        id: item.id,
        title: item.title,
        text: item.text,
      })),
    })
    testResult.value = res.data
    if (res.data.outcome === 'PASS') ElMessage.success('Reranker 测试通过')
    else ElMessage.warning(`Reranker 测试返回 ${res.data.outcome}`)
  } catch (error) {
    showApiError(error, 'Reranker 测试')
  } finally {
    testRunning.value = false
  }
}

async function publishProfile(profile: CapabilityProfileView): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `发布「${profile.modelDisplayName}」的 RERANK 能力后，可用于全局或项目用途绑定。`,
      '发布 Reranker 能力',
      { confirmButtonText: '确认发布', cancelButtonText: '取消', type: 'warning' },
    )
    busyKey.value = `${profile.id}:publish`
    await adminCapabilityApi.publish(profile.id, '管理员从 Reranker 管理页发布')
    ElMessage.success('Reranker 能力已发布')
    await load()
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    showApiError(error, '发布 Reranker 能力')
  } finally {
    busyKey.value = ''
  }
}

async function rollbackProfile(profile: CapabilityProfileView): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确认回滚「${profile.modelDisplayName}」的已发布能力吗？`,
      '回滚 Reranker 能力',
      { confirmButtonText: '确认回滚', cancelButtonText: '取消', type: 'warning' },
    )
    busyKey.value = `${profile.id}:rollback`
    await adminCapabilityApi.rollback(profile.id, '管理员从 Reranker 管理页回滚')
    ElMessage.success('Reranker 能力已回滚')
    await load()
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    showApiError(error, '回滚 Reranker 能力')
  } finally {
    busyKey.value = ''
  }
}

async function disableProfile(profile: CapabilityProfileView): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确认停用「${profile.modelDisplayName}」的 RERANK 能力吗？已绑定的用途将回退。`,
      '停用 Reranker 能力',
      { confirmButtonText: '确认停用', cancelButtonText: '取消', type: 'warning' },
    )
    busyKey.value = `${profile.id}:disable`
    await adminCapabilityApi.disable(profile.id, '管理员从 Reranker 管理页停用')
    ElMessage.success('Reranker 能力已停用')
    await load()
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    showApiError(error, '停用 Reranker 能力')
  } finally {
    busyKey.value = ''
  }
}

async function bindGlobal(): Promise<void> {
  if (!selectedProfileId.value) {
    ElMessage.warning('请选择已发布的 Reranker 能力')
    return
  }
  try {
    busyKey.value = 'assignment:bind'
    await adminCapabilityApi.bindGlobal(selectedProfileId.value, '管理员从 Reranker 管理页绑定')
    ElMessage.success('全局 RERANK 用途已绑定')
    await load()
  } catch (error) {
    showApiError(error, '绑定全局 RERANK 用途')
  } finally {
    busyKey.value = ''
  }
}

async function removeAssignment(): Promise<void> {
  const assignment = globalAssignment.value
  if (!assignment) return
  try {
    await ElMessageBox.confirm(
      '确认解绑全局 RERANK 用途吗？系统将不再使用当前 Reranker 能力。',
      '解绑全局 RERANK',
      { confirmButtonText: '确认解绑', cancelButtonText: '取消', type: 'warning' },
    )
    busyKey.value = 'assignment:remove'
    await adminCapabilityApi.deleteAssignment(assignment.id)
    ElMessage.success('全局 RERANK 用途已解绑')
    await load()
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    showApiError(error, '解绑全局 RERANK 用途')
  } finally {
    busyKey.value = ''
  }
}

onMounted(load)
</script>

<template>
  <div v-loading="loading" class="reranker-admin">
    <div class="toolbar">
      <div>
        <h2>Reranker 能力管理</h2>
        <p>只管理能力档案与用途绑定，不改变当前 RERANK_MODE=java 的生产策略。</p>
      </div>
      <el-button :icon="Refresh" @click="load">刷新</el-button>
    </div>

    <section class="summary-grid">
      <div class="summary-card">
        <span>RERANK 档案</span>
        <strong>{{ rerankProfiles.length }}</strong>
      </div>
      <div class="summary-card">
        <span>已发布</span>
        <strong>{{ publishedProfiles.length }}</strong>
      </div>
      <div class="summary-card">
        <span>全局用途</span>
        <strong>{{ globalAssignment?.enabled ? '已绑定' : '未绑定' }}</strong>
      </div>
    </section>

    <el-card class="assignment-card" shadow="never">
      <template #header>
        <div class="card-head">
          <strong>全局 KNOWLEDGE_RERANK 用途</strong>
          <el-tag v-if="boundProfile" type="success" size="small">{{
            boundProfile.modelDisplayName
          }}</el-tag>
          <el-tag v-else type="info" size="small">未绑定</el-tag>
        </div>
      </template>
      <div v-if="globalAssignment" class="assignment-row">
        <div>
          <div class="assignment-name">
            {{ boundProfile?.modelDisplayName || globalAssignment.profileId }}
          </div>
          <small>
            档案版本 v{{ globalAssignment.profileVersion }} · 状态
            {{ globalAssignment.profileStatus || '未知' }} · 更新
            {{ new Date(globalAssignment.updatedAt).toLocaleString() }}
          </small>
        </div>
        <el-button
          type="danger"
          plain
          :loading="busyKey === 'assignment:remove'"
          @click="removeAssignment"
        >
          解绑
        </el-button>
      </div>
      <div v-else class="assignment-empty">
        <span>尚未绑定全局 Reranker。发布能力后可在此启用。</span>
        <div class="bind-row">
          <el-select
            v-model="selectedProfileId"
            placeholder="选择已发布的 RERANK 档案"
            style="width: 300px"
          >
            <el-option
              v-for="profile in publishedProfiles"
              :key="profile.id"
              :label="`${profile.modelDisplayName} · ${profile.protocol}`"
              :value="profile.id"
            />
          </el-select>
          <el-button
            type="primary"
            :disabled="!selectedProfileId"
            :loading="busyKey === 'assignment:bind'"
            @click="bindGlobal"
          >
            绑定全局用途
          </el-button>
        </div>
      </div>
    </el-card>

    <div v-if="rerankProfiles.length === 0" class="empty-box">
      <el-icon><CircleClose /></el-icon>
      <p>暂无 RERANK 能力档案</p>
      <small>请先在 AI 供应商配置中创建 Reranker Provider 与能力档案。</small>
    </div>

    <div v-else class="profile-list">
      <el-card
        v-for="profile in rerankProfiles"
        :key="profile.id"
        class="profile-card"
        shadow="never"
      >
        <div class="profile-head">
          <div>
            <div class="profile-title-row">
              <strong>{{ profile.modelDisplayName }}</strong>
              <el-tag :type="statusType(profile.status)" size="small">{{
                statusLabels[profile.status]
              }}</el-tag>
              <el-tag
                v-if="globalAssignment?.profileId === profile.id"
                type="success"
                size="small"
                effect="plain"
              >
                全局使用中
              </el-tag>
            </div>
            <div class="profile-sub">
              {{ profile.providerName }} · {{ profile.modelKey }} · {{ profile.protocol }} · v{{
                profile.version
              }}
            </div>
          </div>
          <el-tag type="info" size="small" effect="plain">{{ profile.source }}</el-tag>
        </div>

        <div class="settings-line">
          <span>参数</span>
          <code>{{ settingsSummary(profile) }}</code>
        </div>

        <div class="profile-actions">
          <el-button size="small" :icon="VideoPlay" @click="openTest(profile)">测试</el-button>
          <el-button
            v-if="profile.status === 'TESTED'"
            size="small"
            type="success"
            plain
            :icon="Promotion"
            :loading="busyKey === `${profile.id}:publish`"
            @click="publishProfile(profile)"
          >
            发布
          </el-button>
          <el-tag v-if="profile.status === 'DRAFT'" type="info" size="small"
            >测试通过后才能发布</el-tag
          >
          <el-button
            v-if="profile.status === 'PUBLISHED'"
            size="small"
            type="warning"
            plain
            :icon="RefreshLeft"
            :loading="busyKey === `${profile.id}:rollback`"
            @click="rollbackProfile(profile)"
          >
            回滚
          </el-button>
          <el-button
            v-if="profile.status !== 'DISABLED'"
            size="small"
            type="danger"
            text
            :loading="busyKey === `${profile.id}:disable`"
            @click="disableProfile(profile)"
          >
            停用
          </el-button>
          <el-tag v-if="profile.status === 'DISABLED'" type="info" size="small"
            >需重新测试后再发布</el-tag
          >
        </div>
      </el-card>
    </div>

    <el-dialog v-model="testVisible" title="Reranker 能力测试" width="640px" destroy-on-close>
      <div v-if="testProfile" class="test-profile">
        <strong>{{ testProfile.modelDisplayName }}</strong>
        <span>{{ testProfile.protocol }} · {{ testProfile.providerName }}</span>
      </div>
      <el-form label-position="top">
        <el-form-item label="查询">
          <el-input v-model="testForm.query" maxlength="2000" show-word-limit />
        </el-form-item>
        <el-form-item label="候选文档">
          <div class="doc-list">
            <div v-for="(doc, index) in testForm.documents" :key="doc.id" class="doc-row">
              <el-input v-model="doc.title" placeholder="标题" style="width: 150px" />
              <el-input v-model="doc.text" type="textarea" :rows="2" />
              <el-button
                v-if="testForm.documents.length > 1"
                text
                type="danger"
                @click="testForm.documents.splice(index, 1)"
              >
                删除
              </el-button>
            </div>
          </div>
        </el-form-item>
        <el-form-item label="返回条数">
          <el-input-number v-model="testForm.topK" :min="1" :max="50" />
        </el-form-item>
      </el-form>

      <div v-if="testResult" class="test-result">
        <div class="result-head">
          <el-tag :type="testResult.outcome === 'PASS' ? 'success' : 'warning'">{{
            testResult.outcome
          }}</el-tag>
          <span>总耗时 {{ testResult.totalMs }}ms · overhead {{ testResult.overheadMs }}ms</span>
        </div>
        <div class="score-list">
          <div
            v-for="score in testResult.scores"
            :key="`${score.index}-${score.id}`"
            class="score-row"
          >
            <span>#{{ score.index + 1 }} {{ score.id }}</span>
            <strong>{{ score.score.toFixed(4) }}</strong>
          </div>
        </div>
        <small v-if="testResult.reason">{{ testResult.reason }}</small>
      </div>

      <template #footer>
        <el-button @click="testVisible = false">关闭</el-button>
        <el-button type="primary" :loading="testRunning" :icon="CircleCheck" @click="runTest"
          >执行测试</el-button
        >
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.reranker-admin {
  padding-top: 12px;
}
.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  margin-bottom: 16px;
}
.toolbar h2 {
  margin: 0 0 4px;
  font-size: 18px;
  color: #111827;
}
.toolbar p {
  margin: 0;
  font-size: 12px;
  color: #6b7280;
}
.summary-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
  margin-bottom: 16px;
}
.summary-card {
  background: #ffffff;
  border: 1px solid var(--el-border-color-lighter, #e5e7eb);
  border-radius: 10px;
  padding: 14px 16px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.summary-card span {
  font-size: 12px;
  color: #6b7280;
}
.summary-card strong {
  font-size: 20px;
  color: #111827;
}
.assignment-card,
.profile-card {
  border-radius: 12px;
  border: 1px solid var(--el-border-color-lighter, #e5e7eb);
}
.assignment-card {
  margin-bottom: 16px;
}
.card-head,
.profile-head,
.profile-title-row,
.profile-actions,
.result-head {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.card-head {
  justify-content: space-between;
}
.assignment-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
}
.assignment-name {
  font-weight: 600;
  color: #1f2937;
}
.assignment-row small,
.assignment-empty {
  color: #6b7280;
  font-size: 12px;
}
.assignment-empty {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.bind-row {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}
.empty-box {
  background: #ffffff;
  border: 1px dashed #d1d5db;
  border-radius: 12px;
  padding: 40px 20px;
  text-align: center;
  color: #6b7280;
}
.empty-box .el-icon {
  font-size: 34px;
  color: #9ca3af;
}
.empty-box p {
  margin: 8px 0 2px;
  color: #374151;
  font-weight: 600;
}
.profile-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.profile-head {
  justify-content: space-between;
}
.profile-title-row strong {
  font-size: 16px;
  color: #111827;
}
.profile-sub {
  font-size: 12px;
  color: #6b7280;
  margin-top: 4px;
}
.settings-line {
  margin: 14px 0 12px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.settings-line span {
  font-size: 11px;
  color: #6b7280;
}
.settings-line code {
  font-size: 12px;
  color: #374151;
  background: #f3f4f6;
  padding: 6px 8px;
  border-radius: 6px;
  overflow-wrap: anywhere;
}
.profile-actions {
  justify-content: flex-end;
}
.test-profile {
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin-bottom: 12px;
  color: #374151;
}
.test-profile span {
  font-size: 12px;
  color: #6b7280;
}
.doc-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
}
.doc-row {
  display: flex;
  gap: 8px;
  align-items: flex-start;
}
.test-result {
  border-top: 1px solid #f3f4f6;
  padding-top: 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.result-head {
  justify-content: space-between;
  color: #4b5563;
  font-size: 12px;
}
.score-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.score-row {
  display: flex;
  justify-content: space-between;
  background: #f9fafb;
  border-radius: 6px;
  padding: 6px 10px;
  font-size: 12px;
  color: #374151;
}

@media (max-width: 768px) {
  .summary-grid {
    grid-template-columns: 1fr;
  }
  .assignment-row,
  .assignment-empty,
  .profile-head {
    align-items: flex-start;
    flex-direction: column;
  }
  .profile-actions {
    justify-content: flex-start;
  }
  .doc-row {
    flex-direction: column;
  }
}
</style>
