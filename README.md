# Java HTTP Client Testing

Check of Java HTTP Client Limitations with HTTP/2 and GOAWAY frames

## Overview

This project tests Java 11+ HTTP Client behavior with HTTP/2 protocol features, specifically:
- HTTP/2 Upgrade from HTTP/1.1
- GOAWAY frame handling
- Connection management over HTTP and HTTPS
- ALPN negotiation and custom SSLParameters behavior

## Test Scenarios

### 1. HTTP/2 Upgrade over HTTP
Tests the HTTP/2 upgrade mechanism where the client starts with HTTP/1.1 and upgrades to HTTP/2 using the `Upgrade` header (h2c - cleartext HTTP/2).
This is performed in `JavaHttpClientUpgradeTest.testHttp2UpgradeOverHttp()`

### 2. GOAWAY Frame Handling
Tests how the Java HTTP Client handles GOAWAY frames sent by the server, which signal connection shutdown/timeout see https://datatracker.ietf.org/doc/html/rfc7540#section-6.8

### 3. Connection Reuse and GOAWAY
Tests that connections receiving GOAWAY frames are not reused from the connection pool. Includes:
- Connection tracking with nonces to verify connection reuse
- Marking connections as stale and triggering GOAWAY
- Verifying that new connections are opened after GOAWAY
- Testing parallel requests with connection pooling

Tests are in `JavaHttpClientConnectionReuseTest`:
- `testConnectionReuseWithNonce()` - Verifies basic connection reuse
- `testGoawayOnStaleConnectionReuse()` - Tests GOAWAY behavior
- `testParallelRequestsWithConnectionPooling()` - Tests parallel requests

### 4. Custom SSLParameters with ALPN
Tests the behavior when custom `SSLParameters` are provided to the `HttpClient`. See the [Known Limitations](#known-limitations) section below for important findings.

### 5. HTTPS without ALPN Support
Tests the behavior when connecting to an HTTPS server that does not support ALPN protocol negotiation.

### 6. WebSocket Upgrade Support
Tests WebSocket connection support with Java HttpClient. Related to issue [JDK-8361305](https://bugs.java.com/bugdatabase/view_bug?bug_id=JDK-8361305).

Tests are in `JavaHttpClientWebSocketTest`:
- `testDirectWebSocketConnection()` - Verifies direct WebSocket connection (ws:// URI) works
- `testWebSocketUpgradeAfterHttp2()` - Tests WebSocket connection after HTTP connection to same host

The tests demonstrate that:
- Direct WebSocket connections using `HttpClient.newWebSocketBuilder()` work correctly on `ws://` URIs
- WebSocket upgrade behavior when HTTP/2 connections already exist to the same host

## Known Limitations

### Custom SSLParameters Interfere with HTTP/2

**Issue**: When you configure a Java `HttpClient` with custom `SSLParameters` using `.sslParameters()`, it overrides the client's internal ALPN configuration, preventing HTTP/2 negotiation.

**Behavior**:
- Even if you explicitly set ALPN protocols: `sslParameters.setApplicationProtocols(new String[] {"h2", "http/1.1"})`
- The connection will fall back to HTTP/1.1 over TLS
- This happens because custom `SSLParameters` override the `HttpClient`'s internal ALPN configuration

**Solution**: Do NOT use `.sslParameters()` if you need HTTP/2 over HTTPS. Instead, let the `HttpClient` manage ALPN automatically by only setting:
```java
HttpClient client = HttpClient.newBuilder()
    .version(HttpClient.Version.HTTP_2)
    .sslContext(customSslContext)  // OK - only custom trust settings
    // .sslParameters(...)           // AVOID - breaks HTTP/2 ALPN
    .build();
```

**Tests demonstrating this**:
- `JavaHttpClientUpgradeTest.testHttp2WithCustomSSLParametersALPNAttempt()`
- `JavaHttpClientUpgradeTest.testHttp2WithCustomSSLParametersNoALPN()`

### HTTPS Requires ALPN for HTTP/2

**Issue**: Unlike cleartext HTTP which supports HTTP/2 upgrade using the `Upgrade` header, HTTPS connections **require ALPN** (Application-Layer Protocol Negotiation) for HTTP/2.

**Behavior**:
- Per [RFC 7540](https://datatracker.ietf.org/doc/html/rfc7540), HTTP/2 over TLS requires ALPN during the TLS handshake
- There is NO upgrade mechanism from HTTP/1.1 to HTTP/2 over an existing HTTPS/TLS connection
- If the HTTPS server does not support ALPN, the connection will use HTTP/1.1
- If ALPN negotiation fails or is not configured, there is no fallback upgrade path

**Tests demonstrating this**:
- `JavaHttpClientUpgradeTest.testHttpsServerWithoutALPN()`

## Prerequisites

- Java 11 or higher
- Maven 3.6 or higher

## Building

```bash
mvn clean compile
```

## Running Tests

Run all tests:
```bash
mvn test
```

Run with verbose logging:
```bash
mvn test -Dorg.slf4j.simpleLogger.defaultLogLevel=debug
```

## Project Structure

```
├── src/main/java/io/github/laeubi/httpclient/
│   ├── NettyHttp2Server.java          # Netty-based HTTP/2 test server with connection tracking
│   └── NettyWebSocketServer.java      # Netty-based WebSocket test server
├── src/test/java/io/github/laeubi/httpclient/
│   ├── JavaHttpClientUpgradeTest.java # HTTP/2 upgrade and ALPN tests
│   ├── JavaHttpClientGoawayTest.java  # GOAWAY frame handling tests
│   ├── JavaHttpClientConnectionReuseTest.java # Connection reuse and GOAWAY tests
│   ├── JavaHttpClientWebSocketTest.java # WebSocket connection tests
│   └── JavaHttpClientBase.java        # Base test class with utilities
├── src/main/resources/
│   └── simplelogger.properties        # Logging configuration
├── .github/workflows/
│   └── ci.yml                         # GitHub Actions workflow
└── pom.xml                            # Maven project configuration
```

## Logging

The project uses SLF4J with the Simple Logger implementation. Log levels can be adjusted in `src/main/resources/simplelogger.properties` or via system properties:

```bash
mvn test -Dorg.slf4j.simpleLogger.log.io.github.laeubi.httpclient=debug
```

## CI/CD

The project includes GitHub Actions workflows that run tests on multiple Java versions:
- Java 17.0.17+
- Java 21.0.8+

See `.github/workflows/ci.yml` for the complete CI configuration.
