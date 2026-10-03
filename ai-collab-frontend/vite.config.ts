import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'VITE_')
  const backendOrigin = env.VITE_BACKEND_ORIGIN || 'http://localhost:8080'

  return {
    plugins: [vue()],
    server: {
      host: 'localhost',
      port: 5173,
      proxy: {
        '/api': {
          target: backendOrigin,
          changeOrigin: true,
        },
      },
    },
  }
})
