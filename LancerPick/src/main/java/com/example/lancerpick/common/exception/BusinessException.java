package com.example.lancerpick.common.exception;

import org.springframework.http.HttpStatus;

/** 기능 Service에서 throw하고 공통 Advice에서 HTTP 응답으로 변환한다. */
public class BusinessException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public BusinessException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
}
