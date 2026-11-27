# 订单数仓测试空间

在本机开发环境预置一个独立项目和 420 条模拟订单，覆盖 7 个业务日、5 个地区和 3 个渠道。项目 ID 为 `dk_demo_orders`，业务表在 `dk_test_orders` 库，数据源名称为「订单测试库」。

启动本地平台和 Vite（本脚本使用 `127.0.0.1:5183`），并确认本地 `test` 账号可登录。执行以下命令初始化数据、运行离线工作流并生成治理与 BI 示例。

```sh
python3 examples/test-orders/seed.py --quality --dashboard
```

如果本地测试账号使用其他密码，通过 `DK_TEST_PASSWORD` 提供。脚本使用本地 Compose 的 MySQL 容器补齐工作流和 Flink 所需迁移，随机生成只用于订单测试库的数据库凭据。离线工作流按业务日期覆盖 ODS 分区。再次执行会复用已注册的项目和资产，产生新运行记录。加 `--skip-runs` 可以复核已生成的数据。

- **作业开发 → 调度工作流** 查看「订单数仓日处理」及运行记录。
- **OneSQL 取数**选择「订单测试库」，执行 `SELECT * FROM demo_ads_region_sales ORDER BY biz_date DESC, gmv DESC`。
- **数据地图**搜索 `demo_`，打开任意示例表查看字段和直接血缘。
- **质量结果**查看「订单测试：原始层体检」。
- **仪表板**打开「订单数仓 · 测试看板」，查看成交金额、订单量、趋势和区域分布。

可另用已有的 [社区版 Flink Compose 示例](../realtime-orders/compose.platform-check.yml) 启动 SQL Gateway、JobManager 和 TaskManager。让平台工作流服务能访问集群，在本地环境设置 `FLINK_RUN_PROJECT_ID=dk_demo_orders`、`FLINK_RUN_SQL_GATEWAY` 和 `FLINK_RUN_JOBMANAGER` 后重启工作流服务。

```sh
python3 examples/test-orders/seed.py --skip-runs --flink
```

Flink 示例使用内置数据生成器。实时示例持续运行并提供开窗与运行监控入口，批处理示例读完 120 条后结束。两条作业均使用测试输出，BI 读取离线链路的 ADS 视图。
