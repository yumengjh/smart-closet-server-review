# 管理接口接入指南

鱼搭提供一组中立的服务端管理接口（`/api/manage/v1`），让任意后台平台管理业务用户、查看衣柜洞察；1.1 起还提供运维状态、推荐与模型 KPI、大屏快照、数仓数据出口和模板审核（第 9~14 节）。本文面向接入方：如何开启、如何签名、每个接口怎么调、出错怎么查。

| 资料 | 用途 |
|---|---|
| [`management-api.yaml`](management-api.yaml) | OpenAPI 3.1 契约，唯一事实源；可导入 Swagger Editor、Apifox、Postman 或生成客户端 |
| [设计文档](design/2026-10-07-管理平台接入与可插拔管理接口设计.md) | 设计取舍、数据口径、实现记录 |
| [二期设计](design/2026-10-09-管理接口二期：运维、KPI与大屏数据设计.md) | 运维、KPI、大屏、数仓出口与模板审核的口径与决策（M1~M10） |
| [`scripts/manage-curl.sh`](../scripts/manage-curl.sh) | 命令行签名调用工具 |
| [部署拓扑](DEPLOYMENT_TOPOLOGY.md) | 与管理平台一起部署时的服务、端口与密钥关系 |

契约由 `ManagementContractTest` 守护：实现的路由与 yaml 必须一一对应，多一个或少一个都会测试失败。

## 1. 定位与边界

- **默认关闭。** 未开启时管理模块不注册任何 Bean 与路由，鱼搭行为与未引入该模块时完全一致。
- **服务对服务。** 只接受 HMAC 签名请求；不使用 Cookie、不创建会话、不开放 CORS，浏览器无法直接调用。
- **不认识宿主平台。** 接口不带任何平台专属概念（权限码、信封结构、审计表）。权限判断、审计、格式转换由接入方自己完成。
- **只管账号、统计与只读运维。** 不提供修改用户衣物、读取图片或私密媒体（体型照片、基准人物）的能力；个人资料只返回“是否已填写”。1.1 新增的写操作只有模板审核；运维接口不能重试、取消任务或改配置。
- **大屏与数仓出口不带用户名。** 大屏只给汇总，明细事件只给不可逆的 `userKey`。

## 2. 开启

| 环境变量 | 默认 | 说明 |
|---|---|---|
| `CLOSET_MANAGEMENT_ENABLED` | `false` | `true` 开启 |
| `CLOSET_MANAGEMENT_KEY_ID` | `primary` | 密钥 ID，1–64 位 `[A-Za-z0-9_.-]` |
| `CLOSET_MANAGEMENT_SECRET` | 空 | 共享密钥，按 UTF-8 原文参与 HMAC，至少 32 字节 |

生成密钥：

```bash
openssl rand -base64 48
```

生成结果原样填入鱼搭与接入方两边，不要再做 base64 解码。开启后缺少密钥、密钥过短、ID 重复都会让启动直接失败并给出中文原因。

其他可调项（`application.yml` 的 `closet.management.*`）：

| 配置 | 默认 | 说明 |
|---|---|---|
| `auth.clock-skew` | `300s` | 请求时间戳允许的偏差 |
| `auth.nonce-ttl` | `600s` | nonce 防重放记忆时长，必须 ≥ 2 × clock-skew |
| `max-body-bytes` | `65536` | 请求体上限，超出返回 413 |
| `insights-cache-ttl` | `60s` | 洞察结果缓存时长，`0s` 关闭缓存 |

### 密钥轮换

`auth.keys` 是列表，可以同时配置新旧两把密钥：

```yaml
closet:
  management:
    auth:
      keys:
        - id: primary
          secret: ${CLOSET_MANAGEMENT_SECRET}
        - id: next
          secret: ${CLOSET_MANAGEMENT_SECRET_NEXT}
```

步骤：鱼搭加入新密钥并重启 → 接入方切换到新 `key-id` 与密钥 → 鱼搭删除旧密钥并重启。

### 网络

鱼搭默认只监听 `127.0.0.1`。管理接口不应暴露到公网；若必须经过反向代理，请在代理层对 `/api/manage/**` 限制来源 IP。签名只证明“持有密钥”，不能代替网络隔离。

## 3. 签名

### 3.1 请求头

| 头 | 必填 | 说明 |
|---|---|---|
| `X-Closet-Key-Id` | 是 | 密钥 ID |
| `X-Closet-Timestamp` | 是 | Unix 毫秒 |
| `X-Closet-Nonce` | 是 | 16–64 位 `[A-Za-z0-9_-]`，每个请求唯一 |
| `X-Closet-Signature` | 是 | 签名，见下文 |
| `X-Operator` | 否 | 宿主平台的操作人，≤128 字符单行；写入鱼搭操作日志 |
| `X-Request-Id` | 否 | 追踪 ID，≤128 字符 `[A-Za-z0-9._:-]`；响应原样回写，不合法时鱼搭生成新 ID |

