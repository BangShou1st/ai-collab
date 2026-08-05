<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { normalizeApiError, showApiError } from '../../api/api-result'
import { formatDateTime, roleLabel } from '../../shared/display-labels'
import { resolveSafeRedirect } from '../../shared/safe-redirect'
import { useAuthStore } from '../../stores/auth-store'
import { projectApi } from './project-api'
import type { InvitationPreview, Project } from './types'

type InvitationPageState =
  | 'authInitializing'
  | 'previewLoading'
  | 'previewFailed'
  | 'unauthenticated'
  | 'authenticatedLoadingMembership'
  | 'authenticatedAlreadyMember'
  | 'authenticatedCanAccept'
  | 'authenticatedEmailMismatch'
  | 'acceptingCurrentUser'
  | 'registeringNewUser'
  | 'accepted'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const code = typeof route.params.code === 'string' ? route.params.code : ''
const preview = ref<InvitationPreview | null>(null)
const projects = ref<Project[]>([])
const previewLoading = ref(true)
const membershipLoading = ref(false)
const acceptingCurrentUser = ref(false)
const registeringNewUser = ref(false)
const switchingAccount = ref(false)
const navigatingToLogin = ref(false)
const enteringProject = ref(false)
const accepted = ref(false)
const registrationMode = ref(false)
const previewErrorCode = ref('')
const form = reactive({
  username: '',
  displayName: '',
  email: '',
  password: '',
  confirmPassword: '',
})

const existingProject = computed(() =>
  preview.value
    ? projects.value.find(project => project.id === preview.value?.projectId) ?? null
    : null,
)
const emailMismatch = computed(() => {
  const invitedEmail = preview.value?.invitedEmail?.trim().toLocaleLowerCase()
  if (!invitedEmail) return false
  const currentEmail = auth.currentUser?.email?.trim().toLocaleLowerCase()
  return !currentEmail || currentEmail !== invitedEmail
})
const validationMessage = computed(() => {
  if (!form.username && !form.displayName && !form.password && !form.confirmPassword) return ''
  if (!/^[A-Za-z0-9_]{3,40}$/.test(form.username)) return '用户名需为 3 至 40 位字母、数字或下划线'
  if (!form.displayName.trim()) return '显示名称不能为空'
  if (form.displayName.length > 60) return '显示名称不能超过 60 个字符'
  if (form.email.length > 120) return '邮箱不能超过 120 个字符'
  if (form.password.length < 8) return '密码至少需要 8 个字符'
  if (form.password.length > 72) return '密码不能超过 72 个字符'
  if (form.password !== form.confirmPassword) return '两次输入的密码不一致'
  return ''
})
const canRegister = computed(() =>
  Boolean(
    preview.value
    && form.username
    && form.displayName
    && form.password
    && form.confirmPassword
    && !validationMessage.value,
  ),
)
const pageState = computed<InvitationPageState>(() => {
  if (!auth.initialized) return 'authInitializing'
  if (previewLoading.value) return 'previewLoading'
  if (!preview.value) return 'previewFailed'
  if (!auth.isAuthenticated) {
    return registeringNewUser.value ? 'registeringNewUser' : 'unauthenticated'
  }
  if (membershipLoading.value) return 'authenticatedLoadingMembership'
  if (existingProject.value) return 'authenticatedAlreadyMember'
  if (emailMismatch.value) return 'authenticatedEmailMismatch'
  if (acceptingCurrentUser.value) return 'acceptingCurrentUser'
  if (accepted.value) return 'accepted'
  return 'authenticatedCanAccept'
})
const failureTitle = computed(() => {
  if (previewErrorCode.value === 'INVITATION_EXPIRED') return '邀请已过期'
  if (previewErrorCode.value === 'INVITATION_ALREADY_USED') return '邀请已被使用'
  if (previewErrorCode.value === 'INVITATION_INVALID') return '邀请无效'
  return '暂时无法读取邀请'
})

function clearPasswordFields(): void {
  form.password = ''
  form.confirmPassword = ''
}

async function loadPreview(): Promise<void> {
  previewLoading.value = true
  previewErrorCode.value = ''
  try {
    preview.value = (await projectApi.previewInvitation(code)).data
    form.email = preview.value.invitedEmail ?? ''
  } catch (error) {
    const safeError = normalizeApiError(error)
    preview.value = null
    previewErrorCode.value = safeError.code
    showApiError(error, '邀请信息加载')
  } finally {
    previewLoading.value = false
  }
}

async function loadMembership(): Promise<void> {
  if (!auth.isAuthenticated || !preview.value) return
  membershipLoading.value = true
  try {
    projects.value = (await projectApi.list()).data
  } catch (error) {
    showApiError(error, '项目成员状态加载')
  } finally {
    membershipLoading.value = false
  }
}

