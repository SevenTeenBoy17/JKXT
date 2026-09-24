# G0 运行时路由与静态追溯差异复核

## 结论与证据边界

本次只读复核证明：静态清单中的 **188 条业务 method/path/controller#handler 均已在隔离 Spring 实例注册**，没有发现静态业务路由漏注册或 handler 不一致。运行时的 **194 行**为上述 188 行加 **6 行框架设施**，不是新增 6 个业务功能。按 method/path 去重为 **193 项**，因为 `/error` 有两个不同内容协商 handler。

这不是 188 项功能验收通过，不证明所有权限、事务、数据库结果、导出内容和前端控件正确。84 项业务场景的执行状态仍应由逐项证据维护，不能根据路由注册自动转为 PASS。本复核未改业务源码、数据库保护、原始矩阵或既有证据。

| 证据 | 本次核对值 |
| --- | --- |
| 仓库 | `D:\VB\JKXT-release-g0-g1` |
| 实际 HEAD | `1d023c211cc222a8582e72f24b4c973d1199b086` |
| 实际隔离轮次 | `release_g1_20260924_r1` |
| 测试源码摘要 | `d56b000dd51eb95c1d17f90dea3b59d6eb075b5b6f469700af143173030be17a` |
| Runtime 文件 | `docs/release/evidence/release_g1_20260924_r1/spring-mappings.json` |
| Runtime 文件 SHA-256 | `1c4d41f7761351ae8133627f8ba1765c7d006bf83f2b8ea026c85129233681f9` |
| Runtime 采集时间 | `2026-09-24T07:47:28.949844900Z` |
| Spring collector | `RegisteredApiInventoryTest.recordsActualMappingsAndIsolatedStorageAssertions`，真实 `RequestMappingHandlerMapping` |
| 隔离存储声明及测试断言 | DB `jkxt_test_release_g1_20260924_r1`；Flyway V30；失败迁移 0；Redis PONG；Tomcat 监听 `127.0.0.1` |
| 静态清单 | `docs/release/inventory/static-routes.json`，188 行 |
| 静态清单 SHA-256 | `c4793ded1799fad7be16783ebf57f307ac829dcad6803c923b07a45f281d1cbf` |
| 比较口径 | 每行 method、完整 path、controller 简名和 handler 方法名；188/188 匹配，缺失 0 |

静态 inventory 的生成时间早于 r1，不能声称它携带相同工作树摘要。本次直接重新比对两份文件并核查当前业务 Java 文件未提交差异为空。后续候选变化仍须重新生成/核对清单。

## 6 条 runtime extra 逐项说明

`ANY` 表示 Spring 的 HTTP 方法条件未限定，不是本次执行过全部 HTTP 方法。

| 方法 | 路径 | Runtime handler | 内容协商/性质 | 84 项关系与需补充验收 |
| --- | --- | --- | --- | --- |
| ANY | `/error` | `org.springframework.boot.autoconfigure.web.servlet.error.BasicErrorController#error` | `produces=[]`，框架错误响应，非考务业务 | 关联 FRONT-02、REPORT-05；补 `TRACE-EXT-01`：JSON 异常应保留正确状态码、不泄露栈/密钥/SQL和个人数据，不能把错误页 200 当成功。NOT_RUN |
| ANY | `/error` | `org.springframework.boot.autoconfigure.web.servlet.error.BasicErrorController#errorHtml` | `produces=[text/html]`，浏览器错误页，非考务业务 | 关联 FRONT-02；补 `TRACE-EXT-02`：HTML Accept 与 JSON Accept 分别验证内容和状态，普通用户不能见内部栈；页面提供安全返回路径。NOT_RUN |
| GET | `/swagger-ui/index.html` | `org.springdoc.webmvc.ui.SwaggerWelcomeWebMvc#redirectToUi` | API 文档入口，非业务功能 | 关联 ENV-01、ENV-02、PERM-06；补 `TRACE-EXT-03`：正式部署明确禁用或限制文档入口，测试重定向和所有附属资源，不能仅限制一个 URL。NOT_RUN |
| GET | `/v3/api-docs.yaml` | `org.springdoc.webmvc.api.OpenApiWebMvcResource#openapiYaml` | OpenAPI YAML，非业务功能 | 补 `TRACE-EXT-04`：YAML 与 JSON 采用一致生产访问策略，无内部地址、密钥、Cookie或敏感示例；禁止只保护 `/v3/api-docs/**` 而遗漏同级 `.yaml`。NOT_RUN |
| GET | `/v3/api-docs/swagger-config` | `org.springdoc.webmvc.ui.SwaggerConfigResource#openapiJson` | Swagger UI 配置，非业务功能 | 补 `TRACE-EXT-05`：配置文档遵守相同策略，外部 URL/代理路径无泄漏和意外跨站调用。NOT_RUN |
| GET | `/v3/api-docs` | `org.springdoc.webmvc.api.OpenApiWebMvcResource#openapiJson` | OpenAPI JSON，非业务功能 | 补 `TRACE-EXT-06`：正式模式暴露策略与实际权限一致；文档中列出接口不能替代真实授权/副作用验证。NOT_RUN |

