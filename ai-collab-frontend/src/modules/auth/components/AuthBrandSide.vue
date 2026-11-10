<script setup lang="ts">
import AuthIllustration from './AuthIllustration.vue'

withDefaults(
  defineProps<{
    title?: string
    subtitle?: string
    showIllustration?: boolean
  }>(),
  {
    title: '人与 AI 协同，让项目从想法走向成果',
    subtitle: 'AI 驱动的项目协作与知识管理平台',
    showIllustration: true,
  },
)
</script>

<template>
  <aside class="auth-brand-side">
    <!-- 顶部品牌标识区 -->
    <div class="brand-header">
      <div class="brand-logo-badge">
        <svg class="brand-logo-icon" viewBox="0 0 32 32" fill="none" xmlns="http://www.w3.org/2000/svg">
          <defs>
            <linearGradient id="brandGrad" x1="0" y1="0" x2="32" y2="32" gradientUnits="userSpaceOnUse">
              <stop offset="0%" stop-color="#5865D8" />
              <stop offset="100%" stop-color="#3B82F6" />
            </linearGradient>
            <linearGradient id="sparkGrad" x1="0" y1="0" x2="1" y2="1">
              <stop offset="0%" stop-color="#38BDF8" />
              <stop offset="100%" stop-color="#FFFFFF" />
            </linearGradient>
          </defs>
          <rect width="32" height="32" rx="9" fill="url(#brandGrad)" />
          <!-- 协同网络节点与 AI 星芒 -->
          <circle cx="10" cy="11" r="2.8" fill="#FFFFFF" opacity="0.9" />
          <circle cx="22" cy="11" r="2.8" fill="#FFFFFF" opacity="0.9" />
          <circle cx="16" cy="22" r="3.2" fill="#FFFFFF" />
          <path d="M10 11L16 22L22 11" stroke="#FFFFFF" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" opacity="0.65" />
          <!-- 核心闪烁小星芒 -->
          <path d="M16 11L17.2 13.5L19.7 14.7L17.2 15.9L16 18.4L14.8 15.9L12.3 14.7L14.8 13.5Z" fill="url(#sparkGrad)" />
        </svg>
      </div>
      <div class="brand-titles">
        <div class="brand-name-row">
          <span class="brand-name">AI Collab</span>
        </div>
        <p class="brand-eyebrow">AI 驱动的项目协作与知识管理平台</p>
      </div>
    </div>

    <!-- 品牌主张大标题与说明 -->
    <div class="brand-statement">
      <h1 class="statement-title">{{ title }}</h1>
      <p class="statement-subtitle">{{ subtitle }}</p>
    </div>

    <!-- 核心协作插画展示区 -->
    <div v-if="showIllustration" class="illustration-container">
      <AuthIllustration />
    </div>

    <!-- 底部特性微胶囊标签栏 -->
    <div class="brand-features-row">
      <div class="feature-pill">
        <span class="feature-dot dot-collab"></span>
        <span>多人看板 & 甘特推进</span>
      </div>
      <div class="feature-pill">
        <span class="feature-dot dot-rag"></span>
        <span>知识库问答 & 引用溯源</span>
      </div>
      <div class="feature-pill">
        <span class="feature-dot dot-agent"></span>
        <span>AI 规划 & 写操作审批</span>
      </div>
    </div>
  </aside>
</template>

<style scoped>
.auth-brand-side {
  display: flex;
  flex-direction: column;
  justify-content: center;
  max-width: 580px;
  width: 100%;
}

/* 顶部 Logo 栏 */
.brand-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 20px;
}

.brand-logo-badge {
  width: 44px;
  height: 44px;
  border-radius: 12px;
  background: #ffffff;
  padding: 3px;
  box-shadow:
    0 4px 14px rgba(88, 101, 216, 0.2),
    0 1px 2px rgba(24, 32, 51, 0.06);
  display: grid;
  place-items: center;
  flex-shrink: 0;
}

.brand-logo-icon {
  width: 38px;
  height: 38px;
  display: block;
}

.brand-titles {
  display: flex;
  flex-direction: column;
}

.brand-name-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.brand-name {
  font-size: 20px;
  font-weight: 800;
  letter-spacing: -0.02em;
  color: #182033;
}

.brand-eyebrow {
  margin: 2px 0 0;
  font-size: 12px;
  color: #596279;
  letter-spacing: 0.02em;
}

/* 品牌文案区 */
.brand-statement {
  margin-bottom: 8px;
}

.statement-title {
  margin: 0 0 10px;
  font-size: clamp(24px, 2.6vw, 34px);
  font-weight: 800;
  line-height: 1.25;
  letter-spacing: -0.02em;
  color: #182033;
  background: linear-gradient(135deg, #182033 0%, #2e3856 60%, #4754c7 100%);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
}

.statement-subtitle {
  margin: 0;
  font-size: 15px;
  line-height: 1.5;
  color: #596279;
}

/* 插画容器 */
.illustration-container {
  margin: 12px 0 20px;
  width: 100%;
}

/* 底部特性微胶囊 */
.brand-features-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 4px;
}

.feature-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  background: rgba(255, 255, 255, 0.7);
  backdrop-filter: blur(8px);
  border: 1px solid rgba(226, 230, 239, 0.8);
  border-radius: 999px;
  font-size: 12px;
  font-weight: 500;
  color: #334155;
  box-shadow: 0 1px 2px rgba(24, 32, 51, 0.03);
}

.feature-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
}

.dot-collab {
  background: #10b981;
  box-shadow: 0 0 0 2px rgba(16, 185, 129, 0.2);
}

.dot-rag {
  background: #0284c7;
  box-shadow: 0 0 0 2px rgba(2, 132, 199, 0.2);
}

.dot-agent {
  background: #6366f1;
  box-shadow: 0 0 0 2px rgba(99, 102, 241, 0.2);
}

/* 响应式支持 */
@media (max-width: 1024px) {
  .statement-title {
    font-size: 25px;
  }
  .statement-subtitle {
    font-size: 14px;
  }
}

@media (max-width: 860px) {
  .brand-header {
    margin-bottom: 14px;
  }
  .brand-statement {
    margin-bottom: 12px;
  }
  .brand-features-row {
    justify-content: center;
    gap: 6px;
  }
  .feature-pill {
    padding: 5px 10px;
    font-size: 11px;
  }
}

@media (max-width: 480px) {
  .brand-header {
    margin-bottom: 8px;
    gap: 10px;
  }
  .brand-logo-badge {
    width: 36px;
    height: 36px;
    border-radius: 9px;
  }
  .brand-logo-icon {
    width: 30px;
    height: 30px;
  }
  .brand-name {
    font-size: 17px;
  }
  .brand-tag {
    font-size: 10px;
    padding: 0 6px;
  }
  .brand-eyebrow {
    font-size: 11px;
  }
  .brand-statement {
    margin-bottom: 4px;
  }
  .statement-title {
    font-size: 20px;
    margin-bottom: 4px;
    line-height: 1.3;
  }
  .statement-subtitle {
    font-size: 12.5px;
    line-height: 1.4;
  }
  .brand-features-row {
    display: none !important; /* 手机端彻底隐藏，释放空间给表单 */
  }
}
</style>