onMounted(async () => {
  const previewPromise = loadPreview()
  if (!auth.initialized) {
    await auth.initialize()
  }
  await previewPromise
  if (auth.isAuthenticated && preview.value) {
    await loadMembership()
  }
})

onBeforeUnmount(clearPasswordFields)

async function openLogin(): Promise<void> {
  if (navigatingToLogin.value) return
  navigatingToLogin.value = true
  const redirect = resolveSafeRedirect(router, route.fullPath)
  try {
    await router.push({ path: '/login', query: { redirect } })
  } finally {
    navigatingToLogin.value = false
  }
}

async function switchAccount(): Promise<void> {
  if (switchingAccount.value) return
  switchingAccount.value = true
  const redirect = resolveSafeRedirect(router, route.fullPath)
  try {
    await auth.logout()
  } catch (error) {
    showApiError(error, '账号切换')
    auth.clearAuth()
    auth.initialized = true
  } finally {
    auth.clearAuth()
    auth.initialized = true
    switchingAccount.value = false
    await router.replace({ path: '/login', query: { redirect } })
  }
}

async function enterProject(projectId: string): Promise<void> {
  if (enteringProject.value) return
  enteringProject.value = true
  try {
    await router.push(`/projects/${projectId}/board`)
  } finally {
    enteringProject.value = false
  }
}

async function acceptAsCurrentUser(): Promise<void> {
  if (!preview.value || acceptingCurrentUser.value || emailMismatch.value) return
  acceptingCurrentUser.value = true
  try {
    const result = (await projectApi.acceptInvitationAsCurrentUser(code)).data
    projects.value = (await projectApi.list()).data
    if (!projects.value.some(project => project.id === result.projectId)) {
      throw new Error('accepted project is missing from refreshed project list')
    }
    accepted.value = true
    ElMessage.success(result.alreadyMember ? '你已经是该项目成员' : '已加入项目')
    await router.replace(`/projects/${result.projectId}/board`)
  } catch (error) {
    const safeError = normalizeApiError(error)
    if (safeError.httpStatus === 401) {
      showApiError(error, '邀请接受')
      auth.clearAuth()
      await router.replace({
        path: '/login',
        query: { redirect: resolveSafeRedirect(router, route.fullPath) },
      })
      return
    }
    if (safeError.code === 'INVITATION_EMAIL_MISMATCH') {
      showApiError(error, '邀请接受')
      return
    }
    if (
      safeError.code === 'INVITATION_INVALID'
      || safeError.code === 'INVITATION_EXPIRED'
      || safeError.code === 'INVITATION_ALREADY_USED'
    ) {
      previewErrorCode.value = safeError.code
      preview.value = null
    }
    showApiError(error, '邀请接受', '加入项目后未能刷新项目列表')
  } finally {
    acceptingCurrentUser.value = false
  }
}

async function acceptInvitationAndRegister(): Promise<void> {
  if (!preview.value || !canRegister.value || registeringNewUser.value) return
  registeringNewUser.value = true
  try {
    const result = await projectApi.acceptInvitationAndRegister(code, {
      username: form.username,
      displayName: form.displayName.trim(),
      email: form.email.trim() || null,
      password: form.password,
    })
    auth.establishSession(result.data)
    clearPasswordFields()
    projects.value = (await projectApi.list()).data
    if (!projects.value.some(project => project.id === preview.value?.projectId)) {
      throw new Error('registered project is missing from refreshed project list')
    }
    accepted.value = true
    ElMessage.success('账号已创建并加入项目')
    await router.replace(`/projects/${preview.value.projectId}/board`)
  } catch (error) {
    showApiError(error, '邀请注册与加入')
    clearPasswordFields()
  } finally {
    registeringNewUser.value = false
  }
}
</script>

