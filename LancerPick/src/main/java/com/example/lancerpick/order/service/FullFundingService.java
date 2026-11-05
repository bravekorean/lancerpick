package com.example.lancerpick.order.service;

import com.example.lancerpick.common.exception.BusinessException;
import com.example.lancerpick.common.policy.TradePolicyVersion;
import com.example.lancerpick.contract.repository.*;
import com.example.lancerpick.notification.service.OrderEventPublisher;
import com.example.lancerpick.order.dto.OrderDetailResponse;
import com.example.lancerpick.order.dto.OrderFundsResponse;
import com.example.lancerpick.order.entity.*;
import com.example.lancerpick.order.entity.Order;
import com.example.lancerpick.order.repository.*;
import com.example.lancerpick.payment.entity.*;
import com.example.lancerpick.payment.repository.*;
import com.example.lancerpick.payment.service.FullFundingPolicy;
import com.example.lancerpick.settlement.entity.Settlement;
import com.example.lancerpick.settlement.repository.SettlementRepository;
import com.example.lancerpick.wallet.entity.*;
import com.example.lancerpick.wallet.repository.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Member share -> parent order write -> one affected wallet write. No external IO. */
@Service
@Profile("!demo")
@lombok.RequiredArgsConstructor
@Transactional(isolation = Isolation.READ_COMMITTED)
public class FullFundingService {
  private final OrderRepository orders;
  private final OrderTradeTermsRepository terms;
  private final OrderContractRepository contracts;
  private final ContractSignatureRepository signatures;
  private final PaymentRepository payments;
  private final OrderFundsRepository funds;
  private final WalletRepository wallets;
  private final WalletEntryRepository entries;
  private final SettlementRepository settlements;
  private final OrderClosureRepository closures;
  private final CancellationRequestRepository cancellations;
  private final DeliveryRepository deliveries;
  private final OrderResponseTimerRepository timers;
  private final OrderHistoryRepository histories;
  private final OrderEventPublisher events;
  private final OrderDetailQueries details;
  private final Clock clock;

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public OrderFundsResponse summary(long actor, long id) {
    var o =
        orders
            .findById(id)
            .filter(order ->
                (order.getBuyerId() == actor || order.getSellerId() == actor)
                    && TradePolicyVersion.CONTRACT_V3.name().equals(order.getPolicyVersion()))
            .orElseThrow(() ->
                new BusinessException(
                    HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "주문을 찾을 수 없습니다."));
    if (payments.findByOrderId(id).isEmpty())
      return new OrderFundsResponse("NOT_PAID", o.getPriceAmount(), 0, 0, null, null, null, null);
    var p = payment(o);
    var f = holding(o, p);
    var c = closures.findByOrderId(id).orElse(null);
    if (("HELD".equals(f.getStatus())) != (c == null)) throw integrity();
    return new OrderFundsResponse(
        f.getStatus(),
        f.getAmount(),
        f.getRemainingAmount(),
        p.getRefundedAmount(),
        c == null ? null : c.getFeeAmount(),
        c == null ? null : c.getSellerNetAmount(),
        p.getPaidAt().atOffset(ZoneOffset.UTC),
        f.getResolvedAt() == null ? null : f.getResolvedAt().atOffset(ZoneOffset.UTC));
  }

