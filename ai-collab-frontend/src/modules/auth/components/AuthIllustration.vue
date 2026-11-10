<script setup lang="ts">
// 精致团队协作与 AI 助手矢量插画组件
</script>

<template>
  <div class="auth-illustration-wrap" aria-hidden="true">
    <!-- SVG 核心插画：团队协作与 AI 助手 -->
    <svg
      class="auth-illustration-svg"
      viewBox="0 0 540 360"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
    >
      <defs>
        <!-- 基础渐变 -->
        <linearGradient id="illDeskGrad" x1="0" y1="0" x2="540" y2="0" gradientUnits="userSpaceOnUse">
          <stop offset="0%" stop-color="#E2E8F0" stop-opacity="0.2" />
          <stop offset="50%" stop-color="#CBD5E1" stop-opacity="0.8" />
          <stop offset="100%" stop-color="#E2E8F0" stop-opacity="0.2" />
        </linearGradient>
        <linearGradient id="illAiGlow" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0%" stop-color="#6366F1" />
          <stop offset="100%" stop-color="#3B82F6" />
        </linearGradient>
        <linearGradient id="illAiRing" x1="0" y1="0" x2="1" y2="0">
          <stop offset="0%" stop-color="#38BDF8" />
          <stop offset="50%" stop-color="#818CF8" />
          <stop offset="100%" stop-color="#C084FC" />
        </linearGradient>
        <linearGradient id="illBoardBg" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stop-color="#FFFFFF" />
          <stop offset="100%" stop-color="#F8FAFC" />
        </linearGradient>
        <linearGradient id="illChairGrad" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stop-color="#64748B" />
          <stop offset="100%" stop-color="#475569" />
        </linearGradient>
        <!-- 柔和阴影滤镜 -->
        <filter id="illShadow" x="-20%" y="-20%" width="140%" height="140%" filterUnits="userSpaceOnUse">
          <feDropShadow dx="0" dy="8" stdDeviation="12" flood-color="#1E293B" flood-opacity="0.08" />
        </filter>
        <filter id="illGlow" x="-30%" y="-30%" width="160%" height="160%">
          <feGaussianBlur stdDeviation="6" result="blur" />
          <feComposite in="SourceGraphic" in2="blur" operator="over" />
        </filter>
      </defs>

      <!-- 地面光环与基座投影 -->
      <ellipse cx="270" cy="326" rx="230" ry="18" fill="url(#illDeskGrad)" />
      <ellipse cx="270" cy="326" rx="140" ry="8" fill="#94A3B8" opacity="0.3" />

      <!-- 背景白板/交互协作大屏 -->
      <g filter="url(#illShadow)" class="board-group">
        <rect x="70" y="52" width="220" height="150" rx="14" fill="url(#illBoardBg)" stroke="#E2E8F0" stroke-width="2" />
        <!-- 白板顶部工具条 -->
        <rect x="70" y="52" width="220" height="24" rx="14" fill="#F1F5F9" />
        <circle cx="86" cy="64" r="4" fill="#EF4444" opacity="0.8" />
        <circle cx="98" cy="64" r="4" fill="#F59E0B" opacity="0.8" />
        <circle cx="110" cy="64" r="4" fill="#10B981" opacity="0.8" />
        <rect x="130" y="60" width="80" height="8" rx="4" fill="#CBD5E1" opacity="0.6" />

        <!-- 白板内的甘特与路线构架图草图 -->
        <!-- 列 1 -->
        <rect x="88" y="90" width="48" height="18" rx="4" fill="#EEF2FF" stroke="#C7D2FE" />
        <rect x="94" y="95" width="28" height="4" rx="2" fill="#6366F1" />
        <rect x="94" y="101" width="18" height="3" rx="1.5" fill="#94A3B8" />

        <!-- 列 2 连接箭头 -->
        <path d="M138 99 H152" stroke="#94A3B8" stroke-dasharray="2 2" stroke-width="1.5" />
        <rect x="154" y="86" width="60" height="24" rx="4" fill="#ECFDF5" stroke="#A7F3D0" />
        <rect x="160" y="92" width="36" height="4" rx="2" fill="#10B981" />
        <rect x="160" y="99" width="44" height="4" rx="2" fill="#34D399" />

        <!-- 列 3 下层任务 -->
        <path d="M184 112 V128 H120" stroke="#94A3B8" stroke-dasharray="2 2" stroke-width="1.5" />
        <rect x="88" y="124" width="70" height="20" rx="4" fill="#F0F9FF" stroke="#BAE6FD" />
        <rect x="94" y="130" width="42" height="4" rx="2" fill="#0284C7" />
        <rect x="94" y="136" width="24" height="3" rx="1.5" fill="#94A3B8" />

        <!-- 里程碑标记 -->
        <path d="M246 110 L256 122 L246 134 L236 122 Z" fill="#F59E0B" opacity="0.9" />
        <circle cx="246" cy="122" r="3" fill="#FFFFFF" />

        <!-- 白板支架 -->
        <line x1="120" y1="202" x2="105" y2="320" stroke="#94A3B8" stroke-width="4" stroke-linecap="round" />
        <line x1="240" y1="202" x2="255" y2="320" stroke="#94A3B8" stroke-width="4" stroke-linecap="round" />
      </g>

      <!-- 角色 1：站在白板前的项目规划者（女） -->
      <g class="character-planner" filter="url(#illShadow)">
        <!-- 腿部与鞋子 -->
        <path d="M196 248 L194 316 L184 318 L184 324 L204 324 L204 248 Z" fill="#1E293B" />
        <path d="M214 248 L218 316 L226 318 L226 324 L208 324 L206 248 Z" fill="#334155" />

        <!-- 躯干（米白外套与浅蓝内衬） -->
        <path d="M188 176 Q178 200 182 248 L224 248 Q228 200 218 176 Z" fill="#F8FAFC" stroke="#E2E8F0" stroke-width="1.5" />
        <path d="M198 178 L203 216 L208 178 Z" fill="#6366F1" />

        <!-- 左手臂与指向白板的手势（持触控笔） -->
        <path d="M184 184 L162 152 L144 142" stroke="#F8FAFC" stroke-width="10" stroke-linecap="round" stroke-linejoin="round" />
        <path d="M184 184 L162 152 L144 142" stroke="#E2E8F0" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
        <!-- 触控手与手写笔 -->
        <circle cx="143" cy="141" r="5" fill="#FBCFE8" />
        <line x1="140" y1="145" x2="130" y2="134" stroke="#6366F1" stroke-width="2.5" stroke-linecap="round" />

        <!-- 右手臂（自然微屈） -->
        <path d="M220 184 Q228 206 222 226" stroke="#F8FAFC" stroke-width="9" stroke-linecap="round" />

        <!-- 头部与面部 -->
        <ellipse cx="203" cy="150" rx="13" ry="15" fill="#FBCFE8" />
        <!-- 侧脸五官（专注微笑） -->
        <path d="M194 150 Q196 153 194 155" stroke="#9D174D" stroke-width="1.2" stroke-linecap="round" fill="none" />
        <circle cx="197" cy="148" r="1.5" fill="#0F172A" />

        <!-- 活力发型（深褐发马尾） -->
        <path d="M192 144 C192 134 212 132 216 142 C220 152 216 162 214 164 C208 164 204 160 196 158 Z" fill="#3B261D" />
        <!-- 马尾辫 -->
        <path d="M216 144 C228 140 236 150 232 168 C228 174 222 172 220 166 Z" fill="#3B261D" />
        <circle cx="216" cy="146" r="3" fill="#EC4899" />
      </g>

      <!-- 角色 2：坐着的开发工程师与工学椅、工作桌 -->
      <g class="character-dev" filter="url(#illShadow)">
        <!-- 工作桌面 -->
        <rect x="330" y="240" width="130" height="12" rx="4" fill="#FFFFFF" stroke="#E2E8F0" stroke-width="1.5" />
        <line x1="344" y1="252" x2="340" y2="322" stroke="#94A3B8" stroke-width="4" stroke-linecap="round" />
        <line x1="446" y1="252" x2="450" y2="322" stroke="#94A3B8" stroke-width="4" stroke-linecap="round" />

        <!-- 工学椅靠背与气压杆 -->
        <path d="M304 196 C304 186 318 184 322 196 L324 266 H304 Z" fill="url(#illChairGrad)" />
        <path d="M302 266 H334 V276 H302 Z" fill="#334155" rx="3" />
        <line x1="318" y1="276" x2="318" y2="316" stroke="#475569" stroke-width="6" stroke-linecap="round" />
        <line x1="298" y1="316" x2="338" y2="316" stroke="#334155" stroke-width="4" stroke-linecap="round" />

        <!-- 角色 2 躯干（极简藏蓝连帽卫衣） -->
        <path d="M312 214 C324 210 344 212 352 232 L344 274 H314 Z" fill="#1E293B" />
        <path d="M324 216 L332 234 L340 216 Z" fill="#475569" />

        <!-- 头部与现代黑框眼镜 -->
        <ellipse cx="334" cy="186" rx="12" ry="14" fill="#FED7AA" />
        <!-- 黑色短发 -->
        <path d="M322 186 C322 174 344 172 346 182 C348 188 346 194 340 196 C334 196 326 194 322 186 Z" fill="#1E293B" />
        <!-- 眼镜与面部微表情 -->
        <rect x="333" y="184" width="7" height="5" rx="1.5" stroke="#0F172A" stroke-width="1.2" fill="none" />
        <circle cx="336" cy="186" r="1.2" fill="#0F172A" />

        <!-- 手臂与敲击键盘动作 -->
        <path d="M336 226 L356 236 L376 236" stroke="#1E293B" stroke-width="8" stroke-linecap="round" stroke-linejoin="round" />
        <circle cx="377" cy="236" r="4" fill="#FED7AA" />

        <!-- 笔记本电脑（屏幕泛着代码荧光） -->
        <path d="M370 238 L392 238 L386 210 L366 212 Z" fill="#0F172A" />
        <rect x="368" y="213" width="20" height="23" fill="#1E1B4B" rx="1" />
        <!-- 代码高亮线条 -->
        <line x1="371" y1="217" x2="384" y2="217" stroke="#38BDF8" stroke-width="1.5" stroke-linecap="round" />
        <line x1="371" y1="222" x2="381" y2="222" stroke="#A855F7" stroke-width="1.5" stroke-linecap="round" />
        <line x1="373" y1="227" x2="385" y2="227" stroke="#34D399" stroke-width="1.5" stroke-linecap="round" />
        <line x1="371" y1="232" x2="379" y2="232" stroke="#FBBF24" stroke-width="1.5" stroke-linecap="round" />
        <!-- 笔记本底座 -->
        <polygon points="362,238 398,238 394,242 360,242" fill="#94A3B8" />

        <!-- 桌面小咖啡杯 -->
        <rect x="420" y="228" width="10" height="12" rx="2" fill="#F8FAFC" stroke="#CBD5E1" stroke-width="1" />
        <path d="M430 231 C433 231 433 237 430 237" stroke="#CBD5E1" stroke-width="1" fill="none" />
        <path d="M423 225 Q425 222 427 225" stroke="#CBD5E1" stroke-width="1" stroke-linecap="round" fill="none" opacity="0.6" />
      </g>

      <!-- 友好克制的 AI 智能助手形象（悬浮在两人中央上方） -->
      <g class="ai-assistant" filter="url(#illGlow)">
        <!-- 悬浮智能外环轨线 -->
        <ellipse cx="280" cy="118" rx="34" ry="12" fill="none" stroke="url(#illAiRing)" stroke-width="2" stroke-dasharray="8 4" opacity="0.8" />
        <circle cx="312" cy="116" r="3" fill="#38BDF8" />
        <circle cx="248" cy="120" r="2.5" fill="#C084FC" />

        <!-- AI 助手核心主体（圆润胶囊/微球，带有微渐变高光） -->
        <rect x="260" y="96" width="40" height="42" rx="20" fill="url(#illAiGlow)" />
        <rect x="262" y="98" width="36" height="38" rx="18" fill="#FFFFFF" opacity="0.15" />

        <!-- 友善眼神屏（黑屏与智能笑眸） -->
        <rect x="267" y="106" width="26" height="16" rx="8" fill="#0F172A" />
        <circle cx="274" cy="114" r="2.5" fill="#38BDF8" />
        <circle cx="286" cy="114" r="2.5" fill="#38BDF8" />
        <path d="M277 117 Q280 119 283 117" stroke="#38BDF8" stroke-width="1.2" stroke-linecap="round" fill="none" />

        <!-- 顶部智能脉冲天线与灵感星芒 -->
        <line x1="280" y1="96" x2="280" y2="86" stroke="#6366F1" stroke-width="2.5" stroke-linecap="round" />
        <circle cx="280" cy="84" r="4.5" fill="#38BDF8" />
        <!-- 闪光星点 -->
        <path d="M280 74 L282 78 L286 80 L282 82 L280 86 L278 82 L274 80 L278 78 Z" fill="#F59E0B" />

        <!-- 柔和向下投射的智能连接波纹 -->
        <path d="M268 144 Q280 152 292 144" stroke="#818CF8" stroke-width="1.5" stroke-linecap="round" opacity="0.6" />
        <path d="M264 150 Q280 160 296 150" stroke="#818CF8" stroke-width="1.2" stroke-linecap="round" opacity="0.3" />
      </g>
    </svg>

    <!-- 三个真实产品功能微拟态浮层卡片（围绕插画精巧分布） -->
    <!-- 浮层 1：智能甘特与任务推进 (Progress) -->
    <div class="glass-card card-progress">
      <div class="card-icon icon-progress">
        <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2">
          <rect x="3" y="4" width="18" height="18" rx="2" ry="2"></rect>
          <line x1="16" y1="2" x2="16" y2="6"></line>
          <line x1="8" y1="2" x2="8" y2="6"></line>
          <line x1="3" y1="10" x2="21" y2="10"></line>
        </svg>
      </div>
      <div class="card-content">
        <div class="card-header-row">
          <span class="card-title">项目里程碑 · 冲刺</span>
          <span class="card-badge badge-success">推进中</span>
        </div>
        <div class="card-progress-bar">
          <div class="progress-fill" style="width: 82%;"></div>
        </div>
        <div class="card-meta">
          <span>82% 交付完成</span>
          <span>4 项前置依赖</span>
        </div>
      </div>
    </div>

    <!-- 浮层 2：AI 任务规划审批 (Intelligence & Approval) -->
    <div class="glass-card card-ai-plan">
      <div class="card-icon icon-ai">
        <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2">
          <path d="M12 2v4M12 18v4M4.93 4.93l2.83 2.83M16.24 16.24l2.83 2.83M2 12h4M18 12h4M4.93 19.07l2.83-2.83M16.24 7.76l2.83-2.83"></path>
        </svg>
      </div>
      <div class="card-content">
        <div class="card-header-row">
          <span class="card-title">AI 任务规划提案</span>
          <span class="card-badge badge-primary">写操作审批</span>
        </div>
        <p class="card-desc">自动分解 6 个开发子任务并对齐 Schema</p>
        <div class="card-status-line">
          <span class="status-dot"></span>
          <span>人工审查通过 · 幂等落库</span>
        </div>
      </div>
    </div>

    <!-- 浮层 3：文档知识库与文献引用 (Knowledge RAG) -->
    <div class="glass-card card-knowledge">
      <div class="card-icon icon-doc">
        <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2">
          <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path>
          <polyline points="14 2 14 8 20 8"></polyline>
          <line x1="16" y1="13" x2="8" y2="13"></line>
          <line x1="16" y1="17" x2="8" y2="17"></line>
          <polyline points="10 9 9 9 8 9"></polyline>
        </svg>
      </div>
      <div class="card-content">
        <div class="card-header-row">
          <span class="card-title">知识库智能问答</span>
          <span class="card-badge badge-info">pgvector</span>
        </div>
        <div class="card-quote">
          <span>“依据《产品技术规格书》第 4.2 节已定位引用”</span>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.auth-illustration-wrap {
  position: relative;
  width: 100%;
  max-width: 520px;
  margin: 12px auto 0;
  user-select: none;
}

