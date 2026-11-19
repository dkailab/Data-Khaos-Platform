---
name: data-khaos-dev
description: 用于 Data Khaos 的功能开发、缺陷修复和代码评审。按改动范围定位模块、遵守 PaaS 与 BI 边界并复用 UI，控制需要读取的仓库上下文。
---

# Data Khaos 开发

1. **限定读取范围**。先看适用的 AGENTS.md 与 `git status --short`。用 `rg --files`、`rg -n` 找入口，只读取命中的方法和直接依赖。核实已有契约后再扩展到相关服务。构建输出重定向到临时日志，首次只读结果与失败段。
2. **按任务加载参考**。
   - 后端接口或数据访问读[后端规范](../../../docs/backend-development-guide.md)的相关章节。
   - 涉及跨服务或 PaaS/BI 边界读[架构](../../../docs/architecture.md)。
   - 页面与导航读[前端约定](../../../data-khaos-web/AGENTS.md)，需要设计细节再查 [UI 规范](../../../docs/paas-ui-design.md)。
   - 权限、部署和标准对接从[文档索引](../../../docs/README.md)选择对应材料。
3. **沿生产入口回归**。先确认请求、状态和权限的现有语义。前端运行 `npm run build`，后端优先 `mvn -B -pl <module> -am test`。跨服务、数据库迁移或权限变更增加相应的集成验证。
4. **简洁交接**。交付说明列出改动、验证结果和剩余问题。文档更新放到已有专题入口，避免再创建一份同主题的计划或交接文件。
