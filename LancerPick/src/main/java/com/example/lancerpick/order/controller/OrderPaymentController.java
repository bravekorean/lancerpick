package com.example.lancerpick.order.controller;

import com.example.lancerpick.common.exception.BusinessException;
import com.example.lancerpick.common.security.CurrentMemberId;
import com.example.lancerpick.order.dto.OrderDetailResponse;
import com.example.lancerpick.order.service.OrderPaymentService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@Profile("!demo")
@RequestMapping("/api/orders")
public class OrderPaymentController {
    private final OrderPaymentService payments;
    private final CurrentMemberId members;
    public OrderPaymentController(OrderPaymentService payments, CurrentMemberId members) { this.payments=payments; this.members=members; }
    @PostMapping("/{id}/payment")
    public ResponseEntity<OrderDetailResponse> pay(Authentication authentication, @PathVariable String id, @RequestBody JsonNode body) {
        long memberId=members.require(authentication);
        if (body==null || !body.isObject() || !body.has("agreedToTerms") || !body.get("agreedToTerms").isBoolean() || !body.get("agreedToTerms").asBoolean()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,"VALIDATION_FAILED","거래 조건에 동의해 주세요.");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(payments.pay(memberId,id,true));
    }
}
