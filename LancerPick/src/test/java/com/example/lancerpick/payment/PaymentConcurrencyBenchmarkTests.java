package com.example.lancerpick.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

import com.example.lancerpick.common.exception.BusinessException;
import com.example.lancerpick.common.policy.TradePolicyVersion;
import com.example.lancerpick.contract.service.ContractDocumentService;
import com.example.lancerpick.contract.service.OrderContractService;
import com.example.lancerpick.member.entity.Member;
import com.example.lancerpick.member.repository.MemberRepository;
import com.example.lancerpick.order.entity.Order;
import com.example.lancerpick.order.entity.OrderSnapshot;
import com.example.lancerpick.order.entity.OrderTradeTerms;
import com.example.lancerpick.order.repository.OrderDetailQueries;
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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Opt-in real-MySQL service benchmark. No HTTP/auth/network-provider timing is claimed. */
@IntegrationTest
@Import(PaymentConcurrencyBenchmarkTests.JdbcMetricsConfiguration.class)
@EnabledIfEnvironmentVariable(named = "PAYMENT_BENCHMARK", matches = "true")
class PaymentConcurrencyBenchmarkTests {
  static final Path DOCUMENTS = temp();
  static final ThreadLocal<Metrics> CURRENT = new ThreadLocal<>();
  static final long GRANT = Wallet.INITIAL_GRANT_AMOUNT;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("contracts.storage-root", DOCUMENTS::toString);
    // This fixed pool is identical in before/after runs and larger than the default worker count.
    registry.add("spring.datasource.hikari.maximum-pool-size", () -> 12);
    registry.add("spring.datasource.hikari.minimum-idle", () -> 12);
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
  @MockitoSpyBean OrderDetailQueries details;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager manager;

  enum Scenario { DUPLICATE_ORDER, SHARED_WALLET, INDEPENDENT_WALLETS, INSUFFICIENT_BALANCE }
  record Request(long buyer, long order) {}
  record Outcome(long nanos, boolean success, String error, Metrics metrics) {}
  static final class Metrics {
    long statements, lockStatements, sqlNanos, lockSqlNanos;
    long completionNanos, walletAcquiredAt, walletHoldNanos, detailNanos, detailStatements;
  }

  @Test
  void measureCommittedPaymentsAndVerifyMoney() throws Exception {
    doAnswer(call -> {
      var metrics = CURRENT.get();
      if (metrics == null) return call.callRealMethod();
      long started = System.nanoTime();
      long statements = metrics.statements;
      try {
        return call.callRealMethod();
      } finally {
        metrics.detailNanos += System.nanoTime() - started;
        metrics.detailStatements += metrics.statements - statements;
      }
    }).when(details).forParticipant(anyLong(), anyLong());
    int requests = setting("PAYMENT_BENCHMARK_REQUESTS", 24, 2, 64);
    int repeats = setting("PAYMENT_BENCHMARK_REPEATS", 3, 1, 10);
    boolean sharedWalletOnly = "true".equals(System.getenv("PAYMENT_BENCHMARK_SHARED_WALLET_ONLY"));
    var scenarios = sharedWalletOnly ? List.of(Scenario.SHARED_WALLET) : List.of(Scenario.values());
    var workerCounts = sharedWalletOnly ? List.of(8) : List.of(1, 4, 8);
    String label = System.getenv().getOrDefault("PAYMENT_BENCHMARK_LABEL", "current");
    if (!label.matches("[a-zA-Z0-9_-]+")) throw new IllegalArgumentException("Invalid report label");
    Path report = Path.of("build/reports/payment-benchmark", label + ".csv");
    Files.createDirectories(report.getParent());
    List<String> csv = new ArrayList<>();
    csv.add("scenario,workers,repeat,requests,successful_requests,committed_payments,expected_rejections,unexpected_errors,elapsed_ms,requests_per_sec,payments_per_sec,p50_ms,p95_ms,p99_ms,sql_per_request,locking_sql_per_request,sql_ms_per_request,locking_sql_ms_per_request,completion_ms_per_request,wallet_hold_ms_per_request,detail_ms_per_request,detail_sql_per_request");
    try {
      // Untimed setup and separate warmup; never reuse paid orders for fresh-payment measurements.
      run(Scenario.INDEPENDENT_WALLETS, 4, 8, 0);
      for (Scenario scenario : scenarios) {
        for (int workers : workerCounts) {
          for (int repeat = 1; repeat <= repeats; repeat++) {
            String row = run(scenario, workers, requests, repeat);
            csv.add(row);
            Files.write(report, csv);
            System.out.println("PAYMENT_BENCHMARK " + row);
          }
        }
      }
    } finally {
      Files.write(report, csv);
    }
  }

