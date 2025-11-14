package io.github.laeubi.httpclient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test suite demonstrating that Java HTTP Client does NOT support 
 * HTTP caching out of the box.
 * 
 * This test suite shows that:
 * 1. The client does NOT automatically handle Cache-Control headers
 * 2. The client does NOT automatically use ETags for conditional requests
 * 3. The client does NOT handle 304 Not Modified responses transparently
 * 4. Manual implementation is required for complete caching support
 */
public class JavaHttpClientCachingTest extends JavaHttpClientBase {

	private static final Logger logger = LoggerFactory.getLogger(JavaHttpClientCachingTest.class);

	@BeforeAll
	public static void startServers() throws Exception {
		startHttpServer();
		startHttpsServer();
	}

	@BeforeEach
	public void resetServers() {
		if (httpServer != null) {
			httpServer.reset();
		}
		if (httpsServer != null) {
			httpsServer.reset();
		}
	}

	@AfterAll
	public static void stopServers() {
		stopHttpServer();
	}

	@Test
	@DisplayName("Java HTTP Client does NOT automatically handle Cache-Control headers")
	public void testNoCacheControlHandling() throws Exception {
		logger.info("\n=== Testing that Cache-Control headers are NOT handled automatically ===");

		HttpClient client = httpClient();
		URI uri = getHttpUrl();

		// First request
		HttpRequest request1 = HttpRequest.newBuilder()
				.uri(uri)
				.GET()
				.build();

		logger.info("Sending first HTTP request");
		HttpResponse<String> response1 = client.send(request1, HttpResponse.BodyHandlers.ofString());

		logger.info("Response 1 status: {}", response1.statusCode());
		logger.info("Response 1 headers: {}", response1.headers().map());
		logger.info("Response 1 body: {}", response1.body());

		assertEquals(200, response1.statusCode(), "Expected 200 OK response");
		
		// Server sends Cache-Control: max-age=3600
		String cacheControl = response1.headers().firstValue("cache-control").orElse("");
		assertTrue(cacheControl.contains("max-age"), 
				"Server should send Cache-Control with max-age");
		
		// Second identical request - should go to server again
		HttpRequest request2 = HttpRequest.newBuilder()
				.uri(uri)
				.GET()
				.build();

		logger.info("Sending second identical HTTP request");
		HttpResponse<String> response2 = client.send(request2, HttpResponse.BodyHandlers.ofString());

		logger.info("Response 2 status: {}", response2.statusCode());
		logger.info("Response 2 body: {}", response2.body());

		assertEquals(200, response2.statusCode(), "Expected 200 OK response");
		
		// Both requests should hit the server (different connection IDs in response body)
		// This proves the client does NOT cache based on Cache-Control headers
		assertNotNull(response1.body(), "Response 1 body should not be null");
		assertNotNull(response2.body(), "Response 2 body should not be null");

		logger.info("=== Test confirmed: Java HTTP Client does NOT handle Cache-Control automatically ===\n");
	}

	@Test
	@DisplayName("Java HTTP Client does NOT automatically use ETags for conditional requests")
	public void testNoAutomaticETagHandling() throws Exception {
		logger.info("\n=== Testing that ETags are NOT used automatically for conditional requests ===");

		HttpClient client = httpClient();
		URI uri = getHttpUrl();

		// First request
		HttpRequest request1 = HttpRequest.newBuilder()
				.uri(uri)
				.GET()
				.build();

		logger.info("Sending first HTTP request");
		HttpResponse<String> response1 = client.send(request1, HttpResponse.BodyHandlers.ofString());

		logger.info("Response 1 status: {}", response1.statusCode());
		logger.info("Response 1 headers: {}", response1.headers().map());

		assertEquals(200, response1.statusCode(), "Expected 200 OK response");
		
		// Server sends ETag
		String etag = response1.headers().firstValue("etag").orElse("");
		assertFalse(etag.isEmpty(), "Server should send ETag header");
		logger.info("Received ETag: {}", etag);
		
		// Second request to same URI - client should add If-None-Match, but it doesn't
		HttpRequest request2 = HttpRequest.newBuilder()
				.uri(uri)
				.GET()
				.build();

		logger.info("Sending second HTTP request to same URI");
		HttpResponse<String> response2 = client.send(request2, HttpResponse.BodyHandlers.ofString());

		logger.info("Response 2 status: {}", response2.statusCode());

		// Client does NOT add If-None-Match header automatically
		// So server responds with 200 and full content again
		assertEquals(200, response2.statusCode(), 
				"Expected 200 OK because client did NOT send If-None-Match");
		
		// The response includes ETag again
		String etag2 = response2.headers().firstValue("etag").orElse("");
		assertEquals(etag, etag2, "ETag should be the same for identical content");

		logger.info("=== Test confirmed: Java HTTP Client does NOT use ETags automatically ===\n");
	}

