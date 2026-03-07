package com.flowforge.common.repository;

import com.flowforge.common.entity.BlobBatchEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface BlobBatchRepository extends JpaRepository<BlobBatchEntity, UUID> {
}
