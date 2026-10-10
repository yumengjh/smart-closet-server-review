# 图文模型、超级搜索与重复提醒：本地测试流程

对应设计：[模型内存管理与图像向量设计](design/2026-10-09-模型内存管理与图像向量设计.md)。分支：后端、前端都是 `feat/image-search`。按顺序做，每步都写了“应该看到什么”，任何一步对不上，把截图或 `curl` 输出发给我。

## 0. 准备

| 需要 | 说明 |
|---|---|
| 两个仓库切到 `feat/image-search` | 后端 `git checkout feat/image-search`，前端同名分支 |
| uv | 导出模型用，`curl -LsSf https://astral.sh/uv/install.sh \| sh` |
| 约 6 GB 临时磁盘、10 分钟 | 导出时下载 PyTorch 与官方权重；导出完可删 `~/.cache/smart-closet/` |
| 已有的抠图、文本向量模型 | 可选；没有也能测，只是搜图用 GrabCut 抠图、文字搜索只走关键词 |

## 1. 导出模型（MacBook 上做）

```bash
cd smart-closet-server
bash scripts/export-image-model.sh            # 国内默认走 ModelScope；海外可加参数 huggingface
```

应该看到：

- `agreement with PyTorch (cosine)`：`image_fp32`、`text_fp32` 为 1.0（或 ≥ 0.999），`image_int8`、`text_int8` ≥ 0.99。低于 0.999 时脚本会拒绝安装。
- `check picture vs texts`：用一张红色 T 恤合成图做的零样本检查，**前两名里应该有“红色连衣裙”或“白色T恤”一类上衣**，这是权重正常的标志；如果排名看起来完全随机，说明权重没加载对，告诉我。
- `./models/` 下多了 `image-model-int8.onnx` 等 7 个文件和 `image-model-manifest.json`（SHA-256，请保留）。

然后在本机对拍（Java 预处理、分词、推理与 PyTorch 一致）：

```bash
mvn -o -Dtest=ClipRealModelParityTest test
```

应该看到两行 `[image-model fp32] image cosine 0.99xxx…` 与 `[image-model int8] …`，测试通过（fp32 图像 ≥ 0.995、文字 ≥ 0.9999；int8 ≥ 0.95）。

## 2. 启动并确认加载

照常启动后端（本机 `mvn spring-boot:run` 或 Docker）与前端。**不用重启**：文件放好约 30 秒后，后端日志出现：

```
图文模型（图像）已加载（无需重启）
image index owner=<你的账号> encoded=… elapsedMs=…
```

用管理接口看状态（需要已配置管理密钥，见 [MANAGEMENT_API.md](MANAGEMENT_API.md)）：

```bash
bash scripts/manage-curl.sh GET /api/manage/v1/ops/status | jq '.models.image, .indexes.image, .jvm.processRssBytes'
```

应该看到 `models.image.image.state = "READY"`、`indexes.image.coverage` 逐渐到 1；记下 `processRssBytes`（模型加载后与加载前的差，就是模型实际占的内存）。

## 3. 超级搜索（我的衣柜）

| 操作 | 应该看到 |
|---|---|
| 搜索框输入一段描述，例如“带图案的白T”“格子的” | 结果的理由里出现“图片像“…” 0.xx”；只靠识图字段搜不到的细节也能找到 |
| 点搜索框里的相机，选一张**衣柜里某件衣服换个角度/光线重拍**的照片 | 搜索框左侧出现缩略图；那件衣服排在最前，理由有“图像很像（0.9x）”；结果上方一行灰字“按图像识别（款式、图案、材质感）加颜色轮廓比对…” |
| 同上，再在框里输入“红色”或“上衣” | 结果只剩符合文字条件的，仍按图片相似度排序 |
| 直接 Ctrl/⌘+V 粘贴一张截图；或拖一张图到搜索区域 | 同点相机 |
| 点缩略图右上角的 × | 回到普通文字搜索 |
| 手机宽度（或手机浏览器）重复一次 | 缩略图、相机按钮不挤压，结果为两列 |

