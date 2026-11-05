package com.example.lancerpick.order.entity;

import com.example.lancerpick.payment.service.FullFundingPolicy;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@lombok.Getter
@Entity
@Table(name = "order_closures")
public class OrderClosure {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "order_id", nullable = false, updatable = false)
  private long orderId;

  @Column(nullable = false, updatable = false, length = 40)
  private String reason;

  @Column(name = "source_timer_id", updatable = false)
  private Long sourceTimerId;

  @Column(name = "source_cancellation_request_id", updatable = false)
  private Long sourceCancellationRequestId;

  @Column(name = "inquiry_id", updatable = false)
  private Long inquiryId;

  @Column(name = "actor_type", nullable = false, updatable = false, length = 10)
  private String actorType;

  @Column(name = "actor_id", updatable = false)
  private Long actorId;

  @Column(name = "paid_amount", nullable = false, updatable = false)
  private long paidAmount;

  @Column(name = "buyer_refund_amount", nullable = false, updatable = false)
  private long buyerRefundAmount;

  @Column(name = "seller_gross_amount", nullable = false, updatable = false)
  private long sellerGrossAmount;

  @Column(name = "fee_amount", nullable = false, updatable = false)
  private long feeAmount;

  @Column(name = "seller_net_amount", nullable = false, updatable = false)
  private long sellerNetAmount;

  @JdbcTypeCode(SqlTypes.LOCAL_DATE_TIME)
  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  protected OrderClosure() {}

  public static OrderClosure completed(long order, long buyer, long amount, LocalDateTime at) {
    return create(order, buyer, null, FullFundingPolicy.completed(amount), at);
  }

  public static OrderClosure refunded(
      long order, long seller, long request, long amount, LocalDateTime at) {
    return create(order, seller, request, FullFundingPolicy.cancelled(amount), at);
  }

  private static OrderClosure create(
      long order, long actor, Long request, FullFundingPolicy.Distribution d, LocalDateTime at) {
    if (order < 1 || actor < 1 || at == null || (request != null && request < 1))
      throw new IllegalArgumentException("Invalid order closure");
    var c = new OrderClosure();
    c.orderId = order;
    c.reason = request == null ? "COMPLETED" : "MUTUAL_CANCELLATION";
    c.actorType = "MEMBER";
    c.actorId = actor;
    c.sourceCancellationRequestId = request;
    c.paidAmount = d.fundedAmount();
    c.buyerRefundAmount = d.buyerRefund();
    c.feeAmount = d.fee();
    c.sellerNetAmount = d.sellerPayout();
    c.sellerGrossAmount = d.fee() + d.sellerPayout();
    c.createdAt = at;
    return c;
  }
}
