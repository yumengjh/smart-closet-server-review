# 衣物档案与元信息识别

## 使用

打开 `/workflow/index.html`，默认上传流程为：

```text
保存原照片与衣物草稿 → RECOGNIZE → GENERATE → ENSURE_TRANSPARENCY → STICKER
```

页面默认只需选图并提交。高级设置中可以替换识图模型、调整图片处理或关闭识别。“保留图片 · 仅识别信息”仅执行 RECOGNIZE，保留上传图为展示图。纯文生图预设不自动识别；“本地透明检查 → 贴纸”始终只做本地处理，不发送识图请求。

每次新上传建立一个稳定 `garmentId`，在 `job.request.garmentId` 返回。重新识别、重新整理、继续本地后处理均关联同一件衣物。旧版任务保持不变，不会批量推断或回填衣物；旧图片可通过重新上传进入新流程。

## 输入、输出与边界

- RECOGNIZE 最多一个，位于流程开头。它只读本次保存的校正输入照片，不读取后续生图、抠图或贴纸。
- 重新识别和重新整理从该衣物保存的来源资产重新派生，先校验文件哈希；不把当前展示图自动当成原图。
- 识图传输时合成白底、等比缩到最长 1600 像素，避免透明区域被当成黑色并限制图像输入大小；原始资产保持不变。这不是标定实物颜色，小衣标需要独立清晰照片，当前版本不合并多图。
- 每个工作流仍最多 6 步、2 次生图，另可有 1 次识别。混合双生图加前置抠图时会超出 6 步，需要关闭前置抠图或减少生图步骤。
- 识别结果在 `steps[i].recognition`，原始 JSON 在 `rawRecognition`；该步骤 `outputUrl=null`，不创建伪图片资产，也不改变当前图片流。
- `USER_UPLOAD` 表示用户声明的来源，不保证是实拍；`GENERATED` 仅描述生成图，不能作为实物属性证明；来源不明用 `UNKNOWN`。通过衣物来源资产保持派生关系，用户从系统外上传的图片无法自动鉴别真伪。

### 状态

`assessment`：READY / PARTIAL / NO_GARMENT / MULTIPLE_GARMENTS / UNUSABLE。

- 后三种目标问题暂停后续图片处理，任务进入 NEEDS_REVIEW，所有属性必须为空。问题和补拍建议保留在步骤结果。
- PARTIAL 保留可识别字段并继续图片处理，最终需要检查。
- 上游失败、超时、拒答、截断、结构/语义校验失败，只让识别步骤失败，后续图片处理继续；最终 NEEDS_REVIEW。旧的有效元信息不清空。
- 识别先运行，因此后续生图失败不会丢失已经保存的信息。图片处理失败仍按现有图片工作流规则停止依赖它的后续步骤。
- 已发送但结果未知的识别调用不自动重放。重启后，有合法识别回执时恢复数据；无回执时标 UNKNOWN，继续未执行的图片步骤。

“模型成功返回”“有可用字段”“用户修改确认”是不同状态，系统不把观察标签当成准确率。

## 字段与提示词

字段定义：`RecognitionSchema.FIELDS`；完整 Schema 可从 `GET /api/recognition/schema` 获取。

16 个字段：品类、主色、辅色、图案、领型、袖长、衣物长度、门襟、表面纹理、风格、季节、品牌、尺码、成分、洗护、可见文字。

所有字段采用 `values` 数组；单值字段最多一个，多值字段最多十二个。枚举值用于稳定筛选；品牌等自由文本最多 400 字。未知、不适用、冲突的数组为空，不能以编造值填满。

状态：OBSERVED / INFERRED / UNKNOWN / NOT_APPLICABLE / CONFLICT。风格与季节有值时必须为 INFERRED；其余字段第一版只允许有直接依据的值。每项保留简短 `evidence`。品牌、尺码、成分、洗护需衣标证据；胸前文字不自动等于品牌。服务端验证字段集合、枚举、数量、空值语义、建议状态及下装与领袖属性冲突；这些规则不能保证视觉判断本身正确。

提示词文件：`src/main/resources/recognition/garment-v1.txt`。提交时冻结提示词全文及 SHA-256、Schema 版本、Schema、模型和服务商配置指纹。调整提示词不改变已排队任务。当前只支持 garment-v1，不跨版本自动迁移字段含义。

## 模型配置

识图配置与生图模型独立，通过 `credential-provider` 复用已有服务端地址和密钥，不向浏览器发送凭据。

