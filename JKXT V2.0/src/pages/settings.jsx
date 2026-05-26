// 信息设置 — 5 个模块的表单页:单位 / 地区 / 修改密码 / 确认密码 / 验证邮箱

function FormField({ label, required, hint, error, children }) {
  return (
    <div className="grid grid-cols-[140px_1fr_220px] gap-4 py-4" style={{ borderBottom: '1px solid var(--border-soft)' }}>
      <label className="pt-2 text-[13px] text-right" style={{ color: 'var(--text-medium)' }}>
        {label}
        {required && <span className="ml-1 text-rose-500">*</span>}
      </label>
      <div>
        {children}
        {error && (
          <div className="mt-1.5 text-[11.5px] inline-flex items-center gap-1" style={{ color: '#DC2626' }}>
            <IconAlert size={11} /> {error}
          </div>
        )}
      </div>
      <div className="pt-2.5 text-[11.5px]" style={{ color: 'var(--text-soft)' }}>
        {hint}
      </div>
    </div>
  );
}

function Input({ value, placeholder, prefix, suffix, type = 'text', error }) {
  return (
    <div
      className="flex items-center h-10 rounded-lg px-3 transition-all"
      style={{
        background: '#F8FAFC',
        border: `1px solid ${error ? '#EF4444' : 'transparent'}`,
      }}
    >
      {prefix && <span className="mr-2 text-[13px]" style={{ color: 'var(--text-soft)' }}>{prefix}</span>}
      <input
        type={type}
        defaultValue={value}
        placeholder={placeholder}
        className="flex-1 bg-transparent outline-none text-[13.5px]"
        style={{ color: 'var(--text-strong)' }}
      />
      {suffix && <span className="ml-2">{suffix}</span>}
    </div>
  );
}

function FormSection({ icon: Icn, title, sub, num, children }) {
  return (
    <div
      className="bg-white rounded-2xl"
      style={{ border: '1px solid var(--border-soft)', boxShadow: 'var(--elevation-1)' }}
    >
      <div
        className="px-6 py-4 flex items-center gap-3"
        style={{ borderBottom: '1px solid var(--border-soft)' }}
      >
        <span
          className="w-9 h-9 rounded-lg flex items-center justify-center font-mono font-bold text-[12px]"
          style={{ background: 'var(--brand-soft)', color: 'var(--brand)' }}
        >
          {num}
        </span>
        <div className="flex-1">
          <div className="text-[14.5px] font-semibold inline-flex items-center gap-2" style={{ color: 'var(--text-strong)' }}>
            {Icn && <Icn size={14} className="text-slate-400" />}
            {title}
          </div>
          <div className="text-[11.5px] mt-0.5" style={{ color: 'var(--text-soft)' }}>
            {sub}
          </div>
        </div>
      </div>
      <div className="px-6 py-2">
        {children}
      </div>
    </div>
  );
}

function PasswordStrength({ level = 2 }) {
  const labels = ['弱', '中', '强', '极强'];
  const colors = ['#EF4444', '#F59E0B', '#10B981', '#059669'];
  return (
    <div className="mt-2 inline-flex items-center gap-2">
      <div className="flex gap-1">
        {[0, 1, 2, 3].map((i) => (
          <span
            key={i}
            className="w-7 h-1 rounded-full"
            style={{ background: i < level ? colors[level - 1] : '#E2E8F0' }}
          />
        ))}
      </div>
      <span className="text-[11px] font-medium" style={{ color: colors[level - 1] }}>
        密码强度: {labels[level - 1]}
      </span>
    </div>
  );
}

function CascadeSelect({ items }) {
  return (
    <div className="flex items-center gap-2 flex-wrap">
      {items.map((it, i) => (
        <button
          key={i}
          className="h-10 px-3 rounded-lg inline-flex items-center gap-2 hover:bg-slate-50 transition-colors min-w-[110px]"
          style={{ background: '#F8FAFC' }}
        >
          <span className="text-[12px]" style={{ color: 'var(--text-soft)' }}>{it.label}</span>
          <span className="flex-1 text-[13.5px] font-medium text-left" style={{ color: 'var(--text-strong)' }}>{it.value}</span>
          <IconChevron size={12} className="text-slate-400" />
        </button>
      ))}
    </div>
  );
}

