> 2026-09-29：新增首步 `RECOGNIZE`、衣物档案与局部重跑。识别步骤输出结构化数据，`outputUrl` 为空。详见 [元信息识别](RECOGNITION.md)。原有图片步骤与历史接口继续兼容。

# 图片工作流

## 设计文档

[透明背景与图片工作流设计（含 Mermaid 图）](design/透明背景与图片工作流.md) 记录了可选前置抠图、检查阈值、策略和限制。

## 本版能力

使用 Spring Boot 和现有 Java 图像处理库，增加可配置的生成式 AI 步骤。没有安装额外软件或引入 Python 服务。

- 文生图：文字 → AI 生图 → 自动透明度检查 → 贴纸；有效透明图直接沿用，需要时才抠图。
- 图生图：校正照片 → 可选前置抠图（默认关闭）→ AI 生图 → 可选后置抠背景（默认开启）→ 贴纸。
- 混合：校正照片 → 抠图 → AI 处理；也可 AI → 抠图 → AI。
- 每一步只接收前一步的图片；最多 6 步、2 次生成调用，每次要求一张图。
- 原始输入和每一步输出分开存储。上传照片先应用 EXIF 和用户旋转，只保存校正后的输入。
- `SUCCEEDED` 只代表执行完成，不代表生成内容忠于原图。包含 AI 的任务标记 `requiresReview: true`，由用户对照确认；现在可对具体图片采纳/不采纳；仍无自动一致性评分。

测试页：`http://127.0.0.1:8080/workflow/index.html`，旧衣物实验室也有入口。可编辑每个步骤的完整 JSON。

### 页面操作

主界面依次显示照片、前置抠图、AI 生图、后置抠背景、贴纸。两个抠图开关独立控制；后置抠背景开启时可按需处理或强制重抠，关闭时使用 `PRESERVE`，只校验透明度，不执行分割。若图片没有可用的透明背景，会保留该图并暂停贴纸制作，供用户决定下一步。

衣物信息识别为单独选项，默认基于原照片首先执行，不改变图片步骤的输入。已完成的图片可以仅识别信息；已整理图片可以直接本地制作贴纸，不调用云端。文字生图放在入口类型中，多次 AI 处理保留在高级自定义步骤中。

“本次处理”根据实际提交步骤显示顺序与调用次数。自定义 JSON 会覆盖开关，清空或点击“恢复使用开关”即可恢复。衣物档案中的“按当前流程重新处理”复用同一配置（包括两个抠图开关和自定义步骤），剔除识别步骤，并拒绝纯识别或文字生图流程。元信息表单、继续处理和模型设置默认折叠。

### 添加衣物页面的本地模式

新添加页默认 FORCE，即使照片边角已经透明，也会实际去背景。仅主动开启“背景已经透明，直接制作贴纸”时发送 AUTO。`POST /api/closet/garments` 的 workflow 新增可选 `transparencyPolicy`（AUTO/FORCE），旧客户端缺省仍为 AUTO。Alpha 检查只判断透明结构，不能证明杯子、报纸或人物已去除；完整贴纸也可从抠图前图片重新去背景，保留原图及历史。

## 模块与边界

```text
WorkflowController → WorkflowService（校验、顺序执行、状态持久化）
                            ├─ GenerationService → OpenAI Images / Gemini
                            ├─ SegmentationService → U2Net / GrabCut
                            ├─ TransparencyService → 纯计算 Alpha 检查 / 可选分割
                            └─ StickerRenderer → 从透明图渲染描边和阴影
```

`generation/` 管协议和网络，`workflow/` 管任务编排，原有分割、旋转、贴纸模块继续独立使用。当前使用 MyBatis-Plus + H2 文件库保存四张业务表，并从数据库队列顺序领取任务。Flyway 管理 schema 版本；本地文件锁限定同一图片目录只运行一个新版工作流进程。

## 服务商配置

应用启动时可选读取项目目录 `.local/generation.yml`，此目录已被 Git 忽略。完整无密钥示例见 `generation.example.yml`。

