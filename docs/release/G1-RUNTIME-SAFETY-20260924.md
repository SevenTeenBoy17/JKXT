# G1 完整测试运行安全与失败分流独立审查

日期：2026-09-24。审查基准 HEAD：`1d023c211cc222a8582e72f24b4c973d1199b086`。

范围：`D:\VB\JKXT-release-g0-g1` 内的隔离测试 runner、Compose、Spring 测试配置、测试连接保护、外部发送入口及共享夹具。此报告来自独立只读源码审查，**不是业务测试结果、正式质量门结果或上线许可**。本审查没有启动服务、访问数据库、调用外部 AI/邮件/短信；只被授权新增本报告。JUnit 执行、失败、错误、跳过数量均 **NOT_RUN／待运行**，不是零失败通过。

## 1. 结论与前置条件

- 现有 runner 有明确独立数据库/Redis、环境清理、本机 Docker 限制、拒绝复用和来源留证，适合继续受控 G1。
- 随机 HTTP 测试端口尚需限定 loopback；主代理正在处理，本报告初次审查时尚未看到改动或运行证据。
- 全套测试须串行执行。即使使用全新数据库，类内和跨类共享演示租户夹具仍可能污染后续结果；不能把 fresh DB 等同于每类隔离。
- 不能把 AI mock、邮件短信 LOGGED 或静态接口清单计入真实外部服务验收。
- 保留失败首轮日志；失败后按原因分流，不进行无解释的反复重跑直到绿色。

## 2. 已核查的安全边界

| 要求 | 源码证据 | 当前判定与边界 |
| --- | --- | --- |
| 不使用业务数据库 | `scripts/release/run-isolated.ps1:19–42` 清除连接/JVM/Maven变量并设置 `jkxt_test_<run>`、随机口令、25432/26379；`:54–63` 拒绝占用端口和已存在的 Compose 项目 | 静态已核查；真实连接仍须本次运行数据库名断言确认 |
| 不挂载历史业务卷 | `compose.release-test.yml` 使用 PG16/Redis7、tmpfs、127.0.0.1 端口映射 | 静态已核查；没有普通持久化业务卷 |
| Docker 只在本机 | runner 固定 `desktop-linux`、`npipe:////./pipe/dockerDesktopLinuxEngine` 并核验 context endpoint | 静态已核查；不允许远程 Docker 作为暗中替代 |
| 保留数据库保护 | `application-test.yml` 配置 initializer；47 个被扫描到的 `@SpringBootTest` 均选择 `@ActiveProfiles("test")` | `TestDatabaseIsolationGuard.java` SHA256：`1164a74a9b6e0d09792d1d5918143872bac96d27fd6856a82fa10a6f922805f6`，与先前基线一致 |
| 不跳过关键容器测试 | runner 传入 `-Djkxt.testcontainers.enabled=true` | 仍须检查两类实际 JUnit testcase、failure/error/skipped；参数存在不等于执行成功 |
| 历史迁移独立运行 | `JointPlanningMigrationIntegrationTest.java:30–52` 未传 PostgreSQL system-property URL 时新建自己的 PG16 容器，目标版本28；`P11TestcontainersInfrastructureTest` 使用独立PG/Redis容器 | 不能将历史V28测试改接共享V30库；前者有手工URL覆盖口，因此只使用已清理JVM参数的runner |
| 不进行真实 AI 计费调用 | runner `JKXT_LLM_ENABLED=false`、清除旧JKXT变量；`AiPolicyAcceptanceAuditIntegrationTest:40–59` 虽配置 enabled=true，但替换为 `@MockBean LlmClient`；客户端单元测试使用127.0.0.1 stub | 只证明契约、失败处理和审计；不能证明真实模型提供商可用 |
| 不实际外发邮件短信 | `LoggingEmailSender`、`LoggingSmsSender` 只记录日志并返回 `LOGGED` | 没有在扫描范围发现 SMTP/SMS 提供商发送实现；真实送达仍未验收 |
| 不自动清理外部业务 | 主源码扫描未发现 `@Scheduled`/`@EnableScheduling`；业务异步任务依赖当前测试配置的库 | 静态检查，不证明所有异步任务已正确结束；清理测试夹具前仍须等待相应任务终态 |
| 不复用真实备份文件 | `AdminPlatformService:43` 固定 `/var/backups/jkxt`；本次只读检查 `D:\var\backups\jkxt` 不存在 | Windows 本次原生测试路径无现存文件；其他宿主机需重新检查，不能当作可移植隔离保证 |

