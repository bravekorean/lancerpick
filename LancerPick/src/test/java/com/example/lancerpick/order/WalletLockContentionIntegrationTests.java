package com.example.lancerpick.order;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.lancerpick.common.policy.TradePolicyVersion;
import com.example.lancerpick.common.security.AuthenticatedMember;
import com.example.lancerpick.contract.service.ContractDocumentService;
import com.example.lancerpick.contract.service.OrderContractService;
import com.example.lancerpick.member.entity.Member;
import com.example.lancerpick.member.repository.MemberRepository;
import com.example.lancerpick.order.entity.Order;
import com.example.lancerpick.order.entity.OrderSnapshot;
import com.example.lancerpick.order.entity.OrderTradeTerms;
import com.example.lancerpick.order.repository.OrderRepository;
import com.example.lancerpick.order.repository.OrderSnapshotRepository;
import com.example.lancerpick.order.repository.OrderTradeTermsRepository;
import com.example.lancerpick.order.service.OrderPaymentService;
import com.example.lancerpick.support.IntegrationTest;
import com.example.lancerpick.support.V3FundsInvariant;
import com.example.lancerpick.wallet.entity.Wallet;
import com.example.lancerpick.wallet.entity.WalletEntry;
import com.example.lancerpick.wallet.repository.WalletEntryRepository;
import com.example.lancerpick.wallet.repository.WalletRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 지갑 행 잠금을 다른 트랜잭션이 오래 쥐고 있을 때의 결제 동작.
 *
 * <p>MySQL 기본 잠금 대기 한도는 50초라 테스트 커넥션만 2초로 줄인다(운영 설정은 바꾸지 않는다). 대기 한도 안에서 풀리면 결제가 이어서 성공하고,
 * 한도를 넘으면 결제 전체가 롤백되며, 같은 요청을 다시 보내면 한 번만 차감되는지, 다른 지갑의 결제는 막히지 않는지 확인한다.
 */
@IntegrationTest
@AutoConfigureMockMvc
class WalletLockContentionIntegrationTests {
  static final long AMOUNT = 10_001;
  static final long INITIAL = 1_000_000;
  static final int LOCK_WAIT_SECONDS = 2;
  static final Path directory = temp();

  static Path temp() {
    try {
      return Files.createTempDirectory("lancerpick-wallet-lock-");
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry p) {
    p.add("contracts.storage-root", directory::toString);
    p.add(
        "spring.datasource.hikari.connection-init-sql",
        () -> "SET SESSION innodb_lock_wait_timeout=" + LOCK_WAIT_SECONDS);
  }

  @Autowired MemberRepository members;
  @Autowired WalletRepository wallets;
  @Autowired WalletEntryRepository entries;
  @Autowired OrderRepository orders;
  @Autowired OrderSnapshotRepository snapshots;
  @Autowired OrderTradeTermsRepository terms;
  @Autowired OrderContractService contracts;
  @Autowired ContractDocumentService documents;
  @Autowired OrderPaymentService payments;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager manager;
  @Autowired MockMvc mvc;

  long buyer, otherBuyer, seller, product, category;
  final List<Long> ids = new ArrayList<>();
  final java.util.Map<Long, Long> outboxBeforePayment = new java.util.HashMap<>();
  final ExecutorService pool = Executors.newCachedThreadPool();

  record Principal(Long memberId) implements AuthenticatedMember {}

  @BeforeEach
  void fixture() {
    new TransactionTemplate(manager)
        .executeWithoutResult(ignored -> {
          var at = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1).truncatedTo(ChronoUnit.MICROS);
          buyer = member(at);
          otherBuyer = member(at);
          seller = member(at);
          String name = "wallet-lock-" + UUID.randomUUID();
          jdbc.update("INSERT INTO categories(name,display_order) VALUES (?,0)", name);
          category = jdbc.queryForObject("SELECT id FROM categories WHERE name=?", Long.class, name);
          jdbc.update(
              "INSERT INTO"
                  + " products(seller_id,category_id,title,description,scope_description,preparation_description,price,duration_days,created_at,updated_at)"
                  + " VALUES (?,?,'상품','설명','범위','준비',?,1,?,?)",
              seller,
              category,
              AMOUNT,
              at,
              at);
          product = jdbc.queryForObject("SELECT id FROM products WHERE seller_id=?", Long.class, seller);
        });
  }

  long member(LocalDateTime at) {
    long m =
        members
            .saveAndFlush(Member.create(UUID.randomUUID() + "@wallet-lock.test", "fixture", "표시 이름", at))
            .getId();
    wallets.saveAndFlush(Wallet.withInitialGrant(m, at));
    entries.saveAndFlush(WalletEntry.initialGrant(m, at));
    return m;
  }