  public OrderDetailResponse pay(long actor, long id) {
    var o = lock(actor, id);
    role(o.getBuyerId() == actor);
    if (payments.findByOrderId(id).isPresent()) {
      var p = payment(o);
      var f = holding(o, p);
      debit(o, p);
      if ("REFUNDED".equals(p.status()))
        throw conflict("PAYMENT_ALREADY_REFUNDED", "이미 반환된 결제입니다.");
      if (!Set.of(
              OrderStatus.IN_PROGRESS,
              OrderStatus.REVISION_IN_PROGRESS,
              OrderStatus.AWAITING_CONFIRMATION,
              OrderStatus.COMPLETED)
          .contains(o.getStatus())) throw integrity();
      if (o.getStatus() == OrderStatus.COMPLETED) verifyCompletion(o, p, f);
      else if (!"HELD".equals(f.getStatus())
          || closures.findByOrderId(id).isPresent()
          || settlements.findByOrderId(id).isPresent()) throw integrity();
      return response(o, actor);
    }
    if (o.getStatus() != OrderStatus.AWAITING_PAYMENT
        || o.getPaidAt() != null
        || funds.existsById(id)
        || closures.findByOrderId(id).isPresent()
        || settlements.findByOrderId(id).isPresent()) throw state();
    requireSigned(o);
    noPending(o);
    noTimer(o);
    var wallet = wallets.findByMemberIdForUpdate(actor).orElseThrow(FullFundingService::integrity);
    var at = now();
    o.pay(at);
    wallet.debit(o.getPriceAmount(), at);
    var p = payments.saveAndFlush(Payment.fullFunding(id, o.getPriceAmount(), at));
    funds.saveAndFlush(OrderFunds.hold(id, p.id(), p.amount(), at));
    entries.saveAndFlush(WalletEntry.payment(actor, p.id(), p.amount(), wallet.getBalance(), at));
    events.publish(o, histories.saveAndFlush(OrderHistory.paid(id, actor, at)));
    return response(o, actor);
  }

  public OrderDetailResponse confirm(long actor, long id) {
    var o = lock(actor, id);
    role(o.getBuyerId() == actor);
    var p = payment(o);
    var f = holding(o, p);
    debit(o, p);
    if (o.getStatus() == OrderStatus.COMPLETED) {
      verifyCompletion(o, p, f);
      return response(o, actor);
    }
    if (o.getStatus() != OrderStatus.AWAITING_CONFIRMATION
        || !"PAID".equals(p.status())
        || !"HELD".equals(f.getStatus())) throw state();
    noPending(o);
    noTimer(o);
    if (!deliveries.existsByOrderIdAndSequenceNo(id, 1)
        || (deliveries.revisions(id) > 0 && !deliveries.existsByOrderIdAndSequenceNo(id, 2)))
      throw integrity();
    if (closures.findByOrderId(id).isPresent() || settlements.findByOrderId(id).isPresent())
      throw integrity();
    var d = FullFundingPolicy.completed(p.amount());
    var wallet =
        wallets.findByMemberIdForUpdate(o.getSellerId()).orElseThrow(FullFundingService::integrity);
    var at = now();
    wallet.creditSettlement(d.sellerPayout(), at);
    entries.saveAndFlush(
        WalletEntry.sellerPayout(
            o.getSellerId(), p.id(), d.sellerPayout(), wallet.getBalance(), at));
    settlements.saveAndFlush(Settlement.create(id, p.amount(), 1000, at));
    closures.saveAndFlush(OrderClosure.completed(id, actor, p.amount(), at));
    f.release(at);
    o.confirm(at);
    orders.flush();
    funds.flush();
    events.publish(o, histories.saveAndFlush(OrderHistory.completed(id, actor, at)));
    return response(o, actor);
  }

