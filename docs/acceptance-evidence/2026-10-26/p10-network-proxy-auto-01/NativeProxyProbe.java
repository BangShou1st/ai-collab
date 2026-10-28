import java.lang.management.ManagementFactory;
import java.net.*;

/** Read-only native JDK selector observation; no application HTTP, credentials or connection. */
public class NativeProxyProbe {
    public static void main(String[] args) {
        var selector = ProxySelector.getDefault();
        System.out.print("{\"systemDiscoveryProperty\":\"" + System.getProperty("java.net.useSystemProxies")
                + "\",\"nativeSelectorClass\":\"" + selector.getClass().getName()
                + "\",\"startupArgumentPresent\":" + ManagementFactory.getRuntimeMXBean().getInputArguments().contains("-Djava.net.useSystemProxies=true")
                + ",\"chatRequests\":0,\"tcp\":\"NOT_TESTED\",\"tls\":\"NOT_TESTED\",\"routes\":[");
        boolean first = true;
        for (String value : new String[]{"https://generativelanguage.googleapis.com/", "http://127.0.0.1:9000/", "http://localhost:9000/"}) {
            URI target = URI.create(value);
            Proxy proxy = selector.select(target).getFirst();
            if (!first) System.out.print(",");
            first = false;
            InetSocketAddress address = proxy.address() instanceof InetSocketAddress found ? found : null;
            System.out.print("{\"targetHost\":\"" + target.getHost() + "\",\"selection\":\"" + proxy.type()
                    + "\",\"proxyHost\":\"" + (address == null ? "" : address.getHostString())
                    + "\",\"proxyPort\":" + (address == null ? 0 : address.getPort()) + "}");
        }
        System.out.println("]}");
    }
}