  /** 서명까지 끝나 결제만 남은 CONTRACT_V3 주문. */
  long signedOrder(long orderBuyer) {
    long result =
        new TransactionTemplate(manager)
            .execute(ignored -> {
              var at = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
              var o =
                  Order.create(
                      orderBuyer, seller, product, UUID.randomUUID(), AMOUNT, 1, at,
                      TradePolicyVersion.CONTRACT_V3);
              o.accept(at);
              orders.saveAndFlush(o);
              ids.add(o.getId());
              terms.saveAndFlush(OrderTradeTerms.fullFunding(o.getId()));
              snapshots.saveAndFlush(
                  OrderSnapshot.create(o.getId(), 0L, "상품", "설명", "범위", "준비", "전액 보관", "요구사항"));
              return o.getId();
            });
    var draft = contracts.prepare(seller, result, 0, "");
    var ready = documents.generate(seller, result, draft.version());
    contracts.sign(orderBuyer, result, ready.version(), ready.contentHash(), ready.fileHash());
    contracts.sign(seller, result, ready.version(), ready.contentHash(), ready.fileHash());
    outboxBeforePayment.put(result, outboxEvents(result));
    return result;
  }

  /** 다른 트랜잭션이 지갑 행을 FOR UPDATE로 잡고 release가 열릴 때까지 커밋하지 않는다. */
  final class WalletHolder implements AutoCloseable {
    final CountDownLatch locked = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    final Future<?> done;

    WalletHolder(long member) throws InterruptedException {
      done =
          pool.submit(() ->
              new TransactionTemplate(manager)
                  .executeWithoutResult(ignored -> {
                    jdbc.queryForObject(
                        "SELECT balance FROM wallets WHERE member_id=? FOR UPDATE", Long.class, member);
                    locked.countDown();
                    try {
                      release.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                    }
                  }));
      assertThat(locked.await(10, TimeUnit.SECONDS)).as("지갑 잠금 획득").isTrue();
    }

    void releaseAfter(long millis) {
      pool.submit(() -> {
        Thread.sleep(millis);
        release.countDown();
        return null;
      });
    }

    @Override
    public void close() throws Exception {
      release.countDown();
      done.get(30, TimeUnit.SECONDS);
    }
  }

  @AfterEach
  void cleanup() throws Exception {
    pool.shutdownNow();
    var violations = V3FundsInvariant.violations(jdbc, ids);
    for (long order : ids) {
      var keys =
          jdbc.queryForList(
              "SELECT file_storage_key FROM order_contracts WHERE order_id=? AND file_storage_key IS NOT NULL",
              String.class,
              order);
      jdbc.update("DELETE FROM notifications WHERE order_id=?", order);
      jdbc.update("DELETE FROM outbox_events WHERE aggregate_type='ORDER' AND aggregate_id=?", order);
      for (String table : List.of("order_histories", "order_closures", "order_funds", "settlements"))
        jdbc.update("DELETE FROM " + table + " WHERE order_id=?", order);
      jdbc.update(
          "DELETE FROM wallet_entries WHERE payment_id IN (SELECT id FROM payments WHERE order_id=?)",
          order);
      for (String table :
          List.of("payments", "cancellation_requests", "order_response_timers", "revision_requests", "deliveries"))
        jdbc.update("DELETE FROM " + table + " WHERE order_id=?", order);
      jdbc.update(
          "DELETE FROM contract_signatures WHERE contract_id IN (SELECT id FROM order_contracts WHERE order_id=?)",
          order);
      for (String table : List.of("order_contracts", "order_trade_terms", "order_snapshots"))
        jdbc.update("DELETE FROM " + table + " WHERE order_id=?", order);
      jdbc.update("DELETE FROM orders WHERE id=?", order);
      for (String key : keys) Files.deleteIfExists(directory.resolve(key));
    }
    jdbc.update("DELETE FROM products WHERE id=?", product);
    jdbc.update("DELETE FROM categories WHERE id=?", category);
    jdbc.update("DELETE FROM wallet_entries WHERE member_id IN (?,?,?)", buyer, otherBuyer, seller);
    jdbc.update("DELETE FROM wallets WHERE member_id IN (?,?,?)", buyer, otherBuyer, seller);
    jdbc.update("DELETE FROM members WHERE id IN (?,?,?)", buyer, otherBuyer, seller);
    assertThat(violations).as("V3 금액 불변식 위반").isEmpty();
  }

  @AfterAll
  static void removeDirectory() throws Exception {
    try (var files = Files.list(directory)) {
      for (var p : files.toList()) Files.delete(p);
    }
    Files.delete(directory);
  }

  long balance(long m) {
    return jdbc.queryForObject("SELECT balance FROM wallets WHERE member_id=?", Long.class, m);
  }