.auth-illustration-svg {
  width: 100%;
  height: auto;
  display: block;
  overflow: visible;
}

/* 微悬浮呼吸动效（仅在非 reduced-motion 模式启用） */
@media (prefers-reduced-motion: no-preference) {
  .ai-assistant {
    animation: illFloat 4.8s ease-in-out infinite;
    transform-origin: 280px 118px;
  }
  .card-progress {
    animation: illCardFloat1 6.5s ease-in-out infinite;
  }
  .card-ai-plan {
    animation: illCardFloat2 7.2s ease-in-out infinite 0.8s;
  }
  .card-knowledge {
    animation: illCardFloat3 6.8s ease-in-out infinite 1.5s;
  }
}

@keyframes illFloat {
  0%, 100% {
    transform: translateY(0);
  }
  50% {
    transform: translateY(-8px);
  }
}

@keyframes illCardFloat1 {
  0%, 100% {
    transform: translateY(0);
  }
  50% {
    transform: translateY(-5px);
  }
}

@keyframes illCardFloat2 {
  0%, 100% {
    transform: translateY(0);
  }
  50% {
    transform: translateY(-6px);
  }
}

@keyframes illCardFloat3 {
  0%, 100% {
    transform: translateY(0);
  }
  50% {
    transform: translateY(-4px);
  }
}

