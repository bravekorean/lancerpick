package com.example.lancerpick.payment.repository;

import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/** 주문 잠금 아래에서만 쓰는 원결제 저장소. 기존 V1 스키마를 사용한다. */
@Repository
@Profile("!demo")
public class PaymentRepository {
    private final JdbcTemplate jdbc;
    public PaymentRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record Payment(long id, long amount, String status) {}
    public Optional<Payment> findByOrderId(long orderId) {
        return jdbc.query("SELECT id,amount,status FROM payments WHERE order_id=?",
                (rs, row) -> new Payment(rs.getLong("id"), rs.getLong("amount"), rs.getString("status")), orderId).stream().findFirst();
    }
    public long insert(long orderId, long amount, LocalDateTime at) {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO payments(order_id,amount,fee_rate_bps,status,paid_at) VALUES (?,?,1000,'PAID',?)", Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, orderId);
            statement.setLong(2, amount);
            statement.setObject(3, at);
            return statement;
        }, key);
        return key.getKey().longValue();
    }
    public int markRefunded(long paymentId, LocalDateTime at) {
        return jdbc.update("UPDATE payments SET status='REFUNDED',refunded_at=? WHERE id=? AND status='PAID'", at, paymentId);
    }
}
