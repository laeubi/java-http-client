package io.github.laeubi.httpclient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test suite demonstrating Java HTTP Client support for HTML form data submission.
 * 
 * This test suite evaluates:
 * 1. Whether the client provides a specific API for form data
 * 2. Manual implementation of application/x-www-form-urlencoded
 * 3. Form data with special characters requiring URL encoding
 */
public class JavaHttpClientFormsTest extends JavaHttpClientBase {

	private static final Logger logger = LoggerFactory.getLogger(JavaHttpClientFormsTest.class);
	private static NettyFormsServer formsServer;

	@BeforeAll
	public static void startServers() throws Exception {
		formsServer = new NettyFormsServer(8090);
	}

	@AfterAll
	public static void stopServers() {
		if (formsServer != null) {
			formsServer.stop();
		}
	}

	@Test
	@DisplayName("Java HTTP Client does NOT provide a specific API for form data")
	public void testNoBuiltInFormAPI() throws Exception {
		logger.info("\n=== Testing whether Java HTTP Client has built-in form API ===");

		// Java HTTP Client does NOT have methods like:
		// - HttpRequest.BodyPublishers.ofForm(Map<String, String>)
		// - FormDataBuilder or similar convenience API
		// All form submissions must be manually constructed

		logger.info("Java HTTP Client provides no specific form data API");
		logger.info("Forms must be manually built using application/x-www-form-urlencoded");
		logger.info("=== No built-in form API available ===\n");
	}

	@Test
	@DisplayName("Manual implementation of application/x-www-form-urlencoded form data")
	public void testManualFormDataImplementation() throws Exception {
		logger.info("\n=== Testing manual form data submission ===");

		HttpClient client = httpClient();

		// Manually build form data
		Map<String, String> formData = new HashMap<>();
		formData.put("username", "testuser");
		formData.put("password", "testpass");
		formData.put("remember", "true");

		String formBody = buildFormData(formData);
		logger.info("Built form data: {}", formBody);

		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://localhost:" + formsServer.getPort() + "/form"))
				.header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString(formBody))
				.build();

		logger.info("Sending POST request with form data");
		HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

		logger.info("Response status: {}", response.statusCode());
		logger.info("Response body: {}", response.body());

		assertEquals(200, response.statusCode(), "Expected 200 OK response");
		assertTrue(response.body().contains("username=testuser"), "Response should echo username");
		assertTrue(response.body().contains("password=testpass"), "Response should echo password");
		assertTrue(response.body().contains("remember=true"), "Response should echo remember flag");

		logger.info("=== Manual form data submission works but requires manual implementation ===\n");
	}

	@Test
	@DisplayName("Form data with special characters requires URL encoding")
	public void testFormDataWithSpecialCharacters() throws Exception {
		logger.info("\n=== Testing form data with special characters ===");

		HttpClient client = httpClient();

		// Form data with special characters that need URL encoding
		Map<String, String> formData = new HashMap<>();
		formData.put("email", "user@example.com");
		formData.put("message", "Hello World! How are you?");
		formData.put("tags", "java,http,client");
		formData.put("special", "a=b&c=d");

		String formBody = buildFormData(formData);
		logger.info("Built form data with special chars: {}", formBody);

		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://localhost:" + formsServer.getPort() + "/form"))
				.header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString(formBody))
				.build();

		logger.info("Sending POST request with special characters");
		HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

		logger.info("Response status: {}", response.statusCode());
		logger.info("Response body: {}", response.body());

		assertEquals(200, response.statusCode(), "Expected 200 OK response");
		assertTrue(response.body().contains("email=user@example.com"), "Response should contain email");
		assertTrue(response.body().contains("message=Hello World! How are you?"), 
				"Response should contain message with spaces and punctuation");
		assertTrue(response.body().contains("special=a=b&c=d"), 
				"Response should contain special characters properly decoded");

		logger.info("=== URL encoding works but is manual responsibility ===\n");
	}

	@Test
	@DisplayName("Demonstrate complete manual form handling required")
	public void testCompleteFormHandlingRequired() throws Exception {
		logger.info("\n=== Demonstrating complete manual form handling ===");

		HttpClient client = httpClient();

		// Realistic form data
		Map<String, String> formData = new HashMap<>();
		formData.put("firstName", "John");
		formData.put("lastName", "Doe");
		formData.put("age", "30");
		formData.put("email", "john.doe@example.com");
		formData.put("country", "USA");

		// Manual construction required
		String formBody = buildFormData(formData);

		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://localhost:" + formsServer.getPort() + "/form"))
				.header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString(formBody))
				.build();

		HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

		assertEquals(200, response.statusCode(), "Expected 200 OK response");
		assertNotNull(response.body(), "Response body should not be null");

		logger.info("Summary:");
		logger.info("- Java HTTP Client has NO built-in form API");
		logger.info("- Developers must manually build application/x-www-form-urlencoded strings");
		logger.info("- URL encoding is developer's responsibility");
		logger.info("- No convenience methods for common form operations");
		logger.info("=== Complete manual implementation required ===\n");
	}

	/**
	 * Helper method to build application/x-www-form-urlencoded form data.
	 * This is what developers must implement manually since Java HTTP Client
	 * does not provide this functionality.
	 */
	private String buildFormData(Map<String, String> data) {
		return data.entrySet().stream()
				.map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "=" +
						URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
				.collect(Collectors.joining("&"));
	}
}