<template>
  <main class="auth-page">
    <el-card class="auth-card wide invitation-card" shadow="always">
      <template #header>
        <p class="eyebrow">项目邀请</p>
        <h1>加入竞赛项目</h1>
        <p class="subtitle">确认项目与当前账号信息后选择加入方式</p>
      </template>

      <el-result
        v-if="pageState === 'authInitializing'"
        icon="info"
        title="正在检查登录状态……"
        sub-title="请稍候，页面会根据当前账号显示正确的加入方式。"
      />
      <el-result
        v-else-if="pageState === 'previewLoading'"
        icon="info"
        title="正在读取邀请信息……"
      />
      <el-result
        v-else-if="pageState === 'previewFailed'"
        icon="warning"
        :title="failureTitle"
        sub-title="请联系项目管理员重新确认邀请链接。"
      />

      <template v-else-if="preview">
        <el-descriptions :column="1" border class="invitation-summary">
          <el-descriptions-item label="项目名称">{{ preview.projectName }}</el-descriptions-item>
          <el-descriptions-item label="邀请角色">{{ roleLabel(preview.role) }}</el-descriptions-item>
          <el-descriptions-item label="邀请邮箱">{{ preview.invitedEmail || '未限定' }}</el-descriptions-item>
          <el-descriptions-item label="有效期至">{{ formatDateTime(preview.expiresAt) }}</el-descriptions-item>
        </el-descriptions>

        <template v-if="pageState === 'unauthenticated' || pageState === 'registeringNewUser'">
          <section class="invitation-choice">
            <h2>选择加入方式</h2>
            <p class="readonly-note">已有账号无需重新注册，登录后返回本页确认加入。</p>
            <div class="invitation-actions">
              <el-button
                type="primary"
                plain
                :loading="navigatingToLogin"
                :disabled="navigatingToLogin || registeringNewUser"
                @click="openLogin"
              >
                已有账号登录
              </el-button>
              <el-button
                type="primary"
                :disabled="registeringNewUser"
                @click="registrationMode = true"
              >
                创建账号并加入
              </el-button>
            </div>
          </section>

          <el-form
            v-if="registrationMode"
            class="invitation-registration"
            label-position="top"
            @submit.prevent="acceptInvitationAndRegister"
          >
            <h2>创建账号并加入</h2>
            <el-form-item label="用户名">
              <el-input v-model="form.username" autocomplete="username" :maxlength="40" />
            </el-form-item>
            <el-form-item label="显示名称">
              <el-input v-model="form.displayName" autocomplete="name" :maxlength="60" />
            </el-form-item>
            <el-form-item label="邮箱">
              <el-input v-model="form.email" type="email" autocomplete="email" :maxlength="120" />
            </el-form-item>
            <el-form-item label="密码">
              <el-input
                v-model="form.password"
                type="password"
                autocomplete="new-password"
                :maxlength="72"
                show-password
              />
            </el-form-item>
            <el-form-item label="确认密码">
              <el-input
                v-model="form.confirmPassword"
                type="password"
                autocomplete="new-password"
                :maxlength="72"
                show-password
              />
            </el-form-item>
            <el-alert
              v-if="validationMessage"
              :title="validationMessage"
              type="warning"
              :closable="false"
            />
            <el-button
              class="submit-button"
              type="primary"
              native-type="submit"
              :loading="registeringNewUser"
              :disabled="!canRegister || registeringNewUser"
            >
              创建账号并加入
            </el-button>
          </el-form>
        </template>

        <el-result
          v-else-if="pageState === 'authenticatedLoadingMembership'"
          icon="info"
          title="正在检查项目成员身份……"
        />

        <template v-else-if="pageState === 'authenticatedAlreadyMember' && existingProject">
          <section class="account-summary">
            <p class="eyebrow">当前账号</p>
            <h2>{{ auth.currentUser?.displayName }}</h2>
            <p>{{ auth.currentUser?.username }} · {{ auth.currentUser?.email || '未设置邮箱' }}</p>
          </section>
          <el-result
            icon="success"
            title="你已经是该项目成员"
            :sub-title="`当前角色：${roleLabel(existingProject.role)}。邀请不会改变你的现有角色。`"
          >
            <template #extra>
              <el-button
                type="primary"
                :loading="enteringProject"
                :disabled="enteringProject"
                @click="enterProject(preview.projectId)"
              >
                进入项目
              </el-button>
            </template>
          </el-result>
        </template>

        <template v-else-if="pageState === 'authenticatedEmailMismatch'">
          <section class="account-summary">
            <p class="eyebrow">当前账号</p>
            <h2>{{ auth.currentUser?.displayName }}</h2>
            <p>用户名：{{ auth.currentUser?.username }}</p>
            <p>当前邮箱：{{ auth.currentUser?.email || '未设置邮箱' }}</p>
            <p>邀请邮箱：{{ preview.invitedEmail }}</p>
          </section>
          <el-result
            icon="warning"
            title="当前账号与邀请邮箱不匹配"
            sub-title="请切换到邀请邮箱对应的账号后再接受。"
          >
            <template #extra>
              <el-button
                type="primary"
                plain
                :loading="switchingAccount"
                :disabled="switchingAccount"
                @click="switchAccount"
              >
                切换账号
              </el-button>
            </template>
          </el-result>
        </template>

        <template v-else>
          <section class="account-summary">
            <p class="eyebrow">当前账号</p>
            <h2>{{ auth.currentUser?.displayName }}</h2>
            <p>用户名：{{ auth.currentUser?.username }}</p>
            <p>邮箱：{{ auth.currentUser?.email || '未设置邮箱' }}</p>
          </section>
          <el-button
            class="submit-button"
            type="primary"
            :loading="acceptingCurrentUser"
            :disabled="acceptingCurrentUser || pageState === 'accepted'"
            @click="acceptAsCurrentUser"
          >
            接受邀请并进入项目
          </el-button>
        </template>
      </template>
    </el-card>
  </main>
</template>
