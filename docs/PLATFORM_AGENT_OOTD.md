# 平台级鱼搭、个人资料与 OOTD 接口

> 2026-10-12：新增多模特命名、OOTD 动作（自动/预设/动作卡）与身材调节，见第 7 节与[设计](design/2026-10-12-多模特、OOTD动作与身材调节设计.md)。

更新：2026-10-07。当前已按[真实上游验收](reports/2026-10-07-照片入口补齐与真实AI启用验收.md)开放 yumg 的人物与 OOTD 用途。设计见[完整设计](design/2026-10-06-平台级鱼搭助手与个人OOTD设计.md)，交付与验证边界见[实施验收](reports/2026-10-07-平台级鱼搭与个人OOTD实施验收.md)。

新增接口沿用登录会话、首次改密和 CSRF。客户端通过 `/api/auth/csrf` 获取令牌，写请求使用现有 `X-XSRF-TOKEN` 和会话 Cookie；跨域仍遵守当前允许源与凭据设置。账号归属来自登录身份，不能在请求中指定其他账号。所有示例 ID、版本和供应商名称均须替换为当前账号实际返回值。

## 1. 衣柜事实与穿搭检查

| 方法与路径 | 输入 | 返回与边界 |
| --- | --- | --- |
| GET `/api/closet/summary` | 可选 q、category、color、season、favorite | count 为匹配 ACTIVE 记录数；totalActive、statuses、categoryCounts、seasonCounts、skirtCount、unknownCategoryCount、unknownSeasonCount、filter、queriedAt、wardrobeVersion、mappingVersion、scope |
| PATCH `/api/garments/{id}` | revision、name、favorite | 名称和收藏的小修改，检查账号与版本；字段形式见现有衣物接口 |
| POST `/api/outfit-reviews` | 已保存 outfitId + revision，或当前 scene；可选 selectedGarmentIds、wearRoles、multiLayer | items、slots、blockers、warnings、ruleVersion、fingerprint；免费，不识图、不生图 |

统计分组描述全部 ACTIVE 库存，count 描述本次筛选。类别族中 DRESS 与 SKIRT 分别计数，skirtCount 为两者之和；季节仅有效春夏秋冬标签，交叉计数不能相加当作库存总数。待入柜、回收状态单列；零匹配不回退。含未知季节标签的记录进入 unknownSeasonCount，已有有效标签仍可计入相应季节。

已保存检查示例：

```json
{
  "outfitId": "当前已保存搭配UUID",
  "revision": 3,
  "selectedGarmentIds": ["本次上装UUID", "本次下装UUID"],
  "wearRoles": {},
  "multiLayer": false
}
```

selectedGarmentIds 省略时检查全部真实衣物，传空数组表示没有选择物品。物品须来自该画板，复制贴纸只计一次。角色依据真实品类，wearRoles 不能将外套伪装成主体；明确多层时才可将合适 TOP 指定为 INNER。首饰位置可指定 `ACCESSORY:EAR`、NECK、WRIST、HAND_LEFT、HAND_RIGHT、TIE；与已知品类冲突仍阻止。

日常主体为 TOP+BOTTOM 或 ONE_PIECE，最多一个 OUTER。双外套始终阻止。连体主体不要求另选裤子，未选鞋只有警告。检查通过仅表示结构符合规则，不证明真实尺寸合身。

## 2. 个人资料与基准人物

| 方法与路径 | 用途 |
| --- | --- |
| GET / PUT `/api/me/wearer-profile` | 查看、按 revision 保存真实资料 |
| GET `/api/me/wearer-profile/render-preview` | 免费计算本次人物生成默认值和来源 |
| GET / POST `/api/me/person-baselines` | 列表、明确提交单张人物任务 |
| POST `/api/me/person-baselines/upload-and-generate` | multipart 的 file 与 application/json request；上传并建立一次人物任务 |
| GET `/api/me/image-render-submissions/{key}` | 按原Idempotency-Key核对本人提交；found/job安全摘要，no-store，不执行生成 |
| GET `/api/me/person-baselines/{id}` | 人物候选/确认状态和版本 |
| POST `/api/me/person-baselines/{id}/confirm` | `{ "revision": 当前版本 }`，核对满意后设为默认 |
| DELETE `/api/me/person-baselines/{id}` | 同样带 revision，独立删除人物 |
| GET `/api/me/reference-photos` | 本人已上传全身照列表 |
| DELETE `/api/me/reference-photos/{id}` | 仅删除该原照，已生成人物与 OOTD 保留 |
| GET `/api/me/private-media/{id}` | 本人私有 PNG；no-store，其他账号含管理员不可读 |