```yaml
recognition:
  default-provider: yumg
  providers:
    yumg:
      credential-provider: yumg
      protocol: OPENAI_CHAT
      default-model: gpt-5.6-luna
      max-output-tokens: 4096
      reasoning-effort: low
    # google:
    #   credential-provider: google
    #   protocol: GEMINI
    #   default-model: your-vision-model
    #   max-output-tokens: 4096
```

OPENAI_CHAT 使用 `/chat/completions` 的图像输入与严格 `json_schema`；GEMINI 使用 `generateContent` 的文本/图像输入与 `responseJsonSchema`。不自动从结构化输出降级为自由文本，不自动切换到更贵模型。服务商地址或关键参数改变会阻止旧任务调用。官方协议参考：[OpenAI Structured Outputs](https://developers.openai.com/api/docs/guides/structured-outputs)、[Gemini Structured Outputs](https://ai.google.dev/gemini-api/docs/generate-content/structured-output)。兼容网关必须单独实测。

现有上游 `/models` 已确认存在 gpt-5.6-luna，并已实测图片输入与结构化输出。按用户给出的低成本候选默认选用它；目录没有价格信息，公开首页关闭模型广场，**尚不能确认它是该账户最便宜的模型**。只记录上游 usage，不把缺失用量或价格当成免费，不用模型名称估算金额。GEMINI 适配以本地模拟响应验证，未对真实 Gemini 服务付费验证。

## 接口

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/recognition/providers` | 可用识图配置，不含密钥 |
| GET | `/api/recognition/providers/{id}/models` | 上游目录；不证明能力或价格 |
| GET | `/api/recognition/schema` | 字段选项及输出结构 |
| POST | `/api/workflows` | 原有接口，可在首步加入 RECOGNIZE |
| POST | `/api/recognitions` | 独立识别入口，仍使用持久化工作流队列 |
| GET | `/api/garments?page=1&size=12` | 衣物列表，每页最多 50 |
| GET | `/api/garments/{id}` | 来源图、展示图、有效属性、人工覆盖、历史 |
| PATCH | `/api/garments/{id}/metadata` | 修改字段或恢复自动值 |
| POST | `/api/garments/{id}/recognize` | 仅重新识别，支持更换模型 |
| POST | `/api/garments/{id}/workflows` | 对同一衣物重新运行指定步骤 |
| POST | `/api/garments/{id}/display-image` | 选择属于此衣物的展示图片 |

识别步骤：

```json
{"type":"RECOGNIZE","recognition":{"provider":"yumg","model":"gpt-5.6-luna"}}
```

独立入口：multipart 中 `file` 必需；`recognition` 是可选 JSON 部分，结构同上面的 recognition 对象。必须带 UUID `Idempotency-Key`。返回 202 和工作流地址，查询不会再次识别。

重新识别请求体：`{"provider":"yumg","model":"gpt-5.6-luna"}`。同一 UUID key/相同请求复用任务；改变内容返回 409。换一个 key 才是新的付费请求。页面在响应丢失时保留 key。

修改示例：

```json
{"revision":3,"fields":{"primaryColor":["红色"],"brand":[]},"resetFields":["styles"]}
```

- `fields` 仅含需要修改的字段；`[]` 是人工明确清空，后续识别也不填回。
- `resetFields` 删除该字段的人工覆盖，恢复使用当前模型结果。不能同时设置和恢复同一字段。
- `revision` 是 GET 返回的当前版本；过期返回 409，不静默覆盖。
- 模型结果、人工覆盖、当前有效值分开保存。较旧请求晚返回时保留历史，但不能覆盖较新请求；新请求失败保留上次成功值。
- 手动选择展示图后，后台重做结果作为新候选保留，不自动替换已选图片。新图可再次手动采纳。

## 存储与兼容

Flyway V2 添加 `garment` 表和工作流关联列，保留 V1 表及历史。图片与识别调用共同使用已有 `generation_attempt` 表记录，表名为兼容保留，页面称“模型调用记录”。识别结果保存在工作流步骤 JSON，衣物表保留当前自动结果及人工覆盖。工作流状态和衣物自动更新处于同一数据库事务。

当前是本机单进程原型，仍使用一个 worker，不新增 Redis、Python 服务或数据库服务器，不包含多用户访问隔离。没有通用 DAG 编辑器、多图融合、目标框选识别或完整衣标专用流程。

## 验证

`mvn test`；前端脚本测试见 `src/test/js/`。覆盖工作流集成、原照片输入、局部失败、目标阻塞、非法/截断输出、人工清空、恢复自动值、过期更新、重复提交、展示图归属与保护，以及识别中断后的恢复。真实模型结果与质量限制见 [识图验证记录](../reports/recognition/README.md)。
