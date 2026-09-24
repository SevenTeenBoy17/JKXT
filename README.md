# JKXT 工程更新 · 2026-09-24

这是 [SevenTeenBoy17/JKXT](https://github.com/SevenTeenBoy17/JKXT) 的整改候选交接分支 `codex/source-update-20260924`。应用源码位于 `JKXT V2.0/`，根目录提供独立本机启动配置。请克隆本分支：`main` 是历史原型，不代表当前应用。

**本分支可用于代码查看、本机运行和继续开发，不是已批准的生产版本。** 基于 `codex/source-current-20260922` / `f206bcf6dff291d346cf5c3cf5a68bfce938935e`，包含截至 `aa57a6bb8edb5d97fb20495f0744ea6a372d4be7` 的整改。未合并登录隔离 WIP。

本次更新包含排班器避免误报无解的修正、会话索引续期/撤销原子化修复、隔离测试运行器、真实接口清单和发布检查工具。冻结代码曾在全新 PostgreSQL / Redis 上执行 283 项后端测试，0 失败/错误/跳过；这不等于 84 项交付场景全部验收。详细范围与未完成项见 [整改状态](docs/release/STATUS.md) 和 [本次交接记录](GITHUB-UPDATE-20260924.md)。

本分支保留 Java / Spring Boot、PostgreSQL、Redis 及现有前端，不包含原电脑的实际环境配置、数据库备份、登录 Cookie、运行日志、`artifacts` 或旧发布包；保留已清理源码快照之后的整改提交历史。启动后得到的是迁移脚本生成的演示记录，不是原电脑的数据副本，也不是空白业务库。

登录入口隔离功能仍在独立开发，见 [开发中分支](https://github.com/SevenTeenBoy17/JKXT/tree/codex/login-isolation-wip-20260922)；不要把该分支当作已经验收的新版本覆盖本分支。

## 在其他电脑启动

准备 Git、Docker 和 Docker Compose。Windows 使用已启动的 Docker Desktop（Linux 容器，通常使用 WSL 2）；macOS 可使用 Docker Desktop；Linux 使用 Docker Engine 与 Compose 插件。本说明面向 x86_64，ARM 机器未验证。首次启动需要联网下载容器镜像和 Maven 依赖，不是离线安装包。

```sh
git clone --branch codex/source-update-20260924 --single-branch https://github.com/SevenTeenBoy17/JKXT.git JKXT-current
cd JKXT-current
```

如仓库访问策略要求登录，请使用有权限的 GitHub 账号。以下所有命令都在刚克隆的仓库根目录运行，不要进入 `JKXT V2.0/` 后再执行。仅用 Docker 启动不需要在宿主机安装 Java、Maven 或 Node；后端 Dockerfile 使用 Java 21 / Maven 3.9.9 构建。

Windows PowerShell：

```powershell
Copy-Item .env.review.example .env.review
notepad .env.review
```

macOS / Linux：

```sh
cp .env.review.example .env.review
```

用文本编辑器打开 `.env.review`，把 `POSTGRES_PASSWORD` 改成自己生成的随机密码，建议至少 24 位随机字母数字。`.env.review` 只保存在本机，不提交到 Git。已有此文件时直接编辑，不重复复制覆盖。

```sh
docker compose -p jkxt-review-20260924 --env-file .env.review -f compose.local-review.yml config --quiet
docker compose -p jkxt-review-20260924 --env-file .env.review -f compose.local-review.yml up -d --build --wait --wait-timeout 180
docker compose -p jkxt-review-20260924 --env-file .env.review -f compose.local-review.yml ps
```

等待服务就绪后打开：

- 学校入口：[http://127.0.0.1:18290/Dashboard.html](http://127.0.0.1:18290/Dashboard.html)
- 平台入口：[http://127.0.0.1:18290/Dashboard.html?portal=super](http://127.0.0.1:18290/Dashboard.html?portal=super)
- 后端健康状态：[http://127.0.0.1:18290/api/v1/health](http://127.0.0.1:18290/api/v1/health)

不要双击 HTML 用 `file://` 打开，也不要用静态文件服务替代这套前后端配置。若端口已占用，先检查占用来源，不要直接停止其他应用。修改 `REVIEW_HTTP_PORT` 后，也要相应替换以上访问地址的端口。

## 首次登录与数据

以下是源码迁移自带的公开 UAT 演示账号，不是真实本机账号备份：

| 入口 | 用户名 | 演示密码 |
| --- | --- | --- |
| 学校入口 | `tenant.test` | `Tenant001!!!` |
| 平台入口 | `admin` | `Super001!!!` |

全新数据库会执行 Flyway 迁移并生成演示学校、账号、基础资料及任务记录。本机配置默认开启验证码调试，仅供开发检查；这些演示账号和设置不能用于生产。

本配置只把 Web 端口绑定到 `127.0.0.1:18290`，数据库和 Redis 不向宿主机开放端口。上面的 `-p jkxt-review-20260924` 用于区分旧快照的 `jkxt-source-review`；PostgreSQL 与备份数据卷按项目名隔离，**换目录或分支不会自动隔离数据卷**。如果本机已有相同项目名，首次评估请为所有命令统一换成新的项目名，并在 `.env.review` 选择空闲 Web 端口。不要复用他人的运行环境。

Redis 状态不持久化。外部 AI 和通知默认关闭。需要真实 AI 时，应自行准备服务端密钥及接入配置；不要把密钥写入前端或提交到仓库。

## 停止、再次启动和数据保留

```sh
docker compose -p jkxt-review-20260924 --env-file .env.review -f compose.local-review.yml stop
docker compose -p jkxt-review-20260924 --env-file .env.review -f compose.local-review.yml up -d
```

`stop` 保留本机数据。不要把 `down -v` 当作日常停止命令，它会删除这套环境的数据卷。数据库密码只在首次初始化时生效；修改环境文件不会自动修改已存在数据库的密码，也不要为解决密码不一致而直接删除数据卷。

## 源码位置与验证边界

| 位置 | 内容 |
| --- | --- |
| `compose.local-review.yml`、`.env.review.example` | 本次新增的本机启动入口 |
| `JKXT V2.0/Dashboard.html`、`src/`、`assets/` | 页面、业务界面、本地脚本和素材 |
| `JKXT V2.0/backend/` | Java 21 / Spring Boot 后端、数据库迁移与测试源码 |
| `JKXT V2.0/deploy/`、`scripts/` | 原部署配置及保留的开发脚本 |
| `JKXT V2.0/docs/` | 历史设计和开发文档 |
| `docs/release/` | 84 项验收矩阵、接口追溯、整改计划、已执行与待验边界 |
| `scripts/release/`、`compose.release-test.yml` | 独立测试运行器与拒绝无效证据的检查工具 |

源码目录中的原部署文档包含历史路径、端口和旧功能状态，本次本机启动以本 README 为准。尤其不要以旧 `init-private` / `init-public` 或 `docker-compose.public.yml` 直接对公网部署：它们保留兼容历史开发用途，未获得本轮生产准入，可能带演示 seed、开发口令或非回环端口。历史冒烟脚本若依赖 Excel 测试资料，需要先通过保留且已审查的生成器生成相应文件；不要复制旧 Cookie 或历史运行结果充当新验证证据。

## 开发检查与重新验收

前端是现有 React UMD / Babel 页面，没有需要 `npm install` 的根 package.json。安装 Node 后，可在 `JKXT V2.0/` 下执行 `node scripts/check-jsx.js` 检查语法；这不是浏览器验收或生产前端构建。

后端测试必须连接**专用测试库和隔离 Redis**，保留 `TestDatabaseIsolationGuard`。`backend/mvnw` 使用 Docker 运行 Maven，不代表任意电脑有原生 Maven，也不要让测试连接运行中的学校业务库。

Windows 开发人员需要 PowerShell 7、Java 21、原生 Maven 3.9.9、Node、Docker Desktop Linux 引擎，以及已准备的 Maven 离线缓存，才能使用以下既有严格隔离运行器（路径替换为本机实际安装位置，run 名每次唯一）：

```powershell
./scripts/release/run-isolated.ps1 -JavaHome 'C:/tools/jdk-21' -Maven 'C:/tools/apache-maven-3.9.9/bin/mvn.cmd' -Run review_20260924_01 -DatabasePort 25439 -RedisPort 26386
```

该运行器目前是 Windows 专用且显式使用 Maven `-o`；新电脑没有依赖缓存时会失败，不应把失败改成跳过。首次联网准备可在 `JKXT V2.0/backend/` 用原生 Maven 执行 `mvn -B -DskipTests package`；此命令只准备依赖和编译，**不算业务测试通过**，也不能保证 Surefire 动态测试依赖已全部缓存。缺少依赖时应按错误补齐，不跳过测试。Linux/macOS 的完整隔离测试运行器尚未验收。

原始测试日志、JUnit、连接参数和工作站证据不随源码公开；历史结果说明仅记录当时运行，不是可复用的当前质量门凭证。其他电脑须生成自身证据。缺少 `docs/release/baseline.json` 或报告时检查器应拒绝放行，不要绕过它。

本次交接范围是工程分享和本机启动复核，不是完成其余认证安全、Excel 全场景、审批发布、真实 AI、通知、备份恢复、前端端到端或上线验收。详细执行结果以本次交接记录为准。

保留素材和依赖已有的许可证及来源说明。尚未完成所有第三方素材的全面授权审查；仓库可见性和访问权限不自动授予开源许可，再分发或商业使用前须另行确认授权。
