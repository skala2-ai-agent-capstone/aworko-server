package com.aworko.server.humanaction;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HumanActionRepository extends JpaRepository<HumanAction, String> {

    List<HumanAction> findByWorkIdAndActionOrderByAt(String workId, String action);
}
