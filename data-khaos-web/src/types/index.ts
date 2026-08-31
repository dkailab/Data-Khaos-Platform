/**
 * 后端统一返回包装 R<T>：
 * 注意：字段为 code / msg / data，成功时 code === 0（非 200）。
 */
export interface R<T = any> {
  code: number
  msg: string
  data: T
  timestamp: number
}

/** 通用分页返回 PageResult */
export interface PageResult<T = any> {
  total: number
  pages: number
  current: number
  size: number
  records: T[]
}

/** 通用分页查询参数 */
export interface PageQuery {
  current: number
  size: number
  [key: string]: any
}

/* ==================== 认证 / 用户 / 角色 ==================== */

export interface LoginRequest {
  username: string
  password: string
  captchaId?: string
  captchaCode?: string
}

export interface CaptchaResponse {
  captchaId: string
  imageBase64: string
}

export interface LoginUser {
  id: string
  username: string
  realName: string
  avatar?: string
  status?: number
  lastLoginTime?: string
}

export interface LoginResponse {
  token: string
  expireIn: number
  user: LoginUser
  roles: string[]
  permissions: string[]
}

export interface SysUser {
  id?: string
  username?: string
  password?: string
  realName?: string
  email?: string
  phone?: string
  avatar?: string
  /** 1:启用 0:禁用 */
  status?: number
  createTime?: string
  updateTime?: string
}

export interface SysRole {
  id?: string
  roleCode?: string
  roleName?: string
  description?: string
  /** 1:启用 0:禁用 */
  status?: number
  createTime?: string
}

/* ==================== 权限（菜单 / 组织 / 策略 / 表权限） ==================== */

export interface SysMenu {
  id?: string
  parentId?: string
  name?: string
  path?: string
  component?: string
  permission?: string
  icon?: string
  /** 0:目录 1:菜单 2:按钮 3:API */
  type?: number
  sortOrder?: number
  status?: number
  createTime?: string
}

export interface SysOrganization {
  id?: string
  parentId?: string
  orgName?: string
  orgCode?: string
  /** DEPT / COMPANY / GROUP */
  orgType?: string
  sortOrder?: number
  status?: number
  createTime?: string
}

export interface SysRowPolicy {
  id?: string
  policyName?: string
  targetTable?: string
  /** 过滤表达式，支持 #{currentUserId}/#{currentOrgId} */
  expression?: string
  expressionDesc?: string
  roleId?: string
  userId?: string
  status?: number
  createTime?: string
}

export interface SysColumnPolicy {
  id?: string
  policyName?: string
  targetTable?: string
  columnName?: string
  /** MASK / ENCRYPT / HIDE / PLAIN */
  maskType?: string
  /** 脱敏规则，如 left:3,right:4 */
  maskRule?: string
  roleId?: string
  userId?: string
  status?: number
  createTime?: string
}

export interface SysTablePermission {
  id?: string
  datasourceId?: string
  databaseName?: string
  tableName?: string
  /** SELECT / INSERT / UPDATE / DELETE / ALL */
  permissionType?: string
  roleId?: string
  userId?: string
  /** ROLE / USER */
  grantType?: string
  status?: number
  createTime?: string
}

export interface UserPermissionDto {
  userId: string
  roles: string[]
  permissions: string[]
  menus: MenuDto[]
  /** 当前项目组角色合并后的能力位集合（如 module:config / quality:manage 等） */
  capabilityFlags?: string[]
  /** 当前用户加入的项目组（业务线-项目组） */
  projectGroups?: ProjectGroupDto[]
  /** 当前项目组 Id（主组） */
  projectGroupId?: string
}

/** 业务线-项目组（SgProjectGroup） */
export interface SgProjectGroup {
  id?: string
  orgId?: string
  projectName?: string
  projectCode?: string
  leaderId?: string
  status?: number
  sortOrder?: number
  createTime?: string
}

/** 用户所处项目组视图 */
export interface ProjectGroupDto {
  id?: string
  orgId?: string
  projectName?: string
  projectCode?: string
  /** 是否主组 */
  primary?: boolean
  roles?: string[]
  capabilityFlags?: string[]
}

/** 能力位常量（与后端 PermissionConstants 对齐） */
export const CAP = {
  /** 门户模块展示配置（管理员级，全局可插拔模块开关） */
  MODULE_CONFIG: 'module:config',
} as const

