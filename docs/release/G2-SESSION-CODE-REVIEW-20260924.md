# G2 会话索引切片独立代码审查

日期：2026-09-24。审查任务：`g1_gate_independent_review`。最终状态：**本会话索引切片有界 PASS**；下文保留设计发现及 RED 历史，最终 GREEN 复核见末节。不是完整 G2 / AUTH-02 或生产发布许可。

## 范围与证据等级

- 基线 HEAD：`bf320b69a24739166bb23ffb283eba0f48523946`。
- 仓库：`D:\VB\JKXT-release-g0-g1`；目标：`SessionService.find` 的索引一致性与原子续期切片。
- 已深读：`G2-SESSION-INDEX-SPEC.md`、完整 `SessionService`、`SessionAuthenticationFilter`、`SessionPrincipal`、`AuthService` 登录/改密/退出调用链、`AdminPlatformService.accountAction` 撤销链，以及新 `SessionIndexExpiryIntegrationTest`。
- 本次只读源码/测试；未修改产品和测试，未运行 Maven，未连接 Redis 或业务数据库。以下竞态是**代码与可行事件顺序证据**，不是伪称已执行的真实并发测试。
- 已向主执行者传达关键发现。RED / GREEN 的真实退出码和报告需由执行者另行采集，本报告不代填。

## 必须处理的切片回归

### G2-SR-01：非原子的索引重建会让新 find 错删保留设备凭据

**严重性：Medium；信心：High（命令顺序直接支持）；实际运行复现：待补。**

位置：基线 `SessionService.java:112–116`，`revokeOtherSessions` 依次 `DEL userKey`、`SADD keepSessionId`、`EXPIRE userKey`。`AuthService.changePassword` 依赖它保留当前设备登录。

当新 `find` 按计划把“索引不存在/无成员”判定为孤立 token 并删除时，存在以下合法交错：

1. A 为当前改密设备；A 的第二个标签页/并发请求也携带有效 token。
2. 改密线程撤销其他设备，执行 `DEL userKey`。
3. A 的并发请求读取 A token，执行计划 Lua；此时索引尚未重建，Lua 删除 A token并拒绝认证。
4. 改密线程 `SADD A` 重建索引；不会重新创建 token。
5. A 之后请求持续 401，违反 spec 第 2 项“C仍200/改密者保持登录”。这不是攻击者获得权限，而是新索引判定与旧撤销实现组合产生的可用性回归。

最小方向：将保留索引的替换做成一个 Redis 原子步骤，或采用没有中间空索引窗口的撤销实现；不能通过 `find` 重新 SADD 授权成员来规避，因为那会恢复已撤销授权。整批撤销/索引替换若使用 Lua，需要同时保持单实例 Redis 的明确部署边界。

推荐真实 Redis 定序回归：测试专用委托/spy 仍执行真实 Redis 命令，在旧实现 `delete(userKey)` 的真实删除完成后、方法返回前，执行第二个真正 `SessionService.find(A)`；断言 A token/身份最终仍有效、B已失效。该定序器只控制调度，不返回伪造 Redis 值。修复后若切换原子脚本，不应为满足测试强求继续存在客户端 `DEL` 接缝；应以保留 A、撤销 B、无授权恢复的业务断言为准，并增加原子实现的集成回归。

## 拟定 Lua 的正确性条件

计划中的 `GET token == 已读取原JSON` 与 `SISMEMBER user-index sessionId` 的联合原子检查是正确方向，需确保实现满足：

1. **内容校验先于删除/续期**：若 token 当前不存在或不再等于已读 JSON，返回拒绝，不按旧读值删除另一个当前 token，也不续期它。
2. 仅在 token 内容匹配且成员资格存在时，对两键 `PEXPIRE`；缺失成员时只删除该匹配的孤立 token，不新建 token，不 SADD 索引。
3. 脚本返回成功之前不得向安全过滤器返回旧 principal。Redis 故障必须 fail-closed，不能 `catch` 后使用内存中旧 principal 放行。
4. 说明撤销语义以 Redis 原子操作为线性化点：若 `find` 已在撤销之前成功，已进入的请求不能被本方案追回；本切片承诺的是撤销后的认证检查，不是终止所有在途业务事务。
5. 同一用户 token 与索引采用现有单实例键结构，多键脚本不可直接宣称 Redis Cluster 兼容；spec 已明确排除 Cluster，本审查不要求跨越此范围。

