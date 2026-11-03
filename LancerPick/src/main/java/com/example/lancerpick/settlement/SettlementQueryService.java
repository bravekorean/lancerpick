package com.example.lancerpick.settlement;

import com.example.lancerpick.common.web.PageResponse;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service @Profile("!demo") @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class SettlementQueryService {
    private final JdbcTemplate jdbc;
    public SettlementQueryService(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public record Settlement(String id,String orderId,long grossAmount,int feeRateBps,long feeAmount,long sellerAmount,String createdAt){}
    public record Summary(long completedCount,long grossAmount,long feeAmount,long sellerAmount){}
    public PageResponse<Settlement> list(long member,int page,int size){
        if(page<0 || size<1 || size>100)throw new com.example.lancerpick.common.exception.BusinessException(org.springframework.http.HttpStatus.BAD_REQUEST,"VALIDATION_FAILED","페이지를 확인해 주세요.");
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM settlements s JOIN orders o ON o.id=s.order_id WHERE o.seller_id=?",Long.class,member);
        long offset=(long)page*size;
        List<Settlement> items=offset>=total?List.of():jdbc.query("SELECT s.* FROM settlements s JOIN orders o ON o.id=s.order_id WHERE o.seller_id=? ORDER BY s.created_at DESC,s.id DESC LIMIT ? OFFSET ?",(rs,row)->new Settlement(rs.getString("id"),rs.getString("order_id"),rs.getLong("gross_amount"),rs.getInt("fee_rate_bps"),rs.getLong("fee_amount"),rs.getLong("seller_amount"),rs.getObject("created_at",java.time.LocalDateTime.class).atOffset(ZoneOffset.UTC).toString()),member,size,offset);
        return new PageResponse<>(items,page,size,total,Math.toIntExact(total/size+(total%size==0?0:1)));
    }
    public Summary summary(long member){
        return jdbc.queryForObject("SELECT COUNT(*),COALESCE(SUM(s.gross_amount),0),COALESCE(SUM(s.fee_amount),0),COALESCE(SUM(s.seller_amount),0) FROM settlements s JOIN orders o ON o.id=s.order_id WHERE o.seller_id=?",(rs,row)->new Summary(rs.getLong(1),safe(rs.getBigDecimal(2)),safe(rs.getBigDecimal(3)),safe(rs.getBigDecimal(4))),member);
    }
    private static long safe(java.math.BigDecimal value){long amount=value.longValueExact();if(amount>9_007_199_254_740_991L)throw new ArithmeticException("Settlement summary exceeds JSON safe integer range");return amount;}
}
