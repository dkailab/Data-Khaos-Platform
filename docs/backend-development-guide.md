# Data Khaos 后端开发规范手册

> 版本：v1.0.0　适用：Data Khaos 全部后端模块
> 本文档根据当前项目实际代码沉淀而成，**后续所有后端开发必须遵循**。规范与代码冲突时，以本文档为准并同步修订代码。

---

## 1. 技术栈与运行环境

| 项 | 约定 | 说明 |
|----|------|------|
| JDK | **Java 17** | 全模块统一 |
| 构建 | **Maven 3.11+** | 父 POM 统一管理版本 |
| Spring Boot | **3.2.0** | 使用 jakarta 命名空间 |
| Spring Cloud | **2023.0.0** | 网关基于 WebFlux（非 servlet） |
| Spring Cloud Alibaba | **2023.0.3.3** | Nacos 注册发现 |
| MyBatis-Plus | **3.5.5**（`mybatis-plus-spring-boot3-starter`） | ORM |
| 工具包 | **Hutool 5.8.25**（`cn.hutool`） | `StrUtil` 等 |
| API 文档 | **Knife4j 4.4.0**（OpenAPI3） | `@Tag` / `@Operation` |
| Lombok | provided 作用域 | 全模块共享，不打包进 jar |
| 数据库 | 开发 MySQL 8 / 生产达梦 DM8 | 通过 `data-khaos.mybatis.db-type` 切换方言 |

所有版本号在根 [pom.xml](../pom.xml) 的 `<properties>` / `<dependencyManagement>` 中统一声明，**禁止在子模块写死版本**。

---

## 2. 模块划分与职责

### 2.1 模块类型

| 类型 | 约定 | 例子 |
|------|------|------|
| **common** | 公共能力，被所有模块依赖 | `data-khaos-common` |
| **业务服务** | 独立可运行微服务，`*Application.java` 启动 | `data-khaos-auth` / `data-khaos-visual` 等 |
| **`-api` 契约模块** | 只放 DTO / SPI 接口 / REST Client，**不引入 mybatis、不建表**，供下游复用 | `data-khaos-datasource-api` / `data-khaos-permission-api` |
| **网关** | WebFlux 非阻塞，仅做路由 + 鉴权 + 限流 | `data-khaos-gateway` |

### 2.2 包结构（业务服务）

每个业务服务统一 `com.datakhaos.<service>` 下分层：

```
com.datakhaos.visual
├── VisualApplication.java   # 启动类（模块名 + Application）
├── controller/              # 接口层，仅做参数接收与返回包装
├── service/                 # 业务逻辑层（核心）
├── mapper/                  # MyBatis-Plus 接口
├── entity/                  # 数据库实体（继承 BaseEntity）
├── dto/                     # 请求/响应传输对象
└── config/                  # 本服务配置类（如有）
```

### 2.3 依赖方向

- 业务服务 → `data-khaos-common`（必）→ 其他 `*-api` 契约模块（按需）
- **禁止**业务服务直接依赖另一个业务服务；跨服务一律走 `*-api` 的 REST Client
- 网关不依赖任何业务服务，仅依赖 common

---

## 3. 统一返回与分页

### 3.1 统一返回 `R<T>`

所有 Controller 方法**必须**返回 `com.datakhaos.common.model.R<T>`：

```java
@GetMapping("/foo/{id}")
public R<Foo> foo(@PathVariable String id) {
    return R.ok(service.get(id));
}
```

- 成功：`R.ok(data)` / `R.ok()` / `R.ok(msg, data)`
- 失败：**不要**在 Controller 里 `R.fail(...)`，直接抛 `BusinessException`，由全局异常处理器统一转换
- 文件下载等特殊响应（如 CSV 导出）可用 `ResponseEntity<String>`，属唯一例外

### 3.2 状态码 `ResultCode`

已定义的状态码见 [ResultCode.java](../data-khaos-common/src/main/java/com/datakhaos/common/model/ResultCode.java)。**优先复用，不新增散落数字**：

| code | 含义 |
|------|------|
| 0 | 成功 |
| 400 | 参数校验失败 |
| 401 | 未登录/过期 |
| 403 | 无权限 |
| 404 | 资源不存在 |
| 500 | 操作失败 |
| 5000 | 系统异常 |
| 5001 | 业务异常（默认） |
| 5002 | 数据已存在 |
| 4010/4011 | 令牌无效/过期 |

### 3.3 分页 `PageResult<T>`

