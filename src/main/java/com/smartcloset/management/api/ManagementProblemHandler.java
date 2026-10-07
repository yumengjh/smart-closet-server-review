package com.smartcloset.management.api;

import com.smartcloset.management.ConditionalOnManagementEnabled;
import com.smartcloset.management.ManagementException;
import com.smartcloset.management.ManagementProblems;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@ConditionalOnManagementEnabled
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.smartcloset.management")
public class ManagementProblemHandler {
    private static final Logger log = LoggerFactory.getLogger(ManagementProblemHandler.class);

    @ExceptionHandler(ManagementException.class)
    ResponseEntity<ProblemDetail> known(ManagementException e, HttpServletRequest request) {
        return respond(ManagementProblems.withViolations(ManagementProblems.problem(e.status(), e.code(), e.title(), e.getMessage(), requestId(request)), e.violations()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> body(MethodArgumentNotValidException e, HttpServletRequest request) {
        var violations = new ArrayList<Map<String, String>>();
        e.getBindingResult().getFieldErrors().forEach(f -> violations.add(Map.of("field", f.getField(), "message", String.valueOf(f.getDefaultMessage()))));
        return invalid(violations, request);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ProblemDetail> parameters(HandlerMethodValidationException e, HttpServletRequest request) {
        var violations = new ArrayList<Map<String, String>>();
        e.getParameterValidationResults().forEach(r -> r.getResolvableErrors().forEach(err ->
                violations.add(Map.of("field", String.valueOf(r.getMethodParameter().getParameterName()), "message", String.valueOf(err.getDefaultMessage())))));
        return invalid(violations, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ProblemDetail> constraints(ConstraintViolationException e, HttpServletRequest request) {
        var violations = new ArrayList<Map<String, String>>();
        e.getConstraintViolations().forEach(v -> {
            String path = v.getPropertyPath().toString();
            violations.add(Map.of("field", path.substring(path.lastIndexOf('.') + 1), "message", v.getMessage()));
        });
        return invalid(violations, request);
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    ResponseEntity<ProblemDetail> parameter(Exception e, HttpServletRequest request) {
        String name = e instanceof MethodArgumentTypeMismatchException m ? m.getName() : ((MissingServletRequestParameterException) e).getParameterName();
        return invalid(List.of(Map.of("field", name, "message", "参数缺失或格式不正确")), request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> unreadable(HttpMessageNotReadableException e, HttpServletRequest request) {
        return respond(ManagementProblems.problem(HttpStatus.BAD_REQUEST, "MALFORMED_JSON", "请求体无法解析",
                "请求体不是合法 JSON，或包含未知字段。", requestId(request)));
    }

    @ExceptionHandler({HttpRequestMethodNotSupportedException.class, HttpMediaTypeNotSupportedException.class})
    ResponseEntity<ProblemDetail> unsupported(Exception e, HttpServletRequest request) {
        var status = e instanceof HttpRequestMethodNotSupportedException ? HttpStatus.METHOD_NOT_ALLOWED : HttpStatus.UNSUPPORTED_MEDIA_TYPE;
        return respond(ManagementProblems.problem(status, "VALIDATION_FAILED", "请求不受支持", e.getMessage(), requestId(request)));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e, HttpServletRequest request) {
        log.error("management request failed requestId={}", requestId(request), e);
        return respond(ManagementProblems.problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务器内部错误",
                "处理请求时发生未预期错误。", requestId(request)));
    }

    private ResponseEntity<ProblemDetail> invalid(List<Map<String, String>> violations, HttpServletRequest request) {
        return respond(ManagementProblems.withViolations(ManagementProblems.problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                "请求参数不正确", "请求参数未通过校验。", requestId(request)), violations));
    }

    private static ResponseEntity<ProblemDetail> respond(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus()).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }

    static String requestId(HttpServletRequest request) {
        return (String) request.getAttribute(ManagementProblems.REQUEST_ID_ATTRIBUTE);
    }
}
