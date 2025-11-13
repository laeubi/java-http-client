# Java HTTP Client Testing

Check of Java HTTP Client Limitations with HTTP/2 and GOAWAY frames

## Overview

This project tests Java 11+ HTTP Client behavior with HTTP/2 protocol features, specifically:
- HTTP/2 Upgrade from HTTP/1.1
- GOAWAY frame handling
- Connection management over HTTP and HTTPS

## Test Scenarios

### 1. HTTP/2 Upgrade over HTTP
Tests the HTTP/2 upgrade mechanism where the client starts with HTTP/1.1 and upgrades to HTTP/2 using the `Upgrade` header.
This is performed in `JavaHttpClientUpgrade`

### 2. GOAWAY Frame Handling
Tests how the Java HTTP Client handles GOAWAY frames sent by the server, which signal connection shutdown/timeout see https://datatracker.ietf.org/doc/html/rfc7540#section-6.8


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