export interface MenuDto {
  id: string
  parentId?: string
  name: string
  path?: string
  component?: string
  permission?: string
  icon?: string
  /** 0:目录 1:菜单 2:按钮 3:API */
  type?: number
  sortOrder?: number
}

/* ==================== 审批 ==================== */

export interface ApplyRequest {
  /** 申请类型：TABLE / REPORT / DATASOURCE / MENU */
  applyType: string
  /** 申请目标ID（TABLE 时为数据源ID） */
  targetId?: string
  /** 申请目标名称（TABLE 时为 database.table） */
  targetName?: string
  reason?: string
}

export interface AppApply {
  id?: string
  applicantId?: string
  applyType?: string
  targetId?: string
  targetName?: string
  reason?: string
  /** 0:待审批 1:通过 2:驳回 3:已撤销 */
  status?: number
  currentApprover?: string
  createTime?: string
  updateTime?: string
}

export interface ApprovalActionRequest {
  approverId?: string
  comment?: string
}

export interface TransferRequest {
  toApproverId: string
  comment?: string
}

export interface AppApprovalRecord {
  id?: string
  applyId?: string
  approverId?: string
  /** 1:通过 2:驳回 3:转交 */
  action?: number
  comment?: string
  createTime?: string
}

export interface AppApprovalFlow {
  id?: string
  flowName?: string
  applyType?: string
  stepOrder?: number
  approverRole?: string
}

/* ==================== 数据源 ==================== */

export interface MetaDatasource {
  id?: string
  dsName?: string
  /** MYSQL / DM8 / HIVE / DORIS / CLICKHOUSE / POSTGRESQL / ORACLE / TRANSWARP */
  dsType?: string
  host?: string
  port?: number
  databaseName?: string
  username?: string
  /** 密码仅写入，接口不回传 */
  password?: string
  properties?: string
  status?: number
  createTime?: string
  updateTime?: string
}

export interface DsConfig {
  id?: string
  dsName?: string
  dsType?: string
  host?: string
  port?: number
  databaseName?: string
  username?: string
  password?: string
  properties?: string
}

export interface ColumnInfo {
  columnName?: string
  columnType?: string
  columnLength?: number
  columnScale?: number
  nullable?: boolean
  primaryKey?: boolean
  defaultValue?: string
  description?: string
  sortOrder?: number
  sensitiveLevel?: number
}

export interface QueryResult {
  columns: ColumnInfo[]
  rows: Record<string, any>[]
  rowCount?: number
  costMs?: number
  /** 是否写操作（DDL/DML 非查询） */
  update?: boolean
}

/* ==================== 元数据 ==================== */

export interface MetaDatabase {
  id?: string
  datasourceId?: string
  databaseName?: string
  description?: string
  syncTime?: string
}

export interface MetaTable {
  id?: string
  /** 数据库记录ID（meta_database.id） */
  databaseId?: string
  tableName?: string
  /** TABLE / VIEW */
  tableType?: string
  description?: string
  rowCount?: number
  tableSize?: number
  syncTime?: string
  updateTime?: string
}

export interface MetaColumn {
  id?: string
  /** 表记录ID（meta_table.id） */
  tableId?: string
  columnName?: string
  columnType?: string
  columnLength?: number
  columnScale?: number
  /** 是否可空 1:是 0:否 */
  isNullable?: number
  /** 是否主键 1:是 0:否 */
  isPrimaryKey?: number
  defaultValue?: string
  description?: string
  sortOrder?: number
  /** 敏感级别 0:普通 1:敏感 2:高度敏感 */
  sensitiveLevel?: number
  /** 业务名称（字段治理） */
  bizName?: string
  /** 业务说明（字段治理） */
  bizComment?: string
  /** 关联字典类型编码（数据治理） */
  dictTypeCode?: string
  /** 关联字典类型名称（冗余展示） */
  dictTypeName?: string
}

export interface MetaTableLineage {
  id?: string
  sourceTableId?: string
  targetTableId?: string
  sourceColumn?: string
  targetColumn?: string
  /** ETL / MANUAL */
  relationType?: string
}

/* ==================== 数据治理 ==================== */

