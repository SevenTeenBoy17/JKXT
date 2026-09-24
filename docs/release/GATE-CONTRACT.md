# G0/G1 本地证据门禁契约

## 目的与边界

`scripts/release/check-release.mjs` 是只读、失败即拒绝的证据检查器，不启动产品、不执行 Maven、不给生产发布授权，也不替代 Supervisor 或独立客户验收。

支持的阶段只有 `g0-g1`。即使完整证据通过，输出仍是 `productionReady: false`、`supervisorAcceptance: NOT_CLAIMED`。传入 `--phase release` 明确失败：后续业务、安全、部署、恢复、真实端到端和独立终审尚须另建门禁，不能被 G0/G1 的 PASS 代替。

```powershell
node scripts/release/check-release.mjs --root "D:\VB\JKXT-release-g0-g1" --report docs/release/baseline.json --phase g0-g1
node --test scripts/release/check-release.test.mjs
```

检查通过退出 0，任何缺失、篡改、未运行、阻断或不一致退出 1，输出 `JKXTLocalGateResult/v1`。检查器不修改输入报告。旧失败报告须保留，重新执行生成新 runId/新目录，不能覆写失败使历史变绿。

## 拒绝条件

1. schema、阶段不符；状态不是 `READY`；存在 blocker 或未提供 blockers 数组。
2. 实际 Git 根目录不符、报告 SHA 不等于当前 `git rev-parse HEAD`。
3. 当前源码文件集合、字节数、SHA-256、聚合 digest 与快照不符。快照由 `git ls-files --cached --others --exclude-standard` 枚举，包含未提交且非 ignored 文件，排除 `docs/release/**` 以防报告自引用。**该排除目录只允许存证据/说明，不可存可执行产品源文件。** Git 本来忽略的构建物、私密配置不属于源码快照；实际测试环境仍由隔离启动记录证明。
4. 没有命令记录、重复命令 ID、非 `EXECUTED`、非零退出码、无 argv/cwd、时间无效、运行 ID/SHA/sourceDigest 不一致、日志为空或 hash 不匹配。JUnit 所绑定命令含开启的 `-DskipTests` / `-Dmaven.test.skip` 时拒绝；只有显式 `=false` 可接受。编译跳过参数不能搭配旧 XML 冒充测试。允许多个有边界的选中测试运行合起来覆盖全部要求，不因存在 `-Dtest` 一刀切拒绝，也不把单个选中运行叫全套通过。
5. 没有 JUnit XML、XML 为空/截断/畸形、DTD/实体声明、重复报告/测试身份、零 testcase、声明计数与真实 testcase 节点不一致、报告字节 hash 改变。
6. 运行计数不是 XML 汇总；有失败、错误或任何跳过。G0/G1 本轮没有允许跳过清单。保留重试失败记录的 `flakyFailure`/`rerunFailure` 等不能被成功汇总掩盖。
7. 缺源码推导出的必需测试类或方法。发现器擦除 Java 字符串/字符字面量/文本块/注释后读取当前 Surefire 默认命名类与顶层 `@Test` / `@ParameterizedTest` 方法，排除无测试注解的 `TestDatabaseIsolationGuard` helper；要求**每个声明的测试方法**出现在 XML 中，不允许同类只保留一个绿色方法。`tests.requiredClasses` 必须精确等于源码排序集合，不能缩小。Surefire 的 `method(String, int)[1]` 归并为 `class#method`，保持运行参数化计数仍从 XML 取实际值。遇到继承、动态/嵌套、重载或无法识别的测试声明/显示名直接 fail-closed，扩展发现器后才可放行。当前基线是 71 类；新增 probe 会自动纳入。

   限制：方法覆盖不是参数组合穷尽证明。当前发现器不计算 `MethodSource`、动态 CSV 等产生的全部参数实例；这需要由可信全套 JUnit discovery/执行流水线另输出 invocation manifest，与各实例核对。局部参数实例被故意删除而所有方法仍各留一个，单靠当前源码词法门不能完整识别。因此此工具只支持 G0/G1 本地最低门，不能据此宣称完整生产业务验收。
