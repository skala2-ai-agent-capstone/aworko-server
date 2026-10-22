package com.aworko.server.organization;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamRepository extends JpaRepository<Team, String> {

    Optional<Team> findByTeamIdAndCompanyId(String teamId, String companyId);
}
