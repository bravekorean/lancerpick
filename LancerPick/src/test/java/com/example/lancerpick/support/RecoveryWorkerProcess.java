package com.example.lancerpick.support;

import com.example.lancerpick.LancerPickApplication;
import com.example.lancerpick.wallet.gateway.TossPaymentGateway;
import com.example.lancerpick.wallet.service.TopupRecoveryJobService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 통합 테스트가 별도 JVM으로 띄우는 충전 복구 작업자. 같은 테스트 DB에서 복구 Job을 실행하고, 첫 충전은 바로 확인해 지급하며 둘째 충전의 결제사
 * 조회에서 멈춘다(표준 출력에 {@code BLOCKED}). 부모 테스트는 이 상태에서 프로세스를 강제 종료(SIGKILL)하거나, 살아 있는 원격 실행으로 두고
 * 신호 파일을 만들어 조회를 끝내게 한다. 운영 코드가 아니며 test 프로필·테스트 DB 환경변수만 쓴다.
 *
 * <p>인자: firstKey firstOrderId secondKey secondOrderId amount signalFile
 */
public final class RecoveryWorkerProcess {
  private RecoveryWorkerProcess() {}

  public static void main(String[] args) throws Exception {
    System.setProperty("worker.args", String.join("\u0000", args));
    // 시스템 속성은 application-test.properties보다 우선한다(builder 기본 속성은 가장 낮아 덮어쓰인다).
    System.setProperty("server.port", "0");
    System.setProperty("lancerpick.wallet.topup-recovery.enabled", "true");
    System.setProperty("lancerpick.wallet.topup-recovery.scheduling-enabled", "false");
    var context =
        new SpringApplicationBuilder(LancerPickApplication.class, BlockingGateway.class)
            .profiles("test")
            .run();
    System.out.println("READY");
    System.out.flush();
    var execution = context.getBean(TopupRecoveryJobService.class).start();
    System.out.println("FINISHED " + execution.getId() + " " + execution.getStatus());
    System.out.flush();
    context.close();
    System.exit(0);
  }

  // @Configuration을 붙이지 않는다. 테스트 클래스패스도 컴포넌트 스캔 범위라 다른 테스트 컨텍스트에 이 대역이 끼어들면 안 된다.
  // 작업자 프로세스에서만 SpringApplicationBuilder 소스로 직접 등록한다.
  static class BlockingGateway {
    @Bean
    @Primary
    TossPaymentGateway blockingTossPaymentGateway() {
      String[] a = System.getProperty("worker.args").split("\u0000");
      long amount = Long.parseLong(a[4]);
      Path signal = Path.of(a[5]);
      return new TossPaymentGateway() {
        @Override
        public void requireAvailable() {}

        @Override
        public Payment confirm(String key, String orderId, long total, String requestKey) {
          throw new IllegalStateException("not used");
        }

        @Override
        public Payment lookup(String key) {
          if (key.equals(a[0])) return done(key, a[1]);
          if (!key.equals(a[2])) throw new IllegalStateException("UNEXPECTED_TOPUP");
          System.out.println("BLOCKED " + key);
          System.out.flush();
          try {
            while (!Files.exists(signal)) Thread.sleep(50);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("INTERRUPTED");
          }
          System.out.println("RESUMED " + key);
          System.out.flush();
          return done(key, a[3]);
        }

        private Payment done(String key, String orderId) {
          return new Payment(
              key, orderId, amount, amount, "KRW", "DONE", OffsetDateTime.now(ZoneOffset.UTC));
        }
      };
    }
  }
}
