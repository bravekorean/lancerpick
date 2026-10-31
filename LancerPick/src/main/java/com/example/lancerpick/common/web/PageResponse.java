package com.example.lancerpick.common.web;

import java.util.List;

/** 성공 응답을 별도 envelope로 감싸지 않고 목록 계약 자체를 반환한다. */
public record PageResponse<T>(List<T> items, int page, int size, long totalElements, int totalPages) {
    public PageResponse {
        items = List.copyOf(items);
    }
}
