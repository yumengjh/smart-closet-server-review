# 部署拓扑：鱼搭与管理平台

鱼搭可以单独部署，也可以与管理平台一起部署。本文说明一起部署时有哪些仓库和进程、它们怎么连接、哪些端口只能在内网开放，以及密钥怎样配对。单独部署鱼搭请看 [README](../README.md) 与 [前后端独立部署](INDEPENDENT_DEPLOYMENT.md)。

## 1. 仓库

| 仓库 | 角色 | 技术栈 |
|---|---|---|
| `smart-closet` | 业务前端（用户端） | Vue 3 + Ant Design Vue |
| `smart-closet-server`（本仓库） | 业务后端，含 Agent 运行时 `agent-runtime/` | Spring Boot 4.1 + H2；TypeScript |
| `smart-closet-admin-server` | 管理后端（网关），克隆自 `springboot-start` 模板 | Spring Boot 4.1 + SQLite / PostgreSQL |
| `smart-closet-admin` | 管理前端，克隆自 `springboot-admin` 模板 | Vue 3 + Element Plus |

两个管理仓库的模板远端名为 `template`，推送地址已禁用；同步模板更新时执行 `git fetch template && git merge template/main`。模板仓库本身只读。

## 2. 进程与连接

```
                    公网 / 办公网                                  内网（不对外）
┌──────────────┐                    ┌────────────────────────┐
│ 终端用户浏览器 │ ── HTTPS ────────▶ │ 业务后端 smart-closet-  │ ──▶ Agent 运行时 :8091
└──────────────┘   Cookie 会话       │ server :8080           │
                                    │ （同源托管业务前端）      │ ◀── 管理接口 /api/manage/v1
┌──────────────┐                    └────────────────────────┘       ▲  HMAC 签名
│ 管理员浏览器   │ ── HTTPS ──▶ nginx（管理前端静态文件）                  │
└──────────────┘                     │  /api/v1/** 反向代理             │
                                     ▼                                │
                          管理后端 smart-closet-admin-server :8080 ────┘
                          JWT + Refresh Cookie、RBAC、审计
```

| 进程 | 默认端口 | 谁访问它 | 对外开放 |
|---|---|---|---|
| 业务后端 | 8080 | 终端用户浏览器；管理后端（只访问 `/api/manage/**`） | 业务路径开放；`/api/manage/**` 仅内网 |
| Agent 运行时 | 8091 | 业务后端 | 否 |
| 管理后端 | 8080 | 管理前端 nginx | 只经 nginx 暴露 `/api/v1/**` |
| 管理前端 nginx | 80 | 管理员浏览器 | 是（建议限制在办公网或 VPN） |

业务前端默认构建进业务后端的 `static` 目录，与后端同源提供，不占用单独进程；按 [独立部署](INDEPENDENT_DEPLOYMENT.md) 拆分时才由 nginx 单独托管。管理前端同理，也可以交给已有的 nginx 托管，不必新起容器。

两个后端都默认使用 8080，同一台机器上运行时必须改掉其中一个。

## 3. 密钥与配置配对

| 用途 | 业务后端 | 管理后端 | 要求 |
|---|---|---|---|
| 开启管理接口 / 集成 | `CLOSET_MANAGEMENT_ENABLED=true` | `APP_CLOSET_ENABLED=true` | 两边都开启 |
| 密钥 ID | `CLOSET_MANAGEMENT_KEY_ID` | `APP_CLOSET_KEY_ID` | 相同，默认 `primary` |
| 共享密钥 | `CLOSET_MANAGEMENT_SECRET` | `APP_CLOSET_SECRET` | 相同，`openssl rand -base64 48` 生成，≥32 字节 |
| 业务后端地址 | — | `APP_CLOSET_BASE_URL` | 管理后端可达的内网地址，如 `http://server:8080` |
| Agent 密钥 | `CLOSET_AGENT_SECRET` | — | 与 Agent 运行时相同，和管理密钥无关 |

**常见错误：**

- 两边密钥不一致：管理后台所有业务页面返回 `502 CLOSET_INTEGRATION_MISCONFIGURED`，业务后端日志出现 `management request rejected code=MANAGEMENT_SIGNATURE_INVALID`。
- 业务后端未开启管理接口：管理后端收到 401，同样表现为 502。
- 业务后端宕机或地址错误：管理后台显示“鱼搭服务暂不可用”（503），后台其他功能不受影响；管理后端“运行状态”页的 `closet-service` 检查项显示异常。

管理后端还需要它自己的配置（JWT 密钥、数据库、初始管理员、CORS），见该仓库的 `docs/operations/deployment.md`。

## 4. 浏览器同源与 Cookie