  long count(String table, long order) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE order_id=?", Long.class, order);
  }

  long paymentEntries(long m) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM wallet_entries WHERE member_id=? AND type='PAYMENT'", Long.class, m);
  }

  long outboxEvents(long order) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM outbox_events WHERE aggregate_type='ORDER' AND aggregate_id=?",
        Long.class,
        order);
  }

  String orderStatus(long order) {
    return jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, order);
  }

  void assertUnpaid(long order, long member) {
    assertThat(balance(member)).isEqualTo(INITIAL);
    assertThat(count("payments", order)).isZero();
    assertThat(count("order_funds", order)).isZero();
    assertThat(paymentEntries(member)).isZero();
    assertThat(outboxEvents(order)).as("결제 이벤트 없음").isEqualTo(outboxBeforePayment.get(order));
    assertThat(orderStatus(order)).isEqualTo("AWAITING_PAYMENT");
  }

  void assertPaidOnce(long order, long member) {
    assertThat(balance(member)).isEqualTo(INITIAL - AMOUNT);
    assertThat(count("payments", order)).isEqualTo(1);
    assertThat(count("order_funds", order)).isEqualTo(1);
    assertThat(paymentEntries(member)).isEqualTo(1);
    assertThat(orderStatus(order)).isEqualTo("IN_PROGRESS");
  }

  UsernamePasswordAuthenticationToken auth(long id) {
    return UsernamePasswordAuthenticationToken.authenticated(
        new Principal(id), null, AuthorityUtils.createAuthorityList("ROLE_USER"));
  }

  @Test
  void paymentWaitsForWalletLockReleasedWithinLimitAndThenPaysOnce() throws Exception {
    long order = signedOrder(buyer);
    long started;
    long elapsedMillis;
    try (var holder = new WalletHolder(buyer)) {
      holder.releaseAfter(1_000);
      started = System.nanoTime();
      payments.pay(buyer, Long.toString(order), true);
      elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }
    // 잠금이 풀릴 때까지 기다렸다가 같은 트랜잭션에서 차감·보관·원장을 끝낸다.
    assertThat(elapsedMillis).isGreaterThanOrEqualTo(900).isLessThan(LOCK_WAIT_SECONDS * 1_000L);
    assertPaidOnce(order, buyer);
  }

  @Test
  void walletLockTimeoutRollsBackWholePaymentAndRetryPaysOnce() throws Exception {
    long order = signedOrder(buyer);
    try (var holder = new WalletHolder(buyer)) {
      long started = System.nanoTime();
      assertThatThrownBy(() -> payments.pay(buyer, Long.toString(order), true))
          .isInstanceOf(PessimisticLockingFailureException.class);
      long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
      assertThat(elapsedMillis).isGreaterThanOrEqualTo(LOCK_WAIT_SECONDS * 1_000L - 200);
      // 주문 잠금 뒤 지갑 잠금에서 실패했으므로 주문 상태·결제·보관금·원장·아웃박스 모두 남지 않는다.
      assertUnpaid(order, buyer);
    }
    // 잠금이 풀린 뒤 같은 요청을 다시 보내면 정상 결제되고, 두 번째 재전송은 기존 결제를 돌려준다.
    payments.pay(buyer, Long.toString(order), true);
    payments.pay(buyer, Long.toString(order), true);
    assertPaidOnce(order, buyer);
  }

  @Test
  void walletLockTimeoutOverHttpIsRetryableConflictAndRetrySucceeds() throws Exception {
    long order = signedOrder(buyer);
    try (var holder = new WalletHolder(buyer)) {
      // 잠금 대기 초과는 전체 롤백된 충돌이므로 5xx(결과 불명)가 아니라 재시도 가능한 409로 응답한다.
      mvc.perform(pay(order))
          .andExpect(status().isConflict())
          .andExpect(header().string("Retry-After", "1"))
          .andExpect(jsonPath("$.code").value("RESOURCE_BUSY"));
      assertUnpaid(order, buyer);
    }
    mvc.perform(pay(order))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
    assertPaidOnce(order, buyer);
  }

  org.springframework.test.web.servlet.RequestBuilder pay(long order) {
    return post("/api/orders/" + order + "/payment")
        .with(authentication(auth(buyer)))
        .with(csrf())
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"agreedToTerms\":true}");
  }

  @Test
  void lockedWalletDoesNotDelayPaymentFromIndependentWallet() throws Exception {
    long blocked = signedOrder(buyer);
    long independent = signedOrder(otherBuyer);
    try (var holder = new WalletHolder(buyer)) {
      Future<Throwable> waiting =
          pool.submit(() -> {
            try {
              payments.pay(buyer, Long.toString(blocked), true);
              return null;
            } catch (Throwable e) {
              return e;
            }
          });
      long started = System.nanoTime();
      payments.pay(otherBuyer, Long.toString(independent), true);
      long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
      // 다른 구매자의 지갑은 대기 없이 결제된다(판매자 지갑은 결제 단계에서 잠그지 않는다).
      assertThat(elapsedMillis).isLessThan(1_000);
      assertPaidOnce(independent, otherBuyer);
      assertThat(waiting.isDone()).as("잠긴 지갑의 결제는 아직 대기 중").isFalse();
      holder.release.countDown();
      assertThat(waiting.get(10, TimeUnit.SECONDS)).isNull();
    }
    assertPaidOnce(blocked, buyer);
    assertThat(balance(seller)).isEqualTo(INITIAL);
  }
}
