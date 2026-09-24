# G2 会话索引切片：独立 test-integrity 审查

日期：2026-09-24。审查工作树：`D:\VB\JKXT-release-g0-g1`。
审查起始 HEAD：`bf320b69a24739166bb23ffb283eba0f48523946`。

对象：

- `JKXT V2.0/backend/src/test/java/com/jkxt/security/SessionIndexExpiryIntegrationTest.java`（新增，5个测试）
- `docs/release/G2-SESSION-INDEX-SPEC.md`
- 只读追踪其实际调用的认证过滤器、认证服务、会话服务、管理员禁用服务及相关既有测试。

权限与证据：独立评审只读取源码与主代理生成的运行产物，仅新建本报告；没有运行Maven、没有连接数据库/Redis、没有调用业务API、没有修改产品/测试源码/STATUS。审查时 `SessionService` 相对HEAD没有修改，旧行为仍在，适合执行真实RED。

## 1. 结论

**静态 test-integrity：允许执行RED，并可作为本窄切片的主要回归。** 新测试没有mock核心后端，没有绕过数据库保护或Spring安全过滤链，没有删除或削弱既有断言。五个用例分别覆盖续期一致性、索引已过期后的改密、禁用、成员丢失的fail-closed，以及顺序登出后不复活。

**当前不能给产品修复PASS，更不能把AUTH-02整体标PASS。** 审查本版本时完整RED仍在运行，未见最终command记录或该类JUnit；原有274个绿色不含本新类。实际竞态、全部管理撤销入口、绝对超时、权限版本和CSRF均不由这五例完整证明。

## 2. 测试是否走真实路径

| 检查 | 观察 | 判定 |
| --- | --- | --- |
| 安全链 | `@SpringBootTest` + `@AutoConfigureMockMvc`，未设置 `addFilters=false`；没有 `@WithMockUser`、`@MockBean` | 通过真实 `SessionAuthenticationFilter` 和 `SecurityConfig`，不是伪造已认证用户 |
| 登录 | 每次真实调用 `/auth/captcha`，消费本次验证码后调用 `/auth/login`，使用服务器返回cookie | 不复用历史或真实业务cookie |
| 存储 | 注入真实 `JdbcTemplate`、`StringRedisTemplate`，使用 `test` profile | 继承未修改的 `TestDatabaseIsolationGuard`；仍必须由隔离runner执行 |
| 改密 | 实际POST `/auth/me/change-password`，直接查数据库hash并使用BCrypt `matches`，再验证当前与旧设备 | 返回200不作为唯一成功标准 |
| 禁用 | 新建独立SUPER_ADMIN并登录，实际POST管理员disable，数据库status必须disabled | 不直接用SQL替代受测禁用行为 |
| 会话失效 | HTTP401且错误码 `AUTH_UNAUTHENTICATED`，同时断言Redis旧token删除 | 不仅检查弹窗/toast或布尔返回 |

JDBC插入账号仅用于创建受控夹具；它不属于本轮账号创建API验收，不能声称新建账号业务流程已由此覆盖。

## 3. 五用例与验收准则映射

| 用例 | 独立期望与有效证据 | 边界 |
| --- | --- | --- |
| `legitimateRequestRenewsTokenAndRevocationIndexTogether` | 将自有索引TTL缩短到30秒，真实 `/me` 后两键TTL均大于600秒、差值小于1000ms且成员仍存在；旧实现只续token应失败 | TTL阈值依赖当前默认7天配置；不是绝对生命周期证明。1000ms容差是测试观察容差，不是生产性能SLA |
| `passwordChangeCannotLeaveAnExpiredIndexSessionAuthenticated` | 自然过期旧索引，确认旧token仍存活；重新合法登录后真实改密；DB新hash正确、当前设备200、旧设备401且旧token删除、索引仅含当前设备 | 正确允许旧孤立token fail-closed，而不强迫它继续可登录；未独立验证新密码再次登录，已有AuthIntegrationTest保留该影响面 |
| `disableAccountRejectsTokenWhoseRevocationIndexHasExpired` | 自然过期索引后由另一自有超管禁用；DBdisabled，旧cookie401且旧token/index消失 | 仅验证disable，不代表reset-password/delete/force-logout/tenant suspend全部边界都单独执行 |
| `missingMembershipFailsClosedWithoutRebuildingAuthorization` | 索引仍存在但删掉一个自有session成员；旧token必须401并删除；另一合法session仍200、成员集合准确 | 人为移除成员是明确故障注入，不伪装自然过期；防止无条件SADD恢复授权 |
| `loggedOutTokenIsNotRecreatedBySubsequentRequests` | 真实logout后token不存在，连续两次旧cookie请求401且token仍不存在 | **只证明顺序场景，不证明读取旧payload与Lua之间的并发撤销** |

