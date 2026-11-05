package com.example.lancerpick.contract;

import static org.junit.jupiter.api.Assertions.*;

import com.example.lancerpick.contract.entity.OrderContract;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class OrderContractTimeTests {
  @Test
  void invalidEventTimesCannotChangeStateOrReadyMetadata() {
    var at = LocalDateTime.of(2026, 11, 5, 0, 0);
    var c = OrderContract.draft(1, 1, "v1", "", "{}", 1, at);
    assertThrows(IllegalArgumentException.class, () -> c.startGeneration(null));
    assertThrows(IllegalArgumentException.class, () -> c.supersede(at.minusNanos(1000)));
    assertEquals("DRAFT", c.getStatus());
    c.startGeneration(at.plusSeconds(1));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            c.completeGeneration(
                c.getGenerationKey(), "private.pdf", "LOCAL", 10, OrderContract.hash("pdf"), at));
    assertNull(c.getFileStorageKey());
    c.completeGeneration(
        c.getGenerationKey(),
        "private.pdf",
        "LOCAL",
        10,
        OrderContract.hash("pdf"),
        at.plusSeconds(2));
    assertThrows(IllegalArgumentException.class, () -> c.signComplete(at));
    assertEquals("READY", c.getStatus());
    assertNull(c.getSignedAt());
    c.signComplete(at.plusSeconds(3));
    assertEquals("SIGNED", c.getStatus());
  }
}
