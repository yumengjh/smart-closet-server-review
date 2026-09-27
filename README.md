# AI 数字衣柜 · 图片处理后端

Spring Boot 4.1.1 / Java 21。第一阶段验证：**单件平铺衣物照片 → 前景蒙版 → 透明 PNG**。

两种处理方法都在 Java 服务本地运行：

| 方法 | 实现 | 框选的含义 |
| --- | --- | --- |
| `GRABCUT` | OpenCV 4.14，传统颜色/图割算法 | 框外为确定背景，必须留下部分框外背景 |
| `U2NET`（默认） | ONNX Runtime 1.30 + U²-Net | 在框内裁剪输入再推理，框外透明；不是语义定位提示 |

**当前做主体分割，不做衣物类别识别。** 人、床、植物等也可能被分割为主体。结果里的警告不是模型置信度。材质、风格、季节、去褶皱、补全遮挡都未实现。

## 快速启动

需要已有 JDK 21+ 和 Maven 3.9+。项目没有 Python 服务、数据库、Redis、Docker 或云端 AI 依赖。

```bash
# 首次运行下载约 176 MB 模型到 models/，已下载且校验正确时直接跳过
bash scripts/download-model.sh

# 从项目根目录启动；默认仅监听本机 127.0.0.1:8080
mvn spring-boot:run
```

首次 Maven 构建会下载 Java 依赖和随依赖发布的 OpenCV/ONNX 本地库，不需要 Homebrew/pip 安装。
模型未准备好时服务仍能启动，`GRABCUT` 可用；`U2NET` 返回明确的 `503 MODEL_NOT_INSTALLED`。服务自身不会自动下载模型或上传图片。

```bash
curl http://127.0.0.1:8080/api/health
curl http://127.0.0.1:8080/api/segmentation-methods

# 本地模型
curl -f -F 'file=@samples/white-shirt-light.jpg' \
  'http://127.0.0.1:8080/api/segmentations?method=U2NET'

# 传统算法
curl -f -F 'file=@samples/white-shirt-light.jpg' \
  'http://127.0.0.1:8080/api/segmentations?method=GRABCUT'

# 显式框选，单位是修正 EXIF 方向后的原图像素
curl -f -F 'file=@samples/white-shirt-light.jpg' \
  'http://127.0.0.1:8080/api/segmentations?method=GRABCUT&x=20&y=200&width=1160&height=1400'
```

上传成功返回 `201` 和 `Location`，响应包含：

- `id`、`method`、`model`、图片宽高和 EXIF 方向；
- `sourceUrl`：上传字节原样保存；`originalUrl`：修正方向后的 RGB PNG；
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
| POST | `/api/segmentations/{id}/refine` | 从该结果的原始上传文件重新处理，创建新结果，保留旧结果 |

重新框选请求示例（把 `结果ID` 替换为实际 UUID）：

```bash
curl -f -H 'Content-Type: application/json' \
  -d '{"method":"GRABCUT","region":{"x":20,"y":200,"width":1160,"height":1400}}' \
  http://127.0.0.1:8080/api/segmentations/结果ID/refine
```

矩形必须完整包含想保留的衣物。U2Net 会将框外丢弃；GrabCut 会将框外视为背景。自动 GrabCut 将图像约 3% 的边框视为背景，碰到衣物出画可能切掉衣服，响应会包含该假设的提醒。

## 输入与资源边界

- JPEG/PNG，以实际解码格式判断；支持 EXIF 1～8 方向修正。HEIC 暂不支持。
- 最大 15 MB、1200 万像素、单边 8192 像素、最小 32×32。建议长边 1200～2000。
- 已有透明度的 PNG 先合成到白底供 RGB 模型处理；原始上传字节仍保存。
- GrabCut 等比例缩到最长 1000 像素计算；U2Net 使用 320×320；蒙版还原到原图尺寸，RGB 使用原图，不通过生成模型重画衣物。
- 不做一刀切填洞或只保留最大连通块，以免删除镂空、细肩带等真实结构。细边缘、阴影、背景同色仍可能失败。
- 单次处理一个任务，其余请求立即返回 `429` 和 `Retry-After: 3`，避免多个大图和本地模型同时挤占内存。原型为同步接口。
- 文件保存在 `data/{UUID}/`，上传文件名不参与路径拼接。失败时清理该次临时输出；成功的原图与结果不会自动过期，删除不用的结果目录可回收空间。
- 当前原型仅绑定回环地址，无用户登录和访问隔离；面向本机开发使用。

常见错误：`400 INVALID_IMAGE/INVALID_REQUEST`、`413 IMAGE_TOO_LARGE`、`422 NO_FOREGROUND/SEGMENTATION_FAILED`、`429 PROCESSOR_BUSY`、`503 MODEL_NOT_INSTALLED/MODEL_UNAVAILABLE`。

## 测试与图片对比

```bash
# 核心算法、参数边界、实际 HTTP 上传/下载/重做；不下载模型
mvn test

# 使用 samples/ 中的 4 张照片，真实 HTTP 调用两种方法；需本地模型
mvn -Dcloset.samples=true -Dtest=SampleComparisonTest test

# 第二轮：12 张预先筛选的完整衣物，包含 7 张床单场景；需本地模型
mvn -Dcloset.round2=true -Dtest=FlatLayComparisonTest test

# 构建可执行 JAR
mvn package
java -jar target/smart-closet-server-0.0.1-SNAPSHOT.jar
```

第二轮完整衣物对比见 [reports/round2/index.html](reports/round2/index.html)，逐图结论见 [reports/round2/REVIEW.md](reports/round2/REVIEW.md)。包含原图、两种方法的透明结果、参考蒙版、处理耗时和参考轮廓指标；测试会检查固定输入的 SHA-256。

第一轮的 4 张探索性照片保留在 [reports/latest/index.html](reports/latest/index.html)，说明见 [reports/EXPERIMENT.md](reports/EXPERIMENT.md)。其中有出画和遮挡样例，不能作为完整单件的主要质量依据。`latest` 是第一轮沿用的目录名，不表示最新一轮。

照片来源见 [第一轮来源](samples/SOURCES.md) 和 [第二轮来源与选图记录](samples/round2/SOURCES.md)，模型来源见 [models/README.md](models/README.md)。第二轮是小批筛选样本，且使用半自动参考标注，指标不代表通用准确率；床单背景多样性仍不足。

## 配置

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `PORT` | `8080` | HTTP 端口 |
| `CLOSET_STORAGE_DIR` | `./data` | 原图和结果存储目录 |
| `CLOSET_MODEL_PATH` | `./models/u2net.onnx` | 同一 U2Net 模型文件路径，不是任意模型通用接口 |

POM 按当前 OS/架构选择 OpenCV 本地依赖；macOS arm64、macOS x64、Linux x64/arm64、Windows x64 有配置，实际验证平台记录在实验报告。跨平台打包可使用 `-Dnative.platform=linux-x86_64` 等显式指定，目标平台仍需要运行验证。

## 代码结构

```text
api/                     HTTP 接口与错误响应
image/Images             解码、大小检查、EXIF 与尺寸转换
image/GrabCutEngine      传统算法
image/U2NetEngine        本地模型加载、预处理与推理
image/MaskProcessor      蒙版、透明图、裁剪、颜色和规则提醒
image/SegmentationService 任务容量限制、处理流程与文件保存
```

接下来应先用目标场景照片检查具体失败，再决定换更强的分割模型或增加前景/背景涂抹修正。分类识别可在分割稳定后作为单独能力接入。
