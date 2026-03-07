package com.flowforge.common.repository;

import com.flowforge.common.entity.ParseArtifactEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ParseArtifactRepository extends JpaRepository<ParseArtifactEntity, Long> {

    List<ParseArtifactEntity> findBySnapshotId(UUID snapshotId);
    Optional<ParseArtifactEntity> findBySnapshotIdAndArtifactTypeAndArtifactKey(
        UUID snapshotId, String artifactType, String artifactKey);
}
