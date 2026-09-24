# G0 自有工程与调用链清单

## 范围与证据等级

- 交付候选来自 `codex/source-current-20260922` / `f206bcf6dff291d346cf5c3cf5a68bfce938935e`，不取 `main` 历史原型，不合并登录隔离 WIP。
- `summary.json` 记录本次生成时间、候选 HEAD、工作树 diff 摘要及数量；`files.json/csv` 对每个受检文件记录 SHA-256。生成脚本只读工程源码，写入本目录，不启动应用，不访问数据库，不读取 Cookie。
- `DEEP_READ`：本次 G0 人工完整阅读过该文件；不等于验证其行为正确。`SCAN`：机器读取、词法或 AST 扫描，部分文件另做人工局部阅读；不能称为整文件深读。`UNREAD`：仅路径、大小、哈希；主要为第三方字体/vendor 与媒体资产，未审阅语义/视觉/许可。
- 自有前后端、配置、迁移、脚本和文档均逐文件列出；包括本轮新增的根目录 `scripts/release/` 工具、Maven隔离配置、`compose.release-test.yml` 和手写交付文档。新增工具默认标 `SCAN`，不假定本子任务已完整深读其他人的实现。仅排除可变的 `docs/release/evidence/` 证据目录及本目录自动生成的JSON/CSV，避免报告自引用哈希循环。第三方依赖、媒体也保留清单，但明确不当作自有源码深读。
- 来源快照中的旧文档含阶段性目标、旧测试记录和已过期环境说明，只作为历史线索；本轮不能拿它们当执行证据。

## 文件入口

| 产物 | 用途 | 不能证明什么 |
| --- | --- | --- |
| `files.csv` / `files.json` | 分类、文件大小/哈希、深读/扫描/未读范围 | 不证明业务验收通过 |
| `static-routes.json` / `traceability.csv` | 逐路由：前端候选 → HTTP/API → 权限 →服务调用 → 数据表候选 → 产物 → 测试候选 | **不是** Spring 实际注册接口列表 |
| `frontend-requests.csv/json` | 47 个前端 JS/JSX 文件 AST 中请求与封装调用位置、处理函数、目标候选 | 并非按钮点击通过；动态路径/别名可能仍未解析 |
| `background-discovery.json` | 后台/启动钩子/异步/SSE 的源码定位 | 不证明工作进程已运行或重启恢复有效 |
| `async-downloads.md` | 后台任务、消费者、下载、订阅、外部集成边界 | 不把 HTTP 200、日志或 toast 当送达/导出内容验证 |
| `page-operation-map.md` | 按真实页面组织的垂直切片与待验断言 | 不替代每个按钮、权限分支及失败状态的 E2E |
| `review-findings.md` | 当前源代码已核实的范围限制与待复现风险 | 不重复实施已修复的历史问题 |

## 分类约定

- `真实实现（静态代码）`：Controller 已接服务代码；仅表示有实现体，不表示运行时注册、业务正确、DB 已写入或前端可用。
- `部分实现`：现有实现与操作名称/交付预期不同，如“备份”API只扫描外部生成的备份文件，不亲自执行备份。
- `模拟`：已确认的开发 fixture、测试专用 LLM mock、浏览器本地合成预览。不能进入正式业务通过统计。
- `不可达`：必须有实际运行证据才能定性。静态没找到页面调用一律写 `NO_STATIC_UI_CALL_OBSERVED_NOT_PROOF_OF_UNREACHABILITY`，不能据此删接口。
- `缺配置`：外部 AI 真实调用、邮件/SMS供应商等需单独授权与配置的能力；模板缺省禁用不证明真实部署缺配置。
- `未验证`：所有运行时注册、权限效果、DB/导出内容断言、浏览器操作均要关联本轮单独采集的实际证据。

数据库列是“服务类依赖的表名候选集合”，跨方法取超集，**不是**该接口一定写所有这些表。测试列是源码引用候选，不是精确覆盖证明，也不是执行结果。

## 可重复生成与自检

在本候选仓库根目录运行：

```text
node scripts/release/inventory.mjs
node --check scripts/release/inventory.mjs
```

首次生成源快照统计：676 个文件；31 个 Controller；188 条静态业务路由；47 个前端 JS/JSX；72 个 Java 测试源文件。后续新建测试与受检文件会使文件数增加，以本轮 `summary.json` 为准，禁止复用这里的文字作为测试数量。

当前实际注册与执行结果由主任务的隔离 Spring/数据库测试另外收集；本脚本对此明确写 `NOT_COLLECTED`/`NOT_RUN`。2026-09-24用户补充的84项矩阵及12节架构文档已完整阅读、原字节留存并验证来源，见 `../inputs/` 和 `../execution-package.json`；`summary.json` 动态校验该包，标 `VERIFIED_INPUT_ONLY`，不再沿用历史MISSING。`../scenario-ledger.json`逐项保留未运行状态，不因材料齐备而增加业务PASS。
