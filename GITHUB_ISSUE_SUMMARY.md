# GitHub Issue Summary: Add HTTP Caching Support to Java HTTP Client

## Title
Add HTTP caching support to java.net.http.HttpClient

## Component
core-libs/java.net

## Type
Enhancement

## Summary

The Java HTTP Client (java.net.http.HttpClient) does not support HTTP caching, requiring applications to manually implement cache storage, conditional requests, and response validation. This enhancement request proposes adding opt-in caching support similar to the historical java.net.ResponseCache but with a modern design aligned with current HTTP standards.

## Problem

HTTP caching is a fundamental HTTP feature providing significant benefits:
- Reduced server load (prevents redundant requests)
- Faster response times (eliminates network round-trips)
- Reduced bandwidth usage (conditional requests only transfer data when content changes)
- Offline capabilities (can serve stale content when network unavailable)
- Better user experience (instant responses from cache)

**Current limitations demonstrated by test suite:**

1. The HttpClient does NOT automatically handle `Cache-Control` headers
2. The HttpClient does NOT automatically use ETags for conditional requests
3. The HttpClient does NOT handle 304 Not Modified responses transparently
4. Applications must manually implement complete caching lifecycle

This creates significant drawbacks:
- Each application must implement caching independently
- Implementation is non-trivial and error-prone (requires handling cache storage, ETags, Last-Modified, 304 responses, expiration)
- Caching is often forgotten or incompletely implemented
- HTTP-level caching is treated as an application concern

## Historical Context

Java has supported HTTP caching for the older java.net.URL connection mechanism since Java SE 6 via `java.net.ResponseCache`. However, this caching mechanism is **not available** for the modern `java.net.http.HttpClient` introduced in Java 11.

## Proposed Solution

Add opt-in HTTP caching support with configurable behavior modes:

### Option 1: Fully Transparent Mode
```java
HttpCache cache = HttpCache.automatic();
HttpClient client = HttpClient.newBuilder()
    .cache(cache)
    .build();

// Client automatically:
// - Checks cache and returns cached content if valid
// - Adds conditional headers (If-None-Match, If-Modified-Since)
// - Handles 304 responses by returning cached content
// - Returns content transparently as if it were a 200 response
HttpResponse<String> response = client.send(request, 
    HttpResponse.BodyHandlers.ofString());
String content = response.body(); // From cache or server
```

### Option 2: Semi-Automatic Mode
```java
HttpCache cache = HttpCache.semiAutomatic();
HttpClient client = HttpClient.newBuilder()
    .cache(cache)
    .build();

// Client automatically:
// - Adds conditional headers based on cached ETags/Last-Modified
// - Sends request to server (does not serve from cache automatically)
// - Returns actual 304 response to application for handling
HttpResponse<String> response = client.send(request, 
    HttpResponse.BodyHandlers.ofString());
if (response.statusCode() == 304) {
    // Application retrieves cached content
}
```

### Option 3: Disabled (Default)
```java
// No cache configuration - current behavior
HttpClient client = HttpClient.newBuilder().build();
```

### Cache Configuration

Support flexible cache configuration:

```java
// In-memory cache with size limit
HttpCache cache = HttpCache.newBuilder()
    .maximumSize(100)  // Max 100 cached responses
    .expireAfterWrite(Duration.ofHours(1))
    .build();

// Custom storage (e.g., disk-based)
HttpCache cache = HttpCache.newBuilder()
    .storage(new CustomCacheStorage())
    .build();

// Shared cache across multiple HttpClient instances
HttpCache sharedCache = HttpCache.shared();
```

### Key Features

**Cache Control Directives:**
- Respect `Cache-Control: no-store` (do not cache)
- Respect `Cache-Control: no-cache` (cache but always revalidate)
- Respect `Cache-Control: max-age=N` (cache for N seconds)
- Support `Cache-Control: private` vs `public`

**Conditional Requests:**
- Use `ETag` with `If-None-Match` for validation
- Use `Last-Modified` with `If-Modified-Since` for validation
- Handle 304 Not Modified responses appropriately

**Vary Header Support:**
- Generate cache keys based on `Vary` header
- Cache key: URI + relevant request header values

**Stale Content Handling:**
- Support `stale-while-revalidate` (RFC 5861)
- Return stale content while fetching fresh copy in background
- Support `stale-if-error` for offline scenarios

## Benefits

- **Standards compliant**: Follows RFC 7234 (HTTP Caching) and RFC 7232 (Conditional Requests)
- **Flexible**: Multiple modes to suit different application needs
- **Opt-in**: Backward compatible, existing applications unaffected
- **Modern design**: Builder pattern, fluent API consistent with HttpClient
- **Efficient**: Reduces server load and improves application performance
- **Familiar**: Similar to java.net.ResponseCache but modernized

