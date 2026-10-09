# 数字衣柜首版：运行、接口与交付

本版提供受邀账号独立衣柜，覆盖单件照片录入、真实模型处理、人工核对入柜、信息编辑和回收站。Vue 工程位于同级 `smart-closet/`，正式构建由 Spring Boot 同源提供。默认使用真实 API；显式演示模式标记示例数据并禁止提交模型任务。

界面支持浅色和深色主题，可在登录页及应用顶栏切换；选择保存在当前浏览器。登录页左侧展示随主题切换配色的鱼搭专属 SVG 插画，窄屏会隐藏插画以保留表单空间。主题只影响界面配色，穿搭画板和导出的图片保持白色底板。

## 启动与更新

本机验证环境为 Java 21、Maven 3.9+、Node.js 24.18.0、npm 11.16.0。后端模型及服务商配置见 [配置示例](generation.example.yml) 和 [图片工作流](IMAGE_WORKFLOWS.md)。真实密钥放在忽略的 `.local/generation.yml`，上传图片及 H2 数据位于忽略的 `data/`。

```sh
cd ../smart-closet
npm ci
npm run build:server
cd ../smart-closet-server
mvn -DskipTests package
```

首次建库时创建管理员，初始密码请自行替换：

```sh
CLOSET_BOOTSTRAP_USERNAME=admin CLOSET_BOOTSTRAP_PASSWORD='请替换为至少12位的初始密码' mvn spring-boot:run
```

日常可使用 `mvn spring-boot:run`。独立运行 JAR 时，从工程根目录启动；把构建包复制到独立路径，后续 Maven 打包不会替换正在运行的文件：

```sh
mkdir -p .local/runtime
cp target/smart-closet-server-0.0.1-SNAPSHOT.jar .local/runtime/smart-closet-workflow.jar
java -jar .local/runtime/smart-closet-workflow.jar
```

更新已有运行版本时先等待活动任务终止，停止服务并备份 `data/` 和旧运行包，再替换运行 JAR。打开 `http://127.0.0.1:8080/login`，刷新浏览器以加载新构建。

前端开发使用 `npm run dev`，由 Vite 将 `/api` 代理到 `127.0.0.1:8080`；终端显示实际开发地址。局域网运行可在启动后端时设置 `SERVER_ADDRESS=0.0.0.0`，其他设备访问服务电脑的局域网 IP 与 8080 端口，页面与 API 使用同一地址。

## 登录与账号管理

登录使用用户名、密码和同源 HttpOnly 会话 Cookie；写请求携带从 `/api/auth/csrf` 获取的 `X-XSRF-TOKEN`。会话由 Spring Session JDBC 保存到现有数据库，服务重启后仍可恢复。默认空闲期限和 Cookie 有效期均为 7 天，可分别通过 `CLOSET_SESSION_TIMEOUT` 与 `CLOSET_SESSION_COOKIE_MAX_AGE` 调整。Cookie 名称为 `YIXU_SESSION`，启用 HttpOnly 和 SameSite=Lax；重新登录轮换会话 ID，主动退出或会话过期后旧 Cookie 无法恢复登录。

初始或重置密码登录后，前端先进入“设置新密码”，设置成功才进入衣柜。改密前不挂载业务页面、不请求任务计数、衣柜列表、字段配置或额度；直接打开业务地址或刷新也遵守相同规则。

| 接口 | 请求或返回 |
| --- | --- |
| `GET /api/auth/csrf` | `{token}`，登录前也可获取 |
| `POST /api/auth/login` | `{username,password}` → `{username,role,mustChangePassword}` |
| `GET /api/auth/me` | 当前用户及首次改密标记 |
| `POST /api/auth/password` | `{currentPassword,newPassword}`；首次改密可留空当前密码，普通改密须提供当前密码 |
| `POST /api/auth/logout` | 结束当前会话 |

用户名支持 2–80 位英文字母、数字、点、下划线及短横线；密码长度为 12–200 个字符。账号由管理命令创建，不开放注册或邮件找回。

以下离线命令使用同一份 H2 文件库，先停止后端并备份数据，执行后再启动服务。命令中示例密码须自行替换：

