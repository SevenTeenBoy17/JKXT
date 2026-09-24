# 附件入包与测试完整性独立审阅（2026-09-24）

## 结论及范围

**本次附件入包、来源一致性与新增门禁防退化检查：有界通过。不是 84 项业务场景通过，也不是部署放行。**

审阅者为独立子代理 `package_integrity_review`。未修改产品源码、验收工具或测试断言；只生成本审阅文档与独立测试证据。Supervisor 保持暂停，未调用其任何入口。

审阅对象是在 `D:/VB/JKXT-release-g0-g1`、HEAD `cfab40ec62979ea64ead58506d516399ce52378a` 上尚未提交的附件接入改动。测试运行前后源码摘要均为 `ed10e1a915603e52c90f8a3c29139483f07b064e1145e86531c8d50d6458169d`；主代理随后修改 inventory 生成器时，这些记录只证明所记录的版本，不能替代最终候选复测。

审阅文件：

- `scripts/release/execution-package.mjs` 及其测试。
- `scripts/release/check-release.mjs`、`check-release.test.mjs` 的本轮差异。
- `scripts/release/record-command.test.mjs` 的新增模块复制依赖。
- `scripts/release/initialize-scenarios.mjs`、`summarize.mjs` 的接入逻辑。
- 两份原始附件、保留副本、归一化执行包和三项旧门禁红测证据。

## 附件完整性

已读取矩阵全部 14 组、84 项场景及首尾状态/范围说明，架构文档全部 12 节及其中子标题正文。分别对下载目录原件与 `docs/release/inputs` 副本计算 SHA-256，四次计算成对一致：

| 原件/副本文件名 | SHA-256 |
|---|---|
| `02_后端与全栈验收矩阵.md` | `27289a1ca019087740f2ed7e7c83ea147db6e1726c7d33ff4034858628fe0c5c` |
| `03_架构与优化决策.md` | `227cd4656dbdd90cd4e0aada8f3832f659868e402f35e98dd24088ff18ed87a4` |

入包保留每个场景的编号、组、标题、给定、操作、期望、源路径和行号，并保留 12 节架构正文（含小标题），没有用标题列表代替要求正文。矩阵前言与“状态与范围”作为原始文件字节完整保留；归一化结构没有将这两段转换为业务场景，这是表示范围的边界，不是授权或断言豁免。

## 测试完整性审阅

1. 旧门禁三项真实失败记录 `attachment-package-red.command.json` 的退出码为 1。对应日志分别证明：篡改场景 ID、弱化期望、添加无来源场景后重新计算报告哈希，旧门禁未抛错。它们是门禁缺陷复现，不是业务缺陷或测试成功。
2. 修复后门禁从原件重新生成要求并逐字段对比 `baselineSha/sourceArtifacts/scenarios/architectureDecisions`。只重算归一化 JSON 哈希无法掩盖篡改。
3. 原有空报告、错误 SHA、失败/错误/跳过、缺失测试类/方法、静态路由冒充运行时等拒绝断言保持；未删失败测试或放宽关键期望。
4. 原门禁正向夹具由简化的“84 个标题”升级为明确标识 SYNTHETIC 的完整 Markdown 表与架构文本；这是使夹具符合新输入契约，并非产品场景成功证据。
5. 记录器测试只增加 `execution-package.mjs` 到临时仓库复制清单，以满足新增 import；退出码、源码变化拒绝和证据不可覆盖断言未改弱。
6. `initialize-scenarios.mjs` 的 84 项状态统一为 `NOT_RUN`，执行字段为空；SOLVE-03 的旧红绿证据仅写备注，未自动提升为 PASS。采用 `wx` 防止覆盖已有跟踪台账。
7. `summarize.mjs` 只消除“原始执行包缺失”这一已解决阻断，仍将真实测试基线标为 BLOCKED。其其余 Docker/运行时路由阻断目前是特定阶段说明，后续环境变化需实测更新；此脚本不是可直接给生产放行的通用聚合器。

## 独立实测

运行目录：`D:/VB/JKXT-release-g0-g1`。全程没有启动/连接数据库、Docker、真实会话或外部通道。

| 证据 ID | 实际执行 | 结果 |
|---|---|---|
| `independent-package-review-20260924` | `node --test scripts/release/check-release.test.mjs scripts/release/execution-package.test.mjs scripts/release/record-command.test.mjs` | 82 tests / 82 pass / 0 fail / 0 cancelled / 0 skipped / 0 todo；退出码 0 |
| `independent-package-probes-20260924` | `node --input-type=module -e <独立内存探针，完整源码记录于 command.json>` | 7 项断言通过；退出码 0；业务场景通过数明确为 0 |

两个命令记录的 `sourceUnchanged` 均为 true，原生退出码均为 0。输出哈希：

- 套件日志：`b64b60030854b4bff9a4861d26e4a2e8ab13b1148797de8bc0044f6ed3f64e08`。
- 独立探针日志：`dc4e6509957a5835f947a7f735900b4a37e62a7de82dc7299a686f52361e6d30`。

独立探针额外实际覆盖 Windows junction 将源路径指向仓库外的情况（拒绝），以及绝对路径、反斜杠路径、`../` 逃逸（拒绝）；核对执行包为 84 项/12 节，并在检查前后确认执行包和两份原件副本的字节哈希均未变化。临时目录经精确前缀及父目录确认后清理，未触及产品或业务数据。

## 剩余边界

- 没有发现本次接入改动的阻断级问题；这不涵盖整个业务后端的正确性、安全性和上线资格。
- 哈希与重新解析证明内容一致性，不是不可伪造的签名或独立身份认证。仓库写入者同时篡改原文和哈希仍需要 Git 审阅、固定候选及来源复核约束，不能宣称工具消除了恶意内部篡改风险。
- 当前正向验收夹具是临时合成数据，不应被纳入 84 项业务 PASS 计数。
- 新源码摘要改变后需重新绑定候选与受影响测试；不能沿用上一轮 122 项单测记录给新整体源码快照盖章。
- PostgreSQL/Redis 集成、实际 Spring 注册接口、Excel/导出内容、完整浏览器业务闭环与外部服务验收并未在本审阅执行；仍按真实状态保留 NOT_RUN/BLOCKED。

审阅意见：**允许合入本轮附件接入和门禁增强后继续隔离验收；不批准生产发布。**