8. 84 项原执行包缺失、实际场景少于 84、ID 重复或无标题、声明数量和实际场景数量不符、缺原执行包来源文件/hash。2026-09-24增强：重新解析原始矩阵及架构文档，逐项比较完整编号、组、标题、给定、操作、必须观察结果、源路径/行号、基线SHA及全部架构正文。即使重新计算JSON文件hash，也拒绝偷换场景、降低预期、删节或凭空补行。原始输入至少含matrix角色和恰好一份architecture角色，新增场景需追加可追溯的原始矩阵且基线一致。检查器不会创建伪造 84 条来补齐缺失输入。
9. 缺 Spring 实际注册接口产物、来源不是 `RequestMappingHandlerMapping`、不是隔离测试环境、无成功关联采集命令、身份或数量不符、为空、缺方法/path/handler。静态注解扫描只能进清单，不等同运行时证据。
10. 证据路径逃逸仓库（包括符号链接）、证据不存在、为空或 hash 不匹配。

## `baseline.json` 输入

```json
{
  "schema": "JKXTReleaseBaseline/v1",
  "phase": "g0-g1",
  "status": "BLOCKED",
  "blockers": ["示例：Docker 或 84 项执行包尚未具备；此示例必然拒绝"],
  "gitSha": "填写执行时真实 git HEAD，40 位小写十六进制",
  "runId": "本次不可复用的执行标识",
  "sourceSnapshot": {
    "files": [{"path": "仓库相对路径", "bytes": 123, "sha256": "实际文件 SHA-256"}],
    "sha256": "对排序后的 files 的 JSON.stringify 原样 UTF-8 进行 SHA-256"
  },
  "commands": [{
    "id": "full-maven-suite",
    "status": "EXECUTED",
    "exitCode": 0,
    "argv": ["真实可执行文件", "实际参数，禁止记录凭据"],
    "cwd": "JKXT V2.0/backend",
    "startedAt": "真实 ISO 时间",
    "finishedAt": "真实 ISO 时间",
    "runId": "同本报告",
    "gitSha": "同本报告",
    "sourceDigest": "同 sourceSnapshot.sha256",
    "log": {"path": "docs/release/某次运行/命令.txt", "sha256": "实际 SHA-256"}
  }],
  "tests": {
    "status": "BLOCKED",
    "requiredClasses": ["从当前源代码扫描得到的全部排序类名"],
    "discovered": 0, "executed": 0, "failures": 0, "errors": 0, "skipped": 0,
    "reports": [{
      "path": "docs/release/某次运行/TEST-某类.xml",
      "sha256": "实际 XML SHA-256",
      "commandId": "full-maven-suite",
      "runId": "同本报告", "gitSha": "同本报告", "sourceDigest": "同本报告"
    }]
  },
  "executionPackage": {
    "status": "MISSING",
    "scenarioCount": 0,
    "artifact": {"path": "docs/release/execution-package.json", "sha256": "实际 SHA-256"}
  },
  "runtimeMappings": {
    "status": "NOT_RUN", "count": 0, "commandId": "运行时采集命令 ID",
    "artifact": {"path": "docs/release/某次运行/spring-mappings.json", "sha256": "实际 SHA-256"}
  }
}
```

`READY` 只能在实际前提齐备时替换，不是检查器代填。环境失败用 `BLOCKED`、未执行用 `NOT_RUN`；局部测试已执行也应诚实记录，其不足会使全 G0/G1 门禁拒绝。测试 `discovered` 是所附 XML 中 testcase 数，`executed = discovered - skipped`；源级方法/类发现数请另存 discovery 清单，不混入运行参数化计数。

源码及类发现可直接复用导出函数：

```javascript
import { collectSourceSnapshot, discoverTestClasses, discoverTestMethods } from './scripts/release/check-release.mjs';
const sourceSnapshot = collectSourceSnapshot(repositoryRoot);
const requiredClasses = discoverTestClasses(repositoryRoot, sourceSnapshot);
const requiredMethods = discoverTestMethods(repositoryRoot, sourceSnapshot);
```

