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

[这是什么](#-这是什么) · [核心主张](#-核心主张) · [特性](#-特性) · [痛点解决进度](#-痛点解决进度) · [快速开始](#-快速开始) · [架构设计](#-架构设计) · [目录结构](#-目录结构) · [路线图](#-路线图)

</div>

---

## 💡 这是什么

「面霸」是一个**学习型面试 Agent**：它不满足于"给你一堆题让你刷"，而是像一位老师一样 —— 知道你学到了第几层、该复习什么、没搞懂就不许往前走。

它同时面向**学习**和**面试**两个场景：

| 模式 | 场景 | 特征 |
|---|---|---|
| **LEARN · 学习模式** | 日常学习 / 备考 | 开卷可选、计时可选、可撤回 |
| **REHEARSAL · 模拟面试** | 面试前冲刺 | 闭卷、强制倒计时、提交后不可编辑、追问封顶 2 轮 |

当前图谱种子方向：Java 后端（JVM / 并发 / Spring / 数据库 / 分布式 / 网络），知识图谱按 `layer 1→5` 由浅入深（概念 → 机制 → 实现 → 权衡 → 故障），可通过「学习方向」对话动态扩展。

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

- 🧭 **学习方向 intake** —— 多轮对话确认"你要学什么、目标是啥"，动态生成学习计划，而不是写死成固定目录
- 🧠 **分层知识图谱** —— 概念按 layer 1–5 组织，选题闸门优先从低层未掌握点起步
- 🔗 **组合题挂靠学习** —— 新知识点挂到已掌握的"锚点"上练（一题最多一个新点），防止一上来就崩
- 🔁 **7 种认知探针题型轮换** —— RECALL / CLOZE / REVERSE / TRAP / SCENARIO / CONTRAST / INTEGRATION，换种描述、换种形式反复检测
- ⏰ **FSRS 间隔排程** —— AGAIN/HARD/GOOD/EASY 四档，到期自动安排复习，没过(AGAIN)当天重来
- 🚪 **掌握闸门** —— 同一题同一用户只允许一个未闭环 run（部分唯一索引物理闸门），没搞懂就不会被下一题淹没
- 📊 **掌握画像** —— 量化"你在某个主题上已掌握到第几层"（mastery 0–3），摸底 + 持续出图
- 🎤 **模拟面试** —— 闭卷、倒计时、不可编辑、追问封顶 2 轮，用文字模拟"说出口收不回"的压力
- 🧩 **多种作答格式** —— FREE_TEXT 问答 / CHOICE 选择题（摸底走此链路）/ STRUCTURED 结构化 / CODE 算法题
- 📝 **内化复盘** —— AI 只做"critique"（提示你漏了什么、哪句错了），不替你写正文，并生成解题思路与记忆口诀
- 🔀 **反重复去重** —— 字符级 trigram Jaccard 相似度，换个说法重出会被拦下，零网络依赖
- 🔌 **多 LLM Provider** —— DeepSeek / 阿里百炼 DashScope / Kimi 可切换，留空自动跳过
- 🖥️ **桌面端** —— Electron 壳拉起本地后端 + 静态 SPA，支持免上传读取本地大项目

## 🩺 痛点解决进度

> 这个项目的出发点，是下面 7 个用 AI 学面试时真实踩过的坑。每一条都对应一个明确的产品机制。

图例：✅ 已落地 · 🟡 部分落地 · ⏳ 规划中

| # | 痛点 | 项目给出的解法 | 进度 |
|---|---|---|---|
| 1 | **信息茧房**：让 AI 教你，它只会重复你已经知道的名词，永远学不到新东西 | 分层知识图谱（layer 1–5）保证"从底层没掌握的点起步"；选题闸门按"到期复习 → 低层未掌握 → 临期"排优先级；组合题**挂靠学习**把新点挂到已掌握锚点上；每日任务异步预生成新题 | ✅ |
| 2 | **复习形式单一**：只设学习任务，缺少"多变复习"——换个描述、换个形式再测一次 | FSRS 四档间隔排程自动安排到期复习；**7 种认知探针题型**强制轮换（回忆/挖空/倒推/陷阱/场景/对比/综合）；单点达标后强制升级为组合题（arity≥2）；CONTRAST 对比边专测易混淆点 | ✅ |
| 3 | **无法量化水平**：AI 教学没有量化的水平测试，不知道自己到底在哪 | 学习方向 intake 里的摸底对话（含选择题精确判分）；mastery 0–3 分层；**掌握画像**页直接量化"该主题你已掌握到第几层" | ✅ |
| 4 | **面试 ≠ 手写**：手写能改，面试说出口收不回，怎么保证手写 OK、面试不磕巴 | **REHEARSAL 模拟面试**：闭卷 + 强制倒计时 + 提交后不可编辑 + 追问封顶 2 轮，用文字模拟不可撤回压力；达到 mastery 3 才视为"模拟面试达标" | 🟡 文字模拟已落地；**语音口述 SPEAK** 推迟到 P5 |
| 5 | **应接不暇**：上一题还没搞懂，AI 又推下一题 | **掌握闸门**：同一题同一用户只允许一个未闭环 run（部分唯一索引物理闸门）；判分后没过（AGAIN）当天重来；选题闸门优先到期复习与薄弱概念，不达标不往前走 | ✅ |
| 6 | **题型单一**：只有问答题，算法题、架构设计题怎么办 | 抽象出 `response_format` 作答格式维度：FREE_TEXT（LLM 逐点判分）/ CHOICE（精确比对，不走 LLM）/ STRUCTURED（按 schema 逐条 rubric）/ CODE（relay 判题）；题库 schema 已按此设计 | 🟡 问答与选择题已落地；结构化/算法题的判分实现规划中 |
| 7 | **笔记不过脑子**：AI 给你写的笔记，你根本没经过脑子 | **内化复盘**：AI 只做 critique（提示"你漏了什么、哪句错了"）+ 生成解题思路与记忆口诀，**不替你写正文**；规划 6 段概念卡片（定义→机制→项目印证→对比→易错点→记忆锚点）+ 挖空自测（FSRS 调度） | 🟡 复盘/口诀已落地；概念卡片挖空自测规划中 |

## 🛠️ 技术栈

| 层 | 技术 |
|---|---|
| 后端 | Spring Boot 4 · Java 21 · Spring AI · Spring Security（JWT）· Flyway |
| 存储 | PostgreSQL 16 (+pgvector) · Redis · MinIO（S3 协议） |
| AI | OpenAI 兼容多 Provider：DeepSeek / DashScope（百炼）/ Kimi；结构化输出 + SSE 流式 |
| 前端 | Vite 5 · React 18 · TypeScript · react-router · react-markdown |
| 桌面 | Electron 壳（拉起本地后端 + 加载 SPA + 本地 fs 桥） |

## 🚀 快速开始

### 依赖

- JDK 21
- Node.js 18+（前端 / 桌面端）
- Docker（PostgreSQL / Redis / MinIO）

### 1. 启动基础设施

```bash
docker compose -f docker-compose.dev.yml up -d
# PostgreSQL(:5432) + Redis(:6379) + MinIO(:9000/:9001) 就绪
```

### 2. 配置环境变量

后端启动前需要读取 `.env`（被 gitignore，不入库）。`start.sh` 会自动解析并导出，兼容 `KEY = value` 与"键一行、值一行"等非标准写法。

```bash
# 复制一份模板，填入你的 key
cp .env.example .env
```

`.env` 关键项：

```bash
# LLM Provider（可选；不配则服务器不带任何共享 key，每个用户在「设置」页用自己的 key）
# 云端多用户：建议全部留空 —— 桌面端用户 key 仅存本机（随请求临时传入，不落服务器）；
# Web 端用户在「设置」页填自己的 key（服务器按用户隔离存储）。本地单机模式可继续填 API_KEY。
API_KEY=sk-deepseek-xxx   # 本地单机用；云端多用户建议留空
DASHSCOPE_API_KEY=sk-dashscope-xxx
PROVIDER_KIMI_API_KEY=sk-kimi-xxx
MODEL_NAME=deepseek-chat          # 可选，默认 deepseek-chat

# Swagger（生产默认关，本地开发想开就设 true）
APP_SWAGGER_ENABLED=false

# 基础设施（与 docker-compose.dev.yml 默认一致，可省略）
POSTGRES_HOST=localhost
POSTGRES_PORT=5432
POSTGRES_USER=postgres
POSTGRES_PASSWORD=123456
POSTGRES_DB=interview
REDIS_HOST=localhost
REDIS_PORT=6379

# 对象存储 MinIO（可选，有默认值）
APP_STORAGE_ENDPOINT=http://localhost:9000
APP_STORAGE_ACCESS_KEY=minioadmin
APP_STORAGE_SECRET_KEY=minioadmin
APP_STORAGE_BUCKET=interview

# JWT 密钥（生产环境务必修改）
APP_JWT_SECRET=change-me-to-a-long-random-string

# 邮箱验证（可选：不配则注册自动通过；密码填邮箱的「授权码」）
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

- API 地址：`http://localhost:8080`
- Swagger UI：`http://localhost:8080/swagger`

### 4. 启动前端（Web）

```bash
cd frontend
npm install
npm run dev         # http://localhost:5173，/api 已代理到 8080
```

### 5. 账号（注册 / 登录）

注册采用 **邮箱 + 密码**（密码 BCrypt 存储）。若配置了 `MAIL_HOST` / `MAIL_FROM`，注册后会给邮箱发 6 位验证码，应用内输码完成验证；**未配置 SMTP 时注册自动通过**（本地 / 演示）。

### 6. 桌面端（可选）

桌面端是一个 Electron 壳：加载 `frontend` 构建出的静态 SPA，并负责拉起本地后端（本地模式）或直连你的服务器（云端模式）。应用图标取自 `frontend/src/logo.png`，打包时自动生成各平台图标（mac 的 `.icns` / Windows 的 `.ico`，见 `interview-desktop/build/icon.png`）。

**本地开发运行**（自己跑后端，见上方第 3 步）：

```bash
cd interview-desktop
npm install
npm start          # 自动拉起本地 Spring Boot，等待就绪后加载界面
```

**打包 macOS 安装包（DMG）**：

```bash
cd interview-desktop
npm install
MIANBA_SERVER=https://你的服务器 npm run dist:cloud   # 云端模式：应用直连你的服务器，适合发给你朋友
npm run dist                                          # 本地模式：应用启动时自己拉起本地后端
```

产物在 `interview-desktop/dist-electron/`：

- `面霸-0.1.0-arm64.dmg` —— 双击拖入 Applications 安装（Apple Silicon）
- `面霸-0.1.0-arm64-mac.zip` + `latest-mac.yml` —— 自动更新用

**打包 Windows 安装包（EXE）**：

```bash
cd interview-desktop
npm install
MIANBA_SERVER=https://你的服务器 npm run dist:win      # 云端模式
npm run dist -- --win                                   # 本地模式
```

产物在 `interview-desktop/dist-electron/`：

- `面霸 Setup 0.1.0.exe` —— NSIS 安装程序，双击安装
- `面霸-0.1.0-win.zip` —— 便携版（解压即用）

**打包注意事项：**

- **DMG 只能在 macOS 上打包**（需要 Xcode 命令行工具）。
- **EXE 建议在 Windows 机器或 GitHub Actions CI 上构建** —— macOS 交叉构建 NSIS 依赖 wine，容易踩坑。
- 云端模式会把服务器地址烘焙进应用（写入 `interview-desktop/config.json`）：**启动时不再拉起本地后端，界面直连你的服务器**。
- 所有 `dist` 系列命令都会先构建 `../frontend` 并复制到 `app-dist/`，请先确认 `frontend/` 下已 `npm install`。
- 发新版本改 `interview-desktop/package.json` 的 `version` 字段（如 `0.1.0 → 0.1.1`），electron-updater 按它检查更新。

### 7. 云端部署（后端）

桌面端「云端模式」要连上的服务器，后端这样跑：

**方式 A · Docker（推荐，一条命令）**

```bash
cp .env.example .env     # 填 API_KEY / APP_JWT_SECRET / MAIL_* 等
docker compose -f docker-compose.prod.yml up -d --build
```

自带 Postgres + Redis + 后端镜像。**上线前务必改 `POSTGRES_PASSWORD` 和 `APP_JWT_SECRET`**。核心学习功能只用 Postgres；MinIO 可选（简历上传用，不配则上传功能不可用、核心不受影响）。

**方式 B · 裸 jar**

1. **构建可执行 jar**：`./gradlew :app:bootJar`，产物在 `app/build/libs/`。
2. **服务器上准备**：PostgreSQL（必需）；Redis / MinIO 可选（见上文，核心功能不依赖）。
3. **启动**：把 `.env` 里的环境变量传进去后 `java -jar app/build/libs/app-0.0.1-SNAPSHOT.jar`。

**公网**：用 Nginx / Caddy 反代 8080 并配 HTTPS。桌面端 SPA 从 `file://` 请求，跨域已放行（CORS `*`）。
5. **打包分发**：见上方第 6 节「桌面端」—— `MIANBA_SERVER=https://你的域名 npm run dist:cloud`，把 `dist-electron/` 下的 `.dmg` / `.exe` 发给别人。

## 🏗️ 架构设计

### 一次练习的完整链路

```
intake 摸底对话 ──► 学习方向 / 学习计划
        │
        ▼
选题闸门（纯确定性）      到期复习 → 低层未掌握 → 临期
        │
        ▼
组合策略（纯确定性）      arity、挂靠锚点、同 topic 跨层 / 跨 topic 权衡层
        │
        ▼
出题（LLM）              按 probe_type + response_format + 反重复去重生成
        │
        ▼
作答状态机              READY → ANSWERING → SUBMITTED → GRADED（一题一闭环）
        │
        ▼
判分（Grader 分派）     FREE_TEXT 走 LLM 逐点判分 / CHOICE 精确比对
        │
        ▼
FSRS 排程 + 掌握度更新    AGAIN/HARD/GOOD/EASY → 0/1/2/4 天；mastery 0–3
        │
        ▼
内化复盘 + 复习调度       AI 只 critique 不代写正文；到期自动进入每日任务
```

### 关键设计

- **选题闸门**（`SelectionService`）：纯确定性、零 LLM。"学什么"由算法按 ①到期复习 ②从未练过按 layer 升序 ③最近要到期的 排序。
- **组合策略**（`CombinationPolicy`）：关联来自结构、不随机配对。只有两种合法伙伴——同 topic 跨层（层差 1–2）、同 layer 跨 topic 且 layer≥4（权衡/故障层）。一题最多一个新点，新点必须挂靠到已掌握（level≥2）的锚点上。
- **判分编排**（`Grader` 接口）：按 `response_format` 分派到实现，编排层零 if。LLM 只在 FREE_TEXT 出镜，逐点打分 + 统一输出 `byConceptJson` 留痕，掌握度按概念粒度更新。
- **反重复**（`NgramSimilarityGuard`）：字符级 trigram Jaccard，抓"抄写/换皮"很准，零网络调用、确定性可复现；将来可无缝换 embedding 实现。
- **每日任务**（`DailyPlanService`）：让学习方向自主安排"每天复习多少、新学多少"，异步预生成题目，按方向幂等生成。
- **掌握画像**（`ProfileService`）：不建 Elo，直接 `GROUP BY topic, MAX(layer)` 出深度画像——layer 本身就是难度刻度。

## 📁 目录结构

```
interview/
├── app/                          # Spring Boot 后端
│   ├── src/main/java/interview/homegrown/
│   │   ├── common/               # AI Provider 注册表、结构化输出、配置、异常
│   │   ├── infrastructure/       # 文件解析/存储（Tika + S3）、Redis
│   │   └── modules/
│   │       ├── drill/            # 🆕 面试备考模块（本项目的核心）
│   │       │   ├── ai/           #   出题 / 评分 / 复盘 / 追问（LLM 教具层）
│   │       │   ├── domain/       #   concept / question_bank / drill_run / mastery …
│   │       │   ├── grader/       #   判分策略（Grader / GraderText / GraderMcq）
│   │       │   ├── service/      #   选题闸门 / 组合策略 / 排程 / 复盘 / 画像 …
│   │       │   └── web/          #   REST + SSE 控制器、DTO
│   │       ├── interview/        # 既有面试会话模块
│   │       └── resume/           # 既有简历分析模块
│   └── src/main/resources/
│       ├── db/migration/         # Flyway V1–V11 建表脚本
│       └── application.yml
├── frontend/                     # Vite + React + TS SPA
│   └── src/{pages,components,api,auth,lib,styles}
├── interview-desktop/            # Electron 桌面壳
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

**规划中 / 待打磨：**

- 🗣️ **语音口述 SPEAK**：从"限时 + 不可编辑"的文字模拟，升级为真实语音作答
- 🧩 **STRUCTURED / CODE 判分实现**：架构设计题按 schema 逐条 rubric，算法题 relay 判题
- 🃏 **概念卡片 + 挖空自测**：6 段模板 + cloze_item 用 FSRS 调度（P5 后半）
- 🧠 **完整 Elo / CAT 自适应能力模型**：当前以 layer 刻度画像，后续可叠加自适应摸底
- 🌱 **知识图谱冷启动批产**：离线 Agent 批量生产知识点与题库

## 📄 License

[MIT](LICENSE)
