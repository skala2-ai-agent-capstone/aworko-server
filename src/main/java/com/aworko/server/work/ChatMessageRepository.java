package com.aworko.server.work;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, String> {

    /** 최근 대화 5개(실시간 피드백 범위) */
    List<ChatMessage> findTop5ByWorkIdOrderBySeqDesc(String workId);
}