export interface MetaDictType {
  id?: string
  /** 字典类型编码 */
  typeCode?: string
  /** 字典类型名称 */
  typeName?: string
  description?: string
  /** 1:启用 0:停用 */
  status?: number
  sortOrder?: number
  createTime?: string
}

export interface MetaDictItem {
  id?: string
  /** 所属字典类型ID */
  typeId?: string
  /** 字典项编码 */
  itemCode?: string
  /** 字典项名称 */
  itemName?: string
  /** 字典项值 */
  itemValue?: string
  /** 1:启用 0:停用 */
  status?: number
  sortOrder?: number
  description?: string
  createTime?: string
}

export interface MetaStandard {
  id?: string
  /** 标准编码 */
  stdCode?: string
  /** 标准名称 */
  stdName?: string
  /** 标准分类 */
  category?: string
  dataType?: string
  dataLength?: number
  dataPrecision?: number
  dataScale?: number
  unit?: string
  /** 取值范围/枚举 */
  enumRange?: string
  /** 格式/编码规则 */
  formatRule?: string
  description?: string
  /** 1:启用 0:停用 */
  status?: number
  sortOrder?: number
  createTime?: string
}

/* ==================== 数据集市 ==================== */

/** 模型市场卡片 DTO（仅已发布，含统计） */
export interface MarketModelDto {
  id?: string
  modelName?: string
  modelCode?: string
  /** STAR / SNOWFLAKE */
  modelType?: string
  datasourceId?: string
  description?: string
  /** 数仓分层ID */
  layerId?: string
  /** 数仓分层编码 ODS/DWD/DWS/ADS */
  layerCode?: string
  /** 数仓分层名称 */
  layerName?: string
  /** 项目组ID */
  projectGroupId?: string
  version?: number
  /** 指标数 */
  metricCount?: number
  /** 维度数 */
  dimensionCount?: number
  /** 关联数 */
  relCount?: number
  publishTime?: string
  updateTime?: string
}

export interface MartModel {
  id?: string
  modelName?: string
  /** 项目组ID（权限隔离） */
  projectGroupId?: string
  /** 数仓分层ID */
  layerId?: string
  modelCode?: string
  /** STAR / SNOWFLAKE */
  modelType?: string
  datasourceId?: string
  /** 主事实表 */
  factTable?: string
  description?: string
  /** 0:草稿 1:已发布 2:下线 */
  status?: number
  version?: number
  createTime?: string
  updateTime?: string
}

/** 数仓分层（ODS/DWD/DWS/ADS） */
export interface MartWarehouseLayer {
  id?: string
  layerCode?: string
  layerName?: string
  layerDesc?: string
  sortOrder?: number
  status?: number
}

export interface MartMetric {
  id?: string
  metricName?: string
  metricCode?: string
  /** ATOMIC / DERIVED */
  metricType?: string
  expression?: string
  dataType?: string
  unit?: string
  categoryId?: string
  modelId?: string
  description?: string
  status?: number
  createTime?: string
  updateTime?: string
}

export interface MartDimension {
  id?: string
  dimName?: string
  dimCode?: string
  /** COMMON / TIME / ORG */
  dimType?: string
  modelId?: string
  sourceTable?: string
  sourceColumn?: string
  description?: string
  status?: number
  createTime?: string
}

export interface MartDimLevel {
  id?: string
  dimId?: string
  levelName?: string
  levelColumn?: string
  levelOrder?: number
}

export interface MartModelRel {
  id?: string
  modelId?: string
  factTable?: string
  dimTable?: string
  joinKey?: string
  /** INNER / LEFT / RIGHT */
  joinType?: string
}

/* ==================== 语义查询（BI 画布） ==================== */

export interface MartQueryMetricRef {
  metricCode?: string
}

export interface MartQueryDimRef {
  dimCode?: string
  /** 时间维度粒度 Y / M / D */
  grain?: string
  /** 层级下钻列 */
  levelColumn?: string
}

export interface MartQueryFilterRef {
  dimCode?: string
  /** EQ / NE / GT / GTE / LT / LTE / LIKE / IN / NOT_IN / BETWEEN */
  operator?: string
  values?: string[]
}

export interface MartQuerySortRef {
  code?: string
  direction?: string
}

export interface MartQueryRequest {
  modelId: string
  metrics?: MartQueryMetricRef[]
  dimensions?: MartQueryDimRef[]
  filters?: MartQueryFilterRef[]
  sorts?: MartQuerySortRef[]
  limit?: number
}