## 必须补强的撤销竞态测试

当前新增 `loggedOutTokenIsNotRecreatedBySubsequentRequests` 是“先完整 logout，再 find”的顺序用例，不能证明 `GET → revoke → Lua/续期` 竞态。建议补一个最小真实 Redis 定序测试：

1. 为独立合成用户通过真实 `SessionService.create` 创建 token/索引。
2. 让待测 find 完成真实 GET，暂停于 JSON 解析返回与 Lua 调用之间。例如测试专用 ObjectMapper 委托在真实 `readValue` 后用 latch 暂停，不替换反序列化结果。
3. 另一线程/连接真正执行 `SessionService.revoke(sessionId)` 并确认 token 已不存在。
4. 释放 find，断言返回 empty、token 不存在；再次 find 仍 empty。不得只验证最后不存在，却容许本次返回已撤销 principal。
5. 加入 token JSON 在 GET 后变化的 CAS 反例，确保旧读值不会给新内容续期，也不会误删它。若应用当前没有同 ID 写入接口，该项属于脚本防御性校验，不应包装成已存在的可远程利用漏洞。

## 已存在而本切片不能宣称解决的边界

### 并发 create 与管理员禁用/强退

`create` 的 SET token、SADD index、EXPIRE 是独立命令；登录在调用 create 前读取账号/学校状态和密码。`revokeOtherSessions` 先读成员，再删 token，最后重建索引。存在登录已验证旧状态、管理员随后禁用并撤销、登录最后才创建 token/成员的顺序。新 find 会见到合法成员并放行，不能仅凭索引检查察觉账号已禁用。

这是现有签发/撤销协议与持久化状态竞态，**不是此次 find 修复引入的新授权漏洞**。后续 G2 需通过可证明的凭据/权限版本、签发和撤销协调或请求级状态策略明确解决，并补“禁用/改密与在途登录”的真实定序测试。简单把 create 三步 Lua 化只能修复 Redis 内部中间态，不能解决数据库状态校验到签发之间的窗口。

### 两个并发改密/撤销操作

现有 `revokeOtherSessions` 多命令序列相互交错时，可能删掉另一方保留 token或索引；数据库改密还有事务与乐观锁边界。不能在本切片报告中声称“全部多端竞态已解决”。原子化 Redis 撤销可缩小问题，但不能代替 DB提交/Redis撤销跨系统策略验收。

### 既有其他风险

绝对会话寿命、角色/权限更新实时失效、学校状态实时检查、CSRF、外部部署安全及未知损坏 JSON的处理未在本任务中完整验证；保持 G2 未闭环，不因单切片 GREEN 改写为全站/上线 PASS。

## 当前审查结论

计划的 compare-and-renew 可以修复原索引失联与单 token撤销窗口，但必须先消除 G2-SR-01 的保留设备索引空窗，并增加 GET 与撤销交错的真实 Redis 断言。本审查暂不给该安全切片最终 PASS；待源码实现和真实 GREEN 产物提供后复核。

## 追加：原子撤销方案复核

主执行者已采纳 G2-SR-01，提出在客户端读取待撤销成员快照后，把索引、保留 token、快照内待撤销 token 键通过显式 `KEYS` 传入 Lua；脚本内验证 keep 仍是索引成员且 token 存在，删除快照内 token，原子清理索引，仅对仍有效 keep 恢复索引成员与 TTL。无 keep 时不得误访问占位参数对应的其他授权键。

**方案层面结论：有界可接受，未发现比原实现更宽松的授权路径；实现/运行验收尚待完成。**