分页查询统一返回 `com.datakhaos.common.model.PageResult<T>`，用静态工厂构造：

```java
public PageResult<Foo> page(long current, long size, String keyword) {
    var result = fooMapper.selectPage(
        com.baomidou.mybatisplus.extension.plugins.pagination.Page.of(current, size),
        new LambdaQueryWrapper<Foo>()
            .like(StrUtil.isNotBlank(keyword), Foo::getName, keyword)
            .orderByDesc(Foo::getCreateTime));
    return PageResult.of(current, size, result.getTotal(), result.getRecords());
}
```

Controller 分页入参统一形如 `@RequestParam(defaultValue = "1") long current`、`@RequestParam(defaultValue = "10") long size`。

---

## 4. 实体与数据库约定

### 4.1 实体基类

实体继承 `com.datakhaos.common.entity.BaseEntity`（雪花 ID + createTime）：

```java
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("visual_dashboard")
public class VisualDashboard extends BaseEntity {
    private String name;
    // ... 业务字段

    /** 仅当表含 update_time 列时补充 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
```

- 主键：雪花算法 `@TableId(type = IdType.ASSIGN_ID)`，类型 `String`，对应库表 `VARCHAR(32)`（避免达梦自增序列性能问题）
- `createTime` 由 `BaseEntity` 提供并自动填充（INSERT）
- `updateTime` **仅当表含该列时**在子类补充，配 `@TableField(fill = FieldFill.INSERT_UPDATE)`（填充逻辑见 [MybatisPlusConfig.java](../data-khaos-common/src/main/java/com/datakhaos/common/config/MybatisPlusConfig.java)）
- 必须 `@EqualsAndHashCode(callSuper = true)`，否则继承字段不参与 equals/hashCode

### 4.2 通用字段约定

| 字段 | 命名 | 类型 | 说明 |
|------|------|------|------|
| 主键 | `id` | `String` | 雪花，VARCHAR(32) |
| 创建时间 | `createTime` | `LocalDateTime` | 自动填充 |
| 更新时间 | `updateTime` | `LocalDateTime` | 自动填充（可省） |
| 创建人 | `createBy` | `String` | 用户 ID，手动设置 |
| 状态 | `status` | `Integer` | 语义在字段注释中说明（如 0停用/1草稿/2已上线） |
| 删除标记 | 用 `is_deleted` 逻辑删除时遵守 `CommonConstants.DELETED/NOT_DELETED` | | |

- 状态/类型的数值语义**必须在字段 Javadoc 中写明**
- JSON 配置字段用 `String` 存储（如 `layout`、`config`、`*Json`），不落库为对象

### 4.3 常量

业务常量放 `CommonConstants`（全局）或服务内常量类，**禁止魔法数字/字符串散落**。已定义：`AUTH_HEADER`、`HEADER_USER_*`、`SUPER_ADMIN`、`DEFAULT_ADMIN_ID`、`STATUS_ENABLE/DISABLE` 等。

---

## 5. 异常处理

### 5.1 业务异常

业务失败统一抛 `com.datakhaos.common.exception.BusinessException`：

```java
throw new BusinessException("查询名称不能为空");                       // 默认 5001
throw new BusinessException(ResultCode.NOT_FOUND, "版本不存在: " + id); // 指定状态码
```

- 消息要**具体、可操作**，包含关键上下文（如 id、名称），方便定位
- 不要 throw `RuntimeException` / `Exception`（会被兜底为 5000，信息被吞）

### 5.2 全局异常处理器

[GlobalExceptionHandler.java](../data-khaos-common/src/main/java/com/datakhaos/common/exception/GlobalExceptionHandler.java) 已统一处理：
- `BusinessException` → 返回其 code/msg
- 参数校验异常（`MethodArgumentNotValidException` / `BindException` / `ConstraintViolationException`）→ 400
- `IllegalArgumentException` → 400
- 其他 `Exception` → 5000 "系统繁忙"，并 `log.error` 打印堆栈

业务服务**不要**再自建 `@RestControllerAdvice`，统一复用 common 的。

---

## 6. Controller 分层规范

Controller 职责：**接收参数 → 调 service → 返回 `R<T>`**，不写业务逻辑。

```java
@Tag(name = "可视化引擎")
@RestController
@RequestMapping("/api/visual")   // 路径前缀 = 该服务在网关的路由前缀
@RequiredArgsConstructor
public class VisualController {

    private final VisualService visualService;

    @Operation(summary = "分页查询仪表板")
    @GetMapping("/dashboard/page")
    public R<PageResult<VisualDashboard>> dashboardPage(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String keyword) {
        return R.ok(visualService.dashboardPage(current, size, keyword));
    }
}
```