```sh
java -jar .local/runtime/smart-closet-workflow.jar --closet-command=create-user --username=alice --initial-password='请替换为至少12位的初始密码'
java -jar .local/runtime/smart-closet-workflow.jar --closet-command=create-admin --username=another-admin --initial-password='请替换为至少12位的初始密码'
java -jar .local/runtime/smart-closet-workflow.jar --closet-command=reset-password --username=alice --initial-password='请替换为至少12位的初始密码'
java -jar .local/runtime/smart-closet-workflow.jar --closet-command=disable-user --username=alice
java -jar .local/runtime/smart-closet-workflow.jar --closet-command=enable-user --username=alice
java -jar .local/runtime/smart-closet-workflow.jar --closet-command=claim-legacy --username=admin
```

`claim-legacy` 将未归属的历史任务、衣物和独立分割记录认领给指定的已有账号，关联图片通过任务归属鉴权。旧衣物迁移为待确认，保留图片选择和处理历史。未认领数据不向普通用户开放。管理员测试台为 `/workflow/index.html` 与 `/studio/index.html`。

## 产品接口

以下以当前代码中的真实路由为准，产品列表与操作位于 `/api/closet`，衣物详情和元信息接口沿用 `/api/garments`。

| 接口 | 契约 |
| --- | --- |
| `GET /api/closet/garments` | `status,q,category,color,season,page,size,sort` → `{items,total,page,size}`；页码从 1 开始，前端每页 24 件；`sort` 为 `updated`（默认，最近更新）、`created`（最近添加）、`created_asc`（最早添加）、`favorite`（收藏优先）、`category`、`color`、`name`，文字类按拼音排序、缺值排最后 |
| `POST /api/closet/garments` | multipart：`file` 与 JSON `workflow`；携带 UUID `Idempotency-Key`，返回 202 与任务详情 |
| `GET /api/garments/{id}` | 衣物详情、revision、有效信息、人工覆盖、原图、展示图和处理历史 |
| `PATCH /api/garments/{id}/metadata` | `{revision,fields,resetFields}` |
| `POST /api/garments/{id}/display-image` | `{revision,assetId}` |
| `POST /api/closet/garments/{id}/confirm` | `{revision,assetId,fields,resetFields}`，原子保存信息、选择展示图并入柜 |
| `POST /api/closet/garments/{id}/trash` | `{revision}`，软删除 |
| `POST /api/closet/garments/{id}/restore` | `{revision}`，恢复删除前状态 |
| `POST /api/garments/{id}/recognize` | 可选识别配置，携带 UUID `Idempotency-Key`，新建识别任务 |
| `POST /api/closet/garments/{id}/reprocess` | `{restage,precut,postcut}`，携带 UUID `Idempotency-Key`，新建图片处理任务 |
| `GET /api/closet/tasks` | `page,size`，轻量任务数组；当前界面展示最近 50 个任务 |
| `GET /api/workflows/{id}` | 真实步骤、开始/结束时间、模型调用及图片资产 |
| `POST /api/workflows/{id}/cancel` | 提交取消，等待服务端状态终止 |
| `GET /api/recognition/schema` | `{version,allowCustomValues,fields,schema}`，16 项字段及常见/本人已入柜建议，允许新值 |
| `GET /api/closet/metadata-options` | 本人全部 ACTIVE 衣物的有效值，用于动态筛选 |
| `GET /api/closet/quota` | `{limit,used,resetAt}`；剩余额度由 `limit-used` 计算 |

上传 JSON 的 `mode` 为 `识别并整理`、`仅识别` 或 `本地贴纸`；可选 `restage,precut,postcut,rotationDegrees,generationProvider,generationModel,recognitionProvider,recognitionModel`。普通用户不能编辑任意提示词或参数 JSON，模型许可由服务端校验。

图片限制为 JPEG/PNG、15 MB、最多 1200 万像素；每次一张照片、一件衣物。`fields` 的值均为字符串数组，字段 key 和状态采用 schema，属性值允许新增（options 是建议）：`secondaryColors`、`materialComposition`、`careInstructions` 等名称不可缩写。单值文本中的逗号保留为文本，不拆成多个值。