  public OrderDetailResponse refund(long actor, long id, String reason) {
    var o = lock(actor, id);
    role(o.getSellerId() == actor);
    var p = payment(o);
    var f = holding(o, p);
    debit(o, p);
    if (o.getStatus() == OrderStatus.CANCELLED) {
      var c = closures.findByOrderId(id).orElseThrow(FullFundingService::integrity);
      if (!"REFUNDED".equals(p.status())
          || !"REFUNDED".equals(f.getStatus())
          || !"MUTUAL_CANCELLATION".equals(c.getReason())
          || c.getBuyerRefundAmount() != p.amount()
          || settlements.findByOrderId(id).isPresent()) throw integrity();
      credit(o.getBuyerId(), p, WalletEntryType.REFUND, p.amount(), "REFUND:");
      return response(o, actor);
    }
    if (!Set.of(
                OrderStatus.IN_PROGRESS,
                OrderStatus.AWAITING_CONFIRMATION,
                OrderStatus.REVISION_IN_PROGRESS)
            .contains(o.getStatus())
        || !"PAID".equals(p.status())
        || !"HELD".equals(f.getStatus())) throw state();
    var pending = cancellations.findByOrderIdAndStatus(id, "PENDING");
    if (pending.size() != 1)
      throw conflict("CANCELLATION_REQUEST_REQUIRED", "구매자의 취소 요청을 승인해 주세요.");
    var request = pending.getFirst();
    if (request.getRequestedBy() != o.getBuyerId()) throw integrity();
    noTimer(o);
    if (closures.findByOrderId(id).isPresent() || settlements.findByOrderId(id).isPresent())
      throw integrity();
    var wallet =
        wallets.findByMemberIdForUpdate(o.getBuyerId()).orElseThrow(FullFundingService::integrity);
    var at = now();
    wallet.refund(p.amount(), at);
    p.refund(at);
    f.refund(at);
    request.approve(actor, null, at);
    entries.saveAndFlush(
        WalletEntry.refund(o.getBuyerId(), p.id(), p.amount(), wallet.getBalance(), at));
    closures.saveAndFlush(OrderClosure.refunded(id, actor, request.getId(), p.amount(), at));
    var from = o.getStatus();
    o.cancel(at);
    orders.flush();
    payments.flush();
    funds.flush();
    cancellations.flush();
    events.publish(
        o,
        histories.saveAndFlush(
            OrderHistory.cancellationRequestEvent(
                id, actor, OrderHistoryAction.CANCELLATION_APPROVED, from, at, null)));
    events.publish(o, histories.saveAndFlush(OrderHistory.cancelled(id, actor, from, at, reason)));
    return response(o, actor);
  }

  private Order lock(long actor, long id) {
    if (orders.lockDecisionActor(actor).isEmpty())
      throw new BusinessException(
          HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "로그인이 필요합니다.");
    var o =
        orders
            .findForDecision(id, actor)
            .orElseThrow(() ->
                new BusinessException(
                    HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "주문을 찾을 수 없습니다."));
    if (!TradePolicyVersion.CONTRACT_V3.name().equals(o.getPolicyVersion()))
      throw conflict("ORDER_POLICY_NOT_READY", "전액 보관 정책 주문이 아닙니다.");
    FullFundingPolicy.requireAmount(o.getPriceAmount());
    return o;
  }

  private void requireSigned(Order o) {
    var t = terms.findById(o.getId()).orElseThrow(FullFundingService::integrity);
    if (!"FULL_HOLD".equals(t.getFundingMode())) throw integrity();
    var c =
        contracts
            .findByOrderIdAndActiveSlot(o.getId(), 1)
            .orElseThrow(() -> conflict("CONTRACT_NOT_SIGNED", "계약 서명이 필요합니다."));
    var signed = signatures.findByContractIdOrderByIdAsc(c.getId());
    if (!"SIGNED".equals(c.getStatus()) || signed.size() != 2)
      throw conflict("CONTRACT_NOT_SIGNED", "양측 계약 서명이 필요합니다.");
    for (var s : signed)
      if ((s.getMemberId() == o.getBuyerId() && !"BUYER".equals(s.getPartyRole()))
          || (s.getMemberId() == o.getSellerId() && !"SELLER".equals(s.getPartyRole()))
          || !java.security.MessageDigest.isEqual(s.getSignedContentHash(), c.getContentHash())
          || !java.security.MessageDigest.isEqual(s.getSignedFileHash(), c.getFileHash())
          || !(s.getMemberId() == o.getBuyerId() || s.getMemberId() == o.getSellerId()))
        throw integrity();
  }

  private Payment payment(Order o) {
    var p = payments.findByOrderId(o.getId()).orElseThrow(FullFundingService::integrity);
    if (!TradePolicyVersion.CONTRACT_V3.name().equals(p.getOrderPolicyVersion())
        || !"FULL".equals(p.getInstallmentType())
        || p.amount() != o.getPriceAmount()
        || p.rate() != 1000
        || o.getPaidAt() == null
        || !o.getPaidAt().equals(p.getPaidAt())) throw integrity();
    return p;
  }

