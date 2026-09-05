<div align="center">

<img src="frontend/public/logo.png" alt="面霸" width="88" />

# 面霸

**AI 学习 / 面试备考平台 —— 把「教学决策权」从 LLM 手里拿回来**

> 学什么、多难、何时复习、能否进下一题 —— 这四个决策一律不进 Prompt。
> LLM 只是教具：出题、评分、讲解、追问。

![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.7-6DB33F?logo=spring&logoColor=white)
![Java](https://img.shields.io/badge/Java-21-orange?logo=openjdk)
![React](https://img.shields.io/badge/React-18-61DAFB?logo=react)
![TypeScript](https://img.shields.io/badge/TypeScript-5.6-3178C6?logo=typescript)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-4169E1?logo=postgresql)
![License](https://img.shields.io/badge/License-MIT-green)

---

[这是什么](#-这是什么) · [核心主张](#-核心主张) · [特性](#-特性) · [痛点解决进度](#-痛点解决进度) · [快速开始](#-快速开始) · [桌面应用](#-桌面应用) · [云端部署](#-云端部署) · [架构设计](#-架构设计) · [目录结构](#-目录结构) · [路线图](#-路线图)

</div>

---

## 💡 这是什么

「面霸」是一个**学习型面试 Agent**：它不满足于"给你一堆题让你刷"，而是像一位老师一样 —— 知道你学到了第几层、该复习什么、没搞懂就不许往前走。

它同时面向**学习**和**面试**两个场景：

| 模式 | 场景 | 特征 |
|---|---|---|
| **LEARN · 学习模式** | 日常学习 / 备考 | 先教后考：知识点拆解 → 逐子点讲解 → 对话式作答 → 理解型判分 |
| **REHEARSAL · 模拟面试** | 面试前冲刺 | 闭卷、强制倒计时、提交后不可编辑、追问封顶 2 轮 |
| **INTERVIEW · 模拟面试** | 完整面试演练 | 选面试方式 / 依据（简历 + 学习方向）/ 难度 / 题数，逐题问答并逐题回顾 |

当前图谱种子方向：Java 后端（JVM / 并发 / Spring / 数据库 / 分布式 / 网络），知识图谱按 `layer 1→5` 由浅入深（概念 → 机制 → 实现 → 权衡 → 故障），可通过「学习方向」对话动态扩展。

## 🌐 官方网站

> 独立的静态落地页（`official-site/`），与 Web 应用分离，根路径 `https://<你的域名>/`，Web 应用位于 `/app/`。版本与下载链接 **实时读取 GitHub Releases** —— 发布新 release 后官网自动更新，无需改代码。

| 入口 | 地址 | 说明 |
|---|---|---|
| 🏠 官网 | `https://<你的域名>/` | 产品介绍、特性、截图画廊、下载、Star |
| 💻 Web 应用 | `https://<你的域名>/app/` | 注册登录后的完整产品 |
| 📦 桌面安装 | [GitHub Releases](https://github.com/LuckPony/interview/releases) | Windows / macOS 安装包 |
| ⭐ 开源仓库 | [GitHub](https://github.com/LuckPony/interview) | 源码 + 文档，期待你的 Star |

官网采用纯静态 HTML/CSS/JS（零构建依赖），部署在 nginx 根路径；本地预览：`python3 -m http.server 8899 --directory official-site`。

## 🎯 核心主张

现有 AI 学习工具的一切毛病，根源是**把「当老师」这件事交给了 LLM**。LLM 没有你的长期状态、不知道你的知识边界，也没有动机拦住你说"这题你还没懂，不许走"，它只有"生成下一段合理文本"的本能。

本项目的架构主张：

> **教学决策权归服务端的确定性算法，LLM 降级为教具。**

| 决策 | 归属 | 实现 |
|---|---|---|
| 学什么 | 服务端 | 知识图谱分层选点 + 选题闸门 |
| 多难 | 服务端 | layer 难度刻度（1–5） |
| 何时复习 | 服务端 | FSRS 间隔排程 |
| 能否进下一题 | 服务端 | 掌握闸门（一题一闭环） |
| 题目文本 / 评分 / 讲解 | LLM | Spring AI 多 Provider |

凡是能算清楚的（选哪题、分怎么算、闸门怎么拦、间隔多久、要不要去重），**一律不进 Prompt**。

## ✨ 特性

**学习闭环**

- 🧭 **学习方向 intake** —— 多轮对话确认"你要学什么、目标是啥"，可先挂一份自己的资料（书 / 项目文档），AI 基于真实内容规划学习计划
- 🧠 **分层知识图谱** —— 概念按 layer 1–5 组织，选题闸门优先从低层未掌握点起步；知识点可自由增改删，AI 可按一句话追加
- 📖 **先教后考** —— 进入练习前把知识点拆成 3–8 个子知识点，逐个**讲解再做题**；讲解流式生成，支持 Markdown 排版、代码块高亮与 Mermaid 图，结果按 (concept, sub_point) 缓存，重练不重复调 LLM
- 💬 **讲解页答疑** —— 读讲解时有疑惑随时在下方提问，可选中讲解某段作为上下文；AI 结合讲解正文与用户自己的学习资料回答（SSE 流式），答疑仅当前用户私有、不判分、不反哺讲解正文，可多选删除
- 🔗 **组合题挂靠学习** —— 新知识点挂到已掌握的"锚点"上练（一题最多一个新点），防止一上来就崩
- 🔁 **7 种认知探针题型轮换** —— RECALL / CLOZE / REVERSE / TRAP / SCENARIO / CONTRAST / INTEGRATION，换种描述、换种形式反复检测
- 🗓️ **今日任务 / 每日排期** —— 每天自动排"复习多少、新学多少"，异步预生成题目，READY 即秒开

**作答与判分**

- 💬 **对话式作答** —— 学习模式下像聊天一样逐轮回答，AI 逐条追问，中途可看答案（记录"揭示边界"，评分只取揭示前的回答）
- 🧑‍🏫 **理解型判分** —— 判分前先根据对话实录确定老师实际问过哪些问题，**只评被问到的内容**（没问到的追问对应评分点判 NA、不计分、不展示）；判分看意思不看措辞，换种说法、意思一致即算命中
- 🧩 **多种作答格式** —— FREE_TEXT 问答 / CHOICE 选择题（精确比对，不走 LLM）/ STRUCTURED 结构化 / CODE 算法题
- 🚪 **掌握闸门** —— 同一题同一用户只允许一个未闭环 run（物理唯一索引），没搞懂就不会被下一题淹没

**复习与沉淀**

- ⏰ **FSRS 间隔排程** —— AGAIN/HARD/GOOD/EASY 四档，到期自动安排复习，没过(AGAIN)当天重来
- 📊 **掌握画像** —— 量化"你在某个主题上已掌握到第几层"（mastery 0–3），摸底 + 持续出图
- 📝 **内化复盘** —— AI 只做 critique（提示你漏了什么、哪句错了）+ 生成解题思路与记忆口诀，**不替你写正文**；笔记正文支持 **Markdown 书写 + 实时预览**（方便贴代码），保存后回显，欠账清单自动消化
- 🧾 **对话沉淀（随手记）** —— 日常自由问答（SSE 流式），有价值的对话一键收敛成**知识卡片**；空态按高频标签推荐问题，标签筛选 / 搜索 / 分页
- 🃏 **知识卡片复习** —— 新卡**当天到期**进内化复盘，掌握后按 FSRS 间隔 1/3/7/15/30 天排程；复习反馈按概念粒度**同步掌握度**（封顶 L2，L3 仅模拟面试达标）

**面试**

- 🎤 **REHEARSAL 模拟面试** —— 闭卷、倒计时、不可编辑、追问封顶 2 轮，用文字模拟"说出口收不回"的压力
- 🎙️ **INTERVIEW 完整面试** —— 选面试方式、面试依据（简历 / 学习方向）、难度与题数，逐题流式问答，结束后**逐题回顾**
- 🔀 **反重复去重** —— 字符级 trigram Jaccard 相似度，换个说法重出会被拦下，零网络依赖

**平台**

- 🔌 **多 LLM Provider** —— DeepSeek / 阿里百炼 DashScope / Kimi 可切换；桌面端 key 只存本机（随请求临时传入），Web 端按账号隔离存储
- 🖥️ **桌面应用（Electron）** —— 支持**本地模式**（内嵌 JRE + Spring Boot，开箱即用）与**云端模式**（后端部署在服务器，安装包只带前端）；支持**本地文件夹/文件导入**做学习资料（免上传）、系统托盘驻留、**学习提醒定时任务**、GitHub Release 自动更新（检查 → 下载 → 立即更新），提供 Windows/macOS 安装包

## 🩺 痛点解决进度

> 这个项目的出发点，是下面 7 个用 AI 学面试时真实踩过的坑。每一条都对应一个明确的产品机制。

图例：✅ 已落地 · 🟡 部分落地 · ⏳ 规划中

| # | 痛点 | 项目给出的解法 | 进度 |
|---|---|---|---|
| 1 | **信息茧房**：让 AI 教你，它只会重复你已经知道的名词，永远学不到新东西 | 分层知识图谱（layer 1–5）保证"从底层没掌握的点起步"；选题闸门按"到期复习 → 低层未掌握 → 临期"排优先级；组合题**挂靠学习**把新点挂到已掌握锚点上；每日任务异步预生成新题 | ✅ |
| 2 | **复习形式单一**：只设学习任务，缺少"多变复习"——换个描述、换个形式再测一次 | FSRS 四档间隔排程自动安排到期复习；**7 种认知探针题型**强制轮换；单点达标后强制升级为组合题（arity≥2）；CONTRAST 对比边专测易混淆点 | ✅ |
| 3 | **无法量化水平**：AI 教学没有量化的水平测试，不知道自己到底在哪 | 学习方向 intake 里的摸底对话（含选择题精确判分）；mastery 0–3 分层；**掌握画像**页直接量化"该主题你已掌握到第几层" | ✅ |
| 4 | **面试 ≠ 手写**：手写能改，面试说出口收不回，怎么保证手写 OK、面试不磕巴 | **REHEARSAL 模拟面试**（闭卷 + 强制倒计时 + 不可编辑 + 追问封顶）+ **INTERVIEW 完整面试**（方式/依据/难度/题数 + 逐题回顾）；达到 mastery 3 才视为"模拟面试达标" | 🟡 文字模拟已落地；**语音口述 SPEAK** 推迟到 P5 之后 |
| 5 | **应接不暇**：上一题还没搞懂，AI 又推下一题 | **掌握闸门**：同一题同一用户只允许一个未闭环 run（物理唯一索引）；判分后没过（AGAIN）当天重来；选题闸门优先到期复习与薄弱概念，不达标不往前走 | ✅ |
| 6 | **题型单一**：只有问答题，算法题、架构设计题怎么办 | 抽象出 `response_format` 作答格式维度：FREE_TEXT（LLM 理解型判分）/ CHOICE（精确比对，不走 LLM）/ STRUCTURED（按 schema 逐条 rubric）/ CODE（relay 判题） | 🟡 问答与选择题已落地；结构化/算法题的判分实现规划中 |
| 7 | **笔记不过脑子**：AI 给你写的笔记，你根本没经过脑子 | **内化复盘**：AI 只做 critique + 生成解题思路与记忆口诀，**不替你写正文**；笔记正文支持 **Markdown 书写与预览**（方便沉淀代码）；**对话沉淀 + 知识卡片**（随手问答一键成卡、当天到期复习、掌握度同步）已落地；6 段概念卡片挖空自测规划中 | 🟡 复盘/口诀/卡片已落地；挖空自测规划中 |

## 🛠️ 技术栈

| 层 | 技术 |
|---|---|
| 后端 | Spring Boot 4 · Java 21 · Spring AI · Spring Security（JWT）· Flyway · 端口 23333 |
| 存储 | PostgreSQL 16 (+pgvector)（必需）；Redis / MinIO（可选，见部署章节） |
| AI | OpenAI 兼容多 Provider：DeepSeek / DashScope（百炼）/ Kimi；结构化输出 + SSE 流式 |
| 前端 | Vite · React 18 · TypeScript · react-router · react-markdown（+ GFM / 代码高亮 / Mermaid）· Ant Design |
| 桌面 | Electron · electron-builder · electron-updater · 本地模式内嵌 JRE/Spring Boot · Windows NSIS · macOS DMG |

## 🚀 快速开始

### 依赖

- JDK 21
- Node.js 18+（前端 / 桌面端）
- Docker（PostgreSQL；Redis / MinIO 可选）

### 1. 启动基础设施

```bash
docker compose -f docker-compose.dev.yml up -d
# PostgreSQL(:5432) + Redis(:6379) + MinIO(:9000/:9001)
```

核心学习功能只用 PostgreSQL；Redis（会话缓存）与 MinIO（简历/文件存储）不启动也能跑通主体流程。

### 2. 配置环境变量

后端启动前需要读取 `.env`（被 gitignore，不入库）。`start.sh` 会自动解析并导出。

```bash
cp .env.example .env
```

`.env` 关键项：

```bash
# LLM Provider（可选；不配则服务器不带任何共享 key，每个用户在「设置」页用自己的 key）
# 云端多用户：建议全部留空 —— 桌面端用户 key 仅存本机（随请求临时传入，不落服务器）；
# Web 端用户在「设置」页填自己的 key（服务器按用户隔离存储）。
API_KEY=sk-deepseek-xxx   # 本地单机用；云端多用户建议留空
DASHSCOPE_API_KEY=sk-dashscope-xxx
PROVIDER_KIMI_API_KEY=sk-kimi-xxx
MODEL_NAME=deepseek-chat

# Swagger（生产默认关）
APP_SWAGGER_ENABLED=false

# 基础设施（与 docker-compose.dev.yml 默认一致，可省略）
POSTGRES_HOST=localhost
POSTGRES_PORT=5432
POSTGRES_USER=postgres
POSTGRES_PASSWORD=123456
POSTGRES_DB=interview

# 对象存储 MinIO（可选，简历上传用）
APP_STORAGE_ENDPOINT=http://localhost:9000
APP_STORAGE_ACCESS_KEY=minioadmin
APP_STORAGE_SECRET_KEY=minioadmin
APP_STORAGE_BUCKET=interview

# JWT 密钥（生产环境务必修改）
APP_JWT_SECRET=change-me-to-a-long-random-string

# 邮箱验证（可选：不配则注册自动通过）
MAIL_HOST=
MAIL_PORT=587
MAIL_USERNAME=
MAIL_PASSWORD=
MAIL_FROM=面霸 <你的发件邮箱>
```

### 3. 启动后端

```bash
./start.sh          # 等价于导出 .env 后 ./gradlew :app:bootRun
```

- API 地址：`http://localhost:23333`
- Swagger UI：`http://localhost:23333/swagger`

### 4. 启动前端（Web）

```bash
cd frontend
npm install
npm run dev         # http://localhost:5173，/api 已代理到 23333
```

### 5. 账号（注册 / 登录）

注册采用 **邮箱 + 密码**（密码 BCrypt 存储）。若配置了 `MAIL_HOST` / `MAIL_FROM`，注册后会给邮箱发 6 位验证码；**未配置 SMTP 时注册自动通过**（本地 / 演示）。

## 🖥️ 桌面应用

Electron 桌面壳，一套代码两种模式：

| | 本地模式 | 云端模式 |
|---|---|---|
| 后端 | 安装包内嵌精简 JRE + Spring Boot fat jar，启动自动拉起 | 部署在你自己的服务器，安装包**只含前端 SPA** |
| 打包 | `npm run dist`（默认） | `MIANBA_SERVER=https://你的域名 npm run dist:cloud` |
| 产物 | `dist-electron/` | `dist-electron-cloud/`（体积小几十 MB） |

当前能力：

- **本地文件夹/文件导入** —— 新建学习方向时可选择本机文件或目录作为学习资料：本地模式后端直接读盘免上传；云端模式由 Electron 在你本机读盘（自动跳过 node_modules/.git 等，限 40MB）再交给服务器解析
- **先教后考 + 对话式作答** —— 与 Web 端完全一致的学习闭环，SSE 流式体验
- **系统托盘驻留** —— 关闭主窗口后应用继续在后台运行；双击托盘图标或点击“打开面霸”恢复窗口；托盘菜单“彻底退出”清理后端进程树
- **学习提醒（定时任务）** —— 在「设置 → 学习提醒」配置提醒时间与周期（每天/每周），主进程定时器到点发系统通知；提醒设置持久化在用户目录，卸载重装前不丢失
- **自动更新** —— 启动自动检查 + 设置页「检查更新」（只查不下载）→「下载更新」（按平台下载正确格式：macOS 下 dmg、Windows 下 NSIS）→「立即更新」（Windows 一键重启安装，macOS 打开 dmg 安装包）
- **平台安装包** —— Windows 提供 NSIS `.exe` 和 `.zip`，macOS 提供 `.dmg` 和 `.zip`
- **LLM key 本机存储** —— 桌面端模型 key 只存本机（系统支持时加密），随请求临时传入，不落服务器

### 下载和安装

从 [GitHub Releases](https://github.com/LuckPony/interview/releases) 下载当前平台的安装包：

```text
Windows：mianba-*-cloud-win-x64.exe
macOS：  mianba-*-cloud-mac-*.dmg
```

Windows 推荐使用 `.exe` 安装版，以获得完整的自动更新能力；`.zip` 主要用于便携运行。macOS 未使用有效 Developer ID 签名和公证时，可能需要通过“右键 → 打开”首次启动。

### 本地开发运行

```bash
npm --prefix frontend install
npm --prefix interview-desktop install
cd interview-desktop
npm start
```

开发模式会从源码启动本地后端；打包模式则使用安装包内嵌的 JRE 和 fat jar。

### 构建与发布

**CI（推荐）**：推送到 `main` 后，GitHub Actions 在 Windows / macOS Runner 上构建**云端模式**安装包（只含前端），并把安装包、blockmap、`latest*.yml` 发布到 GitHub Release。需要在仓库 **Settings → Secrets** 配置 `MIANBA_SERVER`（后端服务器地址，用于烘焙前端 API 地址）。

**本地一键发布（云端模式）**：

```bash
cd interview-desktop
MIANBA_SERVER=http://103.236.92.40:23333 GH_TOKEN=xxx npm run release
# 一次构建并上传 Windows x64（exe/zip）与当前 Mac 架构（dmg/zip）的云模式安装包
```

正式发布一律走**云端模式**（桌面端只含前端，后端在服务器）；`MIANBA_SERVER` 可不带协议（自动补 `http://`）。

**本地构建 / 调试**：

```bash
cd interview-desktop
# 云端模式（后端在服务器，只打包前端；不发布，仅出产物）
MIANBA_SERVER=https://你的域名 npm run dist:cloud      # macOS
MIANBA_SERVER=https://你的域名 npm run dist:cloud:win  # Windows

# 本地模式（内嵌后端，连外部 PostgreSQL，本地调试用）
npm run dist            # 当前平台
npm run dist:win:local  # Windows（需在 macOS 上跑脚本）
```

构建产物位于 `dist-electron/`（本地模式）或 `dist-electron-cloud/`（云端模式）。`release:local` / `release:win:local` 仅用于本地模式的分发（调试 / 离线场景），**不要**用作正式发布通道——正式发布请用 CI 或 `npm run release`（云模式）。发布新版本时先更新 `interview-desktop/package.json` 与 `package-lock.json` 中的版本号，客户端只有在 Release 版本高于当前安装版本时才会提示更新。更新日志位于：

### 服务器部署

后端与 Web 部署到服务器（`/opt/mianba`）。当前服务器机房（陕西电信云基地）限制**境外入站**，GitHub Actions（美国机房）无法直连，因此用本机一键脚本部署（本机为国内 IP，SSH 可达）：

```bash
bash deploy/deploy-local.sh
# 构建 jar + Web SPA → scp/tar 上传 → 服务器跑 deploy-prod.sh（PG 备份→docker 重启→健康检查）
# 可用环境变量：SSH_HOST / SSH_PORT(默认37777) / SSH_USER / SSH_KEY / DEPLOY_DIR
```

> 服务器部署统一走本地脚本（机房限制境外入站，GitHub Actions 无法直连，相关部署流水线已移除）。

```text
Windows：%APPDATA%\interview-desktop\updater.log
macOS：  ~/Library/Application Support/interview-desktop/updater.log
```

> ⚠️ 云端产物与本地产物共用同一套 `latest*.yml` 自动更新。同一版本若同时发布本地包与云包，后上传的一方会覆盖 `latest*.yml`，已安装客户端将按它的产物更新。云部署后建议只发布云产物。

## ☁️ 云端部署（后端）

桌面端「云端模式」要连上的服务器，后端这样跑：

**方式 A · Docker（推荐）**

```bash
cp .env.example .env     # 填 API_KEY / APP_JWT_SECRET / MAIL_* 等
docker compose -f docker-compose.prod.yml up -d --build
```

自带 PostgreSQL + 后端镜像。**上线前务必改 `POSTGRES_PASSWORD` 和 `APP_JWT_SECRET`**。核心学习功能只用 PostgreSQL；Redis / MinIO 可选（简历上传用，不配则上传功能不可用、核心不受影响）。

**方式 B · 裸 jar**

1. **构建可执行 jar**：`./gradlew :app:bootJar`，产物在 `app/build/libs/`。
2. **服务器上准备**：PostgreSQL（必需）；Redis / MinIO 可选。
3. **启动**：把 `.env` 里的环境变量传进去后 `java -jar app/build/libs/app-0.0.1-SNAPSHOT.jar`。

**公网**：用 Nginx / Caddy 反代 **23333** 端口并配 HTTPS。桌面端 SPA 从 `file://` 请求，跨域已放行（CORS `*`）。

### 自动部署（GitHub Actions）

推送到 `main` 时，`Deploy to server` 工作流会自动构建并部署后端 + Web 到服务器（也可在 Actions 页手动触发）。采用**产物式部署**，与服务器 `/opt/mianba` 的实际结构一致：

1. **CI 构建产物**：后端 fat jar（`./gradlew :app:bootJar`）+ Web SPA（`npm run build`）。
2. **上传产物**：Windows 兼容的 `scp + ssh/tar` 上传 `app.jar → /opt/mianba/backend/app.jar`、Web 静态文件、`deploy/nginx.conf → /opt/mianba/web-image/nginx.conf` 和 `deploy/deploy-prod.sh`；有 `official-site/index.html` 时整体更新官网与 `/app`，缺少官网源码时只更新 `/app` 并保留服务器现有官网；`.env`、`backups/`、自定义 `docker-compose.yml` 都保留。
3. **复用镜像**：`deploy-prod.sh` 对基础设施镜像（postgres/redis/minio）「已存在则复用、缺失才拉取」；backend/web 用 docker 层缓存构建（未变化的层直接复用）；`compose up` 带 `--no-build`；部署前自动备份 PostgreSQL，部署后健康检查。

配置一次即可（仓库 **Settings → Secrets and variables → Actions**）：

| Secret | 值 |
|---|---|
| `SERVER_HOST` | 服务器 IP，如 `103.236.92.40` |
| `SERVER_USER` | SSH 用户名，如 `root` |
| `SERVER_SSH_KEY` | SSH 私钥完整内容（含 `-----BEGIN ...-----` 行；公钥需已加入服务器 `~/.ssh/authorized_keys`） |
| `SERVER_SSH_PORT` | SSH 端口（非默认 22 时必填，如 `37777`） |
| `DEPLOY_DIR` | 可选，部署目录，默认 `/opt/mianba` |

> 要求：服务器已装 docker 与 docker compose v2；`/opt/mianba/.env` 已配置好全部环境变量（`APP_JWT_SECRET` 等）；Web 对外端口按你的 NAT/防火墙规则暴露（如内部 18080 NAT 到 5678）。

## 🏗️ 架构设计

### 一次学习的完整链路

```
intake 摸底对话 ──► 学习方向 / 学习计划（可挂资料）
        │
        ▼
每日任务 / 选题闸门（纯确定性）   到期复习 → 低层未掌握 → 临期
        │
        ▼
先教后考：拆解知识点 → 逐子点讲解（Markdown/代码/Mermaid，SSE 流式 + 缓存）
        │
        ▼
组合策略（纯确定性）      arity、挂靠锚点、同 topic 跨层 / 跨 topic 权衡层
        │
        ▼
出题（LLM）              按 probe_type + response_format + 反重复去重生成
        │
        ▼
对话式作答（SSE）        READY → ANSWERING → GRADED（一题一闭环，可看答案）
        │
        ▼
理解型判分（Grader 分派）  FREE_TEXT 走 LLM 逐点判分（只评被问到的内容） / CHOICE 精确比对
        │
        ▼
FSRS 排程 + 掌握度更新     AGAIN/HARD/GOOD/EASY → 0/1/2/4 天；mastery 0–3
        │
        ▼
内化复盘（Markdown 笔记 + critique/思路/口诀）＋ 随手记沉淀知识卡片
```

### 关键设计

- **选题闸门**（`SelectionService`）：纯确定性、零 LLM。"学什么"由算法按 ①到期复习 ②从未练过按 layer 升序 ③最近要到期的 排序。
- **组合策略**（`CombinationPolicy`）：关联来自结构、不随机配对。只有两种合法伙伴——同 topic 跨层（层差 1–2）、同 layer 跨 topic 且 layer≥4（权衡/故障层）。一题最多一个新点，新点必须挂靠到已掌握（level≥2）的锚点上。
- **先教后考**（`LessonGenerator`）：concept → 3–8 个子知识点（缓存 outline）→ 逐子点讲解（缓存 concept_lesson）。讲解安全地处理"模型收尾后从头再讲"的重复尾段，且不会把正常编号列表误截。
- **讲解页答疑**（`LessonQaGenerator` / `lesson_qa_message`）：讲解页随时提问，可选中讲解片段作上下文，AI 结合讲解正文 + 用户自己的学习资料流式回答；仅当前用户私有、不判分、不动 mastery、不反哺讲解正文，支持多选删除。
- **判分编排**（`Grader` 接口）：按 `response_format` 分派到实现，编排层零 if。LLM 只在 FREE_TEXT 出镜，判分前先根据对话实录确定实际被问过的问题，**只评被问到的内容**（未问到的判 NA、不计分、不展示），并**按意思判分**而非按措辞；分数由服务端统一计算，LLM 只做逐点判定，绝不让模型算分。
- **反重复**（`NgramSimilarityGuard`）：字符级 trigram Jaccard，抓"抄写/换皮"很准，零网络调用、确定性可复现。
- **每日任务**（`DailyPlanService`）：让学习方向自主安排"每天复习多少、新学多少"，异步预生成题目，按方向幂等生成。
- **掌握画像**（`ProfileService`）：不建 Elo，直接 `GROUP BY topic, MAX(layer)` 出深度画像——layer 本身就是难度刻度。
- **对话沉淀 → 知识卡片**（`ChatCaptureService` / `CardService`）：自由问答（SSE 逐 token）→ 一键收敛为知识卡片（LLM 结构化提炼 question/answer/tags + 关联已有概念）→ 新卡当天到期进内化复盘 → 复习反馈按概念粒度**回写掌握度**，日常沉淀不再与知识点画像脱钩。

## 📁 目录结构

```
interview/
├── app/                          # Spring Boot 后端（端口 23333）
│   ├── src/main/java/interview/homegrown/
│   │   ├── common/               # AI Provider 注册表、结构化输出、配置、异常
│   │   ├── infrastructure/       # 文件解析/存储（Tika + S3）、Redis
│   │   └── modules/
│   │       ├── drill/            # 学习闭环核心：选题/组合/出题/作答/判分/排程/复盘
│   │       │   ├── ai/           #   出题 / 评分 / 讲解 / 拆解（LLM 教具层）
│   │       │   ├── grader/       #   判分策略（Grader / GraderText / GraderMcq）
│   │       │   ├── service/      #   选题闸门 / 组合策略 / 排程 / 复盘 / 画像 / 资料索引
│   │       │   └── web/          #   REST + SSE 控制器、DTO
│   │       ├── knowledge/        # 对话沉淀 + 知识卡片（随手记）
│   │       ├── interview/        # 完整面试会话（方式/依据/难度/题数 + 逐题回顾）
│   │       ├── resume/           # 简历上传与分析
│   │       ├── user/             # 账号（邮箱+密码、JWT、验证码）
│   │       └── demo/             # 健康检查演示
│   └── src/main/resources/
│       ├── db/migration/         # Flyway 建表与迁移脚本（common + postgresql）
│       └── application.yml
├── frontend/                     # Vite + React + TS SPA（页面：首页/今日学习/练习/模拟面试/复盘/随手记/画像/设置…）
├── official-site/                # 官网落地页（静态，根路径 /）：介绍/特性/截图/下载/Star
├── interview-desktop/            # Electron 桌面壳（本地/云端双模式）
├── docs/                         # 设计文档与实现清单
└── docker-compose.dev.yml        # PostgreSQL / Redis / MinIO
```

## 🗺️ 路线图

来自 [docs/drill/IMPLEMENTATION_CHECKLIST.md](docs/drill/IMPLEMENTATION_CHECKLIST.md)（P0→P5 边建边学清单）：

| 阶段 | 内容 | 状态 |
|---|---|---|
| P0 | 地基：跑通「做一道题」 | ✅ |
| P1 | 出题 + 判分 | ✅ |
| P2 | 闸门 + 深度画像 | ✅ |
| P3 | 间隔排程 | ✅ |
| P4 | REHEARSAL 模拟面试（纯文本） | ✅ |
| P5 | 内化（笔记没过脑子） | ✅ |

**P5 之后已落地：**

- 📝 内化复盘（critique + 思路 + 口诀，笔记支持 Markdown + 预览）
- 📖 **先教后考**（知识点拆解 + 逐子点讲解，Markdown / 代码高亮 / Mermaid 图）
- 🧾 **对话沉淀**（自由问答 + 一键成卡）
- 🃏 **知识卡片**（当天到期复习、FSRS 间隔、标签筛选 / 搜索 / 分页、两级查看答案）
- 🎙️ **INTERVIEW 完整面试**（面试方式 / 简历与学习方向依据 / 难度 / 题数 + 逐题回顾）
- 🖥️ **桌面端双模式**（本地内嵌后端 / 云端只带前端）+ 本地文件夹导入 + 学习提醒定时任务
- 🧑‍🏫 **理解型判分**（只评被问到的内容，看意思不看措辞）

**规划中 / 待打磨：**

- 🗣️ **语音口述 SPEAK**：从"限时 + 不可编辑"的文字模拟，升级为真实语音作答
- 🧩 **STRUCTURED / CODE 判分实现**：架构设计题按 schema 逐条 rubric，算法题 relay 判题
- 🃏 **概念卡片 + 挖空自测**：6 段模板 + cloze_item 用 FSRS 调度
- 🧠 **完整 Elo / CAT 自适应能力模型**：当前以 layer 刻度画像，后续可叠加自适应摸底
- 🌱 **知识图谱冷启动批产**：离线 Agent 批量生产知识点与题库

## 📄 License

[MIT](LICENSE)
