package io.github.laeubi.httpclient;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http2.*;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.*;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.AsciiString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLException;
import java.security.cert.CertificateException;

/**
 * Netty-based HTTP/2 server for testing HTTP client behavior with:
 * - HTTP/2 Upgrade from HTTP/1.1
 * - GOAWAY frame handling
 * - Both HTTP and HTTPS support
 */
public class NettyHttp2Server {
    private static final Logger logger = LoggerFactory.getLogger(NettyHttp2Server.class);

    private final int port;
    private final boolean ssl;
    private final boolean sendGoAway;
    private Channel channel;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;

    public NettyHttp2Server(int port, boolean ssl, boolean sendGoAway) {
        this.port = port;
        this.ssl = ssl;
        this.sendGoAway = sendGoAway;
    }

    public void start() throws Exception {
        logger.info("Starting Netty HTTP/2 server on port {} (SSL: {}, GOAWAY: {})", port, ssl, sendGoAway);
        
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        try {
            ServerBootstrap b = new ServerBootstrap();
            b.option(ChannelOption.SO_BACKLOG, 1024);
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .handler(new LoggingHandler(LogLevel.INFO))
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
        } catch (Exception e) {
            logger.error("Failed to start server", e);
            stop();
            throw e;
        }
    }

    private void configureSsl(SocketChannel ch) throws CertificateException, SSLException {
        SelfSignedCertificate ssc = new SelfSignedCertificate();
        SslContext sslCtx = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey())
                .sslProvider(SslProvider.JDK)
                .protocols("TLSv1.2", "TLSv1.3")
                .applicationProtocolConfig(new ApplicationProtocolConfig(
                        ApplicationProtocolConfig.Protocol.ALPN,
                        ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                        ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                        ApplicationProtocolNames.HTTP_2,
                        ApplicationProtocolNames.HTTP_1_1))
                .build();

        ch.pipeline().addLast(sslCtx.newHandler(ch.alloc()));
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
    }

    private void configureHttp(SocketChannel ch) {
        Http2FrameCodec http2FrameCodec = Http2FrameCodecBuilder.forServer()
                .initialSettings(Http2Settings.defaultSettings())
                .frameLogger(new Http2FrameLogger(LogLevel.INFO, NettyHttp2Server.class))
                .build();

        HttpServerCodec sourceCodec = new HttpServerCodec();
        HttpServerUpgradeHandler.UpgradeCodecFactory upgradeCodecFactory = protocol -> {
            if (AsciiString.contentEquals(Http2CodecUtil.HTTP_UPGRADE_PROTOCOL_NAME, protocol)) {
                logger.info("HTTP/2 upgrade requested");
                return new Http2ServerUpgradeCodec(http2FrameCodec, new Http2Handler(sendGoAway));
            } else {
                return null;
            }
        };

        HttpServerUpgradeHandler upgradeHandler = new HttpServerUpgradeHandler(
                sourceCodec, upgradeCodecFactory, 65536);

        CleartextHttp2ServerUpgradeHandler cleartextHttp2ServerUpgradeHandler =
                new CleartextHttp2ServerUpgradeHandler(
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
                FullHttpResponse response = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1,
                        HttpResponseStatus.OK);
                response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
                ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
            }
        });
    }

    private Http2FrameCodec createHttp2FrameCodec() {
        return Http2FrameCodecBuilder.forServer()
                .initialSettings(Http2Settings.defaultSettings())
                .frameLogger(new Http2FrameLogger(LogLevel.INFO, NettyHttp2Server.class))
                .build();
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

    public boolean isSsl() {
        return ssl;
    }

    /**
     * HTTP/2 handler that processes frames and optionally sends GOAWAY
     */
    private static class Http2Handler extends ChannelInboundHandlerAdapter {
        private static final Logger logger = LoggerFactory.getLogger(Http2Handler.class);
        private final boolean sendGoAway;
        private boolean goAwaySent = false;

        public Http2Handler(boolean sendGoAway) {
            this.sendGoAway = sendGoAway;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof Http2HeadersFrame) {
                Http2HeadersFrame headersFrame = (Http2HeadersFrame) msg;
                logger.info("Received HTTP/2 Headers on stream {}: {}", 
                    headersFrame.stream().id(), headersFrame.headers());

                // Send a response
                Http2Headers headers = new DefaultHttp2Headers();
                headers.status("200");
                headers.set("content-type", "text/plain");

                Http2HeadersFrame responseHeaders = new DefaultHttp2HeadersFrame(headers, false);
                responseHeaders.stream(headersFrame.stream());
                ctx.write(responseHeaders);

                // Send data
                Http2DataFrame dataFrame = new DefaultHttp2DataFrame(
                    ctx.alloc().buffer().writeBytes("Hello from HTTP/2 server\n".getBytes()),
                    true);
                dataFrame.stream(headersFrame.stream());
                ctx.write(dataFrame);

                // Send GOAWAY if configured
                if (sendGoAway && !goAwaySent) {
                    logger.info("Sending GOAWAY frame");
                    Http2GoAwayFrame goAwayFrame = new DefaultHttp2GoAwayFrame(
                        Http2Error.NO_ERROR.code(),
                        ctx.alloc().buffer().writeBytes("Server shutting down".getBytes())
                    );
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

            FullHttpResponse response = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1,
                    HttpResponseStatus.OK,
                    ctx.alloc().buffer().writeBytes("Hello from HTTP/1.1 server\n".getBytes()));
            
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain");
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());

            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            logger.error("Exception in HTTP/1.1 handler", cause);
            ctx.close();
        }
    }
}
