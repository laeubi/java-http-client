# HTTP Caching Support in Java HTTP Client

## Problem Statement

[HTTP caching](https://en.wikipedia.org/wiki/Web_cache) is a fundamental capability in modern web communication that provides significant benefits:

1. **Reduced Server Load**: Caching prevents redundant server requests by reusing previously fetched responses, allowing servers to handle more clients
2. **Faster Response Times**: Cached responses eliminate network round-trips, providing instant response to applications
3. **Reduced Bandwidth Usage**: Conditional requests (using ETags/Last-Modified) only transfer data when content has changed, saving bandwidth
4. **Offline Capabilities**: Well-designed caches can serve stale content when the network is unavailable
5. **Better User Experience**: Faster page loads and reduced latency improve application responsiveness

**Currently, the Java HTTP Client does not support HTTP caching out of the box.** This creates several significant drawbacks:

1. Each application must implement caching independently if needed
2. Implementation is non-trivial and error-prone, requiring careful handling of cache headers, validators, and response codes
3. Caching is often forgotten, incompletely implemented, or contains bugs
4. HTTP-level caching is treated as an application concern rather than handled transparently by the HTTP client

## Historical Context: java.net.ResponseCache

Interestingly, Java has supported HTTP caching for the older `java.net.URL` connection mechanism since Java SE 6:
- [java.net.ResponseCache](https://docs.oracle.com/javase/8/docs/technotes/guides/net/http-cache.html) documentation
- Provides pluggable caching mechanism with `java.net.ResponseCache` abstract class
- Allows applications to implement custom cache strategies
- Handles cache storage and retrieval automatically

However, this caching mechanism is **not available** for the modern `java.net.http.HttpClient` introduced in Java 11.

## Current Behavior

The test suite in `JavaHttpClientCachingTest` demonstrates the following current limitations:

### 1. No Automatic Cache-Control Header Handling

The Java HTTP Client does **NOT** automatically handle `Cache-Control` response headers:

```java
HttpRequest request = HttpRequest.newBuilder()
    .uri(uri)
    .GET()
    .build();

// First request
HttpResponse<String> response1 = client.send(request, HttpResponse.BodyHandlers.ofString());
// Server responds with: Cache-Control: max-age=3600

// Second identical request - goes to server again
HttpResponse<String> response2 = client.send(request, HttpResponse.BodyHandlers.ofString());

// Result: Both requests hit the server, caching headers are ignored
// Applications must manually implement cache storage and lookup
```

**Test**: `testNoCacheControlHandling()`

### 2. No Automatic ETag Support

The Java HTTP Client does **NOT** automatically use ETags for conditional requests:

```java
// First request
HttpResponse<String> response1 = client.send(request, HttpResponse.BodyHandlers.ofString());
String etag = response1.headers().firstValue("etag").orElse("");
// etag = "\"abc123\""

// Second request - must manually add If-None-Match header
HttpRequest request2 = HttpRequest.newBuilder()
    .uri(uri)
    .header("If-None-Match", etag)  // Manual implementation required
    .GET()
    .build();

HttpResponse<String> response2 = client.send(request2, HttpResponse.BodyHandlers.ofString());

// Result: Applications must manually track ETags and add conditional headers
```

**Test**: `testNoAutomaticETagHandling()`

### 3. No 304 Not Modified Handling

Even when manually implementing conditional requests, applications must handle 304 responses:

```java
HttpRequest request = HttpRequest.newBuilder()
    .uri(uri)
    .header("If-None-Match", previousETag)
    .GET()
    .build();

HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

if (response.statusCode() == 304) {
    // Application must manually retrieve cached content
    String cachedContent = retrieveFromCustomCache(uri);
} else {
    String content = response.body();
    storeInCustomCache(uri, content, response.headers());
}
```

**Test**: `testManual304Handling()`

### 4. Manual Implementation Required

Applications must implement the complete caching lifecycle:

```java
// Complex manual caching implementation required:

// 1. Cache Storage
class CacheEntry {
    String content;
    String etag;
    String lastModified;
    Instant expiresAt;
    Map<String, List<String>> headers;
}
Map<URI, CacheEntry> cache = new ConcurrentHashMap<>();

// 2. Check Cache Before Request
CacheEntry cached = cache.get(uri);
if (cached != null && cached.expiresAt.isAfter(Instant.now())) {
    // Use cached content
    return cached.content;
}

// 3. Add Conditional Headers
HttpRequest.Builder builder = HttpRequest.newBuilder().uri(uri);
if (cached != null && cached.etag != null) {
    builder.header("If-None-Match", cached.etag);
}
if (cached != null && cached.lastModified != null) {
    builder.header("If-Modified-Since", cached.lastModified);
}

// 4. Make Request
HttpResponse<String> response = client.send(builder.build(), 
    HttpResponse.BodyHandlers.ofString());

// 5. Handle Response
if (response.statusCode() == 304) {
    // Update cached entry's expiration
    updateCacheExpiration(cached, response.headers());
    return cached.content;
} else {
    // Store new content in cache
    storeCacheEntry(uri, response);
    return response.body();
}
```

**Test**: `testManualCachingRequired()`

## HTTP Caching Standards

HTTP caching is defined by several RFCs:

### Cache-Control Header (RFC 7234)

The `Cache-Control` header controls caching behavior:

- `max-age=N`: Response is fresh for N seconds
- `no-cache`: Must revalidate with server before using cached copy
- `no-store`: Must not cache the response at all
- `public`: Can be cached by any cache (including CDNs)
- `private`: Can only be cached by client-side cache (not shared caches)
- `must-revalidate`: Must revalidate once stale

**Example**:
```
Cache-Control: max-age=3600, public
```

### Conditional Requests (RFC 7232)

Conditional requests allow efficient cache revalidation:

**ETag-based validation**:
- Response header: `ETag: "abc123"` (entity tag)
- Request header: `If-None-Match: "abc123"`
- Server response: `304 Not Modified` if content unchanged

**Time-based validation**:
- Response header: `Last-Modified: Wed, 21 Oct 2015 07:28:00 GMT`
- Request header: `If-Modified-Since: Wed, 21 Oct 2015 07:28:00 GMT`
- Server response: `304 Not Modified` if content unchanged

### Response Status Codes

- `200 OK`: Normal response with content
- `304 Not Modified`: Cached content is still valid (no body sent)
- `410 Gone`: Resource permanently deleted (invalidate cache)

## Proposed Solution

The Java HTTP Client should support **transparent HTTP caching** similar to the historical `java.net.ResponseCache` but with a modern design:

### Default Behavior Options

The implementation should be **opt-in** with configurable behavior modes:

**Option 1: Fully Transparent Mode**
```java
HttpClient client = HttpClient.newBuilder()
    .cache(HttpCache.automatic())  // Enable automatic caching
    .build();

// Application code - no cache-specific code needed
HttpResponse<String> response = client.send(request, 
    HttpResponse.BodyHandlers.ofString());

// Result: Client automatically:
//   - Checks cache and returns cached content if valid
//   - Adds conditional headers (If-None-Match, If-Modified-Since)
//   - Handles 304 responses transparently by returning cached content
//   - Returns fresh content to application as if it were a 200 response
```

**Option 2: Semi-Automatic Mode**
```java
HttpClient client = HttpClient.newBuilder()
    .cache(HttpCache.semiAutomatic())  // Semi-automatic caching
    .build();

HttpResponse<String> response = client.send(request, 
    HttpResponse.BodyHandlers.ofString());

// Result: Client automatically:
//   - Adds conditional headers based on cached ETags/Last-Modified
//   - Sends request to server (does not serve from cache automatically)
//   - Returns actual 304 response to application
//   - Application can check status code and handle accordingly
if (response.statusCode() == 304) {
    // Application handles retrieving cached content
}
```

**Option 3: Disabled (Default)**
```java
HttpClient client = HttpClient.newBuilder()
    // No cache configuration - caching disabled
    .build();

// Current behavior: no automatic caching
```

### Cache Storage Options

Support different cache storage backends:

```java
// In-memory cache (default)
HttpCache cache = HttpCache.newBuilder()
    .maximumSize(100)  // Max 100 cached responses
    .expireAfterWrite(Duration.ofHours(1))
    .build();

// Custom storage implementation
HttpCache cache = HttpCache.newBuilder()
    .storage(new CustomCacheStorage())
    .build();

// Shared cache (multiple HttpClient instances)
HttpCache sharedCache = HttpCache.shared();
```

### Cache Key and Variance

Handle cache key generation and `Vary` header:

```java
// Vary header indicates which request headers affect cached response
// Response: Vary: Accept-Encoding, Accept-Language
// Cache key must include: URI + Accept-Encoding value + Accept-Language value
```

### Cache Control

Respect server cache directives:

- `Cache-Control: no-store` → Do not cache at all
- `Cache-Control: no-cache` → Cache but always revalidate
- `Cache-Control: max-age=0` → Immediately stale, revalidate before use
- `Cache-Control: private` → Only cache in client, not shared caches

### Stale-While-Revalidate

Support serving stale content while revalidating in background:

```java
HttpCache cache = HttpCache.newBuilder()
    .staleWhileRevalidate(Duration.ofSeconds(30))
    .build();

// Returns stale cached content immediately
// Asynchronously revalidates in background
// Future requests get fresh content
```

## Compatibility with Existing java.net.ResponseCache

Consider one of these approaches:

**Option A: Separate Implementation**
- Create new `java.net.http.HttpCache` API independent of `java.net.ResponseCache`
- Modern design with builder pattern and fluent API
- Better aligned with `HttpClient` design philosophy

**Option B: Compatibility Bridge**
- Make `java.net.http.HttpClient` use existing `java.net.ResponseCache` if configured
- Allows migration of existing `ResponseCache` implementations
- May have design limitations due to older API

**Recommendation**: Option A (separate implementation) with optional bridge to `ResponseCache` for migration scenarios.

## Benefits

- **Zero-code caching**: Applications get caching without significant code changes
- **Standards compliant**: Follows RFC 7234, RFC 7232 specifications
- **Flexible**: Multiple modes (transparent, semi-automatic, disabled)
- **Efficient**: Reduces server load and network bandwidth
- **Opt-in**: Backward compatible, existing applications continue to work

## Example Use Cases

### 1. REST API Client
```java
// API client with 5-minute cache for GET requests
HttpCache cache = HttpCache.newBuilder()
    .maximumSize(1000)
    .expireAfterWrite(Duration.ofMinutes(5))
    .build();

HttpClient client = HttpClient.newBuilder()
    .cache(cache)
    .build();

// Subsequent identical requests served from cache
```

### 2. Web Scraper
```java
// Web scraper with ETag-based revalidation
HttpCache cache = HttpCache.newBuilder()
    .storage(new DiskCacheStorage("/tmp/http-cache"))
    .build();

HttpClient client = HttpClient.newBuilder()
    .cache(cache)
    .build();

// Respects server Cache-Control and ETag headers
```

### 3. Mobile Application
```java
// Mobile app with stale-while-revalidate for better UX
HttpCache cache = HttpCache.newBuilder()
    .staleWhileRevalidate(Duration.ofMinutes(5))
    .staleIfError(Duration.ofHours(1))
    .build();

HttpClient client = HttpClient.newBuilder()
    .cache(cache)
    .build();

// Returns cached content immediately while fetching updates
```

## References

- [RFC 7234 - HTTP Caching](https://datatracker.ietf.org/doc/html/rfc7234)
- [RFC 7232 - HTTP Conditional Requests](https://datatracker.ietf.org/doc/html/rfc7232)
- [RFC 5861 - HTTP Cache-Control Extensions for Stale Content](https://datatracker.ietf.org/doc/html/rfc5861)
- [Wikipedia - Web cache](https://en.wikipedia.org/wiki/Web_cache)
- [MDN - HTTP Caching](https://developer.mozilla.org/en-US/docs/Web/HTTP/Caching)
- [Java SE 6+ ResponseCache](https://docs.oracle.com/javase/8/docs/technotes/guides/net/http-cache.html)

## Test Results

The test suite (`JavaHttpClientCachingTest`) validates:

✅ Java HTTP Client does NOT automatically handle Cache-Control headers  
✅ Java HTTP Client does NOT automatically use ETags for conditional requests  
✅ Java HTTP Client does NOT handle 304 responses transparently  
✅ Manual caching implementation is required with all its complexity  

All tests demonstrate the current limitation and the need for transparent caching support.

## Recommendation for JDK Enhancement Request

This document and the accompanying test suite (`JavaHttpClientCachingTest`) should be used as the basis for submitting an enhancement request to the OpenJDK project. The tests provide concrete evidence of the current limitation, and this document proposes a clear, opt-in solution that aligns with HTTP standards and modern HTTP client implementations.

### Suggested JDK Issue Summary

**Title**: Add HTTP caching support to java.net.http.HttpClient

**Component**: core-libs/java.net

**Type**: Enhancement

**Priority**: P3 (Desirable feature)

**Description**: (See GITHUB_ISSUE_SUMMARY.md for detailed proposal)

---

## For Maintainers

The test server (`NettyHttp2Server`) has been enhanced to support HTTP caching headers including `Cache-Control`, `ETag`, and conditional request handling. This allows the test suite to validate caching behavior over both HTTP/1.1 and HTTP/2 protocols.