约定：
- 类注解 `@RestController` + `@RequestMapping("/api/<service>")` + `@Tag` + `@RequiredArgsConstructor`（**构造器注入**，禁止 `@Autowired` 字段注入）
- 方法注解 `@Operation(summary = "...")`，**每个接口必须有**，用于 Knife4j 文档
- 路径前缀必须与网关路由一致（见 [application.yml](../data-khaos-gateway/src/main/resources/application.yml)，如 `/api/visual/**` → visual 服务）
- RESTful 语义：GET 查询 / POST 新增 / PUT 修改 / DELETE 删除；有 id 则更新的"新增或修改"用 POST
- 资源路径用名词复数，`{id}` 走 `@PathVariable`，查询参数走 `@RequestParam`
- 复杂请求体用 `@RequestBody DTO`，**不要**直接暴露 entity 时另议（当前项目部分直接用了 entity，新代码优先用 DTO）

---

## 7. Service 分层规范

Service 是**业务核心**，包含校验、事务、防注入、权限、跨服务调用。

### 7.1 基本结构

```java
@Service
@RequiredArgsConstructor
public class VisualService {
    private final VisualDashboardMapper dashboardMapper;
    private final DatasourceApiClient datasourceApiClient;
    // ...
}
```

- 构造器注入（`@RequiredArgsConstructor` + `final` 字段）
- Mapper 注入用接口，CRUD 用 MyBatis-Plus 的 `LambdaQueryWrapper` / `BaseMapper` 方法
- 写操作需保证原子性时加 `@Transactional(rollbackFor = Exception.class)`（**必须指定 rollbackFor**，否则默认只回滚 RuntimeException）

### 7.2 事务边界

- 多表写、级联删除、版本快照等必须事务
- 事务方法内**不要**吞异常；跨服务调用（REST）失败需自行决定是否抛 `BusinessException` 触发回滚

### 7.3 跨服务调用

通过 `*-api` 模块的 REST Client，基于 `@LoadBalanced` 的 `lbRestTemplate` 走 Nacos 负载均衡：

```java
// DatasourceApiClient 示例
public R<QueryResult> executeRaw(String dsId, String sql) {
    try {
        Map<String, String> body = new HashMap<>();
        body.put("sql", sql);
        ResponseEntity<R<QueryResult>> resp = restTemplate.exchange(
            "http://data-khaos-datasource/api/ds/{id}/execute",
            HttpMethod.POST, new HttpEntity<>(body),
            new ParameterizedTypeReference<R<QueryResult>>() {}, dsId);
        return resp.getBody();
    } catch (Exception e) {
        log.warn("调用数据源服务执行 SQL 失败: {}", e.getMessage());
        return R.fail("调用数据源服务执行 SQL 失败: " + e.getMessage());
    }
}
```

约定：
- 服务地址用 `http://<注册中心服务名>/<路由前缀>...`，如 `http://data-khaos-datasource/api/ds`
- 泛型反序列化必须用 `ParameterizedTypeReference<R<T>>`
- 调用需感知错误时用返回原始 `R<T>` 的方法（如 `executeRaw`）；容忍失败用返回默认值的方法
- 跨服务调用失败要 `log.warn` 记录，不静默

### 7.4 新增一个 `-api` 模块的固定套路

