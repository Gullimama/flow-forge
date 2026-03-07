package com.flowforge.common.repository;

import com.flowforge.common.entity.JobEntity;
import com.flowforge.common.entity.JobStatusEnum;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface JobRepository extends JpaRepository<JobEntity, UUID> {

    List<JobEntity> findByStatus(JobStatusEnum status);
    List<JobEntity> findByJobType(String jobType);
    List<JobEntity> findBySnapshotId(UUID snapshotId);

    @Modifying
    @Query("UPDATE JobEntity j SET j.status = :status, j.progressPct = :progress WHERE j.jobId = :id")
    void updateStatus(@Param("id") UUID jobId, @Param("status") JobStatusEnum status, @Param("progress") float progress);
}
