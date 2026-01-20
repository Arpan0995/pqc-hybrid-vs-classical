package bench;

import javax.net.ssl.*;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Properties;

public class HybridTlsServer {

    private final int port;
    private final String[] namedGroups;

    public HybridTlsServer(int port, String[] namedGroups) {
        this.port = port;
        this.namedGroups = namedGroups;
    }

    public void start() throws Exception {
        SSLContext sslContext = createServerContext();
        SSLServerSocketFactory ssf = sslContext.getServerSocketFactory();
        try (SSLServerSocket serverSocket =
                     (SSLServerSocket) ssf.createServerSocket(port)) {

            SSLParameters params = serverSocket.getSSLParameters();
            // Configure TLS 1.3 only for cleaner results
            params.setProtocols(new String[] {"TLSv1.3"});
            if (namedGroups != null && namedGroups.length > 0) {
                params.setNamedGroups(namedGroups);
            }
            serverSocket.setSSLParameters(params);

            System.out.println("Server listening on port " + port);
            while (true) {
                SSLSocket socket = (SSLSocket) serverSocket.accept();
                handleClient(socket);
            }
        }
    }

    private SSLContext createServerContext() throws Exception {
        // Load server key and certificate from JKS (create with keytool beforehand)
        KeyStore ks = KeyStore.getInstance("JKS");
        // Load password from application.properties on the classpath (fallback to "changeit")
        Properties props = new Properties();
        try (InputStream propIn = HybridTlsServer.class.getClassLoader().getResourceAsStream("application.properties")) {
            if (propIn != null) {
                props.load(propIn);
            }
        }
        String password = props.getProperty("keystore.password", "changeit");
        char[] pwdChars = password.toCharArray();

        try (InputStream in = new FileInputStream("server.keystore")) {
            // delegate to helper that accepts a stream
            return createServerContext(in, pwdChars);
        } catch (FileNotFoundException fnfe) {
            System.out.println("server.keystore not found on classpath or filesystem; using default SSLContext (no key managers)");
            // fallback: initialize default SSLContext
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, null, new SecureRandom());
            // wipe password char array for safety
            java.util.Arrays.fill(pwdChars, '\0');
            return ctx;
        }
    }

    /* package-private for testing */
    SSLContext createServerContext(InputStream keystoreStream, char[] password) throws Exception {
        KeyStore ks = KeyStore.getInstance("JKS");
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
            System.out.println("Handshake time (ms): " + (end - start) / 1_000_000.0);
            System.out.println("Protocol: " + session.getProtocol());
            System.out.println("Cipher suite: " + session.getCipherSuite());

            // Simple echo to ensure data path works
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(s.getInputStream()));
            BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(s.getOutputStream()));
            String line = reader.readLine();
            writer.write("OK: " + line + "\n");
            writer.flush();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: HybridTlsServer classical|hybrid");
            System.exit(1);
        }
        String mode = args[0];

        String[] namedGroups;
        if ("classical".equalsIgnoreCase(mode)) {
            namedGroups = new String[] {"x25519"};
        } else if ("hybrid".equalsIgnoreCase(mode)) {
            namedGroups = new String[] {"X25519MLKEM768", "x25519"};
            // Replace "X25519MLKEM768" with the exact hybrid group name your JDK supports (see JEP 527 / docs).[web:29][web:45]
        } else {
            throw new IllegalArgumentException("Unknown mode: " + mode);
        }

        HybridTlsServer server = new HybridTlsServer(8443, namedGroups);
        server.start();
    }

}
