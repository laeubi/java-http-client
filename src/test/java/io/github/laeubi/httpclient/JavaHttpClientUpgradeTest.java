package io.github.laeubi.httpclient;

import static io.github.laeubi.httpclient.JavaHttpClientBase.httpServer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JavaHttpClientUpgradeTest extends JavaHttpClientBase {

	private static final Logger logger = LoggerFactory.getLogger(JavaHttpClientUpgradeTest.class);

	@BeforeAll
	public static void startServers() throws Exception {
		startHttpServer();
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


}
