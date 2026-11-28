package com.example.lancerpick.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.example.lancerpick.admin.entity.AdminActionStatus;
import com.example.lancerpick.admin.service.AdminReprocessingService;
import com.example.lancerpick.common.exception.BusinessException;
import com.example.lancerpick.support.IntegrationTest;
import com.example.lancerpick.wallet.batch.TopupRecoveryLocalExecutions;
import com.example.lancerpick.wallet.gateway.TossPaymentGateway;
import com.example.lancerpick.wallet.service.TopupRecoveryJobService;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 중단 실행 정리 명령의 경쟁·실패 주입. Codex 검토에서 미검증으로 남은 항목을 재현한다.
 *
 * <ul>
 *   <li>재검사 뒤 실행이 갱신되면(버전·마지막 갱신 시각) recover가 덮어쓰지 않는다
 *   <li>recover 도중 실패하면 Job·Step 변경이 모두 롤백되고 결과 불명으로 기록된다
 *   <li>같은 실행에 일반 재시작과 정리 명령이 겹쳐도 새 실행은 하나뿐이다
 *   <li>긴 동기 재시작 중 감사 처리 시간이 지나도 다른 요청이 같은 실행을 다시 정리·재시작하지 못한다
 * </ul>
 */
@IntegrationTest
@SpringBootTest(
    properties = {
      "lancerpick.wallet.topup-recovery.enabled=true",
      "lancerpick.wallet.topup-recovery.scheduling-enabled=false"
    })
