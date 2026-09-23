# 非普通 CRUD 的交付表面

以下均为本次源代码检查，未通过本文件宣称运行通过。源文件位置见 `background-discovery.json` 和 `traceability.csv`。

| 表面 | 实际源码机制/入口 | 存储与产物 | 分类与需要追加的验收 |
| --- | --- | --- | --- |
| 旧任务后台求解 | `ExamTaskController.startScheduleJob` → `ExamTaskService` → `ScheduleJobService` → `CompletableFuture.runAsync` | `schedule_run`、`schedule_assignment`；事务写入及 OR-Tools 结果 | 有真实代码；受控异常、过载、服务重启中断、租户跨读、结果一致性未由扫描证明 |
| 新考试计划后台求解 | `PlanningJobController.start` → `ExamPlanPlanningService.start`，事务 `afterCommit` 后提交共享 `scheduleTaskExecutor` | `exam_plan_planning_job`、版本与联合规划输入/结果 | 有真实代码；非持久化队列。轮询与取消有端点；必须另验重启恢复/取消竞态/超时 |
| 执行器 | `ScheduleAsyncConfig`；核心 2、最大 4、队列 32、CallerRunsPolicy | JVM 内存队列，不是 Redis broker | 不把 Redis 存在当作后台任务持久化。过载时在请求线程执行的延迟需实测 |
| 旧求解进度订阅 | GET `/api/v1/schedule-jobs/{jobId}/events` 和 `/api/v1/scheduling/runs/{runId}/events` | `ScheduleProgressSink`，SSE、120 秒连接、512 run 内存 LRU 缓冲 | 有真实代码；事件 ID/重连/断开/终态/跨租户需真实订阅测试，不仅检查响应头 |
| 新规划作业进度 | GET `/api/v1/planning-jobs/{jobId}`；`exam-plans.jsx`轮询 | DB status/stage/progress | 有真实代码；不是 SSE。同操作应在刷新后从持久状态恢复 |
| Redis 使用 | `SessionService`、CaptchaService、LoginIpThrottle等 | 会话、验证码、节流 key | 静态扫描未发现 Redis 消息消费者、Stream 消费组或 pub/sub listener；不要虚构消费者覆盖 |
| WebSocket | 前端 `api.webSocket/wsUrl` 仅通用封装 | 后端未扫描到 WebSocket 注册/handler | 能力封装不等于已实现订阅功能。实际注册核对仍待运行时证据 |
| 定时任务 | 扫描后端 Java 的 `@Scheduled`/`@Async` | 无匹配；启动钩子另列 | 不声称已有定时重试、孤儿任务回收、通知重投或备份调度 |
| 启动钩子 | `DemoAccountSecurityGuard` 的 ApplicationReadyEvent、`ProductionSecurityGate` 与 `LlmTestModeGuard` 的 PostConstruct | 账号/生产配置限制 | 有真实代码；应测合法与拒绝启动配置，不绕开保护 |
| Excel 模板下载 | GET `/import/templates/{kind}`、`/import/{kind}/template` → `ImportValidationService.template` | MIME XLSX、Content-Disposition、真实 bytes | 必须打开工作簿核对 sheet/列名/格式，不能只断言 200 |
| 服务端排班导出 | GET `/exam-tasks/{taskId}/export?format=xlsx|pdf` → `ExamTaskService.export` | Excel/PDF、租户任务安排 | 非支持格式 Controller 400 白名单已有代码。验证内容应与 DB/独立结果校验器核对，不只测文件存在 |
| 本地预览导出 | `src/pages/quick-engine.jsx` SheetJS workbook 与 localStorage | 浏览器下载，总表/教师/考场/考试分表 | 本地预览/模拟链，不是服务端排班验收证据；需验证演示和正式入口隔离 |
| 站内通知 | `NotificationService`、`NotificationDeliveryService`及各通知 Controller | 数据库站内通知、已读状态、投递记录 | 机构广播、教师个体投递、已读和岗位确认必须分别验收，不可合并成“已送达” |
| 邮件/SMS | `LoggingEmailSender` / `LoggingSmsSender` | 日志返回 LOGGED；dispatcher区分disabled/logged_only/delivered/failed | **模拟/部分实现**：当前发送器不连接网关；不以日志或站内成功宣称邮件短信可用 |
| 真实 AI | `OpenAiCompatibleLlmClient` 使用 HttpClient 对配置来源调用，限流重试/响应限额/JSON校验 | `AiCallAuditService`审计及策略产物 | 真实实现代码存在但外部配置/计费调用未授权执行；test-only mock严格标 fallback，不作真实AI通过证据 |
| 备份登记 | POST `/admin/backups/run` → `AdminPlatformService.runBackup` | 扫描 `/var/backups/jkxt/*.sql`，可读 `.sha256`，写backup_record | **部分实现**：不运行pg_dump，缺产物返回unavailable。旧“固定4.2GB成功”已删除，不再机械修相同问题 |
| 备份/恢复脚本 | `scripts/deploy/backup-private.ps1` / restore drill 等 | Docker ops profile、dump、restore | G0仅列脚本；不得针对原业务DB运行。恢复可用性需要专用DB逐表/约束验证 |

后续应为每项增加本轮命令、环境、开始/结束时间、退出码、输入/结果哈希和独立断言。停止的消费者不能写成功；没有实现的消费者应写“未发现实现”，不是“测试跳过后通过”。
