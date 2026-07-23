import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import App from './App.vue'
import { router } from './router'
import { setUnauthorizedHandler } from './auth/unauthorized-handler'
import './styles.css'

const app = createApp(App)
const pinia = createPinia()

app.use(pinia)
app.use(router)
app.use(ElementPlus)
setUnauthorizedHandler(async () => {
  await router.replace('/login')
})
app.mount('#app')