  private OrderFunds holding(Order o, Payment p) {
    var f = funds.findById(o.getId()).orElseThrow(FullFundingService::integrity);
    if (!f.getHeldAt().equals(p.getPaidAt())
        || f.getPaymentId() != p.id()
        || f.getAmount() != p.amount()
        || ("HELD".equals(f.getStatus())
            ? f.getRemainingAmount() != p.amount()
            : f.getRemainingAmount() != 0)) throw integrity();
    return f;
  }

  private void debit(Order o, Payment p) {
    credit(o.getBuyerId(), p, WalletEntryType.PAYMENT, -p.amount(), "PAYMENT:");
  }

  private void credit(long member, Payment p, WalletEntryType type, long amount, String prefix) {
    var e =
        entries.findByEventKeyForUpdate(prefix + p.id()).orElseThrow(FullFundingService::integrity);
    if (e.getMemberId() != member
        || e.getPaymentId() == null
        || e.getPaymentId() != p.id()
        || e.getType() != type
        || e.getAmountDelta() != amount) throw integrity();
  }

  private void verifyCompletion(Order o, Payment p, OrderFunds f) {
    var c = closures.findByOrderId(o.getId()).orElseThrow(FullFundingService::integrity);
    var s = settlements.findByOrderId(o.getId()).orElseThrow(FullFundingService::integrity);
    var d = FullFundingPolicy.completed(p.amount());
    if (!"PAID".equals(p.status())
        || !"RELEASED".equals(f.getStatus())
        || !"COMPLETED".equals(c.getReason())
        || !"MEMBER".equals(c.getActorType())
        || !Objects.equals(c.getActorId(), o.getBuyerId())
        || !"NORMAL".equals(s.getSettlementType())
        || c.getFeeAmount() != d.fee()
        || c.getSellerNetAmount() != d.sellerPayout()
        || c.getBuyerRefundAmount() != 0
        || !Arrays.equals(s.amounts(), new long[] {p.amount(), 1000, d.fee(), d.sellerPayout()}))
      throw integrity();
    credit(o.getSellerId(), p, WalletEntryType.SELLER_PAYOUT, d.sellerPayout(), "SELLER_PAYOUT:");
  }

  private void noPending(Order o) {
    if (!cancellations.findByOrderIdAndStatus(o.getId(), "PENDING").isEmpty())
      throw conflict("CANCELLATION_PENDING", "대기 중인 취소 요청을 먼저 처리해 주세요.");
  }

  private void noTimer(Order o) {
    if (timers.existsByOrderIdAndStatus(o.getId(), "ACTIVE"))
      throw conflict("TIMER_INTEGRATION_NOT_READY", "활성 검수 타이머 처리가 연결되기 전에는 금액 처리를 진행할 수 없습니다.");
  }

  private LocalDateTime now() {
    return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
  }

  private OrderDetailResponse response(Order o, long actor) {
    var d = details.forParticipant(o.getId(), actor);
    return d.withAllowedActions(
        OrderAllowedActions.actions(
            d.policyVersion(),
            d.status(),
            o.getBuyerId() == actor,
            d.revisionRequest(),
            d.cancellationRequests(),
            d.review()));
  }

  private static void role(boolean allowed) {
    if (!allowed) throw new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN", "처리 권한이 없습니다.");
  }

  private static BusinessException conflict(String code, String text) {
    return new BusinessException(HttpStatus.CONFLICT, code, text);
  }

  private static BusinessException state() {
    return conflict("ORDER_STATE_CONFLICT", "현재 주문 상태에서 처리할 수 없습니다.");
  }

  private static BusinessException integrity() {
    return new BusinessException(
        HttpStatus.INTERNAL_SERVER_ERROR, "DATA_INTEGRITY_ERROR", "거래 금액 기록을 확인해야 합니다.");
  }
}
