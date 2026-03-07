package com.flowforge.common.repository;

import com.flowforge.common.entity.SnapshotEntity;
import com.flowforge.common.entity.SnapshotStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SnapshotRepository extends JpaRepository<SnapshotEntity, UUID> {

    Optional<SnapshotEntity> findTopByStatusOrderByCreatedAtDesc(SnapshotStatus status);
    Optional<SnapshotEntity> findTopByOrderByCreatedAtDesc();
    List<SnapshotEntity> findByStatus(SnapshotStatus status);
}
