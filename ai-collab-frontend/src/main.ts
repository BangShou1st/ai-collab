import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import App from './App.vue'
import { router } from './router'
import { setUnauthorizedHandler } from './auth/unauthorized-handler'
import { resolveSafeRedirect } from './shared/safe-redirect'
import './styles.css'

const app = createApp(App)
const pinia = createPinia()

app.use(pinia)
app.use(router)
app.use(ElementPlus, { locale: zhCn })
setUnauthorizedHandler(async () => {
  const currentRoute = router.currentRoute.value
  if (currentRoute.path === '/login') return
  const redirect = resolveSafeRedirect(router, currentRoute.fullPath)
  await router.replace({ path: '/login', query: { redirect } })
})
app.mount('#app')
