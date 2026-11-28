package com.example.lancerpick.wallet.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("lancerpick.wallet.topup-recovery")
public record TopupRecoveryProperties(
    @DefaultValue("120") @Min(120) @Max(86400) long staleSeconds,
    @DefaultValue("180") @Min(120) @Max(3600) long leaseSeconds,
    @DefaultValue("8") @Min(1) @Max(100) int maxAttempts,
    @DefaultValue("60") @Min(60) @Max(3600) long retrySeconds,
    @DefaultValue("1800") @Min(600) @Max(86400) long interruptedAfterSeconds) {
  public TopupRecoveryProperties {
    // 한 건 처리(처리권 보유) 시간보다 충분히 길어야 살아 있는 실행을 중단으로 오판하지 않는다.
    if (interruptedAfterSeconds < Math.multiplyExact(leaseSeconds, 2)) {
      throw new IllegalArgumentException(
          "interrupted-after-seconds must be at least twice lease-seconds");
    }
  }

  /** 실행 중 상태의 Batch 실행이 이 시간 동안 갱신되지 않으면 프로세스 종료로 남은 실행 후보로 본다. */
  public Duration interruptedAfter() {
    return Duration.ofSeconds(interruptedAfterSeconds);
  }

  public Duration staleAge() {
    return Duration.ofSeconds(staleSeconds);
  }

  public Duration leaseDuration() {
    return Duration.ofSeconds(leaseSeconds);
  }

  public Duration retryDelay(int attempts) {
    long multiplier = 1L << Math.min(Math.max(attempts - 1, 0), 6);
    return Duration.ofSeconds(Math.min(3600, Math.multiplyExact(retrySeconds, multiplier)));
  }
}
