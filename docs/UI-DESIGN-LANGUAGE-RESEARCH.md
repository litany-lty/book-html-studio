# 「纸页工坊」UI 设计语言调研报告

> 目标产品：PDF → 可校对 HTML 书籍的阅读 + 校对工具（Java 后端 + 原生 JS/CSS，无前端框架），中文桌面 / 移动 Web。
> 调研对象：beautifului.dev、beui.dev、rareui.com、transitions.dev、ui.shadcn.com（设计令牌 / 组件规范 / Typeset / Figma）。
> 调研方式：实际抓取 5 站页面 HTML + 样式表，提取 CSS 自定义属性（token）与组件类规范；正文数值均来自实抓资源，来源 URL 见文末。

---

## 0. 采集说明（站点可达性）

| 站点 | 状态 | 说明 |
| --- | --- | --- |
| https://beautifului.dev | 可访问（301 → `www.beautifului.dev`） | 单页组件陈列站；样式来自外链 CSS，已抓取 `/_next/static/css/916c3f25c8795666.css` 提取全部 token |
| https://beui.dev | 可访问 | 组件站；已抓取首页与 `/components/motion/button`、`/components/motion/tabs`、`/components/motion/switch`（含共享动效令牌 `lib/ease.ts`） |
| https://rareui.com | 可访问（301 → `www.rareui.com`） | 组件站；已抓取外链 CSS `/_next/static/chunks/0pkf8xppblx45.css` 提取 token |
| https://transitions.dev | 首页为 JS 演示站，`web_fetch` 仅返回标题 | **已用等价资料补充**：其开源仓库的规范文档 [Jakubantalik/transitions.dev — skills/transitions-dev/SKILL.md](https://github.com/Jakubantalik/transitions.dev/blob/main/skills/transitions-dev/SKILL.md)（含完整 motion token 表），并抓取首页内联 CSS 交叉验证（`--duration-*`、`--ease-*`、`--p12-error-*` 等与文档一致） |
| https://ui.shadcn.com | 可访问 | `/docs/components/button` 301 → `/docs/components/base/button`。**`/docs/tokens` 与 `/tokens` 均为 404**——shadcn 没有独立 "tokens" 文档页，令牌即 [Theming](https://ui.shadcn.com/docs/theming) 里的 CSS 变量表 + [Typeset](https://ui.shadcn.com/docs/typeset) 排版令牌；[Figma](https://ui.shadcn.com/docs/figma) 页只列社区 Figma Kit 与 Figma MCP（design-to-code）方案，无官方 token 表。另抓取 GitHub 源码 `style-nova.css`、`tailwind.css`、`typeset.css`、`bases/base/ui/*.tsx` 得到组件级精确规格 |

---

## 1. beautifului.dev

**实访问 URL**：<https://www.beautifului.dev/>（含样式表 `/_next/static/css/916c3f25c8795666.css`）

### 1.1 站点定位
「Crafted primitives for AI-native interfaces」——为 AI 产品（Agent 对话、工具调用、审批、差异展示）提供**成品界面模式画廊**，由 TurboProduct 设计工作室出品，给需要快速做 AI 产品 UI 的设计师 / 独立开发者看。21 个模式：Loading State、Thinking、Streaming Text、Approval Card、Tool Chips、Task Rows、Diff Table、Records Table、Context Cards、Selection Actions 等。

### 1.2 核心视觉语言
- **配色：中性色三级墨阶 + 单主色 + 三个语义色**，双主题（light/dark）用同一套语义变量翻转。
  - 页面层级：`--page`（应用底）、`--canvas`（内容舞台）、`--surface`（卡片/面板）、`--field`（输入框）、`--inset`（内嵌区）、`--stripe`（表格条纹，oklch 低透明度）。
  - 墨阶：`--ink`（正文）/ `--ink-2`（次级）/ `--ink-3`（弱化），全部是**近中性、极低彩度**（oklch 的 chroma ≤ 0.01，色相约 250–265 的冷灰）。浅色值：ink `oklch(96.4% .002 247.839)`、ink-2 `oklch(73.1% .008 260.731)`、ink-3 `oklch(69.5% .009 264.505)`；深色反转为 24.7% / 50.6% / 54.1%。
  - 线：`--line` / `--line-soft` / `--line-strong` 三级边框（浅色 ≈ oklch 94.6% / 96.6% / 91.2%）。
  - 主色仅一个蓝 `--accent`（浅 `oklch(68% .173 253.301)`），另配 `--green` `--orange` `--red` 三个语义色 + 各自 `-tint`（14% 透明底）。
  - 标签系统用 `--tag-base`（oklch 65–75% 彩度 0.11–0.16）+ 浅染底，色相覆盖面广但彩度受控。
- **排版**：Inter（UI）+ JetBrains Mono（数字/表格/等宽场景）；字重只有 400/500/600/700；行高 `--leading-tight 1.25 / snug 1.375 / normal 1.5 / relaxed 1.625`；字距 `-.02em / -.01em / .08em / .09em`（大字负字距、小号大写标签正字距）；表格数字用 `tabular-nums`。
- **圆角**：语义化四级——`--radius-chip 6px`（徽标/标签）、`--radius-control 8px`（按钮/输入）、`--radius-card 10px`（卡片）、`--radius-window 14px`（窗口/大浮层）。
- **阴影**：核心不是投影而是**发丝描边环**——`--shadow-hairline: 0 0 0 1px var(--line)`；卡片 `--shadow-card = 1px 环 + 0 1px 2px + 0 2px 6px`（深色版把环改为 `oklch(100% 0 0/.11)`）；浮层 `--shadow-overlay = 0 0 0 1px + 0 8px 28px`；另有 Tailwind 式 `--shadow-xs/sm/md/lg` 多层极低透明度投影（每层 alpha 0.03–0.08）。
- **间距**：`--spacing .25rem`（4px 基准）；卡片内部以 4 的倍数（8/12/16/20）排布。

