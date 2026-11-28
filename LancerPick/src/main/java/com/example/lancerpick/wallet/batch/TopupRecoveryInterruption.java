package com.example.lancerpick.wallet.batch;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;

/**
 * 프로세스 종료로 실행 중 상태(STARTING·STARTED·STOPPING)에 남은 충전 복구 실행인지 판단한다. 관리자 조회의 후보 판단과 실제 정리 직전 재검사가 이
 * 규칙 하나를 함께 쓴다.
 *
 * <p>마지막 갱신 시각은 Job·Step 실행의 LAST_UPDATED(없으면 시작·생성 시각) 중 가장 늦은 값이다. Tasklet은 충전 한 건마다 Step 실행을
 * 커밋하므로 살아 있는 실행은 이 값이 계속 바뀐다.
 *
 * <p>시간대: Spring Batch는 이 값을 작업자 JVM 기본 시간대의 {@code LocalDateTime}으로 만들고 {@code java.sql.Timestamp}로 저장·조회한다.
 * MySQL 드라이버(기본 preserveInstants)와 공통 {@code connectionTimeZone}이 이 순간(instant)을 보존하므로, 읽은 값을 이 JVM의 기본
 * 시간대로 해석하면 작업자와 판정 서버의 JVM 시간대가 달라도 같은 순간이 된다. 그래서 벽시계 값끼리 비교하지 않고 {@link Instant}로 바꿔
 * 시스템 시계의 현재 순간과 비교한다(JVM 시간대·UTC 가정에 의존하지 않음).
 */
public final class TopupRecoveryInterruption {
  /** 판단 결과. INTERRUPTED만 정리 대상이다. */
  public enum Verdict {
    INTERRUPTED,
    NOT_RUNNING_STATUS,
    NEWER_EXECUTION_EXISTS,
    RUNNING_IN_THIS_SERVER,
    RECENTLY_UPDATED
  }

  private static final Set<BatchStatus> RUNNING =
      Set.of(BatchStatus.STARTING, BatchStatus.STARTED, BatchStatus.STOPPING);

  private TopupRecoveryInterruption() {}

  /**
   * @param execution 판단할 실행(Step 실행 포함)
   * @param latest 같은 JobInstance의 최신 실행
   * @param runningHere 이 서버 프로세스에서 실행 중인지
   * @param now 현재 순간(시스템 시계)
   * @param quietPeriod 마지막 갱신 후 이 시간이 지나야 중단으로 본다
   */
  public static Verdict assess(
      JobExecution execution,
      JobExecution latest,
      boolean runningHere,
      Instant now,
      Duration quietPeriod) {
    if (!RUNNING.contains(execution.getStatus())) return Verdict.NOT_RUNNING_STATUS;
    if (latest == null || latest.getId() != execution.getId()) return Verdict.NEWER_EXECUTION_EXISTS;
    if (runningHere) return Verdict.RUNNING_IN_THIS_SERVER;
    var heartbeat = lastHeartbeat(execution);
    // 기록 시각이 없거나 현재보다 뒤(서버 시각 차이)면 판단할 수 없으므로 살아 있는 것으로 본다.
    if (heartbeat == null || heartbeat.isAfter(now) || heartbeat.plus(quietPeriod).isAfter(now))
      return Verdict.RECENTLY_UPDATED;
    return Verdict.INTERRUPTED;
  }

  static Instant lastHeartbeat(JobExecution execution) {
    var job =
        Stream.of(execution.getLastUpdated(), execution.getStartTime(), execution.getCreateTime());
    var steps =
        execution.getStepExecutions().stream()
            .flatMap(step -> Stream.of(step.getLastUpdated(), step.getStartTime()));
    var zone = ZoneId.systemDefault();
    return Stream.concat(job, steps)
        .filter(Objects::nonNull)
        .map(at -> at.atZone(zone).toInstant())
        .max(Instant::compareTo)
        .orElse(null);
  }
}
