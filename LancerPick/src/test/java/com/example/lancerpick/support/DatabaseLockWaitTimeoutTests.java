package com.example.lancerpick.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** 애플리케이션 커넥션은 MySQL 기본 50초 대신 짧은 행 잠금 대기 한도(기본 5초)를 쓴다. */
@IntegrationTest
class DatabaseLockWaitTimeoutTests {
  @Autowired JdbcTemplate jdbc;

  @Test
  void pooledConnectionsUseShortRowLockWait() {
    assertThat(jdbc.queryForObject("SELECT @@SESSION.innodb_lock_wait_timeout", Integer.class))
        .isEqualTo(5);
  }
}
