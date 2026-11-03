package com.example.lancerpick.order;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.example.lancerpick.common.security.AuthenticatedMember;
import com.example.lancerpick.order.dto.CreateOrderRequest;
import com.example.lancerpick.order.repository.OrderHistoryRepository;
import com.example.lancerpick.order.service.*;
import com.example.lancerpick.support.IntegrationTest;
import java.sql.Statement;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest @AutoConfigureMockMvc
@Import(OrderPaymentIntegrationTests.FixedClock.class)
class OrderPaymentIntegrationTests {
    static final LocalDateTime AT=LocalDateTime.parse("2026-11-03T01:00:00.123456");
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired OrderCreationService creation;
    @Autowired OrderDecisionService decisions;
    @Autowired OrderPaymentService payments;
    @MockitoSpyBean OrderHistoryRepository histories;
    long buyer,seller,third,category,product,order;
    List<Long> ids=new ArrayList<>();
    record Principal(Long memberId) implements AuthenticatedMember {}
    @BeforeEach void setup() {
        buyer=member(); seller=member(); third=member();
        category=insert("INSERT INTO categories(name,display_order) VALUES (?,0)","pay-"+UUID.randomUUID());
        product=insert("INSERT INTO products(seller_id,category_id,title,description,scope_description,preparation_description,price,duration_days,created_at,updated_at) VALUES (?,?,'title','desc','scope','prep',50000,3,?,?)",seller,category,AT,AT);
        jdbc.update("INSERT INTO wallets(member_id,balance,updated_at) VALUES (?,70000,?)",buyer,AT);
        order=newOrder();
    }
    long newOrder() {
        long id=creation.create(buyer,new CreateOrderRequest(""+product,0L,"requirements",UUID.randomUUID().toString())).orderId();
        ids.add(id); decisions.accept(seller,""+id); return id;
    }
    @AfterEach void cleanup() {
        reset(histories);
        jdbc.update("DELETE FROM wallet_entries WHERE member_id=?",buyer);
        for(long id:ids) {
            jdbc.update("DELETE FROM payments WHERE order_id=?",id);
            jdbc.update("DELETE FROM order_histories WHERE order_id=?",id);
            jdbc.update("DELETE FROM order_snapshots WHERE order_id=?",id);
            jdbc.update("DELETE FROM orders WHERE id=?",id);
        }
        jdbc.update("DELETE FROM wallets WHERE member_id=?",buyer);
        jdbc.update("DELETE FROM products WHERE id=?",product);
        jdbc.update("DELETE FROM categories WHERE id=?",category);
        jdbc.update("DELETE FROM members WHERE id IN (?,?,?)",buyer,seller,third);
    }
    @Test void paymentAndReplayUseStoredAmountAndUtc() throws Exception {
        request(order,buyer,"{\"agreedToTerms\":true,\"amount\":1}").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("IN_PROGRESS")).andExpect(header().string("Cache-Control","no-store"));
        payments.pay(buyer,""+order,true);
        assertThat(balance()).isEqualTo(20000);
        assertThat(count("payments")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT amount FROM payments WHERE order_id=?",Long.class,order)).isEqualTo(50000);
        assertThat(jdbc.queryForObject("SELECT DATE_FORMAT(paid_at,'%Y-%m-%d %H:%i:%s.%f') FROM payments WHERE order_id=?",String.class,order)).isEqualTo("2026-11-03 01:00:00.123456");
        assertThat(jdbc.queryForObject("SELECT DATE_FORMAT(due_at,'%Y-%m-%d %H:%i:%s.%f') FROM orders WHERE id=?",String.class,order)).isEqualTo("2026-11-06 01:00:00.123456");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wallet_entries WHERE member_id=?",Long.class,buyer)).isEqualTo(1);
    }
    @Test void sameOrderConcurrentPaymentIsChargedOnce() throws Exception {
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(4)) {
            var jobs=new ArrayList<Future<?>>();
            for(int i=0;i<4;i++) jobs.add(pool.submit(()->{start.await();return payments.pay(buyer,""+order,true);}));
            start.countDown(); for(var job:jobs)job.get(30,TimeUnit.SECONDS);
        }
        assertThat(balance()).isEqualTo(20000); assertThat(count("payments")).isEqualTo(1);
    }
    @Test void differentOrdersCannotOverspendSharedWallet() throws Exception {
        long other=newOrder(); var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var jobs=new ArrayList<Future<String>>();
            for(long id:List.of(order,other)) jobs.add(pool.submit(()->{start.await();try{payments.pay(buyer,""+id,true);return "OK";}catch(com.example.lancerpick.common.exception.BusinessException e){return e.code();}}));
            start.countDown(); var results=new ArrayList<String>();for(var job:jobs)results.add(job.get(30,TimeUnit.SECONDS));
            assertThat(results).containsExactlyInAnyOrder("OK","INSUFFICIENT_BALANCE");
        }
        assertThat(balance()).isEqualTo(20000); assertThat(count("payments")).isEqualTo(1);
    }
    @Test void historyDatabaseFailureRollsBackAllWrites() {
        doAnswer(invocation->{jdbc.update("INSERT INTO order_histories(order_id,actor_id,action,from_status,to_status,created_at) VALUES (?,-1,'PAID','AWAITING_PAYMENT','IN_PROGRESS',?)",order,AT);return null;}).when(histories).saveAndFlush(any());
        assertThatThrownBy(()->payments.pay(buyer,""+order,true)).isInstanceOf(RuntimeException.class);
        assertThat(balance()).isEqualTo(70000); assertThat(count("payments")).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id=?",String.class,order)).isEqualTo("AWAITING_PAYMENT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wallet_entries WHERE member_id=?",Long.class,buyer)).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"{}","{\"agreedToTerms\":false}","{\"agreedToTerms\":\"true\"}","{\"agreedToTerms\":1}"})
    void requiresActualBooleanConsent(String body) throws Exception {request(order,buyer,body).andExpect(status().isBadRequest());assertThat(balance()).isEqualTo(70000);}
    @Test void permissionsAndCsrfAreEnforced() throws Exception {
        request(order,seller,"{\"agreedToTerms\":true}").andExpect(status().isForbidden());
        request(order,third,"{\"agreedToTerms\":true}").andExpect(status().isNotFound());
        mvc.perform(post("/api/orders/"+order+"/payment").contentType(MediaType.APPLICATION_JSON).content("{\"agreedToTerms\":true}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/orders/"+order+"/payment").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"agreedToTerms\":true}")).andExpect(status().isUnauthorized());
        assertThat(count("payments")).isZero();
    }
    @Test void insufficientBalanceLeavesAllRecordsUnchanged() throws Exception {
        jdbc.update("UPDATE wallets SET balance=49999 WHERE member_id=?",buyer);
        request(order,buyer,"{\"agreedToTerms\":true}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"));
        assertThat(balance()).isEqualTo(49999); assertThat(count("payments")).isZero();
        assertThat(jdbc.queryForObject("SELECT paid_at FROM orders WHERE id=?",Object.class,order)).isNull();
    }
    @Test void refundedPaymentCannotBePaidAgain() throws Exception {
        payments.pay(buyer,""+order,true);
        jdbc.update("UPDATE payments SET status='REFUNDED',refunded_at=paid_at WHERE order_id=?",order);
        jdbc.update("UPDATE orders SET status='CANCELLED',cancelled_at=paid_at WHERE id=?",order);
        request(order,buyer,"{\"agreedToTerms\":true}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PAYMENT_ALREADY_REFUNDED"));
        assertThat(balance()).isEqualTo(20000); assertThat(count("payments")).isEqualTo(1);
    }
    @Test void completedOrderReplayDoesNotReopenOrCharge() throws Exception {
        payments.pay(buyer,""+order,true);
        jdbc.update("UPDATE orders SET status='COMPLETED',completed_at=paid_at WHERE id=?",order);
        request(order,buyer,"{\"agreedToTerms\":true}").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
        assertThat(balance()).isEqualTo(20000); assertThat(count("payments")).isEqualTo(1);
    }
    @Test void contradictoryPaymentAmountIsIntegrityFailure() throws Exception {
        payments.pay(buyer,""+order,true);
        jdbc.update("UPDATE payments SET amount=1 WHERE order_id=?",order);
        request(order,buyer,"{\"agreedToTerms\":true}").andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("DATA_INTEGRITY_ERROR"));
        assertThat(balance()).isEqualTo(20000);
    }
    long balance(){return jdbc.queryForObject("SELECT balance FROM wallets WHERE member_id=?",Long.class,buyer);}
    long count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE order_id IN (SELECT id FROM orders WHERE buyer_id=?)",Long.class,buyer);}
    org.springframework.test.web.servlet.ResultActions request(long id,long member,String body)throws Exception {
        return mvc.perform(post("/api/orders/"+id+"/payment").with(csrf()).with(authentication(UsernamePasswordAuthenticationToken.authenticated(new Principal(member),null,List.of()))).contentType(MediaType.APPLICATION_JSON).content(body));
    }
    long member(){return insert("INSERT INTO members(email,password_hash,display_name,created_at,updated_at) VALUES (?,'fixture','member',?,?)",UUID.randomUUID()+"@payment.test",AT,AT);}
    long insert(String sql,Object...args){var key=new GeneratedKeyHolder();jdbc.update(c->{var s=c.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS);for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);return s;},key);return key.getKey().longValue();}
    @TestConfiguration static class FixedClock {@Bean @Primary Clock paymentClock(){return Clock.fixed(AT.toInstant(ZoneOffset.UTC),ZoneOffset.UTC);}}
}