## 3. 发现项：静态事实与未证实影响

### RT-01：随机 Web 测试端口没有显式 loopback 约束

- 严重度：中；源码事实置信度：高；实际网络监听影响：待运行。
- `RegisteredApiInventoryTest.java:26` 是 `RANDOM_PORT`；初审的 `application-test.yml` 未设置 `server.address`，runner 没有清理 `SERVER_`、`MANAGEMENT_`。
- 后果：随机端口不等于仅本机监听；默认绑定或继承配置可能使带种子测试账号的临时应用对其他网卡可见。DB/Redis 的 loopback 绑定不能替代 Web 绑定。
- 最小方向：只在测试 profile 固定 `server.address=127.0.0.1`，runner 清理相关环境注入；保留数据库保护和所有业务断言。运行时再断言有效配置和实际监听地址。
- 状态：**静态待修/复审**，不能写成已经发生公网暴露，也不能凭配置修改标为运行验证通过。

### RT-02：考试计划测试改写共享教师资格但不恢复

- 严重度：中；源码事实置信度：高；是否导致已见失败或虚假通过：尚未运行证明。
- `ExamPlanApiIntegrationTest.java:338–346` 的 `seedFeasibleRevision` 选择固定组织内首位 active 教师，并直接执行 `update teacher set qualifications='["INVIGILATOR","CHIEF"]'::jsonb where id=?`。
- `:50–70` 的 `cleanup()` 清理计划、外部组织和临时用户，不保存/恢复原教师资格。`:158–165`、`:227–234` 暂时撤销资格后恢复为写死的 CHIEF 组合，也不是原始快照。
- 后果：相同数据库内后续类可读到更宽的教师资格；只运行单类与全套时的初始状态可能不同。该风险不能靠串行运行彻底消除。
- 最小方向：优先使用本类拥有的独立教师/组织；或在首次修改前保存精确原资格，在可靠 `finally`/`@AfterEach` 恢复。不要把整个异步业务测试简单套入会导致异步线程看不到提交数据的事务。
- 验收：对目标教师的资格快照做前后独立 SQL 比较；专项后再跑受影响类和全量。发布后业务产物有意保留不属于此资格泄漏，不应一并强删。
- 状态：**静态缺口，待专项复现**。本报告没有声称任何现有业务断言已经失败。

### RT-03：已知共享夹具行为，要求串行与精确失败分流

- `AiPolicyApiIntegrationTest.java:51–55` 在每个用例前 `callLogRepository.deleteAll()`，作用于整个隔离测试库的 AI 审计行；不能并行运行依赖旧审计行的其他类。这不是误删真实数据的证据，但会影响共享测试状态。
- `RealDataScheduleFlowIntegrationTest.java:377–407` 暂时禁用整个演示租户教师，在多个用例的 `finally` 中恢复旧教师状态，并将新增测试教师禁用。检查到恢复路径存在，不应机械把它判为未恢复；它仍不能与同租户排班测试并行。
- `DemoAccountSecurityGuardTest` 会禁用种子账号，已使用 `finally` 保存/恢复状态；禁止并行执行其他登录用例。
- AI 审计清空、已发布计划保留及异步任务可能影响全套后的总行数。验收应使用当前请求/任务/运行ID归属断言，不能要求整个共享数据库回到空状态。

### RT-04：多上下文连接池容量是失败诊断候选，不是已复现缺陷

- 多个测试使用不同 `@TestPropertySource`、`@MockBean`、MockMvc 与真实随机端口配置，会创建不同 Spring 上下文；测试配置未显式限制 Hikari pool 大小。
- 如果实际出现 `too many clients`、连接获取超时或内存不足，先记录上下文/连接数和当时日志，区分测试容器容量、夹具长事务与产品连接泄漏。当前未测量，不得提前将任何失败归因于此。

