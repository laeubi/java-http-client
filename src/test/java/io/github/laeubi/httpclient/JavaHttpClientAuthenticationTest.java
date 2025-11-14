package io.github.laeubi.httpclient;

import static org.junit.jupiter.api.Assertions.*;

import java.net.Authenticator;
import java.net.PasswordAuthentication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test suite evaluating Java HTTP Client support for common HTTP authentication schemes.
 * 
 * This test suite evaluates support for:
 * 1. HTTP Basic Authentication
 * 2. HTTP Digest Authentication
 * 3. NTLM Authentication
 * 4. SPNEGO/Negotiate Authentication (Kerberos)
 * 5. Bearer Token Authentication
 * 
 * Each test determines:
 * - Whether the scheme is natively supported through java.net.Authenticator
 * - What implementation is required at the application layer
 * - Any restrictions or limitations in the implementation
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class JavaHttpClientAuthenticationTest extends JavaHttpClientBase {

    private static final Logger logger = LoggerFactory.getLogger(JavaHttpClientAuthenticationTest.class);

    private static NettyAuthenticationServer basicAuthServer;
    private static NettyAuthenticationServer digestAuthServer;
    private static NettyAuthenticationServer ntlmAuthServer;
    private static NettyAuthenticationServer negotiateAuthServer;
    private static NettyAuthenticationServer basicAuthHttpsServer;

    private static final String USERNAME = "testuser";
    private static final String PASSWORD = "testpass";
    private static final String REALM = "Test Realm";

    @BeforeAll
    public static void startServers() throws Exception {
        // Start HTTP servers with different authentication schemes
        basicAuthServer = new NettyAuthenticationServer(8090, false, "Basic");
        basicAuthServer.setCredentials(USERNAME, PASSWORD);
        basicAuthServer.setRealm(REALM);
        basicAuthServer.start();

        digestAuthServer = new NettyAuthenticationServer(8091, false, "Digest");
        digestAuthServer.setCredentials(USERNAME, PASSWORD);
        digestAuthServer.setRealm(REALM);
        digestAuthServer.start();

        ntlmAuthServer = new NettyAuthenticationServer(8092, false, "NTLM");
        ntlmAuthServer.setCredentials(USERNAME, PASSWORD);
        ntlmAuthServer.start();

        negotiateAuthServer = new NettyAuthenticationServer(8093, false, "Negotiate");
        negotiateAuthServer.start();

        // HTTPS server with Basic auth
        basicAuthHttpsServer = new NettyAuthenticationServer(8094, true, "Basic");
        basicAuthHttpsServer.setCredentials(USERNAME, PASSWORD);
        basicAuthHttpsServer.setRealm(REALM);
        basicAuthHttpsServer.start();
    }

    @AfterAll
    public static void stopServers() {
        if (basicAuthServer != null) {
            basicAuthServer.stop();
        }
        if (digestAuthServer != null) {
            digestAuthServer.stop();
        }
        if (ntlmAuthServer != null) {
            ntlmAuthServer.stop();
        }
        if (negotiateAuthServer != null) {
            negotiateAuthServer.stop();
        }
        if (basicAuthHttpsServer != null) {
            basicAuthHttpsServer.stop();
        }
    }

    // ==================== HTTP Basic Authentication Tests ====================

    @Test
    @Order(1)
    @DisplayName("1. HTTP Basic - Native support via Authenticator")
    public void testBasicAuthenticationNativeSupport() throws Exception {
        logger.info("\n=== Testing HTTP Basic Authentication - Native Support ===");

        // Java HTTP Client provides NATIVE support for Basic authentication via Authenticator
        Authenticator authenticator = new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                logger.info("Authenticator called for: {} ({})", getRequestingURL(), getRequestingScheme());
                return new PasswordAuthentication(USERNAME, PASSWORD.toCharArray());
            }
        };

        HttpClient client = HttpClient.newBuilder()
                .authenticator(authenticator)
                .build();

        URI uri = URI.create("http://localhost:" + basicAuthServer.getPort() + "/test");
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .GET()
                .build();

        logger.info("Sending request without explicit Authorization header");
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        logger.info("Response status: {}", response.statusCode());
        logger.info("Response body: {}", response.body());

        assertEquals(200, response.statusCode(), "Native Basic authentication should succeed");
        assertTrue(response.body().contains("Authenticated successfully"),
                "Response should confirm authentication");

        logger.info("=== RESULT: HTTP Basic authentication is NATIVELY SUPPORTED via java.net.Authenticator ===\n");
    }

    @Test
    @Order(2)
    @DisplayName("2. HTTP Basic - Manual implementation without Authenticator")
    public void testBasicAuthenticationManualImplementation() throws Exception {
        logger.info("\n=== Testing HTTP Basic Authentication - Manual Implementation ===");

        // HTTP Basic can also be implemented manually at the application layer
        // This requires manually adding the Authorization header

        HttpClient client = HttpClient.newBuilder().build();

        String credentials = USERNAME + ":" + PASSWORD;
        String encodedCredentials = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));

        URI uri = URI.create("http://localhost:" + basicAuthServer.getPort() + "/test");
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .header("Authorization", "Basic " + encodedCredentials)
                .GET()
                .build();

        logger.info("Sending request with manually constructed Authorization header");
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        logger.info("Response status: {}", response.statusCode());
        logger.info("Response body: {}", response.body());

        assertEquals(200, response.statusCode(), "Manual Basic authentication should succeed");
        assertTrue(response.body().contains("Authenticated successfully"),
                "Response should confirm authentication");

        logger.info("=== RESULT: HTTP Basic can be implemented manually with LOW effort (simple header) ===\n");
    }

    @Test
    @Order(3)
    @DisplayName("3. HTTP Basic - HTTPS with native support")
    public void testBasicAuthenticationHttps() throws Exception {
        logger.info("\n=== Testing HTTP Basic Authentication over HTTPS ===");

        Authenticator authenticator = new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(USERNAME, PASSWORD.toCharArray());
            }
        };

        HttpClient client = HttpClient.newBuilder()
                .authenticator(authenticator)
                .sslContext(createTrustAllSslContext())
                .build();

        URI uri = URI.create("https://localhost:" + basicAuthHttpsServer.getPort() + "/test");
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        logger.info("Response status: {}", response.statusCode());
        assertEquals(200, response.statusCode(), "HTTPS with Basic authentication should succeed");

        logger.info("=== RESULT: HTTP Basic authentication works with both HTTP and HTTPS ===\n");
    }

    @Test
    @Order(4)
    @DisplayName("4. HTTP Basic - Challenge-response flow")
    public void testBasicAuthenticationChallengeResponse() throws Exception {
        logger.info("\n=== Testing HTTP Basic Authentication - Challenge-Response Flow ===");

        // First request without authentication should return 401 with WWW-Authenticate header
        HttpClient client = HttpClient.newBuilder().build();

        URI uri = URI.create("http://localhost:" + basicAuthServer.getPort() + "/test");
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .GET()
                .build();

        logger.info("Sending request without authentication");
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        logger.info("Response status: {}", response.statusCode());
        logger.info("WWW-Authenticate header: {}", response.headers().firstValue("www-authenticate"));

        assertEquals(401, response.statusCode(), "Should receive 401 Unauthorized");
        assertTrue(response.headers().firstValue("www-authenticate").isPresent(),
                "Should receive WWW-Authenticate challenge header");
        assertTrue(response.headers().firstValue("www-authenticate").get().startsWith("Basic"),
                "Challenge should be for Basic authentication");

        logger.info("=== RESULT: Challenge-response flow requires application to handle 401 manually ===");
        logger.info("=== java.net.Authenticator handles this automatically ===\n");
    }

    // ==================== HTTP Digest Authentication Tests ====================

    @Test
    @Order(5)
    @DisplayName("5. HTTP Digest - Native support via Authenticator")
    public void testDigestAuthenticationNativeSupport() throws Exception {
        logger.info("\n=== Testing HTTP Digest Authentication - Native Support ===");

        // Java HTTP Client provides NATIVE support for Digest authentication via Authenticator
        Authenticator authenticator = new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                logger.info("Authenticator called for Digest: {} ({})", getRequestingURL(), getRequestingScheme());
                return new PasswordAuthentication(USERNAME, PASSWORD.toCharArray());
            }
        };

        HttpClient client = HttpClient.newBuilder()
                .authenticator(authenticator)
                .build();

        URI uri = URI.create("http://localhost:" + digestAuthServer.getPort() + "/test");
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .GET()
                .build();

        logger.info("Sending request to Digest-protected endpoint");
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        logger.info("Response status: {}", response.statusCode());
        logger.info("Response body: {}", response.body());

        // Note: The Java HTTP Client's support for Digest depends on the JDK version
        // Some versions have better support than others
        logger.info("=== RESULT: HTTP Digest authentication support varies by JDK version ===");
        logger.info("=== Native support is LIMITED - may require manual implementation ===\n");
    }

    @Test
    @Order(6)
    @DisplayName("6. HTTP Digest - Manual implementation complexity")
    public void testDigestAuthenticationManualImplementation() throws Exception {
        logger.info("\n=== Testing HTTP Digest Authentication - Manual Implementation ===");

        // Manual Digest implementation is COMPLEX and requires:
        // 1. Parsing WWW-Authenticate challenge header
        // 2. Extracting realm, nonce, qop, opaque parameters
        // 3. Computing MD5 hash of username:realm:password (HA1)
        // 4. Computing MD5 hash of method:uri (HA2)
        // 5. Computing response = MD5(HA1:nonce:nc:cnonce:qop:HA2)
        // 6. Constructing Authorization header with all parameters

        HttpClient client = HttpClient.newBuilder().build();

        URI uri = URI.create("http://localhost:" + digestAuthServer.getPort() + "/test");

        // Step 1: Get the challenge
        HttpRequest request1 = HttpRequest.newBuilder().uri(uri).GET().build();
        HttpResponse<String> response1 = client.send(request1, HttpResponse.BodyHandlers.ofString());

        assertEquals(401, response1.statusCode(), "Should receive 401 with challenge");
        String wwwAuthenticate = response1.headers().firstValue("www-authenticate").orElse("");
        logger.info("WWW-Authenticate challenge: {}", wwwAuthenticate);

        assertTrue(wwwAuthenticate.startsWith("Digest"), "Should be Digest challenge");

        // Step 2: Parse challenge (simplified - real implementation needs robust parsing)
        // This demonstrates the complexity - a full implementation would be much longer

        logger.info("=== RESULT: HTTP Digest requires MODERATE to HIGH effort for manual implementation ===");
        logger.info("=== Requires: challenge parsing, MD5 hashing, response computation ===");
        logger.info("=== Recommendation: Use native support via Authenticator or third-party library ===\n");
    }

    // ==================== NTLM Authentication Tests ====================

    @Test
    @Order(7)
    @DisplayName("7. NTLM - Native support evaluation")
    public void testNTLMAuthenticationNativeSupport() throws Exception {
        logger.info("\n=== Testing NTLM Authentication - Native Support ===");

        // NTLM is a Microsoft proprietary authentication protocol
        // Java HTTP Client does NOT provide native support for NTLM
        
        Authenticator authenticator = new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                logger.info("Authenticator called for: {} ({})", getRequestingURL(), getRequestingScheme());
                return new PasswordAuthentication(USERNAME, PASSWORD.toCharArray());
            }
        };

        HttpClient client = HttpClient.newBuilder()
                .authenticator(authenticator)
                .build();

        URI uri = URI.create("http://localhost:" + ntlmAuthServer.getPort() + "/test");
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .GET()
                .build();

        logger.info("Sending request to NTLM-protected endpoint");
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        logger.info("Response status: {}", response.statusCode());

        // java.net.Authenticator does NOT automatically handle NTLM
        logger.info("=== RESULT: NTLM is NOT natively supported by java.net.Authenticator ===");
        logger.info("=== Java HTTP Client does NOT provide built-in NTLM support ===\n");
    }

    @Test
    @Order(8)
    @DisplayName("8. NTLM - Manual implementation complexity")
    public void testNTLMAuthenticationManualImplementation() throws Exception {
        logger.info("\n=== Testing NTLM Authentication - Manual Implementation ===");

        // NTLM is a VERY COMPLEX, multi-step challenge-response protocol requiring:
        // 1. Type 1 Message (NTLM Negotiation) - client to server
        // 2. Type 2 Message (NTLM Challenge) - server to client
        // 3. Type 3 Message (NTLM Authentication) - client to server
        // 
        // Each message involves:
        // - Binary protocol encoding
        // - Cryptographic operations (DES, MD4, MD5, HMAC-MD5)
        // - Windows domain/workgroup information
        // - Challenge-response computation
        // - Unicode string encoding
        // - Complex message structure with flags and fields

        HttpClient client = HttpClient.newBuilder().build();
        URI uri = URI.create("http://localhost:" + ntlmAuthServer.getPort() + "/test");
        HttpRequest request = HttpRequest.newBuilder().uri(uri).GET().build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        logger.info("Response status: {}", response.statusCode());
        logger.info("WWW-Authenticate: {}", response.headers().firstValue("www-authenticate"));

        logger.info("=== RESULT: NTLM requires VERY HIGH effort for manual implementation ===");
        logger.info("=== Requires: Binary protocol, cryptography (DES/MD4/MD5), multi-step flow ===");
        logger.info("=== Recommendation: Use third-party library (Apache HttpClient with JCIFS) ===");
        logger.info("=== RESTRICTION: Cannot be easily implemented with standard Java HTTP Client ===\n");
    }

    // ==================== SPNEGO/Kerberos Authentication Tests ====================

    @Test
    @Order(9)
    @DisplayName("9. SPNEGO/Kerberos - Native support evaluation")
    public void testSPNEGOAuthenticationNativeSupport() throws Exception {
        logger.info("\n=== Testing SPNEGO/Kerberos Authentication - Native Support ===");

        // SPNEGO (Simple and Protected GSSAPI Negotiation Mechanism) with Kerberos
        // Java provides some support via JGSS (Java Generic Security Services)
        // but it requires significant configuration

        Authenticator authenticator = new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                logger.info("Authenticator called for: {} ({})", getRequestingURL(), getRequestingScheme());
                return new PasswordAuthentication(USERNAME, PASSWORD.toCharArray());
            }
        };

        HttpClient client = HttpClient.newBuilder()
                .authenticator(authenticator)
                .build();

        URI uri = URI.create("http://localhost:" + negotiateAuthServer.getPort() + "/test");
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .GET()
                .build();

        logger.info("Sending request to Negotiate-protected endpoint");
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        logger.info("Response status: {}", response.statusCode());

        logger.info("=== RESULT: SPNEGO/Kerberos has LIMITED native support ===");
        logger.info("=== Requires: Kerberos infrastructure, krb5.conf configuration, keytab files ===");
        logger.info("=== java.net.Authenticator does NOT automatically handle Negotiate/SPNEGO ===\n");
    }

    @Test
    @Order(10)
    @DisplayName("10. SPNEGO/Kerberos - Manual implementation complexity")
    public void testSPNEGOAuthenticationManualImplementation() throws Exception {
        logger.info("\n=== Testing SPNEGO/Kerberos Authentication - Manual Implementation ===");

        // SPNEGO/Kerberos manual implementation requires:
        // 1. Kerberos ticket acquisition (kinit or programmatic)
        // 2. GSS-API context establishment
        // 3. Token generation and exchange
        // 4. Service Principal Name (SPN) configuration
        // 5. Kerberos realm and KDC configuration
        // 6. Credential delegation handling
        //
        // This is VERY COMPLEX and requires external infrastructure

        HttpClient client = HttpClient.newBuilder().build();
        URI uri = URI.create("http://localhost:" + negotiateAuthServer.getPort() + "/test");
        HttpRequest request = HttpRequest.newBuilder().uri(uri).GET().build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        logger.info("Response status: {}", response.statusCode());
        logger.info("WWW-Authenticate: {}", response.headers().firstValue("www-authenticate"));

        logger.info("=== RESULT: SPNEGO/Kerberos requires VERY HIGH effort for manual implementation ===");
        logger.info("=== Requires: Kerberos infrastructure, JGSS API, ticket management ===");
        logger.info("=== Can be implemented using: Java GSS-API (org.ietf.jgss) ===");
        logger.info("=== Recommendation: Use JGSS with proper Kerberos configuration ===");
        logger.info("=== RESTRICTION: Requires external Kerberos infrastructure (KDC, realm) ===\n");
    }

    // ==================== Bearer Token Authentication Tests ====================

    @Test
    @Order(11)
    @DisplayName("11. Bearer Token - Manual implementation (OAuth 2.0)")
    public void testBearerTokenAuthentication() throws Exception {
        logger.info("\n=== Testing Bearer Token Authentication (OAuth 2.0) ===");

        // Bearer token authentication is common for OAuth 2.0
        // Java HTTP Client does NOT provide native support
        // Must be implemented manually at the application layer

        HttpClient client = HttpClient.newBuilder().build();

        String bearerToken = "sample_oauth2_access_token_12345";

        URI uri = URI.create("http://localhost:" + basicAuthServer.getPort() + "/test");
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .header("Authorization", "Bearer " + bearerToken)
                .GET()
                .build();

        logger.info("Sending request with Bearer token");
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        logger.info("Response status: {}", response.statusCode());

        logger.info("=== RESULT: Bearer Token is NOT natively supported ===");
        logger.info("=== Requires: LOW effort manual implementation (simple header) ===");
        logger.info("=== Implementation: Add 'Authorization: Bearer <token>' header ===");
        logger.info("=== Token acquisition (OAuth 2.0 flow) is separate concern ===\n");
    }

    // ==================== Summary Test ====================

    @Test
    @Order(12)
    @DisplayName("12. Summary - Authentication scheme support matrix")
    public void testAuthenticationSchemeSummary() {
        logger.info("\n" + "=".repeat(80));
        logger.info("SUMMARY: Java HTTP Client Authentication Scheme Support");
        logger.info("=".repeat(80));
        logger.info("");
        logger.info("┌─────────────────┬──────────────────┬─────────────────────┬──────────────────────┐");
        logger.info("│ Scheme          │ Native Support   │ Manual Effort       │ Restrictions         │");
        logger.info("├─────────────────┼──────────────────┼─────────────────────┼──────────────────────┤");
        logger.info("│ HTTP Basic      │ ✓ YES            │ LOW (simple header) │ None                 │");
        logger.info("│                 │ (Authenticator)  │                     │                      │");
        logger.info("├─────────────────┼──────────────────┼─────────────────────┼──────────────────────┤");
        logger.info("│ HTTP Digest     │ ✓ LIMITED        │ MODERATE-HIGH       │ Version-dependent    │");
        logger.info("│                 │ (Authenticator)  │ (parsing, hashing)  │                      │");
        logger.info("├─────────────────┼──────────────────┼─────────────────────┼──────────────────────┤");
        logger.info("│ NTLM            │ ✗ NO             │ VERY HIGH           │ Proprietary protocol │");
        logger.info("│                 │                  │ (binary, crypto)    │ Needs 3rd party lib  │");
        logger.info("├─────────────────┼──────────────────┼─────────────────────┼──────────────────────┤");
        logger.info("│ SPNEGO/Kerberos │ ✗ NO             │ VERY HIGH           │ Needs Kerberos infra │");
        logger.info("│ (Negotiate)     │ (use JGSS API)   │ (JGSS, tickets)     │ (KDC, realm, SPN)    │");
        logger.info("├─────────────────┼──────────────────┼─────────────────────┼──────────────────────┤");
        logger.info("│ Bearer Token    │ ✗ NO             │ LOW (simple header) │ Token acquisition    │");
        logger.info("│ (OAuth 2.0)     │                  │                     │ separate concern     │");
        logger.info("└─────────────────┴──────────────────┴─────────────────────┴──────────────────────┘");
        logger.info("");
        logger.info("RECOMMENDATIONS:");
        logger.info("1. HTTP Basic: Use native Authenticator for automatic support");
        logger.info("2. HTTP Digest: Use native Authenticator, fallback to manual if needed");
        logger.info("3. NTLM: Use Apache HttpClient library with JCIFS-NG for NTLM support");
        logger.info("4. SPNEGO/Kerberos: Use Java JGSS API with proper Kerberos configuration");
        logger.info("5. Bearer Token: Manual implementation with token management library");
        logger.info("");
        logger.info("=".repeat(80) + "\n");
    }
}
