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
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolConfig;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;

/**
 * Simple WebSocket server for testing WebSocket connections with Java HttpClient.
 */
public class NettyWebSocketServer {
	
	static {
		System.setProperty("jdk.httpclient.HttpClient.log", "all");
	}

	private static final Logger logger = LoggerFactory.getLogger(NettyWebSocketServer.class);
	
	private final int port;
	private Channel channel;
	private EventLoopGroup bossGroup;
	private EventLoopGroup workerGroup;
	private AtomicBoolean webSocketUpgradeRequested = new AtomicBoolean();
	
	public NettyWebSocketServer(int port) throws Exception {
		this.port = port;
		logger.info("Starting WebSocket server on port {}", port);
		
		bossGroup = new NioEventLoopGroup(1);
		workerGroup = new NioEventLoopGroup();
		
		ServerBootstrap b = new ServerBootstrap();
		b.group(bossGroup, workerGroup)
			.channel(NioServerSocketChannel.class)
			.handler(new LoggingHandler(LogLevel.INFO))
			.childHandler(new ChannelInitializer<SocketChannel>() {
				@Override
				protected void initChannel(SocketChannel ch) throws Exception {
					// HTTP codec
					ch.pipeline().addLast(new HttpServerCodec());
					ch.pipeline().addLast(new HttpObjectAggregator(65536));
					
					// HTTP request handler (for non-WebSocket requests)
					ch.pipeline().addLast(new SimpleChannelInboundHandler<FullHttpRequest>() {
						@Override
						protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) throws Exception {
							// Check if it's a WebSocket upgrade request
							String upgrade = req.headers().get(HttpHeaderNames.UPGRADE);
							if (upgrade != null && "websocket".equalsIgnoreCase(upgrade)) {
								// Mark that WebSocket upgrade was requested
								logger.info("WebSocket upgrade requested for: {} {}", req.method(), req.uri());
								webSocketUpgradeRequested.set(true);
								// Let it pass to WebSocket handler
								ctx.fireChannelRead(req.retain());
							} else {
								// Regular HTTP request
								logger.info("Received HTTP request: {} {}", req.method(), req.uri());
								FullHttpResponse response = new DefaultFullHttpResponse(
									HttpVersion.HTTP_1_1,
									HttpResponseStatus.OK
								);
								response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain");
								response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
								ctx.writeAndFlush(response);
							}
						}
					});
					
					// WebSocket protocol handler
					WebSocketServerProtocolConfig wsConfig = WebSocketServerProtocolConfig.newBuilder()
						.websocketPath("/websocket")
						.checkStartsWith(true)
						.build();
					ch.pipeline().addLast(new WebSocketServerProtocolHandler(wsConfig));
					
					// WebSocket frame handler
					ch.pipeline().addLast(new WebSocketFrameHandler());
				}
			});
		
		ChannelFuture f = b.bind(port).sync();
		channel = f.channel();
		logger.info("WebSocket server started successfully on port {}", port);
	}
	
	public void stop() {
		logger.info("Stopping WebSocket server on port {}", port);
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
		webSocketUpgradeRequested.set(false);
	}
	
	public void assertWebSocketUpgrade() {
		if (!webSocketUpgradeRequested.compareAndSet(true, false)) {
			throw new AssertionError("WebSocket Upgrade was not performed!");
		}
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
				String response = "Echo: " + text;
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