```yaml
generation:
  providers:
    my-provider:
      protocol: OPENAI_IMAGES
      base-url: https://provider.example/v1
      api-key: ${IMAGE_API_KEY:}
      default-model: your-image-model
      timeout-seconds: 180
      defaults:
        size: 1024x1024
        quality: low
        output_format: png
      image-hosts: []
```

配置变更后重启后端。前端只传 provider ID、model、prompt、parameters；不接收任意服务商地址、认证头或密钥。提供给页面的配置只有协议、默认模型和默认参数。

| 协议 | 文生图 | 图生图 | 参数位置 |
| --- | --- | --- | --- |
| `OPENAI_IMAGES` | `POST {base-url}/images/generations` JSON | `POST {base-url}/images/edits` multipart，`image` 为 PNG | JSON 顶层 / multipart 文本字段 |
| `GEMINI` | `POST {base-url}/models/{model}:generateContent` | 同接口，增加 `inlineData` 图片 | `generationConfig` |

OpenAI base-url 例如 `https://api.openai.com/v1`；Gemini 为 `https://generativelanguage.googleapis.com/v1beta`。模型能力、尺寸、质量等合法组合由服务商决定。本版没有 Chat Completions 图片扩展、Responses 生图工具、云厂商私有异步任务 API 或多图参考/蒙版编辑适配。

OpenAI 参数为标量，例如 `size`、`quality`、`background`、`output_format`、`response_format`。Gemini 支持嵌套参数，例如 `{"imageConfig":{"aspectRatio":"1:1"}}`。禁止覆盖 model、prompt、输入图片、鉴权、地址、n、stream 等保留字段；强制单图，当前仅接收 PNG/JPEG，统一保存为 PNG。

服务商返回 `b64_json` 或 Gemini `inlineData` 时直接校验图片。若返回图片 URL，需要管理员在 `image-hosts` 配置精确、可信的 CDN 域名；只接受 HTTPS/443、检查非内网地址、不携带密钥、不跟随重定向。默认拒绝所有 URL 下载。不要配置用户可控制解析的域名；生产环境还应使用固定出口策略。

## 接口

### 查询服务商和模型（不触发生图）

```text
GET /api/generation/providers
GET /api/generation/providers/{providerId}/models
```

模型列表透传 ID 并用名称标记 `imageCandidate`；它不是能力验证或可用性保证。Gemini 列表当前只返回第一页面。

### 提交文生图

`POST /api/workflows`，JSON；返回 HTTP 202，`Location` 指向任务查询地址。

```json
{
  "steps": [
    {
      "type": "GENERATE",
      "mode": "TEXT_TO_IMAGE",
      "generation": {
        "provider": "yumg",
        "model": "gpt-image-2.5-flare",
        "prompt": "一件纯蓝色短袖 T 恤，完整平铺，正上方视角，纯白背景，不出现人物和文字。",
        "parameters": {"size": "1024x1024", "quality": "low", "output_format": "png"}
      }
    },
    {"type": "ENSURE_TRANSPARENCY", "policy": "AUTO", "method": "U2NET"},
    {"type": "STICKER"}
  ]
}
```

### 上传照片并图生图

同一路径，`multipart/form-data`：

- `file`：JPEG/PNG，最大 15 MB。
- `workflow`：**Content-Type 必须为 application/json**，内容示例：

```json
{
  "rotationDegrees": 90,
  "steps": [
    {
      "type": "GENERATE",
      "mode": "IMAGE_TO_IMAGE",
      "generation": {
        "provider": "yumg",
        "model": "gpt-image-2.5-flare",
        "prompt": "只将衣物外部背景替换为纯白背景。保留原姿态、视角、轮廓、褶皱、颜色、纹理，以及每处印花和文字的内容、数量、大小和位置。不要重新平铺、拉直、对称化或补画被遮挡的细节，不裁切衣物。",
        "parameters": {"size": "1024x1024", "quality": "low", "output_format": "png"}
      }
    },
    {"type": "ENSURE_TRANSPARENCY", "policy": "AUTO", "method": "U2NET"},
    {"type": "STICKER"}
  ]
}
```

JavaScript 提交方式：

