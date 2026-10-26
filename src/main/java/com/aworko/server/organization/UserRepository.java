package com.aworko.server.organization;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, String> {

    Optional<User> findByUserIdAndCompanyId(String userId, String companyId);

    Optional<User> findByEmployeeNo(Integer employeeNo);

    boolean existsByEmployeeNo(Integer employeeNo);

    List<User> findByTeamIdAndRole(String teamId, UserRole role);
}
