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
 * WebSocket server that ONLY supports plain WebSocket protocol without HTTP upgrade.
 * This server is configured to refuse HTTP upgrade requests and only accept 
 * direct WebSocket connections.
 * 
 * This is used to test how Java HttpClient handles servers that don't support
 * the HTTP upgrade mechanism for WebSocket.
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
	private AtomicBoolean httpUpgradeAttempted = new AtomicBoolean();
	
	public NettyPlainWebSocketServer(int port) throws Exception {
		this.port = port;
		logger.info("Starting PLAIN WebSocket server (no HTTP upgrade support) on port {}", port);
		
		bossGroup = new NioEventLoopGroup(1);
		workerGroup = new NioEventLoopGroup();
		
		ServerBootstrap b = new ServerBootstrap();
		b.group(bossGroup, workerGroup)
			.channel(NioServerSocketChannel.class)
			.handler(new LoggingHandler(LogLevel.INFO))
			.childHandler(new ChannelInitializer<SocketChannel>() {
				@Override
				protected void initChannel(SocketChannel ch) throws Exception {
					logger.info("New connection established, configuring pipeline for plain WebSocket");
					
					// We still need HTTP codec to detect if client tries to do HTTP upgrade
					// But we'll reject it
					ch.pipeline().addLast(new HttpServerCodec());
					ch.pipeline().addLast(new HttpObjectAggregator(65536));
					
					// Add handler to detect and reject HTTP upgrade attempts
					ch.pipeline().addLast(new SimpleChannelInboundHandler<FullHttpRequest>() {
						@Override
						protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) throws Exception {
							String upgrade = req.headers().get(HttpHeaderNames.UPGRADE);
							if (upgrade != null && "websocket".equalsIgnoreCase(upgrade)) {
								// Client is trying to do HTTP upgrade - this server doesn't support it
								logger.error("HTTP WebSocket upgrade attempted but this server ONLY supports plain WebSocket protocol!");
								logger.error("Request: {} {}", req.method(), req.uri());
								logger.error("Upgrade header: {}", upgrade);
								httpUpgradeAttempted.set(true);
								
								// Close the connection - we don't support HTTP upgrade
								logger.error("Closing connection - HTTP upgrade not supported by this server");
								ctx.close();
							} else {
								// Regular HTTP request - also not supported
								logger.error("Regular HTTP request received but this server ONLY supports plain WebSocket protocol!");
								logger.error("Request: {} {}", req.method(), req.uri());
								ctx.close();
							}
						}
						
						@Override
						public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
							logger.error("Exception in HTTP upgrade detection handler", cause);
							ctx.close();
						}
					});
					
					// WebSocket protocol handler would go here, but it won't be reached
					// because we reject the HTTP upgrade attempt
					WebSocketServerProtocolConfig wsConfig = WebSocketServerProtocolConfig.newBuilder()
						.websocketPath("/ws")
						.checkStartsWith(true)
						.build();
					ch.pipeline().addLast(new WebSocketServerProtocolHandler(wsConfig));
					
					// WebSocket frame handler
					ch.pipeline().addLast(new WebSocketFrameHandler());
				}
			});
		
		ChannelFuture f = b.bind(port).sync();
		channel = f.channel();
		logger.info("PLAIN WebSocket server (no HTTP upgrade) started successfully on port {}", port);
	}
	
	public void stop() {
		logger.info("Stopping PLAIN WebSocket server on port {}", port);
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
		httpUpgradeAttempted.set(false);
	}
	
	/**
	 * Asserts that NO HTTP upgrade was attempted.
	 * This is the expected behavior for a plain WebSocket server.
	 */
	public void assertNoHttpUpgrade() {
		if (httpUpgradeAttempted.get()) {
			throw new AssertionError("HTTP WebSocket upgrade was attempted, but this server doesn't support it!");
		}
		logger.info("✓ Assertion passed: No HTTP upgrade was attempted (as expected for plain WebSocket)");
	}
	
	/**
	 * Checks if an HTTP upgrade was attempted (for verification in tests)
	 */
	public boolean wasHttpUpgradeAttempted() {
		return httpUpgradeAttempted.get();
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
				String response = "Plain Echo: " + text;
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
