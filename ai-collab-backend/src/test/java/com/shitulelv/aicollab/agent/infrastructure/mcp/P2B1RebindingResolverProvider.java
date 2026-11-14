package com.shitulelv.aicollab.agent.infrastructure.mcp;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolverProvider;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Test-only resolver for the P2-B1 DNS rebinding evidence.
 *
 * <p>It is registered only through the dedicated test overlay. When enabled,
 * addresses resolved from the policy receive the legitimate loopback address;
 * a resolution that happens after policy validation (which would indicate a
 * missing pin) receives the rebound loopback address.</p>
 */
public final class P2B1RebindingResolverProvider extends InetAddressResolverProvider {
    private static final AtomicInteger POLICY_LOOKUPS = new AtomicInteger();
    private static final AtomicInteger EXTERNAL_LOOKUPS = new AtomicInteger();
    private static final byte[] LEGITIMATE = {127, 0, 0, 1};
    private static final byte[] REBOUND = {127, 0, 0, 2};

    @Override
    public InetAddressResolver get(Configuration configuration) {
        InetAddressResolver builtin = configuration.builtinResolver();
        return new InetAddressResolver() {
            @Override
            public Stream<InetAddress> lookupByName(String host, LookupPolicy lookupPolicy)
                    throws UnknownHostException {
                if (!Boolean.getBoolean("p2b1.rebind.enabled") || !"localhost".equalsIgnoreCase(host)) {
                    return builtin.lookupByName(host, lookupPolicy);
                }
                if (calledFromPolicy()) {
                    POLICY_LOOKUPS.incrementAndGet();
                    return Stream.of(address(LEGITIMATE));
                }
                EXTERNAL_LOOKUPS.incrementAndGet();
                return Stream.of(address(REBOUND));
            }

            @Override
            public String lookupByAddress(byte[] address) throws UnknownHostException {
                return builtin.lookupByAddress(address);
            }
        };
    }

    @Override
    public String name() {
        return "p2b1-rebinding-fixture";
    }

    public static void reset() {
        POLICY_LOOKUPS.set(0);
        EXTERNAL_LOOKUPS.set(0);
    }

    public static int policyLookups() {
        return POLICY_LOOKUPS.get();
    }

    public static int externalLookups() {
        return EXTERNAL_LOOKUPS.get();
    }

    private static boolean calledFromPolicy() {
        String policyClass = McpEndpointPolicy.class.getName();
        return StackWalker.getInstance().walk(frames ->
                frames.anyMatch(frame -> policyClass.equals(frame.getClassName())));
    }

    private static InetAddress address(byte[] bytes) {
        try {
            return InetAddress.getByAddress(bytes);
        } catch (UnknownHostException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