目前 `SecurityConfig.java` 显式 permitAll `/v3/api-docs/**`、`/swagger-ui/**`，并对非 API 的剩余请求 `anyRequest().permitAll()`；`application.yml` 配置 Springdoc。没有将“测试 profile 注册这些框架路由”直接定性为生产泄露，必须在 G2 以正式候选配置复现后按批准暴露策略处理。不得为得到较少路由数而删除框架功能或弱化断言。

## 194 行并不是所有 HTTP handler

现 collector 使用 `context.getBeansOfType(RequestMappingHandlerMapping.class)`。因此以下项目不能从本文件推断已枚举或已通过：

- Actuator `WebMvcEndpointHandlerMapping`：`application.yml` 暴露 health、info，启用了 probes；独立的框架 endpoint mapping 不属于当前 collector 的类型范围。
- 静态资源、欢迎页、Servlet、Filter、反向代理路由以及未实例化 profile 下的接口。
- SSE 的 handler 注册已被采集，但连接鉴权、事件回放、终态和断线恢复还需订阅测试。
- 定时工作、消息消费者、启动钩子和进程内异步任务不是普通 HTTP 路由。

新增待验 `TRACE-EXT-07`（关联 OPS-04、ENV-01、RELEASE-05）：扩展独立运行时观测，列出 Actuator 实际注册的路径和暴露配置；分别执行匿名/授权访问、DB/Redis 故障就绪语义验证。不以本次未收集到 Actuator 推断它不存在或访问必然安全。状态：NOT_RUN。此项不改原始 84 条来源记录。

## 23 个异步发现点的真实构成

`background-discovery.json` 的 23 是**词法匹配位置数**，不是 23 个后台任务，也不是 23 个消费者：

| token | 位置数 | 真实解释 | 需要保留的验收 |
| --- | ---: | --- | --- |
| `CompletableFuture.runAsync` | 3 | `ExamPlanPlanningService` 1 处、`ScheduleJobService` 2 处；进程内执行器入口 | ASYNC-01/02/03/04/06 的重启、租约/恢复、饱和、真实取消、多实例幂等；不能由线程提交成功代替 |
| `SseEmitter` | 17 | 包括 import、类型、创建与处理位置，`ScheduleProgressSink.java:56` 同行重复出现 2 次 | ASYNC-05 的跨租户、过期会话、断线重连、终态完整性；不是 17 条 SSE 路由 |
| `@PostConstruct` | 2 | `LlmTestModeGuard`、`ProductionSecurityGate` 启动检查 | ENV-01/02，AI-01；合法启动与不安全组合拒绝分别验证 |
| `@EventListener` | 1 | `DemoAccountSecurityGuard` 的启动事件监听 | 正式账号/演示账号的启动处理与隔离；不是消息中间件消费者 |

实际注册的旧 SSE 路径为 GET `/api/v1/schedule-jobs/{jobId}/events` 与 GET `/api/v1/scheduling/runs/{runId}/events`；新规划作业使用 GET `/api/v1/planning-jobs/{jobId}` 轮询。只读源码搜索未发现 `@Scheduled`、`@RabbitListener`、`@KafkaListener`、Redis listener container、`WebSocketConfigurer` 或 `EnableWebSocket`。这仅代表当前扫描未发现实现，不可登记这些能力已测试或虚构一个消费者链路。

## 15 个未解析前端请求点：人工补充追溯

以下是静态扫描器不能解开的别名/动态参数，**不等于 15 个死按钮**。本次人工解析没有更新原 inventory JSON，以保留机器结果与人工补充的边界；均尚未成为浏览器点击验收证据。路径表中简写的前端相对 API 路径由 `api.js` 加 `/api/v1` 基址。

