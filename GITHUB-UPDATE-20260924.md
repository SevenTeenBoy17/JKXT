# 工程交接更新 · 2026-09-24

## 范围与版本

- 交接分支：`codex/source-update-20260924`。不覆盖 `main`、`feat/fullstack-wiring` 或旧快照分支。
- 基线：`f206bcf6dff291d346cf5c3cf5a68bfce938935e`；整改代码：`aa57a6bb8edb5d97fb20495f0744ea6a372d4be7`。
- 保留全部已跟踪应用源码、前端素材、Java 测试、V1–V30 迁移、依赖声明、环境模板、Docker 配置、开发脚本与设计/验收文档。
- 原工作区用户改动不重置。登录隔离候选仍独立保留于 `codex/login-isolation-wip-20260922`，未合并，未宣称完成验收。
- 对原快照的 665 个文件重新按 SHA-256/字节长度比较，原工作区变化 0、缺失 0；候选另有 5 项修改和 3 项新测试。没有发现需要混合覆盖的原工作区后续源码。历史 `.circleci`、`.impeccable`、旧发布副本和部分旧计划并非当前交接范围，不宣称上传原电脑的所有文件。
- Supervisor 保持暂停；本次不执行或冒充其门禁。

## 已有整改证据（历史，不冒充本次重跑）

| 固定代码 / 检查 | 执行 | 失败 | 错误 | 跳过 | 结论范围 |
|---|---:|---:|---:|---:|---|
| `aa57a6b` 全量后端隔离测试 | 283 | 0 | 0 | 0 | 已执行测试集合，不是全产品验收 |
| `aa57a6b` 发布工具反例 | 109 | 0 | 0 | 0 | 工具对无效报告的拒绝能力 |

9 项会话索引回归包含真实 PostgreSQL / Redis；旧缺陷 RED、最小修复及冻结复测见 `docs/release/G2-SESSION-RESULTS-20260924.md`。84 项验收矩阵仍有 1 项 PARTIAL、83 项 NOT_RUN，不把套件通过率写成产品合格率。

## 安全与运行边界

本交接不携带真实 `.env`、API key、Cookie、业务数据库/备份、上传资料、运行日志、原始测试证据、编译包或 `node_modules`。保留源码中已有、公开说明的演示 seed 和演示口令，只允许在回环测试环境使用。不能据此直接公网部署。

历史文档中的 Evidence ID、SHA、工作站路径属于追溯信息，不代表文件均随仓库分发。原始执行证据只保留在原验收工作区；新电脑应独立重跑，不复制旧报告、会话或数据库。固定旧 SHA 的报告不能批准新提交。

本地启动采用 README 的 `compose.local-review.yml`，数据库/Redis 不开放宿主端口；外部 AI、短信、邮件默认关闭。仓库可见性由所有者控制，本次不会自行修改访问策略或授予新的开源许可证。第三方素材许可仍需在商业分发前逐项核验。

已修正文档中“不同分支自动获得独立数据库卷”的错误说法：Docker Compose 按项目名隔离，README 统一显式指定新项目名。不推荐旧 `init-private` / `init-public` 作为本轮部署入口。评审栈未配置 backup 服务，后台备份操作与旧脚本不构成当前项目的备份恢复闭环；此功能仍待单独接通和验收。

## 本次交接检查

| 检查 | 退出码 / 结果 | 证明范围 |
|---|---|---|
| `node "JKXT V2.0/scripts/check-jsx.js"` | 0；47 文件、0 失败 | 当前页面源码语法 |
| `node --test scripts/release/check-release.test.mjs scripts/release/execution-package.test.mjs scripts/release/summarize.test.mjs scripts/release/record-command.test.mjs` | 0；109 通过、0 失败/取消/跳过 | 发布检查工具，不是业务矩阵 |
| 路径、文件哈希、高信号凭据及静态引用核查 | 0；728 文件、73 引用、0 发现 | 有界检查，不保证穷尽所有敏感内容 |
| 产品源码与整改候选逐文件比对 | 673 应用文件、13 发布工具文件全部相同 | 本轮未改业务实现或测试断言 |
| 显式独立项目 Compose 解析及独立审查 | 0 | 相对路径、回环端口和项目卷隔离 |
| 首次 Docker 构建与启动 | **1 / BLOCKED** | `dependency:go-offline` 下载失败，未到启动和登录阶段 |
| 首次登录、浏览器业务流 | **NOT_RUN** | 构建前置未通过 |

实际构建命令：`docker compose -p jkxt-handoff-20260924 -f compose.local-review.yml up -d --build --wait --wait-timeout 180`。数据库密码为进程内生成的随机值，Web 端口设为 18291；不使用任何既有容器、业务库或 Cookie。失败原因：从 Maven Central 获取 `com.github.luben:zstd-jni:1.5.5-11` 时，6,776,400 字节响应只收到 1,015,808 字节。属于本次网络/依赖获取阻断，没有删依赖、跳过失败或将旧编译结果改写为成功。构建阶段未创建本次应用/数据库容器。原始构建日志保留在本地交接验证目录，不上传运行产物。

GitHub API 与 `gh api` 均确认仓库目前为 public；与历史私有交接权限范围不同。本地准备时已请求所有者确认公开上传，**截至本记录仍未推送、未改变可见性**。具体远端提交只有在授权后的 push 和重新克隆校验完成后才成立。本地文件齐全不等于在另一台电脑已运行验证。

## 接续开发

继续 `docs/release/STATUS.md` 所列 G2 会话绝对期限、登录与撤销完整并发、强制改密/CSRF/入口协议，再按 PLAN 推進其他阶段。不要重新做演示系统，不降低 `TestDatabaseIsolationGuard` 或测试断言。总体上线结论仍为 **NOT APPROVED**。
