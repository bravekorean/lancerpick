package com.example.lancerpick.order.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 타이머 기능 연결 전 활성 타이머 존재 여부만 조회한다. 부분 데이터로 생성·변경하지 않는다. */
@Entity
@Immutable
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