- 在 Lua 内重新判断 keep，避免已注销 token 的 stale keepId 重建授权索引。若 keep token 仍在但原索引无其成员，也不得重新赋予成员资格。
- 待撤销 token 列表必须在客户端排除 keep；原子块中判断 keep 有效后又把它列入删除列表，会留下“有索引无 token”的错误状态。
- 对仍有效 keep 的 `DEL index → SADD keep → PEXPIRE index` 均位于同一脚本，Redis 客户端不再能观察到中间空索引，消除前述新 find 组合回归。
- 客户端快照不是完整账号级撤销锁。新会话 C 在快照之后、脚本之前加入索引时，C 不在 token 删除列表但会被移出索引；后续 find 将拒绝并删除该孤立 token。结果偏向额外退出而非授权恢复，应保留这个已知可用性边界，不能声称并发登录体验完全验收。
- 若 create 在撤销脚本之后继续签发并加入索引，仍可能形成有效会话；Lua 原子性不能修复“旧密码/旧账号状态已验证，但签发晚于禁用/改密”的 DB—Redis 跨系统竞态。必须继续列为后续 G2 阻断范围。
- 通过显式 `KEYS` 提供访问键，优于在脚本中凭前缀动态拼接未声明键；现有不同前缀仍未形成 Redis Cluster 同 hash slot，保持 spec 的单实例范围。
- 不应使用无界 `unpack(KEYS)` 批量删除依赖 Lua 调用栈极限；顺序删除已传入的待撤销键即可。本切片未引入新会话数量上限，不借此声称解决长期存活索引的容量管理。

已只读查看新增两个确定性测试：

1. `revokedAfterPayloadReadMustNotAuthenticateOrRenew` 通过具体 ObjectMapper 委托真实解析，再执行实际 `SessionService.revoke`，不 mock Redis 返回；有 `competingRevokeRan` 断言，确实覆盖 GET 后撤销、身份返回前的窗口。
2. `retainingCurrentDeviceMustNotExposeAnEmptyAuthorizationIndex` 通过具体 StringRedisTemplate 委托真实 DEL 后触发真正 find；最终检查保留 Cookie仍 200、其他 Cookie401/token不存在、索引只含保留会话。修复为单一脚本后，不要求继续暴露旧客户端 DEL 接缝是合理的；但主执行者仍应保留“只修 find”的真实 gap RED 来证明此测试曾针对正确原因失败。

建议追加或在相邻既有回归中明确覆盖：已经 logout 的 keep token不会被重建成员；token尚在但 keep无原成员资格时不会获得授权。测试均应使用专属隔离 Redis 键，不以脚本 mock 代替真实存储。

此追加仍是方案/测试结构审查，未把主执行者所述“运行中”改写成实际 GREEN。最终结论需补对应命令、退出码、XML与冻结身份。

## 追加：最终产品代码及三次真实 RED 复核（08:21 UTC）

已重新逐行审查最终 `SessionService` diff，并独立读取三个隔离运行的 `.command.json`、命令日志字节、所有 JUnit XML及前置源码快照。结论：**最终代码切片未发现新增阻断缺陷；G2-SR-01 已有最小实现修复，但本时点 GREEN 仍运行中，不宣称测试通过。**

最终源码身份：

- HEAD 仍为 `bf320b69a24739166bb23ffb283eba0f48523946`，产品修复当时未提交。
- `SessionService.java` SHA256：`e88238c56b137cd99c1b9b249b8b8f7844bf0a1525ad88cbdddfc8addac06257`。
- `SessionIndexExpiryIntegrationTest.java` SHA256：`e0d07aad5f077c686e418903906dbc676f0f8cf9248dd332e9d40653f8c9cfc6`。
- 数据库保护器 SHA256：`1164a74a9b6e0d09792d1d5918143872bac96d27fd6856a82fa10a6f922805f6`，未删除或弱化。
- GREEN 前置完整源码 digest：`e929db3ba81409b1f906cd3ba21a8df27b840fc0141fe505fc51a2e5bff1023e`。

| 真实隔离运行 | XML文件 | 发现/执行 | 失败 | 错误/跳过 | exit/nativeExit | 本审查独立确认 |
|---|---:|---:|---:|---:|---|---|
| `release_g2_20260924_red` | 74 | 279/279 | 4 | 0/0 | 1/1 | 原始索引续期、孤立成员、改密、禁用缺陷；日志 hash 匹配，sourceUnchanged=true |
| `release_g2_20260924_race_red` | 74 | 281/281 | 5 | 0/0 | 1/1 | 保留上述四失败，新增 payload 读取后撤销仍认证失败；日志 hash 匹配，sourceUnchanged=true |
| `release_g2_20260924_gap_red` | 74 | 283/283 | 3 | 0/0 | 1/1 | 仅 find 修复后，前五项通过；保留设备空窗、logout keep恢复成员、原非成员keep重新授权三项实际失败；日志 hash 匹配，sourceUnchanged=true |

