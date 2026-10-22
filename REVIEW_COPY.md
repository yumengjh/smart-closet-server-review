# 评审裁剪副本说明

本仓库是鱼搭后端完整私有仓库的**裁剪副本**，只用于让评审人员核验项目的真实性与 AI 协同开发过程。使用范围受 [LICENSE](LICENSE) 约束。

## 保留了什么

- **完整的提交时间线**：2026-09-27 起的 169 次提交（作者、时间、提交说明原样保留），可看出项目是怎样逐步长出来的。
- **AI 协同开发的过程材料**：`AGENTS.md`（给 AI 编码助手的项目约定）、`.planning/`（任务计划、发现与进度记录）、`docs/`（设计、验收、复盘报告）、`progress.md`、`task_plan.md`、`findings.md`。
- **工程代码**：账号与安全、会话与登录限流、任务执行器与资源许可、图片入柜与处理工作流、对象存储、OOTD 与个人生成的任务管理、助手会话与工具、管理接口与契约、便携版启动器、数据库迁移、CI 配置，以及对应的测试。

## 移除了什么

从**当前文件和全部 Git 历史**中移除，历史里也不会再出现这些文件：

| 类别 | 路径 |
|---|---|
| 推荐引擎 | `src/main/java/com/smartcloset/recommend/`、`src/test/java/com/smartcloset/recommend/` |
| 规则与画像数据 | `src/main/resources/recommend/`（审美规则、风格画像、搭法模板、特征表、年龄线索） |
| 提示词 | `src/main/resources/recognition/`、`src/main/resources/ootd/` |
| 语义检索与相似衣物 | `src/main/java/com/smartcloset/semantic/`、`similar/` 及其测试 |
| 设计与报告 | 与推荐、审美、风格画像、搭配、年龄感相关的 `docs/design/`、`docs/reports/` 文档；`.planning/recommend-*` |
| 实验脚本 | 若干评估与自检脚本 |
| 大文件 | 超过 1 MB 的图片、设计稿，`reports/`、`samples/`、`output/`、`data/`、`models/`、构建出的前端静态资源 |

## 注意

- 因为核心包被移除，**本仓库无法编译运行**，也不保证测试能通过。
- 想要体验完整功能，请使用在线演示，或解压便携版（见 [COMPETITION.md](COMPETITION.md)）。
- 提交的哈希与完整私有仓库不一致（历史被重写过），但作者、时间和提交说明保持不变。
