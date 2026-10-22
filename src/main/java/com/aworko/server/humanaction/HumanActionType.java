package com.aworko.server.humanaction;

import com.aworko.server.global.common.CodedEnum;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * human_actions.action 중 백엔드가 기록하는 값. 시드 데이터·business-mcp(store.human_action)가 쓰는 이름과 같다.
 * 시드에는 조회 기록("개인 평가 리포트 조회" 등)도 섞여 있으므로 엔티티의 action 컬럼은 문자열로 둔다.
 */
@Getter
@RequiredArgsConstructor
public enum HumanActionType implements CodedEnum {

    CONFIRM("confirm"),
    APPEAL("appeal"),
    REVIEW_DECISION("review_decision"),
    APPROVE_AGGREGATION("approve_aggregation"),
    RETRY_FAILED_EVALUATION("retry_failed_evaluation");

    private final String code;
}
