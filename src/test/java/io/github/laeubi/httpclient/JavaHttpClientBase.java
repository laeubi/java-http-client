package io.github.laeubi.httpclient;

import java.net.URI;
import java.net.http.HttpClient;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.time.Duration;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class JavaHttpClientBase {
	static {
		System.out.println("JAVA Version: " + Runtime.version());
	}

	protected static NettyHttp2Server httpServer;

	protected static NettyHttp2Server httpsServer;

	protected static NettyHttp2Server httpsServerNoAlpn;

	static void startHttpServer() throws Exception {
		httpServer = new NettyHttp2Server(8080, false, false);
	}

	static void startHttpsServer() throws Exception {
		httpsServer = new NettyHttp2Server(8433, true, true);
	}

	static void startHttpsServerNoAlpn() throws Exception {
		httpsServerNoAlpn = new NettyHttp2Server(8434, true, false, false);
	}

	static void stopHttpServer() {
		if (httpServer != null) {
			httpServer.stop();
			httpServer = null;
		}
		if (httpsServer != null) {
			httpsServer.stop();
			httpsServer = null;
		}
		if (httpsServerNoAlpn != null) {
			httpsServerNoAlpn.stop();
			httpsServerNoAlpn = null;
		}
	}

	URI getHttpsUrl() {
		return URI.create("https://localhost:" + httpsServer.getPort() + "/test");
	}

	URI getHttpsUrlNoAlpn() {
		return URI.create("https://localhost:" + httpsServerNoAlpn.getPort() + "/test");
	}

	static URI getHttpUrl() {
		return URI.create("http://localhost:" + httpServer.getPort() + "/test");
	}

	static HttpClient httpClient() {
		return HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).connectTimeout(Duration.ofSeconds(10))
				.build();
	}

	static HttpClient httpsClient() throws NoSuchAlgorithmException, KeyManagementException {
		// Using a trust-all SSL context with HTTP/2
		// The HttpClient will automatically configure ALPN protocols for HTTP/2
		return HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).connectTimeout(Duration.ofSeconds(10))
				.sslContext(createTrustAllSslContext()).build();
	}

	protected static SSLContext createTrustAllSslContext() throws NoSuchAlgorithmException, KeyManagementException {
		// Intentionally insecure for testing with self-signed certificates
		TrustManager[] trustAllCerts = new TrustManager[] { new X509TrustManager() {
			@Override
			public X509Certificate[] getAcceptedIssuers() {
				return new X509Certificate[0];
			}

			@Override
			public void checkClientTrusted(X509Certificate[] certs, String authType) {
				// Intentionally empty - trusts all client certificates for testing
			}

			@Override
			public void checkServerTrusted(X509Certificate[] certs, String authType) {
				// Intentionally empty - trusts all server certificates for testing
			}
		} };

		SSLContext sslContext = SSLContext.getInstance("TLS");
		sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
		return sslContext;
	}
}
