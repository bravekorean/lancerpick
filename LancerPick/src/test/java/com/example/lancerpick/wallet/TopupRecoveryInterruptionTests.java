package com.example.lancerpick.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.lancerpick.wallet.batch.TopupRecoveryInterruption;
import com.example.lancerpick.wallet.batch.TopupRecoveryInterruption.Verdict;
import com.example.lancerpick.wallet.batch.TopupRecoveryLocalExecutions;
import com.example.lancerpick.wallet.config.TopupRecoveryProperties;
import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.step.StepExecution;

/** 중단 실행 판단 규칙. 관리자 후보 판단과 실제 정리 직전 재검사가 이 규칙을 같이 쓴다. */
class TopupRecoveryInterruptionTests {
  private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 28, 12, 0);
  private static final Duration QUIET = Duration.ofMinutes(30);
  private final JobInstance instance = new JobInstance(1, "walletTopupRecovery");

  @Test
  void startedExecutionSilentLongerThanQuietPeriodIsInterrupted() {
    var execution = execution(10, BatchStatus.STARTED, NOW.minusMinutes(31));
    assertThat(assess(execution, execution, false)).isEqualTo(Verdict.INTERRUPTED);
    var stopping = execution(11, BatchStatus.STOPPING, NOW.minusHours(2));
    assertThat(assess(stopping, stopping, false)).isEqualTo(Verdict.INTERRUPTED);
  }

  @Test
  void latestStepUpdateCountsAsHeartbeatEvenWhenJobRowIsOld() {
    var execution = execution(10, BatchStatus.STARTED, NOW.minusHours(3));
    var step = new StepExecution(20, "walletTopupRecoveryStep", execution);
    step.setStatus(BatchStatus.STARTED);
    execution.addStepExecution(step);
    step.setLastUpdated(NOW.minusMinutes(5));
    assertThat(assess(execution, execution, false)).isEqualTo(Verdict.RECENTLY_UPDATED);
    step.setLastUpdated(NOW.minusMinutes(30));
    // 정확히 기준 시간만큼 지난 시점부터 중단으로 본다.
    assertThat(assess(execution, execution, false)).isEqualTo(Verdict.INTERRUPTED);
  }

  @Test
  void finishedNewerLocalOrUndatedExecutionsAreNeverInterrupted() {
    for (var status : new BatchStatus[] {BatchStatus.FAILED, BatchStatus.STOPPED, BatchStatus.COMPLETED,
        BatchStatus.ABANDONED, BatchStatus.UNKNOWN}) {
      var finished = execution(10, status, NOW.minusDays(1));
      assertThat(assess(finished, finished, false)).isEqualTo(Verdict.NOT_RUNNING_STATUS);
    }
    var old = execution(10, BatchStatus.STARTED, NOW.minusDays(1));
    var newer = execution(12, BatchStatus.STARTED, NOW.minusDays(1));
    assertThat(assess(old, newer, false)).isEqualTo(Verdict.NEWER_EXECUTION_EXISTS);
    assertThat(assess(old, null, false)).isEqualTo(Verdict.NEWER_EXECUTION_EXISTS);
    assertThat(assess(old, old, true)).isEqualTo(Verdict.RUNNING_IN_THIS_SERVER);

    var undated = execution(13, BatchStatus.STARTED, null);
    undated.setCreateTime(null);
    assertThat(assess(undated, undated, false)).isEqualTo(Verdict.RECENTLY_UPDATED);
    // 서버 시각 차이로 기록이 현재보다 뒤면 판단하지 않는다.
    var future = execution(14, BatchStatus.STARTED, NOW.plusHours(1));
    assertThat(assess(future, future, false)).isEqualTo(Verdict.RECENTLY_UPDATED);
  }

  @Test
  void localRegistryTracksOnlyExecutionsBetweenListenerCallbacks() {
    var local = new TopupRecoveryLocalExecutions();
    var execution = execution(10, BatchStatus.STARTED, NOW);
    assertThat(local.isRunningHere(10)).isFalse();
    local.beforeJob(execution);
    assertThat(local.isRunningHere(10)).isTrue();
    local.afterJob(execution);
    assertThat(local.isRunningHere(10)).isFalse();
  }

  @Test
  void quietPeriodMustExceedTwoRecoveryLeases() {
    assertThat(new TopupRecoveryProperties(120, 180, 8, 60, 600).interruptedAfter())
        .isEqualTo(Duration.ofMinutes(10));
    assertThatThrownBy(() -> new TopupRecoveryProperties(120, 400, 8, 60, 600))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private Verdict assess(JobExecution execution, JobExecution latest, boolean runningHere) {
    return TopupRecoveryInterruption.assess(execution, latest, runningHere, NOW, QUIET);
  }

  private JobExecution execution(long id, BatchStatus status, LocalDateTime updated) {
    var execution = new JobExecution(id, instance, new JobParameters());
    execution.setStatus(status);
    execution.setCreateTime(updated == null ? null : updated.minusMinutes(1));
    execution.setStartTime(updated == null ? null : updated.minusMinutes(1));
    execution.setLastUpdated(updated);
    return execution;
  }
}
