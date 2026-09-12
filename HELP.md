# Getting Started

## 桌面端记住登录密码与知识库目录整理（2026-09-09）

- 桌面端登录页新增「记住密码」，默认不勾选。勾选并成功登录后，下次退出登录 / 重启应用会填入邮箱和密码，但不会自动提交登录，也不会保留已经退出的登录态。
- 登录凭据使用 Electron `safeStorage` 的系统加密，仅保存在当前电脑的用户数据目录；与登录 token 分开，按本地 / 云端后端地址隔离。系统加密不可用时禁用记忆，绝不回退为明文。Windows 加密保护不等于可以抵御同一系统账号下的恶意软件，共享电脑建议不要勾选。
- 取消勾选或点击「清除已保存」会删除当前后端对应的本机凭据。只有登录成功才更新保存的密码；在个人中心修改密码后，仅同步已记住的同一账号。网页端仍由浏览器自己的密码管理器决定是否保存，不向 localStorage 或后端另存明文密码。
- 知识库目录不再把页码、纯数字、公式碎片当成章节；去掉编号重复、重复标题摘要，全大写英文标题统一显示。没有标题的开篇保留为「正文导读」，碎片和续页归入对应章节，原始内容不删除。
- 知识库详情、建计划的学习主题、工具库的章节阅读和生成上下文复用同一套目录整理规则。旧资料读取时即生效，不需要重新上传；「重新整理」可补充 AI 知识主题与摘要，不改动已有块 ID 或学习关联。原文中本身缺少标题或 PDF 阅读顺序异常时，目录识别仍可能需要 AI 辅助。

**如何生效：**此次同时改了 Electron、React 和 Java，已安装的旧 EXE 不会自动获得新页面。

本地后端 + 桌面源码运行：先从托盘彻底退出旧进程，在 `interview-desktop` 目录执行：

```powershell
npm run sync-spa
npm start
```

云端后端 + 桌面端：先按本文部署流程同步服务器后端；然后在 `interview-desktop` 目录设置正确的 `MIANBA_SERVER`，执行 `npm run dist:cloud:win`，安装新的 EXE。本地自包含安装包则使用 `npm run dist:win:local` 重新打包。`sync-spa` 会切换为本地后端模式，不要用它更新云端包。

可单独运行密码存储回归测试：`npm run test:credentials`（在 `interview-desktop` 目录）。

### Reference Documentation
For further reference, please consider the following sections:

