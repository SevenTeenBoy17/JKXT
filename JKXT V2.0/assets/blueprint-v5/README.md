# V5 资产来源与使用边界

本轮使用 GPT 原生生图制作三个纯绿色背景参考图集，提示词见 `prompts.json`：

- `symbols-green.png`：六类业务图标。
- `empty-green.png`：三表空状态插画。
- `controls-green.png`：按钮、搜索、标签、切换控件的外观参考，不作为可点击图片使用。

生产页面引用 `symbols-clean-transparent.png` 和 `empty-transparent.png`。前者经过原生模型清理绿边，再用 Runway 提取透明层；后者由 Runway 提取透明层。所有文件均实际为 1536×1024 PNG，不声称 4K。图标在页面中显示为 36–48px，插画按容器缩放。

保留 `symbols-transparent.png` 为初次提取稿；`symbols-cutout-refined.png` 为不合格的 Picsart 尝试，**不被生产代码引用**。原生的两份提取失败稿及带棋盘格的中间稿保留在生成目录，不充作透明成品。Magnific 因需要重新认证未使用成功。不同工具的尝试不等于质量通过。

按钮文字、状态、边框、输入框、焦点和禁用态仍由真实 DOM 与学校主题 Token 渲染，保持键盘操作与准确业务反馈。未修改已有 Logo、导航和登录资产。旧主题自带图标保留；新生图只承担装饰性视觉，不承载唯一的业务含义。

最终色板/几何以真实页面为准。生成图不是学校数据，也不是接口调用成功证据。
