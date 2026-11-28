package com.example.lancerpick.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.lancerpick.admin.dto.AdminFinanceResponses.BlockReason;
import com.example.lancerpick.admin.entity.AdminActionStatus;
import com.example.lancerpick.admin.service.AdminFinanceQueryService;
import com.example.lancerpick.admin.service.AdminReprocessingService;
import com.example.lancerpick.common.exception.BusinessException;
import com.example.lancerpick.support.IntegrationTest;
import com.example.lancerpick.support.RecoveryWorkerProcess;
import com.example.lancerpick.wallet.dto.WalletTopupResponse;
import com.example.lancerpick.wallet.entity.WalletTopupStatus;
import com.example.lancerpick.wallet.gateway.TossPaymentGateway;
import com.example.lancerpick.wallet.gateway.TossPaymentGateway.Payment;
import com.example.lancerpick.wallet.service.TopupApprovalTransactions;
import com.example.lancerpick.wallet.service.WalletInitializationService;
import com.example.lancerpick.wallet.service.WalletTopupService;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 실제 별도 JVM({@link RecoveryWorkerProcess})이 같은 테스트 DB에서 복구 Job을 실행하다 멈춘 상태로, 관리자 정리 명령을 검증한다.
 *
 * <ul>
 *   <li>작업자를 SIGKILL로 강제 종료한 뒤의 정리·재시작
 *   <li>작업자가 살아 있는 동안(다른 서버의 실행) 정리 명령이 거절되는지, 그리고 기준 시간 동안 갱신이 멈춘 살아 있는 실행을 정리했을 때도 중복 지급이
 *       없는지(알려진 오판 한계의 재현)
 * </ul>
 *
 * <p>30분 경과는 기다리지 않고 Batch·충전 시각을 DB에서 과거로 옮겨 재현한다.
 */
@IntegrationTest
@SpringBootTest(
    properties = {
      "lancerpick.wallet.topup-recovery.enabled=true",
      "lancerpick.wallet.topup-recovery.scheduling-enabled=false"
    })
class AdminInterruptedRecoveryProcessTests {
  private static final AtomicLong MEMBER_IDS = new AtomicLong(4_900_000);
  private static final long AMOUNT = 50000;
  private static final String WORKER_ZONE =
      java.util.TimeZone.getDefault().getRawOffset() == 0 ? "Asia/Seoul" : "UTC";
  @Autowired AdminReprocessingService service;
  @Autowired AdminFinanceQueryService finance;
  @Autowired WalletTopupService topups;
  @Autowired TopupApprovalTransactions approvals;
  @Autowired WalletInitializationService wallets;
  @Autowired JobRepository jobs;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager manager;
  @MockitoBean TossPaymentGateway gateway;
  final List<Long> members = new ArrayList<>();
  long admin;
  Process worker;
  Path signal;
  final BlockingQueue<String> output = new LinkedBlockingQueue<>();

  record Pending(long owner, WalletTopupResponse topup, String key) {
    long id() {
      return Long.parseLong(topup.id());
    }
  }

  @BeforeEach
  void fixture() throws IOException {
    admin = member("ADMIN");
    signal = Files.createTempDirectory("lancerpick-worker-").resolve("resume");
  }

  @AfterEach
  void cleanup() throws Exception {
    if (worker != null && worker.isAlive()) worker.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
    Files.deleteIfExists(signal);
    Files.deleteIfExists(signal.getParent());
    for (long id : members) jdbc.update("DELETE FROM admin_action_logs WHERE admin_id=?", id);
    for (long instance :
        jdbc.queryForList(
            "SELECT JOB_INSTANCE_ID FROM BATCH_JOB_INSTANCE WHERE JOB_NAME='walletTopupRecovery'",
            Long.class)) jobs.deleteJobInstance(jobs.getJobInstance(instance));
    for (long id : members) {
      jdbc.update("DELETE FROM wallet_entries WHERE member_id=?", id);
      jdbc.update("DELETE FROM wallet_topups WHERE member_id=?", id);
      jdbc.update("DELETE FROM wallets WHERE member_id=?", id);
      jdbc.update("DELETE FROM members WHERE id=?", id);
    }
  }

