<script setup lang="ts">
import AuthBrandSide from './AuthBrandSide.vue'

withDefaults(
  defineProps<{
    brandTitle?: string
    brandSubtitle?: string
    showIllustration?: boolean
  }>(),
  {
    brandTitle: '人与 AI 协同，让项目从想法走向成果',
    brandSubtitle: '面向高校竞赛团队与项目组的一站式智能协作平台',
    showIllustration: true,
  },
)
</script>

<template>
  <main class="auth-layout-root">
    <!-- 背景装饰与环境光晕 -->
    <div class="ambient-glow glow-top-left" aria-hidden="true"></div>
    <div class="ambient-glow glow-bottom-right" aria-hidden="true"></div>
    <div class="ambient-grid-pattern" aria-hidden="true"></div>

    <div class="auth-container">
      <div class="auth-split-layout">
        <!-- 左侧品牌与视觉展示区 -->
        <section class="auth-brand-section">
          <slot name="brand">
            <AuthBrandSide
              :title="brandTitle"
              :subtitle="brandSubtitle"
              :show-illustration="showIllustration"
            />
          </slot>
        </section>

        <!-- 右侧核心认证表单操作区 -->
        <section class="auth-form-section">
          <slot />
        </section>
      </div>
    </div>
  </main>
</template>

<style scoped>
.auth-layout-root {
  position: relative;
  min-height: 100vh;
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px 24px;
  background: linear-gradient(135deg, #f8faff 0%, #edf2fc 100%);
  overflow-x: hidden;
  box-sizing: border-box;
}

/* 柔和弥散光斑 */
.ambient-glow {
  position: fixed;
  border-radius: 50%;
  pointer-events: none;
  z-index: 0;
  filter: blur(80px);
  opacity: 0.85;
}

.glow-top-left {
  top: -100px;
  left: -80px;
  width: 520px;
  height: 520px;
  background: radial-gradient(circle, rgba(99, 102, 241, 0.16) 0%, rgba(99, 102, 241, 0.02) 65%, transparent 70%);
}

.glow-bottom-right {
  bottom: -120px;
  right: -80px;
  width: 560px;
  height: 560px;
  background: radial-gradient(circle, rgba(56, 189, 248, 0.15) 0%, rgba(88, 101, 216, 0.03) 65%, transparent 70%);
}

/* 细腻技术网格纹理 */
.ambient-grid-pattern {
  position: fixed;
  inset: 0;
  pointer-events: none;
  z-index: 0;
  background-image:
    linear-gradient(rgba(88, 101, 216, 0.04) 1px, transparent 1px),
    linear-gradient(90deg, rgba(88, 101, 216, 0.04) 1px, transparent 1px);
  background-size: 36px 36px;
  mask-image: radial-gradient(circle at 50% 50%, #000 60%, transparent 95%);
  -webkit-mask-image: radial-gradient(circle at 50% 50%, #000 60%, transparent 95%);
}

/* 响应式主容器 */
.auth-container {
  position: relative;
  z-index: 1;
  width: 100%;
  max-width: 1140px;
  margin: 0 auto;
}

/* 左右分栏布局 */
.auth-split-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.28fr) minmax(380px, 0.95fr);
  gap: 48px;
  align-items: center;
  justify-content: center;
}

.auth-brand-section {
  display: flex;
  justify-content: center;
}

.auth-form-section {
  display: flex;
  justify-content: center;
  width: 100%;
}

/* 响应式断点适配 */
@media (max-width: 1024px) {
  .auth-layout-root {
    padding: 32px 20px;
  }
  .auth-split-layout {
    grid-template-columns: minmax(0, 1.15fr) minmax(360px, 1fr);
    gap: 32px;
  }
}

@media (max-width: 860px) {
  .auth-layout-root {
    padding: 28px 16px;
    align-items: flex-start;
  }
  .auth-split-layout {
    grid-template-columns: 1fr;
    gap: 28px;
    max-width: 480px;
    margin: 0 auto;
  }
  .auth-brand-section {
    text-align: center;
  }
  :deep(.brand-header) {
    justify-content: center;
  }
  :deep(.brand-features-row) {
    justify-content: center;
  }
  :deep(.illustration-container) {
    display: none; /* 平板与窄屏隐藏大型插画，聚焦核心认证体验 */
  }
}

@media (max-width: 480px) {
  .auth-layout-root {
    padding: 20px 14px;
  }
  .auth-split-layout {
    gap: 20px;
    max-width: 100%;
  }
  :deep(.statement-title) {
    font-size: 22px;
    margin-bottom: 6px;
  }
  :deep(.statement-subtitle) {
    font-size: 13px;
  }
  :deep(.brand-features-row) {
    display: none; /* 手机端隐藏底部胶囊，压缩高度 */
  }
}
</style>
