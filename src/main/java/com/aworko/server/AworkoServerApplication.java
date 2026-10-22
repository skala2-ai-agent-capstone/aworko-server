package com.aworko.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

// 인증은 JWT 필터가 맡으므로 기본 인메모리 사용자(생성 비밀번호)는 만들지 않는다.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class AworkoServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(AworkoServerApplication.class, args);
    }
}