  @Test
  void workerKilledMidJobIsRecoveredOnlyAfterQuietPeriodAndPaysEachTopupOnce() throws Exception {
    var first = pending();
    var second = pending();
    var execution = startWorker(first, second);

    // 첫 건 지급은 커밋됐고 둘째 건 조회 중에 프로세스를 강제 종료한다(SIGKILL, 종료 훅·리스너 없음).
    assertCredited(first);
    worker.destroyForcibly();
    assertThat(worker.waitFor(20, TimeUnit.SECONDS)).isTrue();
    assertThat(worker.exitValue()).isNotZero();
    var crashed = jobs.getJobExecution(execution);
    assertThat(crashed.getStatus()).isEqualTo(BatchStatus.STARTED);
    assertThat(crashed.getStepExecutions())
        .allSatisfy(step -> assertThat(step.getStatus()).isEqualTo(BatchStatus.STARTED));
    assertThat(credits(second.id())).isZero();

    // 방금까지 갱신된 실행은 죽었는지 알 수 없으므로 정리하지 않는다.
    assertThat(finance.recoveryJob(admin, execution).recoverRestart().reason())
        .isEqualTo(BlockReason.JOB_RECENTLY_UPDATED);
    assertThatThrownBy(() -> recoverRestart(execution)).isInstanceOf(BusinessException.class);

    // 30분 이상 지난 상태로 옮긴다: Batch 갱신 시각과 죽은 작업자가 잡고 있던 충전 처리권.
    silenceFor(execution, Duration.ofMinutes(31));
    jdbc.update(
        "UPDATE wallet_topups SET recovery_lease_until=? WHERE id=?",
        LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1),
        second.id());
    when(gateway.lookup(second.key())).thenReturn(done(second));

    var result = recoverRestart(execution);

    assertThat(result.receipt().resultCode()).isEqualTo("BATCH_RECOVER_RESTART_ACCEPTED");
    assertThat(jobs.getJobExecution(execution).getStatus()).isEqualTo(BatchStatus.FAILED);
    var latest = jobs.getLastJobExecution(crashed.getJobInstance());
    assertThat(latest.getId()).isNotEqualTo(execution);
    assertThat(latest.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    assertCredited(first);
    assertCredited(second);
    // 강제 종료 전 지급한 첫 건은 다시 조회하지 않는다.
    verify(gateway, never()).lookup(first.key());
    verify(gateway, times(1)).lookup(second.key());
  }

  @Test
  void liveWorkerInAnotherJvmIsRejectedAndEvenAMisjudgedOneNeverPaysTwice() throws Exception {
    var first = pending();
    var second = pending();
    var execution = startWorker(first, second);
    assertCredited(first);

    // 다른 JVM에서 살아 있는 실행(이 서버 실행 목록에는 없음). 최근 갱신이 있어 정리 대상이 아니다.
    assertThat(finance.recoveryJob(admin, execution).recoverRestart().reason())
        .isEqualTo(BlockReason.JOB_RECENTLY_UPDATED);
    assertThatThrownBy(() -> recoverRestart(execution)).isInstanceOf(BusinessException.class);
    assertThat(jobs.getJobExecution(execution).getStatus()).isEqualTo(BatchStatus.STARTED);
    assertThat(worker.isAlive()).isTrue();

    // 알려진 한계: 살아 있는 실행이 한 건 조회에서 30분 넘게 멈춰 메타데이터를 갱신하지 못하면 정리 대상으로 오판된다.
    silenceFor(execution, Duration.ofMinutes(31));
    assertThat(finance.recoveryJob(admin, execution).recoverRestart().eligible()).isTrue();
    var result = recoverRestart(execution);
    assertThat(result.receipt().resultCode()).isEqualTo("BATCH_RECOVER_RESTART_ACCEPTED");
    var latest = jobs.getLastJobExecution(jobs.getJobExecution(execution).getJobInstance());
    assertThat(latest.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    // 둘째 건은 살아 있는 작업자가 처리권을 쥐고 있어 재시작 실행이 건드리지 않았다.
    verify(gateway, never()).lookup(anyString());
    assertThat(credits(second.id())).isZero();

    // 멈췄던 작업자가 깨어나 둘째 건을 지급하고, Batch 버전 검사로 자기 실행 저장에 실패한다.
    Files.createFile(signal);
    await("RESUMED");
    assertThat(worker.waitFor(60, TimeUnit.SECONDS)).isTrue();
    assertCredited(first);
    assertCredited(second);
    // 깨어난 작업자는 Step 저장이 버전 펜스로 실패해 자기 실행을 UNKNOWN으로 남긴다(Batch가 메타데이터 저장 실패 시 쓰는 상태).
    // 원래 실행은 정리 명령이 FAILED로 바꾼 뒤 작업자가 UNKNOWN으로 덮는다. 어느 쪽이든 재시작·정리 후보가 아니다.
    assertThat(jobs.getJobExecution(execution).getStatus())
        .isIn(BatchStatus.FAILED, BatchStatus.UNKNOWN);
    System.out.println("MISJUDGED_ORIGINAL_STATUS " + jobs.getJobExecution(execution).getStatus());
    assertThat(jobs.getJobExecutions(latest.getJobInstance())).hasSize(2);
  }

  private AdminReprocessingService.Result recoverRestart(long execution) {
    return service.recoverAndRestartTopupRecovery(admin, execution, "작업자 종료 확인", UUID.randomUUID());
  }

  /** 작업자를 띄워 첫 건 지급 뒤 둘째 건 조회에서 멈출 때까지 기다리고, 그 실행 ID를 돌려준다. */
  private long startWorker(Pending first, Pending second) throws Exception {
    // 처리 시작이 복구 기준(120초)보다 오래된 미확정 충전으로 만든다.
    for (var pending : List.of(first, second)) {
      var at = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(10);
      jdbc.update(
          "UPDATE wallet_topups SET created_at=?,processing_started_at=? WHERE id=?",
          at,
          at,
          pending.id());
    }
    long before =
        jdbc.queryForObject("SELECT COALESCE(MAX(JOB_EXECUTION_ID),0) FROM BATCH_JOB_EXECUTION", Long.class);
    String java = ProcessHandle.current().info().command().orElse("java");
    worker =
        new ProcessBuilder(
                java,
                // 작업자 JVM은 이 테스트 JVM과 다른 시간대로 띄운다. 시각 판정이 JVM 시간대에 의존하지 않는지 함께 확인한다.
                "-Duser.timezone=" + WORKER_ZONE,
                "-cp",
                System.getProperty("java.class.path"),
                RecoveryWorkerProcess.class.getName(),
                first.key(),
                first.topup().tossOrderId(),
                second.key(),
                second.topup().tossOrderId(),
                Long.toString(AMOUNT),
                signal.toString())
            .redirectErrorStream(true)
            .start();
    var reader =
        new BufferedReader(new InputStreamReader(worker.getInputStream(), StandardCharsets.UTF_8));
    Thread.ofVirtual()
        .start(() -> {
          try {
            for (String line; (line = reader.readLine()) != null; ) output.add(line);
          } catch (IOException ignored) {
            // 강제 종료 시 스트림이 닫힌다.
          }
        });
    await("BLOCKED " + second.key());
    return jdbc.queryForObject(
        "SELECT JOB_EXECUTION_ID FROM BATCH_JOB_EXECUTION e JOIN BATCH_JOB_INSTANCE i ON"
            + " e.JOB_INSTANCE_ID=i.JOB_INSTANCE_ID WHERE i.JOB_NAME='walletTopupRecovery' AND"
            + " e.JOB_EXECUTION_ID>? AND e.STATUS='STARTED'",
        Long.class,
        before);
  }

  private void await(String prefix) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
    var seen = new ArrayList<String>();
    while (System.nanoTime() < deadline) {
      String line = output.poll(1, TimeUnit.SECONDS);
      if (line == null) {
        if (!worker.isAlive() && output.isEmpty())
          throw new AssertionError("worker exited before " + prefix + ": " + tail(seen));
        continue;
      }
      seen.add(line);
      if (line.startsWith(prefix)) return;
    }
    throw new AssertionError("worker did not reach " + prefix + ": " + tail(seen));
  }