/* 轻量玻璃质感浮层卡片 (Glassmorphism Micro-cards) */
.glass-card {
  position: absolute;
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 10px 14px;
  background: rgba(255, 255, 255, 0.85);
  backdrop-filter: blur(14px);
  -webkit-backdrop-filter: blur(14px);
  border: 1px solid rgba(255, 255, 255, 0.9);
  border-radius: 14px;
  box-shadow:
    0 10px 25px -5px rgba(24, 32, 51, 0.08),
    0 4px 6px -2px rgba(24, 32, 51, 0.04),
    inset 0 1px 0 rgba(255, 255, 255, 0.9);
  z-index: 2;
  transition: transform 0.25s ease, box-shadow 0.25s ease;
  pointer-events: auto;
}

.glass-card:hover {
  transform: translateY(-2px) scale(1.02);
  box-shadow:
    0 14px 28px -4px rgba(88, 101, 216, 0.15),
    0 6px 10px -2px rgba(24, 32, 51, 0.06);
}

/* 卡片定位 */
.card-progress {
  top: 10px;
  left: -8px;
  width: 200px;
}

.card-ai-plan {
  top: 36px;
  right: -10px;
  width: 215px;
}

.card-knowledge {
  bottom: 22px;
  right: 0;
  width: 225px;
}

