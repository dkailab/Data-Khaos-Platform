<template>
  <div class="login-page">
    <!-- 左侧品牌区：抽象几何数据可视化背景 + 品牌 slogan -->
    <div class="brand-panel">
      <svg class="brand-bg" viewBox="0 0 720 900" preserveAspectRatio="xMidYMid slice" aria-hidden="true">
        <g fill="none" stroke="rgba(255,255,255,0.14)" stroke-width="1">
          <path d="M60 120 L260 300 L180 520 L340 640 L300 820" />
          <path d="M520 80 L440 260 L560 420 L500 640 L620 760" />
          <path d="M120 700 L300 560 L460 720 L640 560" />
        </g>
        <g fill="rgba(255,255,255,0.5)">
          <circle cx="60" cy="120" r="4" />
          <circle cx="260" cy="300" r="4" />
          <circle cx="180" cy="520" r="4" />
          <circle cx="340" cy="640" r="4" />
          <circle cx="300" cy="820" r="4" />
          <circle cx="520" cy="80" r="4" />
          <circle cx="440" cy="260" r="4" />
          <circle cx="560" cy="420" r="4" />
          <circle cx="500" cy="640" r="4" />
          <circle cx="620" cy="760" r="4" />
          <circle cx="120" cy="700" r="4" />
          <circle cx="300" cy="560" r="4" />
          <circle cx="460" cy="720" r="4" />
          <circle cx="640" cy="560" r="4" />
        </g>
        <g fill="rgba(255,255,255,0.12)">
          <circle cx="90" cy="240" r="1.5" />
          <circle cx="400" cy="180" r="1.5" />
          <circle cx="600" cy="300" r="1.5" />
          <circle cx="240" cy="680" r="1.5" />
          <circle cx="560" cy="820" r="1.5" />
          <circle cx="180" cy="120" r="1.5" />
          <circle cx="680" cy="500" r="1.5" />
        </g>
      </svg>

      <div class="brand-content">
        <div class="brand-logo">
          <el-icon :size="26" color="#fff"><DataAnalysis /></el-icon>
          <span class="brand-name">Data-Khaos-Platform</span>
        </div>
        <h1 class="brand-title">数据治理平台</h1>
        <p class="brand-slogan">洞察数据 · 驱动决策</p>
        <p class="brand-position">AI+DATA 数据平台</p>
      </div>
    </div>

    <!-- 右侧登录表单 -->
    <div class="form-panel">
      <div class="form-card">
        <div class="form-header">
          <h2>欢迎登录</h2>
          <p>请输入您的账号信息</p>
        </div>

        <el-form ref="formRef" :model="form" :rules="rules" size="large" @keyup.enter="handleLogin">
          <el-form-item prop="username">
            <el-input v-model="form.username" placeholder="请输入账号" :prefix-icon="User" clearable />
          </el-form-item>
          <el-form-item prop="password">
            <el-input
              v-model="form.password"
              type="password"
              placeholder="请输入密码"
              :prefix-icon="Lock"
              show-password
            />
          </el-form-item>
          <el-form-item prop="captchaCode">
            <div class="captcha-row">
              <el-input v-model="form.captchaCode" placeholder="验证码" :prefix-icon="Key" clearable />
              <img
                v-if="captchaImg"
                class="captcha-img"
                :src="captchaImg"
                alt="验证码"
                title="点击刷新"
                @click="loadCaptcha"
              />
            </div>
          </el-form-item>

          <div class="form-options">
            <el-checkbox v-model="remember">记住我</el-checkbox>
            <span class="forgot-link" @click.prevent="handleForgot">忘记密码</span>
          </div>

          <el-form-item>
            <el-button
              type="primary"
              class="login-btn"
              :loading="loading"
              @click="handleLogin"
            >登 录</el-button>
          </el-form-item>
        </el-form>

        <div class="form-copyright">© 2026 dkailab</div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { User, Lock, Key } from '@element-plus/icons-vue'
import { getCaptcha } from '@/api/auth'
import { useUserStore } from '@/stores/user'
import type { LoginRequest } from '@/types'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const formRef = ref<FormInstance>()
const loading = ref(false)
const captchaImg = ref('')
const remember = ref(localStorage.getItem('dk_remember_username') === '1')
const form = reactive<LoginRequest>({
  username: localStorage.getItem('dk_username') || '',
  password: '',
  captchaId: '',
  captchaCode: '',
})

