package com.sharedlibs.opencodezen.autoconfigure;

import com.sharedlibs.opencodezen.OpenCodeZenProtocolPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the shared OpenCode Zen request support. Credentials stay application-owned. */
@ConfigurationProperties(prefix = "opencode.zen")
public class OpenCodeZenProperties {

    /** Defaults to the verified OpenCode CLI-compatible User-Agent. */
    private String userAgent = OpenCodeZenProtocolPolicy.USER_AGENT;

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }
}
