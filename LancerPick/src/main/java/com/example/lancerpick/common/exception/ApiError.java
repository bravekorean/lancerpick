package com.example.lancerpick.common.exception;

import java.util.List;

/** MVC와 Security가 함께 사용하는 오류 계약. 입력 원문은 포함하지 않는다. */
public record ApiError(String code, String message, List<FieldError> fieldErrors, String traceId) {
    public record FieldError(String field, String code, String message) {}
}
