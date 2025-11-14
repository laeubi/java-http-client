# GitHub Issue Summary: Add Transparent HTTP Compression Support to Java HTTP Client

## Title
Add transparent HTTP compression support to java.net.http.HttpClient

## Component
core-libs/java.net

## Type
Enhancement

## Summary

The Java HTTP Client (java.net.http.HttpClient) does not support transparent HTTP compression, requiring applications to manually implement both request header management and response decompression. This enhancement request proposes adding automatic compression support similar to other modern HTTP clients.

## Problem

HTTP compression is a fundamental HTTP feature providing significant benefits:
- Reduced bandwidth usage (critical for mobile connections)
- Faster request completion (allowing servers to handle more concurrent connections)
- Better cache utilization (proxies often cache compressed content)
- Improved security (fewer bytes to encrypt/decrypt)

**Current limitations demonstrated by test suite:**

1. The HttpClient does NOT automatically add `Accept-Encoding` header to requests
2. The HttpClient does NOT automatically decompress compressed responses
3. Applications must manually implement both sides of compression support

This creates drawbacks:
- Each application must implement compression independently
- Implementation is non-trivial and error-prone
- Compression is often forgotten or incompletely implemented
- Transport-level compression is treated as an application concern

## Proposed Solution

Add transparent compression support with the following behavior:

**Default (Transparent Mode):**
- Client automatically adds `Accept-Encoding: gzip, deflate` when not explicitly set
- Client automatically decompresses responses based on `Content-Encoding` header
- BodyHandlers receive decompressed content transparently

**Opt-Out Mechanism:**
- Applications can set custom `Accept-Encoding` header to override default behavior
- Setting `Accept-Encoding: identity` explicitly disables compression (per RFC 2616)
- Custom Accept-Encoding values disable automatic decompression

## Benefits

- **Zero-code compression**: Applications get compression without any code changes
- **Backward compatible**: Existing applications continue to work unchanged
- **Standards compliant**: Follows RFC 2616 specifications
- **Consistent with industry**: Matches behavior of curl, browsers, and popular HTTP libraries

## Example Code

### Current Behavior (Manual Implementation Required)
```java
// Must manually add header
HttpRequest request = HttpRequest.newBuilder()
    .uri(uri)
    .header("Accept-Encoding", "gzip")
    .GET()
    .build();

HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());

// Must manually check and decompress
if ("gzip".equals(response.headers().firstValue("content-encoding").orElse(""))) {
    byte[] compressed = response.body();
    // ... manual decompression code (15+ lines) ...
}
```

### Proposed Behavior (Transparent)
```java
// No compression-specific code needed
HttpRequest request = HttpRequest.newBuilder()
    .uri(uri)
    .GET()
    .build();

HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
String content = response.body(); // Already decompressed!
```

## Supporting Materials

- **Test Suite**: `JavaHttpClientCompressionTest` demonstrates current limitations (5 tests, all passing)
- **Documentation**: `HTTP_COMPRESSION.md` provides detailed technical specification
- **Test Server**: Enhanced `NettyHttp2Server` supports compression for testing

All tests validate that:
- HttpClient does NOT add Accept-Encoding automatically (HTTP and HTTPS)
- HttpClient does NOT decompress responses automatically
- Manual implementation is required for compression support

## References

- [RFC 2616 Section 14.3 - Accept-Encoding](https://datatracker.ietf.org/doc/html/rfc2616#section-14.3)
- [RFC 2616 Section 14.41 - Content-Encoding](https://datatracker.ietf.org/doc/html/rfc2616#section-14.41)
- [Wikipedia - HTTP compression](https://en.wikipedia.org/wiki/HTTP_compression)

## Testing

The enhancement should include:
- Unit tests for gzip and deflate compression/decompression
- Integration tests for transparent mode behavior
- Tests for opt-out mechanism (custom Accept-Encoding headers)
- Tests for both HTTP/1.1 and HTTP/2
- Tests for both HTTP and HTTPS protocols

## Priority Rationale

While HTTP compression is an important feature, applications can currently implement it manually (albeit with significant effort). This enhancement would primarily improve developer experience and reduce boilerplate code. Suggested priority: P4 (Nice to have).
