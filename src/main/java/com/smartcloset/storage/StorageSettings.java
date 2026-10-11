package com.smartcloset.storage;

import java.net.URI;
import java.time.Duration;
import java.util.function.Function;

/**
 * closet.storage.* (env CLOSET_STORAGE_*). The command-line migration reads the same names from the environment,
 * so the server and the migration can never disagree about where objects go.
 */
public record StorageSettings(String type, URI endpoint, String region, String bucket, String accessKey, String secretKey,
                              String prefix, boolean pathStyle, String serve, Duration urlWindow) {
    public static final Duration PRIVATE_WINDOW = Duration.ofMinutes(5);

    public boolean s3() { return "s3".equals(type); }
    public boolean redirect() { return !"proxy".equals(serve); }

    /** Reads keys like "type", "s3.endpoint" through the given lookup; blanks fall back to the defaults. */
    public static StorageSettings of(Function<String, String> get) {
        String type = value(get, "type", "local").toLowerCase();
        if (!type.equals("local") && !type.equals("s3")) throw new IllegalArgumentException("CLOSET_STORAGE_TYPE 只能是 local 或 s3，当前是：" + type);
        String serve = value(get, "s3.serve", "redirect").toLowerCase();
        if (!serve.equals("redirect") && !serve.equals("proxy")) throw new IllegalArgumentException("CLOSET_STORAGE_S3_SERVE 只能是 redirect 或 proxy");
        String prefix = value(get, "s3.prefix", "").replaceAll("^/+", "");
        if (!prefix.isEmpty() && !prefix.endsWith("/")) prefix += "/";
        Duration window = duration(value(get, "s3.url-ttl", "1h"));
        if (window.compareTo(Duration.ofMinutes(1)) < 0 || window.compareTo(Duration.ofDays(3)) > 0)
            throw new IllegalArgumentException("CLOSET_STORAGE_S3_URL_TTL 需在 1 分钟到 3 天之间");
        var settings = new StorageSettings(type, endpoint(value(get, "s3.endpoint", "")), value(get, "s3.region", ""),
                value(get, "s3.bucket", ""), value(get, "s3.access-key", ""), value(get, "s3.secret-key", ""), prefix,
                Boolean.parseBoolean(value(get, "s3.path-style", "false")), serve, window);
        if (settings.s3()) settings.requireS3();
        return settings;
    }

    /** Spring property lookup: closet.storage.{key}. */
    public static StorageSettings of(org.springframework.core.env.Environment env) { return of(k -> env.getProperty("closet.storage." + k)); }
    /** Environment-variable lookup: CLOSET_STORAGE_{KEY}. */
    public static StorageSettings fromEnv(java.util.Map<String, String> env) {
        return of(k -> env.get("CLOSET_STORAGE_" + k.toUpperCase().replace('.', '_').replace('-', '_')));
    }

    public void requireS3() {
        var missing = new java.util.ArrayList<String>();
        if (endpoint == null) missing.add("CLOSET_STORAGE_S3_ENDPOINT");
        if (region.isBlank()) missing.add("CLOSET_STORAGE_S3_REGION");
        if (bucket.isBlank()) missing.add("CLOSET_STORAGE_S3_BUCKET");
        if (accessKey.isBlank()) missing.add("CLOSET_STORAGE_S3_ACCESS_KEY");
        if (secretKey.isBlank()) missing.add("CLOSET_STORAGE_S3_SECRET_KEY");
        if (!missing.isEmpty()) throw new IllegalArgumentException("使用存储桶需要配置：" + String.join("、", missing));
        if (!bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")) throw new IllegalArgumentException("存储桶名称无效：" + bucket);
    }

    @Override public String toString() {
        // Never print the secret.
        return "StorageSettings[type=" + type + ", endpoint=" + endpoint + ", region=" + region + ", bucket=" + bucket
                + ", prefix=" + prefix + ", pathStyle=" + pathStyle + ", serve=" + serve + ", urlWindow=" + urlWindow + "]";
    }

    private static String value(Function<String, String> get, String key, String fallback) {
        String v = get.apply(key);
        return v == null || v.isBlank() ? fallback : v.trim();
    }
    private static URI endpoint(String raw) {
        if (raw.isBlank()) return null;
        var uri = URI.create(raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw);
        if (!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme()) || uri.getHost() == null || (uri.getPath() != null && !uri.getPath().isEmpty()))
            throw new IllegalArgumentException("CLOSET_STORAGE_S3_ENDPOINT 需形如 https://cos.ap-guangzhou.myqcloud.com（不带桶名和路径）");
        return uri;
    }
    static Duration duration(String raw) {
        var m = java.util.regex.Pattern.compile("(\\d+)\\s*(s|m|h|d)").matcher(raw.trim().toLowerCase());
        if (!m.matches()) return Duration.parse(raw);
        long n = Long.parseLong(m.group(1));
        return switch (m.group(2)) { case "s" -> Duration.ofSeconds(n); case "m" -> Duration.ofMinutes(n); case "h" -> Duration.ofHours(n); default -> Duration.ofDays(n); };
    }
}
