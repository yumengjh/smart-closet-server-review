# 项目约定

- 本仓库的网页产物在 src/main/resources/static；前端源码位于相邻的 ../smart-closet。修改界面先修改前端源码，再使用 npm run build:server 发布，禁止手工改压缩产物。
- 所有可见前端交互控件统一使用 Ant Design Vue，禁止混杂原生 select、checkbox、textarea、浏览器 prompt/confirm；定制外观也沿用组件库。前端 AGENTS.md 有完整 UI 约定。
- 全局助手覆盖主布局右侧，不挤压主界面；可固定、拖动、关闭，右下角 SVG 表情球唤起。音效复用已有开关，推荐组合用小卡片加大模态框。
- 上述 Ant Design Vue 约定只针对鱼搭用户端（../smart-closet）。独立管理平台 ../smart-closet-admin（克隆自 springboot-admin 模板）沿用 Element Plus，不受此条约束；鱼搭对其只提供 /api/manage/v1 中立管理接口（默认关闭），详见 docs/design/2026-10-07-管理平台接入与可插拔管理接口设计.md。
- management 包可插拔：核心包不得依赖 com.smartcloset.management，契约以 docs/management-api.yaml 为准。
- 智能搭配推荐约定（总设计 docs/design/2026-10-08-智能搭配推荐引擎与语义召回设计.md）：能用代码算的不调模型（召回、组合、打分、换一批、换一件、今日灵感、搜索、取色都必须零模型调用）；硬规则只留底线（结构、用户明确表达、男性×女款），其余一律加权扣分并写依据；推荐相关改动必须同步更新总设计、对应实现设计和帮助文章；好坏搭配考试题（GoldenOutfitsTest）必须通过，失败时先判断题与规则谁错，不得为通过而删题；不向模型发送衣物完整资料、性别或身材数据；服务不得自动下载模型；推荐引擎已于 P4 收官，新想法先放进总设计 10.1 后续池再单独确认。
