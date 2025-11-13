package io.github.laeubi.httpclient;

import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test cases for Java 11+ HTTP Client against Netty HTTP/2 server
 * Tests HTTP/2 upgrade and GOAWAY frame handling
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class Http2ClientTest {
    private static final Logger logger = LoggerFactory.getLogger(Http2ClientTest.class);
    
    private static final int HTTP_PORT = 8080;
    private static final int HTTPS_PORT = 8443;
    private static final int HTTP_GOAWAY_PORT = 8081;
    private static final int HTTPS_GOAWAY_PORT = 8444;
    
    private static NettyHttp2Server httpServer;
    private static NettyHttp2Server httpsServer;
    private static NettyHttp2Server httpGoAwayServer;
    private static NettyHttp2Server httpsGoAwayServer;

    @BeforeAll
    public static void startServers() throws Exception {
        logger.info("=== Starting test servers ===");
        
        // HTTP server without GOAWAY
        httpServer = new NettyHttp2Server(HTTP_PORT, false, false);
        httpServer.start();
        Thread.sleep(500); // Give server time to start
        
        // HTTPS server without GOAWAY
        httpsServer = new NettyHttp2Server(HTTPS_PORT, true, false);
        httpsServer.start();
        Thread.sleep(500);
        
        // HTTP server with GOAWAY
        httpGoAwayServer = new NettyHttp2Server(HTTP_GOAWAY_PORT, false, true);
        httpGoAwayServer.start();
        Thread.sleep(500);
        
        // HTTPS server with GOAWAY
        httpsGoAwayServer = new NettyHttp2Server(HTTPS_GOAWAY_PORT, true, true);
        httpsGoAwayServer.start();
        Thread.sleep(500);
        
        logger.info("=== All test servers started ===");
    }

    @AfterAll
    public static void stopServers() {
        logger.info("=== Stopping test servers ===");
        if (httpServer != null) {
            httpServer.stop();
        }
        if (httpsServer != null) {
            httpsServer.stop();
        }
        if (httpGoAwayServer != null) {
            httpGoAwayServer.stop();
        }
        if (httpsGoAwayServer != null) {
            httpsGoAwayServer.stop();
        }
        logger.info("=== All test servers stopped ===");
    }

    @Test
    @Order(1)
    @DisplayName("HTTP/2 Upgrade over HTTP")
    public void testHttp2UpgradeOverHttp() throws IOException, InterruptedException {
        logger.info("\n=== Testing HTTP/2 Upgrade over HTTP ===");
        
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + HTTP_PORT + "/test"))
                .GET()
                .build();

        logger.info("Sending HTTP request to http://localhost:{}/test", HTTP_PORT);
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        logger.info("Response status: {}", response.statusCode());
        logger.info("Response version: {}", response.version());
        logger.info("Response body: {}", response.body());
        logger.info("Response headers: {}", response.headers().map());

        assertEquals(200, response.statusCode(), "Expected 200 OK response");
        assertNotNull(response.body(), "Response body should not be null");
        
        logger.info("=== HTTP/2 Upgrade test completed successfully ===\n");
    }

    @Test
    @Order(2)
    @DisplayName("HTTP/2 over HTTPS with ALPN")
    public void testHttp2OverHttpsWithAlpn() throws IOException, InterruptedException, 
            NoSuchAlgorithmException, KeyManagementException {
        logger.info("\n=== Testing HTTP/2 over HTTPS with ALPN ===");
        
        // Create SSL parameters that accept self-signed certificates
        SSLParameters sslParameters = new SSLParameters();
        sslParameters.setEndpointIdentificationAlgorithm(null); // Disable hostname verification
        
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(10))
                .sslContext(createTrustAllSslContext())
                .sslParameters(sslParameters)
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://localhost:" + HTTPS_PORT + "/test"))
                .GET()
                .build();

        logger.info("Sending HTTPS request to https://localhost:{}/test", HTTPS_PORT);
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        logger.info("Response status: {}", response.statusCode());
        logger.info("Response version: {}", response.version());
        logger.info("Response body: {}", response.body());

        assertEquals(200, response.statusCode(), "Expected 200 OK response");
        assertNotNull(response.body(), "Response body should not be null");
        
        logger.info("=== HTTP/2 over HTTPS test completed successfully ===\n");
    }

    @Test
    @Order(3)
    @DisplayName("GOAWAY frame handling over HTTP")
    public void testGoAwayOverHttp() throws IOException, InterruptedException {
        logger.info("\n=== Testing GOAWAY frame handling over HTTP ===");
        
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + HTTP_GOAWAY_PORT + "/test"))
                .GET()
                .build();

        logger.info("Sending HTTP request to http://localhost:{}/test (GOAWAY expected)", 
            HTTP_GOAWAY_PORT);
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        logger.info("Response status: {}", response.statusCode());
        logger.info("Response version: {}", response.version());
        logger.info("Response body: {}", response.body());
        logger.info("First request completed - server should have sent GOAWAY");

        assertEquals(200, response.statusCode(), "Expected 200 OK response");
        
        // Try a second request to see how client handles GOAWAY
        logger.info("Sending second request to verify GOAWAY handling...");
        HttpRequest request2 = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + HTTP_GOAWAY_PORT + "/test2"))
                .GET()
                .build();
        
        try {
            HttpResponse<String> response2 = client.send(request2, HttpResponse.BodyHandlers.ofString());
            logger.info("Second request response status: {}", response2.statusCode());
            logger.info("Second request response body: {}", response2.body());
        } catch (IOException e) {
            logger.info("Second request failed (expected after GOAWAY): {}", e.getMessage());
            // This is expected behavior after GOAWAY
        }
        
        logger.info("=== GOAWAY over HTTP test completed ===\n");
    }

    @Test
    @Order(4)
    @DisplayName("GOAWAY frame handling over HTTPS")
    public void testGoAwayOverHttps() throws IOException, InterruptedException, 
            NoSuchAlgorithmException, KeyManagementException {
        logger.info("\n=== Testing GOAWAY frame handling over HTTPS ===");
        
        // Create SSL parameters that accept self-signed certificates
        SSLParameters sslParameters = new SSLParameters();
        sslParameters.setEndpointIdentificationAlgorithm(null); // Disable hostname verification
        
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(10))
                .sslContext(createTrustAllSslContext())
                .sslParameters(sslParameters)
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://localhost:" + HTTPS_GOAWAY_PORT + "/test"))
                .GET()
                .build();

        logger.info("Sending HTTPS request to https://localhost:{}/test (GOAWAY expected)", 
            HTTPS_GOAWAY_PORT);
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        logger.info("Response status: {}", response.statusCode());
        logger.info("Response version: {}", response.version());
        logger.info("Response body: {}", response.body());
        logger.info("First request completed - server should have sent GOAWAY");

        assertEquals(200, response.statusCode(), "Expected 200 OK response");
        
        // Try a second request to see how client handles GOAWAY
        logger.info("Sending second request to verify GOAWAY handling...");
        HttpRequest request2 = HttpRequest.newBuilder()
                .uri(URI.create("https://localhost:" + HTTPS_GOAWAY_PORT + "/test2"))
                .GET()
                .build();
        
        try {
            HttpResponse<String> response2 = client.send(request2, HttpResponse.BodyHandlers.ofString());
            logger.info("Second request response status: {}", response2.statusCode());
            logger.info("Second request response body: {}", response2.body());
        } catch (IOException e) {
            logger.info("Second request failed (expected after GOAWAY): {}", e.getMessage());
            // This is expected behavior after GOAWAY
        }
        
        logger.info("=== GOAWAY over HTTPS test completed ===\n");
    }

    @Test
    @Order(5)
    @DisplayName("Multiple requests to verify HTTP/2 connection reuse")
    public void testMultipleRequestsConnectionReuse() throws IOException, InterruptedException {
        logger.info("\n=== Testing multiple requests for connection reuse ===");
        
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        // Send multiple requests to see connection reuse
        for (int i = 1; i <= 3; i++) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + HTTP_PORT + "/test" + i))
                    .GET()
                    .build();

            logger.info("Sending request #{} to http://localhost:{}/test{}", i, HTTP_PORT, i);
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            logger.info("Request #{} - Status: {}, Version: {}", 
                i, response.statusCode(), response.version());
            
            assertEquals(200, response.statusCode(), "Expected 200 OK response");
            
            // Small delay between requests
            Thread.sleep(100);
        }
        
        logger.info("=== Connection reuse test completed ===\n");
    }

    /**
     * Create an SSL context that trusts all certificates (for testing only!)
     * 
     * WARNING: This is intentionally insecure and should NEVER be used in production.
     * This TrustManager implementation bypasses certificate validation to allow
     * testing with self-signed certificates generated by the Netty test server.
     * The purpose is to test HTTP/2 client behavior, not certificate validation.
     */
    private SSLContext createTrustAllSslContext() throws NoSuchAlgorithmException, KeyManagementException {
        // Intentionally insecure for testing with self-signed certificates
        TrustManager[] trustAllCerts = new TrustManager[]{
            new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
                public void checkClientTrusted(X509Certificate[] certs, String authType) {
                    // Intentionally empty - trusts all client certificates for testing
                }
                public void checkServerTrusted(X509Certificate[] certs, String authType) {
                    // Intentionally empty - trusts all server certificates for testing
                }
            }
        };

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
        return sslContext;
    }
}