## 4. 建议执行与复现顺序

### 4.1 已有安全入口：fresh 全量运行

以下为计划命令，不是本审查已执行命令。每次必须替换唯一 run ID，不能复用旧证据或测试资源：

```powershell
& 'C:\Users\nuoya\.cache\codex-runtimes\codex-primary-runtime\dependencies\native\powershell\pwsh.exe' -NoProfile -File 'D:\VB\JKXT-release-g0-g1\scripts\release\run-isolated.ps1' -JavaHome 'D:\VB\JKXT-tools\login-entry-20260920\jdk-21.0.12.1+1' -Maven 'D:\VB\JKXT-tools\login-entry-20260920\apache-maven-3.9.9\bin\mvn.cmd' -Run 'release_g1_runtime_safety_unique'
```

前提：主代理完成 RT-01 回归；本机 Docker 健康；脚本认可的端口空闲；源码在整个被记录命令期间冻结。继续使用完整来源记录器与隔离保护，不能将 native exit=0 而 source drift 的记录冒充有效成功。

### 4.2 RT-02 专项命令与独立观察

现有 runner **没有专项 `-Test` 参数**。因此以下只是供主代理在同样已建立、已清理环境且刚创建的专用测试资源中使用的 Maven 内层参数，**不是可直接在任意 shell 执行的安全入口**。不得传入旧 Cookie、业务 JDBC URL 或把历史 `.xml` 复制到新报告目录。

```text
-B -o -s <repo>/scripts/release/maven-settings.xml -gs <repo>/scripts/release/maven-settings.xml
-Djkxt.test.reportsDirectory=<fresh-evidence>/junit
-Dtest=ExamPlanApiIntegrationTest#lifecycleIsTenantScopedOptimisticAndTruthful test
```

方法：

1. 在独立PG16完成当前迁移后，通过该**明确属于本轮的容器**只读查询 `select current_database()`，必须精确等于本轮 `jkxt_test_<run>`。
2. 对固定测试组织保存 `select id, qualifications from teacher where org_id='00000000-0000-0000-0000-000000000001' and status='active' order by teacher_no` 的前置快照，不需要姓名或手机号。
3. 以 `record-command.ps1` 捕获上述专项 Maven 命令、退出码、SHA、源码摘要和独立 JUnit 输出。
4. 用相同只读查询保存后置快照，独立比较原有教师资格；如果发生变化，才将 RT-02 升格为实际夹具泄漏复现。
5. 修复后保持业务断言不变，重新创建 fresh 环境重跑专项，再跑至少 `ExamPlanApiIntegrationTest,ExamOperationsExecutionIntegrationTest,ExamPlanJointPlanningIntegrationTest,RealDataScheduleFlowIntegrationTest` 影响面。两种 Surefire 顺序 `-Dsurefire.runOrder=alphabetical` 与 `-Dsurefire.runOrder=reversealphabetical` 必须分别使用 fresh 环境；不同顺序通过仍不能替代独立资格快照断言和完整全量回归。

如果没有安全的专项执行入口，先完成现有全量入口，保留上述专项为 NOT_RUN，不临时绕过数据库保护。

## 5. G1 运行后必须检查的证据

- 新命令的 source-before/source-after、Git SHA、退出码、时间及日志hash；源码漂移单独失败，不以 Maven 成功掩盖。
- 实际 JUnit testcase 数、失败/错误/跳过、缺失类/方法；重点检查两个 Testcontainers 类未跳过。
- 实际映射探针报告：数据库名、迁移版本30且无失败迁移、Redis PONG、Spring运行时接口清单、候选SHA/runId/sourceDigest。
- 数据库断言和导出内容断言各自留证；Maven编译成功和XML中零错误不能替代没有执行的数据库/E2E用例。
- 若出现 Spring 上下文启动失败引发后续 failure-threshold 错误，保留第一个根因及所有后续错误计数，不把后续错误删除或改成跳过。
- AI 外部计费调用与邮件/短信送达仍需单独授权和实测；本次 mock/LOGGED 结果只能标明其真实范围。