export interface MartQueryResult {
  sql: string
  result: QueryResult
  truncated?: boolean
  originalRowCount?: number
}

/* ==================== 数据质量 ==================== */

export interface DqRule {
  id?: string
  /** 项目组ID（权限隔离） */
  projectGroupId?: string
  ruleCode?: string
  ruleName?: string
  /** NOT_NULL / UNIQUE / VALUE_RANGE / CUSTOM_SQL / CUSTOM_PROBE */
  ruleType?: string
  datasourceId?: string
  databaseName?: string
  tableName?: string
  columnName?: string
  /** 规则配置 JSON */
  ruleConfig?: string
  weight?: number
  alertThreshold?: number
  /** 0停用 1启用 */
  status?: number
  createBy?: string
  createTime?: string
}

export interface DqTask {
  id?: string
  projectGroupId?: string
  taskName?: string
  /** 关联规则ID集合（JSON数组） */
  ruleIds?: string
  cronExpr?: string
  status?: number
  createBy?: string
  createTime?: string
}

export interface DqSnapshot {
  id?: string
  projectGroupId?: string
  taskId?: string
  taskName?: string
  datasourceId?: string
  databaseName?: string
  tableName?: string
  score?: number
  ruleTotal?: number
  rulePass?: number
  ruleFail?: number
  detail?: string
  costMs?: number
  /** MANUAL / SCHEDULE */
  triggerType?: string
  createBy?: string
  createTime?: string
}

export interface DqRuleResult {
  id?: string
  snapshotId?: string
  ruleId?: string
  ruleName?: string
  ruleType?: string
  passed?: number
  actualValue?: number
  threshold?: number
  sampleRows?: string
  message?: string
}

/* ==================== SQL 查询 ==================== */

export interface QueryExecuteRequest {
  datasourceId: string
  databaseName?: string
  sql: string
}

export interface QueryHistory {
  id?: string
  userId?: string
  datasourceId?: string
  databaseName?: string
  sqlText?: string
  /** 1:成功 0:失败 */
  status?: number
  costMs?: number
  rowCount?: number
  errorMessage?: string
  createTime?: string
}

/* ==================== 可视化 ==================== */

/** 数据集 */
export interface VisualDataset {
  id?: string
  name: string
  code: string
  description?: string
  /** SQL / MODEL */
  datasetType: string
  datasourceId?: string
  querySql?: string
  modelId?: string
  refreshInterval?: number
  visibility?: string
  status?: string
  version?: number
  createBy?: string
  createTime?: string
  updateTime?: string
  fields?: DatasetField[]
  variables?: DatasetVariable[]
}

export interface DatasetField {
  id?: string
  fieldName: string
  fieldCode: string
  /** DIMENSION / METRIC */
  fieldType: string
  /** STRING / INTEGER / DECIMAL / DATE */
  dataType?: string
  /** SUM / AVG / COUNT / COUNT_DISTINCT / MAX / MIN */
  aggType?: string
  format?: string
  sortOrder?: number
}

export interface DatasetVariable {
  varName: string
  varType: string
  defaultValue: string
}

/** 分析板配置（一个仪表板可以包含多个分析板Tab） */
export interface DashboardBoard {
  id: string
  dashboardId?: string
  name: string
  /** 图标 */
  icon?: string
  /** 画布/布局模式: CANVAS(自由画布) / GRID(网格布局) */
  layoutMode?: string
  /** 画布宽度(自由画布模式) */
  canvasWidth?: number
  /** 画布高度(自由画布模式) */
  canvasHeight?: number
  /** 画布背景色 */
  canvasBg?: string
  /** 网格配置(JSON) */
  gridConfig?: string
  /** 分析板级筛选器(JSON) */
  filters?: string
  sortOrder?: number
  status?: number
  createTime?: string
  updateTime?: string
}

/** 组件数据配置（数据集+维度+指标+过滤） */
export interface ItemDataConfig {
  /** 数据集ID */
  datasetId?: string
  /** 数据集类型 */
  datasetType?: string
  /** 选中的维度字段列表 */
  dimensions?: ItemDimension[]
  /** 选中的指标字段列表 */
  metrics?: ItemMetric[]
  /** 过滤条件 */
  filters?: ItemFilter[]
  /** 排序 */
  sorts?: ItemSort[]
  /** 数据限制条数 */
  limit?: number
  /** 是否启用自定义SQL */
  useCustomSql?: boolean
  /** 自定义SQL（SQL数据集模式） */
  customSql?: string
}

