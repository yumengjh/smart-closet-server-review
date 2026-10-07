# 项目约定

- 本仓库的网页产物在 src/main/resources/static；前端源码位于相邻的 ../smart-closet。修改界面先修改前端源码，再使用 npm run build:server 发布，禁止手工改压缩产物。
- 所有可见前端交互控件统一使用 Ant Design Vue，禁止混杂原生 select、checkbox、textarea、浏览器 prompt/confirm；定制外观也沿用组件库。前端 AGENTS.md 有完整 UI 约定。
- 全局助手覆盖主布局右侧，不挤压主界面；可固定、拖动、关闭，右下角 SVG 表情球唤起。音效复用已有开关，推荐组合用小卡片加大模态框。
- 上述 Ant Design Vue 约定只针对鱼搭用户端（../smart-closet）。独立管理平台 ../smart-closet-admin（克隆自 springboot-admin 模板）沿用 Element Plus，不受此条约束；鱼搭对其只提供 /api/manage/v1 中立管理接口（默认关闭），详见 docs/design/2026-10-07-管理平台接入与可插拔管理接口设计.md。
- management 包可插拔：核心包不得依赖 com.smartcloset.management，契约以 docs/management-api.yaml 为准。
