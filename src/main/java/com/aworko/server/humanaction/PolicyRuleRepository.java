package com.aworko.server.humanaction;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PolicyRuleRepository extends JpaRepository<PolicyRule, String> {

    List<PolicyRule> findByKindOrderBySeq(String kind);
}
