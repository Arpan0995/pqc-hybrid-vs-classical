package bench;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class HybridTlsClientTest {

    @BeforeAll
    static void setupProvider() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    @AfterAll
    static void cleanupKeystore() {
        File f = new File("target/server.keystore");
        if (f.exists()) f.delete();
    }

    private void writeTempKeystore() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();

        long now = System.currentTimeMillis();
        Date notBefore = new Date(now - 1000L * 60 * 60);
        Date notAfter = new Date(now + 1000L * 60 * 60 * 24);
        X500Name dn = new X500Name("CN=localhost");
        BigInteger serial = BigInteger.valueOf(now);

        JcaX509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
                dn, serial, notBefore, notAfter, dn, kp.getPublic());
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(kp.getPrivate());
        X509Certificate cert = new JcaX509CertificateConverter().getCertificate(certBuilder.build(signer));

        char[] pass = "changeit".toCharArray();
        KeyStore ks = KeyStore.getInstance("JKS");
        ks.load(null, null);
        ks.setKeyEntry("alias", kp.getPrivate(), pass, new java.security.cert.Certificate[]{cert});

        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        ks.store(bout, pass);

        File targetDir = new File("target");
        if (!targetDir.exists()) targetDir.mkdirs();
        File keystoreFile = new File(targetDir, "server.keystore");
        try (FileOutputStream fos = new FileOutputStream(keystoreFile)) {
            fos.write(bout.toByteArray());
            fos.flush();
        }
    }

    @Test
    void singleHandshake_succeeds() throws Exception {
        writeTempKeystore();

        // pick free port
        int port;
        try (ServerSocket ss = new ServerSocket(0)) { port = ss.getLocalPort(); }

        HybridTlsServer server = new HybridTlsServer(port, new String[]{"x25519"});
        Thread t = new Thread(() -> {
            try { server.start(); } catch (Exception e) { throw new RuntimeException(e); }
        }, "hts-test-single");
        t.setDaemon(true);
        t.start();

        // wait until server listening
        boolean listening = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("localhost", port), 200);
                listening = true; break;
            } catch (Exception ex) { Thread.sleep(50); }
        }
        assertTrue(listening, "Server should be listening");

        // run single handshake via client helper
        HybridTlsClient client = new HybridTlsClient("localhost", port, new String[]{"x25519"});
        double ms = client.runSingleHandshake();
        assertTrue(ms >= 0.0, "Handshake time should be non-negative");

        server.stop();
        t.join(1000);
        assertFalse(t.isAlive());
    }

    @Test
    void concurrentBenchmark_runsWithoutThrowing() throws Exception {
        writeTempKeystore();

        int port;
        try (ServerSocket ss = new ServerSocket(0)) { port = ss.getLocalPort(); }

        HybridTlsServer server = new HybridTlsServer(port, new String[]{"x25519"});
        Thread t = new Thread(() -> {
            try { server.start(); } catch (Exception e) { throw new RuntimeException(e); }
        }, "hts-test-concurrent");
        t.setDaemon(true);
        t.start();

        // wait until server listening
        boolean listening = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("localhost", port), 200);
                listening = true; break;
            } catch (Exception ex) { Thread.sleep(50); }
        }
        assertTrue(listening, "Server should be listening");

        HybridTlsClient client = new HybridTlsClient("localhost", port, new String[]{"x25519"});

        // run a small concurrent benchmark (2 threads x 5 runs each)
        assertDoesNotThrow(() -> client.runConcurrentBenchmark(2, 5));

        server.stop();
        t.join(1000);
        assertFalse(t.isAlive());
    }
}