### 1.3 组件模式（实抓可见）
- **Diff Table**：AI 建议改动"扫过"表格——改动行内联高亮、行内单元格成对出现（旧值划除/新值插入），顶部一行标题 + 操作按钮。这是**校对场景最直接可抄的模式**。
- **Records Table**：行号列（`records-rownum`）+ 复选框 + 首列 sticky + 标签组（超量折叠为 `+3`）+ 强度点（`records-strength-dot`，用 green/orange/red/ink-3 四态）+ 外链带 ↗ 图标。密度高、边框细分、条纹底。
- **Approval Card（人机确认卡）**：问题标题 + 一组选项按钮 + `1 / 3` 进度 + `Skip / Continue` 双按钮（Skip 为弱化、Continue 为主行动）。
- **Context Cards**：证据块卡片——正文摘要 + 来源徽标（PDF / CSV chip + 文件名），顶部 `All chunks 32` 计数。
- **Task Rows**：状态行（running/failed/completed）用「标题 + 计数 + 状态词」三段式，草稿态用 `draft` 徽标。
- **按钮层级**：页面里主行动实底、次行动描边/弱底、Skip 类 ghost；危险操作未见高饱和实底（走 tinted 路线）。
- **微交互**：加载器（像素网格 shimmer + 已用时 `0.0s` 计数）、Thinking 可展开步骤、Streaming 文本带内联来源与后续追问 chips。

### 1.4 动效与过渡规范
站点本身克制：`--default-transition-duration .15s`，时长档位 `.1s / .15s / .2s / .25s`；缓动集中四条：
- `--ease-in-out cubic-bezier(.4,0,.2,1)`（默认）
- `--ease-out cubic-bezier(0,0,.2,1)`
- `--ease-out-strong cubic-bezier(.23,1,.32,1)`（进入强调）
- `--ease-link cubic-bezier(.16,1,.3,1)`（链接/箭头类微动）
进入/退出以淡入 + 1px 位移为主，无大幅缩放与回弹。

### 1.5 对「长时间阅读 + 精细校对」最值得借鉴
1. **三级墨阶（ink / ink-2 / ink-3）+ 三级线（line / line-soft / line-strong）**：正文明度与辅助信息拉出稳定层级，长时阅读不易混淆主次。
2. **阴影 = 1px 描边环 + 极淡多层投影**：纸面/正文区完全无投影，只有浮层用 `0 0 0 1px + 大羽化`，视觉负担最小。
3. **Diff Table 的"改动扫过"表达**：校对建议按「行内旧值划除 / 新值就位」呈现，可直接映射为"PDF 原文 ↔ 校对后文本"对照。
4. **Records Table 的行号列 + 首列 sticky + 标签折叠**：适合"问题清单/校对任务"长表（页码、行号、问题类型标签、状态点）。
5. **表格数字 `tabular-nums` + 等宽字体承载坐标类信息**：页码、字符偏移、置信度不会跳动。

---

## 2. beui.dev

**实访问 URL**：<https://beui.dev/>、<https://beui.dev/components/motion/button>、<https://beui.dev/components/motion/tabs>、<https://beui.dev/components/motion/switch>

### 2.1 站点定位
「Animated components for React and Next.js」——基于 Motion（Framer Motion）+ Tailwind 4 + React 19 的**动效组件库**（124 个组件），免费开源、shadcn CLI 分发（`shadcn add @beui/tilt-card`），面向想让产品"活起来"的前端/独立开发者。

### 2.2 核心视觉语言
- **配色**：沿用 shadcn 语义变量（`--background/--card/--popover/--primary/--secondary/--muted/--accent/--destructive/--border/--input/--ring`），但提供 **20 套高彩度主题预设**（`oklch(55%~80% 0.12~0.22 …)`：紫 275/290、蓝 255、青 185、绿 130/150、橙 40/70、红/粉 12/25 等）。浅色底 `oklch(99% 0 0)` 近白、深色底 `#151515`，卡片浅 `oklch(97%)` / 深 `#1c1c1c`。
- **边框/输入**：`--border: oklch(15% 0 0 / 0.06)`（浅）与 `rgb(255 255 255 / 0.05)`（深）——**6% 透明边框**是它的关键手法；`--ring` 是主色 50%–55% 透明。
- **排版**：Inter/system 栈；组件文本集中在 `text-sm / text-xs`，按钮字重中等。
- **圆角/阴影**：组件走 Tailwind 默认档（`rounded-lg/xl`），浮层多用**玻璃面（backdrop-blur + 半透明 bg）**：command palette、bottom sheet、drawer 都标注 "glass surface"。
- **间距**：`--gap: 1rem`，8/12/16 为主。

### 2.3 组件模式
- **Button**：Spring-pressed（按压回弹）+ StatefulButton、MagneticButton（磁吸）、MetallicButton（金属质感）四种变体。
- **Tabs**：Pill / Segment / Underline 三形态，active 指示器用 `layoutId` 共享布局弹簧滑动。
- **Switch**：弹簧驱动滑块 + 按压反馈（press 时 `scale: squish ? 0.9 : 1`）。
- **Morphing Modal**：单面板在内部视图间**形变高度**，内容 blur 交叉淡入——适合作"向导式多步对话框"参考。
- 其他：Animated Toast Stack（滑动关闭、状态形变）、Drawer（弹簧侧栏 + backdrop blur + esc 关闭）、Bottom Sheet（吸附点 + 惯性）、Expandable Tabs（图标栏 active 展开为文字 pill）、OTP Input（滑动焦点环 + 错误抖动 + 成功勾线）。
- **Theme Toggle**：用 View Transition API 全页 clip-path 揭示换肤（矩形/圆形/百叶窗）。

### 2.4 动效与过渡规范（实抓 `lib/ease.ts`，可直接落地）
缓动：
- `EASE_OUT = cubic-bezier(0.16, 1, 0.3, 1)`（注释明确："默认 ease-in/ease-out 太弱，用强变体"）
- `EASE_IN_OUT = cubic-bezier(0.77, 0, 0.175, 1)`
- `EASE_DRAWER = cubic-bezier(0.32, 0.72, 0, 1)`（抽屉/侧滑专用）

弹簧（Motion spring）按语义命名，是它最值得抄的部分：
| Token | 参数 | 用途 |
| --- | --- | --- |
| `SPRING_PRESS` | stiffness 500 / damping 30 / mass 0.6 | 按钮等可按压面 |
| `SPRING_SWAP` | 460 / 30 / 0.55 | 控件内文字/图标换位 |
| `SPRING_PANEL` | 420 / 40 / 0.5 | 模态/浮层进场 |
| `SPRING_LAYOUT` | 360 / 32 / 0.6 | 共享布局滑动（tab 指示器） |
| `SPRING_MOUSE` | 200 / 15 / 0.3 | 磁吸/倾斜（装饰） |
| `SPRING_GLIDE` | 700 / 50 / 0.5 | 滑杆等跟手不回弹（临界阻尼） |

