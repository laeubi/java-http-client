# HTTP Response-Body Stall Timeouts

## Problem

A server can send `200 OK` headers, optionally send part of the body, and then
leave the connection open without completing the response. On the older JDK
builds tested below, `HttpRequest.Builder.timeout(...)` does not bound completion
of that body. This is different from a server that never sends response headers:
the request timeout **does** protect that case.

**This is version-dependent, not an unqualified current Java limitation.**
JDK 26 extended the built-in client's request timeout through response-body
subscriber completion ([JDK-8208693][fix], [release notes][release],
[implementation change][implementation]). It is an overall deadline, not an
independent body read-idle timer that resets whenever data arrives.

## Timeout semantics

| Setting/mechanism | What it bounds | What it does not provide |
| --- | --- | --- |
| `HttpClient.Builder.connectTimeout(...)` | Establishing a new connection | Reading a response on an established or reused connection |
| Request `timeout(...)`, pre-fix behavior reproduced below | Request execution through response headers | Completion of a stalled response body |
| Request `timeout(...)`, JDK 26 built-in implementation | Overall request execution through body-subscriber completion | A fresh timeout interval after each body byte/chunk |
| `Future.get(timeout, unit)` | This caller's wait for that future | Automatic exchange cancellation, or later reads from an already returned stream |
| Independent response-body read-idle timeout | Would bound lack of progress, resetting on progress | No dedicated built-in configuration is demonstrated here |

The [JDK 26 API documentation][api] distinguishes the API's minimum timeout
scope (at least until an `HttpResponse` is constructed) from the built-in
implementation's longer scope (until body-subscriber completion). These can be
different times for streaming consumers.

## Minimal reproduction

Use a local server that flushes this response and keeps the connection open:

```http
HTTP/1.1 200 OK
Content-Length: 2

x
```

Only one of the promised two body bytes arrives. Omitting `x` reproduces a
stall before the first body byte, **after** headers. Neither case is a
connection-establishment timeout.

```java
HttpClient client = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(2))
        .build();
HttpRequest request = HttpRequest.newBuilder(localStallingServerUri)
        .timeout(Duration.ofSeconds(2))
        .build();
CompletableFuture<HttpResponse<String>> response =
        client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
try {
    response.get(3, TimeUnit.SECONDS);
} catch (TimeoutException observationDeadline) {
    // Only the caller's observation expired; the exchange may still be active.
} catch (ExecutionException failure) {
    // On a fixed JDK, inspect failure.getCause() for HttpTimeoutException.
} finally {
    response.cancel(true);
    // Also close the local fixture's accepted connections.
}
```

This is an illustrative client fragment, not a standalone server program.
The executable, synchronized reproduction is
[`JavaHttpClientTimeoutTest`](src/test/java/io/github/laeubi/httpclient/JavaHttpClientTimeoutTest.java).
It uses the existing Netty dependency, IPv4 loopback, and ephemeral ports;
no external service is involved.

## Executable tests

```bash
java -version
mvn -B -Dtest=JavaHttpClientTimeoutTest clean package
# Full suite:
mvn -B clean package
```

| Test method | Scenario |
| --- | --- |
| `testAsyncStringBodyStalls` | `sendAsync` / `ofString`, both after-headers and partial-body stalls |
| `testAsyncByteArrayBodyStalls` | `sendAsync` / `ofByteArray`, both stalls |
| `testSynchronousBodyStalls` | Worker running `send` / `ofString`, both stalls |
| `testInputStreamResponseCompletesBeforeRead` | `ofInputStream` response is available; consume the first byte, then observe a blocked read |
| `testRequestTimeoutBeforeHeaders` | Request received but no headers sent: requires `HttpTimeoutException` |
| `testCompletedResponse` | Complete successful body returns normally |

The fixture flushes headers and zero or one body byte while advertising
`Content-Length: 2`. Tests wait for successful server flush and client
subscriber activation; partial-body cases also wait for subscriber data
delivery. Startup/header failure therefore cannot count as evidence of a body
stall. No sleeps are used.

The request deadline is two seconds. The test observes for another bounded
three seconds **after** these progress signals. On pre-fix runtimes it observes
a test-side `TimeoutException` and an operation still pending, not a client-side
`HttpTimeoutException`. A finite observation proves only **still pending beyond
the configured deadline**, not infinite waiting.

JDK 26+ must produce a timeout, including an `IOException` with a
`HttpTimeoutException` cause for the streamed read. Older runtimes may exhibit
either the reproduced pending behavior or a timeout if fixed/backported;
aggregating cases print the actual result and exact runtime. The tests do not
assert that all future updates of older release lines retain the limitation.
All paths cancel exchanges/subscriptions, close server channels and streamed
bodies, cancel workers, and shut down/await executors and event loops. Cleanup
does not require the `HttpClient.close()` API unavailable in Java 11.

## Verified scope

Local results on Linux x86-64, Eclipse Temurin, Maven 3.10.0:

| Exact runtime build | Stalled aggregating bodies | Streamed second read | Focused tests |
| --- | --- | --- | --- |
| `11.0.32.1+1` | Still pending beyond deadline | Still pending beyond deadline | 6 passed |
| `17.0.17+10` (CI version) | Still pending beyond deadline | Still pending beyond deadline | 6 passed |
| `17.0.20.1+1` | Still pending beyond deadline | Still pending beyond deadline | 6 passed |
| `21.0.8+9-LTS` (CI version) | Still pending beyond deadline | Still pending beyond deadline | 6 passed |
| `25.0.4.1+1-LTS` | Still pending beyond deadline | Still pending beyond deadline | 6 passed |
| `26.0.2.1+1` | `HttpTimeoutException` | `IOException` caused by `HttpTimeoutException` | 6 passed |

The repository CI pins Temurin `17.0.17` and `21.0.8`; this table records actual
local builds, not assumed results from the CI configuration.

Coverage is **cleartext HTTP/1.1**, using the JDK built-in client. HTTPS,
HTTP/2, HTTP/3, other vendors, custom subscribers with unusual completion
semantics, and every Java 11–25 update build are **not** established by this
reproduction. Consult vendor update notes and rerun the tests for a specific
deployment rather than extrapolating from the major version alone.

## Impact and workarounds

* Aggregating handlers such as `ofString()` and `ofByteArray()` require the
  complete body. With the reproduced behavior, synchronous `send` keeps its
  worker waiting and `sendAsync` leaves its future incomplete. This can retain
  connections, application tasks, and partially accumulated data.
* `ofInputStream()` can deliver the response before the body finishes.
  Subsequent reads are separate operations: timing out a response future that
  has already completed cannot bound those reads. Always close the body.
  On the tested JDK 26 build, the request's remaining overall deadline also
  terminates the stalled read; receipt of a byte does not reset that deadline.
* Upgrade to a build implementing JDK-8208693 where an overall body deadline
  matches the application's requirements. A long, healthy download can exceed
  an overall deadline even while continuously making progress.
* Applications can enforce external deadlines and explicitly cancel exchanges.
  `Future.get(timeout)` alone only stops waiting. Keep the original
  `sendAsync` future and request cancellation in cleanup; interruption and
  cancellation behavior must be checked on the deployed JDK. Closing a streamed
  body and releasing application resources is still important.
* `CompletableFuture.orTimeout(...)` alone does **not** guarantee cancellation
  of the underlying transport. Preserve an exchange cancellation handle rather
  than assuming exceptional future completion means network work stopped.
* A custom timed `BodySubscriber` can implement an idle watchdog: reset it on
  meaningful data delivery, cancel the subscription on expiry, complete the
  body exceptionally, and release the scheduler and buffers on every terminal
  path. Backpressure/application inactivity needs a deliberate policy; it is
  not necessarily server inactivity.
* A different HTTP client with explicit response/read-idle timeout support may
  suit applications that need that policy without a custom subscriber.

TCP keepalive detects some dead peers, not application-level response progress.
Connection-pool keepalive settings govern idle pooled connections, not an
incomplete in-flight body. Neither substitutes for a per-response stall policy.
There **are** ways to apply deadlines; the limitation is the older built-in
request timeout's scope and the lack of a separate built-in progress-resetting
body timeout, not an inability to time out anything.

## Upstream records and desired enhancement

* [JDK-8208693: extend request timeout scope to the response body][fix].
  The [OpenJDK implementation change][implementation] adds the body-completion
  scope and regression tests; the [upstream review][review] explains the change
  and links its CSR. [JDK 26 release notes][release] document its release.
* [JDK-8258397][related] is a related timeout report worth checking for affected
  builds and linked issues.
* [JDK 26 `HttpRequest.Builder.timeout`][api],
  [`connectTimeout`][connect], and the [official JDK 26 overview][overview].

The OpenJDK bug tracker and Oracle documentation hosts were unreachable from
the validation environment. The implementation commit was inspected directly;
the release-note/API links above provide the published context. Bug metadata
and a complete vendor/backport inventory could not be independently retrieved.
No backport to an older release is claimed here, nor is its absence guaranteed.

A useful **proposed** enhancement is an independently configurable response-body
read-idle timeout, with documented progress, backpressure, streaming,
cancellation, and resource-release semantics. Any API spelling such as
`bodyIdleTimeout(Duration)` is **hypothetical**, not an existing JDK method.
This proposal is distinct from the overall body deadline already added in
JDK 26.

[fix]: https://bugs.openjdk.org/browse/JDK-8208693
[related]: https://bugs.openjdk.org/browse/JDK-8258397
[implementation]: https://github.com/openjdk/jdk/commit/16699a394d4d6c2b8a21e7de3c3d344c5a3309b4
[review]: https://github.com/openjdk/jdk/pull/27469
[release]: https://www.oracle.com/java/technologies/javase/26-relnote-issues.html
[api]: https://docs.oracle.com/en/java/javase/26/docs/api/java.net.http/java/net/http/HttpRequest.Builder.html#timeout(java.time.Duration)
[connect]: https://docs.oracle.com/en/java/javase/26/docs/api/java.net.http/java/net/http/HttpClient.Builder.html#connectTimeout(java.time.Duration)
[overview]: https://inside.java/2026/03/04/jdk-26-http-client/
