package com.example.lancerpick.admin;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.lancerpick.admin.dto.AdminFinanceResponses;
import com.example.lancerpick.admin.entity.AdminAction;
import com.example.lancerpick.admin.entity.AdminActionStatus;
import com.example.lancerpick.admin.service.*;
import com.example.lancerpick.common.exception.BusinessException;
import com.example.lancerpick.contract.document.ContractPdfRenderer;
import com.example.lancerpick.contract.document.PrivateContractStorage;
import com.example.lancerpick.contract.service.ContractDocumentService;
import com.example.lancerpick.contract.service.OrderContractService;
import com.example.lancerpick.member.entity.Member;
import com.example.lancerpick.member.repository.MemberRepository;
import com.example.lancerpick.order.entity.Order;
import com.example.lancerpick.order.entity.OrderSnapshot;
import com.example.lancerpick.order.repository.OrderRepository;
import com.example.lancerpick.order.repository.OrderSnapshotRepository;
import com.example.lancerpick.support.IntegrationTest;
import com.example.lancerpick.wallet.batch.TopupRecoveryLocalExecutions;
import com.example.lancerpick.wallet.gateway.TossGatewayException;
import com.example.lancerpick.wallet.gateway.TossPaymentGateway;
import com.example.lancerpick.wallet.gateway.TossPaymentGateway.Payment;
import com.example.lancerpick.wallet.repository.WalletEntryRepository;
import com.example.lancerpick.wallet.service.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 실제 감사/claim/지급/Batch/PDF, 제공자만 대역. 개인 DB와 구분한 통합 검사. */
@IntegrationTest
@SpringBootTest(
    properties = {
      "lancerpick.wallet.topup-recovery.enabled=true",
      "lancerpick.wallet.topup-recovery.scheduling-enabled=false",
      "lancerpick.wallet.topup-recovery.max-attempts=1"
    })