资料保存是完整资料表单，局部 Agent 补丁由命令服务合并。字段：revision、sex（MALE/FEMALE/null）、heightCm、weightKg、bodyShape、measurements、preferences。measurements 支持 chest/waist/hips/shoulder/inseam，单位 cm；preferences 支持 fit/style/dislikes/thermal。未填写数值为 null，未填写围度移除键，不能传零代替缺失。defaultBaselineId 由确认人物接口管理。

生成须性别已填写。其他资料缺失使用快照默认：男170cm、女160cm，体重缺失为22×有效身高米²，保留一位小数；体型标准模板。默认值不写回真实资料、记忆或衣柜。

人物提交请求带 `Idempotency-Key`：

```json
{
  "profileRevision": 2,
  "provider": "已配置并验证的供应商",
  "model": "已验证模型",
  "photoId": null
}
```

照片可选，支持全身或带脸半身参考；不可见身体部分采用资料或模板，不测量或推断真实身体。使用照片时 photoId 指向本人 PHOTO。上传并生成使用同一请求且不重复弹确认。提交前须展示实际快照、照片、供应商及一个图像额度。资料修改不自动生成，完成后候选也不自动覆盖默认人物。

上传提交具有60秒客户端时限。连接丢失或响应无法读取时，先按原提交键免费核对，找到则展示原任务；查询不可用时不允许再次提交。明确未找到后，用户可复用原键与冻结参数重新提交，不自动重发。即时状态与错误均在当前确认弹窗展示。修复验收见[提交反馈与断网恢复](reports/2026-10-07-基准人物提交反馈与断网恢复修复.md)。

## 3. OOTD 准备与任务

| 方法与路径 | 用途 |
| --- | --- |
| POST `/api/ootd/preparations` | 已保存搭配与确认人物的免费检查、冻结与10分钟准备 |
| POST `/api/ootd/jobs` | `Idempotency-Key`，`{ "preparationId": "准备ID" }`，确认后提交单张 |
| GET `/api/ootd/jobs` / GET `/api/ootd/jobs/{id}` | 本人持久结果与安全状态 |
| POST `/api/ootd/jobs/{id}/cancel` | 停止本平台后续发布，UNKNOWN 也可停止 |
| DELETE `/api/ootd/jobs/{id}` | 永久删除该 OOTD，不进入回收站 |
| PUT `/api/ootd/jobs/{id}/feedback` | `{ "content": "衣物花纹变化" }`，不会自动重生 |
| GET `/api/me/image-render-jobs` | 人物和 OOTD 安全任务列表 |
| POST `/api/me/image-render-jobs/{id}/cancel` | 统一个人图片任务取消入口 |

准备请求：outfitId、revision、baselineId、baselineRevision、selectedGarmentIds、wearRoles、multiLayer、provider、model、useOlderBaseline。最后一项只有资料已变且用户明确继续旧人物时为 true。搭配必须已保存且与当前画板一致；页面不能用旧保存版本替代未保存修改。

准备返回清单、阻断/提示、版本、快照和额度信息，确认卡展示这些具体内容。缺鞋可继续，生成要求移除基准鞋；缺主体不能靠人物基础衣服补齐。直接参考只有已确认人物及本次衣物，原全身照不作为 OOTD 输入。

重复确认/重连使用原幂等键；同一个准备即使换键也只建立一个任务。过期或来源/授权/模型能力变化须免费重新准备。状态包含 QUEUED、WAITING_RESOURCE、RUNNING、SUCCEEDED、FAILED、UNKNOWN、CANCEL_REQUESTED、CANCELLED、DELETE_PENDING、DELETED。已派发 UNKNOWN 不自动重发；关闭页面不取消任务。取消不能保证供应商停止或返还费用。

每次人物或 OOTD 一张，沿用现有每日图像额度一个单位。检查、准备与查看不扣额度。额度不足不跨天自动生图。结果保留到用户独立删除；来源搭配、照片或人物删除不会自动删除历史 OOTD。

## 4. 两份记忆与命令

| 方法与路径 | 输入与行为 |
| --- | --- |
| GET `/api/agent/memory-documents` | GENERAL / SENSITIVE 原始 Markdown 与 revision |
| PUT `/api/agent/memory-documents/{kind}` | revision、content；明确保存，冲突保留客户端草稿 |
| GET `/api/agent/memory-permissions` | 当前敏感用途许可 |
| POST `/api/agent/memory-permissions` | purpose（OUTFIT/BASELINE/OOTD）、敏感文档 revision |
| DELETE `/api/agent/memory-permissions/{purpose}` | 撤销用途，停止后续输入 |
| GET `/api/agent/commands/{id}` | 查看具体参数、状态和结果 |
| POST `/api/agent/commands/{id}/confirm` | 10分钟内确认本命令；再次执行返回已有结果 |
| POST `/api/agent/commands/{id}/cancel` | 取消未执行命令 |
| POST `/api/agent/commands/{id}/undo` | 有撤销能力且版本仍对应原结果时恢复 |

