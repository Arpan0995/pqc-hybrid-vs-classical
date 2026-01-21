package bench;

import javax.net.ssl.*;
import java.io.*;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HybridTlsServer {
    private final int port;
    private final String[] namedGroups;
    private final ExecutorService threadPool;

    public HybridTlsServer(int port, String[] namedGroups) {
        this.port = port;
        this.namedGroups = namedGroups;
        this.threadPool = Executors.newFixedThreadPool(200);
    }

    public static void main(String[] args) {
        try {
            if (args.length < 1) {
                System.err.println("Usage: HybridTlsServer classical|hybrid");
                System.exit(1);
            }
            String mode = args[0].toLowerCase();

            String[] namedGroups;
            switch (mode) {
                case "classical":
                    namedGroups = new String[]{"x25519"};
                    break;
                case "hybrid":
                    namedGroups = new String[]{"X25519MLKEM768", "x25519"};
                    break;
                default:
                    throw new IllegalArgumentException("Unknown mode: " + mode);
            }

            System.out.println("===========================================");
            System.out.println("Server Mode: " + mode.toUpperCase());
            System.out.println("TLS Named Groups: " + String.join(", ", namedGroups));
            System.out.println("===========================================");

            HybridTlsServer server = new HybridTlsServer(8443, namedGroups);
            server.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void start() throws Exception {
        SSLContext sslContext = createServerContext();
        SSLServerSocketFactory ssf = sslContext.getServerSocketFactory();

        try (SSLServerSocket serverSocket =
                     (SSLServerSocket) ssf.createServerSocket(port)) {

            SSLParameters params = serverSocket.getSSLParameters();
            params.setProtocols(new String[]{"TLSv1.3"});
            if (namedGroups != null && namedGroups.length > 0) {
                params.setNamedGroups(namedGroups);
            }
            serverSocket.setSSLParameters(params);

            System.out.println("Server listening on port " + port);
            System.out.println("Ready for concurrent connections...");

            while (true) {
                SSLSocket socket = (SSLSocket) serverSocket.accept();
                threadPool.submit(() -> handleClient(socket));
            }
        }
    }

    private SSLContext createServerContext() throws Exception {
        KeyStore ks = KeyStore.getInstance("JKS");
        try (InputStream in = new FileInputStream("server.keystore")) {
            ks.load(in, "changeit".toCharArray());
        }

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, "changeit".toCharArray());

        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, new SecureRandom());
        return ctx;
    }

    private void handleClient(SSLSocket socket) {
        try (SSLSocket s = socket) {
            s.startHandshake();

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(s.getInputStream()));
            BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(s.getOutputStream()));

            String line = reader.readLine();
            writer.write("OK: " + line + "\n");
            writer.flush();
        } catch (Exception e) {
            // Silent fail for benchmarking
        }
    }
}
