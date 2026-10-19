package io.github.laeubi.httpclient;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.BodySubscribers;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.concurrent.ImmediateEventExecutor;

class JavaHttpClientTimeoutTest {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(2);
    private static final long OBSERVATION_SECONDS = 3;
    private static final long GUARD_SECONDS = 10;

    @Test
    void testAsyncStringBodyStalls() throws Exception {
        for (boolean partial : new boolean[] { false, true }) {
            checkAggregatingBody(HttpResponse.BodyHandlers.ofString(), partial, false);
        }
    }

    @Test
    void testAsyncByteArrayBodyStalls() throws Exception {
        for (boolean partial : new boolean[] { false, true }) {
            checkAggregatingBody(HttpResponse.BodyHandlers.ofByteArray(), partial, false);
        }
    }

    @Test
    void testSynchronousBodyStalls() throws Exception {
        for (boolean partial : new boolean[] { false, true }) {
            checkAggregatingBody(HttpResponse.BodyHandlers.ofString(), partial, true);
        }
    }

    private <T> void checkAggregatingBody(BodyHandler<T> handler, boolean partial, boolean synchronous)
            throws Exception {
        try (StallingServer server = new StallingServer(partial)) {
            TrackingHandler<T> tracking = new TrackingHandler<>(handler);
            HttpRequest request = server.request("/stall");
            Future<HttpResponse<T>> response = synchronous
                    ? server.executor.submit(() -> server.client.send(request, tracking))
                    : server.client.sendAsync(request, tracking);
            try {
                server.awaitStall(tracking);
                boolean timedOut = observeBodyDeadline(response);
                System.out.printf("JAVA %s: %s %s partial=%s: %s%n", Runtime.version(),
                        synchronous ? "send" : "sendAsync", partial ? "mid-body" : "after headers",
                        partial, timedOut ? "HttpTimeoutException" : "pending beyond request deadline");
            } finally {
                response.cancel(true);
                tracking.cancel();
            }
        }
    }

    @Test
    void testRequestTimeoutBeforeHeaders() throws Exception {
        try (StallingServer server = new StallingServer(false)) {
            CompletableFuture<HttpResponse<String>> response = server.client.sendAsync(
                    server.request("/no-headers"), HttpResponse.BodyHandlers.ofString());
            try {
                server.requestReceived.get(GUARD_SECONDS, TimeUnit.SECONDS);
                ExecutionException failure = assertThrows(ExecutionException.class,
                        () -> response.get(GUARD_SECONDS, TimeUnit.SECONDS));
                assertInstanceOf(HttpTimeoutException.class, failure.getCause());
            } finally {
                response.cancel(true);
            }
        }
    }

    @Test
    void testCompletedResponse() throws Exception {
        try (StallingServer server = new StallingServer(false)) {
            CompletableFuture<HttpResponse<String>> response = server.client.sendAsync(
                    server.request("/complete"), HttpResponse.BodyHandlers.ofString());
            try {
                HttpResponse<String> result = response.get(GUARD_SECONDS, TimeUnit.SECONDS);
                assertEquals(200, result.statusCode());
                assertEquals("ok", result.body());
            } finally {
                response.cancel(true);
            }
        }
    }

    @Test
    void testInputStreamResponseCompletesBeforeRead() throws Exception {
        try (StallingServer server = new StallingServer(true)) {
            AtomicReference<InputStream> opened = new AtomicReference<>();
            AtomicBoolean closing = new AtomicBoolean();
            TrackingHandler<InputStream> tracking = new TrackingHandler<>(info -> BodySubscribers.mapping(
                    BodySubscribers.ofInputStream(), body -> {
                        opened.set(body);
                        if (closing.get()) {
                            try {
                                body.close();
                            } catch (IOException failure) {
                                throw new UncheckedIOException(failure);
                            }
                        }
                        return body;
                    }));
            CompletableFuture<HttpResponse<InputStream>> response = server.client.sendAsync(
                    server.request("/stall"), tracking);
            Future<Integer> read = null;
            try {
                server.awaitStall(tracking);
                InputStream body = response.get(GUARD_SECONDS, TimeUnit.SECONDS).body();
                assertEquals('x', body.read());
                CompletableFuture<Void> reading = new CompletableFuture<>();
                read = server.executor.submit(() -> {
                    reading.complete(null);
                    return body.read();
                });
                reading.get(GUARD_SECONDS, TimeUnit.SECONDS);
                observeBodyDeadline(read);
                assertTrue(response.isDone(), "The response future does not represent subsequent reads");
            } finally {
                closing.set(true);
                try {
                    tracking.cancel();
                    InputStream body = opened.get();
                    if (body != null) {
                        body.close();
                    }
                } finally {
                    response.cancel(true);
                    if (read != null) {
                        read.cancel(true);
                    }
                }
            }
        }
    }

    private boolean observeBodyDeadline(Future<?> operation) throws Exception {
        try {
            Object result = operation.get(OBSERVATION_SECONDS, TimeUnit.SECONDS);
            fail("Incomplete response unexpectedly completed: " + result);
        } catch (TimeoutException observationDeadline) {
            assertFalse(operation.isDone(), "Only the test's observation deadline should have elapsed");
            assertTrue(Runtime.version().feature() < 26,
                    "JDK 26+ must apply the request timeout to body consumption");
            return false;
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            // InputStream reads can wrap the timeout in IOException.
            while (cause instanceof IOException && !(cause instanceof HttpTimeoutException)
                    && cause.getCause() != null) {
                cause = cause.getCause();
            }
            assertInstanceOf(HttpTimeoutException.class, cause);
            return true; // Accept fixes/backports on older runtimes, too.
        }
        throw new AssertionError("Unreachable");
    }

