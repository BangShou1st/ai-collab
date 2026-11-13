<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Close, Cpu, Delete, Edit, Plus, Refresh, Share, VideoPlay,
} from '@element-plus/icons-vue'
import { showApiError } from '../../api/api-result'
import { adminAiProviderApi, type AdminSharedAiProvider } from '../../api/admin-ai-provider-api'
import type { UserAiProviderPayload } from '../ai/user-ai-api'
import EmbeddingAdminContent from './components/EmbeddingAdminContent.vue'
import RerankerAdminContent from './components/RerankerAdminContent.vue'

const route = useRoute()
const router = useRouter()

const activeTab = ref<'shared' | 'embedding' | 'reranker'>('shared')
const loading = ref(false)
const providers = ref<AdminSharedAiProvider[]>([])

// 弹窗表单状态
const dialogVisible = ref(false)
const dialogMode = ref<'create' | 'edit'>('create')
const editingId = ref<string | null>(null)
const submitting = ref(false)
const testingId = ref<string | null>(null)

const form = reactive<UserAiProviderPayload>({
  name: '',
  providerType: 'OPENAI_COMPATIBLE',
  baseUrl: '',
  apiPath: '/v1/chat/completions',
  apiKey: '',
  modelName: '',
  enabled: true,
  temperature: 0.7,
  maxOutputTokens: 4096,
  capabilities: ['CHAT', 'STREAMING'],
})

watch(
  () => route.query.tab,
  (tab) => {
    if (tab === 'embedding') activeTab.value = 'embedding'
    else if (tab === 'reranker') activeTab.value = 'reranker'
    else activeTab.value = 'shared'
  },
  { immediate: true },
)

function onTabChange(tabName: string): void {
  router.replace({ query: { ...route.query, tab: tabName } })
}

async function loadProviders(): Promise<void> {
  loading.value = true
  try {
    const res = await adminAiProviderApi.list()
    providers.value = res.data
  } catch (err) {
    showApiError(err, 'AI 供应商列表加载')
  } finally {
    loading.value = false
  }
}

function openCreateDialog(): void {
  dialogMode.value = 'create'
  editingId.value = null
  Object.assign(form, {
    name: '',
    providerType: 'OPENAI_COMPATIBLE',
    baseUrl: 'https://api.deepseek.com',
    apiPath: '/v1/chat/completions',
    apiKey: '',
    modelName: 'deepseek-chat',
    enabled: true,
    temperature: 0.7,
    maxOutputTokens: 4096,
    capabilities: ['CHAT', 'STREAMING'],
  })
  dialogVisible.value = true
}

function openEditDialog(provider: AdminSharedAiProvider): void {
  dialogMode.value = 'edit'
  editingId.value = provider.id
  Object.assign(form, {
    name: provider.name,
    providerType: provider.providerType,
    baseUrl: provider.baseUrl,
    apiPath: provider.apiPath,
    apiKey: '', // 留空表示保持原有密钥
    modelName: provider.modelName,
    enabled: provider.enabled,
    temperature: provider.temperature,
    maxOutputTokens: provider.maxOutputTokens,
    capabilities: [...provider.capabilities],
  })
  dialogVisible.value = true
}

async function submitForm(): Promise<void> {
  if (!form.name || !form.baseUrl || !form.modelName) {
    ElMessage.warning('请填写供应商名称、Base URL 与模型名称')
    return
  }
  if (dialogMode.value === 'create' && !form.apiKey) {
    ElMessage.warning('新建供应商必须填写 API Key')
    return
  }
  submitting.value = true
  try {
    if (dialogMode.value === 'create') {
      await adminAiProviderApi.create({ ...form })
      ElMessage.success('AI 供应商已创建')
    } else if (editingId.value) {
      await adminAiProviderApi.update(editingId.value, { ...form })
      ElMessage.success('供应商配置已更新')
    }
    dialogVisible.value = false
    await loadProviders()
  } catch (err) {
    showApiError(err, '保存供应商配置')
  } finally {
    submitting.value = false
  }
}

async function testConnection(provider: AdminSharedAiProvider): Promise<void> {
  testingId.value = provider.id
  try {
    const res = await adminAiProviderApi.test(provider.id)
    ElMessage.success({
      message: `连接成功！模型响应：${res.data?.content || '通信正常'}`,
      duration: 3500,
    })
  } catch (err) {
    showApiError(err, `测试「${provider.name}」连接`)
  } finally {
    testingId.value = null
  }
}

async function publishShared(provider: AdminSharedAiProvider): Promise<void> {
  try {
    await adminAiProviderApi.publish(provider.id)
    ElMessage.success(`供应商「${provider.name}」已发布为全站共享`)
    await loadProviders()
  } catch (err) {
    showApiError(err, '发布全站共享')
  }
}

