package com.stampedeio.catalog.projection;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectionOffsetRepository extends JpaRepository<ProjectionOffset, ProjectionOffset.Key> {
}
