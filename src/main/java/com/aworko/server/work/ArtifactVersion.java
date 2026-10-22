package com.aworko.server.work;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/** 산출물 버전. 본문(content)은 매핑하지 않는다. */
@Entity
@Immutable
@Table(name = "artifact_versions")
@IdClass(ArtifactVersion.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ArtifactVersion {

    @Id
    @Column(name = "artifact_id")
    private String artifactId;

    @Id
    @Column(name = "version")
    private String version;

    @Column(name = "work_id", nullable = false)
    private String workId;

    /** ai_draft / employee */
    @Column(nullable = false)
    private String author;

    @Column(name = "saved_at", nullable = false)
    private OffsetDateTime savedAt;

    @EqualsAndHashCode
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Key implements Serializable {
        private String artifactId;
        private String version;
    }
}
