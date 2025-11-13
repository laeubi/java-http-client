package io.github.laeubi.httpclient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JavaHttpClientConnectionReuseTest extends JavaHttpClientBase {

	private static final Logger logger = LoggerFactory.getLogger(JavaHttpClientConnectionReuseTest.class);

	@BeforeAll
	public static void startServers() throws Exception {
		startHttpsServerNoGoaway();
	}

	@AfterAll
	public static void stopServers() {
		stopHttpServer();
	}

	@Test
	@DisplayName("Test connection reuse tracking with nonce")
	public void testConnectionReuseWithNonce() throws Exception {
		logger.info("\n=== Testing connection reuse tracking ===");
		
		// Create a single HTTP client that will pool connections
		HttpClient client = httpsClient();
		
		// First request - should get a nonce
		HttpRequest request1 = HttpRequest.newBuilder()
				.uri(getHttpsUrlNoGoaway())
				.GET()
				.build();
		
		logger.info("Sending first request to establish connection");
		HttpResponse<String> response1 = client.send(request1, HttpResponse.BodyHandlers.ofString());
		
		assertEquals(200, response1.statusCode(), "First request should succeed");
		String nonce = response1.headers().firstValue("x-connection-nonce").orElse(null);
		assertNotNull(nonce, "Server should return a connection nonce");
		logger.info("Received nonce: {}", nonce);
		
		// Second request - send the nonce back to check if connection is reused
		HttpRequest request2 = HttpRequest.newBuilder()
				.uri(getHttpsUrlNoGoaway())
				.header("x-expected-nonce", nonce)
				.GET()
				.build();
		
		logger.info("Sending second request with nonce to check connection reuse");
		HttpResponse<String> response2 = client.send(request2, HttpResponse.BodyHandlers.ofString());
		
		// Should be 200 if connection was reused, 444 if new connection
		int status2 = response2.statusCode();
		logger.info("Second request status: {}", status2);
		assertEquals(200, status2, "Connection should be reused (status 200), got: " + status2);
		
		logger.info("=== Connection reuse test completed successfully ===\n");
	}

	@Test
	@DisplayName("Test GOAWAY when stale connection is reused")
	public void testGoawayOnStaleConnectionReuse() throws Exception {
		logger.info("\n=== Testing GOAWAY on stale connection reuse ===");
		
		// Reset server state
		httpsServerNoGoaway.reset();
		
		// Create a single HTTP client that will pool connections
		HttpClient client = httpsClient();
		
		// First request - establish connection and get nonce
		HttpRequest request1 = HttpRequest.newBuilder()
				.uri(getHttpsUrlNoGoaway())
				.GET()
				.build();
		
		logger.info("Step 1: Sending first request to establish connection");
		HttpResponse<String> response1 = client.send(request1, HttpResponse.BodyHandlers.ofString());
		
		assertEquals(200, response1.statusCode(), "First request should succeed");
		String nonce = response1.headers().firstValue("x-connection-nonce").orElse(null);
		assertNotNull(nonce, "Server should return a connection nonce");
		logger.info("Received nonce: {}", nonce);
		
		// Second request - mark the connection as stale
		HttpRequest request2 = HttpRequest.newBuilder()
				.uri(getHttpsUrlNoGoaway())
				.header("x-expected-nonce", nonce)
				.header("x-mark-stale", "true")
				.GET()
				.build();
		
		logger.info("Step 2: Sending second request to mark connection as stale");
		HttpResponse<String> response2 = client.send(request2, HttpResponse.BodyHandlers.ofString());
		
		assertEquals(200, response2.statusCode(), "Second request should succeed and connection should be reused");
		logger.info("Connection marked as stale");
		// The GOAWAY should have been sent during request 2
		httpsServerNoGoaway.assertGoaway();
		logger.info("GOAWAY was sent as expected when stale connection was reused");
		
		// Third request - should use a NEW connection since GOAWAY was sent
		HttpRequest request3 = HttpRequest.newBuilder()
				.uri(getHttpsUrlNoGoaway())
				.header("x-expected-nonce", nonce)
				.GET()
				.build();
		
		logger.info("Step 3: Sending third request - should use new connection after GOAWAY");
		HttpResponse<String> response3 = client.send(request3, HttpResponse.BodyHandlers.ofString());
		
		// Should be 444 because a new connection was opened (old one got GOAWAY)
		int status3 = response3.statusCode();
		logger.info("Third request status: {}", status3);
		assertEquals(444, status3, "Should use new connection after GOAWAY (status 444), got: " + status3);
		
		logger.info("=== GOAWAY on stale connection reuse test completed successfully ===\n");
	}

	@Test
	@DisplayName("Test parallel requests with connection pooling")
	public void testParallelRequestsWithConnectionPooling() throws Exception {
		logger.info("\n=== Testing parallel requests with connection pooling ===");
		
		// Reset server state
		httpsServerNoGoaway.reset();
		
		// Create a single HTTP client that will pool connections
		HttpClient client = httpsClient();
		
		// Send multiple parallel requests
		List<CompletableFuture<HttpResponse<String>>> futures = new ArrayList<>();
		int numRequests = 5;
		
		logger.info("Sending {} parallel requests", numRequests);
		for (int i = 0; i < numRequests; i++) {
			HttpRequest request = HttpRequest.newBuilder()
					.uri(getHttpsUrlNoGoaway())
					.GET()
					.build();
			
			CompletableFuture<HttpResponse<String>> future = client.sendAsync(request, 
					HttpResponse.BodyHandlers.ofString());
			futures.add(future);
		}
		
		// Wait for all requests to complete
		CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();
		
		// Check that all requests succeeded
		int successCount = 0;
		for (CompletableFuture<HttpResponse<String>> future : futures) {
			HttpResponse<String> response = future.get();
			if (response.statusCode() == 200) {
				successCount++;
				logger.info("Request completed with status: {}", response.statusCode());
			}
		}
		
		assertEquals(numRequests, successCount, "All parallel requests should succeed");
		logger.info("All {} parallel requests completed successfully", successCount);
		
		logger.info("=== Parallel requests test completed successfully ===\n");
	}
}