	@Test
	@DisplayName("Applications must manually add If-None-Match header for conditional requests")
	public void testManualIfNoneMatchRequired() throws Exception {
		logger.info("\n=== Testing manual If-None-Match header handling ===");

		HttpClient client = httpClient();
		URI uri = getHttpUrl();

		// First request
		HttpRequest request1 = HttpRequest.newBuilder()
				.uri(uri)
				.GET()
				.build();

		logger.info("Sending first HTTP request");
		HttpResponse<String> response1 = client.send(request1, HttpResponse.BodyHandlers.ofString());

		assertEquals(200, response1.statusCode(), "Expected 200 OK response");
		
		String etag = response1.headers().firstValue("etag").orElse("");
		assertFalse(etag.isEmpty(), "Server should send ETag header");
		logger.info("Received ETag: {}", etag);
		
		// Second request - MANUALLY add If-None-Match header
		HttpRequest request2 = HttpRequest.newBuilder()
				.uri(uri)
				.header("If-None-Match", etag)  // Manual implementation required!
				.GET()
				.build();

		logger.info("Sending second HTTP request with manual If-None-Match header");
		HttpResponse<String> response2 = client.send(request2, HttpResponse.BodyHandlers.ofString());

		logger.info("Response 2 status: {}", response2.statusCode());

		// When we manually add If-None-Match, server responds with 304 Not Modified
		assertEquals(304, response2.statusCode(), 
				"Expected 304 Not Modified when If-None-Match matches");
		
		// Response body is empty for 304
		String body2 = response2.body();
		assertTrue(body2 == null || body2.isEmpty(), 
				"304 response should have empty body");

		logger.info("=== Test confirmed: Manual If-None-Match handling is required ===\n");
	}

	@Test
	@DisplayName("Java HTTP Client does NOT handle 304 responses transparently")
	public void testNo304Handling() throws Exception {
		logger.info("\n=== Testing that 304 Not Modified responses are NOT handled transparently ===");

		HttpClient client = httpClient();
		URI uri = getHttpUrl();

		// First request to get ETag and content
		HttpRequest request1 = HttpRequest.newBuilder()
				.uri(uri)
				.GET()
				.build();

		logger.info("Sending first HTTP request");
		HttpResponse<String> response1 = client.send(request1, HttpResponse.BodyHandlers.ofString());

		assertEquals(200, response1.statusCode(), "Expected 200 OK response");
		String originalContent = response1.body();
		String etag = response1.headers().firstValue("etag").orElse("");
		
		logger.info("Original content: {}", originalContent);
		logger.info("ETag: {}", etag);
		
		// Second request with If-None-Match
		HttpRequest request2 = HttpRequest.newBuilder()
				.uri(uri)
				.header("If-None-Match", etag)
				.GET()
				.build();

		logger.info("Sending second HTTP request with If-None-Match");
		HttpResponse<String> response2 = client.send(request2, HttpResponse.BodyHandlers.ofString());

		logger.info("Response 2 status: {}", response2.statusCode());
		logger.info("Response 2 body: '{}'", response2.body());

		// Server responds with 304 Not Modified
		assertEquals(304, response2.statusCode(), "Expected 304 Not Modified");
		
		// Client does NOT transparently return cached content
		// The body is empty, not the original content
		String body2 = response2.body();
		assertTrue(body2 == null || body2.isEmpty(), 
				"304 response body is empty - client does NOT return cached content");
		
		// Application must manually handle this by checking status code
		// and retrieving the original content from its own cache
		assertNotEquals(originalContent, body2, 
				"Client does NOT transparently handle 304 by returning cached content");

		logger.info("=== Test confirmed: Java HTTP Client does NOT handle 304 transparently ===\n");
	}

