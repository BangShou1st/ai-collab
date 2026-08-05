<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { showApiError } from '../../api/api-result'
import { formatDateTime, roleLabel } from '../../shared/display-labels'
import PageHeader from '../../shared/PageHeader.vue'
import { useProjectContextStore } from '../../stores/project-context-store'
import { projectApi } from './project-api'
import type { Project, ProjectMember, ProjectRole } from './types'

type InvitableRole = Exclude<ProjectRole, 'OWNER'>

const route = useRoute()
const router = useRouter()
const projectContext = useProjectContextStore()
const projectId = route.params.projectId as string
const project = ref<Project | null>(null)
const members = ref<ProjectMember[]>([])
const loading = ref(false)
const creatingInvitation = ref(false)
const memberOperationId = ref('')
const invitationVisible = ref(false)
const invitationLink = ref('')
const invitationForm = reactive({
  role: 'MEMBER' as InvitableRole,
  invitedEmail: '',
  expiresInHours: 24,
})
const canInvite = computed(() => project.value?.role === 'OWNER' || project.value?.role === 'ADMIN')
const canManageMembers = computed(() => project.value?.role === 'OWNER')
const isOwner = computed(() => project.value?.role === 'OWNER')

async function load(): Promise<void> {
  loading.value = true
  try {
    const [projectResult, memberResult] = await Promise.all([
      projectApi.get(projectId),
      projectApi.listMembers(projectId),
    ])
    project.value = projectResult.data
    members.value = memberResult.data
  } catch (error) {
    showApiError(error, '项目成员加载')
  } finally {
    loading.value = false
  }
}

async function createInvitation(): Promise<void> {
  if (creatingInvitation.value) return
  creatingInvitation.value = true
  invitationLink.value = ''
  try {
    const result = await projectApi.createInvitation(projectId, {
      role: invitationForm.role,
      invitedEmail: invitationForm.invitedEmail.trim() || null,
      expiresInHours: invitationForm.expiresInHours,
    })
    invitationLink.value = `${window.location.origin}/invite/${result.data.code}`
    ElMessage.success('邀请已创建')
  } catch (error) {
    showApiError(error, '项目邀请创建')
  } finally {
    creatingInvitation.value = false
  }
}

async function copyInvitationLink(): Promise<void> {
  if (!invitationLink.value) return
  try {
    await navigator.clipboard.writeText(invitationLink.value)
    ElMessage.success('邀请链接已复制')
  } catch {
    ElMessage.error({ message: '邀请链接复制失败：请手动复制链接', grouping: true })
  }
}

function clearInvitationState(): void {
  invitationLink.value = ''
  invitationForm.invitedEmail = ''
}

async function changeRole(member: ProjectMember, role: InvitableRole): Promise<void> {
  if (role === member.role || memberOperationId.value) return
  try {
    await ElMessageBox.confirm(
      `确认将“${member.displayName}”的角色修改为${roleLabel(role)}吗？`,
      '修改成员角色',
      { confirmButtonText: '确认', cancelButtonText: '取消', type: 'warning' },
    )
    memberOperationId.value = member.userId
    await projectApi.changeMemberRole(projectId, member.userId, role)
    await load()
    ElMessage.success('成员角色已更新')
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    showApiError(error, '成员角色修改')
  } finally {
    memberOperationId.value = ''
  }
}

async function removeMember(member: ProjectMember): Promise<void> {
  if (memberOperationId.value) return
  try {
    await ElMessageBox.confirm(
      `确认移除成员”${member.displayName}”吗？`,
      '移除成员',
      { confirmButtonText: '确认移除', cancelButtonText: '取消', type: 'warning' },
    )
    memberOperationId.value = member.userId
    await projectApi.removeMember(projectId, member.userId)
    await load()
    ElMessage.success('成员已移除')
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    showApiError(error, '项目成员移除')
  } finally {
    memberOperationId.value = ''
  }
}

async function transferOwnership(member: ProjectMember): Promise<void> {
  if (memberOperationId.value) return
  try {
    await ElMessageBox.confirm(
      `确认将项目所有权转移给”${member.displayName}”吗？转移后您将变为普通成员。`,
      '转移所有权',
      { confirmButtonText: '确认转移', cancelButtonText: '取消', type: 'warning' },
    )
    memberOperationId.value = member.userId
    await projectApi.transferOwnership(projectId, member.userId)
    await load()
    await projectContext.refreshProject(projectId)
    ElMessage.success('所有权已转移')
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    showApiError(error, '项目所有权转移')
  } finally {
    memberOperationId.value = ''
  }
}