const rules: FormRules = {
  username: [
    { required: true, message: '请输入账号', trigger: 'blur' },
    { min: 2, max: 50, message: '账号长度为 2-50 个字符', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
  ],
  captchaCode: [{ required: true, message: '请输入验证码', trigger: 'blur' }],
}

async function loadCaptcha() {
  const data = await getCaptcha()
  form.captchaId = data.captchaId
  captchaImg.value = data.imageBase64
}

async function handleLogin() {
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  loading.value = true
  try {
    const payload: LoginRequest = {
      username: form.username,
      password: form.password,
      captchaId: form.captchaId,
      captchaCode: form.captchaCode,
    }
    await userStore.login(payload)
    if (remember.value) {
      localStorage.setItem('dk_username', form.username)
      localStorage.setItem('dk_remember_username', '1')
    } else {
      localStorage.removeItem('dk_username')
      localStorage.removeItem('dk_remember_username')
    }
    ElMessage.success('登录成功')
    router.push((route.query.redirect as string) || '/dashboard')
  } catch {
    // 验证码一次有效：校验失败即失效，失败后自动刷新并清空输入
    form.captchaCode = ''
    loadCaptcha()
  } finally {
    loading.value = false
  }
}

function handleForgot() {
  ElMessage.info('请联系系统管理员重置密码')
}

onMounted(() => {
  loadCaptcha()
})
</script>

<style scoped>
/* 整体：B 端商务浅色模式，低饱和 */
.login-page {
  display: flex;
  height: 100%;
  background: #f7f8fa;
}

/* ---------- 左侧品牌区 ---------- */
.brand-panel {
  position: relative;
  width: 46%;
  min-width: 420px;
  overflow: hidden;
  background: linear-gradient(160deg, var(--dk-primary) 0%, var(--dk-primary-hover) 100%);
  color: #fff;
}
.brand-bg {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  opacity: 0.9;
}
.brand-content {
  position: relative;
  z-index: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  height: 100%;
  padding: 0 64px;
}
.brand-logo {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 28px;
}
.brand-logo > .el-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.16);
}
.brand-name {
  font-size: 22px;
  font-weight: 600;
  letter-spacing: 0.02em;
}
.brand-title {
  margin: 0 0 12px;
  font-size: 34px;
  font-weight: 600;
  letter-spacing: 0.04em;
}
.brand-slogan {
  margin: 0 0 8px;
  font-size: 16px;
  color: rgba(255, 255, 255, 0.9);
  letter-spacing: 0.2em;
}
.brand-position {
  margin: 0;
  font-size: 14px;
  color: rgba(255, 255, 255, 0.72);
  letter-spacing: 0.02em;
}

/* ---------- 右侧表单区 ---------- */
.form-panel {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
}
.form-card {
  width: 100%;
  max-width: 400px;
  padding: 12px 8px;
}
.form-header {
  margin-bottom: 28px;
}
.form-header h2 {
  margin: 0 0 8px;
  font-size: 26px;
  font-weight: 600;
  color: #1d2129;
}
.form-header p {
  margin: 0;
  font-size: 14px;
  color: #86909c;
}
.captcha-row {
  display: flex;
  width: 100%;
  gap: 10px;
}
.captcha-img {
  width: 120px;
  height: 40px;
  border-radius: 4px;
  cursor: pointer;
  border: 1px solid #dcdfe6;
  flex-shrink: 0;
}
.form-options {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: -4px 0 18px;
}
.form-options :deep(.el-checkbox__label) {
  font-size: 14px;
  color: #4e5969;
}
.forgot-link {
  font-size: 14px;
  color: var(--dk-primary);
  cursor: pointer;
  user-select: none;
}
.forgot-link:hover {
  color: #4080ff;
}
.login-btn {
  width: 100%;
  height: 44px;
  font-size: 16px;
  letter-spacing: 0.2em;
  background: var(--dk-primary);
  border-color: var(--dk-primary);
}
.login-btn:hover {
  background: #4080ff;
  border-color: #4080ff;
}
.form-copyright {
  margin-top: 24px;
  text-align: center;
  font-size: 12px;
  color: #a9aeb8;
}

/* 聚焦柔和高亮，禁止刺眼 */
.login-page :deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 1px var(--dk-primary) inset;
}

/* ---------- 响应式：移动端上下布局，隐藏左侧品牌区 ---------- */
@media (max-width: 768px) {
  .brand-panel {
    display: none;
  }
  .form-panel {
    align-items: center;
    padding: 24px 20px;
  }
}
</style>