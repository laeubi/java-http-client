package io.github.laeubi.httpclient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test suite demonstrating that Java HTTP Client does NOT support 
 * transparent HTTP compression out of the box.
 * 
 * This test suite shows that:
 * 1. The client does NOT automatically add Accept-Encoding header
 * 2. The client does NOT automatically decompress gzip responses
 * 3. Manual implementation is required for both request and response handling
 */
public class JavaHttpClientCompressionTest extends JavaHttpClientBase {

	private static final Logger logger = LoggerFactory.getLogger(JavaHttpClientCompressionTest.class);

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
	@DisplayName("Java HTTP Client does NOT automatically add Accept-Encoding header")
	public void testNoAutomaticAcceptEncodingHeader() throws Exception {
		logger.info("\n=== Testing that Accept-Encoding is NOT added automatically ===");

		HttpClient client = httpClient();

		HttpRequest request = HttpRequest.newBuilder()
				.uri(getHttpUrl())
				.GET()
				.build();

		logger.info("Sending HTTP request without explicit Accept-Encoding header");
		HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

		logger.info("Response status: {}", response.statusCode());
		logger.info("Response headers: {}", response.headers().map());
		logger.info("Response body: {}", response.body());

		assertEquals(200, response.statusCode(), "Expected 200 OK response");
		
		// The response should NOT have Content-Encoding header because 
		// the client did not send Accept-Encoding
		assertFalse(response.headers().firstValue("content-encoding").isPresent(),
				"Server should NOT compress response when client does not send Accept-Encoding header");
		
		// Response body should be readable plain text
		assertTrue(response.body().contains("Hello from HTTP/2 server"),
				"Response body should be plain text");

		logger.info("=== Test confirmed: Java HTTP Client does NOT add Accept-Encoding automatically ===\n");
	}

	@Test
	@DisplayName("Java HTTP Client does NOT automatically decompress gzip responses")
	public void testNoAutomaticDecompression() throws Exception {
		logger.info("\n=== Testing that gzip responses are NOT decompressed automatically ===");

		HttpClient client = httpClient();

		// Manually add Accept-Encoding header to request compressed content
		HttpRequest request = HttpRequest.newBuilder()
				.uri(getHttpUrl())
				.header("Accept-Encoding", "gzip")
				.GET()
				.build();

		logger.info("Sending HTTP request WITH Accept-Encoding: gzip header");
		
		// Use ofByteArray to get raw bytes (not automatically decompressed)
		HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());

		logger.info("Response status: {}", response.statusCode());
		logger.info("Response headers: {}", response.headers().map());
		
		assertEquals(200, response.statusCode(), "Expected 200 OK response");
		
		// Server should have compressed the response
		assertTrue(response.headers().firstValue("content-encoding").isPresent(),
				"Server should send Content-Encoding header when client requests compression");
		assertEquals("gzip", response.headers().firstValue("content-encoding").get(),
				"Content-Encoding should be gzip");
		
		// The response body should be compressed (gzip format starts with 0x1f 0x8b)
		byte[] responseBytes = response.body();
		assertNotNull(responseBytes, "Response body should not be null");
		assertTrue(responseBytes.length > 2, "Response should have content");
		
		// Check for gzip magic number
		assertEquals(0x1f, responseBytes[0] & 0xff, "First byte should be gzip magic number 0x1f");
		assertEquals(0x8b, responseBytes[1] & 0xff, "Second byte should be gzip magic number 0x8b");
		
		logger.info("Response is gzip compressed (magic number: 0x1f 0x8b)");
		
		// Try to read as string - should fail or be garbled
		String directString = new String(responseBytes);
		assertFalse(directString.contains("Hello from HTTP/2 server"),
				"Compressed response should NOT be readable as plain text without decompression");
		
		logger.info("Direct string read from compressed bytes (garbled): {}", 
				directString.substring(0, Math.min(20, directString.length())));