## 6. 本审查执行记录

使用 `code-verification` skill 与其 verification-matrix 进行独立安全/完整性审查。执行的是 PowerShell `Get-Content`、`rg`、`git status/rev-parse`、`Get-FileHash`、`Test-Path` 等只读检查，所用命令均返回退出码0；未运行 Maven、Docker启动/迁移、API业务写入。

审查开始时 HEAD 如上，存在主代理及其他评审任务的未提交文档/工具变更，未覆盖。运行通过数量：**NOT_RUN**；真实外部服务验证：**NOT_RUN**；正式发布结论：**未放行**。

## 7. Loopback 修改的独立 test-integrity 复审

复审对象仅四个文件：

- `backend/src/test/java/com/jkxt/security/TestNetworkBindingTest.java`
- `backend/src/test/java/com/jkxt/release/RegisteredApiInventoryTest.java`
- `backend/src/test/resources/application-test.yml`
- `scripts/release/run-isolated.ps1`

以上 backend 路径均相对于 `JKXT V2.0/`。本评审没有运行第二个 Maven 进程，也没有改动这些文件。

### 7.1 必须保留的真实时间线与错误

| 证据 ID | 已读取原始结果 | 独立解释 |
| --- | --- | --- |
| `test-network-red` | exit=1，sourceUnchanged=false；Maven 参数被拼成一个字符串并当作插件坐标，未进入JUnit | **无效复现/命令调用错误**，不能计为业务RED或有效回归。原日志仍保留，不能改写成功 |
| `test-network-valid-red` | exit=1；JUnit 1执行、1失败、0错误、0跳过；sourceUnchanged=true | `server.address` 原为null，被期望127.0.0.1的断言捕获，构成原监听配置缺口的有效RED |
| `test-network-green` | exit=0；JUnit 9执行、0失败、0错误、0跳过；sourceUnchanged=true | 当时的配置单元测试期望应用地址和管理地址都为127.0.0.1。该 **首个绿色存在 oracle 不完整问题**，没有启动真实Spring/Actuator，不能证明管理共用端口配置合法 |

三条 command JSON 引用的输出SHA256均已独立重算并匹配。首次绿色XML中的实际方法名为 `testProfileBindsApplicationAndManagementToLoopback`，与后续修正后的方法名不同，不能借旧绿色冒充最终测试通过。

### 7.2 独立发现并纠正首个绿色的配置 oracle

复审初稿时观察到 `management.server.address=127.0.0.1`，但并未配置独立 management port。独立审查直接对本地项目实际依赖执行只读命令：

```powershell
& 'D:\VB\JKXT-tools\login-entry-20260920\jdk-21.0.12.1+1\bin\javap.exe' -classpath 'C:\Users\nuoya\.m2\repository\org\springframework\boot\spring-boot-actuator-autoconfigure\3.3.13\spring-boot-actuator-autoconfigure-3.3.13.jar' -c -p 'org.springframework.boot.actuate.autoconfigure.web.server.ManagementContextAutoConfiguration$SameManagementContextConfiguration'
```

退出码0。实际字节码中 `verifyAddressConfiguration()` 对 `management.server.address` 非null执行失败断言，消息为：

```text
Management-specific server address cannot be configured as the management server is not listening on a separate port
```

这不是通过猜测构造的新业务条件，而是当前 Spring Boot 3.3.13 依赖的共用管理端口约束。主代理在全套启动前据此删除该冲突配置，并将单元断言改为显式禁止另设管理地址和端口。原9绿色证据保留，不追溯改写为最终正确版本。

### 7.3 最终修改的静态完整性结论