必须在执行前采集源码身份，在结束后复核不变；若代码或测试变了，旧执行不能绑定成新源码结果。日志与报告必须是这次真正执行的产物，不能重绑旧文件来满足字段。

## 执行包和运行时接口产物

执行包归一化格式（从实际提供的执行包提取，不另造最低数）：

```json
{
  "schema": "JKXTExecutionPackage/v1",
  "baselineSha": "原始矩阵明确标注的40位基线SHA",
  "sourceArtifacts": [
    {"role":"matrix", "path":"docs/release/inputs/原始矩阵.md", "sha256":"实际 SHA-256"},
    {"role":"architecture", "path":"docs/release/inputs/原始架构.md", "sha256":"实际 SHA-256"}
  ],
  "scenarios": [{"id":"原始编号", "group":"原始组", "title":"原始标题", "given":"原始给定", "operation":"原始操作", "expected":"原始必须观察结果", "sourcePath":"源文件路径", "sourceLine":1}],
  "architectureDecisions": [{"id":"ADR-01", "title":"原始章节标题", "content":"完整正文含子节", "sourcePath":"源文件路径", "sourceLine":1}]
}
```

上述为字段示意而不是可通过的样例。用 `execution-package.mjs` 从真实原件生成；工具拒绝覆写已有输出。原件副本的hash与用户提供文件另行核对并固定在Git审阅中。矩阵前言、状态/范围附录和架构文件全貌保存在原件，不因只归一化表格而免除要求。输入 `VERIFIED` 只说明可追溯且完整，不说明84业务场景通过；逐项运行记录另存 `scenario-ledger.json`。

注册接口格式（只能由隔离 Spring 上下文读取 handler registry 后产生）：

```json
{
  "schema": "JKXTSpringMappings/v1",
  "collection": "RequestMappingHandlerMapping",
  "environment": "isolated-test",
  "runId": "同本报告", "gitSha": "同本报告", "sourceDigest": "同本报告",
  "mappings": [{"methods": ["GET"], "path": "/api/v1/真实路径", "handler": "真实类#真实方法"}]
}
```

上述产物允许额外字段保存下载、订阅、后台任务等信息，但 HTTP registry 本身不能证明定时任务/消费者已经触发，仍需对应单独证据。不限 HTTP 方法的实际注册可记录 `ANY`，不得猜成 `GET`。

## 自测与信任限制

自测只在系统临时目录创建独立、无远程地址的 Git fixture，不使用业务数据、数据库、Cookie 或生产配置。包含完整正例与空报告、0 执行、失败、错误、跳过、缺类、错误 SHA、源码/报告篡改、命令失败、NOT_RUN/BLOCKED、缺执行包、静态接口冒充运行时、生产阶段误用等反例。临时目录清理前核验完整路径和固定前缀。

第二轮独立审阅发现“同类漏关键方法”和“编译跳过命令配旧 XML”漏洞。修复前实际命令 `node --test --test-name-pattern='partial method|compile-only' scripts/release/check-release.test.mjs` 结果 4 执行/4 失败、退出码 1（全部 Missing expected exception），证明原门错误放行；随后补方法覆盖和命令参数检查，并保留原 39 项自测及新增反例。失败记录不能改写成成功。

本次附件接入又实测3个负例：替换ID、削弱预期、无来源新场景。旧门全部错误放行（3执行/3失败，exit1）；源码回读修复后全部通过。当前工具回归为53个发布检查测试、22个原文解析/一致性测试、7个命令记录器测试，共82个，不是82个业务场景。另有独立7项路径/原件保护探针。记录器自测只增加复制新依赖文件，旧断言均保留。

该检查器验证**一致性和最低证据完整性**，不是签名证据机构：有权同时伪造报告、日志及全部 hash 的人仍能伪造输入，文件 hash 不能证明命令真正执行。真实命令执行、隔离环境创建、日志采集与独立复核应由可信执行流水线保存；Supervisor/客户终审必须保留原来的独立权限边界。此工具不能用任意 PASS 字符串替代 JUnit，也不能把自测的人工正例当成产品通过。