`X-Operator` 与 `X-Request-Id` 都参与签名，篡改即失效。

### 3.2 规范串

九行，用 `\n` 连接，末尾没有换行：

```
CLOSET-HMAC-SHA256
<HTTP 方法，大写>
<原始路径，不含 query，不解码>
<规范化 query>
<X-Closet-Timestamp>
<X-Closet-Nonce>
<X-Operator，没有则为空串>
<X-Request-Id，没有则为空串>
<请求体 SHA-256，小写十六进制；空请求体为 e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855>
```

**规范化 query：**

1. 按 `&` 拆分，丢弃空段；每段按第一个 `=` 拆成名和值（没有 `=` 时值为空串）。
2. 名和值各自按表单规则解码（`+` 视为空格，`%XX` 解码为 UTF-8）。
3. 再按 RFC 3986 编码：`A-Z a-z 0-9 - . _ ~` 原样保留，其余字节编码为大写 `%XX`。
4. 以**编码后**的名排序，名相同再以编码后的值排序（按字节序）。
5. 用 `名=值` 形式以 `&` 连接；没有参数时为空串。

例：`size=1&q=a+b` → `q=a%20b&size=1`；`metric=image_units&metric=garments_created` → `metric=garments_created&metric=image_units`。

**签名：**

```
signature = base64url_无填充( HMAC-SHA256( key = 密钥的 UTF-8 字节, message = 规范串的 UTF-8 字节 ) )
```

**请求体：** 签名用的字节必须与实际发送的字节完全相同。先把 JSON 序列化成字节，再用同一份字节计算哈希并发送；不要让 HTTP 客户端在签名之后重新序列化。

### 3.3 测试向量

两端实现都应通过这个固定向量（鱼搭 `HmacCanonicalTest`、管理后端 `ClosetRequestSignerTest` 均断言它）：

| 字段 | 值 |
|---|---|
| 密钥 | `0123456789abcdef0123456789abcdef` |
| 方法 / 路径 | `GET` `/api/manage/v1/users` |
| 原始 query | `size=1&q=a+b` |
| 时间戳 / nonce | `1790000000000` / `nonce-0123456789abcdef` |
| X-Operator / X-Request-Id | `admin` / `trace-1` |
| 请求体 | 空 |

规范串：

```
CLOSET-HMAC-SHA256
GET
/api/manage/v1/users
q=a%20b&size=1
1790000000000
nonce-0123456789abcdef
admin
trace-1
e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
```

签名：`QrNFOGRrxbMHYQK3h_5AzEq2QbFkYErcWAuygoZ8r88`

### 3.4 参考实现

**Python**（已用上面的向量验证）：

```python
import base64, hashlib, hmac
from urllib.parse import quote, unquote_plus

def canonical_query(raw_query: str) -> str:
    pairs = []
    for part in filter(None, raw_query.split("&")):
        name, _, value = part.partition("=")
        enc = lambda s: quote(unquote_plus(s), safe="-._~")
        pairs.append((enc(name), enc(value)))
    return "&".join(f"{n}={v}" for n, v in sorted(pairs))

def sign(secret: str, method: str, path: str, raw_query: str, timestamp: str, nonce: str,
         operator: str = "", request_id: str = "", body: bytes = b"") -> str:
    canonical = "\n".join([
        "CLOSET-HMAC-SHA256", method.upper(), path, canonical_query(raw_query),
        timestamp, nonce, operator, request_id, hashlib.sha256(body).hexdigest(),
    ])
    digest = hmac.new(secret.encode(), canonical.encode(), hashlib.sha256).digest()
    return base64.urlsafe_b64encode(digest).rstrip(b"=").decode()
```

**Java：** 鱼搭的 [`HmacCanonical`](../src/main/java/com/smartcloset/management/auth/HmacCanonical.java) 只依赖 JDK，可直接复制；调用方式见测试中的 [`ManagementSigner`](../src/test/java/com/smartcloset/management/ManagementSigner.java)：

```java
byte[] body = json.getBytes(StandardCharsets.UTF_8);
String timestamp = Long.toString(System.currentTimeMillis());
String nonce = UUID.randomUUID().toString().replace("-", "");
String canonical = HmacCanonical.canonical("PATCH", "/api/manage/v1/users/alice/status", null,
        timestamp, nonce, "ops-zhang", "trace-42", body);
String signature = HmacCanonical.sign(secret, canonical);
```

**Shell：**

