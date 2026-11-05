package com.example.lancerpick.contract;

import static org.assertj.core.api.Assertions.*;

import com.example.lancerpick.common.exception.BusinessException;
import com.example.lancerpick.contract.entity.OrderContract;
import com.example.lancerpick.contract.repository.*;
import com.example.lancerpick.contract.service.OrderContractService;
import com.example.lancerpick.member.entity.Member;
import com.example.lancerpick.member.repository.MemberRepository;
import com.example.lancerpick.order.entity.*;
import com.example.lancerpick.order.repository.*;
import com.example.lancerpick.order.service.OrderPaymentService;
import com.example.lancerpick.support.IntegrationTest;
import jakarta.persistence.EntityManager;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
@Transactional(isolation = Isolation.READ_COMMITTED)
class OrderContractIntegrationTests {
  @Autowired OrderContractService service;
  @Autowired OrderContractRepository contracts;
  @Autowired ContractSignatureRepository signatures;
  @Autowired MemberRepository members;
  @Autowired OrderRepository orders;
  @Autowired OrderSnapshotRepository snapshots;
  @Autowired OrderPaymentService payments;
  @Autowired JdbcTemplate jdbc;
  @Autowired EntityManager em;
  @Autowired Clock clock;
  @Autowired PlatformTransactionManager transactionManager;

  @AfterEach
  void cleanupCommittedFixture() {
    if (TransactionSynchronizationManager.isActualTransactionActive() || orderId == 0) return;
    Long product =
        jdbc.queryForObject("SELECT product_id FROM orders WHERE id=?", Long.class, orderId);
    Long category =
        jdbc.queryForObject("SELECT category_id FROM products WHERE id=?", Long.class, product);
    jdbc.update(
        "DELETE FROM contract_signatures WHERE contract_id IN (SELECT id FROM order_contracts WHERE"
            + " order_id=?)",
        orderId);
    jdbc.update("DELETE FROM order_contracts WHERE order_id=?", orderId);
    jdbc.update("DELETE FROM order_snapshots WHERE order_id=?", orderId);
    jdbc.update("DELETE FROM orders WHERE id=?", orderId);
    jdbc.update("DELETE FROM products WHERE id=?", product);
    jdbc.update("DELETE FROM categories WHERE id=?", category);
    jdbc.update("DELETE FROM members WHERE id IN (?,?,?)", buyer, seller, other);
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void simultaneousSameDraftCreatesOneCurrentVersion() throws Exception {
    var gate = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var a =
          pool.submit(() -> {
            gate.await();
            return service.prepare(seller, orderId, 0, "same");
          });
      var b =
          pool.submit(() -> {
            gate.await();
            return service.prepare(seller, orderId, 0, "same");
          });
      gate.countDown();
      assertThat(a.get(20, TimeUnit.SECONDS).id()).isEqualTo(b.get(20, TimeUnit.SECONDS).id());
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM order_contracts WHERE order_id=?", Long.class, orderId))
        .isEqualTo(1);
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void simultaneousSignaturesAndReplayCommitExactlyTwoRows() throws Exception {
    var draft = service.prepare(seller, orderId, 0, "");
    new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> ready(draft.id()));
    String file =
        HexFormat.of()
            .formatHex(contracts.findById(Long.parseLong(draft.id())).orElseThrow().getFileHash());
    var gate = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(3)) {
      var jobs = new ArrayList<Future<?>>();
      for (long actor : new long[] {buyer, seller, buyer})
        jobs.add(
            pool.submit(() -> {
              gate.await();
              return service.sign(actor, orderId, 1, draft.contentHash(), file);
            }));
      gate.countDown();
      for (var job : jobs) job.get(20, TimeUnit.SECONDS);
    }
    assertThat(contracts.findById(Long.parseLong(draft.id())).orElseThrow().getStatus())
        .isEqualTo("SIGNED");
    assertThat(signatures.findByContractIdOrderByIdAsc(Long.parseLong(draft.id()))).hasSize(2);
  }

  long buyer, seller, other, orderId;
  LocalDateTime at;

  @BeforeEach
  void setup() {
    at =
        LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
            .truncatedTo(ChronoUnit.MICROS)
            .minusMinutes(1);
    buyer = member();
    seller = member();
    other = member();
    String name = "contract-" + UUID.randomUUID();
    jdbc.update("INSERT INTO categories(name,display_order) VALUES (?,0)", name);
    long category = jdbc.queryForObject("SELECT id FROM categories WHERE name=?", Long.class, name);
    jdbc.update(
        "INSERT INTO"
            + " products(seller_id,category_id,title,description,scope_description,preparation_description,price,duration_days,created_at,updated_at)"
            + " VALUES (?,?,'current-title','desc','scope','prep',100001,1,?,?)",
        seller,
        category,
        at,
        at);
    long product =
        jdbc.queryForObject("SELECT id FROM products WHERE seller_id=?", Long.class, seller);
    var order = Order.create(buyer, seller, product, UUID.randomUUID(), 100001L, 1, at);
    order.accept(at);
    orderId = orders.saveAndFlush(order).getId();
    snapshots.saveAndFlush(
        OrderSnapshot.create(
            orderId,
            0L,
            "frozen-title",
            "desc",
            "scope",
            "prep",
            "original-terms",
            "requirements"));
    jdbc.update("UPDATE orders SET policy_version='CONTRACT_V2' WHERE id=?", orderId);
    em.clear();
  }

