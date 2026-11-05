package com.example.lancerpick.order.controller;

import com.example.lancerpick.common.security.CurrentMemberId;
import com.example.lancerpick.order.dto.OrderFundsResponse;
import com.example.lancerpick.order.service.FullFundingService;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!demo")
@RequiredArgsConstructor
@RequestMapping("/api/orders/{id}/funds")
public class OrderFundsController {
  private final FullFundingService fullFundingService;
  private final CurrentMemberId currentMemberId;

  @GetMapping
  public ResponseEntity<OrderFundsResponse> summary(
      Authentication authentication, @PathVariable("id") @Min(1) long orderId) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(fullFundingService.summary(currentMemberId.require(authentication), orderId));
  }
}
