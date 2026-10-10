package com.smartcloset.management;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("closet.management")
public record ManagementProperties(boolean enabled, Auth auth, Integer maxBodyBytes, Duration insightsCacheTtl, Duration dashboardCacheTtl, String exportSalt, Ops ops) {
    /** Thresholds that turn /ops/status from UP into DEGRADED (design 管理接口二期 §4.1). */
    public record Ops(Duration queueBacklogWarn, Integer failedTasksWarn, Double diskUsedWarn, Double semanticCoverageWarn) {
        public Ops {
            queueBacklogWarn = queueBacklogWarn == null ? Duration.ofMinutes(10) : queueBacklogWarn;
            failedTasksWarn = failedTasksWarn == null ? 10 : failedTasksWarn;
            diskUsedWarn = diskUsedWarn == null ? 0.9 : diskUsedWarn;
            semanticCoverageWarn = semanticCoverageWarn == null ? 0.9 : semanticCoverageWarn;
        }
    }
    public record Key(String id, String secret) {}
    public record Auth(String mode, Duration clockSkew, Duration nonceTtl, List<Key> keys) {}

    public ManagementProperties {
        if (auth == null) auth = new Auth(null, null, null, null);
        auth = new Auth(auth.mode() == null ? "hmac" : auth.mode(),
                auth.clockSkew() == null ? Duration.ofSeconds(300) : auth.clockSkew(),
                auth.nonceTtl() == null ? Duration.ofSeconds(600) : auth.nonceTtl(),
                auth.keys() == null ? List.of() : auth.keys().stream()
                        .filter(k -> k != null && k.secret() != null && !k.secret().isBlank()).toList());
        maxBodyBytes = maxBodyBytes == null ? 65536 : maxBodyBytes;
        insightsCacheTtl = insightsCacheTtl == null ? Duration.ofSeconds(60) : insightsCacheTtl;
        dashboardCacheTtl = dashboardCacheTtl == null ? Duration.ofSeconds(15) : dashboardCacheTtl;
        exportSalt = exportSalt == null ? "" : exportSalt;
        if (ops == null) ops = new Ops(null, null, null, null);
        if (enabled) validate(auth, maxBodyBytes);
    }

    private static void validate(Auth auth, int maxBodyBytes) {
        if ("jwt".equals(auth.mode())) throw new IllegalStateException("closet.management.auth.mode=jwt 尚未实现，请使用 hmac。");
        if (!"hmac".equals(auth.mode())) throw new IllegalStateException("closet.management.auth.mode 只能是 hmac。");
        if (auth.keys().isEmpty())
            throw new IllegalStateException("已开启管理接口（CLOSET_MANAGEMENT_ENABLED=true），但没有配置密钥 CLOSET_MANAGEMENT_SECRET。");
        var ids = new HashSet<String>();
        for (var key : auth.keys()) {
            if (key.id() == null || !key.id().matches("[A-Za-z0-9_.-]{1,64}"))
                throw new IllegalStateException("管理接口密钥 ID 需为 1 到 64 位字母、数字、点、下划线或短横线。");
            if (!ids.add(key.id())) throw new IllegalStateException("管理接口密钥 ID 重复：" + key.id());
            if (key.secret().getBytes(StandardCharsets.UTF_8).length < 32)
                throw new IllegalStateException("管理接口密钥 " + key.id() + " 长度不足 32 字节。");
        }
        if (auth.clockSkew().isNegative() || auth.clockSkew().isZero()) throw new IllegalStateException("clock-skew 必须大于 0。");
        if (auth.nonceTtl().compareTo(auth.clockSkew().multipliedBy(2)) < 0)
            throw new IllegalStateException("nonce-ttl 必须不小于 clock-skew 的两倍。");
        if (maxBodyBytes < 1024 || maxBodyBytes > 1_048_576) throw new IllegalStateException("max-body-bytes 需在 1024 到 1048576 之间。");
    }

    public String secret(String keyId) {
        return auth.keys().stream().filter(k -> k.id().equals(keyId)).map(Key::secret).findFirst().orElse(null);
    }
}
