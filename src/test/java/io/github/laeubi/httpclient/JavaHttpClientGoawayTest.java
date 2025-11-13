package io.github.laeubi.httpclient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JavaHttpClientGoawayTest extends JavaHttpClientBase {

	private static final Logger logger = LoggerFactory.getLogger(JavaHttpClientGoawayTest.class);

	@BeforeAll
	public static void startServers() throws Exception {
		startHttpsServer();
	}

	@AfterAll
	public static void stopServers() {
		stopHttpServer();
	}

	@Test
	@DisplayName("HTTP/2 GOAWAY can be handled")
	public void testHttp2GoawayIsHandled() throws Exception {
		logger.info("\n=== Testing HTTP/2 GOAWAY ===");

		HttpClient client = httpsClient();

		HttpRequest request = HttpRequest.newBuilder().uri(getHttpsUrl()).GET().build();

		logger.info("Sending HTTP request to " + getHttpsUrl());
		CompletableFuture<HttpResponse<String>> responseAsync = client.sendAsync(request,
				HttpResponse.BodyHandlers.ofString());
		try {
			responseAsync.join();
		} catch (Exception e) {
			// whatever happens, we just want to make sure the request is complete
			logger.info("Caught exception during join: " + e);
		}
		httpsServer.assertGoaway();
		try {
			HttpResponse<String> response = responseAsync.get();
			logger.info("Response status: {}", response.statusCode());
			logger.info("Response version: {}", response.version());
			logger.info("Response body: {}", response.body());
			logger.info("Response headers: {}", response.headers().map());
			assertEquals(200, response.statusCode(), "Expected 200 OK response");
			assertNotNull(response.body(), "Response body should not be null");
			logger.info("=== HTTP/2 Upgrade test completed successfully ===\n");
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			cause.printStackTrace();
			// Here we have no way to know any of the details of the GOAWAY see GOAWAY.md
			// for further rationale.
			assertNotEquals(IOException.class, cause.getClass());
		}
	}

}
