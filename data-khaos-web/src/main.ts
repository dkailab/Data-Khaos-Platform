import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import '@/paas-ui/tokens.css'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import { pinia } from '@/stores'
import { useUserStore } from '@/stores/user'
import router from '@/router'
import App from './App.vue'

const app = createApp(App)

app.use(pinia)

// 启动静默校验登录态：本地有 token 则拉取用户信息。
// - token 有效：正常填充 userInfo，页面正常使用；
// - token 无效/过期：/auth/info 返回 401，请求拦截器会强制清空登录态并跳回登录页重新登录。
if (localStorage.getItem('dk_token')) {
  useUserStore(pinia).fetchInfo().catch(() => {
    // 校验失败的跳转已由 request 拦截器统一处理（401 强制登出跳登录）
  })
}

app.use(router)
app.use(ElementPlus, { locale: zhCn })

// 全局注册 Element Plus 图标（侧边栏菜单按名称引用）
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

app.mount('#app')