时长：常规过渡 `duration-200/300`（0.2/0.3s）、图标/文字交叉 `0.15–0.18s`、按压 `0.16s`、装饰循环 1.6–8s。颜色过渡 `transition-colors duration-200/300 ease-[cubic-bezier(.32,.72,0,1)]`。

### 2.5 对「长时间阅读 + 精细校对」最值得借鉴
1. **把动效收敛为一份具名令牌文件（ease.ts / CSS 变量）**：每个语义动作一个 token（press/swap/panel/layout），杜绝魔法数字——本项目应照做（见综合建议 §5）。
2. **SPRING_GLIDE 的临界阻尼思路**：校对滑杆（字号/行距/对比度调节）跟手不回弹。
3. **Tabs 的共享布局指示器**：阅读/校对/大纲多模式切换用一条滑动指示条，比换色更雅、更安静（可改用 CSS `transition: transform/width` 实现，无需 Motion）。
4. **Morphing Modal 的"面板内高度形变 + 内容交叉淡入"**：适合"校对问题逐条处理"的多步对话框（同一条问题内切换「定位/改写/确认」）。
5. **反面借鉴**：其玻璃拟态、3D tilt、磁吸按钮、全页 clip-path 换肤**不要**用在阅读器正文上（见 §7）。

---

## 3. rareui.com

**实访问 URL**：<https://www.rareui.com/>（含样式表 `/_next/static/chunks/0pkf8xppblx45.css`）

### 3.1 站点定位
「Rare Animated React Components」——20+ 个"稀有/独特"的**装饰性动效组件**（Folder 组件、Fluid Orb、Gravity Letters、Duration Picker、OTP Input），shadcn CLI 单文件安装，主要受众是作品集/落地页开发者与设计工程师。

### 3.2 核心视觉语言
- **配色**：标准 shadcn 令牌 + 每个 demo 一套 accent（如图表蓝 `--chart-1 #3b82f6 → --chart-5 #1e3a8a` 五级同色相阶梯；官网强调橙 `#fc4c01`；sky `#e0f2fe/#1e3a8a`）。中性色是 Tailwind neutral 灰阶（`#fafafa→#0a0a0a`）。
- **排版**：Inter 为主（`--font-sans: Inter`），但**提供衬线正文栈 `--font-serif: "Source Serif 4", serif`**（演示中用于长文阅读段）；字重 400/500/600/700；字距 `-0.01em / .06em / .07em / .12em`（小号大写标签用 0.06–0.12em）；行高 `1 / 1.375 / 1.625`。
- **圆角**：`--radius .375rem`（6px）基准，派生 `--radius-2xl 1rem / 3xl 1.5rem`（大圆角只用于展示卡）。
- **阴影**：Tailwind 标准 7 档（`--shadow-2xs … --shadow-2xl`），另有可调阴影原语 `--shadow-x/y/blur/spread/opacity`（默认 0/1px/3px/0/.1）。
- **模糊档位**：`--blur-xs 4px / sm 8px / md 12px / xl 24px / 2xl 40px`。

### 3.3 组件模式
- 全部基于 Motion 的一次性组件：Folder（文件夹翻页 3D）、Fluid Orb（流体球）、Gravity Letters（字母重力掉落）、Duration Picker（旋转时长选择）、OTP Input。
- 底层仍是 shadcn 的按钮/卡片/徽标体系（default/outline/secondary/destructive 变体、`--muted`/`--border` 语义、`--sidebar-*` 侧栏变量）。
- 组件库本身没有独立的表单/对话框规范——**它是"效果库"而非"设计系统"**。

### 3.4 动效与过渡规范
- 默认过渡 `--default-transition-duration .15s` + `cubic-bezier(.4,0,.2,1)`；时长档 `.15/.2/.3/.4s`；缓动 `--ease-out cubic-bezier(0,0,.2,1)` 与 `cubic-bezier(.215,.61,.355,1)`（经典 easeOutCubic）。
- 特色在于**变形类效果**（字母重力、流体、3D 翻页），时长长（0.6–1s+）且带弹性，明显是展示型而非工具型节奏。

### 3.5 对「长时间阅读 + 精细校对」最值得借鉴
1. **`--font-serif: Source Serif 4` 的"衬线正文 + 无衬线 UI"分工**：与本项目"书稿正文衬线、工具界面黑体"的诉求一致（中文换成思源宋体/霞鹜文楷一类）。
2. **小号大写标签字距 0.06–0.12em** 的做法：可用于"章节眉/页码/版面坐标"等辅助信息（中文改 0.02–0.05em，勿照搬）。
3. **阴影可调原语（x/y/blur/spread/opacity）**：一套变量调全站投影，比写死 shadow 名更灵活。
4. **Duration Picker 形态**可改造为"字号/行距步进选择器"（用其形态不用其重力/3D 效果）。
5. **反面借鉴**：重力字母、流体球、3D 翻页文件夹属于作品集特效，进入校对工具只会干扰逐字比对。

---

## 4. transitions.dev（重点）

**实访问 URL**：<https://transitions.dev/>（首页 JS 演示，`web_fetch` 仅得标题）；**等价补充资料**：[Jakubantalik/transitions.dev — SKILL.md（motion tokens 全表）](https://github.com/Jakubantalik/transitions.dev/blob/main/skills/transitions-dev/SKILL.md)、[仓库首页](https://github.com/Jakubantalik/transitions.dev)、[index.html（token 用法）](https://github.com/Jakubantalik/transitions.dev/blob/main/index.html)。首页内联 CSS 已交叉验证 token 值一致。

### 4.1 站点定位
「UI transitions for AI agents / 网页应用必备过渡合集」——32 个**可直接粘贴的纯 CSS 过渡片段**（`t-*` 命名空间 + 语义 CSS 变量 + `prefers-reduced-motion` 兜底），并附带「motion tokens」统一时长/缓动/位移/缩放/模糊量表，以及给 Agent 用的 `transitions reveal/review/apply/refine` 命令。受众：做 Web 应用（尤其 AI 应用）的开发者/Agent。