    private static class TrackingHandler<T> implements BodyHandler<T> {
        private final BodyHandler<T> delegate;
        private final CompletableFuture<Void> subscribed = new CompletableFuture<>();
        private final CompletableFuture<Void> dataReceived = new CompletableFuture<>();
        private final AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
        private final AtomicBoolean cancelled = new AtomicBoolean();

        TrackingHandler(BodyHandler<T> delegate) {
            this.delegate = delegate;
        }

        @Override
        public BodySubscriber<T> apply(HttpResponse.ResponseInfo info) {
            assertEquals(200, info.statusCode());
            BodySubscriber<T> downstream = delegate.apply(info);
            return new BodySubscriber<T>() {
                @Override
                public CompletionStage<T> getBody() {
                    return downstream.getBody();
                }

                @Override
                public void onSubscribe(Flow.Subscription value) {
                    subscription.set(value);
                    downstream.onSubscribe(value);
                    if (cancelled.get()) {
                        value.cancel();
                    }
                    subscribed.complete(null);
                }

                @Override
                public void onNext(List<ByteBuffer> items) {
                    downstream.onNext(items);
                    dataReceived.complete(null);
                }

                @Override
                public void onError(Throwable throwable) {
                    downstream.onError(throwable);
                }

                @Override
                public void onComplete() {
                    downstream.onComplete();
                }
            };
        }

        void cancel() {
            cancelled.set(true);
            Flow.Subscription value = subscription.get();
            if (value != null) {
                value.cancel();
            }
        }
    }

    private static class StallingServer implements AutoCloseable {
        private final NioEventLoopGroup eventLoop = new NioEventLoopGroup(1);
        private final ChannelGroup channels = new DefaultChannelGroup(ImmediateEventExecutor.INSTANCE, true);
        private final ExecutorService executor = Executors.newCachedThreadPool();
        private final CompletableFuture<Void> requestReceived = new CompletableFuture<>();
        private final CompletableFuture<Void> flushed = new CompletableFuture<>();
        private final boolean partial;
        private final HttpClient client;
        private final URI baseUri;

        StallingServer(boolean partial) throws Exception {
            this.partial = partial;
            try {
                Channel listener = new ServerBootstrap().group(eventLoop).channel(NioServerSocketChannel.class)
                        .childHandler(new ChannelInitializer<SocketChannel>() {
                            @Override
                            protected void initChannel(SocketChannel channel) {
                                channels.add(channel);
                                channel.pipeline().addLast(new HttpServerCodec(), new HttpObjectAggregator(1024),
                                        new SimpleChannelInboundHandler<FullHttpRequest>() {
                                            @Override
                                            protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
                                                respond(ctx, request.uri());
                                            }
                                        });
                            }
                        }).bind(new InetSocketAddress("127.0.0.1", 0)).sync().channel();
                channels.add(listener);
                baseUri = URI.create("http://127.0.0.1:" + ((InetSocketAddress) listener.localAddress()).getPort());
                client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(REQUEST_TIMEOUT).executor(executor).build();
            } catch (Exception | Error failure) {
                close();
                throw failure;
            }
        }

        HttpRequest request(String path) {
            return HttpRequest.newBuilder(baseUri.resolve(path)).timeout(REQUEST_TIMEOUT).build();
        }

        private void respond(ChannelHandlerContext ctx, String path) {
            requestReceived.complete(null);
            if ("/no-headers".equals(path)) {
                return;
            }
            if ("/complete".equals(path)) {
                DefaultFullHttpResponse response = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(new byte[] { 'o', 'k' }));
                response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, 2);
                ctx.writeAndFlush(response);
                return;
            }
            DefaultHttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
            response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, 2);
            ctx.write(response);
            // Never satisfy Content-Length, send a last content marker, or close the connection.
            ctx.writeAndFlush(new DefaultHttpContent(partial
                    ? Unpooled.wrappedBuffer(new byte[] { 'x' }) : Unpooled.EMPTY_BUFFER)).addListener(result -> {
                        if (result.isSuccess()) {
                            flushed.complete(null);
                        } else {
                            flushed.completeExceptionally(result.cause());
                        }
                    });
        }

        void awaitStall(TrackingHandler<?> tracking) throws Exception {
            flushed.get(GUARD_SECONDS, TimeUnit.SECONDS);
            tracking.subscribed.get(GUARD_SECONDS, TimeUnit.SECONDS);
            if (partial) {
                tracking.dataReceived.get(GUARD_SECONDS, TimeUnit.SECONDS);
            }
        }

        void closeConnections() {
            assertTrue(channels.close().awaitUninterruptibly(GUARD_SECONDS, TimeUnit.SECONDS),
                    "Server channels did not close");
        }

        @Override
        public void close() {
            try {
                closeConnections();
            } finally {
                eventLoop.shutdownGracefully(0, 5, TimeUnit.SECONDS);
                executor.shutdownNow();
                try {
                    assertTrue(executor.awaitTermination(GUARD_SECONDS, TimeUnit.SECONDS),
                            "Client/worker executor did not terminate");
                    assertTrue(eventLoop.terminationFuture().await(GUARD_SECONDS, TimeUnit.SECONDS),
                            "Server event loop did not terminate");
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
            }
        }
    }
}