第三次 RED 的失败用例名：

- `retainingAlreadyLoggedOutDeviceCannotRecreateItsMembership`
- `retainingCurrentDeviceMustNotExposeAnEmptyAuthorizationIndex`
- `retainingUnindexedTokenCannotReauthorizeIt`

三个运行的 source digest 按时间分别为 `9cec5cb7270868e2fe1c6336f6daec3f70b5a8cda40560fe7ad6fd1f01841f37`、`b5a99433d95eb5769c3712ce0ad3f55a38b4e66bbecf256038abac4532985068`、`14dd73e07618d70f3bd3a66e46f84b32dc8a44ded987ed908d85862d5f914c16`。不能混淆这些有意保留的不同实现/测试版本。

前置源码快照差异的独立检查：第一次 RED→race RED 只改新增测试类；race RED→gap RED 只改 SessionService 与新增测试类；**gap RED→GREEN只改 SessionService**，测试字节完全相同，未通过削弱断言或移除失败测试得到绿色。

最终代码逐项结论：

1. `REFRESH_SESSION` 先用原始 JSON 比较当前 GET；不匹配直接返回拒绝，合法陈旧 payload 不会给新值续期，也不会删除新值。匹配但无索引成员时仅删除当前 token，不 SADD；成功时才同时续期两键并返回身份。
2. 解析出的 null principal / null userId / 与请求 token不符的 sessionId 不会进入认证；null/blank请求参数早返回。损坏 JSON仍拒绝。该检查不被表述成“Redis内容任意篡改均已完整防御”，权限/机构等结构校验不在本切片扩展范围。
3. `REVOKE_OTHER_SESSIONS` 的 keepActive 在脚本内检查非空、成员资格和 token 存在；snapshot keys 列表排除 keep；删除和索引重建在同一脚本。不存在旧客户端 DEL→SADD间隙，也不会重建已 logout或原无成员的 keep。
4. null/blank keep 统一为空串，Lua短路后不读取第二个占位 KEY；占位采用同一索引键，未引入额外可删除的任意键。null userId 仍保持原有 no-op。
5. 脚本没有动态拼接 Redis key；所有实际访问均来自显式 KEYS。没有 `unpack`大参数调用；逐键删除返回数量，不因删除数为0就恢复授权。
6. Redis执行失败未降级成成功身份。并发创建晚于撤销的现有凭据版本问题仍留在注释/spec/本报告，不因原子 Lua“顺手”标为已解决。

本审查重算命令第一次因 PowerShell引号解析失败（exit1，未执行 Node/未访问DB）；改用 here-string之后实际执行成功（exit0，结果工具 chunk `e9fb86`）。源码差异/保护器复核 exit0，chunk `a0ad77`。这些是独立只读审查执行记录，不冒充产品测试运行。

## 最终 GREEN 产物独立复核

主执行方全套运行 `release_g2_20260924_green` 于 2026-09-24T08:19:52.8516403Z 开始、08:22:22.9475872Z 结束，Maven exit/nativeExit 均为 0。本审查随后直接读取最终记录与所有 XML并执行断言，没有只采用主执行者的口头汇总：

- 74 个 XML：283 discovered、283 executed、0 failures、0 errors、0 skipped。
- 新 `SessionIndexExpiryIntegrationTest`：9 个实际执行、0 failure/error/skip；前述三次 RED 的正确原因均被对应回归覆盖。
- 最终日志 SHA256：`35811f7abc8361c613e71ba27546c120c33339bba20d38a682c0d5dd5abe5688`，与 `.command.json` 相符。
- before/after 源码文件集合、字节 hash、聚合 digest、requiredClasses 均与审查时当前源码一致；`sourceUnchanged=true`。最终 digest 仍为 `e929db3ba81409b1f906cd3ba21a8df27b840fc0141fe505fc51a2e5bff1023e`。
- Spring runtime 194 条映射；runId/SHA/digest全部匹配；数据库实际名 `jkxt_test_release_g2_20260924_green`，Flyway V30/失败0，Redis PONG，服务绑定127.0.0.1。
- 独立复核最终命令 exit0，工具结果 chunk `61c344`。该只读探针的第一次草稿错误地比较了含额外 `requiredClasses` 的 capture-record 整体与基础 source snapshot，产生 exit1（chunk `b27379`）；随后改为分别比较全部 files、sha256及从当前源码独立发现的 requiredClasses，保留且解释审查脚本错误，不将其写成产品失败或隐藏重试。

