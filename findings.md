# 技术发现

- 项目原为空目录；本机 Java 21.0.11、Maven 3.9.16、macOS aarch64 已可用。
- 未发现父目录 AGENTS.md。
- Maven Central 确认 Spring Boot 4.1.1、Bytedeco OpenCV 4.14.0-1.5.14 存在。
- OpenCV GrabCut 是前景分割，不提供衣物类别识别。
- ONNX Runtime Java 可在 JVM 中推理，需实测当前 Apple Silicon 二进制兼容性。
- rembg 的 U2Net 实现提供模型来源和输入归一化参考；不会安装 rembg。
- 技术参考：https://docs.opencv.org/4.x/d8/d83/tutorial_py_grabcut.html 、https://onnxruntime.ai/docs/get-started/with-java.html 、https://github.com/danielgatis/rembg 。
- Maven 构建已通过；ONNX 1.30.0 JAR 内包含 osx-aarch64 本地库。
- 样图目视检查：浅色衣物图是完整的平铺上衣；床上图有多件重叠衣物并有出画，属于困难/超范围样本，不能当成单件测试。
- GitHub 模型下载速度约 60 KB/s，正核对 Hugging Face 镜像与原始 MD5 一致性。
- Hugging Face 不可达；改为 GitHub 原文件分段下载后完整 MD5/SHA256 均已核验。
- 目视检查比较图：浅色平铺上衣两方案都提取主体；木地板样图实际上只有衣物局部且出画，GrabCut 大片切错，U2Net 只保留领口附近；杂物样例两者均失败；床上多件样例 GrabCut 保留一组衣物但切掉出画部分，U2Net 主要只留下针织背心。
- 第一轮耗时（含解码/保存）：GrabCut 12101/3505/8859/2336 ms；U2Net 2996/861/873/657 ms，U2Net 首次含加载。不能把这些数字视为稳定基准或准确率。

## 第二轮来源检索
- 找到 aRTF Clothes Dataset：https://github.com/tlpss/aRTF-Clothes-dataset 。来自家庭环境中近乎摊开的真实衣物，数据 CC BY 4.0，代码 MIT；适合补足商品照与实际场景的差异。
- 作者：Thomas Lips、Victor-Louis De Gusseme、Francis wyffels；论文 Learning Keypoints for Robotic Cloth Manipulation using Synthetic Data (2024)。注意论文训练涉及合成数据，但 aRTF 本身是采集的真实照片。
- 完整数据 7 GB，不整包盲目下载；优先寻找缩小版或可单独获取的样例。
- Zenodo 记录 10875542 可按 HTTP Range 读取 ZIP；只下载中心目录与所需图片，不下载完整 7 GB。
- 原始 CVAT 标注包含 semi-auto polygon。可用于辅助比较，但不是完美人工真值，尤其边缘/孔洞要结合原图复核。
- 先按 8 个 test 场景各取 2 张，共 16 张候选；选图不参考任何算法输出。
- 原图初筛：artf-000 袖子折入，artf-020 衣身明显堆叠；即使衣物完整入镜，也不等于充分摊开，不能直接当理想平铺主样例。
- 16 张原始候选全部下载成功；仅紫色和绿色两件衣物，补选床上场景 208/216/228/248/260 五个不同编号，增加衣物多样性。选图仍未运行分割。
- 200 原尺寸检查：床上紫色 T 恤完整入镜、没有外物遮挡，可作为主要场景；120 沙发紫色 T 恤也完整，袖口有自然皱折。
- 最终固定 12 张（7 件不同上衣，4 种场景），保留 248 蓝衣蓝床单难例；排除 080 是因为下摆仅约 6 像素留白，不把它误报成确定出画。
- 第二轮正在运行，前几张已出现 GrabCut 大量保留床单的情况；待完整报告和参考指标后再总结。
- 第二轮完成：GrabCut 9/12 明显留底；U2Net 12 张均找到整件衣物，但 248 同色袖边发虚、120 阴影边缘偏软。参考 IoU 均值 0.518/0.986，不能解释成成功率。逐图结论见 reports/round2/REVIEW.md。

