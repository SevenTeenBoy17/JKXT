# JKXT交付整改接续状态（2026-09-24，Docker恢复后）

> GitHub 交接说明：以下保留整改阶段的原始执行叙述（包括当时“未推送”）。本次交接分支为 `codex/source-update-20260924`，只增加交接文档及当前结果说明，产品源码仍以 `aa57a6b` 为基准。原始 `evidence/`、本机数据库及 `baseline.json` 不上传；历史结果不能作为新 SHA 的质量门凭证。交接执行状态与重跑方法见仓库根 `GITHUB-UPDATE-20260924.md` 和 `README.md`。

**总体INCOMPLETE。两轮G0/G1完整测试通过；G2会话索引切片已修复且两轮完整GREEN通过。AUTH-02整体及G2–G8、84项交付矩阵未完成，不准生产发布。**

## 真实源码与边界

- 候选根 `D:/VB/JKXT-release-g0-g1`，分支`codex/release-g0-g1-20260923`；本轮起点`1d023c211cc222a8582e72f24b4c973d1199b086`。当前代码提交`aa57a6bb8edb5d97fb20495f0744ea6a372d4be7`，G1回滚点`bf320b69a24739166bb23ffb283eba0f48523946`。产品/测试源码已提交；后续结果文档和证据仍为工作区更新，未推送。
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
6. 追加Actuator等其他RequestMappingInfoHandlerMapping采集及HandlerMapping类型清单，r2已实际验证4条Actuator及9种mapping bean；合计198 handler行/197唯一method-path。注册不等于授权/健康状态行为验收。
7. G2新增真实Redis/PG安全回归，三次RED依次复现：索引过期仍授权、读取后撤销仍返回principal、仅修find引起保留设备误踢及重建无效成员资格。两个原子Redis脚本修复后的首轮完整GREEN通过，详见`G2-SESSION-RESULTS-20260924.md`。

## 实际测试与证据

| 记录ID | 发现/执行 | 失败 | 错误 | 跳过 | 退出/结论 |
|---|---:|---:|---:|---:|---|
| test-network-red | 0/0 | — | — | — | 1，跨shell数组错误，非有效RED |
| test-network-valid-red | 1/1 | 1 | 0 | 0 | 1，原监听配置缺失 |
| test-network-green | 9/9 | 0 | 0 | 0 | 0，但管理端口oracle随后纠正，不是最终验收 |
| summary-adapter-red-r2 | 2/2 | 2 | 0 | 0 | 1，旧摘要器缺陷 |
| runtime-g1-tools | 109/109 | 0 | 0 | 0 | 0，53gate+22输入+27摘要+7记录器，非业务场景 |
| **release_g1_20260924_r1** | **274/274** | **0** | **0** | **0** | **0，73个Java类，完整Maven test** |
| **release_g1_20260924_r2** | **274/274** | **0** | **0** | **0** | **0，固定bf320b6，全新数据库** |
| release_g2_20260924_red | 279/279 | 4 | 0 | 0 | 1，旧产品真实失败 |
| release_g2_20260924_race_red | 281/281 | 5 | 0 | 0 | 1，追加读取→撤销定序反例 |
| release_g2_20260924_gap_red | 283/283 | 3 | 0 | 0 | 1，仅修find不足，保留索引反例 |
| release_g2_20260924_green | 283/283 | 0 | 0 | 0 | 0，74类、274旧+9新；随后已冻结复跑 |
| **release_g2_20260924_frozen** | **283/283** | **0** | **0** | **0** | **0，固定aa57a6b，全新PG/Redis** |
| frozen-g2-release-tools | 109/109 | 0 | 0 | 0 | 0，固定候选的质量工具反例 |
| 独立摘要审查 | 27+4工具反例 | 0 | 0 | 0 | 0，不重复计产品用例 |

r1绑定SHA`1d023c211cc222a8582e72f24b4c973d1199b086`与完整源码摘要`d56b000dd51eb95c1d17f90dea3b59d6eb075b5b6f469700af143173030be17a`（含本轮未提交源文件）。三条命令exit0、前后源不变。73 XML实际274case；源码声明258方法，不能混淆参数化数量。

