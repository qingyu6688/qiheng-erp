# 开发环境

记录于 2026-11-18，Asia/Shanghai。

## 本机环境

| 项 | 版本 |
|---|---|
| 操作系统 | Windows 11 专业版 10.0.26200 |
| Node.js | 24.9.0（`package.json` 要求 ≥ 20.19） |
| npm | 11.6.0 |
| Git | 2.47.1.windows.1 |
| Python | 3.12.3（只用于截图转 WebP，构建不需要） |
| 浏览器 | Microsoft Edge（无头模式截图） |

官网是纯前端静态站，不需要 Java、MySQL 和 Redis。

## 主要依赖（安装时解析到的版本）

| 依赖 | 版本 |
|---|---|
| vue | 3.5.45 |
| vue-router | 4.6.5 |
| vite | 7.3.8 |
| vite-ssg | 28.3.0 |
| @unhead/vue | 2.1.17 |
| typescript | 5.9.3 |
| vue-tsc | 3.3.14 |
| vitest | 3.2.7 |
| eslint | 9.39.4 |

## 端口

| 用途 | 地址 |
|---|---|
| 开发服务器 `npm run dev` | `http://localhost:5173/qiheng-erp/` |
| 构建后预览 `npm run preview` | `http://localhost:4173/qiheng-erp/` |

## 环境变量

复制 `.env.example` 为 `.env.local`（不提交）。都是构建时变量，会写进页面，不能放任何密钥。

| 变量 | 说明 |
|---|---|
| `VITE_DEMO_URL` | 演示 ERP 地址，必须是 https；留空时全站“在线演示”显示“筹备中” |
| `VITE_DEMO_TENANT` | 演示企业编码，显示在跳转页（可选） |
| `VITE_DEMO_NOTE` | 跳转页说明文字（可选） |
| `SITE_BASE` | 部署子路径，默认 `/qiheng-erp/`；绑定自有域名时设为 `/` |
