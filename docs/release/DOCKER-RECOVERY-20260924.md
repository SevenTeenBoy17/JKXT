# 本机 Docker 受控恢复（2026-09-24）

本轮用户在既有定点修复方案后明确“给予最大权限，继续完成”。此次授权只用于相关受控修复/隔离测试；没有公共发布、生产迁移或外部计费授权扩展。Supervisor继续暂停。

## 实际过程（失败保留）

1. 只读确认无Docker进程、服务停止；旧日志在2026-09-23启动时因`Docker/run/dockerInference`不可访问崩溃。
2. 单文件`Rename-Item`退出1：系统无法访问此文件。`fsutil reparsepoint query`同样无法访问；没有删除文件或去除reparse标记。
3. 枚举`Docker/run`只有两个零字节通信reparse文件；确认目录本身不是reparse后，将目录改名保留。目录修复脚本退出0并启动Desktop。
4. 2026-09-24T07:37:58Z日志显示启动推进至`docker-secrets-engine/engine.sock`同类残留失败；该目录仅此一个零字节通信文件，未读取任何密钥。
5. 正常`docker desktop stop --timeout 20`失败。日志已确认引擎全部停止后，仅结束本轮启动且路径为`C:/Program Files/Docker/*`的进程。首个脚本因进程尚未完全退出安全停止（退出1），确认进程消失后完成第二目录改名（退出0）。
6. 失败启动留下了新的`dockerInference`；第三次启动因此再次报相同路径错误。保留该新通信目录，确保两处socket路径均不存在后统一启动。一次停止进程竞争退出1，修正为进程对象已退出检查后执行成功。没有将这些失败改写为成功。
7. 实际`docker --context desktop-linux version --format '{{json .Server}}'`退出0：Engine29.1.3、Desktop4.55.0、Linux/WSL2。独立命令证据：`evidence/docker-recovery-verified.command.json`。

## 回滚点与边界

- `C:/Users/nuoya/AppData/Local/Docker/run.rollback-20260924`：原始两个socket。
- `C:/Users/nuoya/AppData/Local/docker-secrets-engine.rollback-20260924`：原始Secrets Engine通信socket。
- `C:/Users/nuoya/AppData/Local/Docker/run.after-first-recovery-20260924`：中间失败启动生成的socket。
- 操作脚本及前置元数据：`evidence/docker-recovery-20260924/`。所有目标为显式检查后的通信目录，均未递归删除、未覆盖已有回滚点。
- 回滚仅在Docker停止时，将当前新建通信目录另行保留后恢复原目录名；原残留socket会恢复原故障，不建议为日常使用执行回滚。
- 没有重置Docker、删除镜像/数据卷、修改业务数据库或执行生产迁移。
- Desktop恢复后，原先配置了自动重启的JKXT/其他容器也自动启动；仅枚举名称/镜像/端口，未连接这些业务数据库、未使用历史Cookie。该自动启动不代表旧部署是本轮候选或验收通过。
- 本轮候选测试仅使用`jkxt-test-release-g1-20260924-r1-*`新容器、25432/26379端口、tmpfs。后续轮次使用新项目名和独立端口；不得指向旧15432/16379业务端口。

Docker恢复是环境修复成功，不是系统功能或上线验收成功。全量测试结果在STATUS及各轮原始证据独立记录。
