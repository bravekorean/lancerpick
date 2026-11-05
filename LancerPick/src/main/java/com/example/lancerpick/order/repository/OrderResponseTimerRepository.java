package com.example.lancerpick.order.repository;

import com.example.lancerpick.order.entity.OrderResponseTimer;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderResponseTimerRepository extends JpaRepository<OrderResponseTimer, Long> {
  boolean existsByOrderIdAndStatus(long orderId, String status);
}
