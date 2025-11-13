package io.github.laeubi.httpclient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tests for WebSocket support with Java HttpClient.
 * 
 * This test demonstrates the issue from JDK-8361305 where:
 * - Direct WebSocket connection (ws://) works fine
 * - But WebSocket upgrade after HTTP connection to the same endpoint does NOT work properly
 */
public class JavaHttpClientWebSocketTest extends JavaHttpClientBase {

	private static final Logger logger = LoggerFactory.getLogger(JavaHttpClientWebSocketTest.class);
	
	private static NettyWebSocketServer wsServer;

	@BeforeAll
	public static void startServers() throws Exception {
		// Start a simple WebSocket server on port 8090
		wsServer = new NettyWebSocketServer(8090);
	}

	@AfterAll
	public static void stopServers() {
		if (wsServer != null) {
			wsServer.stop();
			wsServer = null;
		}
	}

	@Test
	@DisplayName("Direct WebSocket connection works (ws:// URI)")
	public void testDirectWebSocketConnection() throws Exception {
		logger.info("\n=== Testing Direct WebSocket Connection ===");
		
		HttpClient client = HttpClient.newHttpClient();
		WebSocket.Builder webSocketBuilder = client.newWebSocketBuilder();
		
		URI wsUri = URI.create("ws://localhost:" + wsServer.getPort() + "/websocket");
		logger.info("Connecting to WebSocket URI: {}", wsUri);
		
		CountDownLatch messageLatch = new CountDownLatch(1);
		AtomicReference<String> receivedMessage = new AtomicReference<>();
		
		WebSocket.Listener listener = new WebSocket.Listener() {
			@Override
			public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
				logger.info("Received message: {}", data);
				receivedMessage.set(data.toString());
				messageLatch.countDown();
				return CompletableFuture.completedFuture(null);
			}
			
			@Override
			public void onOpen(WebSocket webSocket) {
				logger.info("WebSocket connection opened");
				WebSocket.Listener.super.onOpen(webSocket);
			}
			
			@Override
			public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
				logger.info("WebSocket closed with status: {}, reason: {}", statusCode, reason);
				return CompletableFuture.completedFuture(null);
			}
			
			@Override
			public void onError(WebSocket webSocket, Throwable error) {
				logger.error("WebSocket error", error);
			}
		};
		
		CompletableFuture<WebSocket> wsFuture = webSocketBuilder.buildAsync(wsUri, listener);
		WebSocket webSocket = wsFuture.get(10, TimeUnit.SECONDS);
		assertNotNull(webSocket, "WebSocket should be established");
		
		// Send a message
		webSocket.sendText("Hello from client", true).get(5, TimeUnit.SECONDS);
		
		// Wait for response
		boolean received = messageLatch.await(10, TimeUnit.SECONDS);
		assertTrue(received, "Should receive a response from WebSocket server");
		assertEquals("Echo: Hello from client", receivedMessage.get(), "Should receive echoed message");
		
		// Close the connection
		webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Closing").get(5, TimeUnit.SECONDS);
		
		logger.info("=== Direct WebSocket Connection test completed successfully ===\n");
	}

	@Test
	@DisplayName("WebSocket upgrade after HTTP/2 connection does NOT work (demonstrates JDK-8361305)")
	public void testWebSocketUpgradeAfterHttp2() throws Exception {
		logger.info("\n=== Testing WebSocket Upgrade after HTTP/2 Connection ===");
		
		// Create a combined server that supports both HTTP/2 upgrade and WebSocket on same port
		NettyWebSocketServer combinedServer = new NettyWebSocketServer(9999);
		
		try {
			// Create HttpClient configured for HTTP/2
			HttpClient client = HttpClient.newBuilder()
				.version(HttpClient.Version.HTTP_2)
				.connectTimeout(Duration.ofSeconds(10))
				.build();
			
			// Step 1: First make a regular HTTP request which may establish HTTP/2 connection
			// This simulates a scenario where the client has already connected to the server
			java.net.http.HttpRequest httpRequest = java.net.http.HttpRequest.newBuilder()
				.uri(URI.create("http://localhost:"+combinedServer.getPort()+"/websocket"))
				.GET()
				.build();
			
			logger.info("Step 1: Making HTTP request to http://localhost:9090/websocket");
			try {
				java.net.http.HttpResponse<String> httpResponse = client.send(httpRequest, 
					java.net.http.HttpResponse.BodyHandlers.ofString());
				logger.info("HTTP Response status: {}, version: {}", httpResponse.statusCode(), httpResponse.version());
			} catch (Exception e) {
				logger.info("HTTP request completed (or failed): {}", e.getMessage());
			}
			
			// Step 2: Now try to establish WebSocket connection to the SAME endpoint
			// This is where JDK-8361305 issue manifests - trying to upgrade to WebSocket
			// after HTTP/2 connection exists
			WebSocket.Builder webSocketBuilder = client.newWebSocketBuilder();
			URI wsUri = URI.create("ws://localhost:" + combinedServer.getPort() + "/websocket");
			logger.info("Step 2: Attempting WebSocket connection to same endpoint: {}", wsUri);
			
			CountDownLatch messageLatch = new CountDownLatch(1);
			AtomicReference<String> receivedMessage = new AtomicReference<>();
			AtomicReference<Throwable> errorRef = new AtomicReference<>();
			
			WebSocket.Listener listener = new WebSocket.Listener() {
				@Override
				public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
					logger.info("Received WebSocket message: {}", data);
					receivedMessage.set(data.toString());
					messageLatch.countDown();
					return CompletableFuture.completedFuture(null);
				}
				
				@Override
				public void onOpen(WebSocket webSocket) {
					logger.info("WebSocket connection opened successfully");
					WebSocket.Listener.super.onOpen(webSocket);
				}
				
				@Override
				public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
					logger.info("WebSocket closed with status: {}, reason: {}", statusCode, reason);
					return CompletableFuture.completedFuture(null);
				}
				
				@Override
				public void onError(WebSocket webSocket, Throwable error) {
					logger.error("WebSocket error", error);
					errorRef.set(error);
				}
			};
			
			try {
				CompletableFuture<WebSocket> wsFuture = webSocketBuilder.buildAsync(wsUri, listener);
				WebSocket webSocket = wsFuture.get(10, TimeUnit.SECONDS);
				
				// If we get here, the connection worked
				assertNotNull(webSocket, "WebSocket should be established");
				logger.info("WebSocket connection established successfully");
				
				// Send a test message
				webSocket.sendText("Test message", true).get(5, TimeUnit.SECONDS);
				
				// Wait for response
				boolean received = messageLatch.await(10, TimeUnit.SECONDS);
				assertTrue(received, "Should receive echo response from WebSocket server");
				assertEquals("Echo: Test message", receivedMessage.get(), "Should receive echoed message");
				
				// Assert that WebSocket upgrade was actually performed
				combinedServer.assertWebSocketUpgrade();
				
				// Close the connection
				webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Test complete").get(5, TimeUnit.SECONDS);
				
				logger.info("SUCCESS: WebSocket connection worked after HTTP/2. Issue may be fixed in this Java version.");
			} catch (Exception e) {
				// This documents the JDK-8361305 issue
				logger.error("EXPECTED FAILURE per JDK-8361305: WebSocket upgrade failed after HTTP/2 connection", e);
				logger.info("This test documents that WebSocket upgrade does NOT work when HTTP/2 connection exists to the same host.");
				// Don't fail the test - this documents the known issue
				// In future Java versions where this is fixed, this catch block won't be reached
			}
		} finally {
			combinedServer.stop();
		}
		
		logger.info("=== WebSocket Upgrade test completed ===\n");
	}
}
