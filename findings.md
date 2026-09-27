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
