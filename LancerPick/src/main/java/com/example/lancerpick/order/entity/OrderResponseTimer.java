package com.example.lancerpick.order.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Read-only guard until timer lifecycle integration; never writes a partial timer row. */
@Entity
@org.hibernate.annotations.Immutable
@Table(name = "order_response_timers")
public class OrderResponseTimer {
  @Id private Long id;

  @Column(name = "order_id")
  private long orderId;

  @Column(length = 16)
  private String status;

  @JdbcTypeCode(SqlTypes.LOCAL_DATE_TIME)
  @Column(name = "expires_at")
  private LocalDateTime expiresAt;

  protected OrderResponseTimer() {}
}