export interface ItemDimension {
  fieldCode: string
  fieldName: string
  dateFormat?: string
  sort?: string
}

export interface ItemMetric {
  fieldCode: string
  fieldName: string
  aggType: string
  expression?: string
  format?: string
  decimalDigits?: number
}

export interface ItemFilter {
  fieldCode: string
  /** EQ / NE / GT / LT / GTE / LTE / IN / NOT_IN / LIKE / BETWEEN / IS_NULL / IS_NOT_NULL */
  operator: string
  values?: any[]
  isVariable?: boolean
  variableName?: string
}

export interface ItemSort {
  fieldCode: string
  /** ASC / DESC */
  direction: string
}

/** 组件样式配置 */
export interface ItemStyleConfig {
  title?: TitleStyle
  legend?: LegendStyle
  colorTheme?: string
  bgColor?: string
  borderRadius?: number
  borderWidth?: number
  borderColor?: string
  showGrid?: boolean
  labelShow?: boolean
  labelPosition?: string
  tooltipShow?: boolean
  valueFormat?: string
  decimalDigits?: number
  xAxis?: AxisStyle
  yAxis?: AxisStyle
  padding?: number
  tableStyle?: TableStyleConfig
}

export interface TitleStyle {
  show?: boolean
  text?: string
  fontSize?: number
  /** left / center / right */
  align?: string
  color?: string
  fontWeight?: string
  subtext?: string
}

export interface LegendStyle {
  show?: boolean
  position?: string
}

export interface AxisStyle {
  show?: boolean
  name?: string
  fontSize?: number
  rotate?: number
}

export interface TableStyleConfig {
  pageSize?: number
  showBorder?: boolean
  stripe?: boolean
  fitColumn?: boolean
  fixedHeader?: boolean
}

export interface VisualDashboard {
  id?: string
  name?: string
  description?: string
  layout?: string
  refreshInterval?: number
  /** 0:停用 1:草稿 2:已上线 */
  status?: number
  /** 当前版本号 */
  version?: number
  createBy?: string
  createTime?: string
  updateTime?: string
}

/** 组件图表类型 */
export type ChartType =
  | 'BAR'
  | 'LINE'
  | 'PIE'
  | 'SCATTER'
  | 'HEATMAP'
  | 'AREA'
  | 'GAUGE'
  | 'TREEMAP'
  | 'BOXPLOT'
  | 'MAP'
  | 'TABLE'
  | 'NUMBER'
  | 'FUNNEL'
  | 'RADAR'

export interface VisualDashboardItem {
  id?: string
  dashboardId?: string
  /** 所属分析板ID */
  boardId?: string
  title?: string
  /** BAR / LINE / PIE / SCATTER / HEATMAP / AREA / GAUGE / TREEMAP / BOXPLOT / MAP / TABLE / NUMBER */
  chartType?: ChartType
  /** 兼容旧版：直接数据源ID（推荐使用dataConfig） */
  datasourceId?: string
  /** 兼容旧版：直接SQL（推荐使用dataConfig.customSql） */
  querySql?: string
  /** 数据配置(JSON)：数据集+维度+指标+过滤 */
  dataConfig?: string
  /** 样式配置(JSON)：标题+图例+颜色主题+边框 */
  styleConfig?: string
  /** 下钻明细SQL（可选，配置后点击图表下钻） */
  drillSql?: string
  /** 兼容旧版：组件配置(JSON)：xAxisColumn / seriesColumn / valueColumn */
  config?: string
  /** 组件位置(像素级) */
  posX?: number
  posY?: number
  width?: number
  height?: number
  /** 组件层级 */
  zIndex?: number
  /** 组件样式(前端运行时) */
  bgColor?: string
  borderRadius?: number
  borderWidth?: number
  borderColor?: string
  locked?: number
  visible?: number
  createTime?: string
  updateTime?: string
  /** 运行时展开的数据配置对象 */
  _dataConfigObj?: ItemDataConfig
  /** 运行时展开的样式配置对象 */
  _styleConfigObj?: ItemStyleConfig
}

