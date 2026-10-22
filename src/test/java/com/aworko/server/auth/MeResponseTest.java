package com.aworko.server.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MeResponseTest {

    @Test
    void avatarInitialIsFirstCharacterOfName() {
        assertThat(MeResponse.initialOf("김민주")).isEqualTo("김");
        assertThat(MeResponse.initialOf("  Alice")).isEqualTo("A");
        assertThat(MeResponse.initialOf("😀smile")).isEqualTo("😀");
        assertThat(MeResponse.initialOf(" ")).isNull();
        assertThat(MeResponse.initialOf(null)).isNull();
    }
}
