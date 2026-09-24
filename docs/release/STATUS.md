# JKXT交付整改接续状态（2026-09-24，Docker恢复后）

**总体INCOMPLETE。首轮完整G0/G1本地门禁通过；第二轮固定版本复测待执行；G2–G8及84项业务矩阵未完成，不准生产发布。**

## 真实源码与边界

- 候选根 `D:/VB/JKXT-release-g0-g1`，分支`codex/release-g0-g1-20260923`；本轮起点`1d023c211cc222a8582e72f24b4c973d1199b086`。即将固定新代码提交后补充SHA与第二轮证据。
- 审计基线`f206bcf6dff291d346cf5c3cf5a68bfce938935e` / `codex/source-current-20260922`。不使用main原型、不合并登录隔离WIP。
- 原`D:/VB/JKXT`的用户修改未覆盖。本轮不推送、不公网发布、不连接业务数据库、不使用历史Cookie、不调用外部计费AI/邮件短信。
- Supervisor保持暂停，不调用、修复或要求报告。
- 上轮状态原文完整保存在`evidence/STATUS-before-docker-recovery-20260924.md`，所有旧失败证据保留。

## 本轮实际落地

1. Docker恢复：精确枚举两个不可访问的残留通信socket目录，改名保留回滚后实际启动Engine29.1.3。未恢复出厂设置、删除数据卷或迁移业务库。过程和失败见`DOCKER-RECOVERY-20260924.md`。
2. 测试监听加固：test profile和runner明确127.0.0.1，清SERVER_/MANAGEMENT_继承值；实际Tomcat connector断言。初次unit错误要求独立management地址，独立审查依据Boot字节码纠正为共用回环端口；不隐瞒旧oracle不足。
3. 新建PG16/Redis7临时项目`jkxt-test-release-g1-20260924-r1`，库`jkxt_test_release_g1_20260924_r1`，端口25432/26379，tmpfs；空库实际迁移V1–V30。完整Maven开启Testcontainers，未加-Dtest或跳过参数。
4. 摘要器真实RED2失败后修复：精确下划线run选择、junit目录、同轮命令/身份/源码绑定、隔离元数据、失败保留和正式本地gate衔接；局部/旧结果不能提升完整通过。
5. 真实注册194个MVC handler行（193个唯一method/path），188业务接口全部匹配，6额外行属错误/文档设施。15动态前端点完成手工追溯，23异步token按实际类型区分；新增8项TRACE-EXT待验要求。见`RUNTIME-TRACE-DELTA-20260924.md`。
6. 追加Actuator等其他RequestMappingInfoHandlerMapping采集及HandlerMapping类型清单。新增断言须由第二轮验证，不能使用r1证明新增覆盖。

## 实际测试与证据

| 记录ID | 发现/执行 | 失败 | 错误 | 跳过 | 退出/结论 |
|---|---:|---:|---:|---:|---|
| test-network-red | 0/0 | — | — | — | 1，跨shell数组错误，非有效RED |
| test-network-valid-red | 1/1 | 1 | 0 | 0 | 1，原监听配置缺失 |
| test-network-green | 9/9 | 0 | 0 | 0 | 0，但管理端口oracle随后纠正，不是最终验收 |
| summary-adapter-red-r2 | 2/2 | 2 | 0 | 0 | 1，旧摘要器缺陷 |
| runtime-g1-tools | 109/109 | 0 | 0 | 0 | 0，53gate+22输入+27摘要+7记录器，非业务场景 |
| **release_g1_20260924_r1** | **274/274** | **0** | **0** | **0** | **0，73个Java类，完整Maven test** |
| 独立摘要审查 | 27+4工具反例 | 0 | 0 | 0 | 0，不重复计产品用例 |

r1绑定SHA`1d023c211cc222a8582e72f24b4c973d1199b086`与完整源码摘要`d56b000dd51eb95c1d17f90dea3b59d6eb075b5b6f469700af143173030be17a`（含本轮未提交源文件）。三条命令exit0、前后源不变。73 XML实际274case；源码声明258方法，不能混淆参数化数量。

原始证据：`evidence/release_g1_20260924_r1/`下command.json、日志、source快照、junit、environment、spring-mappings。命令为`run-isolated.ps1 -JavaHome <已验证JDK21> -Maven <原生Maven3.9.9> -Run release_g1_20260924_r1`；完整argv/cwd/时间/退出/日志hash以JSON为准。

数据库断言：专用库名、V30、迁移失败0、Redis PONG、真实监听127.0.0.1通过。未把测试fixture当真实学校数据，未把已有导出测试等同于84矩阵要求的全部文件内容断言。数据库保护器未改。

## 审查与限制

- `G1-RUNTIME-SAFETY-20260924.md`独立安全/test-integrity核查了最终四文件及原始73XML；共享教师资格夹具污染仍为待专项复现风险。
- `G1-SUMMARY-INTEGRITY-20260924.md`独立27+4反例及r1证据复核，无本轮阻断缺陷，不是外部认证。
- CodeRabbit真实CLI `runtime-g1-coderabbit` exit0、0 issues，覆盖8个已跟踪文件；未含未跟踪新测试和随后Actuator追加。独立审查已覆盖新测试；固定提交后再审完整范围。
- 两附件84项完整保留，scenario-ledger仍NOT_RUN，既有274测试通过不自动满足每个更严格业务要求。
- Docker恢复后既有自动重启容器也启动，仅查看名称/镜像/端口，未访问其业务库；旧部署不是当前候选。

## 唯一接续任务

1. 固定本轮提交，用全新r2项目/数据库/端口复跑完整套件，验证补充管理端点、保留实际SHA、运行本地gate。
2. 然后G2先复现用户会话索引TTL短于续期token导致撤销遗漏：专用账号/Redis、自然过期，不改机器时钟；缺索引应安全失效，不能无条件重建旧会话。
3. 后续按PLAN执行安全→数据/Excel/迁移→排班/独立结果/统一发布→异步/通知/真实AI→统计/备份恢复→前端生产化/真实E2E→终审。外部计费/真实数据/外发/生产迁移/公网部署仍需单独授权。

整体发布结论：**NOT APPROVED**。阶段检查通过不等于上线交付。