### 4.2 核心视觉语言（站点自身）
- 高对比黑白灰 + 单主色蓝：浅色 `--bg #fdfdfd`、`--card-bg #ffffff`、`--text #0d0d0d`、`--text-muted #6c6c6c`、`--text-subtle #767676`、`--muted #5e6073`；深色 `#121212 / #181818 / #e3e3e3`。主色 `--accent #0073e5`（浅）/ `#55cfff`（深），配套 `--accent-hover`、`--accent-soft rgba(0,115,229,.09)`、`--accent-soft-hover .15`。
- 字体 Inter + Roboto Mono；卡片 `--card-pad-x 20px`、`--card-border rgba(0,0,0,.06)`、`--card-shadow 0 1px 3px 0 rgba(0,0,0,.04)`。
- 菜单阴影是「1px 环 + 2px + 42px 大羽化」三层：`0 0 0 1px rgba(0,0,0,.06), 0 2px 6px rgba(0,0,0,.05), 0 4px 42px rgba(0,0,0,.06)`。

### 4.3 动效与过渡规范（核心产出，可直接抄）
**Durations（7 档具名令牌）**
| Token | 值 | 用途 |
| --- | --- | --- |
| `--duration-stagger` | `40ms` | 列表逐项错峰 |
| `--duration-micro` | `80ms` | tooltip 延迟、抖动分段、大错峰 |
| `--duration-quick` | `150ms` | **关闭**（modal/dropdown）、文字换态、tooltip 出现 |
| `--duration-fast` | `250ms` | **打开**、图标互换、tab 滑动、页面滑动 |
| `--duration-medium` | `350ms` | 面板关闭、toast 关闭 |
| `--duration-slow` | `400ms` | 面板打开、骨架屏内容揭示、输入清空 |
| `--duration-very-slow` | `500ms` | 强调时刻（徽标出现、文本揭示、成功勾） |

**Easings（6 档）**
| Token | 值 | 用途 |
| --- | --- | --- |
| `--ease-smooth-out` | `cubic-bezier(0.22, 1, 0.36, 1)` | 开/关、页面滑动、位移/尺寸变化（主力缓动） |
| `--ease-in-out` | `ease-in-out` | 图标/文字互换、骨架揭示 |
| `--ease-out` | `ease-out` | tooltip |
| `--ease-linear` | `linear` | shimmer、骨架脉冲、spinner |
| `--ease-bounce` | `cubic-bezier(0.34, 1.36, 0.64, 1)` | 徽标弹出 |
| `--ease-bounce-strong` | `cubic-bezier(0.34, 3.85, 0.64, 1)` | hover 离开回弹（慎用） |

**Distances**：`--distance-micro 4px`（文字换态）/ `small 6px`（错误抖动小幅）/ `base 8px`（页面滑动、抖动大幅）/ `medium 12px`（文本揭示）/ `large 30px`（成功徽标）。
**Scales**：`0.96`（modal）/ `0.97`（dropdown 打开）/ `0.98`（tooltip）/ `0.99`（dropdown 关闭）。
**Blur**：`2px`（面板/图标/文字互换）/ `3px`（页面滑动、文本揭示）/ `8px`（成功勾）。

**具名过渡的行为规则（节选）**
- Modal：打开 250ms + scale `0.97→1` + blur 4px 消散；**关闭更快更小**（150ms + `→0.99`）——"慢进快出"。
- Tooltip：延迟 `80ms`、进 150ms（fade + scale 0.98）、**出 50ms**，可在多个触发点间移动。
- 错误抖动（Error state shake）：分段 cubic-bezier 抖动（80ms/60ms 两段、位移 6px、overshoot 4px）+ 边框红变自动复原（`--p12-revert-dur 280ms`、**红框保持 3000ms**）。
- Toast：进 350ms（升起 + 交叉模糊）、出 250ms，"进慢出快"。
- Tabs sliding：250ms 平滑滑动 pill（首帧写位移时必须 `transition: none`）。
- Checkbox check：底 150ms 填充 → 勾线 350ms 描画（stroke-dasharray）。
- Toggle：350ms 滑块带一次轻微过冲（`cubic-bezier(0.34,1.35,0.64,1)`）。
- Accordion：250ms 开 / 250ms 合（grid-rows `0fr↔1fr`）。
- Shimmer / 骨架：2000ms linear 循环，仅限加载态。
- **每条片段都带 `@media (prefers-reduced-motion: reduce)` 降级**，删除会被无障碍审计判失败。
- 决策规则：两个过渡都匹配时选**开销更小**的（卡片改尺寸 > 面板滑入；下拉 > 模态；小成功勾 > 全屏庆祝）。

### 4.4 对「长时间阅读 + 精细校对」最值得借鉴
1. **"开 250ms / 关 150ms"的非对称节奏**：所有浮层（问题卡、工具栏、命令面板）统一，长期使用形成肌肉记忆且退出不拖沓。
2. **7 档时长 + 6 档缓动的具名令牌体系**：这是本项目动效层应照搬的骨架（§5）。
3. **错误抖动 + 自动复原（红框 3s 后退）**：适合"校对建议被驳回/输入非法"的表单反馈，避免永久红框污染阅读面。
4. **Tooltip 80ms 延迟 / 150ms 进 / 50ms 出**：校对工具里大量图标按钮 + 悬浮解释，这套参数不打断阅读视线。
5. **强制 `prefers-reduced-motion` 降级 + "匹配用途而非数值"的令牌映射原则**（300ms 的 modal close 也应归 `--duration-quick`）——写进本项目的 CSS 规范。

---

## 5. ui.shadcn.com（设计令牌 / 组件规范 / Typeset / Figma）

**实访问 URL**：<https://ui.shadcn.com/docs/components/base/button>（= `/docs/components/button` 重定向目标）、<https://ui.shadcn.com/docs/theming>、<https://ui.shadcn.com/docs/typeset>、<https://ui.shadcn.com/docs/figma>、`/docs/components/base/{card,badge,switch,input,tabs,dialog}`；源码佐证：[shadcn-ui/ui `packages/shadcn/src/tailwind.css`](https://github.com/shadcn-ui/ui/blob/main/packages/shadcn/src/tailwind.css)、[`apps/v4/registry/styles/style-nova.css`](https://github.com/shadcn-ui/ui/blob/main/apps/v4/registry/styles/style-nova.css)、[`apps/v4/app/(app)/(typeset)/typeset.css`](https://github.com/shadcn-ui/ui/blob/main/apps/v4/app/(app)/(typeset)/typeset.css)、[`registry/bases/base/ui/*.tsx`](https://github.com/shadcn-ui/ui/tree/main/apps/v4/registry/bases/base/ui/ui)。
**说明**：`/docs/tokens`、`/tokens` 404（shadcn 无独立 tokens 文档）；`/docs/figma` 仅列社区 Figma Kit（Sitsiilia Bergmann、shadcn/studio、Obra、ShadcnSpace、ShadcnStore 等）与 **Figma MCP / agent skills** 的 design-to-code 方案，不含官方 token 表——token 权威来源是 Theming 页的 CSS 变量。

