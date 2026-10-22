package com.aworko.server.organization;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/** 회사(테넌트). 기준 데이터는 MCP 적재기가 넣으므로 백엔드는 읽기만 한다. */
@Entity
@Immutable
@Table(name = "companies")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Company {

    @Id
    @Column(name = "company_id")
    private String companyId;

    @Column(nullable = false)
    private String name;

    @Column(name = "min_aggregation_size", nullable = false)
    private int minAggregationSize;
}