		logger.info("=== Test confirmed: Java HTTP Client does NOT decompress gzip responses automatically ===\n");
	}

	@Test
	@DisplayName("Manual compression requires both Accept-Encoding header and manual decompression")
	public void testManualCompressionRequired() throws Exception {
		logger.info("\n=== Testing that manual implementation is required for compression ===");

		HttpClient client = httpClient();

		// Step 1: Manually add Accept-Encoding header
		HttpRequest request = HttpRequest.newBuilder()
				.uri(getHttpUrl())
				.header("Accept-Encoding", "gzip")
				.GET()
				.build();

		logger.info("Step 1: Sending request with manual Accept-Encoding: gzip header");
		HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());

		assertEquals(200, response.statusCode(), "Expected 200 OK response");
		assertTrue(response.headers().firstValue("content-encoding").orElse("").equals("gzip"),
				"Response should be gzip compressed");
		
		byte[] compressedBytes = response.body();
		logger.info("Received compressed response: {} bytes", compressedBytes.length);
		
		// Step 2: Manually decompress the response
		logger.info("Step 2: Manually decompressing the gzip response");
		String decompressedBody = manualGzipDecompress(compressedBytes);
		
		logger.info("Decompressed body: {}", decompressedBody);
		
		// After manual decompression, the body should be readable
		assertTrue(decompressedBody.contains("Hello from HTTP/2 server"),
				"After manual decompression, response should be readable");

		logger.info("=== Test confirmed: Manual implementation required for compression support ===\n");
	}

	@Test
	@DisplayName("HTTPS: Java HTTP Client does NOT automatically add Accept-Encoding header")
	public void testHttpsNoAutomaticAcceptEncodingHeader() throws Exception {
		logger.info("\n=== Testing HTTPS: Accept-Encoding is NOT added automatically ===");

		HttpClient client = httpsClient();

		HttpRequest request = HttpRequest.newBuilder()
				.uri(getHttpsUrl())
				.GET()
				.build();

		logger.info("Sending HTTPS request without explicit Accept-Encoding header");
		HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

		logger.info("Response status: {}", response.statusCode());
		logger.info("Response headers: {}", response.headers().map());

		assertEquals(200, response.statusCode(), "Expected 200 OK response");
		
		// The response should NOT have Content-Encoding header
		assertFalse(response.headers().firstValue("content-encoding").isPresent(),
				"Server should NOT compress response when client does not send Accept-Encoding header");
		
		assertTrue(response.body().contains("Hello from HTTP/2 server"),
				"Response body should be plain text");

		logger.info("=== Test confirmed: HTTPS also does NOT add Accept-Encoding automatically ===\n");
	}

	@Test
	@DisplayName("HTTPS: Manual compression works with Accept-Encoding header")
	public void testHttpsManualCompression() throws Exception {
		logger.info("\n=== Testing HTTPS with manual Accept-Encoding header ===");

		HttpClient client = httpsClient();

		HttpRequest request = HttpRequest.newBuilder()
				.uri(getHttpsUrl())
				.header("Accept-Encoding", "gzip")
				.GET()
				.build();

		logger.info("Sending HTTPS request WITH Accept-Encoding: gzip header");
		HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());

		assertEquals(200, response.statusCode(), "Expected 200 OK response");
		
		// Server should have compressed the response
		assertTrue(response.headers().firstValue("content-encoding").orElse("").equals("gzip"),
				"Response should be gzip compressed");
		
		// Manually decompress
		String decompressedBody = manualGzipDecompress(response.body());
		logger.info("Decompressed HTTPS body: {}", decompressedBody);
		
		assertTrue(decompressedBody.contains("Hello from HTTP/2 server"),
				"After manual decompression, HTTPS response should be readable");

		logger.info("=== HTTPS manual compression test completed ===\n");
	}

	/**
	 * Helper method to manually decompress gzip data.
	 * This demonstrates the manual work required by applications.
	 */
	private String manualGzipDecompress(byte[] compressed) {
		try {
			ByteArrayInputStream bais = new ByteArrayInputStream(compressed);
			GZIPInputStream gzipIn = new GZIPInputStream(bais);
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			
			byte[] buffer = new byte[1024];
			int len;
			while ((len = gzipIn.read(buffer)) > 0) {
				baos.write(buffer, 0, len);
			}
			
			gzipIn.close();
			return baos.toString("UTF-8");
		} catch (Exception e) {
			logger.error("Failed to decompress gzip data", e);
			return "<decompression failed>";
		}
	}
}