```bash
export NO_PROXY=127.0.0.1,localhost        # 本机配置了 HTTP 代理时必须加
export CLOSET_MANAGEMENT_URL=http://127.0.0.1:8080
export CLOSET_MANAGEMENT_SECRET='与服务端相同的密钥'
scripts/manage-curl.sh GET '/api/manage/v1/users?size=5'
scripts/manage-curl.sh PATCH /api/manage/v1/users/alice/status '{"enabled":false}'
```

## 4. 通用约定

- **基础路径：** `/api/manage/v1`
- **时间：** 字段统一为 Unix 毫秒，名称以 `AtMs` 结尾；日期参数为 `YYYY-MM-DD`，按 `Asia/Shanghai` 解释，`to` 含当天。
- **分页：** 请求 `page`（从 1 开始，默认 1）、`size`（1–100，默认 20）；响应 `{items, total, page, size}`。
- **请求体：** 严格 JSON，含未知字段、尾随内容或格式错误一律返回 `400 MALFORMED_JSON`。
- **幂等：** 启用/禁用、改角色、改额度可安全重试；创建用户对已存在的用户名返回 409。
- **会话撤销：** 禁用、重置密码、改角色都会立即删除该用户的全部登录会话；禁用和重置密码同时吊销便携登录链接。
- **临时密码：** 创建用户或重置密码时不传 `password`，鱼搭生成 20 位临时密码，**只在本次响应中出现一次**。鱼搭不记录它，接入方也不得写入日志或审计。用户首次登录必须修改密码。

## 5. 用户接口

`{username}` 需匹配 `^[A-Za-z0-9_.-]{2,80}$`。

### `GET /users` 用户列表

| 参数 | 说明 |
|---|---|
| `q` | 用户名包含（忽略大小写），≤80 字符 |
| `status` | `ENABLED` / `DISABLED` |
| `role` | `USER` / `ADMIN` |
| `sort` | `createdAt`（默认）/ `username` / `lastActiveAt` |
| `order` | `desc`（默认）/ `asc` |
| `page`, `size` | 见通用约定 |

```json
{
  "items": [{
    "username": "carol", "role": "USER", "enabled": true, "mustChangePassword": true,
    "createdAtMs": 1791380686514, "lastActiveAtMs": null,
    "activeSessionCount": 0, "garmentCount": 25, "outfitCount": 6
  }],
  "total": 6, "page": 1, "size": 20
}
```

- `lastActiveAtMs`：该用户未过期会话中最近一次访问时间。会话过期被清理后会丢失，因此是近似值，界面应标注“最近会话活跃”。
- `garmentCount` 只统计已入柜（`ACTIVE`）的衣物。

### `GET /users/{username}` 用户详情

```json
{
  "username": "carol", "role": "USER", "enabled": true, "mustChangePassword": true,
  "createdAtMs": 1791380686514, "lastActiveAtMs": null, "activeSessionCount": 0,
  "garments": { "ACTIVE": 25, "DRAFT": 5 },
  "outfitCount": 6, "outfitPlanCount": 0, "quickIntakeCount": 0,
  "hasWearerProfile": false, "activePortableLinkCount": 0,
  "quota": {
    "image": { "limit": 20, "used": 9, "overridden": false },
    "agent": { "limit": 30, "used": 8, "overridden": false },
    "resetAtMs": 1791388800000
  }
}
```

`garments` 的键是数据库中实际出现的衣物状态，不固定。

### `POST /users` 创建用户

```json
{ "username": "bob", "role": "USER" }
```

`password` 可选（12–200 字符）；不传则生成临时密码。返回 `201`，带 `Location` 头：

```json
{ "username": "bob", "temporaryPassword": "EJiwdqxhvZ7mu7zeQaBn" }
```

### `PATCH /users/{username}/status` 启用 / 禁用

```json
{ "enabled": false }
```

```json
{ "username": "alice", "enabled": false, "revokedSessions": 2 }
```

不能禁用最后一个启用的 `ADMIN`（409 `LAST_ADMIN_PROTECTED`）。

### `PATCH /users/{username}/role` 修改角色

```json
{ "role": "ADMIN" }
```

```json
{ "username": "alice", "role": "ADMIN", "revokedSessions": 1 }
```

角色不变时 `revokedSessions` 为 0；不能降级最后一个启用的 `ADMIN`。

### `POST /users/{username}/password-reset` 重置密码

请求体 `{}` 生成临时密码，或 `{"password": "..."}` 指定新密码：

```json
{ "username": "dave", "temporaryPassword": "GhCJRbmCjKxLWZ8PF8bP", "revokedSessions": 0 }
```

### `GET /users/{username}/sessions` / `DELETE /users/{username}/sessions` 会话

GET 返回未过期会话，不暴露可用于登录的会话 ID：

```json
[{ "sessionRef": "9f2c4e1a0b7d3c55", "createdAtMs": 0, "lastAccessAtMs": 0, "expiresAtMs": 0, "portable": false }]
```

