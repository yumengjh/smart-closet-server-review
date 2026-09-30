# 项目文档

## 设计文档

- [成熟生图工作流与持久化设计](design/成熟生图工作流与持久化设计.md)：MyBatis-Plus/H2、任务与资产模型、超时重试边界、跨数据库策略和分阶段设计参考（当前四表实现见接口文档）。
- [透明背景与图片工作流](design/透明背景与图片工作流.md)：可选前置抠图、Mermaid 流程图、透明度检查策略、指标与限制。

## 接口与配置

- [数字衣柜首版交付](CLOSET_PRODUCT.md)：真实运行、首次改密、账号命令、旧数据认领、当前产品路由和错误码。

- [衣物档案与元信息识别](RECOGNITION.md)：默认自动流程、独立识别、字段编辑、模型替换与重跑。

- [图片工作流接口](IMAGE_WORKFLOWS.md)
- [生图服务商配置示例](generation.example.yml)：不含真实密钥。

- [图生图 504 排查记录](UPSTREAM_TIMEOUTS.md)

## 验证报告

- [数字衣柜 Vue 联调验证](../reports/closet/README.md)：真实 AI、选图与入柜、回收站、冲突核对、响应式及首次登录 403 修复。

- [四表工作流验证](../reports/workflow/README.md)：数据库持久化、排队、取消、继续处理、采纳及进程重启验证。

- [透明背景工作流验证](../reports/transparency/README.md)
- [生图服务商实测](../reports/generation/provider-check.md)

- [衣物整理一致性排查](../reports/generation/fidelity-check.md)：原图/预抠图失败案例与单次 high 对照，尚未达到保真目标。
