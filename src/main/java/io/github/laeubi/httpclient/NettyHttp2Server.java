package io.github.laeubi.httpclient;

import java.io.ByteArrayOutputStream;
import java.security.cert.CertificateException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;

import javax.net.ssl.SSLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpServerUpgradeHandler;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http2.CleartextHttp2ServerUpgradeHandler;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2GoAwayFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2CodecUtil;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2FrameLogger;
import io.netty.handler.codec.http2.Http2GoAwayFrame;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2ServerUpgradeCodec;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.AsciiString;
import io.netty.util.AttributeKey;

/**
 * Netty-based HTTP/2 server for testing HTTP client behavior with: - HTTP/2
 * Upgrade from HTTP/1.1 - GOAWAY frame handling - Both HTTP and HTTPS support
 */
public class NettyHttp2Server {

	static {
		System.setProperty("jdk.httpclient.HttpClient.log", "all");
	}

	private static final Logger logger = LoggerFactory.getLogger(NettyHttp2Server.class);
	
	// Custom headers for connection tracking
	private static final String HEADER_CONNECTION_NONCE = "x-connection-nonce";
	private static final String HEADER_EXPECTED_NONCE = "x-expected-nonce";
	private static final String HEADER_MARK_STALE = "x-mark-stale";
	
	// Attribute keys for connection tracking
	private static final AttributeKey<String> ATTR_CONNECTION_ID = AttributeKey.valueOf("connectionId");
	private static final AttributeKey<String> ATTR_CONNECTION_NONCE = AttributeKey.valueOf("connectionNonce");
	private static final AttributeKey<Boolean> ATTR_CONNECTION_STALE = AttributeKey.valueOf("connectionStale");

	private final int port;
	private final boolean sendGoAway;
	private final boolean enableAlpn;
	private Channel channel;
	private EventLoopGroup bossGroup;
	private EventLoopGroup workerGroup;

	private AtomicBoolean httpUpgradeRequested = new AtomicBoolean();
	private AtomicBoolean goawayWasSend = new AtomicBoolean();
	
	// Connection tracking
	private final ConcurrentHashMap<String, String> nonceToConnectionId = new ConcurrentHashMap<>();
	private final AtomicLong connectionCounter = new AtomicLong(0);

	public NettyHttp2Server(int port, boolean ssl, boolean sendGoAway) throws Exception {
		this(port, ssl, sendGoAway, true);
	}

	public NettyHttp2Server(int port, boolean ssl, boolean sendGoAway, boolean enableAlpn) throws Exception {
		this.port = port;
		this.sendGoAway = sendGoAway;
		this.enableAlpn = enableAlpn;
		logger.info("Starting Netty HTTP/2 server on port {} (SSL: {}, GOAWAY: {}, ALPN: {})", port, ssl, sendGoAway, enableAlpn);

		bossGroup = new NioEventLoopGroup(1);
		workerGroup = new NioEventLoopGroup();

		ServerBootstrap b = new ServerBootstrap();
		b.group(bossGroup, workerGroup).channel(NioServerSocketChannel.class).handler(new LoggingHandler(LogLevel.INFO))
				.childHandler(new ChannelInitializer<SocketChannel>() {
					@Override
					protected void initChannel(SocketChannel ch) throws Exception {
						if (ssl) {
							configureSsl(ch);
						} else {
							configureHttp(ch);
						}
					}
				});

		channel = b.bind(port).sync().channel();
		logger.info("Server started successfully on port {}", port);
	}

	public void reset() {
		httpUpgradeRequested.set(false);
		goawayWasSend.set(false);
		nonceToConnectionId.clear();
		connectionCounter.set(0);
	}

	private void configureSsl(SocketChannel ch) throws CertificateException, SSLException {
		SelfSignedCertificate ssc = new SelfSignedCertificate();
		
		SslContextBuilder sslCtxBuilder = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey())
				.sslProvider(SslProvider.JDK)
				.protocols("TLSv1.2", "TLSv1.3");
		
