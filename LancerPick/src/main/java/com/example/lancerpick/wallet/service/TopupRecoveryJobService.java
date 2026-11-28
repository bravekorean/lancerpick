package com.example.lancerpick.wallet.service;

import com.example.lancerpick.wallet.batch.TopupRecoveryInterruption;
import com.example.lancerpick.wallet.batch.TopupRecoveryJobParameters;
import com.example.lancerpick.wallet.batch.TopupRecoveryLocalExecutions;
import com.example.lancerpick.wallet.config.TopupRecoveryBatchConfiguration;
import com.example.lancerpick.wallet.config.TopupRecoveryProperties;
import java.time.Clock;
import java.time.Instant;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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
  private final EntityManager entities;
  private final TransactionTemplate fenceTransaction;
  private final TransactionTemplate recoverTransaction;

  public TopupRecoveryJobService(
      JobOperator operator,
      JobRepository repository,
      @Qualifier("walletTopupRecoveryJob") Job job,
      Clock clock,
      TopupRecoveryLocalExecutions local,
      TopupRecoveryProperties properties,
      EntityManager entities,
      PlatformTransactionManager transactionManager) {
    this.operator = operator;
    this.repository = repository;
    this.job = job;
    this.clock = clock;
    this.local = local;
    this.properties = properties;
    this.entities = entities;
    // 펜스(조건부 버전 증가)만 담는 짧은 트랜잭션. Batch 호출은 이 안에서 하지 않는다.
    fenceTransaction = new TransactionTemplate(transactionManager);
    fenceTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    recoverTransaction = new TransactionTemplate(transactionManager);
    recoverTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
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

  /** 정리 판단에 쓴 시점의 Job 실행 버전과 Step 실행별 버전·상태. 이후 한 번이라도 갱신되면 값이 달라진다. */
  public record Snapshot(long jobVersion, Map<Long, StepState> steps) {
    public Snapshot {
      steps = Map.copyOf(steps);
    }
  }

  /** Step 실행의 버전과 Batch 상태 이름. */
  public record StepState(long version, String status) {
    boolean running() {
      return RUNNING.contains(status);
    }
  }

  /** 관리자 claim 재검사 때 함께 읽는다. 없는 실행이면 IllegalArgumentException. */
  public Snapshot snapshot(long executionId) {
    var execution = load(executionId);
    return snapshotOf(execution);
  }

  /**
   * 프로세스 종료로 실행 중 상태에 남은 실행을 Spring Batch {@code JobOperator.recover}로 FAILED 정리한다. 재시작은 하지 않는다.
   *
   * <p>순서: (1) 메타데이터를 다시 읽어 버전이 관리자 재검사 때의 {@code expected}와 같고 {@link TopupRecoveryInterruption} 규칙상 중단인지
   * 확인한다. (2) 짧은 트랜잭션에서 Job 실행 행과 실행 중 상태인 Step 실행 행의 VERSION을 "기대 버전이고 아직 실행 중 상태일 때만" 1
   * 올린다(펜스). 이미 끝난 Step(COMPLETED 등)은 recover가 바꾸지 않으므로 버전을 올리지 않고, 버전·상태가 그대로인지만 확인한다. 한 행이라도
   * 맞지 않으면 롤백하고 거절한다. (3) 펜스가 커밋된 뒤에만 recover한다. 중단 판단은 Step 상태를 보지 않으므로, Step이 모두 끝난 직후 종료돼
   * Job만 STARTED로 남은 실행도 판단과 펜스가 같은 결론을 낸다.
   *
   * <p>펜스가 필요한 이유: Spring Batch의 {@code JobRepository.update(JobExecution)}·recover는 저장 전에 DB의 최신 버전을 읽어 맞추므로
   * 버전 검사만으로는 그 사이의 갱신을 막지 못한다(Codex 재현). 반면 Step 실행 저장은 메모리의 버전을 조건으로 쓰므로, 펜스 뒤에 살아 있던 작업자가
   * Step을 저장하면 낙관적 잠금으로 실패한다. 펜스 전에 작업자가 먼저 저장했다면 버전이 달라져 펜스가 실패하고 아무것도 바꾸지 않는다.
   */
  public JobExecution recoverInterrupted(long executionId, Snapshot expected) throws Exception {
    var execution = load(executionId);
    if (!snapshotOf(execution).equals(expected)) {
      throw new JobRestartException("Execution is not interrupted: EXECUTION_CHANGED");
    }
    var verdict =
        TopupRecoveryInterruption.assess(
            execution,
            repository.getLastJobExecution(execution.getJobInstance()),
            local.isRunningHere(executionId),
            Instant.now(),
            properties.interruptedAfter());
    if (verdict != TopupRecoveryInterruption.Verdict.INTERRUPTED) {
      throw new JobRestartException("Execution is not interrupted: " + verdict);
    }
    if (!Boolean.TRUE.equals(fenceTransaction.execute(status -> fence(executionId, expected, status)))) {
      throw new JobRestartException("Execution is not interrupted: EXECUTION_CHANGED");
    }
    // Step·Job 저장을 한 트랜잭션으로 묶는다. Job 저장이 실패하면 Step의 FAILED 변경도 롤백된다.
    var recovered = recoverTransaction.execute(status -> operator.recover(load(executionId)));
    if (recovered == null || recovered.getStatus() != BatchStatus.FAILED) {
      throw new IllegalStateException("RECOVERY_NOT_APPLIED");
    }
    return recovered;
  }

  private static final List<String> RUNNING = List.of("STARTING", "STARTED", "STOPPING");

  private boolean fence(long executionId, Snapshot expected, TransactionStatus status) {
    long steps =
        entities
            .createQuery(
                "SELECT COUNT(s) FROM BatchStepExecutionFence s WHERE s.jobExecutionId = :job",
                Long.class)
            .setParameter("job", executionId)
            .getSingleResult();
    boolean fenced = steps == expected.steps().size();
    for (var step : expected.steps().entrySet()) {
      var state = step.getValue();
      if (state.running()) {
        fenced &=
            entities
                    .createQuery(
                        "UPDATE BatchStepExecutionFence s SET s.version = s.version + 1 WHERE s.id = :id"
                            + " AND s.jobExecutionId = :job AND s.version = :version AND s.status IN :running")
                    .setParameter("id", step.getKey())
                    .setParameter("job", executionId)
                    .setParameter("version", state.version())
                    .setParameter("running", RUNNING)
                    .executeUpdate()
                == 1;
      } else {
        fenced &=
            entities
                    .createQuery(
                        "SELECT COUNT(s) FROM BatchStepExecutionFence s WHERE s.id = :id"
                            + " AND s.jobExecutionId = :job AND s.version = :version AND s.status = :status",
                        Long.class)
                    .setParameter("id", step.getKey())
                    .setParameter("job", executionId)
                    .setParameter("version", state.version())
                    .setParameter("status", state.status())
                    .getSingleResult()
                == 1;
      }
    }
    fenced &=
        entities
                .createQuery(
                    "UPDATE BatchJobExecutionFence j SET j.version = j.version + 1 WHERE j.id = :id"
                        + " AND j.version = :version AND j.status IN :running")
                .setParameter("id", executionId)
                .setParameter("version", expected.jobVersion())
                .setParameter("running", RUNNING)
                .executeUpdate()
            == 1;
    if (!fenced) status.setRollbackOnly();
    return fenced;
  }

  private static Snapshot snapshotOf(JobExecution execution) {
    var steps = new TreeMap<Long, StepState>();
    for (var step : execution.getStepExecutions()) {
      steps.put(step.getId(), new StepState(versionOf(step.getVersion()), step.getStatus().name()));
    }
    return new Snapshot(versionOf(execution.getVersion()), steps);
  }

  private static long versionOf(Integer version) {
    return version == null ? -1 : version;
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
