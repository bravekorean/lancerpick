package com.example.lancerpick.order.repository;

import com.example.lancerpick.order.entity.OrderClosure;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderClosureRepository extends JpaRepository<OrderClosure, Long> {
  Optional<OrderClosure> findByOrderId(long orderId);
}