1. 建 `data-khaos-xxx-api`，包 `com.datakhaos.xxx.api`
2. 只放 `model/`（DTO）、可选 `service/`（Client）、`*AutoConfiguration.java`
3. 配 `resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 注册装配类
4. Client 注入 `@Qualifier("lbRestTemplate") RestTemplate`，加 `@ConditionalOnMissingBean`（参考 [DatasourceApiClient / DatasourceApiAutoConfiguration](../data-khaos-datasource-api/src/main/java/com/datakhaos/datasource/api/connector/DatasourceApiAutoConfiguration.java)）

---

## 8. 安全与防注入（强制）

### 8.1 SQL 审核

用户提交的 SQL 执行前**必须**过 `com.datakhaos.common.security.SqlAuditUtil.audit(sql)`：
- 拒绝多条语句（堆叠注入）
- 拒绝 DDL / 危险操作（CREATE/ALTER/DROP/TRUNCATE/GRANT 等）
- 仅允许 SELECT/WITH/SHOW/DESC/EXPLAIN/INSERT/UPDATE/DELETE/REPLACE

```java
String sql = SqlAuditUtil.audit(request.getSql());
```

### 8.2 参数占位符

SQL 中 `${param}` 占位符由后端解析，**禁止**直接拼接用户输入：

```java
// 数字原样替换，字符串加单引号并转义单引号；缺失参数直接报错
private String resolveParams(String sql, Map<String, Object> params) { /* ... */ }
private String renderParamValue(Object val) {
    String s = String.valueOf(val);
    if (s.matches("-?\\d+(\\.\\d+)?")) return s;          // 数字原样
    return "'" + s.replace("'", "''") + "'";              // 字符串转义
}
```

### 8.3 动态 WHERE 拼装（白名单 + 转义）

任何基于用户输入动态拼 WHERE 的地方（如分析板筛选）：

```java
private String applyFilters(String sql, String filtersJson) {
    // 字段名过白名单：仅字母数字下划线(含中文)
    String column = safeColumn(field);   // col.replaceAll("[^a-zA-Z0-9_\\u4e00-\\u9fa5]", "")
    // 值统一单引号转义
    String value = String.valueOf(rawVal).replace("'", "''");
    // 包装：SELECT * FROM (<原SQL>) t WHERE <条件>
}
```

**两条铁律**：字段名白名单校验（`safeColumn`）+ 值单引号转义（`escape`/`renderParamValue`）。过滤器只允许 `eq/ne/gt/gte/lt/lte/contains/in` 固定操作符集合。

### 8.4 表权限

涉及用户数据访问时校验表权限（超级管理员跳过）：

```java
if (permissionCheck && !MetadataHolder.isSuperAdmin() && StrUtil.isNotBlank(userId)) {
    boolean allowed = permissionApiClient.checkTablePermission(userId, datasourceId, db, table, "SELECT");
    if (!allowed) throw new BusinessException("没有对表 " + table + " 的查询权限");
}
```

---

### 8.5 项目组权限（组织 → 项目组 → 人）

自 v1.0.0 起，新增 **组织(业务线) → 项目组 → 人** 三级模型。人加入项目组即获得组内角色能力位（操作权限）与组下资源（数据权限）。

**关键表**（见 [mysql-init.sql](../db/mysql-init.sql)）：

| 表 | 作用 |
|---|---|
| `sg_project_group` | 项目组（`org_id` 归属组织，`leader_id` 组长） |
| `sg_project_group_member` | 成员（`project_role_id` 组内角色，`is_primary` 主组标记） |
| `sg_project_role` | 组角色（`capability_flags` 为**能力位 JSON 数组**；`project_group_id` 空=全局模板） |
| `sg_project_group_resource` | 组下资源（`resource_type` TASK/REPORT/TABLE） |

**能力位合并**：用户当前项目组角色的 `capability_flags` 并入 `UserPermissionDto.permissions`，供前端显隐菜单/按钮。相关实现见 [ProjectGroupService.java](../data-khaos-permission/src/main/java/com/datakhaos/permission/service/ProjectGroupService.java)：

```java
// 当前项目组上下文（主组，无则取第一个）
ProjectGroupDto current = projectGroupService.getCurrentProjectGroup(userId);
List<String> flags = projectGroupService.getCapabilityFlags(userId, current.getId());
```

**表权限主体取并集**：`sys_table_permission` 支持 `user_id`（个人）+ `role_id`（角色）+ `project_group_id`（项目组）三种主体，判定时取**并集**；个人授权优先级最高（用于收敛/放行个别成员）。已集成进 [TablePermissionService.java](../data-khaos-permission/src/main/java/com/datakhaos/permission/service/TablePermissionService.java) 的 `check` / `getUserTablePermissions`。

**能力位清单**（`org_id` 空表 `capability_flags` 模板）：`meta:browse`、`query:execute`、`table:manage`、`model:develop/publish/browse`、`report:develop/publish/browse`、`task:develop/schedule`、`approval:apply/approve`、`pg:manage`。

**约定**：
- 能力位以 JSON 数组字符串存储，读取用 `JSONUtil.parseArray` 解析成去重、保序集合（见 `ParseFlags`）。
- 行/列策略（`sys_row_policy` / `sys_column_policy`）同样支持 `project_group_id` 维度，表达式变量扩展 `#{currentProjectGroupId}`。
- 涉及用户数据访问时按「当前项目组上下文」过滤，前端传 `user` 参数一律不信，以 `MetadataHolder.getUserId()` 为准，再据此取当前项目组。

