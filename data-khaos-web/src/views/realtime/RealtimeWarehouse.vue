<script setup lang="ts">
import { ArrowRight, Connection, DataBoard, Files, TrendCharts } from '@element-plus/icons-vue'
import DkPageHeader from '@/paas-ui/DkPageHeader.vue'

import { projectLocation, useRealtimeProject } from './projectContext'

const { project } = useRealtimeProject()
const layers = [
  { name: 'ODS', title: '订单变更', detail: 'MySQL CDC 写入 Kafka，保留可回放的变更事件。', icon: Connection },
  { name: 'DWD', title: '支付明细', detail: '按订单维护支付状态，处理变更、撤销与去重。', icon: Files },
  { name: 'DWS', title: '地区分钟汇总', detail: '按业务日期、地区和分钟汇总支付数据。', icon: TrendCharts },
  { name: 'ADS', title: '地区日成交额', detail: '按日对外提供地区成交额和支付订单数。', icon: DataBoard },
]
</script>

<template>
  <div class="warehouse">
    <DkPageHeader title="实时数仓" description="订单流批一体样例从 CDC 接入，经 SQL 加工后供业务取数。" />
    <section class="dk-panel layers" aria-labelledby="warehouse-layers">
      <h2 id="warehouse-layers">订单数仓链路</h2>
      <ol>
        <li v-for="layer in layers" :key="layer.name">
          <div class="layer-heading">
            <component :is="layer.icon" aria-hidden="true" /><span>{{ layer.name }}</span>
          </div>
          <strong>{{ layer.title }}</strong>
          <p>{{ layer.detail }}</p>
        </li>
      </ol>
    </section>
    <section class="dk-panel layers" aria-labelledby="warehouse-delivery">
      <h2 id="warehouse-delivery">开发与消费</h2>
      <div class="links">
        <RouterLink :to="projectLocation('/jobs/realtime/sql', project)">开发实时 SQL <ArrowRight aria-hidden="true" /></RouterLink>
        <RouterLink :to="projectLocation('/jobs/onesql', project)">用 OneSQL 查询结果 <ArrowRight aria-hidden="true" /></RouterLink>
        <RouterLink to="/assets">在数据地图查看已登记资产 <ArrowRight aria-hidden="true" /></RouterLink>
        <RouterLink to="/service/warehouse-api">管理数仓 API <ArrowRight aria-hidden="true" /></RouterLink>
      </div>
    </section>
  </div>
</template>

<style scoped>
.warehouse, .layers { display: grid; gap: 15px; }
.layers { padding: 18px; }
h2 { margin: 0; font-size: 18px; }
ol, .links { display: grid; grid-template-columns: repeat(auto-fit, minmax(165px, 1fr)); gap: 9px; padding: 0; margin: 0; }
li { list-style: none; padding: 12px; border-radius: var(--dk-radius-panel); background: var(--dk-primary-soft); }
.layer-heading { display: flex; align-items: center; gap: 7px; color: var(--dk-primary); font-weight: 700; margin-bottom: 9px; }
p { color: var(--dk-muted); font-size: 13px; line-height: 1.5; margin: 5px 0 0; }
.links a { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 10px;
  color: var(--dk-primary); border: 1px solid var(--dk-border); border-radius: var(--dk-radius-control); text-decoration: none; }
.links a:hover { border-color: var(--dk-primary); }
svg { width: 17px; height: 17px; flex: none; }
</style>
