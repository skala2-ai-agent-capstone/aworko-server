package com.aworko.server.organization;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    @Id
    @Column(name = "user_id")
    private String userId;

    /** 사번. 로그인 ID */
    @Column(name = "employee_no", unique = true)
    private Integer employeeNo;

    /** 평문 비밀번호(단순 프로젝트). 응답·로그에 내보내지 않는다. */
    @Column(name = "password")
    private String password;

    @Column(name = "company_id", nullable = false)
    private String companyId;

    @Column(name = "team_id")
    private String teamId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private UserRole role;
}
