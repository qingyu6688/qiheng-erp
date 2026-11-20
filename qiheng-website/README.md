# 柒衡 ERP 官网

柒衡 ERP 的产品官网，用 Vue 3 + TypeScript + Vite 开发，vite-ssg 在构建时把每个页面预渲染成静态 HTML。当前官网为 `https://qiheng.site/`，只读演示为 `https://qiheng.site/experience/`；也支持另行部署到 GitHub Pages。

页面：中文首页 `/`、英文首页 `/en/`、AI 能力 `/features/ai/`、快速开始 `/quick-start/`、在线演示跳转 `/demo/`、404。

## 开发

在本文件夹（`qiheng-website/`）执行，需要 Node.js 20.19 以上。

```bash
npm install
```

```bash
npm run dev
```

打开 `http://localhost:5173/qiheng-erp/`。

## 检查与构建

提交前依次执行：

```bash
npm run lint
```

```bash
npm test
```

```bash
npm run build
```

`build` 先做 TypeScript 类型检查，再预渲染全部页面到 `dist/`，并生成 `404.html` 和 `sitemap.xml`。本地查看构建结果：

```bash
npm run preview
```

## 在线演示地址

演示 ERP 的地址是构建时变量，复制 `.env.example` 为 `.env.local` 填写；GitHub Pages 构建时在仓库 Settings → Variables 中配置同名变量。

- `VITE_DEMO_URL` 留空：全站“在线演示”显示“筹备中”，`/demo/` 页给出本机运行等替代入口。
- 填写后：`/demo/` 显示地址和企业编码，3 秒后跳转并附带 `?from=website`；系统开启“减少动态效果”时不自动跳转。
- 地址必须是 https，否则构建直接失败。页面上不展示任何演示账号密码。

## 目录

```text
src/
  pages/        页面：HomePage（中英文共用）、AiPage、QuickStartPage、DemoPage、NotFoundPage
  components/   导航（nav/）、首页区块（home/）、AI 页示意（ai/）和公共组件
  content/      文案：chrome.ts（导航、页脚）、home.zh.ts、home.en.ts
  hooks/        滚动入场、滚动高亮、页面元信息、语言
  router/       路由和锚点滚动
  config/       站点地址、版本号、演示跳转配置
  styles/       样式与设计令牌
  types/、utils/
public/         吉祥物、分享图、界面截图、robots.txt
tests/unit/     单元测试
design/mockups/ ERP 后台界面稿，public/images/shots/ 的截图来源，不参与构建
docs/           官网方案、开发环境、更新日志
```

## 发布

ERP 仓库的 `.github/workflows/site.yaml` 负责发布：

- 推送到 `main` 或提交拉取请求且改动涉及官网时，依次执行 `npm ci`、lint、测试、构建，并保留静态产物；默认演示入口指向已上线的 `https://qiheng.site/experience/`。
- 自建服务器发布按仓库的 `docs/运维/只读演示部署说明.md` 操作，官网与 `experience/` 一起打包并保留旧目录。
- GitHub Pages 为可选发布：先在 Settings → Pages 将 Source 设为 **GitHub Actions**，再将仓库变量 `ENABLE_GITHUB_PAGES` 设为 `true`。默认不开启，避免未配置 Pages 时发布失败。

## 常见改动

- **改文案**：中文首页改 `src/content/home.zh.ts`，英文首页改 `home.en.ts`，导航和页脚改 `chrome.ts`。两种语言共用一个接口，漏改字段时类型检查会报错；条目数不一致时单元测试会失败。
- **发新版本**：改 `src/config/site.ts` 的 `VERSION`，再核对统计条数字和截图。
- **加站内链接**：地址以 `/` 开头，锚点写成 `/#features`、`/en#ai`；`npm test` 会检查每个站内地址都有对应路由和锚点。
- **换截图**：按 `docs/官网方案.md` 第 6.3 节的规格生成 1 倍和 2 倍 WebP，放进 `public/images/shots/`。

## 说明

- 吉祥物 `public/mascot.svg` 用于导航、页脚和浏览器标签页；“7”标志 `src/assets/logo.svg` 作为统计条和大菜单推荐卡的水印。
- 动效只用 CSS 和少量原生 JS，没有动画库；离开视口时暂停循环动画，系统开启“减少动态效果”时全部关闭。
- 字体使用系统中文字体栈，没有引用 Google Fonts（国内访问不稳定）。
- AI 能力的文案已按 ERP 仓库 `erp-ai` 代码核对，对照表见 `docs/官网方案.md` 第 4.1 节；示例对话里的客户、金额、单号均为虚构。
- `docs/官网方案.md` 是官网方案的唯一版本，改动官网时同步更新。
