# A1 前端交接规格：按 permission 显隐写操作控件

> **背景**：A1 方案 B 的**后端已落地**（`V15` seed + `SecurityConfig` 按 `hasAuthority` 鉴权，全套件 93 绿）。
> 现在后端会对无权限的写请求返回 **403 `AUTH_FORBIDDEN`**。前端需同步**隐藏/禁用**用户无权使用的写控件，
> 否则用户看得到按钮、点了却 403（能用但体验差）。**此文是交接规格**——因前端当前有你未提交的改动，我未直接改，
> 由你（或我在你合并/授权后）按此实施。**注意：这是纯 UX 增强，不实施也不会造成安全漏洞**（后端已是权威防线）。

## 现状（已核实）
- 登录响应 `LoginResponse` 已含 `permissions: string[]`，但**前端目前完全没消费它**——`src/app.jsx` 只存
  `localStorage['jkxt:auth']='1'`（布尔登录态），`permissions` 被丢弃。
- 前端现在仅按 **mode（admin / super）** 和角色粗粒度显隐；无 permission 级控制。

## 需要做的（3 步）

### 1. 登录时存下 permissions
在 `src/app.jsx` 处理登录成功的地方（`localStorage.setItem('jkxt:auth','1')` 附近，约 :167），把响应的
`permissions` 存起来：
```js
try { localStorage.setItem('jkxt:perms', JSON.stringify(resp.permissions || [])); } catch {}
```
登出处（:197 / :240 / :297 移除 `jkxt:auth` 的每一处）同步 `localStorage.removeItem('jkxt:perms')`。

### 2. 提供一个 hasPerm 帮助器
在 `src/api.js`（`window.api` 附近）或一个共享工具里加：
```js
window.hasPerm = function (p) {
  try { return (JSON.parse(localStorage.getItem('jkxt:perms') || '[]')).includes(p); }
  catch { return false; }
};
```
（若已有全局工具文件，放那里并在 `Dashboard.html` 的正确加载顺序位置引入。）

### 3. 用 hasPerm 门控写控件（与后端矩阵一一对应）

| permission | 隐藏/禁用哪些前端写控件 | 涉及页面(src/pages/) |
|---|---|---|
| `masterdata:manage` | 教师/班级/考场/科目的「新增 / 编辑 / 删除」按钮；导入的「提交入库」 | classes-subjects.jsx、主数据相关页 |
| `exam:manage` | 一键排考的「创建任务 / 上传三表 / 导入校验」入口 | quick.jsx、tasks-page.jsx |
| `schedule:manage` | 排考结果的「编辑改派 / 发布 / 撤销 / 重做 / 冲突检查」、AI 策略「应用」、求解触发 | tasks-page.jsx、ai-policy.jsx、timeline.jsx |
| `platform:*` | 超管台各写操作（**当前已由 super mode 整体隔离**，可暂不细化到 permission） | super.jsx |
| `tenant:read` | 读操作——**四个角色都持有，无需隐藏任何读** | 全部 |

**读控件一律不隐藏**（所有登录用户都能读）。只隐藏**写**控件。

## 验收（前端做完后）
- 用 `l5.viewer`（VIEWER）/ `l5.teacher`（TEACHER_QUERY）登录 → 看不到任何新增/编辑/删除/发布按钮，只能浏览。
- 用 ADMIN（`tenant.test`）登录 → 所有业务写控件可见可用。
- 用 SUPER_ADMIN（`admin`）登录 → 业务写控件 + 超管台都可用。
- 后端已保证：即使前端漏隐藏某按钮，点击也会被 403 挡住（安全不依赖前端）。

## 与后端的对应关系（权威源）
后端矩阵见 `docs/A1-PERMISSION-MATRIX-PROPOSAL-20260717.md` §3 与 `SecurityConfig.java` 的规则 4a/4b/4c。
前端的 permission 名称必须与后端**逐字一致**（`masterdata:manage` 等）。

## ⚠️ 部署 A1 时必做（对抗审查 P1）
部署 V15+SecurityConfig 镜像后**必须清空会话存储**（`docker exec jkxt-private-redis redis-cli FLUSHDB` 或重建 redis 卷），
强制全员重新登录。原因：会话里的 permission 是登录时快照冻结在 Redis 的，升级前登录的会话带旧快照——SUPER_ADMIN 缺
新增的 `*:manage` 会写 403、VIEWER/TEACHER_QUERY 空权限会读 403，直到重登录。新登录已由 `306d7f0` 修复，此步只针对
升级瞬间的存量会话。详见 `PRE-GA-HARDENING-BACKLOG` C 组。

---
*后端已完成并验证（V15 + SecurityConfig + P1 修复，全套件 94/0/0/1 绿）。本前端部分是 A1 方案 B 的剩余增强，非安全阻塞项。*
