package com.example.lancerpick.order.controller;

import com.example.lancerpick.common.security.CurrentMemberId;
import com.example.lancerpick.order.dto.OrderDetailResponse;
import com.example.lancerpick.order.dto.RejectOrderRequest;
import com.example.lancerpick.order.service.OrderCancellationService;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController @Profile("!demo") @RequestMapping("/api/orders")
public class OrderCancellationController {
    private final OrderCancellationService cancellations;
    private final CurrentMemberId members;
    public OrderCancellationController(OrderCancellationService cancellations,CurrentMemberId members){this.cancellations=cancellations;this.members=members;}
    @PostMapping("/{id}/cancel")
    public ResponseEntity<OrderDetailResponse> cancel(Authentication authentication,@PathVariable String id,@Valid @RequestBody RejectOrderRequest request){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(cancellations.cancel(members.require(authentication),id,request.reason()));
    }
}