## 贴纸展示
- 后处理不需要生成模型：现有 OpenCV 膨胀、模糊及 Java alpha 合成即可实现描边与阴影。独立渲染接口可处理旧分割结果，不需要重跑 AI。
- 透明孔洞应与外部背景区分，否则描边和投影会填入衣物孔洞；采用外部背景连通检查，并增加孔洞回归测试。
- 12 张真实贴纸渲染本次耗时中位数 163 ms。深色背景会暴露软蒙版的原有边缘缺陷，贴纸化不能代替局部蒙版修正。

## 方向校正
- 用户要求后端保存校正后照片：新上传 source/original 均编码为校正后 PNG，去掉待再次应用的 EXIF 信息，历史结果不批量改写。
- 任意角度需扩展画布，继续检查扩展后的像素限制；直角按像素映射。重做始终读取已校正 original.png 并只应用本次增量角度。
- 测试页只做角度预览并提交原文件+角度，避免前后端重复旋转。真实 180° 测试确认后端保存的贴纸衣领朝上。
# 2026-09-28 生图接入
- api.yumg.cn 首页为鱼站 AI API Gateway；GET /v1/models 返回 20 个模型，图像候选 gpt-image-1、gpt-image-1.5、gpt-image-2。列表本身不证明请求可成功。
- 密钥仅在 Git 忽略的 .local/ 中保存，未输出到报告。
- 对照规范：https://developers.openai.com/api/docs/guides/image-generation 与 https://ai.google.dev/gemini-api/docs/generate-content/image-generation 。
- 3 个生图候选均返回 502；gpt-image-1.5 最小参数请求返回 503 No available compatible accounts。用户正在检查上游；本轮 0 张远程图片成功，不将模型列表当成已验证能力。
- 当前实现仅两种协议：OpenAI Images 与 Gemini generateContent。每步只接收前一步图片，生成结果与校正输入分开保存；纯本地工作流继续可用。

## 上游更新后
- 当前列表 26 个，新增生图 flare / sunburst。flare 文生图与后处理成功；sunburst 图生图返回 HTTP 504，约 60 秒，早于客户端 180 秒期限。
- flare size=1024x1024 实际返回 1312×1199；模型/服务商可能不严格遵循尺寸参数，应以实际图片为准。详细报告 reports/generation/provider-check.md。

## 透明背景改造结果
- 保留 Alpha 必须覆盖上传解码与旋转；仅在生成响应中保存 Alpha 不够。旧分割解码保持白底 RGB，新工作流用保留 Alpha 的解码入口。
- 检查规则为 alpha-v1，接近透明阈值与既有蒙版处理对齐（≤7 / ≥248）；原样复用路径不改像素。检测只证明结构基本可用，不证明衣物语义质量。
- STICKER 已从分割 ID 依赖改为图片输入，ENSURE_TRANSPARENCY 可输出原样图片，避免为满足步骤规则重复抠图。
- 真实 flare 已有抠图通过 USABLE 检查并原样复用；原始不透明生成图 AUTO 触发一次本地 U2Net，PRESERVE 停止供用户检查。

## 默认编辑目标与生成偏差
- 用户案例证明“完整平铺商品图”会引导模型重摆和重绘衣物；泛泛要求保留细节不足以定义编辑边界。现将仅清理背景与重新平铺分开。
- 透明度结构检查无法判断衣物身份一致性。任务 a01ae01c-7abf-4843-b443-63d002266b80 的 AI 步骤成功，但透明检查因主体触边停止，用户另行指出内容偏差；这两类检查不可混为一谈。
- 预抠图是后续生成的唯一参考；误删信息不会由原始输入自动补充。多轮生成可能累积偏差。提示词只是约束意图，不能承诺像素保真。

## 用户澄清：整理衣物，同时保留设计
- 上一轮把保留设计误解为保留照片中的姿态和褶皱。实际目标是摆正、展开、去皱、清理背景，同时保持同一件衣物的颜色、款式和印花。已据此修改默认流程、默认编辑目标和提示词；纯背景清理作为可选项保留。
- 印花应随布料展开自然变换，禁止重新排列、复制和猜测新图案；后处理仍负责透明检查和按需抠图。提示词约束不等于模型效果验证。

## 2026-09-29 工作流持久化
- 当前代码仍是磁盘 job.json + 单任务拒绝机制；保留现有 API 和提示词，新增四表存储，生图调用单独记账。本次源码提示词已比历史记忆更新，不回退。MyBatis-Plus 官方提供 Boot4 starter 3.5.17。