function SettingsPage() {
  return (
    <PageShell
      crumbs={['天津市实验中学', '系统', '信息设置']}
      title="信息设置"
      subtitle="维护单位信息、地区、登录账户与联系方式;字段修改将记录审计日志"
      actions={
        <>
          <Button onClick={() => window.ui.toast('已取消修改', { kind: 'info' })}>取消</Button>
          <Button variant="primary" icon={IconCheck} onClick={async () => {
            const ok = await window.ui.confirm({
              title: '保存信息设置?',
              desc: '本次修改将记录在审计日志中，部分字段需重新登录后生效。',
              confirmText: '确认保存',
            });
            if (ok) window.ui.toast('设置已保存', { kind: 'success', desc: '部分字段下次登录后生效' });
          }}>保存修改</Button>
        </>
      }
    >
      <div className="grid grid-cols-[1fr_320px] gap-5">
        <div className="space-y-5">

          {/* Module 1: 单位信息 */}
          <FormSection num="01" icon={IconBuilding} title="单位信息" sub="基础单位档案,显示于顶栏与导出文件">
            <FormField label="单位名称" required hint="将显示于顶栏、登录页、所有导出报表的页眉">
              <Input value="天津市实验中学" placeholder="请输入完整学校名称" />
            </FormField>
            <FormField label="单位类型" hint="决定排班逻辑中默认的年级体系">
              <div className="inline-flex p-1 rounded-lg" style={{ background: '#F1F5F9' }}>
                {['小学', '初中', '高中', '完全中学', '九年一贯'].map((t, i) => {
                  const active = i === 3;
                  return (
                    <button key={t}
                      className="h-8 px-3 text-[12.5px] font-medium rounded-md transition-all"
                      style={{ background: active ? '#fff' : 'transparent', color: active ? 'var(--brand)' : 'var(--text-medium)', boxShadow: active ? 'var(--elevation-1)' : 'none' }}>
                      {t}
                    </button>
                  );
                })}
              </div>
            </FormField>
            <FormField label="联系电话" hint="紧急联系号码,用于系统通知">
              <Input value="022-2740-XXXX" prefix="+86" />
            </FormField>
          </FormSection>

          {/* Module 2: 地区 */}
          <FormSection num="02" icon={IconBolt} title="所在地区" sub="行政区划用于跨校统计、上级报表汇总">
            <FormField label="行政区划" required hint="国 / 省 / 市 / 区 四级级联">
              <CascadeSelect items={[
                { label: '国', value: '中国' },
                { label: '省', value: '天津市' },
                { label: '市', value: '天津市' },
                { label: '区', value: '南开区' },
              ]} />
            </FormField>
            <FormField label="详细地址" required hint="街道门牌号,用于发送物料邮寄">
              <textarea
                defaultValue="天津市南开区卫津路 92 号"
                className="w-full h-20 rounded-lg p-3 text-[13.5px] outline-none resize-none"
                style={{ background: '#F8FAFC', border: '1px solid transparent', color: 'var(--text-strong)' }}
              />
            </FormField>
          </FormSection>

          {/* Module 3 + 4: 密码 */}
          <FormSection num="03" icon={IconSettings} title="账户安全" sub="登录账户密码 + 二次验证邮箱">
            <FormField label="登录账号" hint="账号创建后不可修改">
              <div className="inline-flex items-center gap-2">
                <span className="font-mono text-[14px] font-semibold" style={{ color: 'var(--text-strong)' }}>wangmy@tsy.edu.cn</span>
                <Tag color="emerald" dot>已激活</Tag>
              </div>
            </FormField>
            <FormField label="新密码" required hint="≥ 8 位,含大小写字母与数字,避免使用生日 / 电话">
              <Input type="password" value="••••••••••" placeholder="输入新密码" />
              <PasswordStrength level={3} />
            </FormField>
            <FormField label="确认新密码" required hint="再次输入以确认无误">
              <Input type="password" value="••••••••••" placeholder="再次输入新密码" />
            </FormField>
            <FormField label="二次验证" hint="开启后每次登录需手机或邮箱验证码">
              <div className="inline-flex items-center gap-3">
                <button className="relative w-11 h-6 rounded-full transition-colors" style={{ background: '#10B981' }}>
                  <span className="absolute top-0.5 right-0.5 w-5 h-5 rounded-full bg-white" style={{ boxShadow: 'var(--elevation-1)' }} />
                </button>
                <Tag color="emerald" dot>已启用 (邮箱)</Tag>
              </div>
            </FormField>
          </FormSection>

          {/* Module 5: 验证邮箱 */}
          <FormSection num="04" icon={IconHelp} title="验证邮箱" sub="用于找回密码、接收审计通知、二次验证">
            <FormField label="主邮箱" required hint="主邮箱用于关键账户操作,变更需当前密码">
              <div className="flex items-center gap-2">
                <div className="flex-1">
                  <Input value="wangmy@tsy.edu.cn" />
                </div>
                <Tag color="emerald" dot>已验证</Tag>
                <Button size="sm" variant="ghost">更换邮箱</Button>
              </div>
            </FormField>
            <FormField label="备用邮箱" hint="可选;主邮箱不可用时的备用收件箱">
              <div className="flex items-center gap-2">
                <div className="flex-1">
                  <Input value="" placeholder="可选,留空表示未设置" />
                </div>
                <Button size="sm" onClick={() => window.ui.toast('请输入新邮箱后发送验证码', { kind: 'info' })}>发送验证码</Button>
              </div>
            </FormField>
          </FormSection>

        </div>

        {/* Right rail */}
        <div className="space-y-4">
          <div className="bg-white rounded-2xl p-5"
            style={{ border: '1px solid var(--border-soft)', boxShadow: 'var(--elevation-1)' }}>
            <div className="text-[13px] font-semibold mb-3" style={{ color: 'var(--text-strong)' }}>
              账户档案
            </div>
            <div className="flex items-center gap-3 mb-4">
              <div className="w-14 h-14 rounded-full flex items-center justify-center text-[20px] font-semibold text-white"
                style={{ background: 'linear-gradient(135deg, #6366F1 0%, #9333EA 100%)' }}>
                王
              </div>
              <div>
                <div className="text-[14px] font-semibold" style={{ color: 'var(--text-strong)' }}>王明远</div>
                <div className="text-[11.5px] mt-0.5" style={{ color: 'var(--text-soft)' }}>教务主任 · 超管</div>
              </div>
            </div>
            <div className="space-y-2 text-[12.5px]">
              {[
                { k: '登录账号', v: 'wangmy@tsy.edu.cn', mono: true },
                { k: '上次登录', v: '今天 08:32', mono: true },
                { k: '上次 IP',   v: '124.65.xxx.xxx', mono: true },
                { k: '账号创建', v: '2024-08-10', mono: true },
              ].map((r) => (
                <div key={r.k} className="flex items-baseline justify-between">
                  <span style={{ color: 'var(--text-soft)' }}>{r.k}</span>
                  <span className={r.mono ? 'font-mono' : ''} style={{ color: 'var(--text-strong)', fontSize: r.mono ? 12 : 13 }}>
                    {r.v}
                  </span>
                </div>
              ))}
            </div>
          </div>

          <div className="bg-white rounded-2xl p-5"
            style={{ border: '1px solid var(--border-soft)', boxShadow: 'var(--elevation-1)' }}>
            <div className="text-[13px] font-semibold mb-3" style={{ color: 'var(--text-strong)' }}>
              最近操作
            </div>
            <ul className="space-y-2.5">
              {[
                { t: '修改了二次验证方式', at: '今天 14:20', c: '#10B981' },
                { t: '导入了 142 名教职工', at: '昨天 09:30', c: '#0EA5E9' },
                { t: '更新了单位地址',   at: '11-15 16:00', c: '#94A3B8' },
                { t: '注册账号',         at: '2024-08-10',   c: '#94A3B8' },
              ].map((it, i) => (
                <li key={i} className="flex items-start gap-2.5">
                  <StatusDot color={it.c} />
                  <div className="min-w-0 flex-1">
                    <div className="text-[12.5px]" style={{ color: 'var(--text-medium)' }}>{it.t}</div>
                    <div className="text-[10.5px] mt-0.5 font-mono" style={{ color: 'var(--text-soft)' }}>{it.at}</div>
                  </div>
                </li>
              ))}
            </ul>
          </div>

          <div className="rounded-2xl p-5 text-white relative overflow-hidden"
            style={{ background: 'linear-gradient(135deg, #4F46E5 0%, #7C3AED 100%)' }}>
            <div className="text-[13px] font-semibold mb-1">需要帮助?</div>
            <div className="text-[11.5px] opacity-80 mb-3">查看操作手册、视频教程,或联系教务处技术支持</div>
            <Button size="sm" style={{ background: 'rgba(255,255,255,.18)', color: '#fff' }}
              onClick={() => window.ui.toast('正在打开帮助中心', { kind: 'info', desc: '常见问题、操作视频、联系教务处' })}>
              查看帮助中心 →
            </Button>
            <svg className="absolute -right-4 -bottom-6 opacity-20" width="120" height="120" viewBox="0 0 120 120">
              <circle cx="60" cy="60" r="40" fill="none" stroke="#fff" strokeWidth="1" />
              <circle cx="60" cy="60" r="55" fill="none" stroke="#fff" strokeWidth="1" />
            </svg>
          </div>
        </div>
      </div>
    </PageShell>
  );
}

window.SettingsPage = SettingsPage;
