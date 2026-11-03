package com.example.lancerpick.order;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.example.lancerpick.order.service.OrderCancellationService;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

/** 결제 시나리오도 재실행해 취소 기능 추가에 따른 회귀를 함께 확인한다. */
class OrderCancellationIntegrationTests extends OrderPaymentIntegrationTests {
    @Autowired OrderCancellationService cancellations;
    @AfterEach void removeRequests(){for(long id:ids)jdbc.update("DELETE FROM cancellation_requests WHERE order_id=?",id);}
    @Test void sellerRefundAndRepeatedCancellationCreditExactlyOnce() throws Exception {
        payments.pay(buyer,""+order,true);
        cancel(seller).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        cancel(seller).andExpect(status().isOk());
        assertThat(balance()).isEqualTo(70000);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wallet_entries WHERE member_id=? AND type='REFUND'",Long.class,buyer)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE order_id=?",String.class,order)).isEqualTo("REFUNDED");
        assertThat(jdbc.queryForObject("SELECT DATE_FORMAT(refunded_at,'%Y-%m-%d %H:%i:%s.%f') FROM payments WHERE order_id=?",String.class,order)).isEqualTo("2026-11-03 01:00:00.123456");
    }
    @Test void unpaidBuyerCancellationDoesNotCreatePaymentOrLedger() throws Exception {
        cancel(buyer).andExpect(status().isOk());assertThat(balance()).isEqualTo(70000);assertThat(count("payments")).isZero();
    }
    @Test void paidBuyerCannotCancelDirectlyAndThirdPartyGets404() throws Exception {
        payments.pay(buyer,""+order,true);
        cancel(buyer).andExpect(status().isForbidden());cancel(third).andExpect(status().isNotFound());assertThat(balance()).isEqualTo(20000);
    }
    @Test void completedOrderCannotRefund() throws Exception {
        payments.pay(buyer,""+order,true);jdbc.update("UPDATE orders SET status='COMPLETED',completed_at=paid_at WHERE id=?",order);
        cancel(seller).andExpect(status().isConflict());assertThat(balance()).isEqualTo(20000);
    }
    @Test void refundRollbackIncludesPaymentWalletAndOrder() {
        payments.pay(buyer,""+order,true);
        doAnswer(invocation->{jdbc.update("INSERT INTO order_histories(order_id,actor_id,action,from_status,to_status,created_at) VALUES (?,-1,'CANCELLED','IN_PROGRESS','CANCELLED',?)",order,AT);return null;}).when(histories).saveAndFlush(any());
        assertThatThrownBy(()->cancellations.cancel(seller,""+order,"reason")).isInstanceOf(RuntimeException.class);
        assertThat(balance()).isEqualTo(20000);
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE order_id=?",String.class,order)).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id=?",String.class,order)).isEqualTo("IN_PROGRESS");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wallet_entries WHERE member_id=? AND type='REFUND'",Long.class,buyer)).isZero();
    }
    @Test void simultaneousRefundsAreIdempotent() throws Exception {
        payments.pay(buyer,""+order,true);var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(3)){
            var jobs=new ArrayList<Future<?>>();for(int i=0;i<3;i++)jobs.add(pool.submit(()->{start.await();return cancellations.cancel(seller,""+order,"reason");}));
            start.countDown();for(var job:jobs)job.get(30,TimeUnit.SECONDS);
        }
        assertThat(balance()).isEqualTo(70000);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wallet_entries WHERE member_id=? AND type='REFUND'",Long.class,buyer)).isEqualTo(1);
    }
    @Test void pendingRequestIsApprovedTogetherWithRefund() throws Exception {
        payments.pay(buyer,""+order,true);
        jdbc.update("INSERT INTO cancellation_requests(order_id,requested_by,request_key,reason,requested_at) VALUES (?,?,?,'reason',?)",order,buyer,UUID.randomUUID().toString(),AT);
        cancel(seller).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT status FROM cancellation_requests WHERE order_id=?",String.class,order)).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_histories WHERE order_id=? AND action='CANCELLATION_APPROVED'",Long.class,order)).isEqualTo(1);
    }
    @Test void paymentAndCancellationRaceNeverLeavesMoneyChargedOnCancelledOrder() throws Exception {
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var payment=pool.submit(()->{start.await();try{payments.pay(buyer,""+order,true);}catch(com.example.lancerpick.common.exception.BusinessException e){assertThat(e.code()).isEqualTo("ORDER_STATE_CONFLICT");}return true;});
            var cancellation=pool.submit(()->{start.await();return cancellations.cancel(seller,""+order,"reason");});
            start.countDown();payment.get(30,TimeUnit.SECONDS);cancellation.get(30,TimeUnit.SECONDS);
        }
        assertThat(balance()).isEqualTo(70000);
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id=?",String.class,order)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payments WHERE order_id=? AND status='PAID'",Long.class,order)).isZero();
    }
    org.springframework.test.web.servlet.ResultActions cancel(long actor)throws Exception {
        return mvc.perform(post("/api/orders/"+order+"/cancel").with(csrf()).with(authentication(UsernamePasswordAuthenticationToken.authenticated(new Principal(actor),null,List.of()))).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\" reason \"}"));
    }
}
