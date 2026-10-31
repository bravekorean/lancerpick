package com.example.lancerpick.common.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TimeConfig {
    /** 서비스에서 주입받아 사용한다. 테스트에서는 고정 Clock으로 교체할 수 있다. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
