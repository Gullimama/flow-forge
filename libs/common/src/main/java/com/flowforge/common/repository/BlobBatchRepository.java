package com.flowforge.common.repository;

import com.flowforge.common.entity.BlobBatchEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import com.flowforge.common.entity.BlobBatchStatus;
import java.util.Optional;
import java.util.UUID;

public interface BlobBatchRepository extends JpaRepository<BlobBatchEntity, UUID> {

    Optional<BlobBatchEntity> findTopByStatusOrderByCompletedAtDesc(BlobBatchStatus status);
}
