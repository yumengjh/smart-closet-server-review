# 发现
当前单件工作流已有识别target及自拍跳过预抠图。识别最长1600，生图原像素。另聊天已完成。现有工作区改动必须保留。
旧quota按工作流计1，需要更新为RECOGNIZE/GENERATE数量。现有GarmentRepository从workflow资产派生图片，可内部导入批次单件生成结果后继续本地后处理以复用原有核对页。
模型本地配置flare，1024 square low；真实账单按用户确认OpenAI 1:1。官方image token输入8美元/百万、输出30美元/百万、文字输入5美元/百万。识别模型价格若无法验证保持未知。

真实识别供应商返回prompt_tokens/completion_tokens/total_tokens，无cached_tokens明细。Luna标准价已官网确认0.10/0.01/0.50美元每百万；本轮费用未知是缺缓存计费明细，不是价格无法确认。按约定暂停追加，报告docs/reports/2026-10-02-batch-experiment.md与JSON记录2个真实目标、0生图。
