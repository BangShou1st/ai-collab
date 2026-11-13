package com.shitulelv.aicollab.agent.infrastructure.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.common.exception.BusinessException;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.Security;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Standalone real-process harness for the P2-B1 network evidence.
 *
 * <p>This class intentionally has a {@code main} method instead of a JUnit test:
 * the evidence must prove real socket behavior in an independent Java process.
 * It uses only production MCP policy/client classes and test-only listeners.</p>
 */
public final class P2B1NetworkEvidenceMain {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private P2B1NetworkEvidenceMain() {
    }

    public static void main(String[] args) {
        ObjectNode root = JSON.createObjectNode();
        Path output = null;
        String mode = args.length == 0 ? "" : args[0];
        try {
            Map<String, String> options = options(args, 1);
            output = Path.of(required(options, "output")).toAbsolutePath();
            configureJvm(options);
            root.put("schemaVersion", 1);
            root.put("evidenceLevel", "REAL_PROCESS");
            root.put("mode", mode);
            root.put("generatedAt", OffsetDateTime.now(SHANGHAI).toString());
            root.put("pid", ProcessHandle.current().pid());
            root.put("javaVersion", System.getProperty("java.version"));
            root.put("os", System.getProperty("os.name"));
            root.put("harnessClass", P2B1NetworkEvidenceMain.class.getName());

            if ("m01".equals(mode)) {
                runM01(options, root);
            } else if ("m02".equals(mode)) {
                runM02(options, root);
            } else {
                throw new IllegalArgumentException("mode must be m01 or m02");
            }

            root.put("harnessStatus", pass(root) ? "PASS" : "FAIL");
            write(root, output);
            System.exit(pass(root) ? 0 : 2);
        } catch (Throwable failure) {
            root.put("harnessStatus", "FAIL");
            root.put("harnessError", failure.getClass().getName() + ": " + failure.getMessage());
            if (output != null) {
                try {
                    write(root, output);
                } catch (IOException ignored) {
                    // The process still exits non-zero; the caller records the missing output.
                }
            }
            failure.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void runM01(Map<String, String> options, ObjectNode root) throws Exception {
        SocketTap.install();
        ObjectNode result = root.putObject("M-01");
        result.put("status", "FAIL");
        result.put("evidenceLevel", "REAL_PROCESS");
        result.put("socketTapInstalled", true);
        ArrayNode cases = result.putArray("cases");

        Path controlCountFile = Path.of(required(options, "control-count-file"));
        Path privateCountFile = Path.of(required(options, "private-count-file"));
        Path outsideCountFile = Path.of(required(options, "outside-count-file"));
        int controlPort = integer(options, "control-port");
        int privatePort = integer(options, "private-port");
        int outsidePort = integer(options, "outside-port");
        String privateAddress = required(options, "private-address");
        String expectedControlVersion = required(options, "control-version");

        AtomicInteger controlResolutions = new AtomicInteger();
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        McpEndpointPolicy controlPolicy = new McpEndpointPolicy(
                Set.of("localhost"),
                host -> {
                    controlResolutions.incrementAndGet();
                    return new InetAddress[]{loopback};
                },
                true);
        ObjectNode control = runCallCase(
                "M-01.control-real-connection",
                "https://localhost:" + controlPort + "/mcp",
                controlPolicy,
                null,
                controlCountFile,
                true);
        control.put("expectedServerVersion", expectedControlVersion);
        control.put("resolverCalls", controlResolutions.get());
        if (!expectedControlVersion.equals(control.path("serverVersion").asText(""))) {
            control.put("status", "FAIL");
            control.put("versionMismatch", true);
        }
        if (!"PASS".equals(control.path("status").asText())) {
            cases.add(control);
            result.put("status", "FAIL");
            return;
        }
        cases.add(control);

        ObjectNode metadata = runCallCase(
                "M-01.metadata-literal",
                "https://169.254.169.254/mcp",
                new McpEndpointPolicy(Set.of("169.254.169.254"), InetAddress::getAllByName, false),
                "AGENT_MCP_ENDPOINT_FORBIDDEN",
                null,
                false);
        metadata.put("targetCategory", "METADATA");
        metadata.put("resolvedAddress", "169.254.169.254");
        cases.add(metadata);

        InetAddress privateTarget = InetAddress.getByName(privateAddress);
        AtomicInteger privateResolutions = new AtomicInteger();
        McpEndpointPolicy privatePolicy = new McpEndpointPolicy(
                Set.of("localhost"),
                host -> {
                    privateResolutions.incrementAndGet();
                    return new InetAddress[]{privateTarget};
                },
                true);
        ObjectNode privateCase = runCallCase(
                "M-01.private-resolution",
                "https://localhost:" + privatePort + "/mcp",
                privatePolicy,
                "AGENT_MCP_ENDPOINT_FORBIDDEN",
                privateCountFile,
                false);
        privateCase.put("targetCategory", "PRIVATE");
        privateCase.put("resolvedAddress", privateAddress);
        privateCase.put("resolverCalls", privateResolutions.get());
        cases.add(privateCase);

        AtomicInteger outsideResolutions = new AtomicInteger();
        ObjectNode outside = runCallCase(
                "M-01.allowlist-outside",
                "https://127.0.0.1:" + outsidePort + "/mcp",
                new McpEndpointPolicy(
                        Set.of("localhost"),
                        host -> {
                            outsideResolutions.incrementAndGet();
                            return new InetAddress[]{loopback};
                        },
                        true),
                "AGENT_MCP_ENDPOINT_FORBIDDEN",
                outsideCountFile,
                false);
        outside.put("targetCategory", "ALLOWLIST_OUTSIDE");
        outside.put("resolverCalls", outsideResolutions.get());
        cases.add(outside);

        boolean allPass = true;
        for (JsonNode item : cases) {
            allPass &= "PASS".equals(item.path("status").asText());
        }
        result.put("status", allPass ? "PASS" : "FAIL");
        result.put("controlObservedRealConnection",
                control.path("socketConnectAttempts").asInt(0) > 0
                        && control.path("listenerAccepted").asInt(0) > 0);
        result.put("allNegativeCasesZeroSocketAttempts", negativeZero(cases));
        root.set("matrix", matrix("M-01", result.path("status").asText()));
    }

    private static void runM02(Map<String, String> options, ObjectNode root) throws Exception {
        P2B1RebindingResolverProvider.reset();
        ObjectNode result = root.putObject("M-02");
        result.put("status", "FAIL");
        result.put("evidenceLevel", "REAL_PROCESS");
        result.put("controlledResolver", "P2B1RebindingResolverProvider");
        int port = integer(options, "port");
        Path goodCountFile = Path.of(required(options, "good-count-file"));
        Path evilCountFile = Path.of(required(options, "evil-count-file"));
        String expectedGoodVersion = required(options, "good-version");

        McpEndpointPolicy policy = new McpEndpointPolicy("localhost", true);
        String serverVersion = null;
        String productError = null;
        int goodBefore = readCount(goodCountFile);
        int evilBefore = readCount(evilCountFile);
        try (HttpMcpClientFacade facade = new HttpMcpClientFacade(
                URI.create("https://localhost:" + port + "/mcp"),
                null, 3000, 32768, JSON, policy)) {
            McpClientFacade.ServerInfo info = facade.initialize();
            serverVersion = info.version();
        } catch (Throwable failure) {
            productError = failure.getClass().getName() + ": " + failure.getMessage();
        }

        int policyLookups = P2B1RebindingResolverProvider.policyLookups();
        int externalBeforeProbe = P2B1RebindingResolverProvider.externalLookups();
        int goodAfterProduct = waitForCount(goodCountFile, goodBefore + 1, 5000);
        int evilAfterProduct = readCount(evilCountFile);

        InetAddress[] rebound = InetAddress.getAllByName("localhost");
        List<String> reboundAddresses = Arrays.stream(rebound)
                .map(InetAddress::getHostAddress)
                .toList();
        boolean reboundResolved = reboundAddresses.contains("127.0.0.2");
        boolean reboundReachable = false;
        String probeError = null;
        try {
            reboundReachable = tlsHandshake(rebound[0], port);
        } catch (Throwable failure) {
            probeError = failure.getClass().getName() + ": " + failure.getMessage();
        }
        int evilAfterProbe = waitForCount(evilCountFile, evilAfterProduct + 1, 3000);

        result.put("endpoint", "https://localhost:" + port + "/mcp");
        result.put("expectedServerVersion", expectedGoodVersion);
        result.put("observedServerVersion", serverVersion);
        result.put("productError", productError);
        result.put("policyLookups", policyLookups);
        result.put("externalLookupsBeforeProbe", externalBeforeProbe);
        result.put("externalLookupsAfterExplicitLookup",
                P2B1RebindingResolverProvider.externalLookups());
        result.put("reboundAddresses", JSON.valueToTree(reboundAddresses));
        result.put("reboundResolved", reboundResolved);
        result.put("reboundReachable", reboundReachable);
        result.put("probeError", probeError);
        result.put("goodAcceptedBefore", goodBefore);
        result.put("goodAcceptedAfterProduct", goodAfterProduct);
        result.put("evilAcceptedBefore", evilBefore);
        result.put("evilAcceptedAfterProduct", evilAfterProduct);
        result.put("evilAcceptedAfterProbe", evilAfterProbe);

        boolean pass = productError == null
                && expectedGoodVersion.equals(serverVersion)
                && policyLookups > 0
                && externalBeforeProbe == 0
                && goodAfterProduct > goodBefore
                && evilAfterProduct == evilBefore
                && reboundResolved
                && reboundReachable
                && evilAfterProbe > evilAfterProduct;
        result.put("status", pass ? "PASS" : "FAIL");
        root.set("matrix", matrix("M-02", result.path("status").asText()));
    }

    private static ObjectNode runCallCase(
            String id,
            String endpoint,
            McpEndpointPolicy policy,
            String expectedErrorCode,
            Path countFile,
            boolean expectSuccess) throws Exception {
        SocketTap.reset();
        ObjectNode record = JSON.createObjectNode();
        record.put("id", id);
        record.put("endpoint", endpoint);
        record.put("status", "FAIL");
        record.put("expectedErrorCode", expectedErrorCode == null ? "" : expectedErrorCode);
        record.put("expectSuccess", expectSuccess);
        String errorCode = null;
        String errorType = null;
        McpClientFacade.ServerInfo info = null;
        try (HttpMcpClientFacade facade = new HttpMcpClientFacade(
                URI.create(endpoint), null, 3000, 32768, JSON, policy)) {
            info = facade.initialize();
        } catch (BusinessException failure) {
            errorCode = failure.getErrorCode().name();
            errorType = failure.getClass().getName();
        } catch (Throwable failure) {
            errorType = failure.getClass().getName();
            errorCode = errorType + ": " + failure.getMessage();
        }
        int accepted = countFile == null
                ? 0
                : (expectSuccess
                        ? waitForCount(countFile, 1, 5000)
                        : readCountAfterDelay(countFile, 350));
        List<SocketTap.Event> events = SocketTap.snapshot();
        record.put("errorCode", errorCode == null ? "" : errorCode);
        record.put("errorType", errorType == null ? "" : errorType);
        record.put("socketConnectAttempts", events.size());
        ArrayNode eventArray = record.putArray("socketConnectEvents");
        for (SocketTap.Event event : events) {
            eventArray.add(JSON.valueToTree(event));
        }
        record.put("listenerConfigured", countFile != null);
        record.put("listenerAccepted", accepted);
        if (info != null) {
            record.put("serverName", info.name());
            record.put("serverVersion", info.version());
        }
        boolean ok;
        if (expectSuccess) {
            ok = errorCode == null
                    && info != null
                    && events.size() > 0
                    && accepted > 0;
        } else {
            ok = expectedErrorCode != null
                    && expectedErrorCode.equals(errorCode)
                    && events.isEmpty()
                    && accepted == 0;
        }
        record.put("status", ok ? "PASS" : "FAIL");
        return record;
    }

    private static boolean negativeZero(ArrayNode cases) {
        for (JsonNode item : cases) {
            if (!item.path("expectSuccess").asBoolean(true)
                    && (item.path("socketConnectAttempts").asInt(-1) != 0
                            || item.path("listenerAccepted").asInt(-1) != 0)) {
                return false;
            }
        }
        return true;
    }

    private static boolean tlsHandshake(InetAddress address, int port) throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        String trustStorePath = System.getProperty("javax.net.ssl.trustStore");
        String password = System.getenv("P2B1_TRUSTSTORE_PASSWORD");
        try (var input = Files.newInputStream(Path.of(trustStorePath))) {
            trustStore.load(input, password == null ? new char[0] : password.toCharArray());
        }
        TrustManagerFactory managers = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        managers.init(trustStore);
        context.init(null, managers.getTrustManagers(), null);
        SSLSocketFactory factory = context.getSocketFactory();
        try (SSLSocket socket = (SSLSocket) factory.createSocket()) {
            socket.connect(new InetSocketAddress(address, port), 3000);
            socket.setSoTimeout(3000);
            socket.startHandshake();
            return true;
        }
    }

    private static int waitForCount(Path path, int minimum, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        int count = readCount(path);
        while (count < minimum && System.nanoTime() < deadline) {
            Thread.sleep(100);
            count = readCount(path);
        }
        return count;
    }

    private static int readCountAfterDelay(Path path, long delayMs) throws InterruptedException {
        Thread.sleep(delayMs);
        return readCount(path);
    }

    private static int readCount(Path path) {
        try {
            if (!Files.exists(path)) {
                return 0;
            }
            JsonNode node = JSON.readTree(Files.readString(path));
            return node.path("acceptedConnections").asInt(0);
        } catch (IOException exception) {
            return -1;
        }
    }

    private static void configureJvm(Map<String, String> options) {
        System.setProperty("java.net.useSystemProxies", "false");
        System.setProperty("javax.net.ssl.trustStore", required(options, "truststore"));
        System.setProperty("javax.net.ssl.trustStoreType", "PKCS12");
        String password = System.getenv("P2B1_TRUSTSTORE_PASSWORD");
        if (password == null) {
            throw new IllegalStateException("P2B1_TRUSTSTORE_PASSWORD is required");
        }
        System.setProperty("javax.net.ssl.trustStorePassword", password);
        // Prevent InetAddress from hiding the controlled resolver's second lookup.
        System.setProperty("sun.net.inetaddr.ttl", "0");
        Security.setProperty("networkaddress.cache.ttl", "0");
        Security.setProperty("networkaddress.cache.negative.ttl", "0");
    }

    private static ObjectNode matrix(String id, String status) {
        ObjectNode matrix = JSON.createObjectNode();
        matrix.put(id, status);
        return matrix;
    }

    private static boolean pass(ObjectNode root) {
        return "PASS".equals(root.path("M-01").path("status").asText("FAIL"))
                || "PASS".equals(root.path("M-02").path("status").asText("FAIL"));
    }

    private static Map<String, String> options(String[] args, int start) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = start; index < args.length; index += 2) {
            if (index + 1 >= args.length || !args[index].startsWith("--")) {
                throw new IllegalArgumentException("options must be --name value pairs");
            }
            values.put(args[index].substring(2), args[index + 1]);
        }
        return values;
    }

    private static String required(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing --" + name);
        }
        return value;
    }

    private static int integer(Map<String, String> options, String name) {
        return Integer.parseInt(required(options, name));
    }

    private static void write(ObjectNode root, Path output) throws IOException {
        Files.createDirectories(output.getParent());
        Files.writeString(output, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n");
    }

    private static final class SocketTap extends SecurityManager {
        private static final List<Event> EVENTS = Collections.synchronizedList(new ArrayList<>());
        private static volatile boolean installed;

        static void install() {
            if (installed) {
                return;
            }
            System.setSecurityManager(new SocketTap());
            installed = true;
        }

        static void reset() {
            EVENTS.clear();
        }

        static List<Event> snapshot() {
            synchronized (EVENTS) {
                return List.copyOf(EVENTS);
            }
        }

        @Override
        public void checkPermission(java.security.Permission permission) {
            // The tap records only connect attempts and must not restrict the harness.
        }

        @Override
        public void checkPermission(java.security.Permission permission, Object context) {
            // The tap records only connect attempts and must not restrict the harness.
        }

        @Override
        public void checkConnect(String host, int port) {
            if (port >= 0) {
                EVENTS.add(new Event(host, port, Thread.currentThread().getName()));
            }
        }

        @Override
        public void checkConnect(String host, int port, Object context) {
            if (port >= 0) {
                EVENTS.add(new Event(host, port, Thread.currentThread().getName()));
            }
        }

        private record Event(String host, int port, String thread) {
        }
    }
}
