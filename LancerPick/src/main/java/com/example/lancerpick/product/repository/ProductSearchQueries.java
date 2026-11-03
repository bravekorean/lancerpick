package com.example.lancerpick.product.repository;

import com.example.lancerpick.product.dto.CategoryResponse;
import com.example.lancerpick.product.dto.ProductSummaryResponse;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!demo")
public class ProductSearchQueries {

    private static final String FROM = """
            FROM products p
            JOIN categories c ON c.id = p.category_id
            JOIN members m ON m.id = p.seller_id
            """;

    private final JdbcTemplate jdbc;

    public ProductSearchQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long count(String keyword, Long categoryId) {
        var args = new ArrayList<Object>();
        String where = where(keyword, categoryId, args);

        return jdbc.queryForObject(
                "SELECT COUNT(*) " + FROM + where,
                Long.class,
                args.toArray()
        );
    }

    public List<ProductSummaryResponse> find(
            String keyword, Long categoryId, int size, long offset) {

        var args = new ArrayList<Object>();
        String where = where(keyword, categoryId, args);
        args.add(size);
        args.add(offset);

        String sql = """
                SELECT p.id, p.title, p.price, p.duration_days,
                       c.id AS category_id, c.name AS category_name,
                       c.display_order,
                       m.id AS seller_id, m.display_name
                """ + FROM + where + """
                 ORDER BY p.created_at DESC, p.id DESC
                 LIMIT ? OFFSET ?
                """;

        return jdbc.query(sql, (rs, rowNum) ->
                new ProductSummaryResponse(
                        Long.toString(rs.getLong("id")),
                        rs.getString("title"),
                        rs.getLong("price"),
                        rs.getInt("duration_days"),
                        new CategoryResponse(
                                Long.toString(rs.getLong("category_id")),
                                rs.getString("category_name"),
                                rs.getInt("display_order")
                        ),
                        new ProductSummaryResponse.Seller(
                                Long.toString(rs.getLong("seller_id")),
                                rs.getString("display_name")
                        )
                ), args.toArray());
    }

    private static String where(
            String keyword, Long categoryId, List<Object> args) {

        var sql = new StringBuilder(
                " WHERE p.sale_status = 'ON_SALE' AND p.is_hidden = false"
        );

        if (keyword != null && !keyword.isEmpty()) {
            sql.append(
                    " AND (p.title LIKE ? ESCAPE '!'"
                            + " OR p.description LIKE ? ESCAPE '!')"
            );

            String pattern = "%" + keyword
                    .replace("!", "!!")
                    .replace("%", "!%")
                    .replace("_", "!_") + "%";

            args.add(pattern);
            args.add(pattern);
        }

        if (categoryId != null) {
            sql.append(" AND p.category_id = ?");
            args.add(categoryId);
        }

        return sql.toString();
    }
}