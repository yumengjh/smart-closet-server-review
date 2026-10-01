# 全局个人助手

## 启动

需要 Java 21、Node.js 22+ 和 npm。模型的上游地址、密钥沿用 `.local/generation.yml` 中 `generation.providers.yumg`，浏览器无法读取这两个字段。

```bash
./scripts/start-with-agent.sh
```

脚本创建权限为 600 的 `.local/agent-secret`，为 Spring 与 Node 提供相同的内部共享密钥。Node 默认只监听 `127.0.0.1:8091`；Spring 默认 `127.0.0.1:8080`。退出脚本会停止它创建的 Node 进程。

也可先 `mvn -DskipTests package`，再运行：

```bash
CLOSET_SERVER_JAR=target/smart-closet-server-0.0.1-SNAPSHOT.jar ./scripts/start-with-agent.sh
```

请以实际生成的 JAR 文件名为准。修改 Spring 端口时，例如 `PORT=8081`，脚本会同步修改 Node 回调地址。单独运行两个进程时须自行配置下列变量。

| 变量 | 默认值 / 作用 |
| --- | --- |
| CLOSET_AGENT_SECRET | 至少 32 字符，Spring 与 Node 相同，不能提交 Git |
| CLOSET_AGENT_PORT | Node 端口，8091 |
| CLOSET_AGENT_RUNTIME_URL | Spring 访问 Node 的地址 |
| CLOSET_AGENT_CALLBACK_URL | Node 访问 Spring 的地址，8080 |
| CLOSET_AGENT_MODEL | Agent 默认 gpt-6-luna |
| CLOSET_RECOGNITION_MODEL | 识图默认 gpt-6-luna |
| CLOSET_AGENT_DAILY_LIMIT | 每人每天 30 条消息 |

`agent.allowed-models` 是后端维护的模型白名单。个人默认模型在设置页保存；会话模型仅覆盖当前对话。服务未配置或内部健康检查失败时不会提交消息或扣助手额度。

2026-10-01 的现有上游模型目录返回 HTTP 200，26 个模型，包含 gpt-6-luna；因此两个默认均使用它。如以后该上游移除它，将两项模型变量都改为原识图模型 gpt-5.6-luna，并相应调整白名单。推理失败不会静默切换模型或重试付费请求。

## 数据流与权限

浏览器 POST `/api/agent/sessions/{id}/messages` → Spring 身份、CSRF、归属与额度检查 → Node LangGraph `assistant → tools → assistant` → Spring 每轮受限工具 → SSE 返回浏览器。

Node 不连接衣橱数据库。每轮获得短期随机工具授权，仅对应该账号、会话、消息、超时和最多 8 次调用。内部接口只豁免该路径的 CSRF，仍校验授权；用户停用或需要重新改密后立即拒绝工具访问。Node `/run` 另校验共享密钥，应保持本机监听。

客户端每条消息带 UUID；重复提交不会二次扣额度。网络中断时查看历史并主动决定是否重试。当前会话一次只能执行一轮；助手额度与图片任务额度分开，删除聊天不会重置额度。

H2 的 V6 迁移新增会话、消息、模型偏好、独立记忆、每日额度和操作建议表。列表加载最近 100 个会话、单会话最近 100 条消息；本轮模型最多读取最近 20 条。长期记忆独立保存，最多 100 条，不从删除的聊天重新推断记忆。

## 上下文与搭配

每条消息读取实时页面及画布快照，包括选中项、未保存元素、搭配版本和名称。发给模型的副本剔除图片地址与私人尺码字段；图片只有勾选“发送当前衣物图片到模型”时才发送，本轮授权发送后重置。

查询与搭配按账号隔离。搭配建议必须包含三套不同的真实 ACTIVE 衣物组合；“这件、这些、选中”指令要求包含当前及选中衣物。库存不足时助手解释原因。候选卡片展示真实贴纸预览，不直接改变画布。