* [Official Gradle documentation](https://docs.gradle.org)
* [Spring Boot Gradle Plugin Reference Guide](https://docs.spring.io/spring-boot/4.0.7/gradle-plugin)
* [Create an OCI image](https://docs.spring.io/spring-boot/4.0.7/gradle-plugin/packaging-oci-image.html)
* [Spring Web](https://docs.spring.io/spring-boot/4.0.7/reference/web/servlet.html)
* [Spring Boot Actuator](https://docs.spring.io/spring-boot/4.0.7/reference/actuator/index.html)
* [Validation](https://docs.spring.io/spring-boot/4.0.7/reference/io/validation.html)
* [Spring Data JPA](https://docs.spring.io/spring-boot/4.0.7/reference/data/sql.html#data.sql.jpa-and-spring-data)

### Guides
The following guides illustrate how to use some features concretely:

* [Building a RESTful Web Service](https://spring.io/guides/gs/rest-service/)
* [Serving Web Content with Spring MVC](https://spring.io/guides/gs/serving-web-content/)
* [Building REST services with Spring](https://spring.io/guides/tutorials/rest/)
* [Building a RESTful Web Service with Spring Boot Actuator](https://spring.io/guides/gs/actuator-service/)
* [Validation](https://spring.io/guides/gs/validating-form-input/)
* [Accessing Data with JPA](https://spring.io/guides/gs/accessing-data-jpa/)

### Additional Links
These additional references should also help you:

* [Gradle Build Scans – insights for your project's build](https://scans.gradle.com#gradle)

### 可以批量一次性导入env所有变量到终端的方式：

在 PowerShell 中运行：

```powershell
Get-Content .env | ForEach-Object { if ($_ -match '^\s*([^#][^=]+)=(.*)$') { [System.Environment]::SetEnvironmentVariable($matches[1].Trim(), $matches[2].Trim()) } }
```

## 修改代码后更新桌面端

项目中的 `frontend/` 是网页端和桌面端共用的 React 源码，`interview-desktop/` 是 Electron 桌面壳。更新方式取决于桌面端使用本地后端还是服务器后端。

### 只修改后端：更新本地桌面端（源码调试）

本地源码模式下，Electron 会自动调用项目根目录的 `start.sh`，再通过 Gradle `bootRun` 编译并启动最新后端。因此只修改 Java 后端后，不需要执行 `sync-spa`，也不需要提前构建 JAR。

1. 在系统托盘中选择“彻底退出”，确保旧 Electron 和旧后端进程已经退出。
2. 打开 Git Bash，进入桌面端目录并重新启动：

```bash
cd "/d/01 documents/02Code/Project/Spring Boot 4.0_Java 21_Spring AI 2.0/interview-homegrown/interview-desktop"
npm start
```

启动链路：

```text
npm start
  → Electron
  → 项目根目录 start.sh
  → ./gradlew :app:bootRun
  → 最新本地后端 http://127.0.0.1:23333
```

如果同时修改了 React 前端，先重新构建并复制 SPA：

```bash
cd "/d/01 documents/02Code/Project/Spring Boot 4.0_Java 21_Spring AI 2.0/interview-homegrown/interview-desktop"
npm run sync-spa
npm start
```

`sync-spa` 只更新前端文件，不会构建后端。

### 只修改后端：更新云端桌面端

云端桌面安装包只包含 Electron 和 React，后端运行在服务器。因此只修改 Java 后端时，只需把后端部署到服务器，不需要重新生成或重新安装 EXE：

```bash
cd "/d/01 documents/02Code/Project/Spring Boot 4.0_Java 21_Spring AI 2.0/interview-homegrown"
bash deploy/deploy-local.sh
```

部署成功后，已经安装的云端桌面端会在下一次接口请求时直接使用服务器上的新后端。

如果后端接口变化同时要求修改 React 前端，则需要先部署服务器，再重新生成云端 Windows EXE：

```powershell
cd "D:\01 documents\02Code\Project\Spring Boot 4.0_Java 21_Spring AI 2.0\interview-homegrown\interview-desktop"
$env:MIANBA_SERVER = "https://你的域名"
npm run dist:cloud:win
```

没有域名时，`MIANBA_SERVER` 可以填写服务器后端根地址，但不要添加 `/api`：

```powershell
$env:MIANBA_SERVER = "http://103.236.92.40:23333"
npm run dist:cloud:win
```

安装包生成在：

```text
interview-desktop/dist-electron-cloud/
```

更新规则汇总：

| 修改内容 | 本地桌面源码模式 | 服务器 | 云端桌面 EXE |
| --- | --- | --- | --- |
| 只修改 Java 后端 | 彻底退出后重新 `npm start` | 运行部署脚本 | 不需要重新打包 |
| 只修改 React 前端 | `npm run sync-spa` 后 `npm start` | 运行部署脚本 | 需要重新打包 |
| 前后端都修改 | `npm run sync-spa` 后 `npm start` | 运行部署脚本 | 需要重新打包 |
| 只修改 Electron 主进程 | 重新 `npm start` | 不需要 | 需要重新打包 |

## Windows 将代码部署到云端服务器

### 前置条件

部署必须在 Windows 的 Git Bash 中执行。终端提示符中应当包含 `MINGW64`，不要在 PowerShell 中直接运行 `bash`，否则可能调用 WSL。

确认本机工具：

```bash
java -version
node -v
npm -v
ssh -V
command -v scp
tar --version
curl --version
```

要求 Java 21、Node.js 18 以上（推荐 20），并且能够使用 Git Bash 自带的 `ssh`、`scp` 和 `tar`。部署脚本不再依赖 Windows 下容易出现管道兼容问题的 `rsync`。

当前默认 SSH 配置：

```text
服务器：103.236.92.40
SSH 端口：37777
SSH 用户：root
私钥：~/.ssh/id_ed25519
服务器部署目录：/opt/mianba
```

首次部署前测试免密 SSH：

```bash
ssh \
  -o BatchMode=yes \
  -i "/c/Users/26680/.ssh/id_ed25519" \
  -p 37777 \
  root@103.236.92.40 \
  "echo SSH_KEY_OK"
```

看到 `SSH_KEY_OK` 后即可部署。

### 执行一键部署

打开 Git Bash：

```bash
cd "/d/01 documents/02Code/Project/Spring Boot 4.0_Java 21_Spring AI 2.0/interview-homegrown"
bash deploy/deploy-local.sh
```

需要显式覆盖连接参数时：

```bash
SSH_HOST=103.236.92.40 \
SSH_PORT=37777 \
SSH_USER=root \
SSH_KEY="/c/Users/26680/.ssh/id_ed25519" \
DEPLOY_DIR=/opt/mianba \
bash deploy/deploy-local.sh
```

脚本自动执行：

```text
检查 SSH
  → 构建 Spring Boot JAR
  → 构建 React Web
  → 使用 scp/ssh/tar 上传
  → 备份 PostgreSQL
  → 构建并重启 backend/web 容器
  → 检查后端健康状态
```

如果本地存在 `official-site/index.html`，脚本会同时更新官网 `/` 和应用 `/app/`；如果不存在，只更新 `/app/` 并保留服务器当前官网。

### 部署后的验证

```bash
curl -fsS http://103.236.92.40:23333/actuator/health
```

正常结果中应包含：

```json
{"status":"UP"}
```

查看服务器容器：

```bash
ssh -i "/c/Users/26680/.ssh/id_ed25519" -p 37777 root@103.236.92.40 \
  'cd /opt/mianba && docker compose -f docker-compose.yml ps'
```

查看后端日志：

```bash
ssh -i "/c/Users/26680/.ssh/id_ed25519" -p 37777 root@103.236.92.40 \
  'cd /opt/mianba && docker compose -f docker-compose.yml logs --tail=200 backend'
```

### 生产配置注意事项

- 部署脚本不会上传或覆盖服务器的 `/opt/mianba/.env`。
- 部署脚本不会上传或覆盖服务器的 `/opt/mianba/docker-compose.yml`。
- 新增环境变量时，需要在服务器 `.env` 中配置，并确认服务器 Compose 的 `backend.environment` 已映射该变量。
- 不要用仓库根目录的 `docker-compose.prod.yml` 覆盖服务器 `/opt/mianba/docker-compose.yml`。前者用于从完整源码构建，后者用于服务器上的产物式目录 `backend/` 和 `web-image/`。
- 服务器 `.env` 或 Compose 修改后，需要重新创建相关容器才会生效。

### 数据库与中间件安全配置

生产环境必须在服务器 `.env` 中显式设置互不重复的 `POSTGRES_PASSWORD`、`REDIS_PASSWORD`、`MINIO_ROOT_PASSWORD` 和 `APP_JWT_SECRET`，不要使用 `.env.example` 中的占位内容。PostgreSQL、Redis 和 MinIO 只能绑定服务器回环地址：

```env
POSTGRES_BIND_ADDRESS=127.0.0.1
REDIS_BIND_ADDRESS=127.0.0.1
MINIO_BIND_ADDRESS=127.0.0.1
```

执行 `docker compose -f docker-compose.yml config --quiet` 检查配置后，重新创建相关容器。Navicat 使用 SSH 隧道连接服务器的 `127.0.0.1:5432`，不要为了图方便将 PostgreSQL 改回 `0.0.0.0`。Redis 生产配置必须启用 `requirepass`，后端同时映射 `SPRING_DATA_REDIS_PASSWORD`；健康检查也必须携带该密码。

```bash
docker compose -f docker-compose.yml up -d --force-recreate postgres redis minio backend
docker compose -f docker-compose.yml up --force-recreate --no-deps minio-init
ss -lntp | grep -E ':(5432|6379|9000|9001)'
```

最后一条命令只能看到 `127.0.0.1`，不能看到 `0.0.0.0`。生产 Compose 现在也以 `127.0.0.1` 为默认绑定地址，并强制要求关键密码存在，避免 `.env` 漏配时退回弱密码或重新暴露公网。

部署前数据库备份先写入 `.tmp` 文件；只有 `pg_dump` 成功、文件非空且能被 `pg_restore --list` 读取时，才会原子重命名为正式 `.dump`。任一步失败都会删除临时文件并中止部署，不再留下容易被误认为有效备份的 0 字节文件。PostgreSQL 未运行时也会默认中止部署；仅首次创建全新环境时才可显式设置 `SKIP_BACKUP=true`。数据库备份还应定期复制到另一台机器或私有对象存储，不能只留在同一台服务器。

### 知识库管理与工具库更新说明

- 「知识库管理」统一展示已导入资料、主题标签和章节索引；右上角「导入资料」直接选择文件，也支持拖拽。单份不超过 20 MB，解析文字不超过 20 万字符。
- 点击资料可查看简介、索引和实际关联的学习计划 / 模拟面试。可直接从详情页创建计划或面试，也可在新建页面的「从知识库选择资料」中选择现有资料，不必重复上传。
- 原「资料导入」菜单改为「知识库工具库」，支持文献翻译、重点提炼、术语解释。可以选择整份资料或章节，也可粘贴内容；每轮最多处理 12000 字符，超过时明确提示分段处理，不会声称已翻译整本书。点击处理会将当前内容发送给用户配置的模型服务，结果支持停止、复制和下载 Markdown，不自动入库。
- 索引生成复用现有 AI 配置和结构化输出。没有可用模型时仍保留章节基础索引；配置模型后可在详情页点击「重新整理」。生成计划 / 面试使用各章节的有界原文节选，不是全文向量检索。
- 新版单文件导入会保留上传文件的原始字节。「打开原文件」在浏览器展示原始 PDF / TXT / Markdown；Word 则下载原始 Word 文件，不发送到第三方预览服务。「查看解析文本」是另一个入口，会明确提示不是原件。历史导入只保存了解析文本，不能凭空恢复 PDF / Word 原件；需要原格式时请重新导入。链接仅对指定资料有效，五分钟后过期，请从详情重新打开。

本次更新同时涉及前后端，需一起更新；后端启动时由 Flyway 执行 `V46__corpus_library.sql`。桌面端的「浏览器查看原文」增加了系统浏览器桥接，需要重新打包新版桌面端才能使用；只替换服务器后端不会自动更新已安装的 exe 页面。

**原文件持久化：**本地默认 `APP_STORAGE_MODE=local`，保存在后端工作目录的 `data/files`，可通过 `APP_STORAGE_LOCAL_DIR` 指定。云端建议 `APP_STORAGE_MODE=minio`，原件写入私有桶，数据库仅记录对象标识；资料解析文本仍留在数据库，用于索引和生成。切换到 MinIO 不会自动搬迁旧磁盘文件，旧文件仍从原本目录读取。备份需要同时保留数据库、MinIO 数据卷以及历史本地文件目录 / 卷。

如果使用 `deploy-local.sh` 上传 jar 的产物式部署，服务器的 `/opt/mianba/docker-compose.yml` 不会被自动覆盖。只需在现有 `services.backend.environment` 中补充模式映射，并保留现有正确的 MinIO 凭据映射：

```yaml
APP_STORAGE_MODE: ${APP_STORAGE_MODE:-minio}
APP_STORAGE_ENDPOINT: http://minio:9000
APP_STORAGE_ACCESS_KEY: ${MINIO_ROOT_USER}
APP_STORAGE_SECRET_KEY: ${MINIO_ROOT_PASSWORD}
APP_STORAGE_BUCKET: ${APP_STORAGE_BUCKET:-interview}
APP_STORAGE_REGION: ${APP_STORAGE_REGION:-us-east-1}
```

确认这些凭据有该私有桶的读写权限、桶已由 `minio-init` 创建，再运行部署脚本重新创建容器。不要公开桶、不需要向浏览器下发 MinIO 密钥或内网地址。保留本地模式时，需确认 `APP_STORAGE_LOCAL_DIR=/app/data/files` 且挂载持久卷；首次新增挂载前先备份并迁移容器里的文件，避免新挂载遮住已有文件。不要直接用仓库 Compose 替换服务器的产物式 Compose。

### Windows 部署后知识库详情报错：旧 JAR 排查

如果网页已经更新但点击资料显示内部服务错误，而本地正常，先看后端日志是否出现 `Request method 'GET' is not supported`。这说明该 URL 没有 GET 接口，不能仅凭提示判断数据库故障。2026-09-08 的实际原因是：Git Bash 将 `cmd.exe /c` 的 `/c` 当成路径转换，Gradle 未运行，部署脚本却因旧 JAR 存在而继续上传。服务器 JAR 缺少 `CorpusLibraryController`，最高迁移文件为 V45，无法处理新版页面的 `GET /api/corpus/{id}`。

现在部署脚本禁用了该次 CMD 调用的路径转换，并在上传前校验当前 Java 类和全部迁移文件都在 JAR 内。可以先单独验证（不连接服务器）：

```bash
bash deploy/build-backend.sh
```

看到 `BUILD SUCCESSFUL` 和 `DEPLOY_JAR_VERIFIED` 才算完成。随后在 Git Bash 中执行 `bash deploy/deploy-local.sh` 同步发布前后端。不要只重启旧容器或只构建前端。云端 EXE 包含前端和 Electron 修改时，仍需按上文重新打包安装。