本规范明确这是AUTH-02的一部分，并明确不解决绝对超时、全部创建/撤销并发、角色版本、学校停用实时检查和CSRF，范围声明适当。最终交付报告应继续使用“会话索引续期/缺成员拒绝切片通过”，不能提升为“认证全部安全”。

## 4. 自然过期、时钟与稳定性

- `expireIndexNaturally()` 对当前自有索引执行1ms TTL，使用Awaitility在最多3秒内每10ms检查Redis键确实消失，而不是固定sleep后臆测。
- 改密/禁用用例随后确认旧token仍存在，证明测试设置命中了“索引消失、token还在”的关键边界。
- 没有修改Windows、WSL、Docker或数据库时钟。该测试是缩短TTL的真实Redis到期验证，不是已等待真实7天，也不是模拟Redis实现。
- 默认环境、单线程测试下设计合理；若重负载导致TTL测量差超过1000ms，应先保存实际TTL/命令时延并判断环境或实现根因，不能直接放大断言换绿。

## 5. 夹具唯一性与清理

- 每例新建UUID账号；用户名 `release.session.<UUID>` 和 `example.invalid` 邮箱唯一，账号密码只为合成测试值；SUPER_ADMIN也为本例新增，不修改种子用户权限或密码。
- 组织ID从种子 `jwc.demo` 只读获取，因此它是“独立账号、共享隔离测试租户”，不是每例新建完整租户。全套仍应串行，不能与停用该共享租户的测试并行。
- `@AfterEach` 精确删除本例收集的token键、用户索引键和自有UUID账号；没有FLUSHDB、全表删除或通配键删除。
- `audit_log.actor_id` 的现有数据库约束是 `ON DELETE SET NULL`，所以清理自有账号保留审计历史而不清空全库审计。这些残留合成审计行仍归属于一次性测试库，不是实际业务数据。
- 当前测试不为账号创建需手工回收的教师绑定或排班产物；测试失败后清理仍执行。如果清理自身异常，必须保留JUnit error并排查，不能吞异常以绿化。
- 既有认证、平台管理、数据库保护器测试没有被改动；本切片只增加5例，不替换原来的立即改密/禁用测试。

## 6. 未覆盖项与后续证据要求

### TI-G2-01：不能用顺序登出测试证明续期/撤销竞态

规范设计使用Redis原子脚本，声称避免旧读取在并发撤销后重新成为凭据。五例只覆盖顺序状态，没有受控安排“读到旧JSON → 另一线程撤销 → 继续校验/续期”的时序。

- 严重度：中；结论：覆盖缺口，不是已证明产品失败。
- 允许先执行当前RED、最小修复和GREEN，但最终报告对原子性的证明应明确为设计/代码审查，不能归功于第5例。
- 若本轮要宣称竞态也已验证，应另加保持真实Redis的定序并发回归：在token旧值已读、Lua尚未执行时用受控同步点完成真实撤销，然后继续读取流程，断言没有principal、token不存在、不重建成员。同步点可以是窄观察拦截而非mock整个会话/Redis后端；不能依赖无界随机压测碰运气。

### 保持G2总门禁未完成的其他项

