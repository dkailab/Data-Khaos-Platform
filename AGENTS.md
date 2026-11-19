# Data Khaos 开发约定

维护者 **dkailab**。从当前模块的入口、调用链和现有校验开始。专题资料按需从[文档索引](docs/README.md)查阅。

- **服务分层**。Controller 处理请求，Service 负责业务、授权与事务，Mapper 负责持久化。统一返回 `R<T>`，列表使用 `PageResult<T>`。跨服务契约放所属 `*-api`，通过已有客户端调用。
- **产品归属**。数据接入、编排、目录、质量、权限、SQL 工作台与语义指标属于 PaaS。数据集、图表和仪表板归 BI，复用平台数据服务。调度负责触发，工作流负责编排，管道负责同步。
- **前端入口**。修改 `data-khaos-web` 时遵循它的 [AGENTS.md](data-khaos-web/AGENTS.md)，复用统一主题、工作区导航及组件。
- **验证**。按改动范围构建和回归。跨服务、授权、迁移或异步任务更改要覆盖相关失败路径，并说明实测环境。开发流程见 [data-khaos-dev](.agents/skills/data-khaos-dev/SKILL.md)。
