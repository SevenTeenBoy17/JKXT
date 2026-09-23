# JKXT 登录入口隔离 · 开发中快照（WIP）

**此分支尚未完成前后端联调。后端能构建不代表界面可用，不能作为已验收的新版本或生产部署包。** 当前前端仍使用旧登录请求，尚未接入新的入口、会话上下文与 CSRF 机制；普通页面能显示也不证明登录和业务操作能正常完成。

本分支为私有仓库 [SevenTeenBoy17/JKXT](https://github.com/SevenTeenBoy17/JKXT) 的 `codex/login-isolation-wip-20260922`，用于保存和继续开发登录入口隔离候选源码。应用位于 `JKXT V2.0/`。需要查看原应用源码时，使用 [当前源码分支](https://github.com/SevenTeenBoy17/JKXT/tree/codex/source-current-20260922)。

## 尚未完成的接口衔接

- 前端仍调用 `/api/v1/auth/login`；候选后端已将该入口固定为 `SCHOOL`。`SUPER_ADMIN` 需要 `/api/v1/auth/platform/login`，原界面的平台登录不能视为已经接通。
- 后端已要求业务请求携带 `X-JKXT-Session-Context`，写请求还需 `X-JKXT-CSRF`；当前前端请求层尚未注入这些信息，后续操作可能返回 `409` 或 `403`。
- 计划中的前端入口与会话隔离模块尚未集成。本仓库根目录的单站本机配置也不等于正式站与演示站的双站隔离验收。
- 后端新增了演示工作区及资源身份检查。不要仅把部署模式改成 `demo` 就尝试上线；演示模式还需要独立数据库身份、受限账号、Redis 身份标记和专用存储等配套环境。

业务源码保持快照原样，本次交接不借机完成这些功能，也不宣称它们通过测试。

## 在其他电脑启动开发检查环境

准备 Git、Docker 和 Docker Compose。Windows 使用已启动的 Docker Desktop（Linux 容器，通常使用 WSL 2）；macOS 可使用 Docker Desktop；Linux 使用 Docker Engine 与 Compose 插件。本说明面向 x86_64，ARM 未验证。首次构建需要联网下载镜像与 Maven 依赖，不是离线安装包。

```sh
git clone --branch codex/login-isolation-wip-20260922 --single-branch https://github.com/SevenTeenBoy17/JKXT.git JKXT-login-wip
cd JKXT-login-wip
```

私有仓库需要有访问权限的 GitHub 账号。以下命令在仓库根目录执行，不要进入 `JKXT V2.0/` 后再执行。

Windows PowerShell：

```powershell
Copy-Item .env.review.example .env.review
notepad .env.review
```

macOS / Linux：

```sh
cp .env.review.example .env.review
```

用文本编辑器把 `.env.review` 中的 `POSTGRES_PASSWORD` 改为自己生成的随机密码，建议至少 24 位随机字母数字。该文件仅保存在本机，不提交到 Git；已有文件时直接编辑，不要重新复制覆盖。

```sh
docker compose --env-file .env.review -f compose.local-review.yml up -d --build
docker compose --env-file .env.review -f compose.local-review.yml ps
```

等待服务就绪后，可以检查：

- 页面：[http://127.0.0.1:18291/Dashboard.html](http://127.0.0.1:18291/Dashboard.html)
- 后端健康状态：[http://127.0.0.1:18291/api/v1/health](http://127.0.0.1:18291/api/v1/health)

健康接口正常只说明相关服务状态，不代表 WIP 界面流程通过。不要双击 HTML 用 `file://` 打开；静态文件服务也不能替代后端。端口冲突时先核对占用来源，不停止其他应用。

## 账号、演示数据与环境边界

迁移仍包含源码自带的 UAT 账号：学校账号 `tenant.test / Tenant001!!!`、平台账号 `admin / Super001!!!`。这些是公开演示凭据，不是原电脑账号的备份；受上述接口未接通问题影响，不能据此承诺当前 UI 可以完成登录或业务操作。

全新数据库会执行 Flyway V1–V36，并生成演示学校、账号、基础资料和任务记录，不是空白业务库。V31 是追加迁移，不代表已经完成真实学校账号初始化。不要把这一候选连接到原版或生产数据库。

根目录配置仅向 `127.0.0.1:18291` 暴露 Web，数据库和 Redis 不向宿主机开放端口。PostgreSQL 与备份使用该分支独立的数据卷，Redis 状态不持久化。默认开启验证码调试，仅供本机开发；演示账号与此配置不能用于生产。外部 AI 和通知默认关闭；需要真实 AI 时自行配置服务端密钥，不写入前端或仓库。

## 停止和保留数据

```sh
docker compose --env-file .env.review -f compose.local-review.yml stop
docker compose --env-file .env.review -f compose.local-review.yml up -d
```

`stop` 保留数据。不要默认使用 `down -v`，它会删除本环境的数据卷。数据库密码仅在首次初始化时生效；改环境文件不会同步修改已有数据库密码，也不要以删除数据卷作为默认排错步骤。

## 交接内容与检查范围

| 位置 | 内容 |
| --- | --- |
| `compose.local-review.yml`、`.env.review.example` | 本次新增的独立本机检查配置 |
| `JKXT V2.0/Dashboard.html`、`src/`、`assets/` | 尚待接入隔离机制的现有界面及素材 |
| `JKXT V2.0/backend/` | 后端源码、迁移、候选测试源码 |
| `JKXT V2.0/scripts/login-isolation/` | 隔离开发脚本及说明，部分配置用于测试而非部署 |
| `JKXT V2.0/docs/` | 历史设计、计划及执行说明 |

本快照不包含实际 env、数据库 dump、Cookie、运行产物、`artifacts`、旧发布包或旧 Git 历史。保留的是源码和经审查的生成器；历史冒烟脚本需要的 Excel 资料应先生成，不能把原机运行结果当作新验证。

历史部署文档包含旧路径、端口与阶段描述，本机启动以本 README 为准。本轮仅开展源码分享、构建与引用安全检查；具体结果以随交接提供的检查记录为准，不预先宣称测试通过。业务回归、界面联调和生产验收仍需继续完成。

保留依赖和素材现有许可证及来源说明。尚无第三方素材的全面授权审查；私有仓库访问不自动授予开源许可，请勿公开或商业再分发。
