package com.example.lancerpick.order.dto;

import java.time.OffsetDateTime;

public record OrderFundsResponse(
    String status,
    long amount,
    long heldAmount,
    long refundedAmount,
    Long feeAmount,
    Long sellerPayout,
    OffsetDateTime paidAt,
    OffsetDateTime resolvedAt) {}
