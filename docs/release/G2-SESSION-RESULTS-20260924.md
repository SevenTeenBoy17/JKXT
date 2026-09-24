# G2 会话索引整改执行证据

范围：AUTH-02 的续期索引/孤立token/撤销交错切片，不是全部认证协议或发布验收。仅隔离候选分支，原业务库与原工作树不动。

## 回滚基线

`bf320b69a24739166bb23ffb283eba0f48523946`。G1 r2实际完整274/274，fail/error/skip均0；SHA对应源码摘要`02116cff45dfebf22733d9bdaf141f070bdfc9413eab508bf2815855ef2c23e7`。frozen-g1-summary/gate都exit0。CodeRabbit frozen-g1-coderabbit实际审15文件、0 findings、exit0，不替代业务矩阵。

## 先复现

`release_g2_20260924_red`：新建专用PG/Redis，真实Maven全套279执行、4失败、0错误、0跳过、exit1。旧274项均无失败，新增5项中的4项失败：

- 合法请求后索引TTL仍约29982ms，没有随token续期。
- 改密成功且数据库hash已变化，失联旧token仍200（应401）。
- 禁用成功且数据库status=disabled，失联旧token仍200。
- 索引存在但缺成员的token仍200。

不是以删除token或缩短整个session伪造修复；测试对自己的索引设1ms TTL并有界等待Redis确认自然消失，token明确仍存续。不改主机时钟、不假称等待真实7天。

第二次`release_g2_20260924_race_red`：281执行/5失败/0错误/0跳过，exit1。新增读取→撤销反例真实返回旧principal；不是只有代码推断。

第三次`release_g2_20260924_gap_red`：仅修find，283执行/3失败/0错误/0跳过，exit1。原4缺陷和读取后撤销已通过，但暴露：DEL→SADD窗口误踢保留设备、重新加入已logout成员、重新授权原非成员。

最终最小产品改动仅`SessionService.java`：原子校验/续期、原子撤销/索引替换。新增测试文件包含9例，不修改旧测试断言。所有访问键显式KEYS传入；不在Lua动态拼键，不创建token正文，不恢复缺失成员。

## 首轮GREEN

`release_g2_20260924_green`：74测试类、283实际执行、0失败/0错误/0跳过、exit0（2026-09-24T08:19:52Z–08:22:22Z）。包含274原用例+9新回归。SHA为G1回滚点bf320b6加本轮待提交改动；源码摘要`e929db3ba81409b1f906cd3ba21a8df27b840fc0141fe505fc51a2e5bff1023e`、前后源码不变。

两个独立审查均读取实际JUnit/命令/hash；gap_red→green新增测试字节不变。随后已固定提交并在全新PG/Redis复测，结果见下一节，不把未提交GREEN替代冻结版本验收。

## 冻结复测与量化结果

本地代码提交：`aa57a6bb8edb5d97fb20495f0744ea6a372d4be7`（4文件：产品服务、新测试、规格、阅读范围）；未推送或替换在用应用。冻结源码摘要仍为`e929db3ba81409b1f906cd3ba21a8df27b840fc0141fe505fc51a2e5bff1023e`。结果文档和运行产物后续更新保留在工作区，不能伪称它们已包含于这个代码提交。

| 运行/记录ID | 发现/执行 | 失败 | 错误 | 跳过 | 实际退出码 |
|---|---:|---:|---:|---:|---:|
| release_g2_20260924_red | 279/279 | 4 | 0 | 0 | 1 |
| release_g2_20260924_race_red | 281/281 | 5 | 0 | 0 | 1 |
| release_g2_20260924_gap_red | 283/283 | 3 | 0 | 0 | 1 |
| release_g2_20260924_green | 283/283 | 0 | 0 | 0 | 0 |
| release_g2_20260924_frozen | 283/283 | 0 | 0 | 0 | 0 |
| frozen-g2-release-tools | 109/109 | 0 | 0 | 0 | 0 |

冻结完整套件实际开始`2026-09-24T08:25:27.5130266Z`，结束`08:27:41.7346997Z`。74类、267声明方法、283实际JUnit case；不能把参数化case与方法数混淆。原始日志SHA256=`77c24fc7e981bc6feb23c0cb3b8cbb37b588643138ce56b42d121eac06c368f7`。