搜图时如果出现黄色提示“图像识别准备中（已完成 x/y）”，是后台还在为衣物算图像向量，等它完成即可。

## 4. 入柜自动查重与通知

1. 找一件已入柜的衣服，**再拍一张**（换角度或换光线），按平时流程上传并入柜。
2. 入柜后约 30 秒~1 分钟（后台依次算图片指纹、图像向量、查重），右上角铃铛出现小圆点和“1 条提醒”，网站开着时还会弹出一条“可能重复录入”。
3. 点开铃铛：最上面“提醒”分组里有这条，点它跳到新衣物的详情页；详情页“相似的衣物”第一件就是旧的那件，等级“可能重复”，理由含“图像几乎一样，可能是同一件的不同照片”。
4. 点“不是重复”后，这一对不会再提醒，也不会再出现在“整理衣柜”的列表里。
5. 一次入柜 4 件以上疑似重复（比如批量导入了重复照片）：只收到一条汇总提醒“新入柜的 N 件衣物可能和衣柜里已有的重复”，点开到“整理衣柜”。

> 已经在柜里的旧衣物在升级时记为“已检查”，**不会**一次性冒出很多旧提醒；要查旧衣物用下面的一键扫描。

## 5. 一键扫描（我的 → 整理衣柜）

| 操作 | 应该看到 |
|---|---|
| 点“一键扫描整个衣柜” | 按钮变“扫描中”，旁边写“正在扫描：先补齐图片指纹和图像识别…” |
| 等几秒到几十秒（衣物越多越久） | 顶部提示“扫描完成：发现 N 对…”；列表刷新；旁边显示“上次扫描 …”；右上角多一条“一键扫描完成”提醒 |
| 扫描中再点一次 | 不会开第二个扫描 |

## 6. 内存紧张时的回退与提示

模拟内存不足：把最低可用内存设得很大，重启后端：

```bash
CLOSET_IMAGE_MODEL_MIN_FREE_MB=999999 mvn spring-boot:run      # Docker 则写进 .env / compose 环境变量
```

| 位置 | 应该看到 |
|---|---|
| 衣柜页用图片搜 | 黄色提示“服务器内存紧张，图像识别暂时关闭，已按颜色、轮廓比对。”，结果照常（按颜色轮廓） |
| 衣柜页文字搜索 | 黄色提示“…文字找图片暂时关闭。”，关键词与描述搜索照常 |
| 整理衣柜 | 同样的黄色提示 |
| 运维状态 | `issues` 里有 `IMAGE_MODEL_LOW_MEMORY`，`models.image.image.lowMemorySkips` 增加 |
| 入柜查重 | 不等图像向量，按颜色轮廓等已有数据照常查重和提醒 |

去掉这个环境变量重启即恢复。删掉或改坏 `models/image-model-int8.onnx`，应看到“图像识别暂时不可用”（`FAILED`）；放回正确文件后约 30 秒自动恢复，不用重启。

## 7. 内存与闲置卸载

- 不操作 10 分钟后，日志出现“图文模型（图像）闲置 10 分钟，已释放内存”，`ops/status` 的 `models.image.image.loaded=false`、`unloads` 加 1；再搜图时自动重新加载（多等约 1 秒）。文本塔是 30 分钟。
- 在 4G 服务器上请记下：启动后、搜过图后、闲置 10 分钟后三个时刻的 `processRssBytes` 和 `free -m`，发给我校准设计里的内存预算。

## 8. 效果评估（决定门槛与精度，建议做）

**每张图都是不同的衣服**（例如为了覆盖各种品类专门收集的截图）：

```bash
bash scripts/eval-image-model.sh ~/衣物素材/pdd2 --distinct
```

不用分组。工具会把子文件夹（如 `已入`）也算进来，为每张图生成两张“模拟重拍”（旋转裁切加暗、缩小换背景并镜像）当作同一件的不同照片，任意两张原图都算不同衣物。跑完：

