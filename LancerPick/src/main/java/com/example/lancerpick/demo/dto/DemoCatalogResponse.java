package com.example.lancerpick.demo.dto;

/** API 명세의 ProductSummary와 같은 형태. 예시 데이터 전용이며 업무 엔티티가 아니다. */
public final class DemoCatalogResponse {
    private DemoCatalogResponse() {}
    public record Category(String id, String name, int displayOrder) {}
    public record Seller(String id, String displayName) {}
    public record Product(String id, String title, long priceAmount, int durationDays,
            Category category, Seller seller) {}
}