```js
const body = new FormData();
body.append('workflow', new Blob([JSON.stringify(spec)], {type: 'application/json'}));
body.append('file', file);
await fetch('/api/workflows', {
  method: 'POST',
  headers: {'Idempotency-Key': crypto.randomUUID()},
  body
});
```

复用历史图片时，可在 JSON 请求中传 `sourceSegmentationId` 和 `sourceImage: "ORIGINAL" | "CUTOUT"`，与 `file` 二选一。默认 ORIGINAL。CUTOUT 保留透明度，不接受入口旋转；需要修正方向时使用 ORIGINAL。

规则：TEXT_TO_IMAGE 只能位于无输入图的开头；IMAGE_TO_IMAGE / SEGMENT / ENSURE_TRANSPARENCY 必须已有图片；STICKER 接受前一步 ENSURE_TRANSPARENCY 或 SEGMENT 输出，也可直接接历史 CUTOUT 输入。STICKER 自身再次检查透明度，不符合基础规则时停止。纯本地流程推荐 `[ENSURE_TRANSPARENCY, STICKER]`，不会连接服务商。旧 `[SEGMENT, STICKER]` 仍可提交。

ENSURE_TRANSPARENCY 支持 `policy: AUTO | FORCE | PRESERVE`，默认 AUTO；method 默认 U2NET。AUTO 保留通过检查的 Alpha，仅在没有有效透明背景时抠图；已有透明度但结构可疑时进入 NEEDS_REVIEW。FORCE 明确要求重抠，PRESERVE 只检查。全透明或没有有效主体时 FAILED。判断是结构启发式，不保证背景清除或衣物保真。

前置抠图开关仅在图生图流程出现，默认关闭；开启后在第一步 GENERATE 前插入 SEGMENT。预抠图可能损伤衣物，输入图和结果都会保留。上传和旋转现在保留已有 Alpha，旧分割接口的 RGB 解码行为保持兼容。

### 查询与图片

```text
GET /api/workflows/{id}
GET /api/workflows/{id}/files/input.png
GET /api/workflows/{id}/files/step-0.png
```

任务包含 `request`（已解析的模型/参数）、`status`、`steps`、`inputUrl`、`outputUrl`、`requiresReview`、`error`。步骤有开始/结束时间、状态、输入/输出 URL、可选 segmentationId 和服务商 usage。透明度检查步骤额外包含 transparency（policy、action、before、after），其中包含规则版本、比例、连通区域与原因。`outputUrl` 在失败或待检查时仍可能指向最后成功的步骤或需要检查的候选图，必须同时检查 status。

## 任务管理与图片采纳

页面新增任务历史、状态筛选、分页、取消、图片采纳和“用这张继续处理”。提交后表单即可处理下一张图片，关闭页面不取消后台任务。

```text
GET  /api/workflows?page=1&size=12&status=SUCCEEDED
POST /api/workflows/{id}/cancel
POST /api/workflows/{id}/continue
POST /api/workflows/{id}/assets/{assetId}/review
```

continue 必须带 UUID `Idempotency-Key`，请求例如：

```json
{"assetId":"图片资产 UUID","policy":"FORCE","method":"GRABCUT"}
```

此接口只派生 `[ENSURE_TRANSPARENCY, STICKER]` 本地任务，不调用生图。返回新任务和 `parentJobId`，旧任务、图片及其失败原因保留。可选 AUTO/FORCE/PRESERVE 和 U2NET/GRABCUT；不等于从任意步骤恢复原工作流，也不会重跑未完成的 GENERATE。

review 请求例如 `{"decision":"APPROVED","note":"已核对颜色与印花"}`。支持 APPROVED/REJECTED/PENDING，只保存当前验收决定。APPROVED 将该图设置为任务的 `selectedAssetId`；拒绝或重置当前采纳图会取消采纳。不能采纳输入原图或其他任务的图片。

任务详情额外返回 `assets`（哈希、尺寸、验收状态）、`attempts`（每次生图的状态、服务商标识与 usage）、`selectedAssetId`、`parentJobId`。技术上的 SUCCEEDED 与用户采纳相互独立。

## 状态、幂等与成本