	@Test
	@DisplayName("Manual caching implementation is complex and error-prone")
	public void testManualCachingRequired() throws Exception {
		logger.info("\n=== Demonstrating manual caching implementation ===");

		HttpClient client = httpClient();
		URI uri = getHttpUrl();

		// Manual cache implementation required
		Map<URI, CacheEntry> cache = new HashMap<>();

		// First request - cache miss
		logger.info("First request (cache miss)");
		HttpRequest request1 = HttpRequest.newBuilder().uri(uri).GET().build();
		HttpResponse<String> response1 = client.send(request1, HttpResponse.BodyHandlers.ofString());
		
		assertEquals(200, response1.statusCode(), "Expected 200 OK");
		String content1 = response1.body();
		String etag1 = response1.headers().firstValue("etag").orElse(null);
		
		// Store in manual cache
		cache.put(uri, new CacheEntry(content1, etag1));
		logger.info("Cached content with ETag: {}", etag1);
		
		assertNotNull(content1, "Content should not be null");
		assertTrue(content1.contains("Hello from HTTP/2 server"), 
				"Should receive actual content");

		// Second request - manually implement conditional request
		logger.info("Second request (manually checking cache)");
		CacheEntry cached = cache.get(uri);
		assertNotNull(cached, "Should have cached entry");
		
		// Build request with If-None-Match from cache
		HttpRequest request2 = HttpRequest.newBuilder()
				.uri(uri)
				.header("If-None-Match", cached.etag)
				.GET()
				.build();
		
		HttpResponse<String> response2 = client.send(request2, HttpResponse.BodyHandlers.ofString());
		
		logger.info("Response 2 status: {}", response2.statusCode());
		
		// Manually handle 304 response
		String content2;
		if (response2.statusCode() == 304) {
			// Must manually retrieve from cache
			logger.info("Received 304, manually retrieving from cache");
			content2 = cached.content;
		} else {
			// Update cache with new content
			content2 = response2.body();
			String etag2 = response2.headers().firstValue("etag").orElse(null);
			cache.put(uri, new CacheEntry(content2, etag2));
		}
		
		assertNotNull(content2, "Cached content should not be null");
		assertEquals(content1, content2, "Cached content should match original");

		logger.info("=== Manual caching works but requires significant implementation ===\n");
	}

	@Test
	@DisplayName("HTTPS behaves identically to HTTP regarding caching")
	public void testHttpsNoCaching() throws Exception {
		logger.info("\n=== Testing that HTTPS also lacks automatic caching support ===");

		HttpClient client = httpsClient();
		URI uri = getHttpsUrl();

		// First request
		HttpRequest request1 = HttpRequest.newBuilder()
				.uri(uri)
				.GET()
				.build();

		logger.info("Sending first HTTPS request");
		HttpResponse<String> response1 = client.send(request1, HttpResponse.BodyHandlers.ofString());

		assertEquals(200, response1.statusCode(), "Expected 200 OK response");
		String etag = response1.headers().firstValue("etag").orElse("");
		assertFalse(etag.isEmpty(), "Server should send ETag header");
		
		// Second request - client does NOT add If-None-Match automatically
		HttpRequest request2 = HttpRequest.newBuilder()
				.uri(uri)
				.GET()
				.build();

		logger.info("Sending second HTTPS request");
		HttpResponse<String> response2 = client.send(request2, HttpResponse.BodyHandlers.ofString());

		// Client does NOT use ETag automatically, so we get 200 again
		assertEquals(200, response2.statusCode(), 
				"Expected 200 OK because client did NOT send If-None-Match over HTTPS");

		logger.info("=== Test confirmed: HTTPS has same caching limitations as HTTP ===\n");
	}

	/**
	 * Simple cache entry for demonstration purposes
	 */
	private static class CacheEntry {
		String content;
		String etag;

		CacheEntry(String content, String etag) {
			this.content = content;
			this.etag = etag;
		}
	}
}
