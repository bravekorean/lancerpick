package com.example.lancerpick.seller.entity;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
@lombok.Getter @Entity @Table(name="seller_profiles")
public class SellerProfile {
    @Id
    @Column(name="member_id") private Long memberId;
    protected SellerProfile() {}

}
