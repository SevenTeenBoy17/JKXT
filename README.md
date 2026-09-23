# JKXT 当前源码快照 · 2026-09-22

这是私有仓库 [SevenTeenBoy17/JKXT](https://github.com/SevenTeenBoy17/JKXT) 的当前应用源码交接分支 `codex/source-current-20260922`。应用源码位于 `JKXT V2.0/`，根目录提供一套独立的本机检查配置，便于在其他电脑继续开发。

本分支保留快照时的业务逻辑，不包含原电脑的实际环境配置、数据库备份、登录 Cookie、运行产物、`artifacts` 或旧发布包，也不携带旧 Git 历史。启动后得到的是迁移脚本生成的演示记录，不是原电脑的数据副本，也不是空白业务库。

登录入口隔离功能仍在独立开发，见 [开发中分支](https://github.com/SevenTeenBoy17/JKXT/tree/codex/login-isolation-wip-20260922)；不要把该分支当作已经验收的新版本覆盖本分支。

## 在其他电脑启动

准备 Git、Docker 和 Docker Compose。Windows 使用已启动的 Docker Desktop（Linux 容器，通常使用 WSL 2）；macOS 可使用 Docker Desktop；Linux 使用 Docker Engine 与 Compose 插件。本说明面向 x86_64，ARM 机器未验证。首次启动需要联网下载容器镜像和 Maven 依赖，不是离线安装包。

```sh
git clone --branch codex/source-current-20260922 --single-branch https://github.com/SevenTeenBoy17/JKXT.git JKXT-current
cd JKXT-current
```

私有仓库需要使用有访问权限的 GitHub 账号认证。以下所有命令都在刚克隆的仓库根目录运行，不要进入 `JKXT V2.0/` 后再执行。

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
docker compose --env-file .env.review -f compose.local-review.yml up -d --build
docker compose --env-file .env.review -f compose.local-review.yml ps
```

等待服务就绪后打开：

- 学校入口：[http://127.0.0.1:18290/Dashboard.html](http://127.0.0.1:18290/Dashboard.html)
- 平台入口：[http://127.0.0.1:18290/Dashboard.html?portal=super](http://127.0.0.1:18290/Dashboard.html?portal=super)
- 后端健康状态：[http://127.0.0.1:18290/api/v1/health](http://127.0.0.1:18290/api/v1/health)

不要双击 HTML 用 `file://` 打开，也不要用静态文件服务替代这套前后端配置。若端口已占用，先检查占用来源，不要直接停止其他应用。

## 首次登录与数据

以下是源码迁移自带的公开 UAT 演示账号，不是真实本机账号备份：

| 入口 | 用户名 | 演示密码 |
| --- | --- | --- |
| 学校入口 | `tenant.test` | `Tenant001!!!` |
| 平台入口 | `admin` | `Super001!!!` |

全新数据库会执行 Flyway 迁移并生成演示学校、账号、基础资料及任务记录。本机配置默认开启验证码调试，仅供开发检查；这些演示账号和设置不能用于生产。

本配置只把 Web 端口绑定到 `127.0.0.1:18290`，数据库和 Redis 不向宿主机开放端口。PostgreSQL 与备份使用该分支专用的独立数据卷，Redis 状态不持久化。外部 AI 和通知默认关闭。需要真实 AI 时，应自行准备服务端密钥及接入配置；不要把密钥写入前端或提交到仓库。

## 停止、再次启动和数据保留

```sh
docker compose --env-file .env.review -f compose.local-review.yml stop
docker compose --env-file .env.review -f compose.local-review.yml up -d
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

源码目录中的原部署文档包含历史路径、端口和旧功能状态，本次本机启动以本 README 为准。历史冒烟脚本若依赖 Excel 测试资料，需要先通过保留且已审查的生成器生成相应文件；不要复制旧 Cookie 或历史运行结果充当新验证证据。

本次交接范围是源码分享、构建与引用安全检查，不是重新完成业务测试或生产验收。具体执行结果以随交接提供的检查记录为准；本说明不预先宣称构建、完整登录流程或全部测试通过。

保留素材和依赖已有的许可证及来源说明。尚未完成所有第三方素材的全面授权审查；仓库访问权限不自动授予开源许可，请勿公开或商业再分发。
