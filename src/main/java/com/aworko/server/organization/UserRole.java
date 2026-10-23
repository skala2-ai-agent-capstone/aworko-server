package com.aworko.server.organization;

import com.aworko.server.global.common.CodedEnum;
import com.aworko.server.global.common.CodedEnumConverter;
import jakarta.persistence.Converter;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** users.role. 팀원(member) 또는 팀장(team_lead, 검토자 겸임). MCP 스키마의 CHECK가 이 둘만 허용한다. */
@Getter
@RequiredArgsConstructor
public enum UserRole implements CodedEnum {

    MEMBER("member"),
    TEAM_LEAD("team_lead");

    private final String code;

    @Converter(autoApply = true)
    public static class JpaConverter extends CodedEnumConverter<UserRole> {
        public JpaConverter() {
            super(UserRole.class);
        }
    }
}
