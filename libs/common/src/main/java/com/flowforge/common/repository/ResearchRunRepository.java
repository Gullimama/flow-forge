package com.flowforge.common.repository;

import com.flowforge.common.entity.ResearchRunEntity;
import com.flowforge.common.entity.ResearchRunStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ResearchRunRepository extends JpaRepository<ResearchRunEntity, UUID> {

    Optional<ResearchRunEntity> findTopByStatusOrderByCreatedAtDesc(ResearchRunStatus status);
}
