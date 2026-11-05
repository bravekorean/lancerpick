package com.example.lancerpick.contract.repository;

import com.example.lancerpick.contract.entity.ContractSignature;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContractSignatureRepository extends JpaRepository<ContractSignature, Long> {
  List<ContractSignature> findByContractIdOrderByIdAsc(long contractId);
}
