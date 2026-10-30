package com.shitulelv.aicollab.common.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PublicAppUrlServiceTest {

    @Test
    void blank_config_stays_disabled() {
        assertThat(new PublicAppUrlService("").publicUrl()).isEmpty();
        assertThat(new PublicAppUrlService("   ").invitationUrl("abc")).isEmpty();
    }

    @Test
    void valid_origin_builds_invitation_url() {
        var service = new PublicAppUrlService("https://aicollab.example.edu/");
        assertThat(service.invitationUrl("abc123")).contains("https://aicollab.example.edu/invite/abc123");
    }

    @Test
    void rejects_non_http_schemes_and_paths() {
        assertThatThrownBy(() -> new PublicAppUrlService("ftp://aicollab.example.edu"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new PublicAppUrlService("https://aicollab.example.edu/app"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new PublicAppUrlService("https://aicollab.example.edu?x=1"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new PublicAppUrlService("not-a-url"))
                .isInstanceOf(IllegalStateException.class);
    }
}