应用前需用户确认；当前画布的页面、选中项、版本、内容必须与建议生成时完全相同，否则返回 409。新建候选写入独立草稿槽，不覆盖原有新建草稿。编辑沿用撤销栈；应用后仍是草稿。保存需再次确认，并使用原有 outfit revision 防止覆盖并发变更。建议只能确认一次，历史恢复后保留处理状态。

## 帮助与记忆

`src/main/resources/help/*.md` 是帮助页和检索的唯一内容来源。部署新 JAR 即更新文章；文件名同时是稳定文章 ID，已有链接引用后尽量保持文件名。正文用一级标题、二级标题和段落即可。

帮助页 `/help` 与助手来源链接共享内容。检索使用中文双字与词项匹配，最多返回 3 篇；未命中须明确“暂时不知道”。首期没有联网、天气查询或反馈提交接口。

风格、颜色和场景偏好仅从当前用户明确原话提取，界面提示并可撤销。尺码、身材须勾选本轮保存许可。设置页支持查看、编辑、删除和手动添加记忆；删除聊天永远不会删除记忆。

## API

所有公开接口使用现有登录 Cookie；写接口使用 X-XSRF-TOKEN。

| 路径（均以 /api/agent 开头） | 方法 | 用途 |
| --- | --- | --- |
| /models、/quota、/help | GET | 白名单与配置状态、额度、帮助 |
| /preferences | GET、PUT | 个人默认模型 |
| /sessions | GET、POST、DELETE | 会话列表、新建、清空聊天 |
| /sessions/{id} | PUT、DELETE | 修改标题或会话模型、删除 |
| /sessions/{id}/messages | GET、POST | 历史、POST SSE 回复 |
| /memories | GET、POST | 记忆列表、手动添加 |
| /memories/{id} | PUT、DELETE | 修改、删除 |
| /proposals/{id}/accept | POST | 校验上下文与一次性用户确认 |
| /internal/tool | POST | Node 专用 Bearer 临时授权 |

SSE 事件：`status`、`token`、`sources`、`navigation`、`proposal`、`memory`、`done`、`error`。候选确认接口本身不写组合，前端应用或调用原有组合保存接口。

## 生产进程示例

同机两个独立 systemd 服务即可，无需 Docker。创建仅服务用户可读的 `/etc/smart-closet/agent.env`，含共享密钥与回调 URL，保持生成上游配置在后端工作目录的 `.local/generation.yml`。

```ini
# smart-closet-agent.service
[Unit]
Description=Smart Closet LangGraph Runtime
After=network.target
[Service]
User=smartcloset
WorkingDirectory=/opt/smart-closet
EnvironmentFile=/etc/smart-closet/agent.env
ExecStart=/usr/bin/node agent-runtime/dist/server.js
Restart=on-failure
[Install]
WantedBy=multi-user.target
```

Spring 服务使用相同 EnvironmentFile，`ExecStart=/usr/bin/java -jar /opt/smart-closet/app.jar`，`After=smart-closet-agent.service`。构建时执行 `npm ci && npm run build`（agent-runtime 目录）。反向代理 SSE 路径须关闭 buffering，读超时大于 120 秒。

## 验证

2026-10-01 的结果和界面截图见 [验证报告](../reports/agent/README.md)。

常规测试全部使用模拟模型，不产生上游费用：

```bash
mvn test
npm --prefix agent-runtime test
npm --prefix agent-runtime run build
# 前端仓库
npm test
npm run build:server
```

真实模型兼容性检查必须显式设置 `CLOSET_AGENT_LIVE_VERIFY=true`、`CLOSET_CHECK_BASE_URL`、`CLOSET_CHECK_API_KEY`；模型由 CLOSET_AGENT_MODEL 指定。Agent 检查 `npx tsx src/model-check.ts` 仅一条强制工具调用并观察流，不执行平台工具；识图 `mvn -Dtest=RecognitionModelLiveCheckTest test` 仅一张公开 samples/white-shirt-light.jpg，通过平台 garment-v2 全字段校验。会产生实际费用，未设置开关时跳过。

框架结构参考 [LangGraph JS 官方文档](https://docs.langchain.com/oss/javascript/langgraph/thinking-in-langgraph)。
