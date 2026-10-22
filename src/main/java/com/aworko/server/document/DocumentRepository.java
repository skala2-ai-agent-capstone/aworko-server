package com.aworko.server.document;

import com.aworko.server.work.WorkType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentRepository extends JpaRepository<Document, Document.Key> {

    List<Document> findByCompanyIdAndCollectionAndWorkTypeAndStatusOrderByEffectiveFromDesc(
            String companyId, String collection, WorkType workType, String status);
}