`sessionRef` 是 SHA-256(会话主键) 的前 16 位，仅用于区分会话；`portable` 表示该会话由便携登录链接建立。DELETE 删除全部会话，返回 `{"revokedSessions": n}`。

### `GET /users/{username}/portable-links` / `DELETE /users/{username}/portable-links/{linkId}` 便携登录链接

GET 返回最近 20 条：

```json
[{ "id": "…", "createdAtMs": 0, "expiresAtMs": 0, "sessionExpiresAtMs": null, "status": "PENDING" }]
```

`status`：`PENDING` 未使用、`USED` 已用于登录（会话有效期内）、`EXPIRED`、`REVOKED`。DELETE 成功返回 204；链接不属于该用户返回 404 `PORTABLE_LINK_NOT_FOUND`；对已吊销的链接再次吊销仍返回 204。

### `GET /users/{username}/quota` / `PUT /users/{username}/quota` 每日额度

PUT 请求的两个字段都取 0–1000 或 `null`（`null` = 恢复默认）：

```json
{ "imageDailyLimit": 50, "agentDailyLimit": null }
```

```json
{
  "image": { "limit": 50, "used": 0, "overridden": true },
  "agent": { "limit": 30, "used": 0, "overridden": false },
  "resetAtMs": 1791388800000
}
```

默认值：图片任务 20 次 / 天，助手消息取 `CLOSET_AGENT_DAILY_LIMIT`（默认 30）。覆盖值立即生效，用户端看到的额度与扣减规则同步变化；`resetAtMs` 为北京时间次日零点。

## 6. 洞察接口（只读）

所有洞察接口都接受可选的 `username`：不传为全站，传入为单个用户（用户不存在返回 404）。`overview` 与 `trends` 还接受 `from`/`to`：默认最近 30 天，区间最长 366 天。结果按参数组合缓存 `insights-cache-ttl`。

**数据口径：**

- 鱼搭**不记录实际穿着**。“使用”指衣物出现在已保存搭配中的次数，同一搭配中重复出现只计一次；OOTD 计划单独统计。
- 品类沿用用户端的 `closet-families-v1` 映射：`TOP` 上装、`SHIRT` 衬衫、`OUTER` 外套、`PANTS` 裤装、`SKIRT` 半身裙、`DRESS` 连衣裙、`ONE_PIECE` 连体衣、`SHOES` 鞋履、`ACCESSORY` 配饰、`UNKNOWN` 未分类。颜色取 `primaryColor`，季节取 `seasons`。
- **采纳率** = 区间内创建、且当前仍是 `ACTIVE` 的衣物占区间内创建衣物的比例。用户之后删除衣物会让历史采纳率下降。
- **活跃用户**依据会话最近访问时间，与用户列表口径一致。

### `GET /insights/overview`

```json
{
  "scope": { "username": null, "from": "2026-09-08", "to": "2026-10-07" },
  "users": { "total": 6, "enabled": 6, "activeInRange": 0, "createdInRange": 6 },
  "garments": { "active": 69, "draft": 18, "createdInRange": 53, "adoptedInRange": 41, "adoptionRate": 0.774 },
  "outfits": { "total": 16, "createdInRange": 16 },
  "outfitPlans": { "createdInRange": 0 },
  "quickIntakes": { "createdInRange": 0 },
  "ai": { "imageUnitsInRange": 525, "agentMessagesInRange": 333 },
  "generatedAtMs": 1791380987884,
  "mappingVersion": "closet-families-v1"
}
```

`users` 只在全站视图返回；区间内没有新衣物时 `adoptionRate` 为 `null`。

### `GET /insights/wardrobe`

只统计 `ACTIVE` 衣物：

```json
{
  "scope": { "username": "carol", "from": null, "to": null },
  "totalActive": 25,
  "categories": [{ "key": "PANTS", "count": 9 }, { "key": "ACCESSORY", "count": 5 }],
  "colors": [{ "key": "米色", "count": 5 }, { "key": "藏青色", "count": 4 }],
  "seasons": [{ "key": "春", "count": 12 }, { "key": "夏", "count": 9 }, { "key": "秋", "count": 14 }, { "key": "冬", "count": 4 }],
  "unknownCategoryCount": 0, "unknownSeasonCount": 5,
  "favoriteCount": 5, "wearingConfirmedCount": 0,
  "mappingVersion": "closet-families-v1"
}
```

颜色返回前 20 项，其余合并为 `其他`；一件衣物有多个季节时分别计数，`seasons` 固定按春夏秋冬返回四项。

### `GET /insights/usage`

