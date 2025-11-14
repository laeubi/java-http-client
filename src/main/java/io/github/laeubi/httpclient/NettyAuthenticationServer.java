package io.github.laeubi.httpclient;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLException;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.util.Base64;

/**
 * Netty-based HTTP server for testing authentication schemes.
 * Supports HTTP Basic, Digest, NTLM, SPNEGO, and Kerberos authentication.
 */
public class NettyAuthenticationServer {
    private static final Logger logger = LoggerFactory.getLogger(NettyAuthenticationServer.class);

    private final int port;
    private final boolean ssl;
    private final String authScheme;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private String username = "testuser";
    private String password = "testpass";
    private String realm = "Test Realm";
    private String nonce = "dcd98b7102dd2f0e8b11d0f600bfb0c093";
    private String opaque = "5ccc069c403ebaf9f0171e9517f40e41";

    public NettyAuthenticationServer(int port, boolean ssl, String authScheme) {
        this.port = port;
        this.ssl = ssl;
        this.authScheme = authScheme;
    }

    public void start() throws Exception {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .handler(new LoggingHandler(LogLevel.INFO))
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) throws Exception {
                            ChannelPipeline p = ch.pipeline();

                            if (ssl) {
                                SelfSignedCertificate ssc = new SelfSignedCertificate();
                                SslContext sslContext = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();
                                p.addLast(sslContext.newHandler(ch.alloc()));
                            }

                            p.addLast(new HttpServerCodec());
                            p.addLast(new HttpObjectAggregator(1048576));
                            p.addLast(new AuthenticationHandler(authScheme, username, password, realm, nonce, opaque));
                        }
                    });

            serverChannel = b.bind(port).sync().channel();
            logger.info("Authentication test server started on port {} with {} authentication", port, authScheme);
        } catch (Exception e) {
            logger.error("Failed to start server", e);
            stop();
            throw e;
        }
    }

    public void stop() {
        if (serverChannel != null) {
            serverChannel.close();
            serverChannel = null;
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
            workerGroup = null;
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
            bossGroup = null;
        }
        logger.info("Server stopped on port {}", port);
    }

    public int getPort() {
        return port;
    }

    public void setCredentials(String username, String password) {
        this.username = username;
        this.password = password;
    }

    public void setRealm(String realm) {
        this.realm = realm;
    }

    /**
     * Handler for HTTP authentication.
     */
    private static class AuthenticationHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        private static final Logger logger = LoggerFactory.getLogger(AuthenticationHandler.class);
        private final String authScheme;
        private final String username;
        private final String password;
        private final String realm;
        private final String nonce;
        private final String opaque;

        public AuthenticationHandler(String authScheme, String username, String password, 
                                     String realm, String nonce, String opaque) {
            this.authScheme = authScheme;
            this.username = username;
            this.password = password;
            this.realm = realm;
            this.nonce = nonce;
            this.opaque = opaque;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
            logger.info("Received {} request to {}", request.method(), request.uri());

            String authHeader = request.headers().get(HttpHeaderNames.AUTHORIZATION);
            logger.info("Authorization header: {}", authHeader);

            boolean authenticated = false;
            String authErrorMessage = null;

            if (authHeader != null) {
                authenticated = validateAuthentication(authHeader);
                if (!authenticated) {
                    authErrorMessage = "Invalid credentials";
                }
            }

            FullHttpResponse response;
            if (authenticated) {
                // Authentication successful
                String content = "Authenticated successfully with " + authScheme + " authentication\n";
                response = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1,
                        HttpResponseStatus.OK,
                        Unpooled.copiedBuffer(content, StandardCharsets.UTF_8));
                response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain");
                response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.length());
                logger.info("Authentication successful");
            } else {
                // Authentication required or failed
                String challenge = generateChallenge();
                String content = authErrorMessage != null ? authErrorMessage + "\n" : "Authentication required\n";
                response = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1,
                        HttpResponseStatus.UNAUTHORIZED,
                        Unpooled.copiedBuffer(content, StandardCharsets.UTF_8));
                response.headers().set(HttpHeaderNames.WWW_AUTHENTICATE, challenge);
                response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain");
                response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.length());
                logger.info("Sending 401 Unauthorized with challenge: {}", challenge);
            }

            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }

        private String generateChallenge() {
            switch (authScheme.toUpperCase()) {
                case "BASIC":
                    return "Basic realm=\"" + realm + "\"";
                case "DIGEST":
                    return String.format(
                            "Digest realm=\"%s\", qop=\"auth\", nonce=\"%s\", opaque=\"%s\"",
                            realm, nonce, opaque);
                case "NTLM":
                    // NTLM challenge - simplified for testing
                    return "NTLM";
                case "NEGOTIATE":
                    // SPNEGO/Kerberos uses Negotiate
                    return "Negotiate";
                case "BEARER":
                    return "Bearer realm=\"" + realm + "\"";
                default:
                    return authScheme + " realm=\"" + realm + "\"";
            }
        }

        private boolean validateAuthentication(String authHeader) {
            if (authScheme.equalsIgnoreCase("BASIC")) {
                return validateBasic(authHeader);
            } else if (authScheme.equalsIgnoreCase("DIGEST")) {
                return validateDigest(authHeader);
            } else if (authScheme.equalsIgnoreCase("NTLM")) {
                return validateNTLM(authHeader);
            } else if (authScheme.equalsIgnoreCase("NEGOTIATE")) {
                return validateNegotiate(authHeader);
            } else if (authScheme.equalsIgnoreCase("BEARER")) {
                return validateBearer(authHeader);
            }
            return false;
        }

        private boolean validateBasic(String authHeader) {
            if (!authHeader.startsWith("Basic ")) {
                return false;
            }
            try {
                String credentials = authHeader.substring(6);
                String decoded = new String(Base64.getDecoder().decode(credentials), StandardCharsets.UTF_8);
                String expectedCredentials = username + ":" + password;
                return decoded.equals(expectedCredentials);
            } catch (Exception e) {
                logger.error("Error validating Basic authentication", e);
                return false;
            }
        }

        private boolean validateDigest(String authHeader) {
            if (!authHeader.startsWith("Digest ")) {
                return false;
            }
            // Simplified digest validation - in a real implementation, this would
            // properly validate all digest parameters including response hash
            // For testing purposes, we just check if the header is properly formatted
            return authHeader.contains("username=") && authHeader.contains("response=");
        }

        private boolean validateNTLM(String authHeader) {
            if (!authHeader.startsWith("NTLM ")) {
                return false;
            }
            // NTLM is a multi-step challenge-response protocol
            // For testing purposes, we accept any NTLM token
            String token = authHeader.substring(5).trim();
            return !token.isEmpty();
        }

        private boolean validateNegotiate(String authHeader) {
            if (!authHeader.startsWith("Negotiate ")) {
                return false;
            }
            // SPNEGO/Kerberos uses the Negotiate scheme
            // For testing purposes, we accept any Negotiate token
            String token = authHeader.substring(10).trim();
            return !token.isEmpty();
        }

        private boolean validateBearer(String authHeader) {
            if (!authHeader.startsWith("Bearer ")) {
                return false;
            }
            String token = authHeader.substring(7).trim();
            return !token.isEmpty();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            logger.error("Exception in authentication handler", cause);
            ctx.close();
        }
    }
}