async function revokeShared(provider: AdminSharedAiProvider): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确认撤销供应商「${provider.name}」的全站共享吗？普通用户将无法继续以此共享模型发起新请求。`,
      '撤销全站共享',
      { confirmButtonText: '确认撤销', cancelButtonText: '取消', type: 'warning' },
    )
    await adminAiProviderApi.revoke(provider.id)
    ElMessage.success(`供应商「${provider.name}」的全站共享已撤销`)
    await loadProviders()
  } catch (err) {
    if (err === 'cancel' || err === 'close') return
    showApiError(err, '撤销全站共享')
  }
}

async function deleteProvider(provider: AdminSharedAiProvider): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确认删除供应商「${provider.name}」吗？若已发布全站共享将同步撤销。`,
      '删除供应商',
      { confirmButtonText: '确认删除', cancelButtonText: '取消', type: 'warning' },
    )
    await adminAiProviderApi.delete(provider.id)
    ElMessage.success('供应商已删除')
    await loadProviders()
  } catch (err) {
    if (err === 'cancel' || err === 'close') return
    showApiError(err, '删除供应商')
  }
}

onMounted(loadProviders)
</script>

<template>
  <div class="providers-container">
    <header class="page-header">
      <div>
        <h1 class="page-title">AI 供应商</h1>
        <p class="page-desc">添加、验证及发布全站共享的大模型，统一管理系统基础设施</p>
      </div>
      <div v-if="activeTab === 'shared'" class="header-actions">
        <el-button :icon="Refresh" @click="loadProviders">刷新</el-button>
        <el-button type="primary" :icon="Plus" @click="openCreateDialog">添加供应商</el-button>
      </div>
    </header>

    <!-- Tab 切换 -->
    <el-tabs v-model="activeTab" class="admin-tabs" @tab-change="onTabChange">
      <el-tab-pane label="全站 AI 供应商" name="shared">
        <div v-loading="loading" class="provider-list-area">
          <div v-if="providers.length === 0" class="empty-box">
            <el-icon class="empty-icon"><Cpu /></el-icon>
            <p>暂未添加任何 AI 供应商</p>
            <el-button type="primary" :icon="Plus" @click="openCreateDialog">添加首个供应商</el-button>
          </div>

          <div v-else class="provider-grid">
            <div v-for="item in providers" :key="item.id" class="provider-card" :class="{ 'is-shared': item.isShared }">
              <div class="card-main">
                <div class="card-top">
                  <div class="title-group">
                    <strong class="provider-title">{{ item.name }}</strong>
                    <el-tag size="small" effect="plain">{{ item.providerType }}</el-tag>
                    <el-tag v-if="item.isShared" type="success" size="small" effect="dark">全站共享</el-tag>
                    <el-tag v-else-if="item.sharedStatus === 'REVOKED'" type="info" size="small">已撤销共享</el-tag>
                    <el-tag v-else type="warning" size="small">仅私有</el-tag>
                  </div>
                  <el-tag :type="item.enabled ? 'success' : 'info'" size="small">
                    {{ item.enabled ? '已启用' : '已停用' }}
                  </el-tag>
                </div>

                <div class="meta-rows">
                  <div class="meta-row">
                    <span class="meta-label">模型名称</span>
                    <code class="meta-code">{{ item.modelName }}</code>
                  </div>
                  <div class="meta-row">
                    <span class="meta-label">端点地址</span>
                    <span class="meta-text text-truncate" :title="item.baseUrl">{{ item.baseUrl }}</span>
                  </div>
                  <div class="meta-row">
                    <span class="meta-label">能力声明</span>
                    <div class="cap-tags">
                      <el-tag v-for="cap in item.capabilities" :key="cap" size="small" type="info" effect="plain">
                        {{ cap === 'NATIVE_TOOLS' ? '工具调用' : (cap === 'STREAMING' ? '流式' : '对话') }}
                      </el-tag>
                    </div>
                  </div>
                </div>
              </div>

              <!-- 操作按钮栏 -->
              <div class="card-actions">
                <el-button
                  size="small"
                  :icon="VideoPlay"
                  :loading="testingId === item.id"
                  @click="testConnection(item)"
                >
                  测试连接
                </el-button>

                <el-button
                  v-if="!item.isShared"
                  size="small"
                  type="success"
                  plain
                  :icon="Share"
                  @click="publishShared(item)"
                >
                  发布为全站共享
                </el-button>
                <el-button
                  v-else
                  size="small"
                  type="warning"
                  plain
                  :icon="Close"
                  @click="revokeShared(item)"
                >
                  撤销共享
                </el-button>

                <el-button size="small" :icon="Edit" @click="openEditDialog(item)">编辑</el-button>
                <el-button size="small" type="danger" text :icon="Delete" @click="deleteProvider(item)">删除</el-button>
              </div>
            </div>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="系统 Embedding 与索引" name="embedding">
        <EmbeddingAdminContent />
      </el-tab-pane>

      <el-tab-pane label="Reranker 管理" name="reranker">
        <RerankerAdminContent />
      </el-tab-pane>
    </el-tabs>

    <!-- 添加 / 编辑配置弹窗 -->
    <el-dialog
      v-model="dialogVisible"
      :title="dialogMode === 'create' ? '添加 AI 供应商' : '编辑供应商配置'"
      width="540px"
      destroy-on-close
    >
      <el-form label-position="top">
        <el-form-item label="供应商显示名称" required>
          <el-input v-model="form.name" placeholder="如 DeepSeek V3 官方" />
        </el-form-item>

        <el-form-item label="供应商协议类型" required>
          <el-select v-model="form.providerType" style="width: 100%">
            <el-option label="OpenAI 兼容协议（DeepSeek, Moonshot, Qwen 等）" value="OPENAI_COMPATIBLE" />
            <el-option label="Anthropic Claude" value="ANTHROPIC" />
            <el-option label="Google Gemini" value="GEMINI" />
          </el-select>
        </el-form-item>

        <el-form-item label="Base URL（必须为公网 HTTPS）" required>
          <el-input v-model="form.baseUrl" placeholder="https://api.deepseek.com" />
        </el-form-item>

        <el-form-item label="API Path">
          <el-input v-model="form.apiPath" placeholder="/v1/chat/completions" />
        </el-form-item>

        <el-form-item :label="dialogMode === 'create' ? 'API Key（密钥加密存储）' : '更新 API Key（留空保持不变）'" :required="dialogMode === 'create'">
          <el-input v-model="form.apiKey" type="password" show-password placeholder="sk-..." />
        </el-form-item>

        <el-form-item label="模型名称（Model ID）" required>
          <el-input v-model="form.modelName" placeholder="如 deepseek-chat 或 gpt-4o" />
        </el-form-item>

        <el-form-item label="支持的能力">
          <el-checkbox-group v-model="form.capabilities">
            <el-checkbox label="CHAT">基础对话 (CHAT)</el-checkbox>
            <el-checkbox label="STREAMING">流式输出 (STREAMING)</el-checkbox>
            <el-checkbox label="NATIVE_TOOLS">工具调用 (NATIVE_TOOLS)</el-checkbox>
          </el-checkbox-group>
        </el-form-item>

        <el-form-item label="启用状态">
          <el-switch v-model="form.enabled" active-text="启用" inactive-text="停用" />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitForm">确认保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.providers-container {
  max-width: 1200px;
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

