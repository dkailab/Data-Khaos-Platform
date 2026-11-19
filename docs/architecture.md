# Data Khaos 架构设计文档

## 系统分层

| 层 | 组成与职责 |
| --- | --- |
| 运行基础设施 | MySQL、Redis、Nacos；Hive/HDFS 与外部引擎按部署场景启用。 |
| 数据工程 | datasource 管理连接和 JDBC SPI；pipeline 进行同步；schedule 触发任务；workflow 运行 DAG。 |
| 治理与权限 | metadata 管理目录、资产与血缘；dquality 负责规则、执行和快照；auth、permission、approval 管理身份与授权。 |
| 数据服务 | query 提供 SQL 工作台，mart 提供模型、指标和语义查询。 |
| 体验 | data-khaos-web 提供门户、数据地图和业务工作区；visual 提供 BI 数据集、图表和仪表板。 |

Controller → Service → Mapper。跨服务通过归属模块的 `*-api` 客户端调用；跨域关系通过资产 ID、模型 ID 和运行 ID 关联。平台 API 是 BI 的数据边界。

## 2. 服务划分

| 服务名称 | 端口 | 说明 |
|---------|------|------|
| data-khaos-gateway | 8099 | API 网关 |
| data-khaos-auth | 8081 | 认证中心 |
| data-khaos-permission | 8082 | 权限服务 |
| data-khaos-approval | 8083 | 审批服务 |
| data-khaos-datasource | 8084 | 数据源服务 |
| data-khaos-metadata | 8085 | 元数据服务 |
| data-khaos-mart | 8086 | 集市服务 |
| data-khaos-query | 8087 | 查询服务 |
| data-khaos-visual | 8088 | 可视化服务 |
| data-khaos-schedule | 8089 | 调度服务 |
| data-khaos-notification | 8090 | 推送服务 |
| data-khaos-dquality | 8091 | 数据质量 |
| data-khaos-pipeline | 8092 | 数据同步 |
| data-khaos-workflow | 8093 | 工作流编排 |

## 3. 通信方式

- **服务间同步调用**：Nacos 服务发现与负载均衡 REST 客户端
- **任务执行**：调度触发工作流和管道，按运行实例跟踪状态
- **前端 → 后端**: RESTful API（通过网关）

## 4. 安全架构

```
用户请求 → Gateway(校验Token) → Auth(解析身份) → Permission(校验权限) → 目标服务
```

- Gateway 层负责 JWT Token 的校验
- Auth 服务负责 Token 的签发与刷新
- Permission 服务负责 RBAC + 行/列权限的校验
- 数据地图在 metadata 服务中按表授权过滤搜索、详情与直接血缘。运行态权限由各业务服务执行。

## PaaS 与 BI 演进

当前同仓构建。未来先将 BI 的预览、下钻、导出、刷新收敛到平台授权与查询接口，再独立部署 visual 和 BI Web。平台保留 mart、OneSQL、治理和数据工程，BI 拥有数据集配置、图表和仪表板。

拆分时先在同仓验证独立构建、schema 迁移、稳定 ID、旧链接和回退，再决定是否迁仓。跨部署使用版本化 API 和兼容矩阵。仓库采用 Apache-2.0，商业扩展边界待确定。

界面入口见 [UI 规范](paas-ui-design.md)，工程方向见[平台路线图](big-data-platform-roadmap.md)。
