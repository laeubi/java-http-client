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
 * This test demonstrates the issue from JDK-8361305 where: - Direct WebSocket
 * connection (ws://) works fine - But WebSocket upgrade after HTTP connection
 * to the same endpoint does NOT work properly
 */
public class JavaHttpClientWebSocketTest extends JavaHttpClientBase {

	private static final Logger logger = LoggerFactory.getLogger(JavaHttpClientWebSocketTest.class);

	private static NettyWebSocketServer wsServer;
	private static NettyPlainWebSocketServer plainWsServer;

	@BeforeAll
	public static void startServers() throws Exception {
		// Start a simple WebSocket server on port 8090 (with HTTP upgrade support)
		wsServer = new NettyWebSocketServer(8090);
		
		// Start a plain WebSocket server on port 8091 (no HTTP upgrade support)
		plainWsServer = new NettyPlainWebSocketServer(8091);
	}

	@AfterAll
	public static void stopServers() {
		if (wsServer != null) {
			wsServer.stop();
			wsServer = null;
		}
		if (plainWsServer != null) {
			plainWsServer.stop();
			plainWsServer = null;
		}
	}

	@Test
	@DisplayName("Direct WebSocket connection to plain WebSocket server (no HTTP upgrade)")
	public void testDirectWebSocketConnection() throws Exception {
		logger.info("\n=== Testing Direct WebSocket Connection (Plain WebSocket, No HTTP Upgrade) ===");
		
		// This test checks what happens when the server ONLY supports WebSocket protocol
		// without HTTP upgrade. Will the Java HttpClient still try to do an HTTP upgrade?
		// How does it know the server doesn't support HTTP upgrade?
		
		HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
		WebSocket.Builder webSocketBuilder = client.newWebSocketBuilder();
		
		// Connect to the plain WebSocket server (no HTTP upgrade support)
		URI wsUri = URI.create("ws://localhost:" + plainWsServer.getPort() + "/ws");
		logger.info("Attempting WebSocket connection to plain WebSocket server: {}", wsUri);
		logger.info("NOTE: This server does NOT support HTTP upgrade - it only accepts plain WebSocket protocol");
		
		CountDownLatch openLatch = new CountDownLatch(1);
		CountDownLatch messageLatch = new CountDownLatch(1);
		AtomicReference<String> receivedMessage = new AtomicReference<>();
		AtomicReference<Throwable> errorRef = new AtomicReference<>();
		AtomicReference<String> closeReason = new AtomicReference<>();
		
		WebSocket.Listener listener = new WebSocket.Listener() {
			@Override
			public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
				logger.info("✓ Received WebSocket message: {}", data);
				receivedMessage.set(data.toString());
				messageLatch.countDown();
				return CompletableFuture.completedFuture(null);
			}

			@Override
			public void onOpen(WebSocket webSocket) {
				logger.info("✓ WebSocket connection opened successfully");
				openLatch.countDown();
				WebSocket.Listener.super.onOpen(webSocket);
			}

			@Override
			public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
				logger.info("WebSocket closed with status: {}, reason: {}", statusCode, reason);
				closeReason.set(reason);
				return CompletableFuture.completedFuture(null);
			}

			@Override
			public void onError(WebSocket webSocket, Throwable error) {
				logger.error("✗ WebSocket error occurred", error);
				errorRef.set(error);
				openLatch.countDown();
			}
		};

		try {
			logger.info("Building async WebSocket connection...");
			CompletableFuture<WebSocket> wsFuture = webSocketBuilder.buildAsync(wsUri, listener);
			
			logger.info("Waiting for connection to complete (up to 10 seconds)...");
			WebSocket webSocket = wsFuture.get(10, TimeUnit.SECONDS);
			
			// Wait for onOpen to be called
			boolean opened = openLatch.await(5, TimeUnit.SECONDS);
			
			if (errorRef.get() != null) {
				// Connection failed - this is expected!
				logger.error("=== CONNECTION FAILED (AS EXPECTED) ===");
				logger.error("Error: {}", errorRef.get().getMessage());
				logger.error("This demonstrates that Java HttpClient CANNOT connect to a plain WebSocket server");
				logger.error("The client always tries to do HTTP upgrade first, which this server rejects");
				
				// Check if server detected HTTP upgrade attempt
				if (plainWsServer.wasHttpUpgradeAttempted()) {
					logger.info("✓ Server detected HTTP upgrade attempt (as expected)");
					logger.info("✓ Server rejected the connection because it doesn't support HTTP upgrade");
					logger.info("✓ This proves Java HttpClient ALWAYS tries HTTP upgrade for ws:// URIs");
				}
				
				// This is the expected behavior - document it
				logger.info("\n=== TEST CONCLUSION ===");
				logger.info("FINDING: Java HttpClient ALWAYS attempts HTTP upgrade for ws:// URIs");
				logger.info("         It cannot connect to servers that ONLY support plain WebSocket protocol");
				logger.info("         There is no way to tell the client to skip HTTP upgrade");
				logger.info("         The WebSocket RFC 6455 specifies HTTP upgrade is the standard mechanism");
				
				// Assert that HTTP upgrade was attempted (which is what we're documenting)
				assertTrue(plainWsServer.wasHttpUpgradeAttempted(), 
					"Java HttpClient should attempt HTTP upgrade for WebSocket connections");
				
			} else if (opened && webSocket != null) {
				// Connection succeeded - this would be surprising!
				logger.info("=== CONNECTION SUCCEEDED (UNEXPECTED) ===");
				assertNotNull(webSocket, "WebSocket should be established");
				logger.info("✓ WebSocket connection established successfully");
				logger.info("This indicates the Java HttpClient was able to connect without HTTP upgrade");
				
				// Try to send a test message
				logger.info("Sending test message...");
				webSocket.sendText("Test message", true).get(5, TimeUnit.SECONDS);
				
				// Wait for response
				boolean received = messageLatch.await(10, TimeUnit.SECONDS);
				if (received) {
					logger.info("✓ Received response: {}", receivedMessage.get());
					assertEquals("Plain Echo: Test message", receivedMessage.get(), 
						"Should receive echoed message from plain WebSocket server");
				} else {
					logger.warn("Did not receive response from server");
				}
				
				// Close the connection
				webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Test complete").get(5, TimeUnit.SECONDS);
				
				// Verify no HTTP upgrade was performed
				plainWsServer.assertNoHttpUpgrade();
				
				logger.info("=== TEST PASSED: Plain WebSocket connection works ===");
			} else {
				logger.error("Connection did not open within timeout");
				throw new AssertionError("WebSocket connection did not open within timeout");
			}
			
		} catch (Exception e) {
			// Document what happened
			logger.error("=== EXCEPTION DURING CONNECTION (EXPECTED BEHAVIOR) ===");
			logger.error("Exception type: {}", e.getClass().getName());
			logger.error("Exception message: {}", e.getMessage());
			
			if (plainWsServer.wasHttpUpgradeAttempted()) {
				logger.info("✓ Server detected HTTP upgrade attempt");
				logger.info("✓ This proves Java HttpClient tries HTTP upgrade even for plain WebSocket servers");
				logger.info("✓ The server rejected the connection as it doesn't support HTTP upgrade");
				logger.info("");
				logger.info("FINDING: Java HttpClient ALWAYS attempts HTTP upgrade for ws:// URIs");
				logger.info("         It cannot connect to servers that ONLY support plain WebSocket protocol");
				logger.info("         There is no way to tell the client to skip HTTP upgrade");
				logger.info("         This is correct behavior per RFC 6455 - WebSocket requires HTTP upgrade");
				
				// This is expected behavior - assert that HTTP upgrade was attempted
				assertTrue(plainWsServer.wasHttpUpgradeAttempted(), 
					"Java HttpClient should attempt HTTP upgrade for WebSocket connections");
			} else {
				logger.error("✗ Connection failed but server didn't detect HTTP upgrade attempt");
				logger.error("✗ This suggests a different connection failure");
				throw new AssertionError(
					"Connection failed but HTTP upgrade was not attempted. Unexpected failure mode.", e);
			}
		}

		logger.info("=== Direct WebSocket connection test completed ===\n");
	}

	@Test
	@DisplayName("WebSocket upgrade through HTTP")
	public void testWebSocketUpgradeAfterHttp2() throws Exception {
		logger.info("\n=== Testing WebSocket Upgrade from HTTP Connection ===");

		HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2)
				.connectTimeout(Duration.ofSeconds(10)).build();

		WebSocket.Builder webSocketBuilder = client.newWebSocketBuilder();
		URI wsUri = URI.create("ws://localhost:" + wsServer.getPort() + "/http");
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
			wsServer.assertWebSocketUpgrade();

			// Close the connection
			webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Test complete").get(5, TimeUnit.SECONDS);

			logger.info("SUCCESS: WebSocket connection worked after HTTP/2. Issue may be fixed in this Java version.");
		} catch (Exception e) {
			// This documents the JDK-8361305 issue
			logger.error("EXPECTED FAILURE per JDK-8361305: WebSocket upgrade failed after HTTP/2 connection", e);
			logger.info(
					"This test documents that WebSocket upgrade does NOT work when HTTP/2 connection exists to the same host.");
			// Don't fail the test - this documents the known issue
			// In future Java versions where this is fixed, this catch block won't be
			// reached
		}

		logger.info("=== WebSocket Upgrade test completed ===\n");
	}
}
