package com.aworko.server.humanaction;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/** 정책 규칙. 백엔드는 kind='masking' 규칙으로 human_actions.payload_masked를 만든다. */
@Entity
@Immutable
@Table(name = "policy_rules")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PolicyRule {

    public static final String KIND_MASKING = "masking";

    @Id
    @Column(name = "rule_id")
    private String ruleId;

    @Column(nullable = false)
    private int seq;

    /** masking / violation */
    @Column(nullable = false)
    private String kind;

    @Column
    private String pattern;

    @Column(name = "replace_with")
    private String replaceWith;
}
