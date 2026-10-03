/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** API 请求前缀，默认 /api/v1 */
  readonly VITE_API_BASE_URL?: string
  /** 后端完整来源，仅供 vite dev 代理使用，默认 http://localhost:8080 */
  readonly VITE_BACKEND_ORIGIN?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
