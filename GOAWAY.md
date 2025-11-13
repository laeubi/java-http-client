# HTTP/2 GOAWAY Frame Handling

## Overview

This document describes the HTTP/2 GOAWAY frame, its purpose, error conditions, and the rationale for a specialized exception in Java's HTTP client to handle GOAWAY scenarios explicitly.

## What is a GOAWAY Frame?

The GOAWAY frame is defined in [RFC 7540 Section 6.8](https://datatracker.ietf.org/doc/html/rfc7540#section-6.8) as a mechanism for gracefully shutting down HTTP/2 connections. When a server or client sends a GOAWAY frame, it signals that it will no longer accept new streams on the current connection.

### GOAWAY Frame Structure

A GOAWAY frame contains:

- **Last-Stream-ID**: The highest stream ID that the sender will process
- **Error Code**: A 32-bit error code indicating why the connection is being closed
- **Debug Data**: Optional additional diagnostic information (opaque data)

### Common GOAWAY Error Codes

According to RFC 7540, the following error codes are defined:

| Error Code | Name | Description |
|------------|------|-------------|
| 0x0 | NO_ERROR | Graceful shutdown with no error |
| 0x1 | PROTOCOL_ERROR | Protocol violation detected |
| 0x2 | INTERNAL_ERROR | Internal error in the implementation |
| 0x3 | FLOW_CONTROL_ERROR | Flow-control protocol violated |
| 0x4 | SETTINGS_TIMEOUT | Timeout occurred waiting for SETTINGS acknowledgment |
| 0x5 | STREAM_CLOSED | Frame received for a closed stream |
| 0x6 | FRAME_SIZE_ERROR | Frame size exceeds maximum allowed |
| 0x7 | REFUSED_STREAM | Stream refused before processing |
| 0x8 | CANCEL | Used by the endpoint to indicate stream is no longer needed |
| 0x9 | COMPRESSION_ERROR | Unable to maintain header compression context |
| 0xa | CONNECT_ERROR | Connection error during CONNECT method |
| 0xb | ENHANCE_YOUR_CALM | Endpoint detected excessive load-generating behavior |
| 0xc | INADEQUATE_SECURITY | Security requirements not met |
| 0xd | HTTP_1_1_REQUIRED | Endpoint requires HTTP/1.1 instead of HTTP/2 |

## When Do Servers Send GOAWAY?

Servers may send GOAWAY frames in various situations:

1. **Graceful Shutdown**: Server is shutting down and needs to close connections cleanly (error code: NO_ERROR)
2. **Load Management**: Server is under excessive load and needs to shed connections (error code: ENHANCE_YOUR_CALM)
3. **Protocol Violations**: Client violated HTTP/2 protocol rules (error code: PROTOCOL_ERROR)
4. **Connection Limits**: Server has reached connection or stream limits
5. **Configuration Changes**: Server needs to apply configuration changes requiring connection restart
6. **Resource Exhaustion**: Server is running out of resources (error code: INTERNAL_ERROR)

## Current Java HTTP Client Behavior

As demonstrated in the test `JavaHttpClientGoawayTest.testHttp2GoawayIsHandled()`, the current Java HTTP client (java.net.http.HttpClient) handles GOAWAY frames internally but does not expose this information to client code in a structured way.

### Observed Behavior

When a GOAWAY frame is received:

1. The HTTP client logs the GOAWAY frame: `GoAway debugData Server shutting down`
2. The connection is marked for shutdown
3. Existing streams may complete successfully if they were started before the Last-Stream-ID
4. The connection is closed with a generic IOException: `connection closed locally` or similar

### The Problem

The current implementation has several limitations:

1. **Generic Exception**: GOAWAY results in a generic `IOException`, making it indistinguishable from network failures, timeouts, or other connection issues
2. **Loss of Context**: The error code and debug data from the GOAWAY frame are not available to application code
3. **Retry Ambiguity**: Applications cannot determine if a request should be retried or if the server explicitly rejected further processing
4. **Load Management**: Clients cannot distinguish between transient load conditions (ENHANCE_YOUR_CALM) and permanent failures

## Why Client Code Needs Explicit GOAWAY Handling

### 1. Intelligent Retry Logic

Different GOAWAY error codes require different retry strategies:

- **NO_ERROR (0x0)**: Safe to retry on a new connection
- **ENHANCE_YOUR_CALM (0xb)**: Should implement exponential backoff before retry
- **REFUSED_STREAM (0x7)**: Safe to retry immediately on a new connection
- **PROTOCOL_ERROR (0x1)**: Application bug; should not retry with same request
- **HTTP_1_1_REQUIRED (0xd)**: Should fallback to HTTP/1.1

### 2. Load Management and Backpressure

When servers send `ENHANCE_YOUR_CALM`, clients should:
- Reduce request rate
- Implement exponential backoff
- Potentially shed their own load

Without explicit GOAWAY handling, clients may aggressively retry, worsening the server's load problem.

### 3. Observability and Monitoring

Applications need to:
- Monitor GOAWAY frequency and error types
- Alert on abnormal patterns
- Distinguish between client-side and server-side issues
- Track connection stability metrics

### 4. Graceful Degradation

Applications can make informed decisions:
- Route traffic to alternate backends
- Enable circuit breakers
- Provide user feedback about service status
- Implement fallback strategies

## Proposed Solution: Specialized Exception

### Precedent in java.net.http

The `java.net.http` package already provides specialized exceptions for specific scenarios:

- **`HttpTimeoutException`**: Thrown when a request times out
- **`HttpConnectTimeoutException`**: Thrown when connection establishment times out
- **`WebSocketHandshakeException`**: Thrown when WebSocket handshake fails

These exceptions allow applications to handle specific failure modes differently than generic IOExceptions.

### Proposed Exception: HttpGoAwayException

A specialized `HttpGoAwayException` should extend `IOException` and provide:

#### Required Information

1. **Error Code**: The HTTP/2 error code from the GOAWAY frame
   - Type: `long` or enum representing error codes
   - Purpose: Enables conditional retry logic and error handling

2. **Last Stream ID**: The last stream ID the peer will process
   - Type: `int`
   - Purpose: Helps determine which requests were completed vs. rejected

3. **Debug Data**: Optional diagnostic information from the server
   - Type: `byte[]` or `String`
   - Purpose: Provides server-specific diagnostic information for logging and debugging

4. **Error Code Name**: Human-readable error code name
   - Type: `String` (e.g., "NO_ERROR", "ENHANCE_YOUR_CALM")
   - Purpose: Improves logging and debugging without looking up codes

#### Additional Useful Information

5. **Connection Details**: Information about the affected connection
   - Remote address/port
   - Connection age
   - Number of streams processed

6. **Request Context**: If the exception is thrown for a specific request
   - HTTP method and URI
   - Stream ID

### Example Usage

```java
try {
    HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
} catch (HttpGoAwayException e) {
    switch (e.getErrorCode()) {
        case NO_ERROR:
            // Graceful shutdown - safe to retry immediately
            retryOnNewConnection();
            break;
        case ENHANCE_YOUR_CALM:
            // Server overloaded - backoff and retry
            logger.warn("Server requested rate limiting: {}", e.getDebugData());
            exponentialBackoffAndRetry();
            break;
        case PROTOCOL_ERROR:
            // Client bug - do not retry
            logger.error("Protocol error: {}", e.getDebugData());
            reportBug();
            break;
        case REFUSED_STREAM:
            // Stream refused - retry on new connection
            retryOnNewConnection();
            break;
        default:
            // Other error - log and potentially retry with caution
            logger.error("GOAWAY received: {} - {}", e.getErrorCodeName(), e.getDebugData());
            conditionalRetry();
    }
} catch (IOException e) {
    // Network or other IO error - different handling
    handleNetworkError(e);
}
```

## Implementation Considerations

### Backward Compatibility

- The exception should extend `IOException` to maintain compatibility with existing catch blocks
- Applications currently catching `IOException` will continue to work
- Applications wanting specific GOAWAY handling can add specific catch blocks

### When to Throw

The exception should be thrown when:
1. A GOAWAY frame is received and affects an in-flight request
2. A new request cannot be processed because a GOAWAY was previously received
3. The connection is being closed due to a GOAWAY

### Connection Pooling

The HTTP client should:
- Remove connections that received GOAWAY from the connection pool
- Not attempt new streams on connections that received GOAWAY
- Provide clear error messages when requests cannot be completed

## Current Test Case

The test `JavaHttpClientGoawayTest.testHttp2GoawayIsHandled()` currently expects the GOAWAY handling to fail with a specific exception:

```java
// Here we have no way to know any of the details of the GOAWAY see GOAWAY.md
// for further rationale.
assertNotEquals(IOException.class, cause.getClass());
```

This test validates that:
1. GOAWAY frames are received from the server
2. The exception thrown is NOT a generic IOException but something more specific (currently this test passes because the request actually completes successfully with NO_ERROR GOAWAY)

The test should be updated once a specialized exception is implemented to verify:
- Correct error code propagation
- Debug data availability
- Proper exception type

## References

- [RFC 7540 - HTTP/2](https://datatracker.ietf.org/doc/html/rfc7540)
- [RFC 7540 Section 6.8 - GOAWAY](https://datatracker.ietf.org/doc/html/rfc7540#section-6.8)
- [RFC 7540 Section 7 - Error Codes](https://datatracker.ietf.org/doc/html/rfc7540#section-7)
