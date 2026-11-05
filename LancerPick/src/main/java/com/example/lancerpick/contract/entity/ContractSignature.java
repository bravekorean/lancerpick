package com.example.lancerpick.contract.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Entity
@Table(name = "contract_signatures")
public class ContractSignature {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "contract_id", nullable = false, updatable = false)
  private long contractId;

  @Column(name = "member_id", nullable = false, updatable = false)
  private long memberId;

  @Column(name = "party_role", nullable = false, updatable = false, length = 10)
  private String partyRole;

  @Column(
      name = "signed_content_hash",
      nullable = false,
      updatable = false,
      columnDefinition = "BINARY(32)")
  private byte[] signedContentHash;

  @Column(
      name = "signed_file_hash",
      nullable = false,
      updatable = false,
      columnDefinition = "BINARY(32)")
  private byte[] signedFileHash;

  @JdbcTypeCode(SqlTypes.LOCAL_DATE_TIME)
  @Column(name = "signed_at", nullable = false, updatable = false)
  private LocalDateTime signedAt;

  protected ContractSignature() {}

  public static ContractSignature sign(
      OrderContract contract, long memberId, String role, LocalDateTime at) {
    if (!"READY".equals(contract.getStatus())
        || memberId <= 0
        || !("BUYER".equals(role) || "SELLER".equals(role)))
      throw new IllegalArgumentException("Invalid signature");
    var s = new ContractSignature();
    s.contractId = contract.getId();
    s.memberId = memberId;
    s.partyRole = role;
    s.signedContentHash = contract.getContentHash();
    s.signedFileHash = contract.getFileHash();
    s.signedAt = at;
    return s;
  }

  public byte[] getSignedContentHash() {
    return signedContentHash.clone();
  }

  public byte[] getSignedFileHash() {
    return signedFileHash.clone();
  }
}
