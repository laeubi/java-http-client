package io.github.laeubi.httpclient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test suite demonstrating Java HTTP Client support for multipart/form-data requests.
 * 
 * This test suite evaluates:
 * 1. Whether the client provides a specific API for multipart requests
 * 2. Manual implementation of multipart/form-data
 * 3. File upload with multipart
 * 4. Mixed content (text fields + files) in multipart requests
 */
public class JavaHttpClientMultipartTest extends JavaHttpClientBase {

	private static final Logger logger = LoggerFactory.getLogger(JavaHttpClientMultipartTest.class);
	private static NettyFormsServer formsServer;

	@BeforeAll
	public static void startServers() throws Exception {
		formsServer = new NettyFormsServer(8091);
	}

	@AfterAll
	public static void stopServers() {
		if (formsServer != null) {
			formsServer.stop();
		}
	}

	@Test
	@DisplayName("Java HTTP Client does NOT provide a specific API for multipart data")
	public void testNoBuiltInMultipartAPI() throws Exception {
		logger.info("\n=== Testing whether Java HTTP Client has built-in multipart API ===");

		// Java HTTP Client does NOT have methods like:
		// - HttpRequest.BodyPublishers.ofMultipartForm(...)
		// - MultipartBuilder or similar convenience API
		// All multipart submissions must be manually constructed

		logger.info("Java HTTP Client provides no specific multipart data API");
		logger.info("Multipart must be manually built with proper boundaries and headers");
		logger.info("=== No built-in multipart API available ===\n");
	}

	@Test
	@DisplayName("Manual implementation of multipart/form-data with text fields")
	public void testManualMultipartTextFields() throws Exception {
		logger.info("\n=== Testing manual multipart submission with text fields ===");

		HttpClient client = httpClient();

		String boundary = "----WebKitFormBoundary" + System.currentTimeMillis();
		
		MultipartBodyBuilder builder = new MultipartBodyBuilder(boundary);
		builder.addField("username", "testuser");
		builder.addField("email", "test@example.com");
		builder.addField("message", "Hello from multipart!");
		
		byte[] body = builder.build();

		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://localhost:" + formsServer.getPort() + "/multipart"))
				.header("Content-Type", "multipart/form-data; boundary=" + boundary)
				.POST(HttpRequest.BodyPublishers.ofByteArray(body))
				.build();

		logger.info("Sending POST request with multipart data");
		HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

		logger.info("Response status: {}", response.statusCode());
		logger.info("Response body: {}", response.body());

		assertEquals(200, response.statusCode(), "Expected 200 OK response");
		assertTrue(response.body().contains("username"), "Response should contain username field");
		assertTrue(response.body().contains("testuser"), "Response should contain username value");
		assertTrue(response.body().contains("email"), "Response should contain email field");

		logger.info("=== Manual multipart submission works but requires complex implementation ===\n");
	}

	@Test
	@DisplayName("Manual implementation of file upload with multipart/form-data")
	public void testManualFileUpload() throws Exception {
		logger.info("\n=== Testing manual file upload with multipart ===");

		HttpClient client = httpClient();

		// Create a temporary file for upload
		Path tempFile = Files.createTempFile("test-upload", ".txt");
		String fileContent = "This is test file content\nLine 2\nLine 3";
		Files.writeString(tempFile, fileContent);

		try {
			String boundary = "----WebKitFormBoundary" + System.currentTimeMillis();
			
			MultipartBodyBuilder builder = new MultipartBodyBuilder(boundary);
			builder.addField("description", "Test file upload");
			builder.addFile("file", tempFile.getFileName().toString(), 
					Files.readAllBytes(tempFile), "text/plain");
			
			byte[] body = builder.build();

			HttpRequest request = HttpRequest.newBuilder()
					.uri(URI.create("http://localhost:" + formsServer.getPort() + "/upload"))
					.header("Content-Type", "multipart/form-data; boundary=" + boundary)
					.POST(HttpRequest.BodyPublishers.ofByteArray(body))
					.build();

			logger.info("Sending POST request with file upload");
			HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

			logger.info("Response status: {}", response.statusCode());
			logger.info("Response body: {}", response.body());

			assertEquals(200, response.statusCode(), "Expected 200 OK response");
			assertTrue(response.body().contains("file"), "Response should contain file field");
			assertTrue(response.body().contains(tempFile.getFileName().toString()), 
					"Response should contain filename");

			logger.info("=== File upload works but requires manual multipart construction ===\n");
		} finally {
			Files.deleteIfExists(tempFile);
		}
	}

