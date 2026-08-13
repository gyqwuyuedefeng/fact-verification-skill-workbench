package com.hsmap.factverification.shared;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 把业务错误和请求合同错误统一转换为脱敏 problem JSON。 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /** 业务异常保留稳定 code，用户描述已在 ServiceException 创建时脱敏。 */
    @ExceptionHandler(ServiceException.class)
    public ResponseEntity<ApiError> handleService(ServiceException exception, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, exception.getCode(), exception.getMessage(), request);
    }

    /** 缺少 OpenAPI 必填请求头属于 400 合同错误。 */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiError> handleMissingHeader(
            MissingRequestHeaderException exception, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "REQUEST_HEADER_REQUIRED", "缺少必填请求头", request);
    }

    private static ResponseEntity<ApiError> response(
            HttpStatus status, String code, String message, HttpServletRequest request) {
        String requestId = request.getHeader("Idempotency-Key");
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(new ApiError(code, ErrorSanitizer.sanitize(message), requestId, Map.of()));
    }
}