管理后端的 Refresh Token 放在 HttpOnly Cookie 中，所以管理前端最好与 `/api/v1` **同源**：由 nginx 托管前端静态文件，并把 `/api/v1/` 反向代理到管理后端。

```nginx
server {
    listen 80;
    root /usr/share/nginx/html;          # smart-closet-admin 的 pnpm build 产物
    location / { try_files $uri $uri/ /index.html; }
    location /api/v1/ {
        proxy_pass http://admin-server:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

管理前端仓库自带的 `Dockerfile` 只复制静态文件，没有包含上面的代理配置，部署时需要补上这份 nginx 配置。

如果前后端不同源，需要同时满足三点：

- 管理前端构建时设置 `VITE_API_BASE_URL`；
- 管理后端的 CORS 白名单精确包含前端来源（首次建库读 `APP_SECURITY_CORS_ALLOWED_ORIGINS`，之后在后台“运行配置”中修改）；
- 生产环境 Refresh Cookie 必须是 `Secure`。

`localhost` 与 `127.0.0.1` 是不同的来源。本地开发时，按 CORS 白名单里写的那个地址访问管理前端，否则登录返回 403。

## 5. 一起部署的 Compose 示例

下面是示意配置，镜像名与卷按实际调整。业务后端使用本仓库 `compose.yaml` 中的定义，这里只列出与管理平台相关的部分。

```yaml
services:
  server:                       # 本仓库 compose.yaml 的业务后端
    environment:
      CLOSET_MANAGEMENT_ENABLED: "true"
      CLOSET_MANAGEMENT_KEY_ID: primary
      CLOSET_MANAGEMENT_SECRET: ${CLOSET_MANAGEMENT_SECRET:?generate with openssl rand -base64 48}
    # 不要把 /api/manage/** 通过公网入口暴露；只让 admin-server 在 compose 网络内访问

  admin-server:
    image: ghcr.io/<owner>/smart-closet-admin-server:${ADMIN_TAG:-latest}
    environment:
      SPRING_PROFILES_ACTIVE: postgres
      APP_CLOSET_ENABLED: "true"
      APP_CLOSET_BASE_URL: http://server:8080
      APP_CLOSET_KEY_ID: primary
      APP_CLOSET_SECRET: ${CLOSET_MANAGEMENT_SECRET}
      # 其余 JWT / 数据库 / 初始管理员变量见 smart-closet-admin-server 的 .env.example
    expose: ["8080"]

  admin-web:
    image: ghcr.io/<owner>/smart-closet-admin:${ADMIN_TAG:-latest}
    volumes:
      - ./deploy/admin-nginx.conf:/etc/nginx/conf.d/default.conf:ro   # 第 4 节的配置
    ports: ["127.0.0.1:8081:80"]
    depends_on: [admin-server]
```

## 6. 本地联调

| 进程 | 命令 | 地址 |
|---|---|---|
| 业务后端 | `CLOSET_MANAGEMENT_ENABLED=true CLOSET_MANAGEMENT_SECRET=… PORT=18080 java -jar target/smart-closet-server-*.jar` | 127.0.0.1:18080 |
| 管理后端 | 在 `.env` 中写 `APP_CLOSET_*`（`APP_CLOSET_BASE_URL=http://127.0.0.1:18080`），然后 `./mvnw spring-boot:run -Dspring-boot.run.arguments="--server.port=18090 --server.address=127.0.0.1"` | 127.0.0.1:18090 |
| 管理前端 | `VITE_API_TARGET=http://127.0.0.1:18090 pnpm dev --port 8849` | localhost:8849 |

注意：

- 管理后端会把 `.env` 当作 properties 文件导入，其中的 `SERVER_PORT` 不会映射到 `server.port`。端口要用系统环境变量或 `--server.port` 指定，否则仍会监听 8080，可能与本机已运行的业务后端冲突。
- 本机配置了 HTTP 代理时，给相关进程和 curl 加上 `NO_PROXY=127.0.0.1,localhost`。
- 不启动管理平台时，可以用 `scripts/manage-curl.sh` 直接调用管理接口，见 [管理接口接入指南](MANAGEMENT_API.md)。

## 7. 拆除

- **只停用管理平台：** 业务后端设 `CLOSET_MANAGEMENT_ENABLED=false` 并重启，然后下线管理后端与管理前端。鱼搭用户端不受影响。
- **让管理后端不再接入鱼搭：** 设 `APP_CLOSET_ENABLED=false` 并重启。已写入的“鱼搭业务”菜单不会自动删除，可在后台“菜单管理”中隐藏。
- **把鱼搭接入其他平台：** 业务后端无需改代码。对方按 [管理接口接入指南](MANAGEMENT_API.md) 实现签名客户端即可，`smart-closet-admin-server` 中的 `modules/integrations/closet` 可作为参考实现。
