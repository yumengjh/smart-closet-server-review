# 本地抠图与贴纸接口（底层）

本文是服务端底层图片处理能力的说明：两种本地分割方法、`/api/segmentations` 接口、方向校正、贴纸化、输入与资源边界、算法对比测试。
日常录入请使用数字衣柜页面（带账号归属检查的衣物与任务接口）；这些底层接口与 `/studio`、`/workflow` 测试页只对管理员开放。
原为 README 的一部分，2026-10-19 README 重写时整体移到这里，内容未删改（仅调整相对链接）。

## 两种分割方法

两种处理方法都在 Java 服务本地运行：

| 方法 | 实现 | 框选的含义 |
| --- | --- | --- |
| `GRABCUT` | OpenCV 4.14，传统颜色/图割算法 | 框外为确定背景，必须留下部分框外背景 |
| `U2NET`（默认） | ONNX Runtime 1.30 + U²-Net | 在框内裁剪输入再推理，框外透明；不是语义定位提示 |

**本地算法做主体分割，不做衣物类别识别。** 人、床、植物等也可能被分割为主体。结果里的警告不是模型置信度。现已接入独立于分割算法的多模态元信息识别：默认上传流程先识别、再整理图片，风格与季节标为推测，成分需衣标证据；可选生成式 AI 步骤可按提示词整理图片，但没有保真、去褶皱或补全准确性保证。

## 快速调用

