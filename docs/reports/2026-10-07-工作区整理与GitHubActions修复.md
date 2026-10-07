# 工作区整理与 GitHub Actions 修复

日期：2026-10-07。用户授权整理前后端全部待处理文件、提交进度文档、创建同名的前端远程仓库并推送、修复后端最近失败的 Action。

## 已确认失败原因

后端最近两个失败运行：

- [37459486771](https://github.com/yumengjh/smart-closet-server/actions/runs/37459486771)，提交 `28264f5`，196 项中 3 个页面断言失败。
- [37206675149](https://github.com/yumengjh/smart-closet-server/actions/runs/37206675149)，提交 `e1d25a0`，174 项中相同的 3 个断言失败。

失败均为 `OutfitApiTest.outfitHistoryLinksServeTheVueShell`、`WorkflowApiTest.batchPageSupportsDirectOpenAndRefresh`、`WorkflowApiTest.productionVueBundleAndHistoryRoutesAreServedBySpring` 收到 404。测试依赖本机 `build:server` 生成的 `static/index.html` 和 JS，但这些产物已经退出 Git；GitHub 干净检出中不存在。后端镜像本来采用独立部署，不应重新提交压缩前端包来掩盖问题。

## 修正

- 新增 `src/test/resources/spa-test` 明确标注的测试 HTML 与静态 JS，仅两个相关 HTTP 测试使用该资源位置。测试继续验证真实 HTTP 200、history 转发、应用挂载点、测试标识和引用 JS 可读，不跳过断言，不冒充真实 Vue 验收。
- 后端 CI 在 Maven verify 后检查打包结果，拒绝 `spa-test`、本机 SPA 页面或 assets 混入后端生产包。
- 原三个 404 消除后，干净检出发现人工快速入柜恢复测试与后台扫描的时序竞争；只在该测试关闭执行器并结束后重建上下文，不放松生产 revision 校验、不新增付费重试。
- 后端 Docker 上下文排除本地配置、工具目录、规划、临时输出及忽略的前端构建产物；Agent 上下文排除 node_modules、dist、环境文件和日志。
- 前端新增独立 `Frontend checks`：Node 24、npm ci、测试、类型检查、关闭模拟的构建，不需要相邻后端、不调用真实 AI。

## 工作区与仓库

- `.archify/`、`output/`、缓存、临时目录、日志和 `.planning/.active_plan` 等留在本地并忽略，没有删除私人文件。
- 原始 `.planning/platform-agent-ootd` 三份 Markdown 及本次整理进度进入版本管理，早期阶段快照与后续实施状态明确区分。
- 前端既有 `waiting.ts` 改动保留并提交：同时显示所有运行步骤，取消提示与等待兜底保留。
- 创建 [yumengjh/smart-closet](https://github.com/yumengjh/smart-closet) 私有仓库，名称沿用文件夹；配置 origin 并推送完整 main 历史。与后端私有范围一致。
- 前端 README 补齐平台功能、相邻检出方式、可在 GitHub 打开的完整设计和 299 项清单链接。
- 上传前核对可达前端历史 394 个文本 blob，未发现所检查的常见凭据模式；`.env`、`.local` 不在可达 Git 历史中。

## 本地与远端验证

在独立归档目录重建源码，不复用本机数据库、私有配置、页面包或 node_modules。

| 验证 | 结果 |
|---|---|
| 后端 Maven verify | 225 项：218 通过、7 跳过；0 失败／错误；打包成功 |
| 后端包内容门禁 | 无测试 fixture、SPA index 或 assets |
| Agent npm ci／测试／构建 | 16／16 通过，构建成功 |
| 前端 npm ci／测试／类型检查／构建 | 55／55 通过，构建成功 |
| 前端第一次 GitHub Action | [37592782235](https://github.com/yumengjh/smart-closet/actions/runs/37592782235) 全部成功 |
| 后端修复后 GitHub Action | 修复提交推送后等待远端测试、两种镜像构建及 GHCR 发布，完成后补充 |

此前提交前报告的“226 项”聚合包含 `target/surefire-reports/TEST-com.smartcloset.workflow.ParallelHttpWorkflowTest.xml` 旧残留。该测试源已不在当前版本；本次干净检出的实际数为 225，历史提交不重写，通过本记录更正。7 项跳过仍为 1 项可选真实识别和 6 项样本／图片检查。

本机 Docker 守护进程未运行，因此镜像不宣称本地构建通过，以 GitHub 实际结果为准。本次没有真实 AI 调用，也没有更改 8080 运行服务。