```json
{
  "scope": { "username": "carol", "from": null, "to": null },
  "outfitCount": 6,
  "topUsed": [{ "garmentId": "1f91847b-…", "owner": "carol", "name": "白色牛仔裤", "family": "PANTS", "createdAtMs": 1789884780000, "outfitCount": 2 }],
  "idle": { "count": 13, "ratio": 0.52, "sample": [{ "garmentId": "…", "owner": "carol", "name": "…", "family": "ACCESSORY", "createdAtMs": 0, "outfitCount": 0 }] },
  "byFamily": [{ "key": "PANTS", "active": 9, "usedInOutfits": 4 }]
}
```

`topUsed` 最多 10 条；`idle.sample` 最多 20 条，按入柜时间从早到晚排列（越靠前闲置越久）。全站视图用 `owner` 下钻到单个用户。不返回图片地址。

### `GET /insights/trends`

| 参数 | 说明 |
|---|---|
| `metric` | 必填，可重复：`garments_created`、`garments_adopted`、`outfits_created`、`quick_intakes`、`image_units`、`agent_messages`、`users_created`；1.1 新增 `active_users`、`recommendations_shown`、`recommendation_likes`、`recommendation_dislikes`、`wear_logs`、`model_calls`、`model_tokens`、`ootd_samples`（定义见第 10.3 节） |
| `granularity` | `day`（默认）/ `week`（周一为起点）/ `month`（1 日为起点，1.1 新增） |
| `from`, `to`, `username` | 同上 |

```json
{
  "granularity": "week",
  "buckets": ["2026-09-07", "2026-09-14", "2026-09-21", "2026-09-28", "2026-10-05"],
  "series": [
    { "metric": "garments_created", "values": [11, 12, 14, 11, 5] },
    { "metric": "image_units", "values": [88, 133, 85, 142, 77] }
  ]
}
```

空桶补 0，`values` 与 `buckets` 一一对应。

## 7. 错误

错误统一为 RFC 9457 Problem Details（`Content-Type: application/problem+json`）。以 `code` 为准判断错误，`detail` 是给人看的中文说明：

```json
{
  "type": "urn:closet:problem:validation-failed",
  "title": "请求参数不正确",
  "status": 400,
  "detail": "请求参数未通过校验。",
  "instance": "/api/manage/v1/users/dave/quota",
  "code": "VALIDATION_FAILED",
  "requestId": "cli-498a9c939f770af47fd0e5f29863b1ce",
  "violations": [{ "field": "imageDailyLimit", "message": "最大不能超过1000" }]
}
```

| HTTP | code | 含义 | 排查 |
|---|---|---|---|
| 400 | `VALIDATION_FAILED` | 参数或请求体字段校验失败 | 看 `violations` |
| 400 | `MALFORMED_JSON` | 不是合法 JSON、含未知字段或尾随内容 | 对照 yaml 的 `additionalProperties: false` |
| 401 | `MANAGEMENT_SIGNATURE_MISSING` | 缺少签名头或格式不符 | 检查四个必填头和 nonce 字符集 |
| 401 | `MANAGEMENT_KEY_UNKNOWN` | 鱼搭没有这个密钥 ID | 两边 `KEY_ID` 是否一致 |
| 401 | `MANAGEMENT_TIMESTAMP_SKEWED` | 时间戳偏差超过 300 秒 | 校准两台机器时钟；时间戳单位必须是毫秒 |
| 401 | `MANAGEMENT_SIGNATURE_INVALID` | 签名不匹配 | 先用 3.3 的向量验证实现；再核对规范 query 与实际发送字节 |
| 401 | `MANAGEMENT_REPLAYED` | nonce 已用过 | 每个请求（包括重试）生成新 nonce |
| 404 | `USER_NOT_FOUND` | 用户不存在 | — |
| 404 | `PORTABLE_LINK_NOT_FOUND` | 链接不存在或不属于该用户 | — |
| 409 | `USER_ALREADY_EXISTS` | 用户名已存在 | — |
| 409 | `LAST_ADMIN_PROTECTED` | 会禁用或降级最后一个启用的管理员 | 先启用或提升另一个管理员 |
| 413 | `PAYLOAD_TOO_LARGE` | 请求体超过 `max-body-bytes` | — |
| 503 | `MANAGEMENT_BUSY` | 防重放缓存已满 | 稍后重试；持续出现说明请求量异常 |
| 500 | `INTERNAL_ERROR` | 未预期错误 | 用 `requestId` 在鱼搭日志中查找 |

**排查签名失败的常见原因：**

- 签名时用的 query 与实际发出的不同。例如 HTTP 客户端把空格编码成 `+`、把中文再编码一次，或者调整了参数顺序。鱼搭会先解码再规范化，所以 `+` 和 `%20` 都能接受，但被编码两次的值无法还原。
- 签名用的请求体与实际发送的不同，比如签名后又重新序列化 JSON，字段顺序或空格变了。
- 把 base64 生成的密钥先解码成字节再用。密钥应按原文字符串的 UTF-8 字节使用。
- 时间戳用了秒而不是毫秒，结果报 `TIMESTAMP_SKEWED`。
- 本机配置了 HTTP 代理，请求被转到代理，看到的是 502 或连接错误而不是鱼搭的响应。加 `NO_PROXY=127.0.0.1,localhost`。