### 5.1 站点定位
最流行的开源组件体系（React + Tailwind + Base UI/Radix），copy-paste 源码模式；文档即规范，给需要"一致、可访问、可改源码"的产品团队。对本项目的价值在于**语义令牌命名与组件级规格**（即便我们用原生 CSS，也照抄它的变量名与尺寸）。

### 5.2 核心视觉语言（Theming 令牌，实抓）
- **语义色变量（17 个）**：`--background / --foreground / --card / --card-foreground / --popover / --popover-foreground / --primary / --primary-foreground / --secondary / --secondary-foreground / --muted / --muted-foreground / --accent / --accent-foreground / --destructive / --border / --input / --ring`（+ `--chart-1..5`、`--sidebar-*`）。
- **默认主题（base-nova）**：全中性、**无彩色主色**——浅色 `--background oklch(1 0 0)`、`--foreground oklch(0.145 0 0)`、`--card oklch(1 0 0)`、`--primary oklch(0.205 0 0)`（近黑）、`--secondary/--muted/--accent oklch(0.97 0 0)`、`--muted-foreground oklch(0.556 0 0)`、`--border/--input oklch(0.922 0 0)`、`--ring oklch(0.708 0 0)`、`--destructive oklch(0.577 0.245 27.325)`。深色整套反转（`--background oklch(0.145)`, `--card oklch(0.205)`，边框改 `oklch(1 0 0 / 10%)`、input `15%`）。
- **圆角**：`--radius: 0.625rem`（10px）为基准，派生 `--radius-sm = ×0.6 (6px) / md = ×0.8 (8px) / lg = ×1 (10px) / xl = ×1.4 (14px) / 2xl ×1.8 / 3xl ×2.2 / 4xl ×2.6`。
- **排版（Typeset 令牌）**：`--typeset-font-body / -heading / -mono`、`--typeset-size`、`--typeset-leading`、`--typeset-flow`（块间距）四变量定义整套长文排版；预设：
  - `typeset`（默认）：1em / leading **1.75** / flow **1.25em**；
  - `typeset-docs`：**15px** / 1.75 / 1.5em；
  - `typeset-reading`（阅读档，**与本项目最相关**）：衬线（Lora）/ **18px** / leading **1.9** / flow **2em**；
  - `typeset-compact`：无衬线 14px / 1.6 / 1em；
  - 深色模式行高加宽到 1.9（`.dark .typeset { --typeset-leading: 1.9 }`）。
  - 标题阶梯（typeset.css）：h1 1.75em/1.3、h2 1.25em/1.4、h3 1.125em/1.45、h4 1em/1.5、h5 0.875em/1.5（500，弱色）、h6 0.8125em/1.5（500，字距 .08em 大写，弱色）；标题一律 `font-weight: 600`。
  - 细节：链接下划线用 `color-mix(currentColor 30%)`，hover 才满色；`mark` 高亮是 40% 黄混透明 + `padding .05em .15em` + `radius .2em`；`kbd` 1px 边 + 2px 底边 + 小圆角；表格/代码等宽。

### 5.3 组件模式（实抓源码 `style-nova.css` / `bases/base/ui`）
- **按钮层级**（`cn-button-variant-*`）：
  - `default`：`bg-primary text-primary-foreground`，hover `bg-primary/80`；
  - `secondary`：`bg-secondary`，hover `color-mix(in oklch, secondary, foreground 5%)`（**只混 5% 前景色**的极轻加深）；
  - `outline`：`border-border bg-background`，hover `bg-muted`；深色 `bg-input/30`；
  - `ghost`：hover `bg-muted`，无边框；
  - `destructive`：**tinted**（`bg-destructive/10 text-destructive`，hover `/20`，focus ring `destructive/20`）——危险按钮不是实底红，非常克制；
  - `link`：`text-primary underline-offset-4 hover:underline`。
- **按钮尺寸**：`xs h-6 px-2 text-xs` / `sm h-7 px-2.5 text-[0.8rem]` / `default h-8 px-2.5` / `lg h-9 px-2.5`；圆形图标钮 `size-6/7/8/9`；圆角 `rounded-[min(var(--radius-md), 10px~12px)]`；图标间距靠 `data-icon="inline-start|end"` 切换 padding（1.5/2）。
- **卡片**：`rounded-xl`、`ring-1 ring-foreground/10`（**用 ring 不用 border**）、`gap-4 py-4`、内容区 `px-(--card-spacing)`（默认 `--spacing(4)=16px`，`data-size=sm` 改 12px）；标题 `text-base font-medium`，描述 `text-sm text-muted-foreground`；**footer 是 `bg-muted/50 border-t`**（弱底 + 上边线）。
- **对话框**：`rounded-xl p-4 gap-4 ring-1 ring-foreground/10 bg-popover`，`max-w-[calc(100%-2rem)] sm:max-w-sm`；进出场 `fade-in-0 + zoom-in-95`（出 `zoom-out-95`）**duration-100**；遮罩 `bg-black/10` + `backdrop-blur-xs`（很淡！）；footer 同卡片弱底样式；关闭钮 `absolute top-2 right-2`。
- **表单**：输入 `cn-input w-full`，占位 `text-muted-foreground`，禁用 `opacity-50`；聚焦态统一 **`ring-3 ring-ring/50 + border-ring`**（3px 焦点环）；非法态 `border-destructive + ring-destructive/20 ring-3`；field 组间距 `gap-5`，标签 `text-sm font-medium`，说明 `text-sm text-muted-foreground`，错误文本 `text-destructive text-sm`。
- **标签页**：列表 `rounded-lg p-[3px] h-8`（横向），触发 `rounded-md px-1.5 py-0.5 text-sm font-medium`，active 仅 `shadow-sm`（默认形态）或下划线（`variant=line`）；内容 `text-sm`。
- **徽标**：`default`（实底 primary）/ `secondary` / `outline`（border + text-foreground）/ `destructive`（tinted `bg-destructive/10 text-destructive`）/ `ghost` / `link`。
- **开关**：track 用 `data-selected:bg-primary`，滑块 `size-4 rounded-full`，位移 `translate-x-[calc(100%-2px)]`，**仅 transition-transform**；热区外扩 `after:-inset-x-3 after:-inset-y-2`。
- **表格**：表头 `h-10 px-2 font-medium`，单元 `p-2`，行 `hover:bg-muted/50`、选中 `bg-muted`、`border-b transition-colors`。
- **浮层动效统一**：`fade-in-0 + zoom-in-95 + 方向 slide-in-from-*-2`，**duration-100**（100ms!）。

