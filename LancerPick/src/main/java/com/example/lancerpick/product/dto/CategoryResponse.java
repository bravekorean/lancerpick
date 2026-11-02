package com.example.lancerpick.product.dto;

import com.example.lancerpick.product.entity.Category;

public record CategoryResponse(
        String id,
        String name,
        int displayOrder
) {

    public static CategoryResponse from(Category category) {
        return new CategoryResponse(
                category.getId().toString(),
                category.getName(),
                category.getDisplayOrder()
        );
    }
}