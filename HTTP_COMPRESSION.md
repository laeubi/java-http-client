# HTTP Compression Support in Java HTTP Client

## Problem Statement

[HTTP compression](https://en.wikipedia.org/wiki/HTTP_compression) is a fundamental capability in modern HTTP communication that provides significant benefits:

1. **Reduced Bandwidth Usage**: Compression reduces data transferred over the network, which is crucial for mobile connections and bandwidth-constrained environments
2. **Faster Transfers**: Reduced data size means faster completion of requests, allowing servers to handle more concurrent connections
3. **Better Cache Utilization**: Many proxies cache compressed content. Requesting uncompressed content can lead to cache misses, resulting in higher loads and slower downloads
4. **Improved Security**: Encryption benefits from compression as fewer bytes need to be encrypted/decrypted, and redundancy is reduced in ciphertext

**Currently, the Java HTTP Client does not support transparent HTTP compression out of the box.** This creates several significant drawbacks:

1. Each application must implement compression support independently
2. Implementation is non-trivial, requiring changes to both request and response handling
3. Compression is often forgotten, incompletely implemented, or contains bugs
4. Transport-level compression is treated as an application concern rather than handled transparently

## Current Behavior Demonstrated by Tests

The test suite in `JavaHttpClientCompressionTest` demonstrates the following current limitations:

### 1. No Automatic Accept-Encoding Header

The Java HTTP Client does **NOT** automatically add the `Accept-Encoding` header to requests:

```java
HttpRequest request = HttpRequest.newBuilder()
    .uri(uri)
    .GET()
    .build();

HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

// Result: Response does NOT contain Content-Encoding header
// The client never requested compression
```

**Test**: `testNoAutomaticAcceptEncodingHeader()` and `testHttpsNoAutomaticAcceptEncodingHeader()`

### 2. No Automatic Decompression

Even when the `Accept-Encoding` header is manually added and the server responds with compressed content, the Java HTTP Client does **NOT** automatically decompress the response:

```java
HttpRequest request = HttpRequest.newBuilder()
    .uri(uri)
    .header("Accept-Encoding", "gzip")
    .GET()
    .build();

HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());

// Result: Response body contains raw gzip-compressed bytes
// Application must manually decompress the data
byte[] compressed = response.body();
// compressed[0] == 0x1f, compressed[1] == 0x8b (gzip magic number)
```

**Test**: `testNoAutomaticDecompression()`

### 3. Manual Implementation Required

Applications must implement both sides of compression manually:

```java
// Step 1: Manually add Accept-Encoding header
HttpRequest request = HttpRequest.newBuilder()
    .uri(uri)
    .header("Accept-Encoding", "gzip")
    .GET()
    .build();

HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());

// Step 2: Check if response is compressed
String contentEncoding = response.headers().firstValue("content-encoding").orElse("");

// Step 3: Manually decompress if needed
if ("gzip".equals(contentEncoding)) {
    byte[] compressed = response.body();
    ByteArrayInputStream bais = new ByteArrayInputStream(compressed);
    GZIPInputStream gzipIn = new GZIPInputStream(bais);
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    
    byte[] buffer = new byte[1024];
    int len;
    while ((len = gzipIn.read(buffer)) > 0) {
        baos.write(buffer, 0, len);
    }
    
    String decompressed = baos.toString("UTF-8");
    // Now we have the actual content
}
```

**Test**: `testManualCompressionRequired()` and `testHttpsManualCompression()`

## Proposed Solution

The Java HTTP Client should support **transparent HTTP compression** similar to other modern HTTP client implementations (e.g., curl, browsers, popular libraries in other languages):

### Default Behavior (Transparent Mode)

When no `Accept-Encoding` header is explicitly set by the application:

1. **Request**: The client automatically adds `Accept-Encoding: gzip, deflate` (or similar appropriate default)
2. **Response**: When the server responds with `Content-Encoding: gzip` (or other supported encoding), the client automatically decompresses the content
3. **BodyHandler**: The `BodyHandler` receives already-decompressed content, requiring no special handling from the application

### Example of Proposed Behavior

```java
// Application code - no compression-specific code needed
HttpRequest request = HttpRequest.newBuilder()
    .uri(uri)
    .GET()
    .build();

HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

// Result: Client automatically:
//   - Sent "Accept-Encoding: gzip, deflate"
//   - Received compressed response
//   - Decompressed the content transparently
//   - Returned decompressed string to application
String content = response.body(); // Already decompressed!
```

### Opt-Out Mechanism

Applications should be able to disable transparent compression when needed:

```java
// Option 1: Explicitly request no compression
HttpRequest request = HttpRequest.newBuilder()
    .uri(uri)
    .header("Accept-Encoding", "identity")  // RFC 2616 Section 14.3
    .GET()
    .build();

// Option 2: Custom compression handling
HttpRequest request = HttpRequest.newBuilder()
    .uri(uri)
    .header("Accept-Encoding", "br")  // Request only Brotli
    .GET()
    .build();
// When a custom Accept-Encoding is set, client uses it as-is
// and does not perform automatic decompression
```

### Compatibility

The proposed solution is backward compatible:

- Existing applications that don't use compression continue to work (they now get compression for free)
- Applications that manually implement compression can either:
  - Remove their manual implementation and benefit from transparent handling
  - Continue using manual implementation by setting custom `Accept-Encoding` headers
- The `identity` encoding allows explicit opt-out per RFC 2616

## Technical Specification

### Supported Encodings

Initial implementation should support:
- `gzip` - GZIP compression (RFC 1952)
- `deflate` - DEFLATE compression (RFC 1951)

Future extensions could include:
- `br` - Brotli compression (RFC 7932)
- `zstd` - Zstandard compression

### Request Handling

1. If `Accept-Encoding` header is **not present** in request:
   - Add `Accept-Encoding: gzip, deflate` automatically
   - Mark request as using "transparent compression mode"

2. If `Accept-Encoding` header **is present** in request:
   - Use the provided value as-is
   - Disable transparent decompression (application handles it)

### Response Handling

1. Check for `Content-Encoding` header in response
2. If present and request was in "transparent mode":
   - Decompress content using appropriate algorithm
   - Make decompressed content available to BodyHandler
   - Optionally remove or update `Content-Encoding` header in response object
3. If present but request was **not** in "transparent mode":
   - Pass response as-is to application (no decompression)

### BodyHandler Integration

Decompression should happen before the `BodyHandler` receives data:

```
Network → HTTP Client → Decompression (if needed) → BodyHandler → Application
```

This ensures all existing `BodyHandler` implementations work without modification.

## References

- [RFC 2616 Section 14.3 - Accept-Encoding](https://datatracker.ietf.org/doc/html/rfc2616#section-14.3)
- [RFC 2616 Section 14.41 - Content-Encoding](https://datatracker.ietf.org/doc/html/rfc2616#section-14.41)
- [RFC 1952 - GZIP file format specification](https://datatracker.ietf.org/doc/html/rfc1952)
- [RFC 1951 - DEFLATE compression specification](https://datatracker.ietf.org/doc/html/rfc1951)
- [RFC 7932 - Brotli Compressed Data Format](https://datatracker.ietf.org/doc/html/rfc7932)
- [Wikipedia - HTTP compression](https://en.wikipedia.org/wiki/HTTP_compression)

## Test Results

The test suite (`JavaHttpClientCompressionTest`) validates:

✅ Java HTTP Client does NOT automatically add `Accept-Encoding` header (HTTP)  
✅ Java HTTP Client does NOT automatically add `Accept-Encoding` header (HTTPS)  
✅ Java HTTP Client does NOT automatically decompress gzip responses  
✅ Manual compression requires both request header and decompression code  
✅ HTTPS behaves identically to HTTP regarding compression  

All tests demonstrate the current limitation and the need for transparent compression support.

## Recommendation for JDK Enhancement Request

This document and the accompanying test suite (`JavaHttpClientCompressionTest`) should be used as the basis for submitting an enhancement request to the OpenJDK project. The tests provide concrete evidence of the current limitation, and this document proposes a clear, backward-compatible solution that aligns with HTTP standards and modern HTTP client implementations.

### Suggested JDK Issue Summary

**Title**: Add transparent HTTP compression support to java.net.http.HttpClient

**Component**: core-libs/java.net

**Type**: Enhancement

**Priority**: P4 (Nice to have)

**Description**: (See below for GitHub issue summary)

---

## For Maintainers

The test server (`NettyHttp2Server`) has been enhanced to support gzip compression when clients send the `Accept-Encoding: gzip` header. This allows the test suite to validate both HTTP/1.1 and HTTP/2 compression behavior over both HTTP and HTTPS protocols.
