# 项目约定

- 本仓库的网页产物在 src/main/resources/static；前端源码位于相邻的 ../smart-closet。修改界面先修改前端源码，再使用 npm run build:server 发布，禁止手工改压缩产物。
- 所有可见前端交互控件统一使用 Ant Design Vue，禁止混杂原生 select、checkbox、textarea、浏览器 prompt/confirm；定制外观也沿用组件库。前端 AGENTS.md 有完整 UI 约定。
- 全局助手覆盖主布局右侧，不挤压主界面；可固定、拖动、关闭，右下角 SVG 表情球唤起。音效复用已有开关，推荐组合用小卡片加大模态框。
- 上述 Ant Design Vue 约定只针对鱼搭用户端（../smart-closet）。独立管理平台 ../smart-closet-admin（克隆自 springboot-admin 模板）沿用 Element Plus，不受此条约束；鱼搭对其只提供 /api/manage/v1 中立管理接口（默认关闭），详见 docs/design/2026-10-07-管理平台接入与可插拔管理接口设计.md。
- management 包可插拔：核心包不得依赖 com.smartcloset.management，契约以 docs/management-api.yaml 为准。
- 智能搭配推荐约定（总设计 docs/design/2026-10-08-智能搭配推荐引擎与语义召回设计.md）：能用代码算的不调模型（召回、组合、打分、换一批、换一件、今日灵感、搜索、取色都必须零模型调用）；硬规则只留底线（结构、用户明确表达、男性×女款），其余一律加权扣分并写依据；推荐相关改动必须同步更新总设计、对应实现设计和帮助文章；好坏搭配考试题（GoldenOutfitsTest）必须通过，失败时先判断题与规则谁错，不得为通过而删题；不向模型发送衣物完整资料、性别或身材数据；服务不得自动下载模型；推荐引擎已于 P4 收官，新想法先放进总设计 10.1 后续池再单独确认。
- 全身穿搭约定（docs/design/2026-10-09-全身穿搭与呈现增强设计.md）：配件（袜子/裤袜、头饰、帽子、包、手持物、围巾）只做建议、不改总分；品类分类按 OutfitRules 的固定顺序匹配，前端 roleFromGarment 必须同步；整套穿搭样本的原图审核完成即删除，提取不得包含人脸、身份、年龄、身材等可识别信息，疑似未成年自动拒收；“好看”由管理员审核决定，模型只描述；样本生成的模板必须经过模板审核才生效。
- 管理接口约定（docs/MANAGEMENT_API.md、docs/management-api.yaml）：新增或修改 /api/manage/v1 路由必须同步 yaml（ManagementContractTest 守护）；管理模块只能单向依赖核心、所有 bean 受开关控制；大屏与数仓出口不得返回用户名、衣物名称、图片或聊天内容，明细只用 userKey；运维接口只读，不提供远程重试、取消或改配置。
- 搭配审美约定（docs/design/2026-10-09-搭配审美：风格搭法与语义填位设计.md、docs/design/2026-10-10-搭配审美实现设计.md）：审美规则与参数只改 src/main/resources/recommend/aesthetic-rules.json，避坑表每格都要有结论与理由（AestheticRulesTest 守护）；改规则或参数后必须通过 GoldenOutfitsTest、AestheticSpreadTest、ExposureBalanceTest，并用 RecommendationSnapshotTest 生成快照对比页随改动提交；审美组只加分不写提醒；搭法 describe 只写具体物件、不写风格形容词；搭法须经管理员审核才生效；closet.recommend.* 四个开关必须保持可回到 P4 行为。
- 风格画像约定（docs/design/2026-10-10-风格画像设计.md）：用户风格词（甜、多巴胺、千金……）按 src/main/resources/recommend/style-looks.json 的线索与权重计算，不得再压成 7 个粗风格标签或交给模型映射；改画像数据后必须通过 StyleLooksTest、GoldenOutfitsTest 与 SemanticSearchTest；closet.recommend.looks-enabled=false 必须能回到改动前行为。
- 多模特、OOTD 动作与身材调节约定（docs/design/2026-10-12-多模特、OOTD动作与身材调节设计.md）：动作只以文字进入生图提示词，动作参考图不保存、不发给生图模型，提取结果不含脸、身份、身材与衣着；动作提取的未成年检查默认关闭（closet.ootd.pose-minor-check-enabled）；身材调节只生成新图、不写回资料；模特名称只是标签，不改 revision；改动须通过 ModelsPosesAdjustmentsTest 与 PersonalRendersTest。
