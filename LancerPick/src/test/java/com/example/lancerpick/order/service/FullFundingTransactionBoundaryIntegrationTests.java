package com.example.lancerpick.order.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.lancerpick.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;

@IntegrationTest
class FullFundingTransactionBoundaryIntegrationTests {
  @Autowired FullFundingService funding;

  @Test
  void lockedEntryCannotStartItsOwnTransactionWithoutCallerLocks() {
    // Must fail at the Spring transaction boundary, before inspecting the supplied order.
    assertThatThrownBy(() -> funding.payLocked(1L, null))
        .isInstanceOf(IllegalTransactionStateException.class);
  }
}