- 一个 worker 顺序执行，默认最多等待 30 项，`workflow.max-queued` 可配置。队列满返回 429；同 key 查询既有任务不受队列上限影响。
- 建议每次提交使用 UUID `Idempotency-Key`。同 key 同内容复用任务，不同内容返回 409。新请求对 JSON 对象键排序后计算指纹，仍需保留原始上传字节及空值/缺省约定；旧任务指纹继续兼容。
- 模型、提示词及合并参数提交时冻结，执行时不再合并最新默认参数；协议或端点变化会阻止旧任务调用。
- QUEUED → RUNNING → SUCCEEDED / FAILED / NEEDS_REVIEW / UNKNOWN。取消排队直接 CANCELLED；执行中先 CANCEL_REQUESTED，当前步骤返回后停止后续执行。远程已发生的调用不承诺撤销或退款，迟到图片仍保留。
- 远程 5xx、读超时、连接中断等可能已受理的错误记 UNKNOWN，不自动重新提交。usage 缺失表示未提供，不等于免费。
- 重启后 QUEUED 继续执行；已发送的云端调用没有可恢复结果时进入 UNKNOWN；本地未完成步骤可恢复。输出图片与回执已落盘但数据库未提交时，通过哈希校验恢复引用。
- 超时默认 180 秒、可配 1～600 秒，连接超时 15 秒。响应体最多 32 MB，图片下载最多 20 MB，像素仍受上限约束。
- 当前只记录 usage，不估算金额或实现预算。单图、并发和步骤限制不等于余额上限。

## 数据库、文件与迁移

- 四张业务表：`workflow_job`、`workflow_step`、`generation_attempt`、`image_asset`。Flyway 另有一张内部 schema 历史表。
- 默认数据库 `jdbc:h2:file:./data/db/closet`，随 `closet.storage-dir` 调整。可通过 `CLOSET_DB_URL`、`CLOSET_DB_USER`、`CLOSET_DB_PASSWORD` 覆盖连接。
- 图片继续位于 `data/workflows/{id}/`；新任务以数据库为状态来源，不再写 job.json。步骤回执用于恢复文件/数据库提交间隙，不是第二套任务状态。
- 首次启动自动导入旧 job.json，保留旧 UUID、图片 URL 和 JSON 原件，不自动重放旧云端调用。损坏项在日志报告，缺失图片不注册成可用资产；不会伪造旧供应商调用记录。
- 首次升级前停止旧服务并备份 data 目录，避免旧程序与新版同时写入。旧版没有新版的文件锁，不能靠该锁保护跨版本并行运行。
- 使用应用 UUID、毫秒时间、字符串枚举、文本 JSON、MyBatis-Plus 分页和通用条件更新。目前已实现并验证 H2；SQLite/PostgreSQL/MySQL 需要相应 JDBC 驱动、数据库迁移目录和实库测试，不能只改 URL 就宣称兼容。
- 迁移目录默认 `classpath:db/migration/h2`，可用 `workflow.migration-location` 指定数据库对应版本脚本。不要修改已经执行的迁移，后续变更追加版本。
- 备份时停止写入，将 H2 文件和图片目录一起备份。当前不自动清理孤立文件，不提供资产物理删除或审计历史。
- 保持本机 127.0.0.1 监听；没有账户权限隔离、跨机器调度、自动供应商对账或远程取消适配。不要直接把此版本公开到互联网。

## 验证范围

Alpha 检查覆盖原样复用、按需分割、异常停止、透明上传和旋转；执行 `node src/test/js/workflow-presets.test.cjs` 可验证预抠图开关与预设顺序。OpenAI JSON、生图编辑 multipart、Gemini 文本与图片协议使用本机 HTTP 服务模拟验证；覆盖透明 PNG、参数、错误脱敏、超时、大小限制、URL 拒绝。工作流通过真实 Spring HTTP 验证串联、旋转、幂等和失败终止；另测队列、取消竞争、同键并发、重启恢复、输出回执、参数冻结及“继续处理不会增加生图调用次数”。页面交互检查另运行 `node src/test/js/workflow-management.test.cjs`。

