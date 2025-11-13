package io.github.laeubi.httpclient;

import static io.github.laeubi.httpclient.JavaHttpClientBase.httpServer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;

import javax.net.ssl.SSLParameters;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JavaHttpClientUpgradeTest extends JavaHttpClientBase {

	private static final Logger logger = LoggerFactory.getLogger(JavaHttpClientUpgradeTest.class);

	@BeforeAll
	public static void startServers() throws Exception {
		startHttpServer();
		startHttpsServer();
		startHttpsServerNoAlpn();
	}

	@BeforeEach
	public void resetServers() {
		if (httpServer != null) {
			httpServer.reset();
		}
		if (httpsServer != null) {
			httpsServer.reset();
		}
		if (httpsServerNoAlpn != null) {
			httpsServerNoAlpn.reset();
		}
	}

	@AfterAll
	public static void stopServers() {
		stopHttpServer();
	}

	@Test
	@DisplayName("HTTP/2 Upgrade over HTTP")
	public void testHttp2UpgradeOverHttp() throws Exception {
		logger.info("\n=== Testing HTTP/2 Upgrade over HTTP ===");

		HttpClient client = httpClient();

		HttpRequest request = HttpRequest.newBuilder()
				.uri(getHttpUrl()).GET()
				.build();

		logger.info("Sending HTTP request to ", getHttpUrl());
		HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

		logger.info("Response status: {}", response.statusCode());
		logger.info("Response version: {}", response.version());
		logger.info("Response body: {}", response.body());
		logger.info("Response headers: {}", response.headers().map());
		httpServer.assertHttpUpgrade();
		assertEquals(200, response.statusCode(), "Expected 200 OK response");
		assertNotNull(response.body(), "Response body should not be null");

		logger.info("=== HTTP/2 Upgrade test completed successfully ===\n");
	}

	@Test
	@DisplayName("HTTP/2 with Custom SSLParameters (attempting ALPN)")
	public void testHttp2WithCustomSSLParametersALPNAttempt() throws Exception {
		logger.info("\n=== Testing HTTP/2 with Custom SSLParameters (attempting ALPN) ===");

		// Create custom SSLParameters that attempt to preserve ALPN protocols
		SSLParameters sslParameters = new SSLParameters();
		// Disable hostname verification for testing with self-signed certificates
		sslParameters.setEndpointIdentificationAlgorithm(null);
		// Explicitly set ALPN protocols to support HTTP/2
		// NOTE: This demonstrates a limitation - setting custom SSLParameters
		// overrides HttpClient's internal ALPN configuration
		sslParameters.setApplicationProtocols(new String[] {"h2", "http/1.1"});

		HttpClient client = HttpClient.newBuilder()
				.version(HttpClient.Version.HTTP_2)
				.connectTimeout(Duration.ofSeconds(10))
				.sslContext(createTrustAllSslContext())
				.sslParameters(sslParameters)
				.build();

		HttpRequest request = HttpRequest.newBuilder()
				.uri(getHttpsUrl())
				.GET()
				.build();

		logger.info("Sending HTTPS request with custom SSLParameters to {}", getHttpsUrl());
		HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

		logger.info("Response status: {}", response.statusCode());
		logger.info("Response version: {}", response.version());
		logger.info("Response body: {}", response.body());
		assertEquals(200, response.statusCode(), "Expected 200 OK response");
		// This test documents the current behavior: Even when attempting to set ALPN protocols
		// manually in SSLParameters, the HttpClient doesn't properly negotiate HTTP/2
		// This is a known limitation when using custom SSLParameters
		assertEquals(HttpClient.Version.HTTP_1_1, response.version(), 
			"Custom SSLParameters override HttpClient's ALPN configuration, " +
			"causing fallback to HTTP/1.1 even when protocols are explicitly set");
		assertNotNull(response.body(), "Response body should not be null");

		logger.info("=== Custom SSLParameters test completed - demonstrates ALPN limitation ===\n");
	}

	@Test
	@DisplayName("HTTP/2 with Custom SSLParameters (no ALPN protocols set)")
	public void testHttp2WithCustomSSLParametersNoALPN() throws Exception {
		logger.info("\n=== Testing HTTP/2 with Custom SSLParameters (no ALPN protocols) ===");

		// Create custom SSLParameters without ALPN protocols
		// This simulates the scenario from PR #3 where custom SSLParameters interfered with ALPN
		SSLParameters sslParameters = new SSLParameters();
		sslParameters.setEndpointIdentificationAlgorithm(null);
		// NOT setting ALPN protocols - this will prevent HTTP/2 negotiation

		HttpClient client = HttpClient.newBuilder()
				.version(HttpClient.Version.HTTP_2)
				.connectTimeout(Duration.ofSeconds(10))
				.sslContext(createTrustAllSslContext())
				.sslParameters(sslParameters)
				.build();

		HttpRequest request = HttpRequest.newBuilder()
				.uri(getHttpsUrl())
				.GET()
				.build();

		logger.info("Sending HTTPS request with SSLParameters (no ALPN) to {}", getHttpsUrl());
		HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

		logger.info("Response status: {}", response.statusCode());
		logger.info("Response version: {}", response.version());
		logger.info("Response body: {}", response.body());
		assertEquals(200, response.statusCode(), "Expected 200 OK response");
		// When ALPN is not configured in SSLParameters, the connection falls back to HTTP/1.1
		assertEquals(HttpClient.Version.HTTP_1_1, response.version(), 
			"Should fall back to HTTP/1.1 when custom SSLParameters don't include ALPN protocols");
		assertNotNull(response.body(), "Response body should not be null");

		logger.info("=== Custom SSLParameters (no ALPN) test completed successfully ===\n");
	}

	@Test
	@DisplayName("HTTPS server without ALPN support - no HTTP/2 upgrade possible")
	public void testHttpsServerWithoutALPN() throws Exception {
		logger.info("\n=== Testing HTTPS server without ALPN support ===");

		// Use standard httpsClient without custom SSLParameters
		HttpClient client = httpsClient();

		HttpRequest request = HttpRequest.newBuilder()
				.uri(getHttpsUrlNoAlpn())
				.GET()
				.build();

		logger.info("Sending HTTPS request to server without ALPN support: {}", getHttpsUrlNoAlpn());
		HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

		logger.info("Response status: {}", response.statusCode());
		logger.info("Response version: {}", response.version());
		logger.info("Response body: {}", response.body());
		assertEquals(200, response.statusCode(), "Expected 200 OK response");
		// When server doesn't support ALPN, there is NO upgrade mechanism for HTTPS
		// The connection must use HTTP/1.1 - HTTP/2 upgrade is only possible over cleartext HTTP
		assertEquals(HttpClient.Version.HTTP_1_1, response.version(), 
			"Should use HTTP/1.1 when HTTPS server doesn't support ALPN - no upgrade mechanism exists for HTTPS");
		assertNotNull(response.body(), "Response body should not be null");

		logger.info("=== HTTPS server without ALPN test completed - no HTTP/2 upgrade possible ===\n");
	}


}