- 绝对会话生命周期、浏览器cookie与服务端TTL约束。
- 账号/租户实时状态与权限版本，不只依赖撤销索引。
- reset-password、delete、force-logout、tenant suspend的过期索引专项；现有立即路径回归不能自动覆盖这些时间边界。
- Redis不可用/脚本失败必须拒绝授权；没有新增fail-open。
- create/revoke全链路并发、跨库/跨实例事务，以及Redis Cluster多键同slot不在本部署切片。
- CSRF等其他认证/生产安全要求。

## 7. 运行记录位置与当前状态

本审查读取时 `docs/release/evidence/release_g2_20260924_red/full-maven-suite.txt` 正在写入，尚无最终 `full-maven-suite.command.json`。因此新测试的实际执行数/失败/错误/跳过此时均为 **待完成记录**；静态发现5个测试方法不能替代JUnit执行数量。

完成后需核对：

1. 初始RED确实使用旧 `SessionService`、来源快照固定；失败定位到上述新增断言，而不是数据库保护/启动错误。
2. 每条失败保留原始日志/JUnit；不把现有顺序logout通过算成旧实现全部正确。
3. GREEN使用同一测试断言（如需修正oracle必须单独审查并保留失败历史），新增与既有影响面全部运行。
4. 固定候选提交后全量复跑，SHA和源码摘要匹配；不得把旧绿色报告移植到新候选。

## 8. 首轮5例RED的独立核验结果

本节追加实际完成的证据，不覆盖前述“当时仍运行”的记录。

- 路径：`docs/release/evidence/release_g2_20260924_red/`。
- 命令：`full-maven-suite.command.json`，SHA=`bf320b69a24739166bb23ffb283eba0f48523946`，源码摘要=`9cec5cb7270868e2fe1c6336f6daec3f70b5a8cda40560fe7ad6fd1f01841f37`，sourceUnchanged=true。
- 时间：`2026-09-24T08:07:18.1287724Z` 至 `2026-09-24T08:10:02.0941921Z`；nativeExitCode=exitCode=1。
- 独立逐份解析74个JUnit XML：**279执行、4失败、0错误、0跳过**。排除新增类后的原274例为0失败/错误/跳过。
- 新类5例中4失败：缺成员、禁用后、改密后三个用例都真实得到旧cookieHTTP200而不是401；续期例读到索引TTL **29982ms**，未超过要求的600000ms。顺序logout用例通过。
- 日志SHA256独立重算匹配：`09f7d38c9d74c69f8508e80f9a53560aee28490af2025a95be58537b0443717f`。

**结论：有效RED，缺陷已由真实隔离存储与真实API调用复现，不是环境启动错误或删除旧测试换取结果。** 产品修复尚未完成，不能将这份失败基线标PASS。

## 9. 新增两条定序交错回归的独立审查

新增后此类共7例；原5例和原全套断言未降低。新增两例由真实服务/Redis和窄依赖包装确定执行顺序，不使用Mockito或伪造Redis结果。它们是**对合法竞争时序的确定性模拟**，不是多线程压力测试、概率压测或全部并发行为的穷尽证明。

### 9.1 `revokedAfterPayloadReadMustNotAuthenticateOrRenew`

- 使用真实登录产生session，再单独构造相同配置的 `SessionService`，替换的只有 `ObjectMapper.readValue` 窄观察点。
- 包装器先委托Spring实际ObjectMapper解析真实JSON，再在SessionPrincipal已读、调用方尚未完成身份返回时执行真实 `sessionService.revoke(sessionId)`。
- 没有返回人造principal或Redis值；真正删除发生在同一个隔离Redis实例。
- `competingRevokeRan` 必须为true，确保观察点实际执行；`find()`必须empty，之后通过真实HTTP链再次断言401和token不存在。
- 对当前旧代码而言，该测试可以捕获“token被删后仍返回已经读出的principal”的安全缺陷。对新增Lua而言，若它在解析后原子比较当前token正文/成员，才能正确拒绝旧读取。
- 这是有效的服务级时序回归，同时有HTTP后置验证；不能描述为整个请求链已真实同时运行两个线程。