**最终结论：规格和代码质量在这个会话索引切片范围内通过。** G2-SR-01 已经由真实 gap RED 复现，再通过原子撤销的同断言 GREEN 闭环；没有删除失败测试、绕过数据库保护或 mock 核心Redis行为。固定提交后的新一轮验证仍须由主执行者执行，当前报告不提前填入未来SHA。

整体 AUTH-02、绝对生命周期、create与禁用/改密并发的状态版本、CSRF和后续 G2–G8仍未因此通过。不得把此处有界 PASS替换为“全部会话问题或上线风险为零”。

## 固定候选后的第二次 GREEN / 有界验收

2026-09-24 冻结复核，当前实际 HEAD：`aa57a6bb8edb5d97fb20495f0744ea6a372d4be7`。独立重算当前非 release-doc 源码 digest 仍为 `e929db3ba81409b1f906cd3ba21a8df27b840fc0141fe505fc51a2e5bff1023e`；与前次 GREEN 的全部源文件路径、字节数、文件 hash **完全相同**，包括产品代码、9项新增测试和数据库保护器。不是将旧执行重新绑定新 SHA：以下是提交后真实新运行。

- `release_g2_20260924_frozen` 全套 Maven：08:25:27.5130266Z—08:27:41.7346997Z，exit/nativeExit=0；74 XML、283 discovered / 283 executed / 0 failures / 0 errors / 0 skipped。9项 SessionIndexExpiry 回归再次全部通过。
- 完整日志 SHA256：`77c24fc7e981bc6feb23c0cb3b8cbb37b588643138ce56b42d121eac06c368f7`。
- 本审查逐一断言冻结 docker-preflight、infrastructure-up、full-maven-suite、frozen-g2-coderabbit、frozen-g2-release-tools、frozen-g2-database-assertions、frozen-g2-gate 共7条记录：当前 SHA/digest一致，exit/nativeExit均0，日志hash匹配，sourceUnchanged=true，before/after files、digest、requiredClasses与当前源码一致。
- `frozen-g2-coderabbit`：实际 CLI committed review，基线 `bf320b69a24739166bb23ffb283eba0f48523946`，4个文件、0 findings、exit0；日志 SHA256 `1d5d496a6d84a106105342fd769dd003e0fb151d128ef8c5220df8fa677c35cf`。这是独立代码审查证据，不替代业务运行。
- `frozen-g2-release-tools`：实际 Node测试109项全部通过，0失败/取消/跳过，exit0；日志 SHA256 `9c1f027fceb2ebae31a50b938513ef46f813a7c2fb47bf3a5fce9726809e875a`。它们是发布工具测试，不能计成109项业务场景。
- `frozen-g2-database-assertions`：命令明确指定自有容器和 `jkxt_test_release_g2_20260924_frozen`，SQL使用 `ON_ERROR_STOP=1`及DO块，先拒绝错误库名、残留自有账号、失败迁移和错误版本，再输出断言值；exit0。实际结果：`owned_accounts_remaining=0`、`flyway_max=30`、`failed_migrations=0`。日志 SHA256 `2a857733e53b1c2404a37b5d0dbcb37946b9f309201a5b99ec18b6e12ae95209`。本审查读取产物，没有再次连接数据库。
- 冻结 Spring产物：194 mappings、正确测试DB名、Redis PONG、127.0.0.1绑定、当前SHA/digest均匹配。直接重跑只读 `validateRelease` 得 G0/G1 PASS、`productionReady:false`。

上述独立证据复核命令 exit0，工具结果 chunk `6b295c`；并非重跑 Maven或伪造第三方签名。

**固定候选结论：会话索引TTL与本报告定义的原子续期/撤销切片通过两次真实GREEN及独立复核；可作为该切片的候选交付。** 全产品部署放行仍为否；完整AUTH-02、并发create/禁用/改密的跨系统版本栅栏、绝对寿命和其余G2–G8必须继续执行，不能因本阶段闭环从阻断清单删除。
