package com.smartcloset.image;

import org.springframework.http.HttpStatus;

public class ProcessingException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private long retryAfterMillis;
    public long retryAfterMillis(){return retryAfterMillis;}
    public ProcessingException retryAfter(String value){try{retryAfterMillis=Math.max(1000,Long.parseLong(value)*1000);}catch(Exception e){try{retryAfterMillis=Math.max(1000,java.time.ZonedDateTime.parse(value,java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()-System.currentTimeMillis());}catch(Exception ignored){retryAfterMillis=30000;}}return this;}

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
