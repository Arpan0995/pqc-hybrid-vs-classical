package bench;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

public class HybridTlsServerTest {

    @Test
    void createServerContext_returnsNonNullSslContext() throws Exception {
        HybridTlsServer srv = new HybridTlsServer(0, new String[]{"x25519"});
        Method m = HybridTlsServer.class.getDeclaredMethod("createServerContext");
        m.setAccessible(true);
        Object ctxObj = m.invoke(srv);
        assertNotNull(ctxObj, "createServerContext should not return null");
        assertTrue(ctxObj instanceof SSLContext, "returned object should be an SSLContext");

        SSLContext ctx = (SSLContext) ctxObj;
        assertNotNull(ctx.getServerSocketFactory(), "SSLContext should provide a server socket factory");
    }

    @Test
    void sslContext_canCreateServerSocket() throws Exception {
        HybridTlsServer srv = new HybridTlsServer(0, null);
        Method m = HybridTlsServer.class.getDeclaredMethod("createServerContext");
        m.setAccessible(true);
        SSLContext ctx = (SSLContext) m.invoke(srv);

        SSLServerSocketFactory ssf = ctx.getServerSocketFactory();
        assertNotNull(ssf, "Server socket factory must not be null");

        // create a server socket on an ephemeral port to ensure the factory works
        try (SSLServerSocket s = (SSLServerSocket) ssf.createServerSocket(0)) {
            assertTrue(s.getLocalPort() > 0, "Bound server socket should have a non-zero port");
        }
    }
}

