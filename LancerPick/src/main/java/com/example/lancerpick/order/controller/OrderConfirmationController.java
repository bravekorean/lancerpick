package com.example.lancerpick.order.controller;
import com.example.lancerpick.common.security.CurrentMemberId;
import com.example.lancerpick.order.dto.OrderDetailResponse;
import com.example.lancerpick.order.service.OrderConfirmationService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
@RestController @Profile("!demo") @RequestMapping("/api/orders")
public class OrderConfirmationController {
    private final OrderConfirmationService confirmations;
    private final CurrentMemberId members;
    public OrderConfirmationController(OrderConfirmationService confirmations,CurrentMemberId members){this.confirmations=confirmations;this.members=members;}
    @PostMapping("/{id}/confirm") public ResponseEntity<OrderDetailResponse> confirm(Authentication auth,@PathVariable String id){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(confirmations.confirm(members.require(auth),id));}
}