原始证据：`evidence/release_g1_20260924_r1/`下command.json、日志、source快照、junit、environment、spring-mappings。命令为`run-isolated.ps1 -JavaHome <已验证JDK21> -Maven <原生Maven3.9.9> -Run release_g1_20260924_r1`；完整argv/cwd/时间/退出/日志hash以JSON为准。

数据库断言：专用库名、V30、迁移失败0、Redis PONG、真实监听127.0.0.1通过。未把测试fixture当真实学校数据，未把已有导出测试等同于84矩阵要求的全部文件内容断言。数据库保护器未改。

r2绑定SHA`bf320b69a24739166bb23ffb283eba0f48523946`、摘要`02116cff45dfebf22733d9bdaf141f070bdfc9413eab508bf2815855ef2c23e7`；`frozen-g1-summary`和`frozen-g1-gate`均exit0。G2开始后`g2-source-drift-gate`实际exit1/SOURCE_HASH_MISMATCH，证明旧绿报告不能替代新增代码。

当前冻结绑定SHA`aa57a6bb8edb5d97fb20495f0744ea6a372d4be7`、源码摘要`e929db3ba81409b1f906cd3ba21a8df27b840fc0141fe505fc51a2e5bff1023e`。`frozen-g2-summary`和`frozen-g2-gate`均exit0，仍为G0/G1本地证据门、productionReady=false。`frozen-g2-database-assertions`实际SQL断言exit0，自有合成账号清理后0、V30/失败迁移0。完整实际命令、退出码、时间线与量化口径见`G2-SESSION-RESULTS-20260924.md`及对应command.json。

运行资源已收尾：`isolated-test-cleanup` exit0，仅逐一验证本轮7个项目的14个tmpfs测试容器后停止；临时合成数据库随tmpfs释放，原业务容器/卷不变，全部日志/XML/命令证据保留。重跑必须新run名，不能假定已停止的旧测试数据仍在。

当前静态清单720文件：33深读、519扫描、168未读（多为媒体/vendor）；G2深读增量另见`READING-LOG-G2-20260924.md`。188业务静态接口、212前端请求点、15动态请求由人工补充追溯，不把扫描覆盖当功能验收。

## 审查与限制

- `G1-RUNTIME-SAFETY-20260924.md`独立安全/test-integrity核查了最终四文件及原始73XML；共享教师资格夹具污染仍为待专项复现风险。
- `G1-SUMMARY-INTEGRITY-20260924.md`独立27+4反例及r1证据复核，无本轮阻断缺陷，不是外部认证。
- CodeRabbit真实CLI `runtime-g1-coderabbit` exit0、0 issues，覆盖8个已跟踪文件；未含未跟踪新测试和随后Actuator追加。独立审查已覆盖新测试；固定提交后再审完整范围。
- CodeRabbit固定G1复审`frozen-g1-coderabbit` exit0、0 issues，覆盖15文件，包括新增测试和Actuator采集；不代表后来G2修复已审。
- CodeRabbit固定G2复审`frozen-g2-coderabbit` exit0、0 issues，覆盖4文件，包括产品服务及9项新测试。独立代码/test-integrity审查核对三个RED、GREEN及冻结重跑，未删除测试或降低断言。详见两份G2审查文档。
- 两附件84项完整保留，scenario-ledger中AUTH-02为PARTIAL并绑定9项实际回归；其余未逐项验收。283测试通过不自动满足每个更严格业务要求。
- Docker恢复后既有自动重启容器也启动，仅查看名称/镜像/端口，未访问其业务库；旧部署不是当前候选。

## 唯一接续任务

1. 从当前aa57a6b接续G2，不重做Docker修复或已闭环会话索引切片；下一项先设计并复现AUTH-02服务端绝对寿命，以及登录签发与禁用/改密撤销的完整并发边界。九项切片不能代替全部AUTH-02。
2. 随后处理强制改密服务端门、学校/平台入口完整协议、CSRF正反向、生产开关与权限；仍须先RED、最小修复、专项及影响面回归。共享教师资格测试夹具污染风险也须专项复现。
3. 后续按PLAN执行安全→数据/Excel/迁移→排班/独立结果/统一发布→异步/通知/真实AI→统计/备份恢复→前端生产化/真实E2E→终审。外部计费/真实数据/外发/生产迁移/公网部署仍需单独授权。

整体发布结论：**NOT APPROVED**。阶段检查通过不等于上线交付。