实际冻结启动命令：

```powershell
& './scripts/release/run-isolated.ps1' -JavaHome 'D:/VB/JKXT-tools/login-entry-20260920/jdk-21.0.12.1+1' -Maven 'D:/VB/JKXT-tools/login-entry-20260920/apache-maven-3.9.9/bin/mvn.cmd' -Run release_g2_20260924_frozen -DatabasePort 25438 -RedisPort 26385
```

工作目录`D:/VB/JKXT-release-g0-g1`。此前四轮使用相同runner/JDK/Maven，run与PG/Redis端口依次为`red:25434/26381`、`race_red:25435/26382`、`gap_red:25436/26383`、`green:25437/26384`；完整实际argv、cwd、时间、exit和源码快照保存在对应`evidence/<run>/full-maven-suite.command.json`，没有-Dtest/-DskipTests。

- `frozen-g2-summary`：`node scripts/release/summarize.mjs release_g2_20260924_frozen`，exit0。
- `frozen-g2-gate`：`node scripts/release/check-release.mjs --root D:/VB/JKXT-release-g0-g1 --report docs/release/baseline.json --phase g0-g1`，exit0/PASS/**productionReady:false**；这是G0/G1证据质量门，不是正式G2或发布批准。
- `frozen-g2-release-tools`：`node --test scripts/release/check-release.test.mjs scripts/release/execution-package.test.mjs scripts/release/summarize.test.mjs scripts/release/record-command.test.mjs`，109通过。验证空报告、错误SHA、跳过/失败、编译冒充测试、非隔离库等反例。
- `frozen-g2-coderabbit`：`coderabbit review --agent -t committed --base-commit bf320b69a24739166bb23ffb283eba0f48523946`，实际4文件、0 issues、exit0。独立代理另作代码与test-integrity审查，不混称为CodeRabbit结果。
- `frozen-g2-database-assertions`：容器项目标签先核对为`jkxt-test-release-g2-20260924-frozen`，再以`psql -v ON_ERROR_STOP=1`执行DO断言和结果查询。exit0，实际库名正确、自有测试账号剩余0、V30、失败迁移0。SQL完整原文在command.json，不访问原业务库。
- 运行时194 MVC行+4 Actuator行仍由真实Spring采集；188业务接口注册映射匹配。注册不是功能、权限或导出内容通过证明。

量化口径：本切片新增回归9/9，冻结后台套件283/283，质量工具109/109；这些维度均100%仅指已执行集合。84项交付矩阵中AUTH-02为PARTIAL，其余尚无本轮逐项完整验收，不合成为虚假的“产品100分”。

## 测试资源收尾

`isolated-test-cleanup`记录exit0；脚本先核验本轮7个精确run的环境记录、Compose标签、服务名和纯tmpfs存储，再停止对应14个容器并确认全部非运行状态。没有删除业务卷/业务容器，也没有停止Docker本身。临时合成数据随tmpfs停止而释放，不能原地恢复，但可用相同测试在新run重建；源代码、失败与成功日志、JUnit、数据库断言结果均保留。禁止重用已停止旧run冒充新环境。

## 审查和门禁

- 独立test-integrity确认真实安全链、真实PG/Redis、唯一账号/keys、密码hash与status断言、精确清理。旧测试未删除或改弱。
- 独立代码审查G2-SR-01发现仅修find可能因DEL→SADD空窗误踢保留会话，已纳入本切片而非忽略。
- Debug Issue图工具真实返回0 nodes/0 edges，没有候选图覆盖；工具给出的low risk不采信，退回源码调用链与实际测试。没有把空图当审查通过。
- `g2-source-drift-gate`实际exit1、`SOURCE_HASH_MISMATCH`，正确拒绝以G1旧摘要替代新增源码的结果。

## 未覆盖边界

服务端绝对会话寿命、登录签发与禁用的完整并发串行化、账号/租户状态实时复查、CSRF、独立入口/多标签、生产安全配置仍未完成；不得将AUTH-02整体标PASS。新增的定序Redis交错不等同压力测试。未做公网发布、业务数据库迁移、外部AI计费、邮件短信外发或本轮浏览器验收。