- 测试profile仅设置 `server.address: 127.0.0.1`；没有 `management.server.address` 或 `management.server.port`，Actuator共享原loopback监听。
- runner 环境清理增加 `SERVER_`、`MANAGEMENT_`，内层Maven增加 `-Dserver.address=127.0.0.1`。原数据库/Redis/JVM清理、鲜活项目检查和Testcontainers开关未删除。
- 最终 `TestNetworkBindingTest.testProfileBindsSharedApplicationAndManagementPortToLoopback` 同时断言应用地址为127、管理专用地址和端口均null；这项更改是修正与真实框架契约冲突的oracle，不是为产品错误降低断言。
- `RegisteredApiInventoryTest` 新增实际 `TomcatWebServer` connector 的 `protocol.getAddress()` 非null且等于127.0.0.1断言，并在运行报告记录 `serverAddress`，没有以读取同一份YAML替代运行时观察。
- 原数据库名、Flyway版本30/失败数0、Redis PONG、映射非空和核心路径断言全部保留；保护器文件hash再次确认未变。
- 新断言绑定当前项目的真实Tomcat依赖，若将来换Web服务器实现应进行明确迁移而不是捕获异常后忽略。

**结论：最终差异通过本次静态 test-integrity 复审，运行验收待全套新证据。** `release_g1_20260924_r1/environment.json` 已存在且记录独立容器READY，但它仅证明基础设施准备，不证明JUnit或业务通过。不得将先前9绿色合并成此最终来源摘要的完整运行结果。

## 8. G1 r1 完整运行后的独立证据核验

本节是第7节之后的新证据，不追溯改变此前 NOT_RUN 和配置oracle纠正记录。测试由主代理运行，独立评审只读取新报告。

- 命令记录：`evidence/release_g1_20260924_r1/full-maven-suite.command.json`。
- 起止：`2026-09-24T07:45:42.7098499Z` 至 `2026-09-24T07:47:56.3912496Z`；exitCode=0，nativeExitCode=0，sourceUnchanged=true。
- HEAD：`1d023c211cc222a8582e72f24b4c973d1199b086`，运行源码摘要：`d56b000dd51eb95c1d17f90dea3b59d6eb075b5b6f469700af143173030be17a`。该摘要包括当时未提交的修复，不能单凭HEAD视为纯已提交树。
- 直接逐份读取 `junit/TEST-*.xml` 并统计实际 `testcase`：**73类、274执行、0失败、0错误、0跳过**，与本轮Maven日志一致。
- 日志SHA256独立重算匹配记录：`effde67392e8677cca8435e017acf04b89b6a8efa99811522708cfef3b41df80`。
- `TestNetworkBindingTest` 最终方法 `testProfileBindsSharedApplicationAndManagementPortToLoopback` 1执行、0失败/错误/跳过；`TestDatabaseIsolationGuardTest` 8执行、0失败/错误/跳过。
- `RegisteredApiInventoryTest` 1执行、0失败/错误/跳过；`P11TestcontainersInfrastructureTest` 与 `JointPlanningMigrationIntegrationTest` 各1执行、0失败/错误/跳过，**不是以关闭Testcontainers跳过换绿**。
- `spring-mappings.json`：本轮runId与命令一致，collection=`RequestMappingHandlerMapping`，实际映射194；数据库=`jkxt_test_release_g1_20260924_r1`，Flyway版本30、失败迁移0，Redis=`PONG`，实际Tomcat connector地址=`127.0.0.1`。采集时间为 `2026-09-24T07:47:28.949844900Z`，SHA/源码摘要与命令一致。

**配置修复最终 test-integrity 结论：PASS（限定本轮来源摘要、隔离测试配置与已有完整Java测试范围）。** 同端口Actuator冲突已被纠正，修正后的配置单元、真实Spring/Tomcat、存储断言均实际运行通过。此结论不是84项业务全部覆盖，不是前端E2E、真实AI/通知或部署上线通过；RT-02资格夹具风险仍未被本次按默认顺序通过所排除。

## 9. G2 会话撤销专题：只读审查与最小稳定复现方案

### 9.1 已确认的代码事实

