package com.smartcloset.image;

import org.springframework.http.HttpStatus;

public class ProcessingException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public ProcessingException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
    public HttpStatus status() { return status; }
    public String code() { return code; }
    public static ProcessingException invalid(String message) {
        return new ProcessingException(HttpStatus.BAD_REQUEST, "INVALID_IMAGE", message);
    }
}
