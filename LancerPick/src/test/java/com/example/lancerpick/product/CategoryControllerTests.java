package com.example.lancerpick.product;

import com.example.lancerpick.common.exception.ApiExceptionHandler;
import com.example.lancerpick.common.security.SecurityConfig;
import com.example.lancerpick.common.security.SecurityErrorWriter;
import com.example.lancerpick.common.web.TraceIdFilter;
import com.example.lancerpick.product.controller.CategoryController;
import com.example.lancerpick.product.dto.CategoryResponse;
import com.example.lancerpick.product.service.CategoryService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 공개 HTTP 계약 검사. 실제 조회·정렬은 MySQL 통합 검사에서 확인한다. */
@WebMvcTest(controllers = CategoryController.class,
        properties = "spring.config.location=optional:classpath:/default-catalog-test.properties")
@ActiveProfiles("test")
@Import({SecurityConfig.class, SecurityErrorWriter.class, TraceIdFilter.class, ApiExceptionHandler.class})
class CategoryControllerTests {
    @Autowired MockMvc mvc;
    @MockitoBean CategoryService categories;

    @Test
    void anonymousCanReadCategoriesWithStringIdsAndOnlyContractFields() throws Exception {
        when(categories.getCategories()).thenReturn(List.of(
                new CategoryResponse("9007199254740993", "분류 😀", 2)));
        mvc.perform(get("/api/categories"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        [{"id":"9007199254740993","name":"분류 😀","displayOrder":2}]
                        """, org.springframework.test.json.JsonCompareMode.STRICT))
                .andExpect(jsonPath("$[0].id").isString())
                .andExpect(header().exists("X-Trace-Id"));
    }

    @Test
    void noCategoriesReturnsAnEmptyArray() throws Exception {
        when(categories.getCategories()).thenReturn(List.of());
        mvc.perform(get("/api/categories"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
    }

    @Test
    void databaseFailureIsAnErrorRatherThanAnEmptySuccess() throws Exception {
        when(categories.getCategories()).thenThrow(new IllegalStateException("internal-db-detail"));
        mvc.perform(get("/api/categories"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("internal-db-detail"))));
    }
}