完成 Vue 构建后，启动并打开 [数字衣柜](http://127.0.0.1:8080/login)。管理员登录并完成首次改密后，也可打开 [衣物测试页面](http://127.0.0.1:8080/studio/index.html)。页面位于 `src/main/resources/static/studio/`，随后端一起提供，无需 Node 构建、外部 CDN 或额外前端服务。支持选图/拖放、左右 90° 旋转、角度滑杆与数值输入、抠图/贴纸预览、浅深背景切换和图片下载。

首次 Maven 构建会下载 Java 依赖和随依赖发布的 OpenCV/ONNX 本地库，不需要 Homebrew/pip 安装。
模型未准备好时服务仍能启动，`GRABCUT` 可用；`U2NET` 返回明确的 `503 MODEL_NOT_INSTALLED`。服务自身不会自动下载模型，纯本地流程不向第三方上传图片。

下列分割请求展示参数用法；除 `/api/health` 外，业务请求均需管理员会话，写请求还需 `X-XSRF-TOKEN`。鉴权契约见 [产品接口文档](CLOSET_PRODUCT.md#登录与账号管理)，日常录入建议使用数字衣柜页面。

```bash
curl http://127.0.0.1:8080/api/health
curl http://127.0.0.1:8080/api/segmentation-methods

# 本地模型
curl -f -F 'file=@samples/white-shirt-light.jpg' \
  'http://127.0.0.1:8080/api/segmentations?method=U2NET'

# 传统算法
curl -f -F 'file=@samples/white-shirt-light.jpg' \
  'http://127.0.0.1:8080/api/segmentations?method=GRABCUT'

# 显式框选，单位是 EXIF 与用户角度校正后的图片像素
curl -f -F 'file=@samples/white-shirt-light.jpg' \
  'http://127.0.0.1:8080/api/segmentations?method=GRABCUT&x=20&y=200&width=1160&height=1400'
```

上传成功返回 `201` 和 `Location`，响应包含：

- `id`、`method`、`model`、校正后图片宽高、原始 EXIF 方向及 `rotationDegrees`；
- `sourceUrl` 与 `originalUrl`：均为校正后的 RGB PNG，新上传不再持久化未经校正的原始字节；
- `maskUrl`：原图尺寸灰度蒙版，0 为背景、255 为前景、中间值表示透明过渡；
- `cutoutUrl`：原图尺寸透明 PNG；`croppedUrl`：按非透明区域裁剪的 PNG；
- `foregroundBounds`：裁剪范围；`foregroundRatio`：alpha ≥ 128 的像素占比；
- `processingMillis`：解码、算法和图片保存耗时，包含第一次模型加载，不包含上传耗时；
- `dominantColors`：alpha ≥ 230 区域中占比最多的五个 RGB 色桶，`fraction` 相对于所有参与统计的像素，五项之和不一定为 1；这是拍照颜色，不是经过校色的面料颜色；
- `warnings`：提示人工检查，不代表质量判定成功或失败。

返回的图片路径是相对于当前服务的路径。例如 `http://127.0.0.1:8080` 加上 `cutoutUrl` 即可查看。Java 服务保持运行时链接有效；结果文件在重启后仍然保留。

## 接口

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/api/health` | 服务存活，不代表模型已加载 |
| GET | `/api/segmentation-methods` | 方法、模型文件是否存在、是否加载过 |
| POST | `/api/segmentations` | multipart 文件上传，`method` 可选；`x/y/width/height` 要一起提供 |
| GET | `/api/segmentations/{id}` | 读取已保存结果 |
| GET | `/api/segmentations/{id}/files/{name}` | 读取白名单内图片文件 |
| POST | `/api/segmentations/{id}/refine` | 从已保存的校正照片重新处理，支持继续旋转/框选，创建新结果 |
| POST | `/api/segmentations/{id}/sticker` | 从已有抠图生成贴纸；首次 201，命中缓存 200 |
| GET | `/api/segmentations/{id}/sticker` | 读取贴纸元信息；未生成时 404 |
| GET | `/api/segmentations/{id}/sticker/image` | 下载透明贴纸 PNG；未生成时 404 |

重新框选请求示例（把 `结果ID` 替换为实际 UUID）：

```bash
curl -f -H 'Content-Type: application/json' \
  -d '{"method":"GRABCUT","region":{"x":20,"y":200,"width":1160,"height":1400}}' \
  http://127.0.0.1:8080/api/segmentations/结果ID/refine
```

矩形必须完整包含想保留的衣物，坐标基于本次旋转完成后的图片。U2Net 会将框外丢弃；GrabCut 会将框外视为背景。自动 GrabCut 将图像约 3% 的边框视为背景，碰到衣物出画可能切掉衣服，响应会包含该假设的提醒。

## 方向校正与保存约定

```bash
# 正数顺时针；负数逆时针。省略时为 0，支持小数，范围 -360～360。
curl -f -F 'file=@samples/round2/artf-260.png' \
  'http://127.0.0.1:8080/api/segmentations?method=U2NET&rotationDegrees=180'

# 在已保存结果上再顺时针转 90°；region 可以省略
curl -f -H 'Content-Type: application/json' \
  -d '{"method":"U2NET","rotationDegrees":90}' \
  http://127.0.0.1:8080/api/segmentations/结果ID/refine
```

顺序固定为：解码 → EXIF 校正 → 用户角度 → 分割 → 保存 → 可选贴纸。90° 的整数倍按像素转换，任意角度扩展画布以保留整张照片，新增角落填白；旋转后仍受 1200 万像素、单边 8192 的限制，超限返回 400，需先缩小照片。

新结果的 `source.png`、`original.png` 保存相同的校正照片，不携带待再次应用的 EXIF 旋转标签；蒙版、抠图、裁剪图和贴纸方向也保持一致。未处理的上传文件只用于本次请求解码，不作为结果文件持久化。

`rotationDegrees` 响应值归一化为 [0, 360)，表示累计用户旋转角度，不包含原照片 EXIF 校正。重做基于已保存照片，仅应用本次新增角度；不传角度时不会重复旋转。多次非直角旋转会重采样并扩大留白，建议在上传预览中一次调整好。

历史结果保持原样，不批量改写；旧 JSON 缺少 `rotationDegrees` 时为 null，重做按累计 0° 起算，仍读取旧 `original.png`，避免重复应用原 EXIF。

测试页面只预览旋转，提交原文件和角度让后端执行一次。其他客户端如果已把旋转结果编码成新图片，应传 `rotationDegrees=0`，避免双重旋转。

## 贴纸化与处理流程

```text
上传 → 预处理 → U²-Net / GrabCut → 保存干净抠图与蒙版
                                       ↓ 独立请求
                                  贴纸渲染 → 保存展示图
```

贴纸是展示衍生图，原来的 `cutoutUrl`、`maskUrl` 与元信息保持不变。已有结果也能生成贴纸；渲染直接读取本地 `cutout.png`，不重新运行模型，不调用外部 AI。

```bash
# 把“结果ID”换成上传响应中的 id；无请求体
curl -X POST http://127.0.0.1:8080/api/segmentations/结果ID/sticker
```

返回 `segmentationId`、`style`、`createdAt`、`width`、`height`、`contentBounds`、`processingMillis`、`imageUrl`。`contentBounds` 是贴纸画布中衣物的矩形位置，不包含外描边和投影。`processingMillis` 包含读取、渲染与图片保存，缓存命中保留最初生成时间与耗时。

当前样式为 `bold-v2`。参数暂时集中硬编码在 [StickerRenderer.java](../src/main/java/com/smartcloset/image/StickerRenderer.java) 顶部，修改后重新构建/启动服务：

| 参数 | 当前值 | 控制内容 |
| --- | --- | --- |
| `SIZE` | 1024 | 输出画布尺寸 |
| `PADDING` | 88 | 衣物与画布边缘距离 |
| `OUTLINE` | 32 | 外描边宽度，像素 |
| `OUTLINE_RGB` | `0xfffdf9` | 描边颜色 |
| `SHADOW_RGB` | `0x20252b` | 阴影颜色 |
| `SHADOW_OPACITY` | 0.32 | 阴影不透明度，0～1 |
| `SHADOW_OFFSET_X/Y` | 8 / 18 | 阴影向右、向下的偏移 |
| `SHADOW_SIGMA` | 10 | 阴影模糊程度 |

修改参数时同步升级 `STYLE`，用于区分落盘缓存；下载地址的 `v` 参数也随之变化，以便浏览器加载新图。接口暂不接受自定义样式参数。增大描边、模糊或偏移时，需相应保留足够的 `PADDING`，避免效果被画布截断。

- 1024×1024 透明 PNG；衣物等比例居中，最长边 848 像素，衣物与画布边缘至少相隔 88 像素，描边和投影占用部分留白。
- 32 像素暖白外描边，阴影不透明度 32%、向右 8/向下 18 像素；使用已存在的 OpenCV Java 依赖处理透明蒙版。
- 只装饰与画布边缘连通的外部背景，保留衣物内部封闭的透明孔洞。不自动旋转、去皱、补全或修复原分割误差。
- 贴纸保存到 `data/{UUID}/sticker-bold-v2/`，整份结果原子保存。同一版本重复请求复用已有文件；服务重启后也可读取。原有 `sticker-soft-v1/` 保留，当前接口使用新版本。
- 贴纸最多同时渲染一个任务，忙时返回 `429 PROCESSOR_BUSY`；这是独立于分割任务的容量限制。首次生成用 POST，GET 不隐式执行处理。
- 框选重做产生新的分割 ID，新 ID 的贴纸独立生成。后续 AI 分析和穿搭合成使用干净抠图，避免描边、阴影干扰。

[查看 12 张贴纸在浅色/深色背景下的效果](../reports/stickers/index.html) · [贴纸检查记录](../reports/stickers/README.md)。背景颜色仅存在于预览页，下载的 PNG 仍然透明。

## 输入与资源边界

- JPEG/PNG，以实际解码格式判断；支持 EXIF 1～8 方向修正。HEIC 暂不支持。
- 最大 15 MB、1200 万像素、单边 8192 像素、最小 32×32。建议长边 1200～2000。
- 已有透明度的 PNG 先合成到白底供 RGB 模型处理；保存的是 EXIF 与用户角度校正后的照片。
- GrabCut 等比例缩到最长 1000 像素计算；U2Net 使用 320×320；蒙版还原到原图尺寸，RGB 使用原图，不通过生成模型重画衣物。
- 不做一刀切填洞或只保留最大连通块，以免删除镂空、细肩带等真实结构。细边缘、阴影、背景同色仍可能失败。
- 单次处理一个任务，其余请求立即返回 `429` 和 `Retry-After: 3`，避免多个大图和本地模型同时挤占内存。原型为同步接口。
- 文件保存在 `data/{UUID}/`，上传文件名不参与路径拼接。失败时清理该次临时输出；成功的原图与结果不会自动过期，删除不用的结果目录可回收空间。
- 原有底层分割 API 与测试台由管理员专用；普通账号应通过数字衣柜产品页面使用带归属检查的衣物/任务接口。默认仍只监听回环地址。

常见错误：`400 INVALID_IMAGE/INVALID_REQUEST`、`413 IMAGE_TOO_LARGE`、`422 NO_FOREGROUND/SEGMENTATION_FAILED`、`429 PROCESSOR_BUSY`、`503 MODEL_NOT_INSTALLED/MODEL_UNAVAILABLE`。

## 测试与图片对比

```bash
# 核心算法、参数边界、实际 HTTP 上传/下载/重做；不下载模型
mvn test

# 使用 samples/ 中的 4 张照片，真实 HTTP 调用两种方法；需本地模型
mvn -Dcloset.samples=true -Dtest=SampleComparisonTest test

# 第二轮：12 张预先筛选的完整衣物，包含 7 张床单场景；需本地模型
mvn -Dcloset.round2=true -Dtest=FlatLayComparisonTest test

# 使用第二轮已保存的抠图生成贴纸预览，无需模型文件或再次分割
mvn -Dcloset.stickers=true -Dtest=StickerPreviewTest test

# 构建可执行 JAR
mvn package
java -jar target/smart-closet-server-0.0.1-SNAPSHOT.jar
```

第二轮完整衣物对比见 [reports/round2/index.html](../reports/round2/index.html)，逐图结论见 [reports/round2/REVIEW.md](../reports/round2/REVIEW.md)。包含原图、两种方法的透明结果、参考蒙版、处理耗时和参考轮廓指标；测试会检查固定输入的 SHA-256。

第一轮的 4 张探索性照片保留在 [reports/latest/index.html](../reports/latest/index.html)，说明见 [reports/EXPERIMENT.md](../reports/EXPERIMENT.md)。其中有出画和遮挡样例，不能作为完整单件的主要质量依据。`latest` 是第一轮沿用的目录名，不表示最新一轮。

照片来源见 [第一轮来源](../samples/SOURCES.md) 和 [第二轮来源与选图记录](../samples/round2/SOURCES.md)，模型来源见 [models/README.md](../models/README.md)。第二轮是小批筛选样本，且使用半自动参考标注，指标不代表通用准确率；床单背景多样性仍不足。

## 相关代码

```text
api/                     HTTP 接口与错误响应
image/Images             解码、大小检查、EXIF、用户旋转与尺寸转换
image/GrabCutEngine      传统算法
image/U2NetEngine        本地模型加载、预处理与推理
image/MaskProcessor      蒙版、透明图、裁剪、颜色和规则提醒
image/SegmentationService 任务容量限制、处理流程与文件保存
image/StickerRenderer     透明画布、外描边、投影与孔洞保留
image/StickerService      独立贴纸步骤、缓存与持久化
resources/static/studio/  后端内置测试页面（HTML / CSS / JavaScript）
```

接下来应先用目标场景照片检查具体失败，再决定换更强的分割模型或增加前景/背景涂抹修正。分类识别可在分割稳定后作为单独能力接入。
