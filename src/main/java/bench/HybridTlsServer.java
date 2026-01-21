package bench;

import javax.net.ssl.*;
import java.io.*;
import java.security.SecureRandom;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HybridTlsServer {

    private static final Logger LOGGER = LoggerFactory.getLogger(HybridTlsServer.class);

    private final int port;
    private final String[] namedGroups;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private volatile SSLServerSocket serverSocket;

    public HybridTlsServer(int port, String[] namedGroups) {
        this.port = port;
        this.namedGroups = namedGroups;
    }

    public void start() throws Exception {
        SSLContext sslContext = createServerContext();
        SSLServerSocketFactory ssf = sslContext.getServerSocketFactory();
        try {
            serverSocket = (SSLServerSocket) ssf.createServerSocket(port);

            SSLParameters params = serverSocket.getSSLParameters();
            // Configure TLS 1.3 only for cleaner results
            params.setProtocols(new String[] {"TLSv1.3"});
            if (namedGroups != null && namedGroups.length > 0) {
                params.setNamedGroups(namedGroups);
            }

            // Log supported named groups for easier debugging
            String[] supported = sslContext.getSupportedSSLParameters().getNamedGroups();
            if (supported != null && supported.length > 0) {
                LOGGER.info("Supported named groups: {}", String.join(", ", supported));
            } else {
                LOGGER.info("Supported named groups: (none)");
            }

            serverSocket.setSSLParameters(params);

            LOGGER.info("Server listening on port {}", port);
            while (running.get()) {
                try {
                    SSLSocket socket = (SSLSocket) serverSocket.accept();
                    handleClient(socket);
                } catch (IOException acceptEx) {
                    if (!running.get()) {
                        // shutting down; break loop
                        break;
                    }
                    LOGGER.warn("Error accepting connection", acceptEx);
                }
            }
        } finally {
            if (serverSocket != null && !serverSocket.isClosed()) {
                try {
                    serverSocket.close();
                } catch (IOException e) {
                    LOGGER.warn("Error closing server socket", e);
                }
            }
        }
    }

    /**
     * Stop the running server. This will cause the accept() to unblock and the start() loop to exit.
     */
    @SuppressWarnings("unused")
    public void stop() {
        running.set(false);
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                LOGGER.warn("Error closing server socket during stop()", e);
            }
        }
    }

    private SSLContext createServerContext() throws Exception {
        // Load server key and certificate from JKS (create with keytool beforehand)
        // Load password from application.properties on the classpath (fallback to "changeit")
        Properties props = new Properties();
        try (InputStream propIn = HybridTlsServer.class.getClassLoader().getResourceAsStream("application.properties")) {
            if (propIn != null) {
                props.load(propIn);
            }
        }
        String password = props.getProperty("keystore.password", "changeit");
        char[] pwdChars = password.toCharArray();

        // Try working directory first, then target/ so tests can write temporary keystore there
        InputStream in = null;
        try {
            in = new FileInputStream("server.keystore");
        } catch (FileNotFoundException e1) {
            try {
                in = new FileInputStream("target/server.keystore");
            } catch (FileNotFoundException e2) {
                // will handle below
            }
        }

        if (in != null) {
            try (InputStream inStream = in) {
                java.security.KeyStore ks = java.security.KeyStore.getInstance("JKS");
                ks.load(inStream, pwdChars);

                KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                kmf.init(ks, pwdChars);

                SSLContext ctx = SSLContext.getInstance("TLS");
                ctx.init(kmf.getKeyManagers(), null, new SecureRandom());

                // wipe password char array for safety
                java.util.Arrays.fill(pwdChars, '\0');
                return ctx;
            }
        }

        LOGGER.info("server.keystore not found; using default SSLContext (no key managers)");
        // fallback: initialize default SSLContext
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, null, new SecureRandom());
        // wipe password char array for safety
        java.util.Arrays.fill(pwdChars, '\0');
        return ctx;
    }

    /* package-private for testing */
    SSLContext createServerContext(InputStream keystoreStream, char[] password) throws Exception {
        java.security.KeyStore ks = java.security.KeyStore.getInstance("JKS");
        ks.load(keystoreStream, password);

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, password);

        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, new SecureRandom());

        return ctx;
    }

    private void handleClient(SSLSocket socket) {
        try (SSLSocket s = socket) {
            long start = System.nanoTime();
            s.startHandshake();
            long end = System.nanoTime();

            SSLSession session = s.getSession();
            LOGGER.info("Handshake time (ms): {}", (end - start) / 1_000_000.0);
            LOGGER.info("Protocol: {}", session.getProtocol());
            LOGGER.info("Cipher suite: {}", session.getCipherSuite());

            // Simple echo to ensure data path works
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(s.getInputStream()));
            BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(s.getOutputStream()));
            String line = reader.readLine();
            writer.write("OK: " + line + "\n");
            writer.flush();
        } catch (Exception e) {
            // Quiet known/expected handshake closure or missing-extension cases, but log unexpected errors
            Throwable r = e;
            while (r.getCause() != null) r = r.getCause();

            if (r instanceof EOFException) {
                LOGGER.debug("Client closed connection during/after handshake: {}", r.toString());
            } else if (e instanceof SSLHandshakeException || (e.getMessage() != null && e.getMessage().contains("missing_extension"))) {
                LOGGER.warn("SSL handshake failure: {}", e.getMessage());
            } else {
                LOGGER.error("Error handling client", e);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        // Usage: HybridTlsServer <mode> [port]
        if (args.length < 1) {
            LOGGER.error("Usage: HybridTlsServer classical|hybrid|pqc [port]");
            System.exit(1);
        }

        String mode = args[0].toLowerCase();
        int port = 8443;
        if (args.length >= 2) {
            try {
                port = Integer.parseInt(args[1]);
            } catch (NumberFormatException nfe) {
                LOGGER.warn("Invalid port: {}, using default 8443", args[1]);
            }
        }

        String[] namedGroups;
        if ("classical".equalsIgnoreCase(mode)) {
            namedGroups = new String[]{"x25519"};
        } else if ("hybrid".equalsIgnoreCase(mode)) {
            namedGroups = new String[]{"X25519MLKEM768", "x25519"};
        } else if ("pqc".equalsIgnoreCase(mode) || "pqc-only".equalsIgnoreCase(mode)) {
            namedGroups = new String[]{"MLKEM768"};
        } else {
            LOGGER.error("Unknown mode: {}", mode);
            LOGGER.error("Usage: HybridTlsServer classical|hybrid|pqc [port]");
            System.exit(1);
            return;
        }

        LOGGER.info("Starting server in mode: {} on port {}", mode, port);
        HybridTlsServer server = new HybridTlsServer(port, namedGroups);
        server.start();
    }

}