## 8. 运维

- **操作日志：** 每次写操作都会记一行到 `management_operation_log`：时间、密钥 ID、`X-Operator`、`X-Request-Id`、动作、目标用户、结果、摘要（不含密码）。第一期不自动清理。
- **日志关键字：** 签名被拒时，服务端日志会记录 `management request rejected code=… requestId=…`，但不记录签名值与密钥。
- **关闭：** 把 `CLOSET_MANAGEMENT_ENABLED` 改回 `false` 并重启即可。已建的两张表（`closet_user_quota`、`management_operation_log`）保留；其中额度覆盖值仍然生效，因为额度属于核心功能。需要撤销覆盖，请在关闭前把额度设回 `null`。
- **规模假设：** 全站洞察按用户逐个聚合，适合约 200 个用户、2 万件衣物以内的规模，超出后需要改成批量查询（接口不变）。

## 9. 运维接口（1.1，只读）

### `GET /ops/status`

总体状态与原因、应用与 JVM、存储、模型与索引覆盖、任务队列、服务商与助手运行时、在线会话。**不返回密钥、服务商地址或用户名。** 模型会话占用堆外内存，看内存请看 `jvm.processRssBytes`（进程常驻内存，仅 Linux）而不是堆；抠图模型与图文模型闲置会自动卸载（`loaded: false` 属正常）。图文模型（可选）在 `models.image`：`image`/`text` 两塔各自的 `state`（`READY`/`NOT_INSTALLED`/`LOW_MEMORY`/`FAILED`）、`loaded`、`loads`、`unloads`、`lowMemorySkips`、`requiredFreeBytes`，以及 `availableMemoryBytes`；图像向量覆盖在 `indexes.image`。未安装不算问题，内存不足或文件损坏会进 `issues`（`IMAGE_MODEL_LOW_MEMORY`、`IMAGE_MODEL_FAILED`）。

```json
{
  "status": "DEGRADED",
  "issues": [{ "code": "SEMANTIC_INDEX_BEHIND", "message": "语义索引覆盖 62%，后台仍在补算" }],
  "app": { "version": "0.0.1-SNAPSHOT", "startedAtMs": 1760000000000, "uptimeMs": 86400000, "java": "21.0.8+9" },
  "jvm": { "heapUsedBytes": 412000000, "heapMaxBytes": 2147483648, "nonHeapUsedBytes": 180000000, "threads": 64, "processors": 4, "systemLoadAverage": 0.42, "processCpuLoad": 0.03,
           "processRssBytes": 1450000000, "systemMemoryTotalBytes": 4100000000, "systemMemoryFreeBytes": 1600000000 },
  "storage": { "totalBytes": 107374182400, "usableBytes": 64424509440, "usedRatio": 0.4, "databaseBytes": 52428800 },
  "models": { "segmentation": { "present": true, "loaded": false, "idleUnloadMinutes": 10, "unloads": 3 }, "embedding": { "available": true, "version": "local-text-cls-v1:69a0b846f4f116b5:45bbac6b:512" },
              "image": { "image": { "present": true, "loaded": false, "state": "READY", "idleUnloadMinutes": 10, "loads": 4, "unloads": 3, "lowMemorySkips": 0 }, "text": { "present": true, "loaded": true, "state": "READY", "idleUnloadMinutes": 30 }, "availableMemoryBytes": 1500000000, "version": "cnclip-img:24ef106fe11f57e8" } },
  "indexes": { "semantic": { "indexed": 620, "total": 1000, "coverage": 0.62 }, "color": { "measured": 998, "total": 1000, "coverage": 0.998 } },
  "queue": { "byStatus": { "QUEUED": 3, "RUNNING": 1, "WAITING_QUOTA": 0, "CANCEL_REQUESTED": 0 },
             "byKind": [{ "kind": "SEMANTIC_INDEX", "queued": 3, "running": 1, "waitingQuota": 0, "failed24h": 0 }],
             "oldestQueuedAgeMs": 42000, "failed24h": 0 },
  "providers": { "recognition": { "defaultProvider": "yumg", "configured": true }, "generation": [{ "id": "yumg", "configured": true }],
                 "agentRuntime": { "reachable": true, "latencyMs": 8, "checkedAtMs": 1760086400000 } },
  "sessions": { "active": 35, "onlineLast5Min": 6 },
  "generatedAtMs": 1760086400000
}
```

