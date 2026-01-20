package bench;

import javax.net.ssl.*;
import java.io.*;
import java.security.SecureRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HybridTlsClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(HybridTlsClient.class);

    private final String host;
    private final int port;
    private final String[] namedGroups;

    public HybridTlsClient(String host, int port, String[] namedGroups) {
        this.host = host;
        this.port = port;
        this.namedGroups = namedGroups;
    }

    private SSLContext createClientContext() throws Exception {
        // Simple trust-all for local experiments (DO NOT use in production)
        TrustManager[] trustAll = new TrustManager[] {
                new X509TrustManager() {
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
                    public void checkClientTrusted(java.security.cert.X509Certificate[] xcs, String s) { }
                    public void checkServerTrusted(java.security.cert.X509Certificate[] xcs, String s) { }
                }
        };
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, trustAll, new SecureRandom());
        return ctx;
    }

    public void runBenchmark(int iterations) throws Exception {
        SSLContext ctx = createClientContext();
        SSLSocketFactory factory = ctx.getSocketFactory();

        long totalHandshakeNanos = 0;

        for (int i = 0; i < iterations; i++) {
            try (SSLSocket socket =
                         (SSLSocket) factory.createSocket(host, port)) {

                SSLParameters params = socket.getSSLParameters();
                params.setProtocols(new String[] {"TLSv1.3"});
                if (namedGroups != null && namedGroups.length > 0) {
                    params.setNamedGroups(namedGroups);
                }
                socket.setSSLParameters(params);

                long start = System.nanoTime();
                socket.startHandshake();
                long end = System.nanoTime();

                totalHandshakeNanos += (end - start);

                SSLSession session = socket.getSession();
                System.out.printf(
                        "Run %d: handshake=%.3f ms, protocol=%s, cipher=%s%n",
                        i,
                        (end - start) / 1_000_000.0,
                        session.getProtocol(),
                        session.getCipherSuite());

                BufferedWriter writer = new BufferedWriter(
                        new OutputStreamWriter(socket.getOutputStream()));
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream()));
                writer.write("hello\n");
                writer.flush();
                String resp = reader.readLine();
                if (resp != null) {
                    LOGGER.info("Server response: " + resp);
                }
            }
        }

        double avgMs = totalHandshakeNanos / 1_000_000.0 / iterations;
        System.out.println("Average handshake time (ms): " + avgMs);
    }

    public static void main(String[] args) throws Exception {
        try {
            if (args.length < 1) {
                System.err.println("Usage: HybridTlsServer classical|hybrid");
                System.exit(1);
            }
            String mode = args[0];

            String[] namedGroups;
            switch (mode.toLowerCase()) {
                case "classical":
                    namedGroups = new String[]{"x25519"};
                    break;
                case "hybrid":
                    namedGroups = new String[]{"X25519MLKEM768","x25519"};
                    break;
                case "pqc":
                     namedGroups = new String[]{"MLKEM768"};
                     break;
                default:
                    throw new IllegalArgumentException("Unknown mode: " + mode);
            }

            HybridTlsClient client = new HybridTlsClient("localhost", 8443, namedGroups);
            client.runBenchmark(100);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
