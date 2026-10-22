package com.aworko.server.organization;

import com.aworko.server.global.common.CodedEnum;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** users.role. 팀원(member) 또는 팀장(team_lead, 검토자 겸임). */
@Getter
@RequiredArgsConstructor
public enum UserRole implements CodedEnum {

    MEMBER("member"),
    TEAM_LEAD("team_lead");

    private final String code;

    /**
     * MCP 적재기가 role을 이전 시드 값(employee·reviewer·ax_manager·admin)으로 되돌릴 수 있어
     * team_lead만 팀장으로, 나머지는 권한이 가장 작은 팀원으로 읽는다. MCP 스키마 반영 후 엄격하게 바꾼다.
     */
    @Converter(autoApply = true)
    public static class JpaConverter implements AttributeConverter<UserRole, String> {

        @Override
        public String convertToDatabaseColumn(UserRole role) {
            return role == null ? null : role.getCode();
        }

        @Override
        public UserRole convertToEntityAttribute(String code) {
            if (code == null) {
                return null;
            }
            return TEAM_LEAD.code.equals(code) ? TEAM_LEAD : MEMBER;
        }
    }
}
