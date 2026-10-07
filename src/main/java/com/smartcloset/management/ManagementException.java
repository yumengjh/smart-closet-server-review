package com.smartcloset.management;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** A failure that maps to one stable Problem Details code of the management contract. */
public class ManagementException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final String title;
    private final List<Map<String, String>> violations;

    public ManagementException(HttpStatus status, String code, String title, String detail) {
        this(status, code, title, detail, List.of());
    }
    public ManagementException(HttpStatus status, String code, String title, String detail, List<Map<String, String>> violations) {
        super(detail);
        this.status = status; this.code = code; this.title = title; this.violations = List.copyOf(violations);
    }
    public List<Map<String, String>> violations() { return violations; }
    public HttpStatus status() { return status; }
    public String code() { return code; }
    public String title() { return title; }

    public static ManagementException userNotFound(String username) {
        return new ManagementException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "用户不存在", "用户 " + username + " 不存在。");
    }
    public static ManagementException invalid(String detail) {
        return new ManagementException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "请求参数不正确", detail);
    }
    public static ManagementException invalid(List<Map<String, String>> violations) {
        return new ManagementException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "请求参数不正确", "请求参数未通过校验。", violations);
    }
    public static ManagementException malformed() {
        return new ManagementException(HttpStatus.BAD_REQUEST, "MALFORMED_JSON", "请求体无法解析", "请求体不是合法 JSON，或包含未知字段。");
    }
}
