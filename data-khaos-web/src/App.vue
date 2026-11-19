<template>
  <router-view />
  <!-- 全站常驻水印：Data khaos 半透明倾斜平铺 -->
  <div class="global-watermark" aria-hidden="true">
    <div v-for="i in watermarkRows" :key="i" class="watermark-row">
      <span v-for="j in watermarkCols" :key="j" class="watermark-item">Data khaos</span>
    </div>
  </div>
</template>

<script setup lang="ts">
// 依据屏幕尺寸平铺水印密度（1920x1080 基准约 6 行 x 1 列）
const watermarkRows = 8
const watermarkCols = 1
</script>

<style>
/* ============ 全局基础 ============ */
html,
body,
#app {
  height: 100%;
  margin: 0;
  padding: 0;
  font-family: var(--dk-font);
}

/* ============ 全站水印 ============ */
.global-watermark {
  position: fixed;
  inset: 0;
  z-index: 9999;
  pointer-events: none;
  overflow: hidden;
}
.watermark-row {
  display: flex;
  justify-content: space-around;
  opacity: 0.5;
}
.watermark-item {
  padding: 46px 0;
  font-size: 20px;
  font-weight: 600;
  color: rgba(120, 150, 180, 0.16);
  white-space: nowrap;
  transform: rotate(-24deg);
  letter-spacing: 2px;
  user-select: none;
}
</style>