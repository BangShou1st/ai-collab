import pluginVue from 'eslint-plugin-vue'
import { defineConfigWithVueTs, vueTsConfigs } from '@vue/eslint-config-typescript'
import prettierConfig from 'eslint-config-prettier'

// 将 vue 推荐规则整体降级为 warn：先以警告级别接入，仅 error 级问题阻断 CI
function downgradeToWarn(config) {
  return {
    ...config,
    rules: Object.fromEntries(
      Object.entries(config.rules ?? {}).map(([rule, setting]) => {
        if (typeof setting === 'string') {
          return [rule, setting === 'error' ? 'warn' : setting]
        }
        if (Array.isArray(setting) && setting[0] === 'error') {
          return [rule, ['warn', ...setting.slice(1)]]
        }
        return [rule, setting]
      }),
    ),
  }
}

const vueRecommendedWarn = pluginVue.configs['flat/recommended'].map(downgradeToWarn)

export default defineConfigWithVueTs(
  {
    ignores: ['dist/**', 'node_modules/**', '.wrangler/**', 'coverage/**'],
  },
  ...vueRecommendedWarn,
  vueTsConfigs.recommended,
  prettierConfig,
  {
    files: ['src/**/*.{ts,vue}'],
    rules: {
      '@typescript-eslint/no-explicit-any': 'warn',
      '@typescript-eslint/no-unused-vars': [
        'warn',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_' },
      ],
    },
  },
)
