# G2首个修复切片：会话续期与撤销索引（2026-09-24）

仅在固定版本G1复跑完成后实施。保留Java/Spring/Redis协议与现有登录界面，不合并WIP，不扩大到生产。范围为原矩阵AUTH-02的一部分，不能将整个AUTH-02标为PASS（服务端绝对生命周期尚未在本切片实现）。

## 复现与验收

1. 在专用测试库创建唯一合成账号并通过真实Spring安全过滤链登录A/B。将该账号Redis索引寿命缩短，等待Redis自然过期；不改系统时间，token仍存在。
2. 合法新登录C后调用真实改密API，旧B请求必须401、C仍200，数据库新密码hash必须匹配。
3. 超管禁用索引已失联的账号后，旧会话不得继续请求，数据库status必须disabled。
4. 活跃合法请求必须同时续期token与用户索引，不能只给token续期；缺失索引或成员资格必须401并移除孤立token，不能根据旧token重建授权索引。
5. 已撤销token不得因续期操作重新出现；相关既有登录/多端改密/禁用/租户/RBAC测试全部回归。
6. 在真实Redis读取token正文后、续期前插入一次真实撤销，旧读取不能继续授权。使用委托真实JSON解析的定序注入，不把它称为大规模并发压力测试。
7. 处理改密保留设备时，不能暴露客户端DEL索引→SADD保留会话的空窗。独立审查G2-SR-01指出仅修find会造成误踢；先验证此组合回归，再做原子索引替换。

## 最小设计

保持现有Redis键与JSON结构。读取并解析principal后，使用小型原子Redis脚本校验token内容仍为读取值、用户索引仍包含sessionId；仅同时满足时为两键续期。缺索引/成员关系拒绝身份，删除孤立token，不重新SADD。这样不会把并发撤销和续期之间的旧读取变成凭据复活。

撤销使用显式KEYS：索引、保留token、已观察到的待撤销token。脚本内检查保留token与成员资格仍存在，撤销快照token并原子替换索引；不能无条件恢复已不存在的保留会话。不在Lua中动态拼接未知键，不恢复token正文。索引成员快照采集与新的登录签发未统一加锁，因此完整create/disable竞态仍保留为后续G2工作。

依据：[Redis脚本原子执行](https://redis.io/docs/latest/develop/programmability/eval-intro/)、[Spring Data Redis脚本执行](https://docs.spring.io/spring-data/redis/reference/redis/scripting.html)。本部署为已声明单实例Redis；若未来采用Redis Cluster，多键同slot策略需单独设计验证，不将现有实现宣称Cluster兼容。

## 执行与回滚

- 先添加独立账号、真实Redis/数据库/MockMvc回归，保持业务源码旧行为，保存真实RED；旧测试和数据库保护器不改。
- 最小修改`SessionService.find`、`revokeOtherSessions`及静态脚本；不把安全存储故障改成允许访问，不改CSRF/登录协议。
- 新鲜隔离全套GREEN，再固定代码并全套复跑；保留所有失败记录。
- 回滚点是G1固定提交，使用可审阅revert或候选工作树，不覆盖原工程。没有部署动作。
- 该切片不解决全部并发创建/撤销、绝对超时、角色版本、学校停用实时检查或CSRF；这些继续列在G2门禁，不隐去。
