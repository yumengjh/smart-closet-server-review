# 前端 Nginx 与后端独立部署

前端示例为 `https://closet.example.com`，API 为 `https://api.example.com`。替换为同一主域下的两个 HTTPS 子域名，均从域名根路径提供服务。浏览器直接访问 API 子域名，前端 Nginx 只提供静态文件。

## 1. 前端构建

在相邻 `smart-closet` 仓库操作：

```bash
cp .env.example .env.production.local
# 填写 VITE_API_BASE_URL=https://api.example.com
npm ci
npm run build:standalone
```

将整个 `dist/` 上传到 Nginx 的 `/srv/smart-closet/current`。构建会检查 API 地址为 HTTPS Origin、禁止携带路径或凭据，并关闭 mocks。`VITE_*` 是浏览器可读取的公开配置；后端密钥不要放入前端环境文件。更改 API 域名需要重新构建与发布静态文件。

既有同源方式继续使用 `npm run build:server`，该命令强制清空 API 基地址并发布到后端静态目录，同时准备后端人物贴纸资源。独立构建只生成 `dist/`；后端仍需保留已发布的人物贴纸资源。现有后端镜像构建及 workflow/studio 页面继续使用原流程。禁止手工修改压缩产物。

## 2. 后端环境变量

在后端仓库操作，将脱敏模板复制到独立的受限配置目录：

```bash
install -d -m 700 /etc/smart-closet
install -m 600 .env.example /etc/smart-closet/server.env
# 编辑模板：域名、随机 Agent 密钥、管理员初始密码、AI 供应商配置。
docker compose --env-file /etc/smart-closet/server.env config --quiet
docker compose --env-file /etc/smart-closet/server.env up -d --build
```

`--env-file` 用于 Compose 插值；`compose.yaml` 显式将配置传入 Java 或 Agent 容器。Java 本身不自动读取 dotenv，也不执行 dotenv 内的 shell 内容。模型仍放在 `models/u2net.onnx`，数据库、会话及用户图片保存在现有 `closet-data` 命名卷；不要删除或重新创建数据卷。

| 变量 | 含义 |
| --- | --- |
| `CLOSET_CORS_ALLOWED_ORIGINS` | 精确前端 Origin，逗号分隔，无尾斜杠、路径、通配符；本地同源可留空 |
| `CLOSET_COOKIE_SECURE` | HTTPS 生产填 `true`；本地 HTTP 填 `false`；省略或留空时按请求的 HTTPS 状态自动判断 |
| `CLOSET_HOST_PORT` | 宿主机回环端口，默认 8080，需要与 Nginx upstream 一致 |
| `CLOSET_TRUSTED_PROXIES` | Tomcat 信任的直接代理来源正则，仅配置实际来源 |
| `CLOSET_DB_URL/USER/PASSWORD` | 数据源设置，默认 H2 文件位于容器 `/data/db/closet` |
| `CLOSET_SESSION_TIMEOUT` / `CLOSET_SESSION_COOKIE_MAX_AGE` | 默认均为 7 天；便携会话仍由后端限制为 24 小时 |
| `CLOSET_YUMG_BASE_URL/API_KEY/PROTOCOL/DEFAULT_MODEL` | 默认供应商的 API 基地址、密钥、生图协议和默认生图模型 |
| `CLOSET_YUMG_DEFAULTS_JSON` | 生图默认参数 JSON 对象，提供后替换该供应商原 YAML defaults；`{}` 清空默认参数 |
| `CLOSET_YUMG_IMAGE_HOSTS/TIMEOUT_SECONDS` | 允许下载生成图片的主机列表、生图超时秒数（1–600） |
| `CLOSET_RECOGNITION_MODEL` | 默认识别模型 |
| `CLOSET_AGENT_MODEL/ALLOWED_MODELS` | 助手默认模型与允许模型列表；默认模型必须在列表内 |
| `CLOSET_AGENT_DAILY_LIMIT/MAX_TOOL_CALLS/TIMEOUT_SECONDS` | 助手每日额度、工具次数及超时 |

供应商 `PROTOCOL` 支持 `OPENAI_IMAGES` 或 `GEMINI`；识别和助手仍使用现有 OpenAI Chat 调用协议及共用凭据。`BASE_URL` 包含服务所需的 `/v1` 等前缀，不填写具体 `/chat/completions` 或 `/images/generations` 端点。生图默认模型填写供应商实际支持的模型名。

显式环境配置覆盖已导入的 `.local/generation.yml`，没有设置的字段保留 YAML/defaults；环境字段留空表示不覆盖。其他供应商继续用 YAML 配置。只设置密钥而不提供有效地址会阻止启动；URL 和密钥都未设置时可以运行非 AI 功能。AI 密钥只传入 Java，Agent 使用现有内部授权通道。

修改环境文件后重新创建容器，单纯 `docker restart` 不会重新注入环境变量：

```bash
docker compose --env-file /etc/smart-closet/server.env up -d --force-recreate server agent-runtime
```

## 3. Nginx 与代理可信来源

复制 [Nginx 样例](../deploy/nginx/closet.conf.example)，替换两个域名、证书路径、静态目录及后端端口。前端使用 history 路由回退；`index.html` 重新验证，哈希 assets 长期缓存。后端入口允许 64 MiB 请求、关闭 SSE 缓冲、读取超时 360 秒。CORS 由 Spring Security 提供，Nginx 不再添加第二组 CORS 响应头。内部 Agent 回调路径在公网 Nginx 返回 404。

Compose 只将 Java 端口发布到 `127.0.0.1`，Agent 不发布端口。宿主机 Nginx 进入 Docker 后，Tomcat 看到的直接来源可能是 Docker 网关，不能假定仍为 `127.0.0.1`。查看网关：

```bash
docker inspect -f '{{range .NetworkSettings.Networks}}{{println .Gateway}}{{end}}' \
  "$(docker compose --env-file /etc/smart-closet/server.env ps -q server)"
```

将实际网关作为精确 IP 正则加入 `CLOSET_TRUSTED_PROXIES`，例如网关确实为 `172.20.0.1` 时：

```dotenv
CLOSET_TRUSTED_PROXIES='127\.0\.0\.1|::1|0:0:0:0:0:0:0:1|172\.20\.0\.1'
```

不要将所有来源设为可信。Nginx 样例覆盖 `X-Forwarded-*` 并移除客户端的 `Forwarded`；正确识别 HTTPS 是便携登录可用的必要条件。网络重建后重新检查网关，再验证登录。配置检查通过后按服务器现有方式 reload Nginx。

## 4. 验收与回退

- 用真实浏览器验证登录、首次改密、刷新保留登录、退出失效、便携链接兑换及撤销；Cookie 应为后端主机专属、Secure、SameSite=Lax，Session 另有 HttpOnly。
- 验证单件及批量上传、衣物和任务图片、原图链接、人物贴纸、画板 PNG 导出及助手逐步流出。图片能显示不足以证明画板能读像素或导出。
- 检查允许 Origin 的预检无需会话即可成功，错误 Origin/请求头被拒绝；401/403 响应对允许的前端仍可读取。无效 CSRF 仍应返回 403。
- 刷新 `/wardrobe`、衣物详情和搭配深链；请求必须进入 API 域名，前端域名的 `/api/` 不作反向代理。
- 本地 `npm run dev` 保留 Vite 同源代理；`build:server` 继续提供后端同源界面。HTTP 本地配置使用空 Origin 白名单、关闭 Secure 或保留自动判断。
- 发布前保留前后端上一版本产物与环境配置。回退使用完整旧前端目录及匹配后端镜像，不删除数据库或数据卷。本次没有数据库迁移。