export interface VisualDashboardVersion {
  id?: string
  dashboardId?: string
  version?: number
  name?: string
  description?: string
  layout?: string
  refreshInterval?: number
  /** 组件快照(JSON数组字符串) */
  itemsJson?: string
  /** 分析板快照(JSON数组字符串) */
  boardsJson?: string
  remark?: string
  createBy?: string
  createTime?: string
}

/** 分析板（仪表板内嵌套子业务模块） */
export interface VisualBoard {
  id?: string
  dashboardId?: string
  boardName?: string
  subtitle?: string
  icon?: string
  /** ANALYSIS / CUSTOM */
  boardType?: string
  /** 板块样式与布局配置(JSON) */
  layout?: string
  /** 分析板独立筛选配置(JSON) */
  filters?: string
  /** 是否联动全局筛选 1:联动 0:独立 */
  linkGlobal?: number
  /** 自动刷新周期(秒) */
  refreshInterval?: number
  /** 0:展开 1:折叠 */
  collapse?: number
  /** 布局锁定 */
  locked?: number
  sortOrder?: number
  status?: number
  createTime?: string
  updateTime?: string
}

export interface AdhocQueryRequest {
  datasourceId: string
  sql: string
  params?: Record<string, any>
  adhocId?: string
}

/** 即席查询执行响应 */
export interface AdhocExecuteResponse {
  result: QueryResult
  truncated: boolean
  originalRowCount: number
}

/** 收藏的即席查询 */
export interface VisualAdhocQuery {
  id?: string
  name?: string
  datasourceId?: string
  sqlText?: string
  params?: Record<string, any>
  folder?: string
  createBy?: string
  createTime?: string
  updateTime?: string
}

/** 即席查询执行历史 */
export interface VisualAdhocHistory {
  id?: string
  adhocId?: string
  userId?: string
  datasourceId?: string
  sqlText?: string
  status?: number
  costMs?: number
  rowCount?: number
  errorMessage?: string
  createTime?: string
}

/* ==================== 调度 ==================== */

export interface ScheduleJob {
  id?: string
  jobName?: string
  /** SQL / QUALITY / SYNC / REFRESH / PUSH */
  jobType?: string
  jobGroup?: string
  cronExpression?: string
  datasourceId?: string
  targetSql?: string
  targetTable?: string
  params?: string
  /** 0:停用 1:启用 */
  status?: number
  retryCount?: number
  retryInterval?: number
  timeout?: number
  createTime?: string
  updateTime?: string
}

/** 调度任务简要信息（供质量任务页面展示关联关系） */
export interface ScheduleJobBrief {
  jobId?: string
  jobName?: string
  jobType?: string
  cronExpression?: string
  /** 0:停用 1:启用 */
  status?: number
  /** 关联的质量任务ID */
  taskId?: string
}

export interface ScheduleJobLog {
  id?: string
  jobId?: string
  /** 0:运行中 1:成功 2:失败 */
  status?: number
  startTime?: string
  endTime?: string
  durationMs?: number
  errorMessage?: string
  resultRows?: number
}

export interface ScheduleJobDep {
  id?: string
  jobId?: string
  depJobId?: string
  /** HARD / SOFT */
  depType?: string
}

/* ==================== 通知 ==================== */

export interface NotifyTemplate {
  id?: string
  templateCode?: string
  templateName?: string
  /** MAIL / SITE / WECHAT / SMS */
  channel?: string
  titleTemplate?: string
  contentTemplate?: string
  status?: number
  createTime?: string
}

export interface SendRequest {
  templateCode: string
  /** USER 为用户ID；ROLE/ORG 为角色/组织ID */
  receiverId: string
  /** USER / ROLE / ORG */
  receiverType: string
  /** 渠道（缺省取模板渠道） */
  channel?: string
  /** 模板变量（如 title / content / jobName / message） */
  vars?: Record<string, any>
}

export interface NotifyRecord {
  id?: string
  templateId?: string
  receiverId?: string
  receiverType?: string
  channel?: string
  title?: string
  content?: string
  /** 0:待发送 1:已发送 2:发送失败 */
  status?: number
  sendTime?: string
  errorMessage?: string
  createTime?: string
}