class AdminInterruptedRecoveryRaceTests {
  private static final AtomicLong MEMBER_IDS = new AtomicLong(4_950_000);
  @Autowired AdminReprocessingService service;
  @MockitoSpyBean JobRepository jobs;
  @Autowired JdbcTemplate jdbc;
  @MockitoBean Clock clock;
  @MockitoBean TossPaymentGateway gateway;
  @MockitoSpyBean TopupRecoveryJobService jobService;
  @MockitoSpyBean JobOperator operator;
  @MockitoSpyBean TopupRecoveryLocalExecutions localExecutions;
  final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());
  final List<Long> members = new ArrayList<>();
  final List<Long> instances = new ArrayList<>();
  long admin, otherAdmin;

  @BeforeEach
  void fixture() {
    when(clock.instant()).thenAnswer(ignored -> now.get());
    when(clock.millis()).thenAnswer(ignored -> now.get().toEpochMilli());
    when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    admin = admin();
    otherAdmin = admin();
  }

  @AfterEach
  void cleanup() {
    reset(jobService, operator, localExecutions, jobs);
    for (long id : members) jdbc.update("DELETE FROM admin_action_logs WHERE admin_id=?", id);
    for (long id : instances) jobs.deleteJobInstance(jobs.getJobInstance(id));
    for (long id : members) jdbc.update("DELETE FROM members WHERE id=?", id);
  }

  @Test
  void versionBumpAfterRecheckIsNeverOverwrittenByRecover() throws Exception {
    var crashed = interruptedJob();
    // 관리자 재검사(claim)와 recover 사이에 다른 프로세스가 Job 실행 행을 갱신한 상황. 마지막 갱신 시각은 그대로 둔다.
    doAnswer(call -> {
          jdbc.update(
              "UPDATE BATCH_JOB_EXECUTION SET VERSION=VERSION+1 WHERE JOB_EXECUTION_ID=?",
              crashed.getId());
          return call.callRealMethod();
        })
        .when(jobService)
        .recoverInterrupted(anyLong(), any());

    var result = recoverRestart(admin, crashed.getId());

    assertThat(result.receipt().status()).isEqualTo(AdminActionStatus.REJECTED);
    assertThat(result.receipt().resultCode()).isEqualTo("BATCH_RECOVER_REJECTED");
    assertUntouched(crashed);
  }

  @Test
  void stepHeartbeatAfterRecheckIsNeverOverwrittenByRecover() throws Exception {
    var crashed = interruptedJob();
    // 살아 있던 작업자가 한 건을 끝내고 Step을 커밋한 상황(버전·마지막 갱신 시각 모두 바뀜).
    doAnswer(call -> {
          jdbc.update(
              "UPDATE BATCH_STEP_EXECUTION SET VERSION=VERSION+1,LAST_UPDATED=? WHERE"
                  + " JOB_EXECUTION_ID=?",
              Timestamp.from(Instant.now()),
              crashed.getId());
          return call.callRealMethod();
        })
        .when(jobService)
        .recoverInterrupted(anyLong(), any());

    var result = recoverRestart(admin, crashed.getId());

    assertThat(result.receipt().resultCode()).isEqualTo("BATCH_RECOVER_REJECTED");
    assertUntouched(crashed);
  }

  @Test
  void failureInsideRecoverRollsBackJobAndStepAndIsRecordedAsUnconfirmed() throws Exception {
    for (RuntimeException failure :
        List.of(
            new OptimisticLockingFailureException("injected"),
            new IllegalStateException("injected"))) {
      var crashed = interruptedJob();
      // recover는 Step을 FAILED로 저장한 다음 Job을 저장한다. Job 저장에서 실패시키면 앞선 Step 변경까지 롤백돼야 한다.
      doAnswer(call -> {
            JobExecution saving = call.getArgument(0);
            if (saving.getId() == crashed.getId() && saving.getStatus() == BatchStatus.FAILED)
              throw failure;
            return call.callRealMethod();
          })
          .when(jobs)
          .update(any(JobExecution.class));

      var result = recoverRestart(admin, crashed.getId());

      assertThat(result.receipt().status()).isEqualTo(AdminActionStatus.UNKNOWN);
      assertThat(result.receipt().resultCode()).isEqualTo("BATCH_RESULT_UNCONFIRMED");
      reset(jobs);
      assertUntouched(crashed);
    }
  }

  @Test
  void plainRestartAfterRecoverIsBlockedWhileRecoverRestartIsInProgress() throws Exception {
    var crashed = interruptedJob();
    var plain = new AtomicReference<Throwable>();
    var first = new AtomicBoolean(true);
    // recover가 FAILED로 커밋한 직후, 정리 명령의 restart 전에 다른 관리자가 일반 재시작을 보낸다.
    // (V14 전에는 이 요청이 통과해 재시작이 두 번 실행됐다. 진행 중 UNIQUE가 대상 기준이 되며 409로 막힌다.)
    doAnswer(call -> {
          if (first.getAndSet(false)) {
            try {
              CompletableFuture.runAsync(() ->
                      service.restartTopupRecovery(
                          otherAdmin, crashed.getId(), "실패 실행 재시작", UUID.randomUUID()))
                  .get(60, TimeUnit.SECONDS);
            } catch (Exception rejected) {
              plain.set(rejected.getCause());
            }
          }
          return call.callRealMethod();
        })
        .when(jobService)
        .restart(anyLong());

    var result = recoverRestart(admin, crashed.getId());

    assertThat(plain.get())
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.code()).isEqualTo("ADMIN_ACTION_IN_PROGRESS"));
    assertThat(result.receipt().resultCode()).isEqualTo("BATCH_RECOVER_RESTART_ACCEPTED");
    var executions = jobs.getJobExecutions(crashed.getJobInstance());
    assertThat(executions).hasSize(2);
    assertThat(executions.stream().filter(e -> e.getStatus() == BatchStatus.COMPLETED)).hasSize(1);
  }

  @Test
  void concurrentRestartRequestsFromTwoAdminsStartOnlyOneExecution() throws Exception {
    var crashed = interruptedJob();
    // 먼저 FAILED로만 정리해 일반 재시작 후보로 만든다(정리 명령의 재시작은 거절시킨다).
    doAnswer(call -> {
          throw new org.springframework.batch.core.launch.JobRestartException("hold");
        })
        .when(jobService)
        .restart(anyLong());
    assertThat(recoverRestart(admin, crashed.getId()).receipt().resultCode())
        .isEqualTo("BATCH_RECOVERED_RESTART_REJECTED");
    reset(jobService);

    var barrier = new CyclicBarrier(2);
    var outcomes = new ArrayList<CompletableFuture<String>>();
    for (long by : List.of(admin, otherAdmin)) {
      outcomes.add(
          CompletableFuture.supplyAsync(() -> {
            try {
              barrier.await(30, TimeUnit.SECONDS);
              return service
                  .restartTopupRecovery(by, crashed.getId(), "동시 재시작", UUID.randomUUID())
                  .receipt()
                  .resultCode();
            } catch (BusinessException rejected) {
              return rejected.code();
            } catch (Exception e) {
              throw new IllegalStateException(e);
            }
          }));
    }
    var codes = new ArrayList<String>();
    for (var outcome : outcomes) codes.add(outcome.get(120, TimeUnit.SECONDS));

    assertThat(codes).containsOnlyOnce("BATCH_RESTART_ACCEPTED");
    assertThat(jobs.getJobExecutions(crashed.getJobInstance())).hasSize(2);
    System.out.println("CONCURRENT_RESTART_CODES " + codes);
  }

  @Test
  void auditLeaseExpiringDuringLongRestartDoesNotLetAnotherRequestRunAgain() throws Exception {
    var crashed = interruptedJob();
    var during = new ArrayList<Throwable>();
    var first = new AtomicBoolean(true);
    // 재시작한 새 실행이 도는 동안 감사 처리 시간(기본 10분)이 지나고, 다른 관리자가 같은 실행에 두 명령을 다시 보낸다.
    doAnswer(call -> {
          call.callRealMethod();
          JobExecution started = call.getArgument(0);
          if (started.getId() != crashed.getId() && first.getAndSet(false)) {
            now.set(now.get().plus(Duration.ofMinutes(11)));
            for (Runnable competing :
                List.<Runnable>of(
                    () -> recoverRestart(otherAdmin, crashed.getId()),
                    () ->
                        service.restartTopupRecovery(
                            otherAdmin, crashed.getId(), "재시작", UUID.randomUUID()))) {
              try {
                CompletableFuture.runAsync(competing).get(60, TimeUnit.SECONDS);
              } catch (Exception rejected) {
                during.add(rejected.getCause());
              }
            }
          }
          return null;
        })
        .when(localExecutions)
        .beforeJob(any());

    var result = recoverRestart(admin, crashed.getId());

    assertThat(during).hasSize(2);
    assertThat(during)
        .allSatisfy(e ->
            assertThat(e)
                .isInstanceOfSatisfying(
                    BusinessException.class,
                    b -> assertThat(b.code()).isEqualTo("ADMIN_ACTION_NOT_ELIGIBLE")));
    // 처리 시간이 지난 요청을 다른 요청이 닫으려 했지만 그 요청이 거절되며 함께 롤백됐다. 원래 요청이 결과를 기록한다.
    assertThat(result.receipt().status()).isEqualTo(AdminActionStatus.SUCCEEDED);
    assertThat(result.receipt().resultCode()).isEqualTo("BATCH_RECOVER_RESTART_ACCEPTED");
    assertThat(jobs.getJobExecutions(crashed.getJobInstance())).hasSize(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM admin_action_logs WHERE target_id=?",
                Long.class,
                crashed.getId()))
        .isEqualTo(1);
  }

  private AdminReprocessingService.Result recoverRestart(long by, long execution) {
    return service.recoverAndRestartTopupRecovery(by, execution, "작업자 종료 확인", UUID.randomUUID());
  }

  private void assertUntouched(JobExecution crashed) {
    var after = jobs.getJobExecution(crashed.getId());
    assertThat(after.getStatus()).isEqualTo(BatchStatus.STARTED);
    assertThat(after.getStepExecutions())
        .isNotEmpty()
        .allSatisfy(step -> assertThat(step.getStatus()).isEqualTo(BatchStatus.STARTED));
    assertThat(after.getExecutionContext().containsKey("batch.recovered")).isFalse();
    assertThat(jobs.getJobExecutions(crashed.getJobInstance())).hasSize(1);
  }

  /** 실행·단계 STARTED, 마지막 갱신이 1시간 전인 실행. 시각은 Batch와 같이 Timestamp(순간)로 옮긴다. */
  private JobExecution interruptedJob() {
    var params =
        new JobParametersBuilder()
            .addString("run.id", UUID.randomUUID().toString())
            .addLong("requestedAtMillis", now.get().toEpochMilli())
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
    var at = Timestamp.from(Instant.now().minus(Duration.ofHours(1)));
    for (String table : List.of("BATCH_JOB_EXECUTION", "BATCH_STEP_EXECUTION")) {
      jdbc.update(
          "UPDATE " + table + " SET CREATE_TIME=?,START_TIME=?,LAST_UPDATED=? WHERE JOB_EXECUTION_ID=?",
          at,
          at,
          at,
          execution.getId());
    }
    return jobs.getJobExecution(execution.getId());
  }

  private long admin() {
    long id = MEMBER_IDS.incrementAndGet();
    members.add(id);
    var at = LocalDateTime.now(ZoneOffset.UTC).minusHours(1);
    jdbc.update(
        "INSERT INTO members(id,email,password_hash,display_name,role,created_at,updated_at)"
            + " VALUES (?,?,?,'경쟁 관리자','ADMIN',?,?)",
        id,
        "race-admin-" + id + "@example.test",
        "fixture-hash",
        at,
        at);
    return id;
  }
}
