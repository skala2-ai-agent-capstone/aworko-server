package com.aworko.server.work;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkCaseRepository extends JpaRepository<WorkCase, String> {

    Optional<WorkCase> findByWorkIdAndCompanyId(String workId, String companyId);
}
