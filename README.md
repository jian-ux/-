# Feisheng Bot — 智能客服系统

基于 Java + Spring Boot 3 的多渠道智能客服系统，支持微信、钉钉、网页渠道接入，提供 AI 自动回复、FAQ 知识库匹配、人工客服转接、对话管理和后台配置能力。

## 技术栈

| 层级 | 技术 |
|---|----|
| 后端 | Java 17, Spring Boot 3, MyBatis-Plus |
| 数据库 | MySQL 8 |
| 缓存 | Redis 7 |
| 前端 | Vue 3, Element Plus, Vite |
| 部署 | Docker Compose |

## 项目结构

```
feisheng-bot/
├── feisheng-bot-parent/       # Maven 父项目
│   ├── feisheng-bot-common/   # 公共模块
│   ├── feisheng-bot-gateway/  # 渠道接入模块
│   ├── feisheng-bot-core/     # 核心对话模块
│   ├── feisheng-bot-knowledge/ # 知识库模块
│   ├── feisheng-bot-admin/    # 后台 API 与统一启动入口 (端口 8080)
│   └── sql/                   # 数据库初始化脚本
├── feisheng-bot-admin-ui/     # Vue 3 前端
├── docker-compose.yml          # Docker 编排
├── Dockerfile                  # 后端镜像构建
└── README.md
```

## 快速开始

### 本地开发

1. **配置**: 复制 `.env.example` 为 `.env`，设置数据库、JWT 和 MinIO 参数，并将这些变量加载到当前终端。
2. **数据库**: 在 MySQL 中按文件名前缀顺序执行 `feisheng-bot-parent/sql/` 下的全部迁移脚本；已有数据库只需继续执行尚未应用的较新编号脚本。
3. **后端**: 当前采用模块化单体部署，由 Admin 模块统一启动：
   ```bash
   cd feisheng-bot-parent
   mvn clean install -DskipTests
   mvn spring-boot:run -pl feisheng-bot-admin
   ```
4. **前端**:
   ```bash
   cd feisheng-bot-admin-ui
   npm install
   npm run dev
   ```

### Docker 部署

```bash
docker compose up -d
```

在支持 NVIDIA GPU 的 Docker Desktop 环境中启用仓库自带的 Reranker：

```powershell
.env 中设置 COMPOSE_PROFILES=reranker
.env 中设置 RAG_RERANK_ENABLED=true
docker compose up -d
.\scripts\verify-reranker.ps1
```

Compose 会并行启动机器人和重排器，模型缓存保存在 Docker 命名卷中，
重排器异常退出后会自动重启。首次启动需要构建 CUDA/PyTorch 镜像并下载模型；
后续启动会复用缓存。宿主机计划任务方案仅作为开发备用，说明见
`services/qwen3-vl-reranker/README.md`。

访问管理面板：`http://localhost`；后端宿主机端口为 `http://localhost:8082`，MySQL 调试端口为 `127.0.0.1:3307`。

## 管理员登录

- **用户名**: 由 `ADMIN_USERNAME` 配置，默认 `admin`
- **密码**: 必须通过 `ADMIN_PASSWORD` 配置，系统不会提供默认密码

## 服务端口

| 模块 | 端口 | 基础路径 |
|---|----|-----|
| 本地 Admin 应用 | 8080 | /api/*、/core/*、/gateway/* |
| Docker 后端映射 | 8082 | /api/*、/core/*、/gateway/* |
| Docker 前端 | 80 | / |

## 钉钉与企业微信接入

回调地址、环境变量和平台后台配置步骤见 [渠道接入说明](docs/channel-integration.md)。

## 许可证

Apache License 2.0