### 5.4 动效与过渡规范
- 组件级过渡极短：`duration-100`（浮层进出）、`transition-colors`/`transition-transform`（状态切换）、sheet `duration-200 ease-in-out`、OTP 光标 `duration-1000` 闪烁。
- Accordion 用高度 keyframes（`accordion-down/up`）。
- 无回弹、无模糊位移；强调"可访问性 > 表现力"（cursor、RTL、`data-*` 状态机、focus ring）。

### 5.5 对「长时间阅读 + 精细校对」最值得借鉴
1. **Typeset 的 `--typeset-size / --typeset-leading / --typeset-flow` 三变量排版体系 + `typeset-reading` 预设**（衬线 18px / 1.9 / 2em）——书稿正文直接采用，并做中文参数化（字号/行距/段距可调）。
2. **卡片 = ring-1 描边环 + 无投影**；对话框遮罩仅 `black/10 + blur-xs`——工具型界面的"安静感"来源。
3. **destructive 用 tinted（10% 底 + 同色文字）而非实底红**：校对工具里的"删除/驳回/放弃"类按钮同样低刺激。
4. **统一 3px 焦点环（`ring-3 ring-ring/50`）+ 全组件 `data-*` 状态机**：键盘逐条校对时焦点必须始终可见。
5. **`--radius: 10px` 单基准派生全套圆角**的令牌结构：一套变量控制全站气质（配合 §4 圆角阶梯）。

---

## 6. 综合建议：「纸页工坊」设计令牌（可直接落地）

> 命名约定：颜色用 `--color-*`，排版 `--font-* / --text-*`，几何 `--radius-* / --shadow-* / --line-*`，动效 `--duration-* / --ease-* / --distance-*`。全部写入一个 `:root{}` + `[data-theme="dark"]` 块（原生 CSS 即可，无需构建）。

### 6.1 配色（低饱和 · 豆青/青碧 + 黛蓝 + 米白宣纸；浅色为主）

**浅色（默认，日间阅读）**
| Token | HEX | 用途 |
| --- | --- | --- |
| `--color-bg` | `#EAE6DC` | 工作台背景（米灰，比纸面深一档，形成"书案"） |
| `--color-paper` | `#F7F3EA` | **书稿纸面**（米白宣纸，暖调不刺眼） |
| `--color-surface` | `#FCFAF4` | 面板/卡片/工具栏 |
| `--color-ink` | `#2E2B25` | 正文（松烟墨，非纯黑；对纸面对比 ≈ 12:1） |
| `--color-ink-2` | `#6D675B` | 次级文字/说明（≈ 5:1） |
| `--color-ink-3` | `#948E7F` | 占位/禁用/极弱信息（仅装饰） |
| `--color-primary` | `#3D6A5A` | **主色·青碧**（行动按钮、当前项、进度） |
| `--color-primary-hover` | `#335A4C` | 主色 hover/active |
| `--color-primary-tint` | `#E3EBE4` | 选中行、当前章节底、focus 底 |
| `--color-accent` | `#45607A` | **辅色·黛蓝**（链接、引用、版面坐标、信息类徽标） |
| `--color-accent-tint` | `#E4E8EE` | 辅色浅底 |
| `--color-success` | `#4F7A46` | 已校/通过（苔绿） |
| `--color-warning` | `#A07A2E` | 疑似/待定（秋香） |
| `--color-danger` | `#A34A3A` | 错误/删除/驳回（赭朱，非纯红） |
| `--color-mark` | `#EFE3A8` | 校对定位荧光笔高亮（半透明可用 `#EFE3A8CC`） |
| `--color-line` | `#DCD6C8` | 常规边框（纸边色） |
| `--color-line-soft` | `#E7E2D5` | 分隔线/表格线 |
| `--color-line-strong` | `#C5BEAE` | 输入框边、强调分隔 |
| 语义浅底 | success `#E7EEDF` / warning `#F1E8D3` / danger `#F2E1DC` | 徽标/条带 tinted 底（对应 `-tint`） |

**深色（夜读）**
| Token | HEX | | Token | HEX |
| --- | --- | --- | --- | --- |
| `--color-bg` | `#1D211F` | | `--color-primary` | `#7FA894` |
| `--color-paper` | `#242825` | | `--color-primary-hover` | `#93B9A6` |
| `--color-surface` | `#2A2E2B` | | `--color-primary-tint` | `#313B36` |
| `--color-ink` | `#D9D4C8` | | `--color-accent` | `#8CA6BE` |
| `--color-ink-2` | `#A39D8F` | | `--color-accent-tint` | `#2C3540` |
| `--color-ink-3` | `#7B766A` | | `--color-success` | `#85A878` |
| `--color-line` | `#383E3A` | | `--color-warning` | `#C39A57` |
| `--color-line-soft` | `#2F3431` | | `--color-danger` | `#C67C6B` |
| `--color-line-strong` | `#4B524C` | | `--color-mark` | `#5A5230` |

配色原则（三站共识）：**中性色 3 级墨阶 + 3 级线阶 + 单主色 + 1 辅色 + 3 语义色**；语义色一律"tinted 底 + 深色文字"而非实底；主色饱和度控制在 oklch chroma 0.05–0.08（青碧 `#3D6A5A` 约合 oklch 62% 0.06 165）。

