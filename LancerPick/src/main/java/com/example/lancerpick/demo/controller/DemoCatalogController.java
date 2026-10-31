package com.example.lancerpick.demo.controller;

import com.example.lancerpick.common.web.PageResponse;
import com.example.lancerpick.demo.dto.DemoCatalogResponse.Category;
import com.example.lancerpick.demo.dto.DemoCatalogResponse.Product;
import com.example.lancerpick.demo.service.DemoCatalogService;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 팀원이 따라갈 읽기 예제: Controller → Service → DTO. 운영 상품 API와 경로를 분리한다. */
@RestController
@Profile("(default | demo) & !prod")
@RequestMapping("/api/demo")
public class DemoCatalogController {
    private final DemoCatalogService catalog;
    public DemoCatalogController(DemoCatalogService catalog) { this.catalog = catalog; }

    @GetMapping("/categories")
    public List<Category> categories() { return catalog.categories(); }

    @GetMapping("/products")
    public PageResponse<Product> products(@RequestParam(defaultValue = "") String keyword,
            @RequestParam(required = false) String categoryId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return catalog.products(keyword, categoryId, page, size);
    }
}
