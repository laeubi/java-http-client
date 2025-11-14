# Java HTTP Client Testing

Check of Java HTTP Client Limitations with HTTP/2, GOAWAY frames, HTTP Compression, HTTP Caching, HTTP Authentication, Forms, and Multipart

## Overview

This project tests Java 11+ HTTP Client behavior with HTTP/2 protocol features, HTTP compression, HTTP caching, HTTP authentication, and form/multipart handling, specifically:
- HTTP/2 Upgrade from HTTP/1.1
- GOAWAY frame handling
- Connection management over HTTP and HTTPS
- ALPN negotiation and custom SSLParameters behavior
- HTTP compression support (or lack thereof)
- HTTP caching support (or lack thereof)
- HTTP authentication schemes support (Basic, Digest, NTLM, SPNEGO/Kerberos)
- HTML form submission support (application/x-www-form-urlencoded)
- Multipart/form-data support including file uploads

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

### 7. HTTP Compression Support
Tests demonstrating that Java HTTP Client does NOT support transparent HTTP compression out of the box. See [HTTP_COMPRESSION.md](HTTP_COMPRESSION.md) for detailed documentation.

Tests are in `JavaHttpClientCompressionTest`:
- `testNoAutomaticAcceptEncodingHeader()` - Verifies client does NOT add Accept-Encoding automatically (HTTP)
- `testNoAutomaticDecompression()` - Verifies client does NOT decompress gzip responses automatically
- `testManualCompressionRequired()` - Demonstrates manual implementation required for compression
- `testHttpsNoAutomaticAcceptEncodingHeader()` - Verifies same behavior for HTTPS
- `testHttpsManualCompression()` - Shows manual compression works with HTTPS

The tests demonstrate that:
- Java HTTP Client does NOT automatically add `Accept-Encoding` header
- Java HTTP Client does NOT automatically decompress compressed responses
- Applications must manually implement both request headers and response decompression
- Both HTTP and HTTPS behave identically regarding compression

### 8. HTTP Caching Support
Tests demonstrating that Java HTTP Client does NOT support HTTP caching out of the box. See [HTTP_CACHING.md](HTTP_CACHING.md) for detailed documentation.

Tests are in `JavaHttpClientCachingTest`:
- `testNoCacheControlHandling()` - Verifies client does NOT handle Cache-Control headers automatically
- `testNoAutomaticETagHandling()` - Verifies client does NOT use ETags for conditional requests
- `testManualIfNoneMatchRequired()` - Demonstrates manual If-None-Match header required
- `testNo304Handling()` - Verifies client does NOT handle 304 Not Modified transparently
- `testManualCachingRequired()` - Shows complex manual implementation required
- `testHttpsNoCaching()` - Verifies same behavior for HTTPS

The tests demonstrate that:
- Java HTTP Client does NOT automatically handle Cache-Control headers
- Java HTTP Client does NOT automatically use ETags for conditional requests
- Java HTTP Client does NOT handle 304 Not Modified responses transparently
- Applications must manually implement complete caching lifecycle
- Both HTTP and HTTPS behave identically regarding caching

### 9. HTTP Authentication Support

Tests evaluating support for common HTTP authentication schemes. See [HTTP_AUTHENTICATION.md](HTTP_AUTHENTICATION.md) for detailed documentation.

Tests are in `JavaHttpClientAuthenticationTest`:
- `testBasicAuthenticationNativeSupport()` - Verifies native Basic auth via Authenticator
- `testBasicAuthenticationManualImplementation()` - Tests manual Basic auth implementation
- `testBasicAuthenticationHttps()` - Tests Basic auth over HTTPS
- `testBasicAuthenticationChallengeResponse()` - Tests challenge-response flow
- `testDigestAuthenticationNativeSupport()` - Tests Digest auth support (limited)
- `testDigestAuthenticationManualImplementation()` - Shows Digest implementation complexity
- `testNTLMAuthenticationNativeSupport()` - Tests NTLM support (not supported)
- `testNTLMAuthenticationManualImplementation()` - Shows NTLM implementation complexity
- `testSPNEGOAuthenticationNativeSupport()` - Tests SPNEGO/Kerberos (limited via JGSS)
- `testSPNEGOAuthenticationManualImplementation()` - Shows SPNEGO implementation requirements
- `testBearerTokenAuthentication()` - Tests Bearer token (OAuth 2.0) implementation
- `testAuthenticationSchemeSummary()` - Displays comprehensive support matrix