- `security/auth/SessionService.java:48–67` 创建token键与 `jkxt:user-sessions:<userId>` 索引，二者初始TTL相同。
- `:71–85` 的 `find()` 只为token键续期（`:79`），不续期用户索引，不检查索引成员关系。因此持续访问可使token存活时间超过索引。
- `:99–117` 的 `revokeOtherSessions()` 只枚举当前索引成员；索引消失或成员缺失时，不会发现仍有正文的token。
- `AuthService.java:230` 改密调用上述撤销；`AdminPlatformService.java:389/391/396/410` 的重置口令、强制退出、禁用、删除也依赖上述索引；`:235–253` 租户停用逐用户调用同一撤销方式。
- `SessionAuthenticationFilter.java:50–62` 直接把Redis principal变成当前权限；无租户切换请求时`:83–85`直接使用principal orgId，没有实时复核账号/租户状态。`AuthService.me():180–187` 查询用户但未拒绝disabled状态。

**判定：高优先安全缺口，源码因果链明确；本独立评审尚未运行过期索引复现，因此不虚构失败测试或已利用结果。** 已有274绿色只能说明现有测试通过，不能证明遗漏场景安全。

### 9.2 现有回归锚点与缺失边界

- `src/test/java/com/jkxt/security/AuthIntegrationTest.java:255`：`changePasswordRevokesOtherSessionsButKeepsCurrent`，新建A/B后立即改密，未经历索引自然过期。
- `src/test/java/com/jkxt/platform/P10ReportAdminApiIntegrationTest.java:302` 附近：真实创建用户、登录、禁用后旧cookie401，也是立即撤销场景。
- 未发现直接验证 `find()` 后索引TTL不早于tokenTTL、缺失索引token fail-closed、自然过期后的改密/禁用撤销或续期与撤销原子性的既有专项测试。
- 不能删除现有立即撤销断言；应增加边界回归并运行两类现有影响面。

### 9.3 不改变宿主时钟的稳定复现

只在新的专用Redis/PG和测试专属账号下进行，不使用种子共享账号或业务Cookie。

1. 创建测试专属用户，登录两次得到旧会话A/B。用明确用户ID定位本用例索引，断言A/B成员均存在，两个token正文TTL均明显大于短窗口。
2. 将**本用例索引**TTL短化至例如100–500ms，不更改宿主、Docker或PostgreSQL时钟；有界轮询等待Redis确认该索引自然消失。不能只 `sleep` 后假设已过期。
3. 独立确认B的token正文仍存在且TTL>0。此处模拟的是当前代码可达的“索引先过期，token经续期仍有效”状态，不能伪装成已等待7天。
4. 对改密：重新合法登录得到C，让C改密成功；随后B请求 `/api/v1/auth/me` 及一个真实受保护只读业务路由必须401，C仍200，旧密码新登录失败、新密码成功。**不要要求已失去索引的A继续获准改密**，否则会错误阻止正确的fail-closed修复。
5. 对禁用/重置/强制退出/删除：使用另一独立超管会话执行实际对应API，再断言旧B访问401，并断言数据库实际状态/口令更改；不把toast或返回status字串作为唯一成功标准。
6. 另设正常续期测试：缩短索引TTL后调用 `find()`，再读取真实Redis TTL，检查索引与token仍有效且不会提前落后失效；避免毫秒级相等断言，使用共同绝对到期界限/合理测量容差。
7. 本用例清理仅精确删除自有用户/会话键；测试完成再次执行上述两类原回归与G1全量。实际RED/GREEN必须关联对应命令、源码摘要及新JUnit。

### 9.4 最小修复的完整性条件

- 仅补 `expire(userKey, ttl)` 不能处理已经失效的索引；无条件重新添加旧token也可能在撤销后恢复会话。缺失/非成员token应拒绝，不允许凭正文自我复活。
- token读取、成员确认、续期与撤销的并发关系需要原子化或等价版本机制；不能认为连续几次Redis命令天然原子。
- 保留改密当前合法会话、撤销其他设备、禁用全部会话、session cookie约束与真实角色权限边界。
- 账号/租户实时状态及权限版本属于相邻纵深防御问题，需要独立规格与负向测试；不能把仅修TTL后的结果宣称为全部认证安全验收完成。