  private String run(Scenario scenario, int workers, int count, int repeat) throws Exception {
    Fixture fixture = new Fixture();
    try {
      List<Request> work = fixture.prepare(scenario, count);
      List<Outcome> outcomes = new ArrayList<>();
      long elapsed;
      try (var executor = Executors.newFixedThreadPool(workers)) {
        var ready = new CountDownLatch(Math.min(workers, count));
        var start = new CountDownLatch(1);
        var futures = work.stream().map(request -> executor.submit(() -> {
          ready.countDown();
          if (!start.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("Start timeout");
          Metrics metrics = new Metrics();
          CURRENT.set(metrics);
          long began = System.nanoTime();
          try {
            payments.pay(request.buyer(), Long.toString(request.order()), true);
            return new Outcome(System.nanoTime() - began, true, null, metrics);
          } catch (BusinessException error) {
            return new Outcome(System.nanoTime() - began, false, error.code(), metrics);
          } catch (RuntimeException error) {
            return new Outcome(System.nanoTime() - began, false, error.getClass().getSimpleName(), metrics);
          } finally {
            CURRENT.remove();
          }
        })).toList();
        assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
        long began = System.nanoTime();
        start.countDown();
        for (var future : futures) outcomes.add(future.get(120, TimeUnit.SECONDS));
        elapsed = System.nanoTime() - began;
      }
      long successes = outcomes.stream().filter(Outcome::success).count();
      long rejected = outcomes.stream().filter(o -> "INSUFFICIENT_BALANCE".equals(o.error())).count();
      var unexpected = outcomes.stream().filter(o -> !o.success()
          && !(scenario == Scenario.INSUFFICIENT_BALANCE && "INSUFFICIENT_BALANCE".equals(o.error()))).toList();
      assertThat(unexpected).as("unexpected payment failures").isEmpty();
      long expectedPayments = scenario == Scenario.DUPLICATE_ORDER || scenario == Scenario.INSUFFICIENT_BALANCE ? 1 : count;
      assertThat(successes).isEqualTo(scenario == Scenario.INSUFFICIENT_BALANCE ? 1 : count);
      assertThat(rejected).isEqualTo(scenario == Scenario.INSUFFICIENT_BALANCE ? count - 1 : 0);
      fixture.verify(expectedPayments);
      long[] latencies = outcomes.stream().mapToLong(Outcome::nanos).sorted().toArray();
      long sql = outcomes.stream().mapToLong(o -> o.metrics().statements).sum();
      long locks = outcomes.stream().mapToLong(o -> o.metrics().lockStatements).sum();
      long sqlNanos = outcomes.stream().mapToLong(o -> o.metrics().sqlNanos).sum();
      long lockNanos = outcomes.stream().mapToLong(o -> o.metrics().lockSqlNanos).sum();
      long completionNanos = outcomes.stream().mapToLong(o -> o.metrics().completionNanos).sum();
      long walletHoldNanos = outcomes.stream().mapToLong(o -> o.metrics().walletHoldNanos).sum();
      long detailNanos = outcomes.stream().mapToLong(o -> o.metrics().detailNanos).sum();
      long detailStatements = outcomes.stream().mapToLong(o -> o.metrics().detailStatements).sum();
      return String.format(Locale.ROOT,
          "%s,%d,%d,%d,%d,%d,%d,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f",
          scenario, workers, repeat, count, successes, expectedPayments, rejected, unexpected.size(),
          elapsed / 1e6, count * 1e9 / elapsed, expectedPayments * 1e9 / elapsed,
          percentile(latencies, .5), percentile(latencies, .95), percentile(latencies, .99),
          (double) sql / count, (double) locks / count, sqlNanos / 1e6 / count, lockNanos / 1e6 / count,
          completionNanos / 1e6 / count, walletHoldNanos / 1e6 / count,
          detailNanos / 1e6 / count, (double) detailStatements / count);
    } finally {
      fixture.cleanup();
    }
  }

  private class Fixture {
    final List<Long> memberIds = new ArrayList<>();
    final List<Long> orderIds = new ArrayList<>();
    long seller, product, category, amount;

    List<Request> prepare(Scenario scenario, int count) {
      amount = scenario == Scenario.INSUFFICIENT_BALANCE ? 600_001 : 10_001;
      new TransactionTemplate(manager).executeWithoutResult(ignored -> {
        seller = member();
        String name = "payment-bench-" + UUID.randomUUID();
        jdbc.update("INSERT INTO categories(name,display_order) VALUES (?,0)", name);
        category = jdbc.queryForObject("SELECT id FROM categories WHERE name=?", Long.class, name);
        jdbc.update("INSERT INTO products(seller_id,category_id,title,description,scope_description,preparation_description,price,duration_days,created_at,updated_at) VALUES (?,?,'bench','description','scope','preparation',?,1,?,?)",
            seller, category, amount, at(), at());
        product = jdbc.queryForObject("SELECT id FROM products WHERE seller_id=?", Long.class, seller);
      });
      long sharedBuyer = new TransactionTemplate(manager).execute(ignored -> member());
      List<Request> result = new ArrayList<>();
      for (int i = 0; i < count; i++) {
        long buyer = scenario == Scenario.INDEPENDENT_WALLETS
            ? new TransactionTemplate(manager).execute(ignored -> member()) : sharedBuyer;
        if (scenario == Scenario.DUPLICATE_ORDER && i > 0) {
          result.add(result.getFirst());
          continue;
        }
        long order = new TransactionTemplate(manager).execute(ignored -> {
          Order value = Order.create(buyer, seller, product, UUID.randomUUID(), amount, 1, at(), TradePolicyVersion.CONTRACT_V3);
          value.accept(at());
          orders.saveAndFlush(value);
          orderIds.add(value.getId());
          terms.saveAndFlush(OrderTradeTerms.fullFunding(value.getId()));
          snapshots.saveAndFlush(OrderSnapshot.create(value.getId(), 0L, "bench", "description", "scope", "preparation", "full hold", "requirements"));
          return value.getId();
        });
        var draft = contracts.prepare(seller, order, 0, "");
        var pdf = documents.generate(seller, order, draft.version());
        contracts.sign(buyer, order, pdf.version(), pdf.contentHash(), pdf.fileHash());
        contracts.sign(seller, order, pdf.version(), pdf.contentHash(), pdf.fileHash());
        result.add(new Request(buyer, order));
      }
      return result;
    }

    long member() {
      long id = members.saveAndFlush(Member.create(UUID.randomUUID() + "@payment-bench.test", "fixture", "bench", at())).getId();
      memberIds.add(id);
      wallets.saveAndFlush(Wallet.withInitialGrant(id, at()));
      entries.saveAndFlush(WalletEntry.initialGrant(id, at()));
      return id;
    }

    void verify(long expectedPayments) {
      assertThat(V3FundsInvariant.violations(jdbc, orderIds)).isEmpty();
      long paid = 0;
      for (long order : orderIds) {
        long payments = jdbc.queryForObject("SELECT COUNT(*) FROM payments WHERE order_id=?", Long.class, order);
        assertThat(payments).isBetween(0L, 1L);
        paid += payments;
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_funds WHERE order_id=?", Long.class, order)).isEqualTo(payments);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wallet_entries e JOIN payments p ON p.id=e.payment_id WHERE p.order_id=? AND e.type='PAYMENT'", Long.class, order)).isEqualTo(payments);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_histories WHERE order_id=? AND action='PAID'", Long.class, order)).isEqualTo(payments);
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, order))
            .isEqualTo(payments == 1 ? "IN_PROGRESS" : "AWAITING_PAYMENT");
      }
      assertThat(paid).isEqualTo(expectedPayments);
      for (long member : memberIds) {
        long delta = jdbc.queryForObject("SELECT COALESCE(SUM(amount_delta),0) FROM wallet_entries WHERE member_id=? AND type='PAYMENT'", Long.class, member);
        long balance = jdbc.queryForObject("SELECT balance FROM wallets WHERE member_id=?", Long.class, member);
        assertThat(balance).isEqualTo(GRANT + delta).isNotNegative();
        if (member == seller) assertThat(balance).isEqualTo(GRANT);
        assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM payments WHERE order_id IN (SELECT id FROM orders WHERE buyer_id=?)", Long.class, member)).isEqualTo(-delta);
      }
    }

    void cleanup() throws Exception {
      for (long order : orderIds) {
        var files = jdbc.queryForList("SELECT file_storage_key FROM order_contracts WHERE order_id=? AND file_storage_key IS NOT NULL", String.class, order);
        jdbc.update("DELETE FROM notifications WHERE order_id=?", order);
        jdbc.update("DELETE FROM outbox_events WHERE aggregate_type='ORDER' AND aggregate_id=?", order);
        jdbc.update("DELETE FROM wallet_entries WHERE payment_id IN (SELECT id FROM payments WHERE order_id=?)", order);
        for (String table : List.of("order_histories", "order_funds", "payments", "contract_signatures")) {
          if (table.equals("contract_signatures")) jdbc.update("DELETE FROM contract_signatures WHERE contract_id IN (SELECT id FROM order_contracts WHERE order_id=?)", order);
          else jdbc.update("DELETE FROM " + table + " WHERE order_id=?", order);
        }
        for (String table : List.of("order_contracts", "order_trade_terms", "order_snapshots", "orders"))
          jdbc.update("DELETE FROM " + table + " WHERE " + (table.equals("orders") ? "id" : "order_id") + "=?", order);
        for (String file : files) {
          Path target = DOCUMENTS.resolve(file).normalize();
          if (!target.startsWith(DOCUMENTS)) throw new IllegalStateException("Invalid fixture file");
          Files.deleteIfExists(target);
        }
      }
      if (product != 0) jdbc.update("DELETE FROM products WHERE id=?", product);
      if (category != 0) jdbc.update("DELETE FROM categories WHERE id=?", category);
      for (long member : memberIds) {
        jdbc.update("DELETE FROM wallet_entries WHERE member_id=?", member);
        jdbc.update("DELETE FROM wallets WHERE member_id=?", member);
        jdbc.update("DELETE FROM members WHERE id=?", member);
      }
    }
  }

  static LocalDateTime at() { return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MICROS); }
  static double percentile(long[] sorted, double p) { return sorted[(int) Math.ceil(sorted.length * p) - 1] / 1e6; }
  static int setting(String name, int fallback, int min, int max) {
    int value = Integer.parseInt(System.getenv().getOrDefault(name, Integer.toString(fallback)));
    if (value < min || value > max) throw new IllegalArgumentException(name + " outside supported range");
    return value;
  }
  static Path temp() {
    try { return Files.createTempDirectory("lancerpick-payment-bench-").toAbsolutePath().normalize(); }
    catch (Exception error) { throw new IllegalStateException(error); }
  }

  @AfterAll
  static void removeFixtureDirectory() throws Exception {
    Files.deleteIfExists(DOCUMENTS);
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class JdbcMetricsConfiguration {
    @Bean static BeanPostProcessor measuredDataSource() {
      return new BeanPostProcessor() {
        @Override public Object postProcessAfterInitialization(Object bean, String name) {
          if (!(bean instanceof DataSource)) return bean;
          return Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
            Object value = invoke(bean, method, args);
            if (!(value instanceof Connection connection) || !method.getName().equals("getConnection")) return value;
            return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (cp, cm, ca) -> {
              Metrics current = CURRENT.get();
              if (current != null && (cm.getName().equals("commit") || cm.getName().equals("rollback"))) {
                long began = System.nanoTime();
                try { return invoke(connection, cm, ca); }
                finally {
                  long ended = System.nanoTime();
                  current.completionNanos += ended - began;
                  if (current.walletAcquiredAt != 0) {
                    current.walletHoldNanos += ended - current.walletAcquiredAt;
                    current.walletAcquiredAt = 0;
                  }
                }
              }
              Object prepared = invoke(connection, cm, ca);
              if (!(prepared instanceof PreparedStatement statement) || ca == null || !(ca[0] instanceof String sql)) return prepared;
              String normalized = sql.toLowerCase(Locale.ROOT);
              boolean locking = normalized.contains("for update") || normalized.contains("for share") || normalized.contains("lock in share mode");
              return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class}, (sp, sm, sa) -> {
                Metrics metrics = CURRENT.get();
                if (metrics == null || !sm.getName().startsWith("execute")) return invoke(statement, sm, sa);
                metrics.statements++;
                if (locking) metrics.lockStatements++;
                long began = System.nanoTime();
                try {
                  Object result = invoke(statement, sm, sa);
                  if (locking && normalized.contains("wallets")) metrics.walletAcquiredAt = System.nanoTime();
                  return result;
                }
                finally {
                  long nanos = System.nanoTime() - began;
                  metrics.sqlNanos += nanos;
                  if (locking) metrics.lockSqlNanos += nanos;
                }
              });
            });
          });
        }
      };
    }
    static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
      try { return method.invoke(target, args); }
      catch (InvocationTargetException error) { throw error.getCause(); }
    }
  }
}
