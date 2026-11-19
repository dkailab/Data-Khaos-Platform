# Data Khaos 前端约定

- 使用[共享主题和页面规范](../docs/paas-ui-design.md)。页面复用 `DkPageHeader`、`DkButton`、`DkStatus`、Element Plus 和现有线性图标。
- 导航入口来自模块注册表与工作区配置。门户列出产品，业务侧栏仅列当前工作区。数据地图归数据资产，历史链接与登录回跳保持兼容。
- 生产页面使用真实接口状态。验证加载、空结果、权限错误和窄屏。完成后运行 `npm run build`，在浏览器核对变更行为。