元信息开放值规则、同义词归并与颜色大类筛选见 [元信息识别](RECOGNITION.md#开放值与复用)。新名称在确认入柜后进入该账号的动态选项，草稿和回收站不提供选项，不跨账号共享。

## 处理、核对与入柜

衣物生命周期 `DRAFT / ACTIVE / TRASHED` 与任务状态分别保存。处理结束并不代表入柜。透明背景检查为 `NEEDS_REVIEW` 时，后续贴纸暂停，原图及已有生成候选继续可见；用户可选择可用图片核对入柜。AI 失败且有可用原图时允许手填信息入柜，明确判定无衣物、多件衣物或照片不可用时要求重新录入。

活动任务结束或取消终止后才能确认或删除。确认入柜同步选中候选的采纳记录；删除保留图片与历史，恢复回到删除前状态，不提供永久清除。

编辑使用 `revision` 防止静默覆盖。`fields: {key: []}` 表示人工清空；`resetFields: [key]` 删除人工覆盖并恢复自动值，同一字段不能同时修改和恢复。过期版本返回 409，前端保留输入，主动核对时保留已修改字段并更新未修改字段，核对后再次保存。

活动任务约每 2 秒查询，失败退避到最多 30 秒，终态停止轮询。刷新从服务端恢复已提交任务，不自动重放收费请求；重新识别和整理均由用户主动提交。终态耗时取步骤及调用结束时间，后续采纳图片不会增加处理耗时。

每日 AI 额度为每账号 20 个任务，按 `Asia/Shanghai` 日期重置；同任务的识别与生图合计一次，本地任务不计。同键复用不重复扣额度，失败不自动返还，队列拒绝不扣除。现有单 worker 与 30 项等待队列保持不变。

## 错误码与排查

| HTTP / code | 含义与界面行为 |
| --- | --- |
| `401 / AUTHENTICATION_REQUIRED` | 未登录或会话失效，返回登录页 |
| `403 / PASSWORD_CHANGE_REQUIRED` | 初始密码尚未修改，返回密码设置页 |
| `403 / CSRF_INVALID` | 写请求安全校验失效，刷新后主动重新提交 |
| `403 / ACCESS_DENIED` | 当前账号无操作权限 |
| `404` | 对象不存在或不属于当前普通用户 |
| `409 / GARMENT_VERSION_CONFLICT` | 信息版本过期，保留输入并核对 |
| `409 / WORKFLOW_ACTIVE` | 仍存在活动任务，等待或取消后继续 |

若出现成片 403，先查看 `/api/auth/me` 的 `mustChangePassword` 及返回的业务错误码；首次登录页面应只有密码设置，不应发起衣柜、任务或 schema 请求。更新构建后刷新页面，确认加载最新入口文件。

## 验证与边界

```sh
# 后端：本地 stub 与回归测试，不调用真实收费模型
mvn verify
# 前端：在 smart-closet 中执行
npm test
npm run typecheck
npm run build:server
```

本次交付记录见 [2026-09-30 验证报告](../reports/closet/README.md)。首版不包含公网部署、穿搭推荐、批量上传、HEIC 转换、永久清除及完整 JSON 工作流编辑器。AI 返回的整理图需要用户对照原图核对款式与细节；透明结构检查不能证明衣物保真。

## 贴纸中断与恢复（2026-09-30）

透明背景检测是本地结构检查，不能把其短耗时当作 AI 生成耗时。`NEEDS_REVIEW` 表示中断，后续 `STICKER / SKIPPED` 表示尚未制作，不能视为贴纸成功。详情新增 `imageProcessing: {jobId,status,stickerRequired,stickerReady,stickerAssetId}`，以最新图片任务的真实成功步骤及对应资产判定；重新识别不会覆盖图片任务状态。

工作台提供「重新抠图并制作贴纸」，使用透明检查前的图片进行本地处理。已有真实透明背景、只触发结构警告时，用户可对照原图核对完整性，采纳图片后继续制作。`POST /api/workflows/{id}/continue` 使用 `FORCE`（重新抠图）或 `REVIEWED`（人工核对透明图），生成独立子任务并保留父任务中断历史；不会再次识图或生图，不占 AI 额度。`REVIEWED` 仅允许已采纳资产的继续处理，仍拒绝不透明或无主体图片，不允许直接提交任意工作流绕过审核。

正常确认接口在贴纸尚未成功时返回 `409 STICKER_NOT_READY`。备用操作「暂用原图入柜」需显式传 `allowIncompleteSticker:true`，且 assetId 必须是衣物上传原图；人工信息与原图仍原子保存，贴纸状态保持未完成。原先已入柜但处理未完成的记录也显示提示，允许继续制作；贴纸完成后选择贴纸展示图并保存修改。衣物状态与任务状态不会相互冒充。