| # | 源位置 | 人工核对后的链路 | 当前结论与后续验收 |
| ---: | --- | --- | --- |
| 1 | `src/api.js:94` | `api.request(path, options)` → `fetch(resolveUrl(...), init)` | 通用传输层，不是独立业务控件。方法/参数由调用者指定；FRONT-02、AUTH-03/04 需真实 Cookie、错误和超时验证 |
| 2 | `src/api.js:215` | `jointInputSnapshot` → GET `/plan-revisions/{revisionId}/inputs/{resource}` | `jointInputResources` 显式 13 项白名单；13 个 GET 均有注册 handler。需逐资源结构、角色、学校和版本验证 |
| 3 | `src/api.js:216` | `replaceJointInput` → POST 同上 | 13 个 POST 均有注册 handler，发送 `{items}`；需 DATA/IMPORT/PUBLISH 并发、过期指纹及原子提交断言 |
| 4 | `src/pages/operations-v6-approvals.jsx:104` | 局部 `api=window.api.examPlans`；`api.revision` → GET `/exam-plans/{planId}/revisions/{revisionId}` | 别名已静态解开；需跨计划/租户拒绝和来源版本一致性 |
| 5 | 同上第一个 `api.assignments` | GET `/plan-revisions/{predecessorId}/duties` | 来源版本安排，runtime handler `PlanRevisionResourceController#duties`；不代表内容正确 |
| 6 | 同上第二个 `api.assignments` | GET `/plan-revisions/{revisionId}/duties` | 当前版本安排；与来源版本对比不能只断言页面有变化 |
| 7 | `src/pages/operations-v6-approvals.jsx:175` | 动态 `api.examPlans[kind]`：approve/reject；publish 走显式分支 → 对应 POST revision 端点 | 三条均已注册；页面含重读与 If-Match，仍需 PUBLISH-01/02/03/04 真正后端拒绝和通知断言 |
| 8 | `src/pages/quick-step4.jsx:40` | `api.url(clean)` 仅生成服务器下载链接；`ExamTaskService.downloads` 给出 `/exam-tasks/{taskId}/export?format=xlsx|pdf` | URL_ONLY 不是 fetch。需浏览器下载＋文件内容/DB对照，REPORT-03/04；不得把本地预览下载混算服务器产物 |
| 9 | `src/pages/super.jsx:48` | `useSuperResource(path)` → GET；租户、账号、公告、审计列表调用点提供具体 path | 通用列表 hook，不是一个独立未知端点；实际 admin handler 已注册，继续 FRONT-01/PERM-06 按调用点核验 |
| 10 | `src/pages/super.jsx:78` | `useSuperObject(path)` → GET；security overview、system health 等 | 通用对象 hook；响应形状校验不证明度量真实，OPS-04/06 仍需独立断言 |
| 11 | `src/pages/super.jsx:105` | `superPost(path, body, ...)` → POST；租户/账号状态、公告、备份、IP规则由调用点决定 | 通用写封装；不能以 toast 或 HTTP成功代替实际业务效果，OPS-01/06、REPORT-06、PERM/FRONT 分别验 |
| 12 | `src/pages/super.jsx:124` | `superPut(path, body, ...)` → PUT；例如公告编辑 `/admin/announcements/{id}` | 通用写封装；需刷新、DB和审计断言，不能只验证 reload 已调用 |
| 13 | `src/pages/super.jsx:307` | `auditPath` → GET `/admin/audit-logs?hours=24|168|720` | 仪表盘时间过滤；注册 `AdminPlatformController#auditLogs`，需核查真实时间窗而非只前端过滤 |
| 14 | `src/pages/super.jsx:1538` | GET `/admin/audit-logs?hours=24|168|720` 或不带 hours | 审计页时间窗口及全部；空结果/权限/分页仍未由注册证明 |
| 15 | `src/sidebar.jsx:11` | `load(path,key)` → GET `/admin/tenants`、`/admin/accounts` | 超管徽章计数请求；两者已注册，应测加载失败不显示伪造计数、普通用户不可读取 |

13 项 joint-input resource 为 `demands`、`demand-classes`、`demand-windows`、`candidates`、`enrollments`、`candidate-windows`、`special-requirements`、`room-pool`、`room-windows`、`seat-topology`、`teacher-pool`、`avoidances`、`campus-travel`。它们展开为 26 条已有业务注册行，不新增 26 条“已通过”场景。

建议新增 `TRACE-EXT-08`（FRONT-01/06 的补充）：以真实浏览器覆盖以上动态链路的每个已批准操作，记录按钮 → 请求 → 授权 → DB/产物 → 界面终态，尤其 13 个资源编辑、审批三分支、超管通用封装和真实下载。状态 NOT_RUN。范围内发现的新参数分支继续扩展，不删减84项。

## 本次执行与退出码

- Node 只读 JSON 对比（method/path）输出：shared 188、extra 6、staticMissing 0；退出码 0。
- Node 再按 controller#handler 精确校对：matchedBusinessHandlerRows 188、mismatches `[]`；退出码 0。
- Node 只读计数：runtime rows 194、unique method/path 193；async token 分布 3/17/2/1；退出码 0。
- `rg` 搜索未注册后台消费者/定时/WS 关键字：未找到匹配（rg 自身退出码 1 表示无匹配，不是业务测试失败）。
- 实際读取了 runtime JSON、静态 route/frontend/background 清单、`api.js`、相关页面函数、`SecurityConfig`、application 配置、下载 URL 生产函数。未全量深读所有 Controller/Service；已有“深读/扫描/未读”清单不因此整体升级。
- 本文不新增业务测试运行计数；真实 suite 退出码和发现/执行/失败/错误/跳过数以同轮 `full-maven-suite.command.json` 和 JUnit 原始文件及主任务汇总为准。

## 下一步

1. 固定候选二轮完成后，复核新 run 的 188 业务匹配关系及框架 extra 是否保持一致。
2. G2 明确并验证框架错误端点、OpenAPI/Swagger 的正式暴露策略；保留以上 TRACE-EXT 待验要求，不伪造通过。
3. 补充 Actuator 等非 RequestMappingHandlerMapping 范围的真实枚举与就绪行为，解除“全部HTTP路由已枚举”的覆盖缺口。
4. 在 G7 建立动态调用点对应的真实浏览器操作证据，在 G4/G5/G6 对 DB、通知、任务和导出作独立结果校验。