| issue 代码 | 含义 | 阈值配置 |
|---|---|---|
| `EMBEDDING_MODEL_MISSING` | 向量模型不可用，搜索与推荐降级为字段加关键词 | — |
| `SEGMENTATION_MODEL_MISSING` | 抠图模型未安装 | — |
| `SEMANTIC_INDEX_BEHIND` | 语义索引覆盖率低于阈值（新装模型后会持续几分钟） | `ops.semantic-coverage-warn`（0.9） |
| `QUEUE_BACKLOG` | 最早排队的任务等待超过阈值 | `ops.queue-backlog-warn`（10m） |
| `TASK_FAILURES` | 24 小时失败任务数达到阈值 | `ops.failed-tasks-warn`（10） |
| `DISK_FULL` | 存储所在磁盘使用率达到阈值 | `ops.disk-used-warn`（0.9） |
| `RECOGNITION_NOT_CONFIGURED` | 识图服务商没有密钥 | — |
| `AGENT_RUNTIME_UNREACHABLE` | 助手运行时 `/health` 1 秒内无响应（结果缓存 15 秒） | — |

### `GET /ops/tasks`

后台任务列表，默认只看失败（`FAILED`、`UNKNOWN`、`INTERRUPTED` 需显式传）。参数：`status`（可重复）、`kind`、`page`、`size`。不返回任务载荷；`errorMessage` 截断到 300 字。只读，不提供重试或取消。

### `GET /ops/operation-log`

管理接口自己的操作日志（第 8 节），参数 `from`、`to`、`action`、`targetUsername`、`page`、`size`。

## 10. KPI 接口（1.1）

### 10.1 `GET /insights/recommendations`

| 字段 | 定义 |
|---|---|
| `shown` | 范围内展示的推荐套数 |
| `adopted` / `adoptionRate` | 采用＝点赞、在搭台打开，或展示后 3 天内打卡穿过这一套的全部衣物；按用户分别匹配后汇总 |
| `liked` / `disliked` / `likeRate` | 点赞、点踩；点赞率＝点赞/（点赞+点踩） |
| `openedInWorkspace` / `wornWithin3Days` / `swaps` | 分项 |
| `users` | 收到过推荐的用户数 |
| `bySource` | `PAGE` 推荐区、`AGENT` 助手、`INSPIRATION` 今日灵感、`SWAP` 换一件、`MORE` 换一批 |
| `byRank` | 第 1/2/3 名分别的展示与采用（看排序有没有用） |
| `templates` | 命中模板的展示与采用 Top 10 |
| `daily` | 每天的展示与采用 |

比率在分母为 0 时为 `null`。

### 10.2 `GET /insights/ai`

按功能（`AGENT` 助手、`OOTD_EXTRACT` 整套穿搭样本提取…）的调用次数与 token、生图额度、助手消息数、零模型推荐占比；不传 `username` 时附 token 用量 Top 10 用户（运维排查用，不要放上大屏）。**只给 token 数，费用请用服务商单价换算。**

### 10.3 趋势新增指标

| 指标 | 定义 |
|---|---|
| `active_users` | 当天“有操作”的用户数：新增衣物、新建或编辑搭配、收到推荐、反馈、行为信号、打卡、模型调用、快速入柜、助手消息或生图额度消耗。按桶去重（周值不是 7 个日值相加）。只浏览不操作的不计，属近似 DAU |
| `recommendations_shown` | 展示的推荐套数 |
| `recommendation_likes` / `recommendation_dislikes` | 点赞 / 点踩 |
| `wear_logs` | 打卡次数 |
| `model_calls` / `model_tokens` | 模型调用次数 / 输入+输出 token |
| `ootd_samples` | 上传的整套穿搭样本数 |

## 11. 大屏快照（1.1）

`GET /dashboard/snapshot`：一次拿全大屏需要的数据，服务端缓存 15 秒（`dashboard-cache-ttl`），建议按响应里的 `refreshAfterMs`（≥30 秒）轮询，多块屏幕同时轮询也只算一次。**只含汇总，不含用户名、衣物名称或图片。**

| 区块 | 内容 |
|---|---|
| `tiles` | `usersTotal`、`usersNewToday`、`activeToday`、`active7d`、`active30d`（去重）、`onlineNow`（近 5 分钟有请求的登录用户）、`garmentsActive`、`garmentsToday`、`outfitsTotal`、`outfitsToday`、`recommendationsToday`、`adoptionRate7d`、`wearLogsToday`、`agentMessagesToday`、`imageUnitsToday`、`modelTokensToday` |
| `hourly` | 近 24 个整点（含当前小时，`hours` 为 `yyyy-MM-ddTHH:mm+08:00`）的 `garments_created`、`outfits_created`、`recommendations_shown`、`model_calls`、`active_users` |
| `daily` | 近 30 天的 `active_users`、`garments_created`、`outfits_created`、`recommendations_shown`、`model_tokens` |
| `distributions` | 衣物大类、主色 Top 10、季节、近 7 天推荐来源的展示与采用 |
| `ops` | 运维摘要：`status`、`issues` 数、排队积压、24 小时失败数 |