### 6.2 字体与中文排版
```css
--font-serif: "Source Han Serif SC", "Noto Serif CJK SC", "Songti SC", "SimSun", serif;      /* 书稿正文 */
--font-sans:  "Source Han Sans SC", "Noto Sans CJK SC", "PingFang SC", "Microsoft YaHei", sans-serif; /* 工具 UI */
--font-mono:  "JetBrains Mono", "Sarasa Mono SC", ui-monospace, monospace;                    /* 页码/偏移/差异 */
```
- **正文（书稿）**：衬线 400，`font-size 17px`（移动）/ `18px`（≥768px），`line-height 1.85`，`letter-spacing 0.01em`；段距 `1.25em`（中文有缩进，不必 2em）；每行 **30–38 汉字**（measure 约 32em ≈ 580–640px，`max-width: 36em`）；对齐 `text-align: justify; text-justify: inter-ideograph`；标点 `text-spacing-trim: trim-end`（支持的浏览器）。
- **UI 文字**：无衬线 `13px/1.6`（控件）、`15px/1.7`（面板正文）、`12px/1.5`（辅助/徽标，字距 `0.02em`）。
- **标题阶梯**（参照 shadcn typeset，中文调低比例）：章 `1.6em / 1.35 / 600`，节 `1.25em / 1.4 / 600`，小节 `1.1em / 1.5 / 600`；标题字距 `0`（中文标题勿加负字距）。
- **字重**：正文 400；强调/人名书名 500–600（中文慎用 700，避免糊）；UI 标签 500。
- **数字/坐标**：`font-variant-numeric: tabular-nums` + `--font-mono`（页码 `25`、字符偏移、置信度）。
- 深色模式行高放宽到 1.9–1.95（照抄 shadcn `.dark .typeset{--typeset-leading:1.9}`）。
- 阅读设置暴露三档：`--typeset-size / --typeset-leading / --typeset-flow` 可调（小 16px/1.8、标准 18px/1.85、大 20px/1.9）。

### 6.3 圆角 / 阴影 / 边框统一规范
```css
--radius-chip: 6px;      /* 徽标、标签、kbd */
--radius-control: 8px;   /* 按钮、输入、开关容器 */
--radius-card: 10px;     /* 卡片、问题卡 */
--radius-panel: 12px;    /* 侧栏面板、弹出菜单 */
--radius-window: 14px;   /* 对话框、大浮层 */

--line: 1px;             /* 全站唯一边框宽度 */
--border: 1px solid var(--color-line);
--border-strong: 1px solid var(--color-line-strong);
--ring-hairline: 0 0 0 1px var(--color-line);   /* beautifului 发丝环，替代投影做分层 */

--shadow-xs: 0 1px 2px rgba(46,43,37,.06);
--shadow-sm: 0 1px 2px rgba(46,43,37,.06), 0 3px 8px rgba(46,43,37,.05);
--shadow-md: 0 0 0 1px rgba(46,43,37,.06), 0 2px 6px rgba(46,43,37,.05), 0 10px 28px rgba(46,43,37,.08);   /* 浮层（transitions.dev menu-shadow 结构） */
--shadow-lg: 0 0 0 1px rgba(46,43,37,.07), 0 8px 28px rgba(46,43,37,.16);                                   /* 对话框 */
```
- **书稿纸面（`.paper`）**：`background: var(--color-paper)` + `1px solid var(--color-line)` + **零投影**（beautifului / shadcn 的共识）。
- 卡片层级靠**底色差 + 1px 线**表达，投影只允许出现在**浮层**（菜单/对话框/toast）。
- 焦点环全站统一：`outline: none; box-shadow: 0 0 0 3px color-mix(in srgb, var(--color-primary) 30%, transparent)`（= shadcn `ring-3 ring-ring/50`）。
- 间距节奏：4px 基准（`--spacing: .25rem`），常用 4/8/12/16/24/32/48；卡片内边距 16px（sm 12px，照 shadcn `--card-spacing`）；控件高度 24/28/32/36px（shadcn h-6/7/8/9）。

### 6.4 动效规范（transitions.dev 令牌 × beui 语义化）
```css
--duration-micro: 80ms;    --duration-quick: 150ms;  --duration-fast: 250ms;
--duration-medium: 350ms;  --duration-slow: 400ms;   --duration-stagger: 40ms;

--ease-standard: cubic-bezier(0.22, 1, 0.36, 1);     /* 主力：开合、位移、尺寸 */
--ease-emphasized: cubic-bezier(0.16, 1, 0.3, 1);    /* 进入强调（beui EASE_OUT） */
--ease-in-out: ease-in-out;                          /* 内容互换、淡入淡出 */
--ease-linear: linear;                               /* 仅 shimmer/spinner */
--ease-pop: cubic-bezier(0.34, 1.36, 0.64, 1);       /* 仅徽标/计数弹出，禁止用于面板 */
```
落地规则：
| 场景 | 进 | 出 | 缓动 | 备注 |
| --- | --- | --- | --- | --- |
| 菜单/下拉/气泡 | 250ms，`scale .97→1` + `opacity` | 150ms，`→.99` | `--ease-standard` | "慢进快出" |
| 对话框 | 250ms，`scale .96→1` | 150ms | `--ease-standard` | 遮罩 `bg rgba(30,33,31,.18)`，可选 2px blur |
| 侧栏/检查面板 | 350ms 滑入 | 250ms 滑出 | `--ease-standard` | 位移 8px，不整屏重排 |
| Tooltip | 延迟 80ms + 150ms | 50ms | `--ease-out`(ease-out) | 不遮挡正在校对的行 |
| 标签页指示条 | 250ms `transform/width` | — | `--ease-standard` | 首帧 `transition:none` 写位移 |
| 开关滑块 | 250–350ms | 同 | `--ease-standard`（可轻微过冲 `cubic-bezier(.34,1.35,.64,1)`） | 只动 transform |
| 复选/勾选 | 勾线 350ms 描画 | 150ms | `--ease-standard` | stroke-dasharray |
| 校对定位跳转 | 滚动 300ms + 高亮闪 1200ms 淡出 | — | `--ease-emphasized` | 高亮用 `--color-mark` |
| 表单错误 | 抖动 2 段（80ms/60ms，位移 6px） | 边框红保持 3000ms 后 280ms 复原 | `--ease-standard` | 参考 transitions.dev error shake |
| 列表/问题卡入场 | 逐项 40ms 错峰，上限 8 项 | 150ms | `--ease-emphasized` | 超过 8 项直接整批淡入 |
| 加载 shimmer | 2000ms linear 循环 | — | `--ease-linear` | 仅加载态 |

强制条款：
1. 所有动画走 `transform / opacity`（必要时 `filter: blur`），禁止动画 `width/height/top/left`（accordion 用 `grid-template-rows: 0fr↔1fr`）。
2. 全局提供 `@media (prefers-reduced-motion: reduce) { * { animation-duration: .01ms !important; transition-duration: .01ms !important; } }` 与应用内"减少动效"开关。
3. 每个动效声明必须引用 `--duration-* / --ease-*`，**禁止魔法数字**（照 transitions.dev `transitions refine` 的"按用途匹配令牌"原则）。
4. 书稿正文区（`.paper` 内）**禁止任何动画**（含文字渐入、模糊、字符扰动）。

