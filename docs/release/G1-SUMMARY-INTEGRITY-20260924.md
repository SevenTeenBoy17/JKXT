# G1 摘要适配器独立 test-integrity / code review

审查时间：2026-09-24T07:52:25Z。独立审查任务：`g1_gate_independent_review`。

## 结论与范围

本次对 `summarize.mjs` 的未提交变更及新增 `summarize.test.mjs` 的有界审查未发现阻断缺陷。规格与测试完整性结论均为 **PASS（仅本次摘要适配器范围）**。不等于生产发布许可，也不等于 84 项业务验收通过。

真实产品执行记录由主执行方产生；本审查独立重算原始 XML、日志散列和源码绑定，并独立执行工具测试，未重新执行 Maven、连接数据库、操作真实 Cookie 或调用任何外部业务服务。未启动 Supervisor。未修改产品、测试或发布工具源码。

审查基准：

- 仓库：`D:\VB\JKXT-release-g0-g1`
- HEAD：`1d023c211cc222a8582e72f24b4c973d1199b086`
- 完整非 release-doc 源码 digest：`d56b000dd51eb95c1d17f90dea3b59d6eb075b5b6f469700af143173030be17a`
- `scripts/release/summarize.mjs` SHA256：`f19987b07b164b189b5a5a0d6b32b2d479aa80e287f6f0a9ea91702e533db060`
- `scripts/release/summarize.test.mjs` SHA256：`c3960657e2a893726ad52cf21e6ff26add1d40c38691cb8bf4c75882621e914c`
- `scripts/release/check-release.mjs` SHA256：`58aaeaba3bd82a79d1e050f6eadfcb40118a4b4ab747804bcdd5d4a935e2d2e9`

这些身份只覆盖上述内容；后续变更需重新验证，不能将审查结论直接绑定到新的源码。

## 执行与证据

| 检查 | 实际执行结果 | 边界 |
|---|---|---|
| `node --test scripts/release/summarize.test.mjs` | exit 0；27 tests / 27 pass / 0 fail / 0 cancelled / 0 skipped，约 24.2 秒 | 独立临时 Git fixture，非产品测试 |
| 独立额外 Node 探针 | exit 0；4 tests / 4 pass / 0 fail / 0 cancelled / 0 skipped | 重复 XML、零 testcase、重复 suite 属性、生产阶段误用 |
| 重读 `release_g1_20260924_r1/junit/TEST-*.xml` 并使用严格 JUnit 解析器累加 | 73 XML；274 discovered / 274 executed / 0 failures / 0 errors / 0 skipped | 读取已产生的真实执行字节，没有重新运行 Maven |
| 对三个 r1 命令重算日志 SHA256、读取 before/after source manifest | 三个日志 hash 均匹配；source digest 均匹配当前源码；均 exit 0 / sourceUnchanged=true | `docker-preflight`、`infrastructure-up`、`full-maven-suite` |
| 直接调用 `validateRelease({root, reportPath:'docs/release/baseline.json'})` | exit 0；G0/G1 PASS；`productionReady:false`；84 source scenarios；194 runtime mappings | 仅本地 G0/G1 证据门，不是上线门 |
| `git diff --check` | exit 0 | 无空白错误；不是功能测试 |

原始产品命令与产物均位于 `docs/release/evidence/release_g1_20260924_r1/`：

- `full-maven-suite.command.json`：2026-09-24T07:45:42.7098499Z 开始，07:47:56.3912496Z 结束；exit 0；记录了显式 Maven 路径、无筛选 `test`、启用 Testcontainers、loopback server、独立报告目录、runId / SHA / sourceDigest。
- `full-maven-suite.txt` SHA256：`effde67392e8677cca8435e017acf04b89b6a8efa99811522708cfef3b41df80`。
- `infrastructure-up.txt` SHA256：`f866bb27f1d279279b6b53279d03bdd1de4b8d72e239591e1520a88c86c8c883`。
- `docker-preflight.txt` SHA256：`14435b75cde3edb3cb665334dcab4439068716080f6fc9d2e618d188adac3be2`。
- `spring-mappings.json`：collection=`RequestMappingHandlerMapping`，194 mappings；实际 database=`jkxt_test_release_g1_20260924_r1`、migrationVersion=30、failedMigrations=0、redisPing=PONG、serverAddress=127.0.0.1；身份与本次执行记录一致。

独立审查终端结果定位（工具输出 chunk ID，仅用于本次会话定位，不冒充核心 Evidence ID）：27 项 `47e61b`；原始记录重算 `e9745c`；4 额外探针 `14e750`；源码身份复核 `588068`。

## 测试完整性与拒绝行为

1. 本 diff 未删除任何原有测试或降低原发布检查断言；新增 27 项明确标注为工具适配器 synthetic fixture，不冒充真实 Maven / DB 结果。
2. 新摘要器选中**精确隔离运行目录**；历史重复 `full-maven-suite` ID 的模糊选择被拒绝。旧失败保存在 history，而不与新运行相加，也不改写成成功。
3. 成功运行的 XML 来自隔离 runner 的 `junit/`，历史窄测试仍读 `<command-id>/`。后者有 `PARTIAL_EXECUTION` blocker，不再凭窄测试误称全套。
4. 原 27 项包含 missing reports / package / runtime、静态扫描冒充 Spring、错误 SHA / runId、源码漂移、失败基础设施 / Maven、选择性测试、skipTests、禁用 Testcontainers、报告目录错绑、失败 / 错误 / 跳过 XML 等反例；断言检查实际 BLOCKED / 具体原因，而非仅判断命令可运行。
5. 摘要命令成功退出 0 仅表示完成摘要；`baseline.status=BLOCKED` 仍不可放行。独立发布命令对于不合格 baseline 必须非零，此边界在测试中保持。
6. 本次额外探针在内存中复用 fixture 建造器，不写主源码：
   - 加入第二个相同 testcase XML，拒绝 `DUPLICATE_TEST`。
   - 替换成合法但零 testcase 的 XML，拒绝 `EMPTY_REPORT`。
   - 重复 `-Djkxt.release.runId=...` 参数，保留 `MISSING_OR_DUPLICATE_SUITE_PROPERTY` blocker。
   - 先产生有效本地 fixture baseline，再运行 `check-release.mjs --phase production`，exit 1 / `PRODUCTION_GATE_NOT_IMPLEMENTED`。
7. 原始 requirements package 与 runtime mapping 的最终校验复用现有严格 `validateRelease`，未新增更弱的并行“绿色”判据。

## 限制与交接

- 此审查没有执行 84 项逐场景端到端验收。274 个 JUnit invocation 与 84 项业务场景不是同一计量，不能互相替代。
- 未审查全部后台服务安全、约束完备性、通知送达、AI 实际调用、生产构建或部署恢复；这些仍属于后续 G2–G8。
- 参数化方法的全部实例数仍依赖可信 Surefire 运行，源码词法发现器只保守核对类和方法；当前工具不是签名证据系统。此既有边界不能因本次审查而取消。
- 本地 G0/G1 PASS 必须连同 `productionReady:false` 一起报告。任何新源码、测试或发布工具改动后，需采集新 source digest 并重跑对应验证。
