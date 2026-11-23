# Data Khaos 文档

维护者 **dkailab**。发布历史见[变更记录](../CHANGELOG.md)。

| 任务 | 入口 |
| --- | --- |
| 部署 | [Compose 快速开始](../docker/README.md) · [部署与运维](deployment.md) |
| 架构 | [分层与边界](architecture.md) · [平台路线图](big-data-platform-roadmap.md) · [PaaS 实施](paas-implementation-plan.md) · [实时数仓实施](paas/realtime-warehouse.md) · [Flink 开发工作区](paas/flink-studio.md) |
| 产品设计 | [项目空间隔离 PRD](paas/project-workspace-prd.md) · [功能设计](paas/project-workspace-design.md) · [HTML 原型](../prd/project-workspace-prototype.html) |
| 开发 | [后端规范](backend-development-guide.md) · [UI 与门户规范](paas-ui-design.md) · [数据库](db-design.md) · [权限](permission-handbook.md) |
| 专题 | [数据管道](data-pipeline.md) · [数据质量](dquality-requirements.md) · [BI 语义层](bi-chart-semantic-layer.md) · [国家数据技术文件](standards/README.md) |

AI 开发从根目录 [AGENTS.md](../AGENTS.md)开始。只有涉及标准映射或对外接入时才加载 [data-khaos-standards](../.agents/skills/data-khaos-standards/SKILL.md)。

新模块先评审 PRD，再评审功能设计，通过后进入开发。交给 AI 时只提供本模块两份文档、根目录 AGENTS.md 和相关入口。产品目标与验收放在 PRD，接口、流程图和迁移放在功能设计。既有 HTML 原型归档在仓库根目录的 `prd/`。