### 9.2 `retainingCurrentDeviceMustNotExposeAnEmptyAuthorizationIndex`

- 使用真实Redis连接构建 `StringRedisTemplate` 子类，`delete(String)`先执行 `super.delete`；仅当被删的是当前用例的用户索引时，再调用原Spring `SessionService.find(retained)`。
- 这个观察点模拟另一请求恰好落在客户端 `DEL index → SADD retained` 两条命令之间，不虚构空索引。
- 旧find忽略成员关系，所以该场景可能通过；如果先只修find为成员缺失即拒绝，却不修撤销的中间空窗，它应能暴露合法保留设备被错误注销。这一中间修复的RED必须实际运行，当前不能预填。
- 最后仍独立断言保留设备HTTP200、其他设备401且token删除、索引精确仅含保留session。

**重要证据限制：** 若最终实现用一条原子服务端脚本替换索引，不再调用客户端 `delete(String)`，该特定hook不会触发；`retainedWasValidDuringReplacement`初值为true，末态断言仍会执行。因此它是针对已知客户端DEL/SADD窗口的结构敏感回归，不能凭最终绿色声称“最终版本强制执行过同一中间竞争请求”。消除不可观察的中间状态要结合最终原子脚本审查；不能强行要求hook执行从而要求实现重新暴露不安全窗口。

### 9.3 范围与下一步

- 两例补充了 TI-G2-01 的已知时序覆盖，但并未证明所有创建、撤销、角色变更、Redis故障和多线程竞争。
- 当前 `release_g2_20260924_race_red` 全量仍在运行，本审查未见其最终命令记录；7例真实统计保持待完成，不用5例报告代替。
- 若中间find-only修复真实暴露保留索引空窗，规范需要显式增加“保留当前设备时原子替换用户会话索引”这一最小依赖修复；不能继续写成只修改find，也不能扩大宣称解决全部create/revoke竞态。
- 继续要求新增测试来源不漂移、保留旧5例RED、保留新增时序RED、中间修复失败及最终GREEN各自的独立证据。

## 10. 两条交错回归加入后的旧实现RED

独立核验 `evidence/release_g2_20260924_race_red/`：

- HEAD仍为 `bf320b69a24739166bb23ffb283eba0f48523946`，sourceDigest=`b5a99433d95eb5769c3712ce0ad3f55a38b4e66bbecf256038abac4532985068`，sourceUnchanged=true。
- 命令时间 `2026-09-24T08:13:23.7956102Z` 至 `2026-09-24T08:15:51.0464048Z`，exitCode=nativeExitCode=1。
- 直接解析74个JUnit XML：**281执行、5失败、0错误、0跳过**；原274例无失败/错误/跳过。
- 新类7例中5失败。原有4个失败保持：缺成员、索引过期后的改密/禁用仍返回HTTP200；索引TTL为29988ms，未同步续期。
- 新的 `revokedAfterPayloadReadMustNotAuthenticateOrRenew` 失败明确显示 `find()` 返回包含旧 `SessionPrincipal` 的Optional而不是empty，证明旧实现把已经真实撤销后的旧读取仍当身份返回。
- `retainingCurrentDeviceMustNotExposeAnEmptyAuthorizationIndex` 在旧find下通过，符合该用例预期只在缺成员fail-closed修复与旧客户端索引替换组合下暴露回归的设计，不能称它已复现空窗故障。
- 日志SHA256独立重算匹配：`3f73301cd24bff39878748b817ec766b559b70dc81b9e1630d136d1ec2564ce7`。

**证据解释：旧实现的新时序安全缺陷已有效复现，但这不是多线程吞吐或概率压力结果。**

## 11. 保留参数不得重建失效授权：第8/9例审查

