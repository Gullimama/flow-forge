package com.flowforge.common.repository;

import com.flowforge.common.entity.BlobRecordEntity;
import com.flowforge.common.entity.BlobRecordStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BlobRecordRepository extends JpaRepository<BlobRecordEntity, Long> {

    List<BlobRecordEntity> findByBatchIdAndStatus(UUID batchId, BlobRecordStatus status);
    boolean existsByEtag(String etag);
}
