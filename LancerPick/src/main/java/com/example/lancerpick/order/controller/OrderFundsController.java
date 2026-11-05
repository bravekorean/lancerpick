package com.example.lancerpick.order.controller;

import com.example.lancerpick.common.security.CurrentMemberId;
import com.example.lancerpick.order.dto.OrderFundsResponse;
import com.example.lancerpick.order.service.FullFundingService;
import jakarta.validation.constraints.Min;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("!demo")
@lombok.RequiredArgsConstructor
public class OrderFundsController {
  private final FullFundingService funds;
  private final CurrentMemberId members;

  @GetMapping("/api/orders/{id}/funds")
  public ResponseEntity<OrderFundsResponse> get(
      Authentication auth, @PathVariable @Min(1) long id) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(funds.summary(members.require(auth), id));
  }
}