1. 终端打印 `report.md`：零模型与图文模型的找回率、误判重复率、余弦分布与门槛建议；
2. 打开 `target/image-eval/report.html`：30 个常用词（T恤、连衣裙、格子、碎花、汉服、洛丽塔……）各自排前 6 的图片，在每个词旁填“对了几张”，点“生成结果”复制文字；下面还有“最像的其他衣物”对比；
3. 把 `report.md` 和复制的文字一起发给我。

**一件衣服有好几张截图**（同一商品截了多张）：（例如电商商品页截图，同一件商品截了好几张）：

```bash
bash scripts/eval-image-model.sh ~/衣物素材/pdd2
```

第一次运行会在文件夹里生成 `grouping.html`，并提示用浏览器打开。页面已按截图时间先猜好“哪几张是同一件”（相隔超过 20 秒算新的一件，可以改），你只需：

1. 看每个彩色框是不是同一件衣服；不对就点照片下的“从这张起是新的一件”或“并入上一件”；
2. 尺码表、纯文字详情、和衣服无关的截图点“不用这张”；
3. 点右上角“导出分组”，下载的 `groups.json` 会在下次运行时自动从“下载”移进文件夹；
4. 再运行一次同样的命令，报告就出来了。

也可以按下面的子文件夹方式自己整理：

准备一个文件夹（不放进仓库）：

```
eval/
  same/
    grey-hoodie/   1.jpg 2.jpg 3.jpg     ← 同一件衣服的不同照片：换角度、换光线、平铺/上身、商品图
    navy-tee/      1.jpg 2.jpg
    ...                                    ← 建议 30~100 件，每件 2~4 张；也放几件“很像但不是同一件”的
  queries.tsv                              ← 可选：每行“文字<Tab>文件夹名”，例如：带小熊图案的白T	bear-tee
```

```bash
bash scripts/eval-image-model.sh /path/to/eval
```

（等同于 `mvn -Dtest=ImageModelEvaluation -Dimage.eval=/path/to/eval test`；没有网页入口，在后端项目目录里运行即可，几分钟内完成。）

报告写到 `target/image-eval/report.md`，包含：

- 零模型 vs 图文模型（int8、fp32 都装了就各一行）的“同件排第 1 / 前 5”“同件判为可能重复”“不同衣物误判可能重复”；
- 同件与不同衣物的图像余弦分布，以及建议的“同一件”门槛（现在是 0.93）；
- 文字找图片每条的命中情况、期望衣物的最高余弦与全部中位（用来调 `CLOSET_IMAGE_TEXT_THRESHOLD/MARGIN/WINDOW`）；
- 本机编码耗时与内存。

把报告发给我，我按数字调门槛；如果 int8 明显不如 fp32，就把默认图像塔改回 fp32（`CLOSET_IMAGE_MODEL_PATH=/models/image-model-fp32.onnx`，旧向量会自动重算）。

## 9. 接口速查

| 接口 | 用途 |
|---|---|
| `GET /api/garments/search?q=` | 文字搜索（含“文字找图片”），响应带 `imageModel` |
| `POST /api/garments/search`（multipart：`image`、`q`、`page`） | 超级搜索：图片 / 图片 + 文字 |
| `POST /api/garments/search-by-image` | 旧接口，保留兼容，内部同样用图像向量 |
| `GET /api/garments/{id}/similar`、`GET /api/garments/duplicates` | 相似与可能重复，带 `imageModel` |
| `POST /api/garments/duplicate-scan`、`GET /api/garments/duplicate-scan` | 一键扫描、扫描状态 |
| `GET /api/notifications`、`POST /api/notifications/{id}/read`、`POST /api/notifications/read-all` | 提醒 |

`imageModel.state`：`READY` 正常；`INDEXING` 后台计算中（带 `indexed/total`）；`NOT_INSTALLED` 未装；`LOW_MEMORY` 内存不足未加载；`FAILED` 文件或推理出错。`message` 是给用户看的中文，前端原样显示。