	@Test
	@DisplayName("Manual implementation of multipart with mixed content")
	public void testManualMultipartMixedContent() throws Exception {
		logger.info("\n=== Testing multipart with mixed text and file content ===");

		HttpClient client = httpClient();

		// Create temporary files
		Path textFile = Files.createTempFile("document", ".txt");
		Path dataFile = Files.createTempFile("data", ".csv");
		
		Files.writeString(textFile, "Document content here");
		Files.writeString(dataFile, "name,value\nitem1,100\nitem2,200");

		try {
			String boundary = "----WebKitFormBoundary" + System.currentTimeMillis();
			
			MultipartBodyBuilder builder = new MultipartBodyBuilder(boundary);
			// Add text fields
			builder.addField("title", "Mixed Content Upload");
			builder.addField("author", "Test User");
			builder.addField("version", "1.0");
			
			// Add files
			builder.addFile("document", textFile.getFileName().toString(), 
					Files.readAllBytes(textFile), "text/plain");
			builder.addFile("data", dataFile.getFileName().toString(), 
					Files.readAllBytes(dataFile), "text/csv");
			
			byte[] body = builder.build();

			HttpRequest request = HttpRequest.newBuilder()
					.uri(URI.create("http://localhost:" + formsServer.getPort() + "/multipart"))
					.header("Content-Type", "multipart/form-data; boundary=" + boundary)
					.POST(HttpRequest.BodyPublishers.ofByteArray(body))
					.build();

			logger.info("Sending POST request with mixed multipart content");
			HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

			logger.info("Response status: {}", response.statusCode());
			logger.info("Response body length: {} bytes", response.body().length());

			assertEquals(200, response.statusCode(), "Expected 200 OK response");
			assertNotNull(response.body(), "Response body should not be null");

			logger.info("=== Mixed content multipart works but is complex to implement ===\n");
		} finally {
			Files.deleteIfExists(textFile);
			Files.deleteIfExists(dataFile);
		}
	}

	@Test
	@DisplayName("Demonstrate boundary handling complexity")
	public void testBoundaryHandling() throws Exception {
		logger.info("\n=== Demonstrating multipart boundary handling complexity ===");

		// Boundaries must:
		// 1. Be unique and not appear in the content
		// 2. Start with two hyphens "--"
		// 3. End with two hyphens "--" followed by CRLF
		// 4. Separate each part with the boundary
		// 5. Be included in the Content-Type header

		String boundary = "----CustomBoundary123456789";
		logger.info("Boundary: {}", boundary);
		logger.info("Content-Type header must include: multipart/form-data; boundary=" + boundary);
		logger.info("Each part starts with: --{}", boundary);
		logger.info("Final boundary ends with: --{}--", boundary);

		HttpClient client = httpClient();
		
		MultipartBodyBuilder builder = new MultipartBodyBuilder(boundary);
		builder.addField("test", "value");
		byte[] body = builder.build();

		String bodyStr = new String(body, StandardCharsets.UTF_8);
		logger.info("Generated multipart body:\n{}", bodyStr);

		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://localhost:" + formsServer.getPort() + "/multipart"))
				.header("Content-Type", "multipart/form-data; boundary=" + boundary)
				.POST(HttpRequest.BodyPublishers.ofByteArray(body))
				.build();

		HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
		assertEquals(200, response.statusCode(), "Expected 200 OK response");

		logger.info("\nSummary:");
		logger.info("- Java HTTP Client has NO built-in multipart API");
		logger.info("- Developers must manually construct multipart/form-data bodies");
		logger.info("- Boundary generation and formatting is developer's responsibility");
		logger.info("- File uploads require manual encoding");
		logger.info("- No convenience methods for common multipart operations");
		logger.info("=== Complete manual implementation required ===\n");
	}

	/**
	 * Helper class to manually build multipart/form-data bodies.
	 * This demonstrates the complexity that developers must handle manually
	 * since Java HTTP Client does not provide this functionality.
	 */
	private static class MultipartBodyBuilder {
		private final String boundary;
		private final List<byte[]> parts = new ArrayList<>();
		private static final byte[] CRLF = "\r\n".getBytes(StandardCharsets.UTF_8);
		private static final byte[] DOUBLE_DASH = "--".getBytes(StandardCharsets.UTF_8);

		public MultipartBodyBuilder(String boundary) {
			this.boundary = boundary;
		}

		public void addField(String name, String value) {
			StringBuilder part = new StringBuilder();
			part.append("--").append(boundary).append("\r\n");
			part.append("Content-Disposition: form-data; name=\"").append(name).append("\"\r\n");
			part.append("\r\n");
			part.append(value).append("\r\n");
			parts.add(part.toString().getBytes(StandardCharsets.UTF_8));
		}

		public void addFile(String fieldName, String fileName, byte[] fileContent, String contentType) 
				throws IOException {
			StringBuilder part = new StringBuilder();
			part.append("--").append(boundary).append("\r\n");
			part.append("Content-Disposition: form-data; name=\"").append(fieldName)
				.append("\"; filename=\"").append(fileName).append("\"\r\n");
			part.append("Content-Type: ").append(contentType).append("\r\n");
			part.append("\r\n");
			
			byte[] header = part.toString().getBytes(StandardCharsets.UTF_8);
			byte[] combined = new byte[header.length + fileContent.length + CRLF.length];
			System.arraycopy(header, 0, combined, 0, header.length);
			System.arraycopy(fileContent, 0, combined, header.length, fileContent.length);
			System.arraycopy(CRLF, 0, combined, header.length + fileContent.length, CRLF.length);
			
			parts.add(combined);
		}

		public byte[] build() throws IOException {
			// Calculate total size
			int totalSize = 0;
			for (byte[] part : parts) {
				totalSize += part.length;
			}
			// Add final boundary size: "--boundary--\r\n"
			byte[] finalBoundary = ("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
			totalSize += finalBoundary.length;

			// Combine all parts
			byte[] result = new byte[totalSize];
			int offset = 0;
			for (byte[] part : parts) {
				System.arraycopy(part, 0, result, offset, part.length);
				offset += part.length;
			}
			System.arraycopy(finalBoundary, 0, result, offset, finalBoundary.length);

			return result;
		}
	}
}
