# Getting Started

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
SSH_KEY="/c/Users/26680/.ssh/"本机公钥"" \
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
