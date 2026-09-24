# G2读取范围增量

本文件补充G0机械清单的保守阅读状态，不把搜索命中或机器扫描说成全项目深读。

| 相对候选根路径 | 本轮阅读深度 | 用途 |
|---|---|---|
| `JKXT V2.0/backend/src/main/java/com/jkxt/security/auth/SessionService.java` | 全文深读、逐补丁审阅；独立代码审查 | 会话创建/读取/续期/撤销、Cookie及两个Redis原子脚本 |
| `JKXT V2.0/backend/src/test/java/com/jkxt/security/SessionIndexExpiryIntegrationTest.java` | 本轮完整编写、深读、独立test-integrity | 九项真实Redis/数据库回归；无核心mock |
| `JKXT V2.0/backend/src/test/java/com/jkxt/security/AuthIntegrationTest.java` | 全文深读，未改 | 原登录、登出、并发登录、改密、当前设备保留回归 |
| `JKXT V2.0/backend/src/test/java/com/jkxt/security/BusinessRbacAcceptanceTest.java` | 全文深读，未改 | 独立测试用户初始化、角色与真实请求链参考 |
| `JKXT V2.0/backend/src/main/java/com/jkxt/security/auth/SessionPrincipal.java` | 全文深读，未改 | 保留已有JSON/身份字段协议 |
| `JKXT V2.0/backend/src/main/java/com/jkxt/security/auth/SessionAuthenticationFilter.java` | 全文深读，未改 | 确认鉴权依赖会话查找，辨明租户实时状态未覆盖 |
| `JKXT V2.0/backend/src/main/java/com/jkxt/security/auth/AuthService.java` | 局部深读：会话签发/`me`/`logout`/`changePassword`；其他部分不宣称全读 | 追溯数据库副作用及撤销调用 |
| `JKXT V2.0/backend/src/main/java/com/jkxt/platform/service/AdminPlatformService.java` | 局部深读：租户停用与账号action；其他部分不宣称全读 | 追溯禁用/重置/退出/删除依赖 |
| `JKXT V2.0/backend/src/test/java/com/jkxt/platform/P10ReportAdminApiIntegrationTest.java` | 局部深读禁用与软删除回归 | 旧断言保持与影响面 |

全部原生套件被执行不等于全部源码已人工深读。代码图工具当前候选返回零节点，未将其风险分数作为证据。静态清单仍保留SCAN/UNREAD范围；最终全站浏览器与真实外部服务验收仍不在本切片证据内。