## Example Code

### Current Behavior (Manual Implementation Required)
```java
// Complex manual implementation required:
// 1. Cache storage
Map<URI, CacheEntry> cache = new HashMap<>();

// 2. First request
HttpResponse<String> response = client.send(request, 
    HttpResponse.BodyHandlers.ofString());
String etag = response.headers().firstValue("etag").orElse(null);
cache.put(uri, new CacheEntry(response.body(), etag));

// 3. Subsequent request - manually add conditional header
HttpRequest request2 = HttpRequest.newBuilder()
    .uri(uri)
    .header("If-None-Match", etag)  // Manual!
    .GET()
    .build();

HttpResponse<String> response2 = client.send(request2, 
    HttpResponse.BodyHandlers.ofString());

// 4. Manually handle 304
if (response2.statusCode() == 304) {
    // Must manually retrieve from cache
    content = cache.get(uri).content;
} else {
    content = response2.body();
}
```

### Proposed Behavior (Transparent)
```java
// Simple automatic caching:
HttpCache cache = HttpCache.automatic();
HttpClient client = HttpClient.newBuilder()
    .cache(cache)
    .build();

HttpResponse<String> response = client.send(request, 
    HttpResponse.BodyHandlers.ofString());
String content = response.body(); // Automatically cached/validated!
```

## Supporting Materials

- **Test Suite**: `JavaHttpClientCachingTest` demonstrates current limitations (6 tests, all passing)
- **Documentation**: `HTTP_CACHING.md` provides detailed technical specification
- **Test Server**: Enhanced `NettyHttp2Server` supports caching headers for testing

All tests validate that:
- HttpClient does NOT handle Cache-Control headers automatically
- HttpClient does NOT use ETags for conditional requests automatically
- HttpClient does NOT handle 304 responses transparently
- Manual implementation is required with significant complexity

## References

- [RFC 7234 - HTTP Caching](https://datatracker.ietf.org/doc/html/rfc7234)
- [RFC 7232 - HTTP Conditional Requests](https://datatracker.ietf.org/doc/html/rfc7232)
- [RFC 5861 - HTTP Cache-Control Extensions for Stale Content](https://datatracker.ietf.org/doc/html/rfc5861)
- [Wikipedia - Web cache](https://en.wikipedia.org/wiki/Web_cache)
- [MDN - HTTP Caching](https://developer.mozilla.org/en-US/docs/Web/HTTP/Caching)
- [Java SE 6+ ResponseCache](https://docs.oracle.com/javase/8/docs/technotes/guides/net/http-cache.html)

## Testing

The enhancement should include:
- Unit tests for cache storage and retrieval
- Tests for ETag and Last-Modified validation
- Tests for Cache-Control directive handling
- Integration tests for transparent and semi-automatic modes
- Tests for Vary header support
- Tests for stale content handling
- Tests for both HTTP/1.1 and HTTP/2
- Tests for both HTTP and HTTPS protocols

## Implementation Considerations

### Compatibility with java.net.ResponseCache

Two potential approaches:

**Option A: Separate Implementation (Recommended)**
- Create new `java.net.http.HttpCache` API independent of `java.net.ResponseCache`
- Modern design with builder pattern and fluent API
- Better aligned with HttpClient design philosophy
- Allows for HTTP/2-specific optimizations

**Option B: Compatibility Bridge**
- Make HttpClient use existing `java.net.ResponseCache` if configured
- Allows migration of existing ResponseCache implementations
- May have design limitations due to older API

**Recommendation**: Option A with optional bridge for migration scenarios.

### Security Considerations

- Respect `private` vs `public` cache directives
- Do not cache responses with authentication credentials by default
- Validate cache keys to prevent cache poisoning
- Consider size limits to prevent memory exhaustion
- Support secure deletion of sensitive cached content

### Performance Considerations

- Use efficient cache key generation
- Support concurrent access to cache
- Minimize synchronization overhead
- Consider using soft references for memory management
- Support background revalidation to avoid blocking

## Priority Rationale

HTTP caching is a fundamental web feature supported by all major browsers and HTTP clients. While applications can implement caching manually, it's complex and error-prone. This enhancement would:
- Reduce boilerplate code significantly
- Improve application performance by default
- Align Java HTTP Client with modern HTTP client implementations
- Reduce server load across the ecosystem

Suggested priority: **P3** (Desirable feature that improves developer experience and application performance)