class AdminReprocessingIntegrationTests {
  private static final Instant BASE = Instant.parse("2026-11-10T00:00:00Z");
  static final Path directory = temporaryDirectory();
  @Autowired AdminReprocessingService service;
  @Autowired AdminActionAuditService audit;
  @Autowired AdminTopupRecheckService recheck;
  @Autowired TopupRecoveryTransactions recovery;
  @Autowired WalletTopupService topups;
  @Autowired TopupApprovalTransactions approvals;
  @Autowired WalletInitializationService wallets;
  @Autowired MemberRepository members;
  @Autowired OrderRepository orders;
  @Autowired OrderSnapshotRepository snapshots;
  @Autowired OrderContractService drafts;
  @Autowired ContractDocumentService documents;
  @Autowired JobRepository jobs;
  @Autowired PlatformTransactionManager manager;
  @Autowired JdbcTemplate jdbc;
  @MockitoBean Clock clock;
  @MockitoBean TossPaymentGateway gateway;
  @MockitoSpyBean ContractPdfRenderer renderer;
  @MockitoSpyBean PrivateContractStorage storage;
  @MockitoSpyBean WalletEntryRepository entries;
  @MockitoSpyBean TopupRecoveryLocalExecutions localExecutions;
  @Autowired AdminFinanceQueryService finance;
  long admin, otherAdmin, buyer, seller, orderId, product, category;
  final List<Long> memberIds = new ArrayList<>();
  final List<Long> instances = new ArrayList<>();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("contracts.storage-root", directory::toString);
  }

  static Path temporaryDirectory() {
    try {
      return Files.createTempDirectory("lancerpick-admin-pdf-");
    } catch (IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  @BeforeEach
  void fixture() {
    when(clock.instant()).thenReturn(BASE);
    when(clock.millis()).thenReturn(BASE.toEpochMilli());
    admin = member(true);
    otherAdmin = member(true);
    buyer = member(false);
    seller = member(false);
  }

  @AfterEach
  void cleanup() throws IOException {
    reset(entries, renderer, localExecutions);
    for (long id : memberIds) jdbc.update("DELETE FROM admin_action_logs WHERE admin_id=?", id);
    if (orderId != 0) {
      var files =
          jdbc.queryForList(
              "SELECT file_storage_key FROM order_contracts WHERE order_id=? AND file_storage_key"
                  + " IS NOT NULL",
              String.class,
              orderId);
      jdbc.update(
          "DELETE FROM contract_signatures WHERE contract_id IN (SELECT id FROM order_contracts"
              + " WHERE order_id=?)",
          orderId);
      jdbc.update("DELETE FROM order_contracts WHERE order_id=?", orderId);
      jdbc.update(
          "DELETE FROM notifications WHERE event_id IN (SELECT event_id FROM outbox_events WHERE"
              + " aggregate_type='ORDER' AND aggregate_id=?)",
          orderId);
      jdbc.update(
          "DELETE FROM outbox_events WHERE aggregate_type='ORDER' AND aggregate_id=?", orderId);
      jdbc.update("DELETE FROM order_histories WHERE order_id=?", orderId);
      jdbc.update("DELETE FROM order_snapshots WHERE order_id=?", orderId);
      jdbc.update("DELETE FROM orders WHERE id=?", orderId);
      jdbc.update("DELETE FROM products WHERE id=?", product);
      jdbc.update("DELETE FROM categories WHERE id=?", category);
      for (String file : files) storage.discardObsolete(file);
    }
    for (long id : memberIds) {
      jdbc.update("DELETE FROM wallet_entries WHERE member_id=?", id);
      jdbc.update("DELETE FROM wallet_topups WHERE member_id=?", id);
      jdbc.update("DELETE FROM wallets WHERE member_id=?", id);
      jdbc.update("DELETE FROM members WHERE id=?", id);
    }
    for (long id : instances) jobs.deleteJobInstance(jobs.getJobInstance(id));
  }

  @AfterAll
  static void removeOwnedTemporaryFiles() throws IOException {
    // 이 테스트가 직접 만든 고유 임시 디렉터리만 정리한다.
    try (var paths = Files.walk(directory)) {
      for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
    }
  }

  record Pending(long id, String key, String tossOrderId) {}

  @Test
  void recheckCommitsAuditBeforeProviderAndCreditsOnlyOnceOnReplay() {
    var pending = pending();
    var key = UUID.randomUUID();
    when(gateway.lookup(pending.key()))
        .thenAnswer(call -> {
          assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
          assertThat(
                  jdbc.queryForObject(
                      "SELECT status FROM admin_action_logs WHERE admin_id=? AND request_key=?",
                      String.class,
                      admin,
                      key.toString()))
              .isEqualTo("REQUESTED");
          return done(pending);
        });
    var first = service.recheckTopup(admin, pending.id(), "승인 결과 확인", key);
    assertThat(first.receipt().resultCode()).isEqualTo("TOPUP_CREDITED");
    assertThat(first.receipt().status()).isEqualTo(AdminActionStatus.SUCCEEDED);
    assertThat(balance()).isEqualTo(1_050_000);
    assertThat(credits(pending.id())).isEqualTo(1);
    assertThat(service.recheckTopup(admin, pending.id(), "승인 결과 확인", key).replay()).isTrue();
    verify(gateway, times(1)).lookup(pending.key());
    verify(gateway, never()).confirm(anyString(), anyString(), anyLong(), anyString());
  }

  @Test
  void manualRecoveryIgnoresAutomaticLimitWithoutResettingOrIncreasingIt() {
    var pending = pending();
    jdbc.update("UPDATE wallet_topups SET recovery_attempt_count=1 WHERE id=?", pending.id());
    when(gateway.lookup(pending.key()))
        .thenAnswer(call -> {
          assertThat(recovery.claim(pending.id(), BASE).isEmpty()).isTrue();
          return done(pending);
        });
    assertThat(
            service
                .recheckTopup(admin, pending.id(), "상한 이후 수동 확인", UUID.randomUUID())
                .receipt()
                .resultCode())
        .isEqualTo("TOPUP_CREDITED");
    assertThat(
            jdbc.queryForObject(
                "SELECT recovery_attempt_count FROM wallet_topups WHERE id=?",
                Integer.class,
                pending.id()))
        .isEqualTo(1);
  }

  @Test
  void unavailableProviderKeepsUnknownAndSetsBackoffWithoutGrantingMoney() {
    var pending = pending();
    when(gateway.lookup(pending.key())).thenThrow(new TossGatewayException());
    var result = service.recheckTopup(admin, pending.id(), "통신 결과 확인", UUID.randomUUID());
    assertThat(result.receipt().status()).isEqualTo(AdminActionStatus.UNKNOWN);
    assertThat(result.receipt().resultCode()).isEqualTo("TOPUP_RESULT_UNCONFIRMED");
    assertThat(balance()).isEqualTo(1_000_000);
    assertThat(credits(pending.id())).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT recovery_lease_until FROM wallet_topups WHERE id=?",
                Object.class,
                pending.id()))
        .isNull();
    assertThatThrownBy(() -> service.recheckTopup(admin, pending.id(), "재조회", UUID.randomUUID()))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  void mismatchedProviderAmountNeverCreditsAndIsNotMarkedAsConfirmedFailure() {
    var pending = pending();
    when(gateway.lookup(pending.key()))
        .thenReturn(
            new Payment(
                pending.key(),
                pending.tossOrderId(),
                1,
                1,
                "KRW",
                "DONE",
                OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC)));
    assertThat(
            service.recheckTopup(admin, pending.id(), "검증", UUID.randomUUID()).receipt().status())
        .isEqualTo(AdminActionStatus.UNKNOWN);
    assertThat(balance()).isEqualTo(1_000_000);
    assertThat(credits(pending.id())).isZero();
  }

  @Test
  void resolvedProviderFailureIsSuccessfulRecheckButDoesNotPay() {
    var pending = pending();
    when(gateway.lookup(pending.key()))
        .thenReturn(
            new Payment(pending.key(), pending.tossOrderId(), 50000, 0, "KRW", "CANCELED", null));
    var result = service.recheckTopup(admin, pending.id(), "조회", UUID.randomUUID());
    assertThat(result.receipt().status()).isEqualTo(AdminActionStatus.SUCCEEDED);
    assertThat(result.receipt().resultCode()).isEqualTo("TOPUP_RESOLVED_FAILED");
    assertThat(balance()).isEqualTo(1_000_000);
  }

  @Test
  void failedLedgerCommitRollsBackMoneyAndPreservesUnknownAudit() {
    var pending = pending();
    when(gateway.lookup(pending.key())).thenReturn(done(pending));
    doThrow(new DataAccessResourceFailureException("sensitive-original-error"))
        .when(entries)
        .insertTopup(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), any());
    var result = service.recheckTopup(admin, pending.id(), "확인", UUID.randomUUID());
    assertThat(result.receipt().resultCode()).isEqualTo("TOPUP_RESULT_UNCONFIRMED");
    assertThat(result.toString()).doesNotContain("sensitive-original-error", pending.key());
    assertThat(balance()).isEqualTo(1_000_000);
    assertThat(credits(pending.id())).isZero();
  }

  @Test
  void wrongOrExpiredAuditAndDemotedAdminCannotReachProvider() {
    var pending = pending();
    var start =
        audit.begin(admin, AdminAction.TOPUP_RECHECK, pending.id(), "확인", UUID.randomUUID());
    long logId = Long.parseLong(start.receipt().id());
    assertThatThrownBy(() -> recheck.recheck(otherAdmin, logId, pending.id()))
        .isInstanceOf(BusinessException.class);
    jdbc.update("UPDATE members SET role='USER' WHERE id=?", admin);
    assertThatThrownBy(() -> recheck.recheck(admin, logId, pending.id()))
        .isInstanceOf(BusinessException.class);
    jdbc.update("UPDATE members SET role='ADMIN' WHERE id=?", admin);
    when(clock.instant()).thenReturn(BASE.plusSeconds(722));
    assertThatThrownBy(() -> recheck.recheck(admin, logId, pending.id()))
        .isInstanceOf(BusinessException.class);
    // begin의 제공자 활성 설정 확인은 허용하지만 실제 외부 통신은 없어야 한다.
    verify(gateway, never()).lookup(anyString());
    verify(gateway, never()).confirm(anyString(), anyString(), anyLong(), anyString());
  }

  @Test
  void administratorsRacingForOneTopupCannotPerformTwoLookups() throws Exception {
    var pending = pending();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    when(gateway.lookup(pending.key()))
        .thenAnswer(call -> {
          entered.countDown();
          if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
          return done(pending);
        });
    try (var pool = Executors.newSingleThreadExecutor()) {
      var first =
          pool.submit(() -> service.recheckTopup(admin, pending.id(), "확인", UUID.randomUUID()));
      try {
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(
                () -> service.recheckTopup(otherAdmin, pending.id(), "확인", UUID.randomUUID()))
            .isInstanceOfSatisfying(
                BusinessException.class,
                e -> assertThat(e.code()).isEqualTo("ADMIN_ACTION_IN_PROGRESS"));
      } finally {
        release.countDown();
      }
      assertThat(first.get(10, TimeUnit.SECONDS).receipt().resultCode())
          .isEqualTo("TOPUP_CREDITED");
    }
    assertThat(credits(pending.id())).isEqualTo(1);
    verify(gateway, times(1)).lookup(pending.key());
  }

  @Test
  void batchRestartUsesExistingInstanceAndReplayDoesNotStartAnotherExecution() {
    var execution = failedJob();
    var key = UUID.randomUUID();
    var result = service.restartTopupRecovery(admin, execution.getId(), "실패 실행 재시작", key);
    assertThat(result.receipt().resultCode()).isEqualTo("BATCH_RESTART_ACCEPTED");
    var latest = jobs.getLastJobExecution(execution.getJobInstance());
    assertThat(latest.getId()).isNotEqualTo(execution.getId());
    assertThat(latest.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    assertThat(service.restartTopupRecovery(admin, execution.getId(), "실패 실행 재시작", key).replay())
        .isTrue();
    assertThat(jobs.getLastJobExecution(execution.getJobInstance()).getId())
        .isEqualTo(latest.getId());
  }

  @Test
  void runningJobIsNotForcedIntoRestartOrFailedState() {
    var execution = failedJob();
    execution.setStatus(BatchStatus.STARTED);
    jobs.update(execution);
    assertThatThrownBy(
            () -> service.restartTopupRecovery(admin, execution.getId(), "진행 중", UUID.randomUUID()))
        .isInstanceOf(BusinessException.class);
    assertThat(jobs.getJobExecution(execution.getId()).getStatus()).isEqualTo(BatchStatus.STARTED);
  }

  @Test
  void interruptedExecutionIsRecoveredThenRestartedOnceAndReplayChangesNothing() {
    var crashed = interruptedJob(Duration.ofHours(1));
    assertThat(finance.recoveryJob(admin, crashed.getId()).restart().reason())
        .isEqualTo(AdminFinanceResponses.BlockReason.JOB_RUNNING);
    assertThat(finance.recoveryJob(admin, crashed.getId()).recoverRestart().eligible()).isTrue();
    var key = UUID.randomUUID();

    var result = service.recoverAndRestartTopupRecovery(admin, crashed.getId(), "프로세스 종료 확인", key);

    assertThat(result.replay()).isFalse();
    assertThat(result.receipt().action()).isEqualTo(AdminAction.TOPUP_RECOVERY_RECOVER_RESTART);
    assertThat(result.receipt().targetType()).isEqualTo("BATCH_JOB_EXECUTION");
    assertThat(result.receipt().status()).isEqualTo(AdminActionStatus.SUCCEEDED);
    assertThat(result.receipt().resultCode()).isEqualTo("BATCH_RECOVER_RESTART_ACCEPTED");
    var original = jobs.getJobExecution(crashed.getId());
    assertThat(original.getStatus()).isEqualTo(BatchStatus.FAILED);
    assertThat(original.getStepExecutions())
        .allSatisfy(step -> assertThat(step.getStatus()).isEqualTo(BatchStatus.FAILED));
    var latest = jobs.getLastJobExecution(crashed.getJobInstance());
    assertThat(latest.getId()).isNotEqualTo(crashed.getId());
    assertThat(latest.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    assertThat(jobs.getJobExecutions(crashed.getJobInstance())).hasSize(2);

    // 같은 키 재전송은 recover·restart를 다시 부르지 않는다.
    assertThat(
            service.recoverAndRestartTopupRecovery(admin, crashed.getId(), "프로세스 종료 확인", key).replay())
        .isTrue();
    assertThat(jobs.getJobExecutions(crashed.getJobInstance())).hasSize(2);
    // 이미 정리된 실행에 새 키로 다시 요청해도 접수 전에 거절된다.
    assertThatThrownBy(
            () ->
                service.recoverAndRestartTopupRecovery(
                    admin, crashed.getId(), "다시 정리", UUID.randomUUID()))
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.code()).isEqualTo("ADMIN_ACTION_NOT_ELIGIBLE"));
    assertThat(jobs.getJobExecutions(crashed.getJobInstance())).hasSize(2);
  }

  @Test
  void recentlyUpdatedOrLocallyRunningOrFailedExecutionIsNeverRecovered() {
    var recent = interruptedJob(Duration.ofMinutes(5));
    assertThat(finance.recoveryJob(admin, recent.getId()).recoverRestart().reason())
        .isEqualTo(AdminFinanceResponses.BlockReason.JOB_RECENTLY_UPDATED);
    rejectedBeforeAudit(recent.getId());
    assertThat(jobs.getJobExecution(recent.getId()).getStatus()).isEqualTo(BatchStatus.STARTED);

    var local = interruptedJob(Duration.ofHours(1));
    localExecutions.beforeJob(local);
    try {
      assertThat(finance.recoveryJob(admin, local.getId()).recoverRestart().reason())
          .isEqualTo(AdminFinanceResponses.BlockReason.JOB_RUNNING);
      rejectedBeforeAudit(local.getId());
      assertThat(jobs.getJobExecution(local.getId()).getStatus()).isEqualTo(BatchStatus.STARTED);
    } finally {
      localExecutions.afterJob(local);
    }

    // 실패 실행은 일반 재시작 대상이며 정리 명령의 후보가 아니다.
    var failed = failedJob();
    assertThat(finance.recoveryJob(admin, failed.getId()).recoverRestart().reason())
        .isEqualTo(AdminFinanceResponses.BlockReason.JOB_NOT_INTERRUPTED);
    rejectedBeforeAudit(failed.getId());
    assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM admin_action_logs WHERE admin_id=?", Long.class, admin))
        .isZero();
  }

  @Test
  void executionThatComesAliveAfterAuditIsLeftUntouchedAndRecordedAsRejected() {
    var crashed = interruptedJob(Duration.ofHours(1));
    // 접수 검사·claim 재검사까지는 중단으로 보이다가, 실제 recover 직전 재검사에서 이 서버 실행으로 확인되는 경쟁.
    doReturn(false, false, true).when(localExecutions).isRunningHere(crashed.getId());

    var result =
        service.recoverAndRestartTopupRecovery(admin, crashed.getId(), "정리 시도", UUID.randomUUID());

    assertThat(result.receipt().status()).isEqualTo(AdminActionStatus.REJECTED);
    assertThat(result.receipt().resultCode()).isEqualTo("BATCH_RECOVER_REJECTED");
    var after = jobs.getJobExecution(crashed.getId());
    assertThat(after.getStatus()).isEqualTo(BatchStatus.STARTED);
    assertThat(after.getExecutionContext().containsKey("batch.recovered")).isFalse();
    assertThat(jobs.getJobExecutions(crashed.getJobInstance())).hasSize(1);
  }

  @Test
  void ordinaryMemberCannotRecoverInterruptedExecution() {
    var crashed = interruptedJob(Duration.ofHours(1));
    assertThatThrownBy(
            () ->
                service.recoverAndRestartTopupRecovery(
                    buyer, crashed.getId(), "확인", UUID.randomUUID()))
        .isInstanceOf(BusinessException.class);
    assertThat(jobs.getJobExecution(crashed.getId()).getStatus()).isEqualTo(BatchStatus.STARTED);
  }

  private void rejectedBeforeAudit(long executionId) {
    assertThatThrownBy(
            () ->
                service.recoverAndRestartTopupRecovery(
                    admin, executionId, "정리 시도", UUID.randomUUID()))
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.code()).isEqualTo("ADMIN_ACTION_NOT_ELIGIBLE"));
  }

  /** 프로세스가 체크포인트 저장 전에 종료돼 실행·단계가 STARTED로 남고, 마지막 갱신이 silentFor 전인 실행. */
  org.springframework.batch.core.job.JobExecution interruptedJob(Duration silentFor) {
    var params =
        new JobParametersBuilder()
            .addString("run.id", UUID.randomUUID().toString())
            .addLong("requestedAtMillis", BASE.toEpochMilli())
            .toJobParameters();
    var instance = jobs.createJobInstance("walletTopupRecovery", params);
    instances.add(instance.getInstanceId());
    var execution = jobs.createJobExecution(instance, params, new ExecutionContext());
    execution.setStatus(BatchStatus.STARTED);
    execution.setStartTime(LocalDateTime.now());
    jobs.update(execution);
    var step = jobs.createStepExecution("walletTopupRecoveryStep", execution);
    step.setStatus(BatchStatus.STARTED);
    step.setStartTime(LocalDateTime.now());
    jobs.update(step);
    // Batch 메타데이터 시각은 서버 기본 시간대의 LocalDateTime이다. 갱신이 멈춘 시점을 과거로 옮긴다.
    var at = LocalDateTime.now().minus(silentFor);
    jdbc.update(
        "UPDATE BATCH_JOB_EXECUTION SET CREATE_TIME=?,START_TIME=?,LAST_UPDATED=? WHERE"
            + " JOB_EXECUTION_ID=?",
        at,
        at,
        at,
        execution.getId());
    jdbc.update(
        "UPDATE BATCH_STEP_EXECUTION SET CREATE_TIME=?,START_TIME=?,LAST_UPDATED=? WHERE"
            + " STEP_EXECUTION_ID=?",
        at,
        at,
        at,
        step.getId());
    return jobs.getJobExecution(execution.getId());
  }

  @Test
  void adminGeneratesActualPdfWithoutBeingSellerAndReplayNeverRendersTwice() throws Exception {
    long contract = contract();
    var key = UUID.randomUUID();
    var result = service.regenerateContractPdf(admin, contract, "계약 PDF 생성", key);
    assertThat(result.receipt().resultCode()).isEqualTo("PDF_GENERATED");
    var file = documents.download(buyer, orderId);
    assertThat(new String(file, 0, 4, java.nio.charset.StandardCharsets.US_ASCII))
        .isEqualTo("%PDF");
    assertThat(
            jdbc.queryForObject(
                "SELECT file_hash FROM order_contracts WHERE id=?", byte[].class, contract))
        .isEqualTo(PrivateContractStorage.hash(file));
    assertThat(service.regenerateContractPdf(admin, contract, "계약 PDF 생성", key).replay()).isTrue();
    verify(renderer, times(1)).render(anyLong(), anyInt(), anyString());
    assertThat(result.toString())
        .doesNotContain("fileHash", "canonical", "generationKey", "fileStorageKey");
  }

  @Test
  void failedPdfCanBeRetriedAndCancelledOrderIsRejected() throws Exception {
    long contract = contract();
    doThrow(new IllegalStateException("private-render-error"))
        .when(renderer)
        .render(anyLong(), anyInt(), anyString());
    var first = service.regenerateContractPdf(admin, contract, "재시도 준비", UUID.randomUUID());
    assertThat(first.receipt().resultCode()).isEqualTo("PDF_GENERATION_FAILED");
    assertThat(first.receipt().status()).isEqualTo(AdminActionStatus.FAILED);
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM order_contracts WHERE id=?", String.class, contract))
        .isEqualTo("FAILED");
    reset(renderer);
    assertThat(
            service
                .regenerateContractPdf(admin, contract, "실패 이후 재생성", UUID.randomUUID())
                .receipt()
                .resultCode())
        .isEqualTo("PDF_GENERATED");
    jdbc.update(
        "UPDATE orders SET status='CANCELLED',cancelled_at=?,updated_at=? WHERE id=?",
        LocalDateTime.ofInstant(BASE, ZoneOffset.UTC),
        LocalDateTime.ofInstant(BASE, ZoneOffset.UTC),
        orderId);
    assertThatThrownBy(
            () -> service.regenerateContractPdf(admin, contract, "종료 주문", UUID.randomUUID()))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  void ordinaryMemberCannotUseAnyAdminCommand() {
    assertThatThrownBy(() -> service.recheckTopup(buyer, 999, "확인", UUID.randomUUID()))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> service.restartTopupRecovery(buyer, 999, "확인", UUID.randomUUID()))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> service.regenerateContractPdf(buyer, 999, "확인", UUID.randomUUID()))
        .isInstanceOf(BusinessException.class);
    verifyNoInteractions(gateway);
  }

  @Test
  void pdfPublicationRechecksOrderAfterRenderingAndDiscardsUnusedFile() throws Exception {
    long contract = contract();
    doAnswer(
            call -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              var bytes = call.callRealMethod();
              var at = LocalDateTime.ofInstant(BASE, ZoneOffset.UTC);
              jdbc.update(
                  "UPDATE orders SET status='CANCELLED',cancelled_at=?,updated_at=? WHERE id=?",
                  at,
                  at,
                  orderId);
              return bytes;
            })
        .when(renderer)
        .render(anyLong(), anyInt(), anyString());
    var result = service.regenerateContractPdf(admin, contract, "생성 중 취소 경합", UUID.randomUUID());
    assertThat(result.receipt().resultCode()).isEqualTo("PDF_STATE_CHANGED");
    assertThat(
            jdbc.queryForObject(
                "SELECT file_storage_key FROM order_contracts WHERE id=?", String.class, contract))
        .isNull();
    verify(storage).discardObsolete(anyString());
  }

  Pending pending() {
    var response = topups.create(buyer, 50000);
    long id = Long.parseLong(response.id());
    String key = "private-payment-" + id;
    approvals.prepare(buyer, id, key, response.tossOrderId(), 50000);
    when(clock.instant()).thenReturn(BASE.plusSeconds(121));
    return new Pending(id, key, response.tossOrderId());
  }

  Payment done(Pending pending) {
    return new Payment(
        pending.key(),
        pending.tossOrderId(),
        50000,
        50000,
        "KRW",
        "DONE",
        OffsetDateTime.ofInstant(BASE.plusSeconds(1), ZoneOffset.UTC));
  }

  long balance() {
    return jdbc.queryForObject("SELECT balance FROM wallets WHERE member_id=?", Long.class, buyer);
  }

  long credits(long id) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM wallet_entries WHERE topup_id=?", Long.class, id);
  }

  long member(boolean adminRole) {
    return new TransactionTemplate(manager)
        .execute(ignored -> {
          var member =
              members.saveAndFlush(
                  Member.create(
                      UUID.randomUUID() + "@ops.test",
                      "fixture",
                      "운영 검증",
                      LocalDateTime.ofInstant(BASE.minusSeconds(60), ZoneOffset.UTC)));
          memberIds.add(member.getId());
          wallets.initializeForNewMember(member.getId());
          if (adminRole) jdbc.update("UPDATE members SET role='ADMIN' WHERE id=?", member.getId());
          return member.getId();
        });
  }

  org.springframework.batch.core.job.JobExecution failedJob() {
    var params =
        new JobParametersBuilder()
            .addString("run.id", UUID.randomUUID().toString())
            .addLong("requestedAtMillis", BASE.toEpochMilli())
            .toJobParameters();
    var instance = jobs.createJobInstance("walletTopupRecovery", params);
    instances.add(instance.getInstanceId());
    var execution = jobs.createJobExecution(instance, params, new ExecutionContext());
    execution.setStatus(BatchStatus.FAILED);
    jobs.update(execution);
    return execution;
  }

  long contract() {
    new TransactionTemplate(manager)
        .executeWithoutResult(ignored -> {
          var at = LocalDateTime.ofInstant(BASE.minusSeconds(30), ZoneOffset.UTC);
          String name = "ops-" + UUID.randomUUID();
          jdbc.update("INSERT INTO categories(name,display_order) VALUES (?,0)", name);
          category =
              jdbc.queryForObject("SELECT id FROM categories WHERE name=?", Long.class, name);
          jdbc.update(
              "INSERT INTO"
                  + " products(seller_id,category_id,title,description,scope_description,preparation_description,price,duration_days,created_at,updated_at)"
                  + " VALUES (?,?,'상품','설명','범위','준비',10000,1,?,?)",
              seller,
              category,
              at,
              at);
          product =
              jdbc.queryForObject("SELECT id FROM products WHERE seller_id=?", Long.class, seller);
          var order = Order.create(buyer, seller, product, UUID.randomUUID(), 10000L, 1, at);
          order.accept(at);
          orderId = orders.saveAndFlush(order).getId();
          snapshots.saveAndFlush(
              OrderSnapshot.create(orderId, 0L, "상품", "설명", "범위", "준비", "조건", "요구사항"));
          jdbc.update(
              "UPDATE orders SET policy_version='CONTRACT_V3',status='AWAITING_CONTRACT' WHERE"
                  + " id=?",
              orderId);
        });
    return Long.parseLong(drafts.prepare(seller, orderId, 0, "범위 내 특약").id());
  }
}