敏感保存和用途许可分开；文档改动使旧许可失效。实际调用仅取相关片段，普通查询不读敏感正文。删除/忘记提升上下文 epoch，旧聊天投影不再作为模型输入；用户仍可看原聊天，上游已收到内容不能撤回。

命令由可信后端准备，模型不能指定 owner、确认状态、等级或授权。能力包含具体单件编辑、回收/恢复、批量修改、识别/增强任务启动、取消任务、个人资料补丁、基准人物/OOTD生成及删除、记忆修改。账号密码和管理员设置仍走原页。

当前意图入口采用保守表达规则；服务端再检查工具权限、目标、版本和业务条件。查询、问候、能力咨询不授予搭配/写权限。旧 proposals、memories 和 canvas 兼容接口仍受新规则约束。Spring 与 Node 工具契约必须同时更新。

## 5. 图像用途能力配置

GET `/api/render-capabilities` 返回用途能力，无上游密钥。默认无已验证记录，因此新人物/OOTD不能提交；统计、资料和免费检查仍可使用。

管理员 PUT `/api/admin/render-capabilities` 按 provider/model/purpose 登记。字段为 provider、model、purpose（BASELINE/OOTD）、maxReferences（1–20）、verified、version、notes、outputOptions。已有记录须带当前 version；首次可用0。verifiedAt 与 providerRevision 由服务端计算，不能靠客户端声称网关仍兼容。

```json
{
  "provider": "已配置供应商",
  "model": "待验证模型",
  "purpose": "OOTD",
  "maxReferences": 8,
  "verified": false,
  "version": 0,
  "notes": "尚未完成真实多参考和衣物保真验收",
  "outputOptions": {}
}
```

登记只本地校验配置，不调用模型。verified=true 要求实际人工验证证据，notes 不得为空；本次没有做这种真实验证。outputOptions 须兼容所配置供应商，固定输出一张，不以目录模型名称替代用途验证。超过参考数量直接阻止，不丢物品、不静默换模型或改用拼图。

## 6. 数据迁移、私有删除与部署

追加V12/V13，旧账号性别为空；旧PERSONAL记忆迁入敏感文档，不自动授予用途。历史衣物仅确定性分类映射，不批量识别。个人图片不进入衣柜计数或公共人物贴纸。

私有媒体位于专用存储域，删除首先记录代次和撤销访问，再清理文件/回执/副本；失败重试，不提前声称删除完成。任务只检查实际直接依赖：删除照片A阻止用A的在途人物，不阻止只用已生成B的OOTD。私人媒体不默认进入普通资源备份；旧数据库恢复必须保留并应用最新删除清单。

2026-10-07已按用户授权[部署到8080/8091](reports/2026-10-07-8080平台级鱼搭部署记录.md)。其他环境部署前按[实施报告部署与回退说明](reports/2026-10-07-平台级鱼搭与个人OOTD实施验收.md#10-部署与回退操作说明)备份、同步Spring/Node、使用唯一版本JAR并核验迁移与权限。真实模型用途未验证保持关闭；天气仅有扩展协议，真实天气接入后续独立实施。

## 7. 多模特、动作与身材调节（2026-10-12）

| 方法与路径 | 用途 |
| --- | --- |
| PATCH `/api/me/person-baselines/{id}` | `{ "name": "长发的我" }`，1–20 字；不改 revision |
| POST `/api/me/person-baselines/{id}/confirm` | 新增可选 `makeDefault`（默认 true）；false 只确认、不换默认 |
| POST `/api/me/person-baselines`、`…/upload-and-generate` | 请求可带 `name`，省略时为「模特 N」 |
| GET `/api/ootd/poses` | 预设动作、我的动作卡、今日提取次数与上限 |
| POST `/api/ootd/poses/extract` | multipart `file`；同步识图提取动作卡，原图不保存；每天 20 次 |
| POST `/api/ootd/poses` | `{ name, description, tags }` 文字新建 |
| PATCH / DELETE `/api/ootd/poses/{id}` | 改名、删除 |
| POST `/api/ootd/jobs/{id}/adjustments` | `Idempotency-Key`；`{ build, shoulders, waist, hips, legs }` 各 −2..2；新图，原图保留，1 个图像额度 |

准备请求新增可选 `pose`：`{ "mode": "AUTO" }`（默认）、`{ "mode": "PRESET", "id": "hand-on-hip" }`、`{ "mode": "CUSTOM", "id": "动作卡ID" }`；准备响应返回解析后的 `pose`。任务新增 `parentJobId`（身材调节的原图）与 `summary`（模特名、动作名、调整值，仅展示）。
