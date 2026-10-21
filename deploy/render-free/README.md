# Render 免费临时验证拓扑（非商用上线批准）

用途：在新加坡免费实例上运行**现有真实前后端**，仅使用合成验收数据。不能替代生产部署验收；不得迁入真实学校数据。免费 Web 会休眠、没有持久磁盘；免费数据库可能到期；免费 Key Value 不提供可靠持久化。因此没有长期可用性、备份恢复、队列/会话恢复或学校业务 SLA 承诺。平台限制以创建当日官方页面为准。

## 构建与运行边界

- Docker build context：`JKXT V2.0`；Dockerfile：`deploy/render-free/Dockerfile`。
- 示例：在应用目录运行 `docker build -f deploy/render-free/Dockerfile -t jkxt-render-validation:<candidate-id> .`。冻结候选时记录镜像摘要、所有基础镜像摘要、源码摘要及 Git 身份；标签不是不可变证据。
- 后端在 Docker 中用 Maven 3.9.9 / Java 21 从当前源码打包；`-DskipTests` **只算编译**。前端使用 Node 22 和锁文件重新构建，部署生产 `dist`，不依赖宿主旧 JAR/dist。
- 最终镜像使用 Java 21 JRE、发行版安全更新后的 nginx、tini、真实字体，以 UID/GID 10001 运行。
- 公开端口只允许 nginx `${PORT}`（默认 10000）；Java 只监听 `127.0.0.1:8080`。平台健康检查路径必须为 `/actuator/health/readiness`，不能改为静态首页。它同时检查 PostgreSQL 和 Redis。
- nginx 同源转发 `/api/v1/`，已有两类 SSE 端点关闭缓冲并保留长读超时。必须实际验证 Cookie、CSRF、登录门户、SSE、下载，不能以静态首页 200 代替。
- 镜像不含数据库、Redis、密钥、旧测试报告、备份或用户上传。现有 `uploads` 目录只有 `.gitkeep`，当前 UI 图片引用 `assets`；因此没有复制整个 uploads 目录，也不提供该目录的公开读取。
- 512 MiB 起始预算：192 MiB heap、128 MiB metaspace、32 MiB code cache、24 MiB direct memory；使用 SerialGC，HTTP 线程上限 24，数据库连接上限 6。**只是启动参数，不是已通过负载测试**；OR-Tools 等本地内存不受 heap 上限保护。真实启动/排班/导出发生 OOM 或持续超时则停止验收，不降断言。

## 仅由运行环境注入

| 设置 | 要求 |
|---|---|
| `SPRING_DATASOURCE_URL` | 专用数据库 JDBC PostgreSQL URL；不嵌入密码。依据平台证书和网络配置 TLS，不关闭验证来掩盖错误。 |
| `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` | 新建专用免费验证数据库凭据，不使用原机业务数据库。 |
| `SPRING_DATA_REDIS_HOST` / `SPRING_DATA_REDIS_PORT` | 新建专用 Key Value；默认端口由 Spring 配置解析为 6379。不得连接已有业务 Redis。 |
| `SPRING_DATA_REDIS_USERNAME` / `SPRING_DATA_REDIS_PASSWORD` / `SPRING_DATA_REDIS_SSL_ENABLED` | 按平台实际要求注入；不把 URL 带密码打印到日志。 |
| `APP_BASE_URL` | 单一 HTTPS 源，不带路径/用户信息；未设置时使用平台 `RENDER_EXTERNAL_URL`。 |
| `CORS_ALLOWED_ORIGINS` | 必须与上述源完全一致；默认自动取该源，不接受 `*`。 |
| `JKXT_FIXED_ORG_ID` | 精确私有组织 UUID；默认保留现有迁移的合成组织 0001。 |
| `JKXT_VERSION` | 对应冻结源码与镜像的候选标识，不使用笼统 production。 |

启动期强制保持 strict 检查、禁用公开默认账号和演示教师、验证码调试关闭、Secure Cookie 开启；拒绝不安全覆盖。外部 AI、email、SMS 均关闭；站内通知仍走原真实业务。初始管理员**不自动创建**，不提供默认口令、环境口令 bootstrap 或 HTTP 初始化后门；继续使用既有 `InitialOperatorCli` 的显式交互式维护流程。免费平台不能运行交互 shell 时，由受控本地同版本镜像连接专用数据库，使用 `verify-full` 和已验证服务器证书进行维护；未经用户交互确认不得绕过 CLI。

## 信任与停止策略

不知道平台 ingress 的可信 CIDR 时，不采信客户端的 `X-Forwarded-For` / `Forwarded`。nginx 覆盖这些头，Spring 只信任 127.0.0.1，审计记录平台直接代理 IP。**不声称获得真实访客 IP**；基于 IP 的风控不能据此商用验收。公网 TLS 由托管入口终止；本容器只适用于已验证 HTTPS 的该入口，不能直接裸露 HTTP 给用户。

Java 或 nginx 任一退出（即使退出码为 0），容器以非零状态停止另一个进程；SIGTERM 同时传给两者，最多等待 10 秒，随后强制收尾；tini 负责回收孤儿进程。日志写标准输出/错误，无环境打印。入口脚本不接收额外参数，不允许隐式 JVM options。

没有持久备份卷，不挂载假备份目录、不登记假成功。后台备份扫描保持原有“未生成备份”行为。退出免费验证之前导出允许保留的合成测试证据，并按另行确认清理云资源；不能把清理称为备份。

## 专项验证（独立于业务验收）

在仓库根目录执行 `node --test scripts/release/render-free.test.mjs`。测试覆盖配置拒绝、头部信任规则、最小构建上下文及真实 Bash 进程退出/信号收尾（Java/nginx 在此专项中为进程替身，不替代真实容器或业务测试）。Windows 使用 `wsl.exe -d Ubuntu-22.04`；可通过测试专用 `JKXT_TEST_WSL_DISTRO` 指定已有 Linux 发行版。缺少可运行 Bash/envsubst 时测试失败，不静默跳过。

之后必须另行运行实际镜像：512 MiB 资源限制 + 专用 PostgreSQL/Key Value；验证 readiness、真实迁移/验证码/会话、已有核心排班和导出、故障停止及公网 HTTPS。Redis 7 本地通过不能自动证明托管 Valkey 8 兼容；目标服务必须再次验收。本目录并不宣告这些检查已经通过。