The tests demonstrate that:
- **HTTP Basic:** Fully supported natively via `java.net.Authenticator`
- **HTTP Digest:** Limited native support, varies by JDK version
- **NTLM:** Not supported natively, requires third-party libraries (Apache HttpClient + JCIFS)
- **SPNEGO/Kerberos:** Not supported via Authenticator, can use Java GSS-API with infrastructure
- **Bearer Token (OAuth 2.0):** Not supported natively, requires manual implementation

See [GITHUB_ISSUE_SUMMARY.md](GITHUB_ISSUE_SUMMARY.md) for a concise summary suitable for submitting as a JDK enhancement request.

### 10. HTML Forms and Multipart Support

Tests evaluating support for HTML form submissions and multipart/form-data requests. See [FORMS_AND_MULTIPART.md](FORMS_AND_MULTIPART.md) for detailed documentation.

Tests are in `JavaHttpClientFormsTest`:
- `testNoBuiltInFormAPI()` - Verifies client does NOT provide form data API
- `testManualFormDataImplementation()` - Demonstrates manual URL-encoded form submission
- `testFormDataWithSpecialCharacters()` - Tests URL encoding requirements
- `testCompleteFormHandlingRequired()` - Shows complete manual implementation needed

Tests are in `JavaHttpClientMultipartTest`:
- `testNoBuiltInMultipartAPI()` - Verifies client does NOT provide multipart API
- `testManualMultipartTextFields()` - Demonstrates manual multipart with text fields
- `testManualFileUpload()` - Tests file upload with multipart/form-data
- `testManualMultipartMixedContent()` - Tests mixed content (text fields + files)
- `testBoundaryHandling()` - Demonstrates boundary generation and formatting complexity

The tests demonstrate that:
- Java HTTP Client does NOT provide convenience APIs for form data submission
- Java HTTP Client does NOT provide convenience APIs for multipart/form-data
- Applications must manually build `application/x-www-form-urlencoded` strings with URL encoding
- Applications must manually construct multipart bodies with boundaries and headers
- File uploads require manual encoding and Content-Disposition header construction
- Both forms and multipart work but require complete manual implementation

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
│   ├── NettyWebSocketServer.java      # Netty-based WebSocket test server
│   ├── NettyAuthenticationServer.java # Netty-based authentication test server
│   └── NettyFormsServer.java          # Netty-based forms and multipart test server
├── src/test/java/io/github/laeubi/httpclient/
│   ├── JavaHttpClientUpgradeTest.java # HTTP/2 upgrade and ALPN tests
│   ├── JavaHttpClientGoawayTest.java  # GOAWAY frame handling tests
│   ├── JavaHttpClientConnectionReuseTest.java # Connection reuse and GOAWAY tests
│   ├── JavaHttpClientWebSocketTest.java # WebSocket connection tests
│   ├── JavaHttpClientCompressionTest.java # HTTP compression tests
│   ├── JavaHttpClientCachingTest.java # HTTP caching tests
│   ├── JavaHttpClientAuthenticationTest.java # HTTP authentication tests
│   ├── JavaHttpClientFormsTest.java   # HTML forms tests
│   ├── JavaHttpClientMultipartTest.java # Multipart/form-data tests
│   └── JavaHttpClientBase.java        # Base test class with utilities
├── src/main/resources/
│   └── simplelogger.properties        # Logging configuration
├── .github/workflows/
│   └── ci.yml                         # GitHub Actions workflow
├── HTTP_COMPRESSION.md                # HTTP compression documentation
├── HTTP_CACHING.md                    # HTTP caching documentation
├── HTTP_AUTHENTICATION.md             # HTTP authentication documentation
├── FORMS_AND_MULTIPART.md             # Forms and multipart/form-data documentation
├── GITHUB_ISSUE_SUMMARY.md            # JDK enhancement request summary
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