当前新增类共有9个测试方法，前7个断言未降低。最新两个用例在 `find` 已改为原子GET比较/SISMEMBER/PEXPIRE、`revokeOtherSessions`仍旧实现的阶段加入；它们及保留空窗测试将由独立的 `gap_red` 全量命令实际验证，不能从前述7例推断结果。

### `retainingAlreadyLoggedOutDeviceCannotRecreateItsMembership`

- 两次真实登录，同一专属账号产生removed/other两个token。
- 对removed实际调用HTTP logout，并独立断言Redis正文已经不存在。
- 调用真实 `sessionService.revokeOtherSessions(account.id(), removedId)`，要求失效ID不能被重新写回索引，且removed/other后续HTTP均401、token均不存在。
- Oracle来自授权状态不可凭调用参数恢复的安全不变量，不来自预期实现的Lua结构。即使正文没有恢复，重新登记已经登出的成员也是本切片明确禁止的状态。

### `retainingUnindexedTokenCannotReauthorizeIt`

- 两次真实登录，使用真实Redis SREM只移除orphan的成员资格，并断言移除数量为1、正文仍存在。
- 调用真实 `revokeOtherSessions(..., orphanId)`，要求不能仅凭keepSessionId参数把原非成员变回成员；orphan与other真实HTTP均401且正文不存在。
- 这是与“正文已删除”不同的独立反例：仅检查token EXISTS会错误允许原非成员重新获授权，故两个用例不可相互替代。

### 完整性结论与边界

- 静态test-integrity通过：真实服务/Redis，真实HTTP后置验证，精确自有fixture；没有mock返回值、取消旧断言或关闭数据库保护。
- 这两个是服务级非法保留参数回归，不是通过前端输入攻击keepSessionId的证据；现有正常API仍从当前principal派生参数，实际并发/撤销导致失效参数的边界需结合服务设计理解。
- 在这一阶段应先让9例真实跑完RED，不允许为保留旧SADD逻辑而把上述两例改成期望成员恢复。
- 本段撰写时 `release_g2_20260924_gap_red/full-maven-suite.command.json` 尚未生成，实际统计为**待完成**；不预填3失败或任何通过数量。

## 12. find-only 中间补丁的真实 gap RED

新增证据已完成，独立重数 `evidence/release_g2_20260924_gap_red/junit/` 中74个XML：**283执行、3失败、0错误、0跳过**；原274例仍无失败/错误/跳过。9个新例中6通过、3失败。

- 命令SHA=`bf320b69a24739166bb23ffb283eba0f48523946`，sourceDigest=`14dd73e07618d70f3bd3a66e46f84b32dc8a44ded987ed908d85862d5f914c16`。
- 时间 `2026-09-24T08:16:47.7769126Z` 至 `2026-09-24T08:19:04.9008599Z`；exitCode=nativeExitCode=1，sourceUnchanged=true。
- 日志hash独立重算匹配：`652474a4f6d1b5c361a689bed4ca1083ad724456effaba6c60e8b03cba994c75`。
- 当前实际测试来源是仅为find新增GET内容比较、SISMEMBER和两键PEXPIRE原子脚本，而撤销仍有客户端DEL→SADD。不能把这份失败称作最终修复通过。

三个失败与预期风险一一对应：

1. `retainingCurrentDeviceMustNotExposeAnEmptyAuthorizationIndex`：真实DEL后的定序find返回false，`retainedWasValidDuringReplacement`实际为false，合法当前设备被误撤销。该测试现在已证明对中间补丁有回归敏感性，不再只是静态风险。
2. `retainingAlreadyLoggedOutDeviceCannotRecreateItsMembership`：预期false的成员资格实际为true，旧撤销逻辑重新登记已无token正文的已登出ID。
3. `retainingUnindexedTokenCannotReauthorizeIt`：预期false的成员资格实际为true，旧撤销逻辑重新赋予原非成员token成员资格。