服务商实测结果独立见 `../reports/generation/provider-check.md`。**模拟测试成功不等于服务商生图成功。**

规范来源：[OpenAI Images](https://developers.openai.com/api/docs/guides/image-generation)、[Gemini generateContent 图片生成](https://ai.google.dev/gemini-api/docs/generate-content/image-generation)。

## 编辑目标与衣物一致性

- 测试页默认“照片 → AI 整理 → 透明检查 → 贴纸”，编辑目标默认为衣物整理：只保留画面中央的主体衣物，移除人物/人体局部、人台、非主体和叠放衣物，摆正为正上方视角、适度舒展和去皱、清背景，同时严格保留原款式、颜色、印花、内衬、口袋、领口、门襟、拉链、缝线和扣子。只清理背景保留为可选目标；同样要求移除人物和非主体衣物，但不改变主体姿态。
- 姿态变换会重绘衣物，折叠下的图案无法从单张照片可靠恢复。当前提示词限制整理幅度，看不清的部分沿用原状，不强制重建正面版型。
- 文生图使用独立描述，不依赖不存在的参考照片。各目标保留页面内的提示词草稿，刷新页面会重置；服务器按请求中的 prompt 执行并保存，不暗中追加提示词。
- 混合流程第一次使用用户的处理要求，第二次默认只清理背景，避免重复要求重新平铺。两次 AI 编辑仍可能累积偏差，可通过完整 JSON 分别调整。自定义 JSON 始终覆盖页面预设。
- 前置抠图默认关闭；开启后，模型只收到前一步抠图，无法恢复已误删的信息，也没有原照片作为第二张参考。
- 所有成功生成的图片都标为候选图，即使后续透明检查失败也显示人工对照提示。Alpha 检查不判断印花、颜色或款式一致性，不能作为保真验收。
- 要求忠实记录时使用本地分割与贴纸流程，并检查分割是否误删。生成效果需要用户对照原图确认，此次提示词修正尚未通过新一轮远程生图验证。
- 未向未知上游统一强加 `input_fidelity` 等模型专属参数；`quality` 不等同于输入保真度。确认模型支持后可在参数中显式设置。

提示词写法参考 [OpenAI Image prompting](https://developers.openai.com/api/docs/guides/image-prompting)：限定修改范围并明确必须保留的内容。兼容服务商实际模型行为仍需单独验证。

### 当前整理提示词 · v5

请整理上传照片中画面中央、作为拍摄主体的那一件衣物，用于个人数字衣柜展示。无论输入中是否有人、人体局部、模特、人台、多件衣物、叠放衣物或夹带衣物，成图只能留下这唯一一件主体衣物：彻底删除人物、脸、手、脚、身体任何部位、模特、人台以及全部非主体衣物、配饰、床单和杂物；不要把衣物穿在人体或人台上，也不要输出第二件衣物。将主体衣物摆正、居中，调整为自然的正上方平铺拍摄角度；衣物开口、领口或腰头朝上，衣摆或裤脚朝下。仅适度减轻不属于衣物结构的随机褶皱，保留自然的布料质感。以原图为准，严格保留主体衣服原有的颜色、款式、比例、内衬、领口、翻领、袖口、门襟、口袋、标签、拉链、缝线，以及全部扣子的数量、形状、颜色、位置和扣合状态；逐一保留印花和文字的样式与相对位置。领口、翻领、门襟、口袋、袖口和缝线附近原本合理的结构褶皱、折痕和造型必须保留，不能为了去皱而抹平、改形或补画。整理幅度以不改变这些特征为限，看不清的部分沿用原状。背景替换为均匀纯白。完整保留衣物边缘，四周留出空白，不裁切衣物。输出同一件衣服整理后的照片。

此次仅调整默认提示词和页面措辞，模型与参数不变。v5 在单件主体、去人和正上方构图的基础上，新增对内衬、口袋、领口/翻领、门襟、拉链、缝线、扣子和结构褶皱的严格保留要求；仅允许减轻非结构性的随机褶皱。它不能让本地 U2Net 获得衣物/人物识别能力，也不能保证远端生成模型对遮挡衣物的重建保真；实际效果仍待对照验证。
