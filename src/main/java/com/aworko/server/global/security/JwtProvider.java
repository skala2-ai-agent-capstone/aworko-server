package com.aworko.server.global.security;

import com.aworko.server.global.common.CodedEnum;
import com.aworko.server.organization.UserRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

/** Access JWT 발급·검증 (HS256). 클레임: sub=user_id, cid=company_id, tid=team_id, role */
@Component
public class JwtProvider {

    private static final String CLAIM_COMPANY = "cid";
    private static final String CLAIM_TEAM = "tid";
    private static final String CLAIM_ROLE = "role";

    private final JwtProperties props;
    private final SecretKey key;
    private final Clock clock;

    public JwtProvider(JwtProperties props, Clock clock) {
        byte[] secret = props.secret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException("app.jwt.secret(JWT_SECRET)은 32바이트 이상이어야 합니다");
        }
        this.props = props;
        this.key = Keys.hmacShaKeyFor(secret);
        this.clock = clock;
    }

    public IssuedToken issue(AuthPrincipal principal) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(props.accessTokenTtl());
        String token = Jwts.builder()
                .issuer(props.issuer())
                .subject(principal.userId())
                .claim(CLAIM_COMPANY, principal.companyId())
                .claim(CLAIM_TEAM, principal.teamId())
                .claim(CLAIM_ROLE, principal.role().getCode())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();
        return new IssuedToken(token, props.accessTokenTtl().toSeconds());
    }

    /** 서명·만료·발급자가 맞으면 사용자 정보를, 아니면 empty를 돌려준다. 실패 이유는 응답에 노출하지 않는다. */
    public Optional<AuthPrincipal> parse(String token) {
        try {
            Claims c = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(props.issuer())
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            UserRole role = CodedEnum.fromCode(UserRole.class, c.get(CLAIM_ROLE, String.class));
            return Optional.of(new AuthPrincipal(c.getSubject(), c.get(CLAIM_COMPANY, String.class),
                    c.get(CLAIM_TEAM, String.class), role));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public record IssuedToken(String accessToken, long expiresInSeconds) {
    }
}
