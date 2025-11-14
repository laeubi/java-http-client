package io.github.laeubi.httpclient;

import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolConfig;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;

/**
 * WebSocket-only server that accepts WebSocket connections but rejects general HTTP requests.
 * 
 * This server demonstrates an important distinction from RFC 6455 Section 1.7:
 * "The WebSocket Protocol is an independent TCP-based protocol. Its only relationship 
 * to HTTP is that its handshake is interpreted by HTTP servers as an Upgrade request."
 * 
 * A WebSocket-only server:
 * - MUST handle the WebSocket opening handshake (which uses HTTP/1.1 syntax)
 * - MAY reject other HTTP requests that are not WebSocket handshakes
 * - Does not need to be a general-purpose HTTP server
 * 
 * This is used to test how Java HttpClient handles WebSocket-only servers and to
 * verify that the client correctly sends the WebSocket opening handshake.
 */
public class NettyPlainWebSocketServer {
	
	static {
		System.setProperty("jdk.httpclient.HttpClient.log", "all");
	}

	private static final Logger logger = LoggerFactory.getLogger(NettyPlainWebSocketServer.class);
	
	private final int port;
	private Channel channel;
	private EventLoopGroup bossGroup;
	private EventLoopGroup workerGroup;
	private AtomicBoolean webSocketHandshakeCompleted = new AtomicBoolean();
	private AtomicBoolean nonWebSocketHttpRequestReceived = new AtomicBoolean();
	
	public NettyPlainWebSocketServer(int port) throws Exception {
		this.port = port;
		logger.info("Starting WebSocket-only server (accepts WS handshake, rejects general HTTP) on port {}", port);
		
		bossGroup = new NioEventLoopGroup(1);
		workerGroup = new NioEventLoopGroup();
		
		ServerBootstrap b = new ServerBootstrap();
		b.group(bossGroup, workerGroup)
			.channel(NioServerSocketChannel.class)
			.handler(new LoggingHandler(LogLevel.INFO))
			.childHandler(new ChannelInitializer<SocketChannel>() {
				@Override
				protected void initChannel(SocketChannel ch) throws Exception {
					logger.info("New connection established, configuring pipeline for WebSocket-only server");
					
					// HTTP codec to handle the WebSocket opening handshake
					ch.pipeline().addLast(new HttpServerCodec());
					ch.pipeline().addLast(new HttpObjectAggregator(65536));
					
					// Add handler to detect and reject non-WebSocket HTTP requests
					ch.pipeline().addLast(new SimpleChannelInboundHandler<FullHttpRequest>() {
						@Override
						protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) throws Exception {
							String upgrade = req.headers().get(HttpHeaderNames.UPGRADE);
							if (upgrade != null && "websocket".equalsIgnoreCase(upgrade)) {
								// This is the WebSocket opening handshake - accept it!
								logger.info("✓ WebSocket opening handshake received: {} {}", req.method(), req.uri());
								logger.info("✓ This is part of the WebSocket protocol (uses HTTP-like syntax)");
								// Let it pass to WebSocket handler
								ctx.fireChannelRead(req.retain());
							} else {
								// Regular HTTP request - this server doesn't support general HTTP
								logger.error("✗ Non-WebSocket HTTP request received: {} {}", req.method(), req.uri());
								logger.error("✗ This server only supports WebSocket protocol, not general HTTP");
								nonWebSocketHttpRequestReceived.set(true);
								ctx.close();
							}
						}
						
						@Override
						public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
							logger.error("Exception in request handler", cause);
							ctx.close();
						}
					});
					
					// WebSocket protocol handler - handles the handshake completion
					WebSocketServerProtocolConfig wsConfig = WebSocketServerProtocolConfig.newBuilder()
						.websocketPath("/ws")
						.checkStartsWith(true)
						.build();
					ch.pipeline().addLast(new WebSocketServerProtocolHandler(wsConfig));
					
					// Track successful handshake completion
					ch.pipeline().addLast(new io.netty.channel.ChannelInboundHandlerAdapter() {
						@Override
						public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
							if (evt instanceof io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler.HandshakeComplete) {
								webSocketHandshakeCompleted.set(true);
								logger.info("✓ WebSocket handshake completed successfully");
							}
							super.userEventTriggered(ctx, evt);
						}
					});
					
					// WebSocket frame handler
					ch.pipeline().addLast(new WebSocketFrameHandler());
				}
			});
		
		ChannelFuture f = b.bind(port).sync();
		channel = f.channel();
		logger.info("WebSocket-only server started successfully on port {}", port);
	}
	
	public void stop() {
		logger.info("Stopping WebSocket-only server on port {}", port);
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
	
	public void reset() {
		webSocketHandshakeCompleted.set(false);
		nonWebSocketHttpRequestReceived.set(false);
	}
	
	/**
	 * Asserts that the WebSocket handshake was completed successfully.
	 */
	public void assertWebSocketHandshakeCompleted() {
		if (!webSocketHandshakeCompleted.get()) {
			throw new AssertionError("WebSocket handshake was not completed!");
		}
		logger.info("✓ Assertion passed: WebSocket handshake completed successfully");
	}
	
	/**
	 * Checks if the WebSocket handshake was completed.
	 */
	public boolean wasWebSocketHandshakeCompleted() {
		return webSocketHandshakeCompleted.get();
	}
	
	/**
	 * Asserts that no non-WebSocket HTTP requests were received.
	 */
	public void assertNoNonWebSocketHttpRequests() {
		if (nonWebSocketHttpRequestReceived.get()) {
			throw new AssertionError("Non-WebSocket HTTP request was received by this WebSocket-only server!");
		}
		logger.info("✓ Assertion passed: No non-WebSocket HTTP requests received");
	}
	
	/**
	 * Checks if a non-WebSocket HTTP request was received.
	 */
	public boolean wasNonWebSocketHttpRequestReceived() {
		return nonWebSocketHttpRequestReceived.get();
	}
	
	/**
	 * Handler for WebSocket frames - echoes text messages back to client
	 */
	private static class WebSocketFrameHandler extends SimpleChannelInboundHandler<WebSocketFrame> {
		
		private static final Logger logger = LoggerFactory.getLogger(WebSocketFrameHandler.class);
		
		@Override
		protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) throws Exception {
			if (frame instanceof TextWebSocketFrame) {
				TextWebSocketFrame textFrame = (TextWebSocketFrame) frame;
				String text = textFrame.text();
				logger.info("Received WebSocket text frame: {}", text);
				
				// Echo the message back
				String response = "WebSocket-only Echo: " + text;
				ctx.writeAndFlush(new TextWebSocketFrame(response));
				logger.info("Sent WebSocket response: {}", response);
			} else {
				logger.warn("Unsupported WebSocket frame type: {}", frame.getClass().getName());
			}
		}
		
		@Override
		public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
			logger.error("Exception in WebSocket handler", cause);
			ctx.close();
		}
	}
}
