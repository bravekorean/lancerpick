package com.example.lancerpick.product;

import com.example.lancerpick.product.dto.CategoryResponse;
import com.example.lancerpick.product.repository.CategoryRepository;
import com.example.lancerpick.product.service.CategoryService;
import com.example.lancerpick.support.IntegrationTest;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** TEST_DB_* 전용 MySQL에서 실제 매핑·조회·정렬·공개 HTTP 연결을 검사한다. */
@IntegrationTest
@AutoConfigureMockMvc
@Transactional
class CategoryIntegrationTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired CategoryRepository repository;
    @Autowired CategoryService service;
    @Autowired MockMvc mvc;

    @Test
    void readsPersistedCategoriesOrderedByDisplayOrderThenIdWithoutChangingRows() throws Exception {
        String prefix = "cat-" + UUID.randomUUID().toString().substring(0, 8);
        long later = insert(prefix + "-later", 21);
        long first = insert(prefix + "-first 😀", 20);
        long second = insert(prefix + "-second", 20);
        var ids = List.of(later, first, second);

        assertThat(repository.findAllByOrderByDisplayOrderAscIdAsc().stream()
                .filter(category -> ids.contains(category.getId())).map(category -> category.getId()))
                .containsExactly(first, second, later);
        var expected = List.of(
                new CategoryResponse(Long.toString(first), prefix + "-first 😀", 20),
                new CategoryResponse(Long.toString(second), prefix + "-second", 20),
                new CategoryResponse(Long.toString(later), prefix + "-later", 21));
        assertThat(service.getCategories().stream()
                .filter(category -> ids.contains(Long.parseLong(category.id()))).toList())
                .containsExactlyElementsOf(expected);

        mvc.perform(get("/api/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + first + "')].name")
                        .value(hasItem(prefix + "-first 😀")));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM categories WHERE name LIKE ?",
                Long.class, prefix + "%")).isEqualTo(3);
    }

    private long insert(String name, int order) {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(
                    "INSERT INTO categories(name, display_order) VALUES (?, ?)", Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, name);
            statement.setInt(2, order);
            return statement;
        }, key);
        return key.getKey().longValue();
    }
}
