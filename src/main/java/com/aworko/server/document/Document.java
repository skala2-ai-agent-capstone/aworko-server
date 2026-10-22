package com.aworko.server.document;

import com.aworko.server.work.WorkType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * 사내 문서 메타데이터. 백엔드는 업무 유형별 현행 루브릭 버전을 정할 때만 쓴다(collection = 'rubrics').
 * 본문(content)은 매핑하지 않는다.
 */
@Entity
@Immutable
@Table(name = "documents")
@IdClass(Document.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Document {

    public static final String COLLECTION_RUBRICS = "rubrics";
    public static final String STATUS_CURRENT = "현행";

    @Id
    @Column(name = "doc_id")
    private String docId;

    @Id
    @Column(name = "version")
    private String version;

    @Column(name = "company_id", nullable = false)
    private String companyId;

    @Column(nullable = false)
    private String title;

    @Column
    private String collection;

    @Column(nullable = false)
    private String status;

    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    @Column(name = "work_type")
    private WorkType workType;

    @EqualsAndHashCode
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Key implements Serializable {
        private String docId;
        private String version;
    }
}