---

## 9. 用户上下文（MetadataHolder）

网关校验 JWT 后透传 `X-User-Id / X-Username` 等头，各服务经 `MetadataContextFilter` 解析进 `ThreadLocal`，使用 `com.datakhaos.common.security.MetadataHolder` 读取：

```java
String userId = MetadataHolder.getUserId();        // 可能为 null
String username = MetadataHolder.getUsername();
boolean isAdmin = MetadataHolder.isSuperAdmin();
```

- 数据隔离（如"我的收藏/我的历史"）查询时**必须**按 `MetadataHolder.getUserId()` 过滤，禁止跨用户泄露
- 前端传 user 参数一律不信，以 `MetadataHolder` 为准

---

## 10. 日志规范

- 用 Lombok `@Slf4j`
- 业务异常：`log.warn("...: code={}, msg={}", ...)`
- 跨服务调用失败：`log.warn("...失败: {}", e.getMessage())`
- 系统异常：`log.error("...", e)`（带堆栈）
- 日志消息**不拼接敏感信息**（密码、token 明文）

---

## 11. 网关约定

- 网关基于 **Spring Cloud Gateway（WebFlux）**，无 servlet，不引入 mybatis/lbRestTemplate
- 路由：`/api/<service>/**` → `lb://<服务注册名>`，见 [application.yml](../data-khaos-gateway/src/main/resources/application.yml)
- 鉴权：新增**公开接口**需加入 `AuthGlobalFilter.WHITE_LIST`（如登录、验证码）；其余默认校验 JWT
- 限流：`/api/query/**` 已接 Redis `RequestRateLimiter`（默认 10 req/s），新增高频接口可仿照
- 新增服务时：网关加路由 + 服务自身 `@RequestMapping("/api/<service>")` + knife4j 文档统一经网关 `/doc.html` 聚合

---

## 12. 配置与部署约定

- 配置走 `application.yml`，敏感项用环境变量占位带默认值：`${JWT_SECRET:default}`、`${MYSQL_HOST:127.0.0.1}`
- 服务端口：开发环境见 [docker-compose.yml](../docker/docker-compose.yml)；网关对外端口变更需**同步**前端 `vite.config.ts` 代理、`smoke-test.sh`、各文档
- 数据库方言切换：`data-khaos.mybatis.db-type`（MYSQL/DM）
- 生产达梦：`mvn clean install -Pprod` 引入 `DmJdbcDriver18`

---

## 13. 命名与代码风格速查

| 项 | 约定 |
|----|------|
| 包名 | `com.datakhaos.<service>`，层名 `controller/service/mapper/entity/dto/config` |
| 类名 | 业务名 + `Controller/Service/Mapper/Application`；`-api` 叫 `XxxApiClient` |
| Mapper | 接口继承 `BaseMapper<T>`，命名 `XxxMapper` |
| 启动类 | `XxxApplication`，`@SpringBootApplication` |
| controller 方法 | 动词+资源，如 `savedAdhoc`、`dashboardPage` |
| 常量 | 接口/类持有 `public static final`，`SCREAMING_SNAKE` |
| 注入 | 构造器注入 + `final` |
| 事务 | `@Transactional(rollbackFor = Exception.class)` |
| 文档 | 类/方法加 Javadoc，字段加 `/** */` 注释，中文 |

---

## 14. 开发清单（Checklist）

新增/修改后端功能时逐项自查：

- [ ] 返回类型用 `R<T>`，分页用 `PageResult<T>`
- [ ] 业务失败抛 `BusinessException`，自定义状态码
- [ ] 实体继承 `BaseEntity`，带 `@EqualsAndHashCode(callSuper = true)`，状态语义有注释
- [ ] Controller 有 `@Tag`、方法有 `@Operation(summary=...)`
- [ ] Controller 只收参调 service，无业务逻辑
- [ ] Service 构造器注入，写操作视需要加事务
- [ ] 用户数据隔离按 `MetadataHolder.getUserId()`
- [ ] 用户 SQL 过 `SqlAuditUtil.audit`；动态 WHERE 字段白名单 + 值转义
- [ ] 跨服务走 `*-api` Client，失败有日志
- [ ] 新增公开接口加入网关白名单
- [ ] 魔法数字/字符串抽常量
- [ ] 日志分级正确，不泄敏感信息