此前TTL、成员缺失、过期索引改密/禁用和read→revoke用例已通过，说明失败不是原5类问题未修，而是只修find后与既有撤销流程之间的实际组合缺陷。旧274例继续通过也不能掩盖这些新增安全/可用性失败。

规范已显式扩展到原子替换保留会话索引，并声明不无条件恢复缺失token/非成员；仍保留create/disable全部竞态、绝对超时等后续门禁。该扩展与真实复现一致，不属于暗中扩张或降低验收标准。

**test-integrity判定：三阶段RED均为有效且可区分的证据，9例静态设计与真实回归敏感性通过审查；最终业务修复GREEN尚待新来源证据。**

## 13. 原子撤销修复后的 GREEN：独立重数及来源核对

本节记录最终实现的首次完整绿色结果，保留此前三次真实RED，不将它们改写成一次通过。

- 证据目录：`evidence/release_g2_20260924_green/`；直接解析74个 `TEST-*.xml` 的实际testcase节点，得到 **283执行、0失败、0错误、0跳过**。其中原274例与新增9例均通过。
- `full-maven-suite.command.json`：SHA=`bf320b69a24739166bb23ffb283eba0f48523946`，sourceDigest=`e929db3ba81409b1f906cd3ba21a8df27b840fc0141fe505fc51a2e5bff1023e`；exitCode=nativeExitCode=0，sourceUnchanged=true。
- 命令时间：`2026-09-24T08:19:52.8516403+00:00` 至 `2026-09-24T08:22:22.9475872+00:00`。这是显式执行Maven `test`、启用Testcontainers的真实运行，不是skipTests编译。
- 独立重算日志SHA256与命令记录一致：`35811f7abc8361c613e71ba27546c120c33339bba20d38a682c0d5dd5abe5688`。
- 对 `gap_red` 与 `green` 的source snapshot双向比较：两边 `/src/test/` 均为 **76个文件，路径与SHA256差异0**；没有删除测试或降低断言来换取GREEN。新增9例源码12246字节，SHA256=`e0d07aad5f077c686e418903906dbc676f0f8cf9248dd332e9d40653f8c9cfc6`，与当前工作区文件一致。
- 实际Spring证据的runId、Git SHA及sourceDigest与命令一致：194映射；专用数据库 `jkxt_test_release_g2_20260924_green`；Flyway版本30、失败迁移0；Redis PONG；实际Tomcat监听 `127.0.0.1`。这仍只是隔离test profile证据，不是生产部署证明。
- 存在Surefire dumpstream关于Manifest-JAR绝对classpath根路径的启动提示；未将该文件删掉或据此忽略失败。本次XML实际全量执行且原生命令退出0，提示本身不计JUnit error。

### 最终实现与测试预期是否同源自证

`find` 的脚本在同一Redis原子步骤中比较已读取JSON、验证Set成员后再续期两键；已撤销/已替换正文不得返回旧principal，缺成员删除孤儿正文而不是重新登记。`revokeOtherSessions` 在同一脚本中先验证保留token仍具备成员资格且正文存在，再删除已观察的其他token并原子替换索引；已logout/原非成员均不重获资格。上述行为分别由先前真实RED证明敏感的外部HTTP/Redis状态断言约束，不是仅检查脚本字符串。

保留设备测试的已知限制继续有效：最终原子脚本不再触发客户端DEL观察hook；其GREEN结合脚本原子性审查和真实HTTP/Redis末态断言成立，不等于最终版本执行过该中间竞争窗口。两个定序交错用例不是多线程压力测试。

**切片结论：本次会话索引同步续期、孤儿会话fail-closed、读取后撤销拒绝、保留索引原子替换及不重建失效授权的测试完整性通过。** 该结论限定到上述源码摘要及9例切片；尚需固定候选提交后在全新隔离环境复跑，不能提前替代frozen结果。AUTH-02整体保持部分覆盖，绝对超时、创建/禁用完整并发竞争、角色/账号状态版本与Redis异常等后续验收不在本次通过范围；全站、真实外部服务及上线验收也未据此宣称完成。
