package com.example.lancerpick.product.service;

import com.example.lancerpick.product.dto.CategoryResponse;
import com.example.lancerpick.product.repository.CategoryRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Profile("!demo")
public class CategoryService {

    private final CategoryRepository categoryRepository;

    public CategoryService(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    @Transactional(readOnly = true)
    public List<CategoryResponse> getCategories() {
        return categoryRepository
                .findAllByOrderByDisplayOrderAscIdAsc()
                .stream()
                .map(CategoryResponse::from)
                .toList();
    }
}