async function leaveProject(): Promise<void> {
  if (memberOperationId.value) return
  try {
    await ElMessageBox.confirm(
      '确认退出当前项目吗？退出后将无法访问该项目。',
      '退出项目',
      { confirmButtonText: '确认退出', cancelButtonText: '取消', type: 'warning' },
    )
    memberOperationId.value = 'self'
    await projectApi.leaveProject(projectId)
    ElMessage.success('已退出项目')
    router.push('/projects')
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    showApiError(error, '退出项目')
  } finally {
    memberOperationId.value = ''
  }
}

onMounted(load)
</script>

<template>
  <main class="workspace-page">
    <PageHeader
      eyebrow="团队协作"
      title="成员管理"
      :context="project?.name"
    >
      <template #actions>
        <el-button v-if="canInvite" type="primary" @click="invitationVisible = true">
          创建邀请
        </el-button>
        <el-button
          v-if="!isOwner"
          type="danger"
          plain
          :loading="memberOperationId === 'self'"
          :disabled="Boolean(memberOperationId)"
          @click="leaveProject"
        >
          退出项目
        </el-button>
      </template>
    </PageHeader>

    <el-table v-loading="loading" :data="members" empty-text="暂无成员">
      <el-table-column prop="username" label="用户名" min-width="150" />
      <el-table-column label="显示名称" min-width="190">
        <template #default="{ row }">
          <div class="member-identity">
            <span class="member-avatar" aria-hidden="true">{{ row.displayName.slice(0, 1) }}</span>
            <strong>{{ row.displayName }}</strong>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="角色" width="180">
        <template #default="{ row }">
          <el-select
            v-if="canManageMembers && row.role !== 'OWNER'"
            :model-value="row.role"
            :loading="memberOperationId === row.userId"
            :disabled="Boolean(memberOperationId)"
            aria-label="修改成员角色"
            @change="changeRole(row, $event)"
          >
            <el-option label="管理员" value="ADMIN" />
            <el-option label="成员" value="MEMBER" />
          </el-select>
          <el-tag v-else>{{ roleLabel(row.role) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="加入时间" min-width="190">
        <template #default="{ row }">{{ formatDateTime(row.joinedAt) }}</template>
      </el-table-column>
      <el-table-column v-if="canManageMembers" label="操作" width="200">
        <template #default="{ row }">
          <el-button
            v-if="row.role !== 'OWNER'"
            type="primary"
            text
            :loading="memberOperationId === row.userId"
            :disabled="Boolean(memberOperationId)"
            @click="transferOwnership(row)"
          >
            转移所有权
          </el-button>
          <el-button
            v-if="row.role !== 'OWNER'"
            type="danger"
            text
            :loading="memberOperationId === row.userId"
            :disabled="Boolean(memberOperationId)"
            @click="removeMember(row)"
          >
            移除成员
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog
      v-model="invitationVisible"
      title="创建邀请"
      width="520px"
      @closed="clearInvitationState"
    >
      <el-form label-position="top" @submit.prevent="createInvitation">
        <el-form-item label="邀请角色">
          <el-select v-model="invitationForm.role">
            <el-option label="管理员" value="ADMIN" />
            <el-option label="成员" value="MEMBER" />
          </el-select>
        </el-form-item>
        <el-form-item label="邀请邮箱（可空）">
          <el-input v-model="invitationForm.invitedEmail" type="email" :maxlength="120" />
        </el-form-item>
        <el-form-item label="有效时长（小时）">
          <el-input-number v-model="invitationForm.expiresInHours" :min="1" :max="168" />
        </el-form-item>
        <el-button
          type="primary"
          native-type="submit"
          :loading="creatingInvitation"
          :disabled="creatingInvitation"
        >
          创建邀请
        </el-button>
      </el-form>

      <section v-if="invitationLink" class="one-time-link" aria-live="polite">
        <el-alert
          title="邀请链接只展示一次，请妥善保存。页面刷新后无法恢复。"
          type="warning"
          :closable="false"
          show-icon
        />
        <el-form-item label="邀请链接">
          <el-input :model-value="invitationLink" readonly />
        </el-form-item>
        <el-button type="primary" plain @click="copyInvitationLink">复制链接</el-button>
      </section>
    </el-dialog>
  </main>
</template>