### 6.5 组件模式建议（映射到本工具）
- **按钮**：`primary`（实底 `--color-primary`，hover `/85`）｜`secondary`（`--color-surface` + 边框，hover `--color-primary-tint`）｜`ghost`（hover `--color-primary-tint`）｜`destructive`（**tinted**：`--color-danger` 10% 底 + 同色字，仅确认对话框里用实底）；高度 28/32/36px，圆角 `--radius-control`，图标钮 28/32px 方。
- **问题卡（校对核心）**：仿 beautifului Diff Table + Approval Card——`--color-surface` 底、`--radius-card`、1px 边、标题行（页码 mono + 类型徽标 + 状态点）、"原文划除 / 建议插入"双行对照、底部 `跳过（ghost）/ 采纳（primary）`，卡片间距 12px，错峰入场。
- **徽标**：`--radius-chip`；状态三色 tinted（疑似 `warning`、待定 `accent`、已改 `success`、错误 `danger`），字 12px/500/`0.02em`。
- **标签页**（阅读/校对/大纲）：shadcn `variant=line` 下划线式（列表高 32px、`padding 3px`、active 指示条 2px `--color-primary`，250ms 滑动）。
- **开关**：track 32×18px、thumb 14px、位移 `calc(100% - 2px)`、仅 `transition: transform 250ms var(--ease-standard)`。
- **对话框**：`--radius-window`、`--shadow-lg`、遮罩 `rgba(30,33,31,.18)`、`fade + scale .96→1` 250ms/150ms；确认类放 destructive-tinted 按钮，Esc/点击遮罩可关闭（破坏性操作除外）。
- **表单**：输入高 32px、`--radius-control`、1px `--color-line-strong`、focus 3px 主色环；错误文案 `text-danger 13px` + 抖动反馈。
- **长表（问题清单）**：借 beautifului Records Table——行号列 + 首列 sticky + 12px 密度 + `hover: --color-primary-tint` + 行选中底色。

### 6.6 明确**不适合**本工具的流行做法
1. **玻璃拟态滥用**（beui 的 glass surface、bottom sheet/command palette 的 backdrop-blur）：中文笔画密集，模糊底上的正文边缘发虚、长时间阅读疲劳；仅允许对话框遮罩 ≤2px blur，正文/纸面**零模糊**。
2. **3D tilt + 光斑**（rareui/beui Tilt Card、Fluid Orb、Gravity Letters、Folder 3D）：装饰性动效在逐字校对时是纯干扰；倾斜还会让笔画几何变形。
3. **文字扰动类动画**（beui Text Animation 的 character scramble、chromatic sweep、letter-cascade；transitions.dev 的 streaming text 逐词模糊入场）：校对要求文字**绝对稳定**，任何对正文的持续动画都不可接受（AI 流式建议文本可例外，但只在建议区）。
4. **回弹/过冲动效**（`--ease-bounce-strong cubic-bezier(.34,3.85,.64,1)`、重力字母、bouncy accordion）：只允许用于 ≤16px 的徽标弹出（`--ease-pop`），面板/按钮一律无回弹。
5. **长时长 + 循环动画**（>500ms 的入场、2s shimmer 用于静态内容、Dynamic Island 式常驻形变）：加载态 shimmer 保留，其余一律 ≤400ms 且一次性。
6. **高饱和多主题预设**（beui 20 套 oklch 55–80% / 0.2 彩度）：与"护眼、雅致"冲突；主题只做**浅/深 + 纸面色温微调**（宣纸/竹青/夜读三档底色），主色保持青碧低饱和。
7. **纯黑/纯白高对比**（rareui `#000/#fff`、transitions 深色 `#0d0d0d` 文字）：改用暖调墨色 `#2E2B25` 与米白 `#F7F3EA`，深色模式用 `#1D211F/#D9D4C8`。
8. **全页 View Transition 换肤特效**（beui Theme Toggle 的百叶窗/圆形 clip-path）：主题切换用 200ms 颜色过渡即可，避免阅读中整屏重绘闪烁。
9. **超大圆角药丸化**（`rounded-3xl 1.5rem`、全 pill 按钮）：与书卷/版刻气质不符，圆角上限 14px，按钮不默认 pill。
10. **重投影浮层**（大扩散彩色阴影、glow）：只用 §6.3 的四级阴影，浮层靠"1px 环 + 28px 羽化"分层。

---

## 7. 来源（实访问页面）

- beautifului.dev：<https://www.beautifului.dev/>（样式表 `https://www.beautifului.dev/_next/static/css/916c3f25c8795666.css`）
- beui.dev：<https://beui.dev/>、<https://beui.dev/components/motion/button>、<https://beui.dev/components/motion/tabs>、<https://beui.dev/components/motion/switch>
- rareui.com：<https://www.rareui.com/>（样式表 `https://www.rareui.com/_next/static/chunks/0pkf8xppblx45.css`）
- transitions.dev：<https://transitions.dev/>（首页 JS 站，仅得标题；等价资料）[SKILL.md motion tokens](https://github.com/Jakubantalik/transitions.dev/blob/main/skills/transitions-dev/SKILL.md)、[仓库](https://github.com/Jakubantalik/transitions.dev)、[index.html token 用法](https://github.com/Jakubantalik/transitions.dev/blob/main/index.html)
- shadcn/ui：<https://ui.shadcn.com/docs/components/base/button>、<https://ui.shadcn.com/docs/theming>、<https://ui.shadcn.com/docs/typeset>、<https://ui.shadcn.com/docs/figma>、<https://ui.shadcn.com/docs/components/base/card>、`.../badge`、`.../switch`、`.../input`、`.../tabs`、`.../dialog`；源码 [tailwind.css](https://github.com/shadcn-ui/ui/blob/main/packages/shadcn/src/tailwind.css)、[style-nova.css](https://github.com/shadcn-ui/ui/blob/main/apps/v4/registry/styles/style-nova.css)、[typeset.css](https://github.com/shadcn-ui/ui/blob/main/apps/v4/app/(app)/(typeset)/typeset.css)、[bases/base/ui](https://github.com/shadcn-ui/ui/tree/main/apps/v4/registry/bases/base/ui/ui)。（`/docs/tokens`、`/tokens` 404，无独立 token 文档页）