export interface NotifySubscription {
  id?: string
  userId?: string
  /** REPORT / METRIC / JOB */
  subscribeType?: string
  targetId?: string
  channel?: string
  status?: number
  createTime?: string
}

/* ==================== 数据管道 ==================== */

export interface PipelineTask {
  id?: string
  taskName?: string
  /** SYNC=同步 ETL=加工 */
  taskType?: string
  /** 执行引擎 DB_SYNC/DATAX/SEATUNNEL */
  engine?: string
  sourceDsId?: string
  sourceTable?: string
  targetDsId?: string
  targetTable?: string
  /** 源查询（自定义 SQL，选填） */
  sourceQuery?: string
  /** 字段映射（JSON，选填） */
  fieldMapping?: string
  /** 引擎扩展配置（JSON） */
  config?: string
  /** 定时表达式（空=仅手动） */
  cronExpr?: string
  /** 归属项目组（业务线-项目组隔离） */
  projectGroupId?: string
  /** 1=启用 0=停用 */
  status?: number
  createBy?: string
  createTime?: string
  updateTime?: string
}

export interface PipelineEngineInfo {
  type: string
  name: string
  available: boolean
}

export interface PipelineInstance {
  id?: string
  taskId?: string
  engine?: string
  /** MANUAL / CRON */
  triggerType?: string
  /** 0=运行中 1=成功 2=失败 */
  status?: number
  startTime?: string
  endTime?: string
  durationMs?: number
  rows?: number
  errorMessage?: string
  worker?: string
}

/* ==================== 门户可插拔模块配置 ==================== */

/** 模块归属分类（与后端 / 前端注册表一致） */
export type ModuleCategory =
  | 'ingress' // 数据接入
  | 'dev' // 数据开发
  | 'govern' // 数据治理
  | 'asset' // 数据资产
  | 'service' // 数据服务
  | 'ops' // 监控运维
  | 'system' // 系统管理

/** 门户模块展示配置（module_display_config 表实体） */
export interface ModuleDisplayConfig {
  /** 模块唯一标识（主键，与前端注册表 moduleKey 对应） */
  moduleKey: string
  /** 模块展示名称 */
  moduleName: string
  /** 归属分类 */
  category: ModuleCategory
  /** 分类展示名 */
  categoryName?: string
  /** 图标 */
  icon?: string
  /** 路由路径（空=待建设） */
  path?: string
  /** 1=系统必须，不可取消 0=可配置 */
  mandatory?: number
  /** 1=显示 0=隐藏 */
  visible?: number
  sortOrder?: number
  createdTime?: string
  updatedTime?: string
}

/* ==================== 工作流编排 ==================== */

/** 工作流定义 */
export interface WorkflowDef {
  id?: string
  name: string
  code?: string
  cronExpression?: string
  /** 0:禁用/草稿 1:启用 */
  status?: number
  description?: string
  owner?: string
  /** 运行参数模板(JSON) */
  params?: string
  createTime?: string
  updateTime?: string
}

/** 工作流节点 */
export interface WorkflowNode {
  id?: string
  wfId?: string
  nodeCode: string
  nodeName: string
  /** SQL / SHELL / PYTHON / DATA_OP */
  nodeType: string
  configJson?: string
  posX?: number
  posY?: number
  timeout?: number
  retryCount?: number
  retryInterval?: number
}

/** 工作流依赖边 */
export interface WorkflowEdge {
  id?: string
  wfId?: string
  fromCode: string
  toCode: string
  conditionExpr?: string
}

/** 工作流图（保存/详情） */
export interface WorkflowGraph {
  def: WorkflowDef
  nodes: WorkflowNode[]
  edges: WorkflowEdge[]
}

/** 工作流运行实例 */
export interface WorkflowRun {
  id?: string
  wfId?: string
  wfName?: string
  triggerType?: string
  runStatus?: string
  triggerParams?: string
  startTime?: string
  endTime?: string
  durationMs?: number
  errorMessage?: string
  createTime?: string
}

/** 节点运行实例 */
export interface WorkflowNodeRun {
  id?: string
  runId?: string
  wfId?: string
  nodeCode?: string
  nodeName?: string
  nodeType?: string
  status?: string
  startTime?: string
  endTime?: string
  durationMs?: number
  resultRows?: number
  logText?: string
  errorMessage?: string
  createTime?: string
}
