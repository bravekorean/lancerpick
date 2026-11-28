package com.example.lancerpick.wallet.service;

import com.example.lancerpick.wallet.batch.TopupRecoveryInterruption;
import com.example.lancerpick.wallet.batch.TopupRecoveryJobParameters;
import com.example.lancerpick.wallet.batch.TopupRecoveryLocalExecutions;
import com.example.lancerpick.wallet.config.TopupRecoveryBatchConfiguration;
import com.example.lancerpick.wallet.config.TopupRecoveryProperties;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.launch.JobRestartException;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 내부 실행 계약. 관리자 API가 추가되면 호출자의 권한·사유·운영 이력은 관리자 영역에서 검사한다. */
@Service
@Profile("!demo")
@ConditionalOnProperty(name = "lancerpick.wallet.topup-recovery.enabled", havingValue = "true")
@Transactional(propagation = Propagation.NEVER)
public class TopupRecoveryJobService {
  private final JobOperator operator;
  private final JobRepository repository;
  private final Job job;
  private final Clock clock;
  private final TopupRecoveryLocalExecutions local;
  private final TopupRecoveryProperties properties;

  public TopupRecoveryJobService(
      JobOperator operator,
      JobRepository repository,
      @Qualifier("walletTopupRecoveryJob") Job job,
      Clock clock,
      TopupRecoveryLocalExecutions local,
      TopupRecoveryProperties properties) {
    this.operator = operator;
    this.repository = repository;
    this.job = job;
    this.clock = clock;
    this.local = local;
    this.properties = properties;
  }

  public JobExecution start() throws Exception {
    return operator.start(
        job,
        new JobParametersBuilder()
            .addString(TopupRecoveryJobParameters.RUN_ID, UUID.randomUUID().toString())
            .addLong(TopupRecoveryJobParameters.REQUESTED_AT_MILLIS, clock.millis())
            .toJobParameters());
  }

  public JobExecution restart(long executionId) throws Exception {
    return operator.restart(load(executionId));
  }

  /**
   * 프로세스 종료로 실행 중 상태에 남은 실행을 Spring Batch {@code JobOperator.recover}로 FAILED 정리한다. 재시작은 하지 않는다.
   *
   * <p>호출 직전에 메타데이터를 다시 읽어 {@link TopupRecoveryInterruption} 규칙을 재검사한다. 조건이 맞지 않으면 아무것도 바꾸지 않고
   * {@link JobRestartException}을 던진다. 재검사와 recover 사이에 살아 있던 실행이 메타데이터를 갱신하면 Batch의 버전 검사가 recover 저장을
   * 실패시킨다(덮어쓰지 않는다).
   */
  public JobExecution recoverInterrupted(long executionId) throws Exception {
    var execution = load(executionId);
    var verdict =
        TopupRecoveryInterruption.assess(
            execution,
            repository.getLastJobExecution(execution.getJobInstance()),
            local.isRunningHere(executionId),
            LocalDateTime.now(),
            properties.interruptedAfter());
    if (verdict != TopupRecoveryInterruption.Verdict.INTERRUPTED) {
      throw new JobRestartException("Execution is not interrupted: " + verdict);
    }
    var recovered = operator.recover(execution);
    if (recovered.getStatus() != BatchStatus.FAILED) {
      throw new IllegalStateException("RECOVERY_NOT_APPLIED");
    }
    return recovered;
  }

  private JobExecution load(long executionId) {
    JobExecution execution;
    try {
      execution = repository.getJobExecution(executionId);
    } catch (EmptyResultDataAccessException missing) {
      execution = null;
    }
    if (execution == null
        || !TopupRecoveryBatchConfiguration.JOB_NAME.equals(
            execution.getJobInstance().getJobName())) {
      throw new IllegalArgumentException("Unknown top-up recovery execution");
    }
    return execution;
  }
}
