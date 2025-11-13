# Java HTTP Client Testing

Check of Java HTTP Client Limitations with HTTP/2 and GOAWAY frames

## Overview

This project tests Java 11+ HTTP Client behavior with HTTP/2 protocol features, specifically:
- HTTP/2 Upgrade from HTTP/1.1
- GOAWAY frame handling
- Connection management over HTTP and HTTPS

## Features

- **Netty-based HTTP/2 Server**: A test server that supports:
  - HTTP/2 upgrade from HTTP/1.1 (clear-text HTTP/2)
  - HTTP/2 over HTTPS with ALPN negotiation
  - GOAWAY frame sending for connection shutdown testing
  - Comprehensive logging for debugging

- **Comprehensive Test Suite**: JUnit 5 tests that verify:
  - HTTP/2 upgrade mechanism over plain HTTP
  - HTTP/2 over HTTPS with ALPN
  - GOAWAY frame handling over both HTTP and HTTPS
  - Connection reuse with multiple requests

- **CI/CD Integration**: GitHub Actions workflow testing with:
  - Java 17.0.17+
  - Java 21.0.8+

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
│   └── NettyHttp2Server.java          # Netty-based HTTP/2 test server
├── src/test/java/io/github/laeubi/httpclient/
│   └── Http2ClientTest.java           # Test cases for Java HTTP Client
├── src/main/resources/
│   └── simplelogger.properties        # Logging configuration
├── .github/workflows/
│   └── ci.yml                         # GitHub Actions workflow
└── pom.xml                            # Maven project configuration
```

## Test Scenarios

### 1. HTTP/2 Upgrade over HTTP
Tests the HTTP/2 upgrade mechanism where the client starts with HTTP/1.1 and upgrades to HTTP/2 using the `Upgrade` header.

### 2. HTTP/2 over HTTPS with ALPN
Tests HTTP/2 over TLS using Application-Layer Protocol Negotiation (ALPN).

### 3. GOAWAY Frame Handling
Tests how the Java HTTP Client handles GOAWAY frames sent by the server, which signal connection shutdown.

### 4. Connection Reuse
Tests that the HTTP client properly reuses HTTP/2 connections for multiple requests.

## Known Java HTTP Client Limitations

This project helps identify and verify various limitations in the Java 11+ HTTP Client implementation:

1. **GOAWAY Frame Handling**: The behavior when receiving GOAWAY frames
2. **Connection Pooling**: How connections are reused and managed
3. **HTTP/2 Upgrade**: Compatibility with the HTTP/2 upgrade mechanism

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

## License

This project is for testing purposes to identify limitations in the Java HTTP Client.