大屏建议布局：顶部一排 `tiles` 数字卡；中部左侧 `hourly` 折线（活跃与推荐）、右侧 `daily` 柱状；底部 `distributions` 饼图/条形图；角落放 `ops.status` 指示灯。

## 12. 数仓出口（1.1）

### 12.1 `GET /exports/daily`

按天宽表，最长 366 天；`format=csv` 返回带表头的 UTF-8 CSV（`Content-Disposition: attachment`）。列：`date, users_created, active_users, garments_created, garments_adopted, outfits_created, quick_intakes, recommendations_shown, recommendation_likes, recommendation_dislikes, wear_logs, agent_messages, image_units, model_calls, input_tokens, output_tokens, ootd_samples`。适合每天凌晨拉前一天，或首次全量拉一年。

### 12.2 `GET /exports/events`

去标识明细事件，增量拉取：

```bash
# 首次：从某个时间点开始
scripts/manage-curl.sh GET '/api/manage/v1/exports/events?since=1759248000000&limit=1000'
# 之后：带上次的 nextCursor，直到 hasMore=false；下一轮定时任务继续用最后一个 nextCursor
scripts/manage-curl.sh GET '/api/manage/v1/exports/events?cursor=MTc1OTI0ODAwMDAwMHx3ZWFyX2xvZ2dlZHw...&limit=1000'
```

```json
{
  "items": [
    { "type": "recommendation_shown", "id": "2b0c…", "atMs": 1759248001234, "userKey": "3f9a1c0e5b7d2a64",
      "attrs": { "source": "PAGE", "rank": 1, "score": 86.5, "template": "white-tee-denim-sneaker" } }
  ],
  "nextCursor": "…", "hasMore": true, "watermarkMs": 1760086395000
}
```

| type | attrs |
|---|---|
| `user_created` | `role`（`id` 也是 userKey） |
| `garment_created` | `status`、`family`（衣物大类） |
| `outfit_created` | `items`（件数） |
| `recommendation_shown` | `source`、`rank`、`score`、`template` |
| `recommendation_feedback` | `verdict`、`reasons`、`recommendationId` |
| `wear_logged` | `items` |
| `model_usage` | `feature`、`model`、`inputTokens`、`outputTokens` |
| `quick_intake` | `mode` |

- 排序键 `(atMs, type, id)`；游标不透明，同一游标重复拉取结果一致，失败重试安全。`types` 可筛选（可重复）。
- 只返回 `watermarkMs`（当前时间 −5 秒）之前的事件，避免并发写入导致漏拉。
- `userKey = HMAC-SHA256(盐, 用户名)` 前 16 位十六进制。盐用 `CLOSET_MANAGEMENT_EXPORT_SALT` 配置；不配置时由第一把管理密钥派生，**轮换管理密钥或改盐都会让 userKey 全部变化**，数仓需按时间分段。建议显式配置一个独立的盐。
- 明细不含用户名、衣物名称、图片、聊天内容、意图原文。

## 13. 模板审核（1.1）

- `GET /templates`：资源文件模板与整套穿搭样本生成的草稿（`source`：`FILE`/`SAMPLE`），含审核状态、类型、适用人群、季节、场合、有效期、槽位定义。
- `PATCH /templates/{id}`：`{"reviewStatus":"APPROVED","enabled":true,"bonus":4}`（至少一个字段，`bonus` 0~5），与鱼搭管理员审核页共用存储，立即生效；审核人记为 `manage:{keyId}:{X-Operator}`，成功与失败都写操作日志（动作 `TEMPLATE_REVIEW`）。

## 14. 1.1 配置

| 环境变量 / 配置 | 默认 | 说明 |
|---|---|---|
| `closet.management.dashboard-cache-ttl` | `15s` | 大屏快照缓存 |
| `CLOSET_MANAGEMENT_EXPORT_SALT`（`closet.management.export-salt`） | 空 | 明细事件 userKey 的盐；空则由管理密钥派生 |
| `closet.management.ops.queue-backlog-warn` | `10m` | 排队积压阈值 |
| `closet.management.ops.failed-tasks-warn` | `10` | 24 小时失败任务阈值 |
| `closet.management.ops.disk-used-warn` | `0.9` | 磁盘使用率阈值 |
| `closet.management.ops.semantic-coverage-warn` | `0.9` | 语义索引覆盖率阈值 |

全站统计按 SQL 聚合计算，适合千级用户、数十万条记录；大屏有 15 秒缓存，其余洞察有 `insights-cache-ttl`（60 秒）缓存。
