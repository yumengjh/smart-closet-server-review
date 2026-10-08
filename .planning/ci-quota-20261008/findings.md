# 发现

- 失败运行 37704247839，对应 7c35a9d；Java 测试 247 项中 2 失败，7 跳过，后续构建和发布均跳过。
- BatchServiceTest 用数据库 CURRENT_DATE 插入已用额度；生产代码按 Asia/Shanghai 日期读取。运行发生在 2026-10-07 23:49 UTC，此时业务日期已为 2026-10-08。
- 两个失败分别表现为总额度 21 而非 20，以及没有进入 WAITING_QUOTA，与测试把额度插入前一天一致。
- QuotaPolicy.today() 已提供业务日期入口，应让测试使用此入口，而非修改生产额度规则或强制 runner 时区。
- 干净 HEAD 快照以 Pacific/Honolulu JVM 时区运行原 BatchServiceTest：10 项中同样 2 失败（20/21 与 WAITING_QUOTA），0 错误，确认根因。
