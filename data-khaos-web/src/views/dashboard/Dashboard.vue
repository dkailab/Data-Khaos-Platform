<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import { ArrowRight, Connection, Odometer, PieChart } from '@element-plus/icons-vue'
import portalArt from '@/assets/portal-data-core.webp'
import { useModuleStore } from '@/stores/module'
import { buildWorkspaces, describeWorkspace, groupProducts, workspaceHome } from '@/modules/workspaces'

const modules = useModuleStore()
const products = computed(() => modules.loaded
  ? groupProducts(buildWorkspaces(modules.categories, modules.systemCategory, modules.canConfig)).flatMap(group => group.workspaces)
  : [])
</script>

<template>
  <div class="portal-home">
    <section class="portal-hero" aria-labelledby="portal-title">
      <div class="hero-inner">
        <div class="hero-art" aria-hidden="true">
          <img :src="portalArt" alt="" fetchpriority="high" width="1080" height="720" />
        </div>
        <div class="hero-copy">
          <h1 id="portal-title">Data Khaos</h1>
          <p class="hero-subtitle">DATA + AI</p>
          <p class="hero-description">统一的数据工程与治理能力，为业务分析与 AI 应用提供可靠的数据基础。</p>
          <a href="#products" class="hero-action">探索产品 <ArrowRight aria-hidden="true" /></a>
          <ul class="hero-capabilities" aria-label="核心能力">
            <li><Connection aria-hidden="true" />数据接入</li>
            <li><Odometer aria-hidden="true" />资产与治理</li>
            <li><PieChart aria-hidden="true" />BI 分析</li>
          </ul>
        </div>
      </div>
    </section>

    <section id="products" class="portal-products" aria-labelledby="products-title">
      <div class="products-heading">
        <h2 id="products-title">产品与工作区</h2>
        <p>选择一个工作区开始工作。</p>
      </div>
      <div v-if="products.length" class="product-grid">
        <RouterLink v-for="product in products" :key="product.key" :to="workspaceHome(product)" class="dk-panel product-card">
          <span class="product-icon"><component :is="product.icon" aria-hidden="true" /></span>
          <div><h3>{{ product.title }}</h3><p>{{ describeWorkspace(product.key) }}</p></div>
          <ArrowRight class="card-arrow" aria-hidden="true" />
        </RouterLink>
      </div>
      <p v-else class="products-pending">暂无可用产品入口。</p>
    </section>
  </div>
</template>

<style scoped>
.portal-home { min-height: calc(100dvh - 57px); background: #f8faff; }
.portal-hero { background: radial-gradient(ellipse at 30% 62%, #e5eeff 0%, transparent 61%), linear-gradient(115deg, #f4f8ff, #f8faff 74%); border-bottom: 1px solid #e6edf7; }
.hero-inner { display: grid; grid-template-columns: 1.12fr 1fr; align-items: center; gap: clamp(18px, 5vw, 88px); max-width: 1260px; margin: auto; padding: clamp(32px, 6vw, 76px) clamp(16px, 5vw, 70px); }
.hero-art { min-width: 0; position: relative; aspect-ratio: 1.42; }
.hero-art img { display: block; width: 100%; height: 100%; object-fit: contain; mix-blend-mode: multiply; mask-image: radial-gradient(ellipse 72% 74% at center, #000 45%, transparent 78%); }
.hero-copy { min-width: 0; padding-block: 6px; }
h1 { margin: 0; font-size: clamp(44px, 6vw, 78px); line-height: 1; letter-spacing: -.067em; color: #12264a; }
.hero-subtitle { margin: 15px 0 8px; color: #22477d; font-size: clamp(19px, 2.5vw, 30px); font-weight: 680; letter-spacing: -.02em; }
.hero-description { margin: 0 0 23px; max-width: 30rem; color: var(--dk-secondary); line-height: 1.8; font-size: 14px; }
.hero-action { display: inline-flex; align-items: center; gap: 9px; background: var(--dk-primary); color: white; text-decoration: none; border-radius: var(--dk-radius-control); padding: 10px 15px; font-weight: 650; }
.hero-action:hover { background: var(--dk-primary-hover); }
.hero-action svg, .hero-capabilities svg, .card-arrow { width: 16px; height: 16px; flex: none; }
.hero-capabilities { display: flex; flex-wrap: wrap; gap: 10px 16px; padding: 0; margin: 18px 0 0; list-style: none; color: var(--dk-secondary); font-size: 12px; }
.hero-capabilities li { display: flex; align-items: center; gap: 5px; }
.hero-capabilities svg { color: var(--dk-primary); }
.portal-products { max-width: 1260px; margin: auto; padding: 28px clamp(16px, 5vw, 70px) 44px; scroll-margin-top: 66px; }
.products-heading h2 { margin: 0; font-size: 24px; letter-spacing: -.03em; }
.products-heading p, .products-pending { color: var(--dk-muted); margin: 4px 0 16px; }
.product-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: var(--dk-space-3); }
.product-card { display: grid; grid-template-columns: 36px minmax(0,1fr) 16px; gap: 11px; align-items: start; padding: 14px; text-decoration: none; color: var(--dk-ink); }
.product-card:hover { border-color: #97b7e6; background: #fcfdff; }
.product-card h3 { margin: 0; font-size: 15px; }
.product-card p { margin: 4px 0 0; color: var(--dk-secondary); font-size: 12px; }
.product-icon { display: grid; place-items: center; width: 36px; height: 36px; border-radius: var(--dk-radius-control); background: var(--dk-primary-soft); color: var(--dk-primary); }
.product-icon svg { width: 18px; height: 18px; }
.card-arrow { margin-top: 4px; color: var(--dk-primary); }
@media (max-width: 1050px) { .product-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); } }
@media (max-width: 730px) {
  .hero-inner { grid-template-columns: 1fr; gap: 16px; padding-block: 25px; }
  .hero-copy { grid-row: 1; }
  .product-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .hero-art { width: min(100%, 410px); margin-inline: auto; }
}
@media (max-width: 480px) { .product-grid { grid-template-columns: 1fr; } }
</style>