/* 图标样式 */
.card-icon {
  flex-shrink: 0;
  width: 28px;
  height: 28px;
  border-radius: 8px;
  display: grid;
  place-items: center;
  margin-top: 1px;
}

.icon-progress {
  background: #ecfdf5;
  color: #059669;
}

.icon-ai {
  background: #eef2ff;
  color: #5865d8;
}

.icon-doc {
  background: #f0f9ff;
  color: #0284c7;
}

/* 卡片内容排版 */
.card-content {
  flex: 1;
  min-width: 0;
}

.card-header-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
  margin-bottom: 4px;
}

.card-title {
  font-size: 11px;
  font-weight: 700;
  color: #1e293b;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.card-badge {
  font-size: 9px;
  font-weight: 600;
  padding: 1px 5px;
  border-radius: 4px;
  line-height: 1.3;
}

.badge-success {
  background: #d1fae5;
  color: #065f46;
}

.badge-primary {
  background: #e0e7ff;
  color: #4338ca;
}

.badge-info {
  background: #e0f2fe;
  color: #0369a1;
}

.card-desc {
  margin: 0 0 4px;
  font-size: 10px;
  color: #64748b;
  line-height: 1.35;
}

.card-progress-bar {
  width: 100%;
  height: 4px;
  background: #e2e8f0;
  border-radius: 2px;
  overflow: hidden;
  margin: 4px 0;
}

.progress-fill {
  height: 100%;
  background: linear-gradient(90deg, #10b981, #059669);
  border-radius: 2px;
}

.card-meta {
  display: flex;
  justify-content: space-between;
  font-size: 9px;
  color: #64748b;
}

.card-status-line {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 9px;
  font-weight: 600;
  color: #059669;
}

.status-dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: #10b981;
  box-shadow: 0 0 0 2px rgba(16, 185, 129, 0.2);
}

.card-quote {
  font-size: 9px;
  color: #475569;
  line-height: 1.35;
  font-style: italic;
  background: rgba(241, 245, 249, 0.7);
  padding: 3px 6px;
  border-radius: 4px;
  border-left: 2px solid #38bdf8;
}

/* 响应式调整 */
@media (max-width: 1100px) {
  .card-progress {
    width: 205px;
    left: -4px;
    padding: 8px 11px;
  }
  .card-ai-plan {
    width: 215px;
    right: -4px;
    padding: 8px 11px;
  }
  .card-knowledge {
    width: 215px;
    right: 0;
    padding: 8px 11px;
  }
}
</style>
