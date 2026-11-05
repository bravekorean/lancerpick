package com.example.lancerpick.payment;

import static org.junit.jupiter.api.Assertions.*;

import com.example.lancerpick.payment.service.ContractPaymentPolicy;
import org.junit.jupiter.api.Test;

class ContractPaymentPolicyTests {
  @Test
  void oddTotalKeepsEveryWon() {
    var amounts = ContractPaymentPolicy.installments(100_001);
    assertEquals(50_000, amounts.deposit());
    assertEquals(50_001, amounts.balance());
  }

  @Test
  void noResponseUsesOnePercentOfCompensation() {
    var result = ContractPaymentPolicy.noResponse(100_000);
    assertEquals(50_000, result.paid());
    assertEquals(35_000, result.buyerRefund());
    assertEquals(15_000, result.sellerGross());
    assertEquals(150, result.fee());
    assertEquals(14_850, result.sellerNet());
  }

  @Test
  void allAllowedAmountsPreserveMoneyAndRounding() {
    for (long total = 10_000; total <= 1_000_000; total++) {
      var split = ContractPaymentPolicy.installments(total);
      assertEquals(total, split.deposit() + split.balance());
      for (var result : new ContractPaymentPolicy.Distribution[] {
          ContractPaymentPolicy.completed(total), ContractPaymentPolicy.noResponse(total),
          ContractPaymentPolicy.agreedCancellation(total)}) {
        assertEquals(result.paid(), result.buyerRefund() + result.sellerGross());
        assertEquals(result.sellerGross(), result.fee() + result.sellerNet());
      }
    }
  }

  @Test
  void cancellationReturnsAllDepositWithoutFee() {
    var result = ContractPaymentPolicy.agreedCancellation(10_001);
    assertEquals(5_000, result.buyerRefund());
    assertEquals(0, result.fee());
    assertEquals(0, result.sellerNet());
  }

  @Test
  void completionChargesTenPercent() {
    var result = ContractPaymentPolicy.completed(100_019);
    assertEquals(10_001, result.fee());
    assertEquals(90_018, result.sellerNet());
  }

  @Test
  void invalidTotalsCannotProducePaymentPlans() {
    for (long total : new long[] {Long.MIN_VALUE, -1, 0, 9_999, 1_000_001, Long.MAX_VALUE}) {
      assertThrows(IllegalArgumentException.class, () -> ContractPaymentPolicy.installments(total));
    }
  }
}
