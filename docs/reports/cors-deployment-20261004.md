# 独立部署与跨域验收记录

2026-10-04，使用隔离 H2/storage、原生 Nginx、Chrome 与真实 Spring Security 验收。前端为 `https://app.closet.test:18443`，API 为 `https://api.closet.test:18443`，通过 Chrome host resolver 映射到本机。仅忽略测试证书错误，没有关闭浏览器同源策略，也没有 mock 浏览器到 Java 的请求。

## 自动检查

- `mvn -q verify`：174 项，0 失败，0 错误，7 项按现有条件跳过。
- 前端 `npm test`：45/46 通过；新增 API 地址测试 3/3 通过。原有 `tests/outfit.test.ts:61` 的摆放间距断言失败，已从 HEAD 提取原测试及原模块在临时目录复现同一失败。
- `npm run typecheck`、`build:standalone`、`build:server` 通过。
- `docker compose --env-file .env.example config --quiet` 通过。
- 生产 Nginx 样例替换为测试证书路径后，`nginx -t` 通过。

## 浏览器结果

| 场景 | 结果 |
| --- | --- |
| 跨子域登录、首次改密、刷新及后端重启恢复会话 | 登录与 `/api/auth/me` 200，业务页面可用 |
| Cookie | API 主机专属，Session 为 Secure/HttpOnly/Lax，CSRF 为 Secure/Lax |
| 单件 multipart 上传 | 202，进入真实任务和衣物详情，原图可加载；后续本地抠图因无测试模型未完成 |
| 批量 XHR 创建、追加第二张照片 | 202 / 200，两张 API 预览均加载成功 |
| 私有衣物加入画板 | 成功，Canvas `getImageData` 与 PNG 导出可用，已检查下载的 PNG 包含测试图形 |
| 助手 POST SSE | 首段 72 ms，完成 785 ms，间隔 713 ms；助手界面发送消息同样完成 |
| 便携链接兑换、撤销 | 兑换后 200 且 portableLogin=true，撤销 204，原授权随后返回可读的 401 |
| 他人私有图片 | 404 |
| 无效 CSRF | 可读的 403 |
| 退出登录 | 204，随后 `/api/auth/me` 401 |
| Vite 同源代理 | 实际登录成功，`/api/auth/me` 200 |
| 后端托管同源生产页面 | 页面、会话与图片可用；运行时 index 和其引用的 6 个 assets 与已发布文件逐字节一致 |
| 深链 | 衣物详情、搭配、批次、衣柜直接导航与刷新成功 |

验收发现并修复了便携登录过滤器先于 CORS 返回错误的问题；新增集成测试保证授权撤销后的 401 仍包含 CORS 响应头。

## 验证边界

Docker daemon 当时未运行，因此完成了 Compose 配置解析，未运行容器镜像。代理来源为原生 Nginx 的回环地址，正式 Docker 网关仍需按部署说明配置并在服务器验证。Agent 使用分段、延迟发送的本地 runtime stub；未调用付费 AI。未安装测试抠图模型，单件上传验证不代表抠图算法完成。

验收过程中观察到现有助手 SVG 动画的 `matrix(NaN,...)` 控制台提示；此次没有修改动画。其余预期 401/403/404 已按业务权限核对，不作为跨域网络失败。

本机临时证据位于 `target/cors-browser/` 与 `.playwright-cli/`，不纳入 Git；执行 Maven clean 会删除 target 证据。验收进程结束后未改变原有 8080/8091 服务或现有数据库。