  private static List<String> tail(List<String> lines) {
    return lines.subList(Math.max(0, lines.size() - 40), lines.size());
  }

  private void silenceFor(long execution, Duration age) {
    var at = java.sql.Timestamp.from(Instant.now().minus(age));
    jdbc.update(
        "UPDATE BATCH_JOB_EXECUTION SET CREATE_TIME=?,START_TIME=?,LAST_UPDATED=? WHERE"
            + " JOB_EXECUTION_ID=?",
        at,
        at,
        at,
        execution);
    jdbc.update(
        "UPDATE BATCH_STEP_EXECUTION SET CREATE_TIME=?,START_TIME=?,LAST_UPDATED=? WHERE"
            + " JOB_EXECUTION_ID=?",
        at,
        at,
        at,
        execution);
  }

  private Pending pending() {
    long owner = member("USER");
    var topup = topups.create(owner, AMOUNT);
    String key = "process_kill_payment_" + topup.id();
    approvals.prepare(owner, Long.parseLong(topup.id()), key, topup.tossOrderId(), topup.amount());
    return new Pending(owner, topup, key);
  }

  private Payment done(Pending pending) {
    return new Payment(
        pending.key(),
        pending.topup().tossOrderId(),
        AMOUNT,
        AMOUNT,
        "KRW",
        "DONE",
        OffsetDateTime.now(ZoneOffset.UTC));
  }

  private void assertCredited(Pending pending) {
    assertThat(credits(pending.id())).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM wallet_topups WHERE id=?", String.class, pending.id()))
        .isEqualTo(WalletTopupStatus.COMPLETED.name());
  }

  private long credits(long topupId) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM wallet_entries WHERE topup_id=?", Long.class, topupId);
  }

  private long member(String role) {
    long id = MEMBER_IDS.incrementAndGet();
    members.add(id);
    new TransactionTemplate(manager)
        .executeWithoutResult(status -> {
          var now = LocalDateTime.now(ZoneOffset.UTC).minusHours(1);
          jdbc.update(
              "INSERT INTO members(id,email,password_hash,display_name,role,created_at,updated_at)"
                  + " VALUES (?,?,?,'프로세스 복구',?,?,?)",
              id,
              "process-recovery-" + id + "@example.test",
              "fixture-hash",
              role,
              now,
              now);
          wallets.initializeForNewMember(id);
        });
    return id;
  }

}