		if (enableAlpn) {
			// Configure ALPN for HTTP/2 negotiation
			sslCtxBuilder.applicationProtocolConfig(new ApplicationProtocolConfig(
					ApplicationProtocolConfig.Protocol.ALPN,
					ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
					ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
					ApplicationProtocolNames.HTTP_2, ApplicationProtocolNames.HTTP_1_1));
		}
		
		SslContext sslCtx = sslCtxBuilder.build();

		ch.pipeline().addLast(sslCtx.newHandler(ch.alloc()));
		
		if (enableAlpn) {
			ch.pipeline().addLast(new ApplicationProtocolNegotiationHandler(ApplicationProtocolNames.HTTP_1_1) {
				@Override
				protected void configurePipeline(ChannelHandlerContext ctx, String protocol) {
					if (ApplicationProtocolNames.HTTP_2.equals(protocol)) {
						logger.info("ALPN negotiated HTTP/2");
						ctx.pipeline().addLast(createHttp2Handler());
						return;
					}

					if (ApplicationProtocolNames.HTTP_1_1.equals(protocol)) {
						logger.info("ALPN negotiated HTTP/1.1");
						ctx.pipeline().addLast(new HttpServerCodec());
						ctx.pipeline().addLast(new HttpObjectAggregator(65536));
						ctx.pipeline().addLast(new Http1Handler());
						return;
					}

					throw new IllegalStateException("Unknown protocol: " + protocol);
				}
			});
		} else {
			// Without ALPN, only HTTP/1.1 is supported over TLS
			logger.info("ALPN disabled - using HTTP/1.1 only");
			ch.pipeline().addLast(new HttpServerCodec());
			ch.pipeline().addLast(new HttpObjectAggregator(65536));
			ch.pipeline().addLast(new Http1Handler());
		}
	}

	private void configureHttp(SocketChannel ch) {
		Http2FrameCodec http2FrameCodec = Http2FrameCodecBuilder.forServer()
				.initialSettings(Http2Settings.defaultSettings())
				.frameLogger(new Http2FrameLogger(LogLevel.INFO, NettyHttp2Server.class)).build();

		HttpServerCodec sourceCodec = new HttpServerCodec();
		HttpServerUpgradeHandler.UpgradeCodecFactory upgradeCodecFactory = protocol -> {
			if (AsciiString.contentEquals(Http2CodecUtil.HTTP_UPGRADE_PROTOCOL_NAME, protocol)) {
				logger.info("HTTP/2 upgrade requested");
				httpUpgradeRequested.set(true);
				return new Http2ServerUpgradeCodec(http2FrameCodec, new Http2Handler(sendGoAway));
			} else {
				return null;
			}
		};

		HttpServerUpgradeHandler upgradeHandler = new HttpServerUpgradeHandler(sourceCodec, upgradeCodecFactory, 65536);

		CleartextHttp2ServerUpgradeHandler cleartextHttp2ServerUpgradeHandler = new CleartextHttp2ServerUpgradeHandler(
				sourceCodec, upgradeHandler, new ChannelInitializer<Channel>() {
					@Override
					protected void initChannel(Channel ch) {
						ch.pipeline().addLast(http2FrameCodec);
						ch.pipeline().addLast(new Http2Handler(sendGoAway));
					}
				});

		ch.pipeline().addLast(cleartextHttp2ServerUpgradeHandler);
		ch.pipeline().addLast(new SimpleChannelInboundHandler<HttpRequest>() {
			@Override
			protected void channelRead0(ChannelHandlerContext ctx, HttpRequest msg) {
				logger.info("Received HTTP/1.1 request (no upgrade): {} {}", msg.method(), msg.uri());
				// If we reach here, upgrade didn't happen
				FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
				response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
				ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
			}
		});
	}

	private Http2FrameCodec createHttp2FrameCodec() {
		return Http2FrameCodecBuilder.forServer().initialSettings(Http2Settings.defaultSettings())
				.frameLogger(new Http2FrameLogger(LogLevel.INFO, NettyHttp2Server.class)).build();
	}

	private ChannelHandler createHttp2Handler() {
		Http2FrameCodec http2FrameCodec = createHttp2FrameCodec();

		return new ChannelInitializer<Channel>() {
			@Override
			protected void initChannel(Channel ch) {
				ch.pipeline().addLast(http2FrameCodec);
				ch.pipeline().addLast(new Http2Handler(sendGoAway));
			}
		};
	}

	public void stop() {
		logger.info("Stopping server on port {}", port);
		if (channel != null) {
			channel.close();
		}
		if (workerGroup != null) {
			workerGroup.shutdownGracefully();
		}
		if (bossGroup != null) {
			bossGroup.shutdownGracefully();
		}
	}

	public int getPort() {
		return port;
	}

	/**
	 * HTTP/2 handler that processes frames and optionally sends GOAWAY
	 */
	private class Http2Handler extends ChannelInboundHandlerAdapter {
		private final boolean sendGoAway;
		private boolean goAwaySent = false;

		public Http2Handler(boolean sendGoAway) {
			this.sendGoAway = sendGoAway;
		}
		
		@Override
		public void channelActive(ChannelHandlerContext ctx) throws Exception {
			// Assign a unique connection ID when the channel becomes active
			String connectionId = "conn-" + connectionCounter.incrementAndGet();
			ctx.channel().attr(ATTR_CONNECTION_ID).set(connectionId);
			logger.info("New connection established: {} on channel {}", connectionId, ctx.channel().id());
			super.channelActive(ctx);
		}

		@Override
		public void channelRead(ChannelHandlerContext ctx, Object msg) {
			if (msg instanceof Http2HeadersFrame) {
				Http2HeadersFrame headersFrame = (Http2HeadersFrame) msg;
				Http2Headers requestHeaders = headersFrame.headers();
				logger.info("Received HTTP/2 Headers on stream {}: {}", headersFrame.stream().id(),
						requestHeaders);

				// Get or create connection ID (in case channelActive wasn't called)
				String connectionId = ctx.channel().attr(ATTR_CONNECTION_ID).get();
				if (connectionId == null) {
					connectionId = "conn-" + connectionCounter.incrementAndGet();
					ctx.channel().attr(ATTR_CONNECTION_ID).set(connectionId);
					logger.info("Connection ID created: {} on channel {}", connectionId, ctx.channel().id());
				}
				
				String connectionNonce = ctx.channel().attr(ATTR_CONNECTION_NONCE).get();
				
				// Check if client expects a specific nonce (checking for connection reuse)
				CharSequence expectedNonce = requestHeaders.get(HEADER_EXPECTED_NONCE);
				boolean connectionReused = false;
				if (expectedNonce != null && connectionNonce != null) {
					String storedConnectionId = nonceToConnectionId.get(expectedNonce.toString());
					connectionReused = connectionId.equals(storedConnectionId);
					logger.info("Client expects nonce: {}, stored connectionId: {}, current: {}, reused: {}", 
							expectedNonce, storedConnectionId, connectionId, connectionReused);
				}
				
				// Check if client wants to mark connection as stale
				CharSequence markStale = requestHeaders.get(HEADER_MARK_STALE);
				if (markStale != null && "true".equalsIgnoreCase(markStale.toString())) {
					ctx.channel().attr(ATTR_CONNECTION_STALE).set(true);
					logger.info("Connection {} marked as stale", connectionId);
				}
				
				// Check if connection is stale and should send GOAWAY
				Boolean isStale = ctx.channel().attr(ATTR_CONNECTION_STALE).get();
				boolean shouldSendGoaway = false;
				if (isStale != null && isStale && connectionReused && !goAwaySent) {
					shouldSendGoaway = true;
					logger.info("Stale connection reused - will send GOAWAY");
				}
				
				// Determine response status based on connection reuse when expected nonce is present
				Http2Headers headers = new DefaultHttp2Headers();
				if (expectedNonce != null) {
					// Client is checking for connection reuse
					if (connectionReused) {
						headers.status("200"); // Connection was reused
						logger.info("Responding 200 - connection was reused");
					} else {
						headers.status("444"); // Connection was NOT reused (new connection)
						logger.info("Responding 444 - connection was NOT reused (new connection)");
					}
				} else {
					// Normal request - generate and return a new nonce
					connectionNonce = UUID.randomUUID().toString();
					ctx.channel().attr(ATTR_CONNECTION_NONCE).set(connectionNonce);
					nonceToConnectionId.put(connectionNonce, connectionId);
					headers.status("200");
					headers.set(HEADER_CONNECTION_NONCE, connectionNonce);
					logger.info("Generated new nonce: {} for connection: {}", connectionNonce, connectionId);
				}
				
				headers.set("content-type", "text/plain");
				
				// Check if client accepts gzip compression
				CharSequence acceptEncoding = requestHeaders.get("accept-encoding");
				boolean useGzip = acceptEncoding != null && 
					acceptEncoding.toString().toLowerCase().contains("gzip");
				
				// Send data
				String responseBody = "Hello from HTTP/2 server (connection: " + connectionId + ")\n";
				byte[] responseBytes = responseBody.getBytes();
				
				// Compress if client accepts gzip
				if (useGzip) {
					responseBytes = gzipCompress(responseBytes);
					headers.set("content-encoding", "gzip");
					logger.info("Compressing response with gzip");
				}

				Http2HeadersFrame responseHeaders = new DefaultHttp2HeadersFrame(headers, false);
				responseHeaders.stream(headersFrame.stream());
				ctx.write(responseHeaders);
				
				Http2DataFrame dataFrame = new DefaultHttp2DataFrame(
						ctx.alloc().buffer().writeBytes(responseBytes), true);
				dataFrame.stream(headersFrame.stream());
				ctx.write(dataFrame);

				// Send GOAWAY if configured or if stale connection was reused
				if ((sendGoAway || shouldSendGoaway) && !goAwaySent) {
					goawayWasSend.set(true);
					logger.info("Sending GOAWAY frame");
					Http2GoAwayFrame goAwayFrame = new DefaultHttp2GoAwayFrame(Http2Error.NO_ERROR.code(),
							ctx.alloc().buffer().writeBytes("Server shutting down".getBytes()));
					ctx.write(goAwayFrame);
					goAwaySent = true;
				}

				ctx.flush();
			} else {
				logger.debug("Received other frame: {}", msg.getClass().getName());
			}
		}

		@Override
		public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
			logger.error("Exception in HTTP/2 handler", cause);
			ctx.close();
		}
	}

	/**
	 * HTTP/1.1 handler for non-upgraded connections
	 */
	private static class Http1Handler extends SimpleChannelInboundHandler<FullHttpRequest> {
		private static final Logger logger = LoggerFactory.getLogger(Http1Handler.class);

		@Override
		protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
			logger.info("Received HTTP/1.1 request: {} {}", req.method(), req.uri());

			String responseBody = "Hello from HTTP/1.1 server\n";
			byte[] responseBytes = responseBody.getBytes();
			
			// Check if client accepts gzip compression
			String acceptEncoding = req.headers().get(HttpHeaderNames.ACCEPT_ENCODING);
			boolean useGzip = acceptEncoding != null && acceptEncoding.toLowerCase().contains("gzip");
			
			if (useGzip) {
				responseBytes = gzipCompress(responseBytes);
				logger.info("Compressing HTTP/1.1 response with gzip");
			}
			
			FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
					ctx.alloc().buffer().writeBytes(responseBytes));

			response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain");
			response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
			
			if (useGzip) {
				response.headers().set(HttpHeaderNames.CONTENT_ENCODING, "gzip");
			}

			ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
		}

		@Override
		public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
			logger.error("Exception in HTTP/1.1 handler", cause);
			ctx.close();
		}
	}

	public void assertHttpUpgrade() {
		if (!httpUpgradeRequested.compareAndSet(true, false)) {
			throw new AssertionError("HTTP/2 Upgrade was not performed!");
		}
	}

	public void assertGoaway() {
		if (!goawayWasSend.compareAndSet(true, false)) {
			throw new AssertionError("GOAWAY was not send!");
		}
	}
	
	/**
	 * Helper method to gzip compress data
	 */
	private static byte[] gzipCompress(byte[] data) {
		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			try (GZIPOutputStream gzipOut = new GZIPOutputStream(baos)) {
				gzipOut.write(data);
			}
			return baos.toByteArray();
		} catch (Exception e) {
			logger.error("Failed to compress data", e);
			return data;
		}
	}
}
