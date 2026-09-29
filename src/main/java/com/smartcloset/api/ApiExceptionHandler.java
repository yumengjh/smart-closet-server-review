package com.smartcloset.api;

import com.smartcloset.image.ProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.multipart.MultipartException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    public record ErrorBody(String code, String message) {}

    @ExceptionHandler(ProcessingException.class)
    public ResponseEntity<ErrorBody> processing(ProcessingException e) {
        var response = ResponseEntity.status(e.status());
        if (e.status() == HttpStatus.TOO_MANY_REQUESTS) response.header("Retry-After", "3");
        return response.body(new ErrorBody(e.code(), e.getMessage()));
    }
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorBody> tooLarge(Exception e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(new ErrorBody("IMAGE_TOO_LARGE", "图片不能超过 15 MB，请先缩小图片。"));
    }
    @ExceptionHandler({MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class,
            MethodArgumentNotValidException.class, ServletRequestBindingException.class, MultipartException.class})
    public ResponseEntity<ErrorBody> invalid(Exception e) {
        return ResponseEntity.badRequest().body(new ErrorBody("INVALID_REQUEST", "请检查请求格式、UUID、图片参数与工作流步骤；multipart 的 workflow 部分须使用 application/json。"));
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorBody> unexpected(Exception e) {
        if (e instanceof ErrorResponse webError) {
            return ResponseEntity.status(webError.getStatusCode())
                    .body(new ErrorBody("HTTP_ERROR", "请求路径、方法或内容类型不受支持。"));
        }
        log.error("Image request failed", e);
        return ResponseEntity.internalServerError().body(new ErrorBody("PROCESSING_ERROR", "图片处理失败，请检查服务日志。"));
    }
}
