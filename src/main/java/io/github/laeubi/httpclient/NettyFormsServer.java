package io.github.laeubi.httpclient;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
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
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.multipart.Attribute;
import io.netty.handler.codec.http.multipart.FileUpload;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;

/**
 * Netty-based HTTP server for testing form and multipart request handling.
 */
public class NettyFormsServer {

	private static final Logger logger = LoggerFactory.getLogger(NettyFormsServer.class);

	private final int port;
	private Channel channel;
	private EventLoopGroup bossGroup;
	private EventLoopGroup workerGroup;

	public NettyFormsServer(int port) throws Exception {
		this.port = port;
		logger.info("Starting Netty Forms server on port {}", port);

		bossGroup = new NioEventLoopGroup(1);
		workerGroup = new NioEventLoopGroup();

		ServerBootstrap b = new ServerBootstrap();
		b.group(bossGroup, workerGroup)
			.channel(NioServerSocketChannel.class)
			.handler(new LoggingHandler(LogLevel.INFO))
			.childHandler(new ChannelInitializer<SocketChannel>() {
				@Override
				protected void initChannel(SocketChannel ch) {
					ch.pipeline().addLast(new HttpServerCodec());
					ch.pipeline().addLast(new HttpObjectAggregator(1048576)); // 1MB max
					ch.pipeline().addLast(new FormsHandler());
				}
			});

		channel = b.bind(port).sync().channel();
		logger.info("Forms server started successfully on port {}", port);
	}

	public void stop() {
		logger.info("Stopping forms server on port {}", port);
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
	 * Handler for form and multipart requests
	 */
	private static class FormsHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
		private static final Logger logger = LoggerFactory.getLogger(FormsHandler.class);

		@Override
		protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
			logger.info("Received {} request to {}", req.method(), req.uri());
			logger.info("Content-Type: {}", req.headers().get(HttpHeaderNames.CONTENT_TYPE));
			logger.info("Content-Length: {}", req.headers().get(HttpHeaderNames.CONTENT_LENGTH));

			try {
				String path = new QueryStringDecoder(req.uri()).path();
				String responseBody;

				if (HttpMethod.POST.equals(req.method())) {
					String contentType = req.headers().get(HttpHeaderNames.CONTENT_TYPE);
					
					if (contentType != null && contentType.startsWith("application/x-www-form-urlencoded")) {
						responseBody = handleFormUrlEncoded(req);
					} else if (contentType != null && contentType.startsWith("multipart/form-data")) {
						responseBody = handleMultipart(req);
					} else {
						responseBody = "Unsupported Content-Type: " + contentType;
					}
				} else {
					responseBody = "Method not supported: " + req.method();
				}

				sendResponse(ctx, HttpResponseStatus.OK, responseBody);
			} catch (Exception e) {
				logger.error("Error processing request", e);
				sendResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, 
						"Error: " + e.getMessage());
			}
		}

		private String handleFormUrlEncoded(FullHttpRequest req) {
			logger.info("Processing application/x-www-form-urlencoded data");
			
			String content = req.content().toString(StandardCharsets.UTF_8);
			logger.info("Raw form data: {}", content);

			Map<String, String> formData = new HashMap<>();
			QueryStringDecoder decoder = new QueryStringDecoder("?" + content);
			decoder.parameters().forEach((key, values) -> {
				if (!values.isEmpty()) {
					formData.put(key, values.get(0));
					logger.info("Form field: {} = {}", key, values.get(0));
				}
			});

			StringBuilder response = new StringBuilder();
			response.append("Form data received:\n");
			formData.forEach((key, value) -> 
				response.append(key).append("=").append(value).append("\n"));

			return response.toString();
		}

		private String handleMultipart(FullHttpRequest req) {
			logger.info("Processing multipart/form-data");
			
			HttpPostRequestDecoder decoder = null;
			try {
				decoder = new HttpPostRequestDecoder(req);
				
				StringBuilder response = new StringBuilder();
				response.append("Multipart data received:\n");

				for (InterfaceHttpData data : decoder.getBodyHttpDatas()) {
					logger.info("Part type: {}, name: {}", data.getHttpDataType(), data.getName());
					
					if (data.getHttpDataType() == InterfaceHttpData.HttpDataType.Attribute) {
						Attribute attribute = (Attribute) data;
						String value = attribute.getValue();
						logger.info("Field: {} = {}", attribute.getName(), value);
						response.append("Field: ").append(attribute.getName())
							.append(" = ").append(value).append("\n");
					} else if (data.getHttpDataType() == InterfaceHttpData.HttpDataType.FileUpload) {
						FileUpload fileUpload = (FileUpload) data;
						logger.info("File: {} ({}), size: {} bytes", 
							fileUpload.getName(), 
							fileUpload.getFilename(),
							fileUpload.length());
						response.append("File: ").append(fileUpload.getName())
							.append(" (").append(fileUpload.getFilename())
							.append("), size: ").append(fileUpload.length()).append(" bytes\n");
						
						// Log first 100 bytes of file content for debugging
						if (fileUpload.length() > 0 && fileUpload.length() < 1000) {
							ByteBuf content = fileUpload.getByteBuf();
							byte[] bytes = new byte[content.readableBytes()];
							content.getBytes(0, bytes);
							String preview = new String(bytes, StandardCharsets.UTF_8);
							logger.info("File content preview: {}", 
								preview.length() > 100 ? preview.substring(0, 100) + "..." : preview);
						}
					}
				}

				return response.toString();
			} catch (Exception e) {
				logger.error("Error decoding multipart data", e);
				return "Error processing multipart data: " + e.getMessage();
			} finally {
				if (decoder != null) {
					decoder.destroy();
				}
			}
		}

		private void sendResponse(ChannelHandlerContext ctx, HttpResponseStatus status, String body) {
			byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
			ByteBuf content = Unpooled.copiedBuffer(bodyBytes);
			
			FullHttpResponse response = new DefaultFullHttpResponse(
				HttpVersion.HTTP_1_1, status, content);
			
			response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
			response.headers().set(HttpHeaderNames.CONTENT_LENGTH, bodyBytes.length);
			
			ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
		}

		@Override
		public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
			logger.error("Exception in forms handler", cause);
			ctx.close();
		}
	}
}
