package com.shitulelv.aicollab.common.net;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.lang.management.ManagementFactory;

import static org.assertj.core.api.Assertions.assertThat;

class ProxyForkStartupTest {
    @Test
    @EnabledIfSystemProperty(named = "p10.proxy.expectedHost", matches = ".+")
    void mavenExplicitProxyPropertiesReachActualForkAndSelectTheExpectedRoute() {
        String host = System.getProperty("p10.proxy.expectedHost");
        int port = Integer.parseInt(System.getProperty("p10.proxy.expectedPort"));
        assertThat(System.getProperty("https.proxyHost")).isEqualTo(host);
        assertThat(System.getProperty("https.proxyPort")).isEqualTo(Integer.toString(port));
        var route = EnvironmentProxySelector.system().route(java.net.URI.create("https://probe.example.invalid/"));
        assertThat(route.source()).isEqualTo("JVM_PROPERTIES");
        assertThat(((java.net.InetSocketAddress) route.proxy().address()).getHostString()).isEqualTo(host);
        assertThat(((java.net.InetSocketAddress) route.proxy().address()).getPort()).isEqualTo(port);
    }

    @Test void nativeDiscoveryPropertyIsPassedToActualSurefireJvmAtStartup() {
        String expected = System.getProperty("java.net.useSystemProxies");
        assertThat(expected).isIn("true", "false");
        assertThat(ManagementFactory.getRuntimeMXBean().getInputArguments())
                .contains("-Djava.net.useSystemProxies=" + expected);
        assertThat(ProcessHandle.current().parent()).isPresent();
    }
}
