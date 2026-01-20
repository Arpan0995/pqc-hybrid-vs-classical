package bench;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

public class HybridTlsServerKeystoreTest {

    @BeforeAll
    static void setupProvider() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    @Test
    void createServerContext_withGeneratedKeystore() throws Exception {
        // generate a key pair
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();

        // create a self-signed cert
        long now = System.currentTimeMillis();
        Date notBefore = new Date(now - 1000L * 60 * 60);
        Date notAfter = new Date(now + 1000L * 60 * 60 * 24);
        X500Name dn = new X500Name("CN=Test");
        BigInteger serial = BigInteger.valueOf(now);

        X509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
                dn, serial, notBefore, notAfter, dn, kp.getPublic());

        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(kp.getPrivate());
        X509Certificate cert = new JcaX509CertificateConverter().getCertificate(certBuilder.build(signer));

        // put into a keystore
        char[] pass = "testpass".toCharArray();
        KeyStore ks = KeyStore.getInstance("JKS");
        ks.load(null, null);
        ks.setKeyEntry("alias", kp.getPrivate(), pass, new java.security.cert.Certificate[]{cert});

        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        ks.store(bout, pass);

        // call the package-private helper with the generated keystore
        HybridTlsServer srv = new HybridTlsServer(0, null);
        X509Certificate[] certs = new X509Certificate[]{cert};
        try (ByteArrayInputStream bin = new ByteArrayInputStream(bout.toByteArray())) {
            assertDoesNotThrow(() -> {
                srv.createServerContext(bin, pass);
            });
        }
    }
}

