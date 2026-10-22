package com.aworko.server.work;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ArtifactVersionRepository extends JpaRepository<ArtifactVersion, ArtifactVersion.Key> {

    List<ArtifactVersion> findByWorkIdAndVersion(String workId, String version);
}