.provider-list-area {
  padding-top: 12px;
}
.empty-box {
  background: #ffffff;
  border: 1px dashed #d1d5db;
  border-radius: 12px;
  padding: 48px 24px;
  text-align: center;
  color: #6b7280;
}
.empty-icon {
  font-size: 40px;
  color: #9ca3af;
  margin-bottom: 12px;
}

.provider-grid {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.provider-card {
  background: #ffffff;
  border: 1px solid var(--el-border-color-lighter, #e5e7eb);
  border-radius: 12px;
  overflow: hidden;
  transition: all 0.2s ease;
}
.provider-card.is-shared {
  border-left: 4px solid #16a34a;
}
.card-main {
  padding: 18px 20px 14px;
}
.card-top {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 14px;
}
.title-group {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.provider-title {
  font-size: 16px;
  font-weight: 600;
  color: #111827;
}

.meta-rows {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 14px;
}
.meta-row {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.meta-label {
  font-size: 11px;
  color: #6b7280;
  font-weight: 500;
}
.meta-code {
  font-family: monospace;
  font-size: 13px;
  color: #1f2937;
  background: #f3f4f6;
  padding: 2px 6px;
  border-radius: 4px;
  width: fit-content;
}
.meta-text {
  font-size: 13px;
  color: #374151;
}
.text-truncate {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.cap-tags {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.card-actions {
  background: #f9fafb;
  border-top: 1px solid #f3f4f6;
  padding: 10px 20px;
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  flex-wrap: wrap;
}

@media (max-width: 768px) {
  .meta-rows {
    grid-template-columns: 1fr;
    gap: 8px;
  }
  .card-actions {
    justify-content: flex-start;
  }
}
</style>