  long member() {
    return members
        .saveAndFlush(Member.create(UUID.randomUUID() + "@contract.test", "fixture", "name", at))
        .getId();
  }

  void ready(String id) {
    // Renderer result fixture only; no real PDF generation is asserted here.
    var c = contracts.findById(Long.parseLong(id)).orElseThrow();
    c.startGeneration(c.getCreatedAt());
    c.completeGeneration(
        c.getGenerationKey(),
        "private/" + c.getGenerationKey() + ".pdf",
        "LOCAL",
        20,
        OrderContract.hash("pdf fixture"),
        c.getCreatedAt());
    contracts.flush();
  }

  @Test
  void frozenSnapshotAndBothSignaturesCompleteContractExactlyOnce() {
    var draft = service.prepare(seller, orderId, 0, "additional scope detail");
    assertThat(draft.canonicalContent())
        .contains("frozen-title", "50000", "50001")
        .doesNotContain("current-title");
    ready(draft.id());
    var c = contracts.findById(Long.parseLong(draft.id())).orElseThrow();
    String file = HexFormat.of().formatHex(c.getFileHash());
    var first = service.sign(buyer, orderId, 1, draft.contentHash(), file);
    assertThat(first.status()).isEqualTo("READY");
    service.sign(buyer, orderId, 1, draft.contentHash(), file);
    var second = service.sign(seller, orderId, 1, draft.contentHash(), file);
    assertThat(second.status()).isEqualTo("SIGNED");
    assertThat(second.signatures()).hasSize(2);
    assertThat(service.sign(seller, orderId, 1, draft.contentHash(), file).signatures()).hasSize(2);
    assertThatThrownBy(() -> service.prepare(seller, orderId, 1, "changed"))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  void replacementPreservesOldSignatureButRejectsItForNewVersion() {
    var first = service.prepare(seller, orderId, 0, "first");
    ready(first.id());
    String file =
        HexFormat.of()
            .formatHex(contracts.findById(Long.parseLong(first.id())).orElseThrow().getFileHash());
    service.sign(buyer, orderId, 1, first.contentHash(), file);
    var second = service.prepare(seller, orderId, 1, "second");
    assertThat(second.version()).isEqualTo(2);
    assertThat(second.signatures()).isEmpty();
    assertThat(contracts.findById(Long.parseLong(first.id())).orElseThrow().getStatus())
        .isEqualTo("SUPERSEDED");
    assertThat(signatures.findByContractIdOrderByIdAsc(Long.parseLong(first.id()))).hasSize(1);
    assertThatThrownBy(() -> service.sign(buyer, orderId, 1, first.contentHash(), file))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  void thirdPartyAndBuyerCannotPrepareDraft() {
    assertThatThrownBy(() -> service.prepare(other, orderId, 0, ""))
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.code()).isEqualTo("RESOURCE_NOT_FOUND"));
    assertThatThrownBy(() -> service.prepare(buyer, orderId, 0, ""))
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.code()).isEqualTo("FORBIDDEN"));
  }

  @Test
  void retrySameDraftDoesNotCreateVersionAndStaleChangeFails() {
    var first = service.prepare(seller, orderId, 0, "same");
    em.flush();
    em.clear();
    assertThat(service.prepare(seller, orderId, 0, "same").id()).isEqualTo(first.id());
    assertThatThrownBy(() -> service.prepare(seller, orderId, 0, "different"))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  void fileHashMismatchAndUnreadyDocumentCannotBeSigned() {
    var first = service.prepare(seller, orderId, 0, "");
    String fake = HexFormat.of().formatHex(OrderContract.hash("fake"));
    assertThatThrownBy(() -> service.sign(buyer, orderId, 1, first.contentHash(), fake))
        .isInstanceOf(BusinessException.class);
    ready(first.id());
    assertThatThrownBy(() -> service.sign(buyer, orderId, 1, first.contentHash(), fake))
        .isInstanceOf(BusinessException.class);
    assertThat(signatures.findByContractIdOrderByIdAsc(Long.parseLong(first.id()))).isEmpty();
  }

  @Test
  void legacyPaymentApiCannotChargeContractOrder() {
    assertThatThrownBy(() -> payments.pay(buyer, Long.toString(orderId), true))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.code()).isEqualTo("CONTRACT_PAYMENT_NOT_READY"));
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM payments WHERE order_id=?", Long.class, orderId))
        .isZero();
  }

  @Test
  void supersededRendererCannotPublishLateResult() {
    var first = service.prepare(seller, orderId, 0, "one");
    var old = contracts.findById(Long.parseLong(first.id())).orElseThrow();
    old.startGeneration(old.getCreatedAt());
    service.prepare(seller, orderId, 1, "two");
    assertThatThrownBy(() ->
            old.completeGeneration(
                old.getGenerationKey(),
                "private/old.pdf",
                "LOCAL",
                20,
                OrderContract.hash("x"),
                at))
        .isInstanceOf(IllegalStateException.class);
  }
}
