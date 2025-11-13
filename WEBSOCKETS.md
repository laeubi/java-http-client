# WebSocket Protocol

## Overview

WebSocket is a communication protocol that provides full-duplex, bidirectional communication channels over a single TCP connection. Defined in [RFC 6455](https://datatracker.ietf.org/doc/html/rfc6455), WebSocket enables real-time, low-latency communication between clients and servers, making it ideal for applications like chat, live updates, gaming, and streaming data.

## Basic Concepts

### What is WebSocket?

WebSocket is distinct from HTTP, which is the request-response protocol used to serve most webpages. While both protocols operate over TCP/IP, WebSocket provides a persistent connection that allows both the client and server to send data at any time without the overhead of HTTP headers on every message.

Key characteristics of WebSocket:

- **Full-Duplex Communication**: Both client and server can send messages independently and simultaneously
- **Persistent Connection**: The connection remains open, eliminating the need to establish a new connection for each message
- **Low Overhead**: After the initial handshake, data frames have minimal protocol overhead compared to HTTP
- **Real-Time**: Enables instant bidirectional communication without polling or long-polling workarounds
- **Message-Oriented**: Data is transmitted as discrete messages rather than a byte stream

### WebSocket vs HTTP

| Feature | HTTP | WebSocket |
|---------|------|-----------|
| Communication Pattern | Request-Response | Full-Duplex |
| Connection | Short-lived (typically) | Persistent |
| Overhead | High (headers on every request/response) | Low (small frame headers) |
| Server Push | Requires workarounds (SSE, long-polling) | Native support |
| Use Case | Document retrieval, APIs | Real-time communication |
| Protocol Ports | 80 (HTTP), 443 (HTTPS) | 80 (WS), 443 (WSS) |

## Differences Between TCP and WebSocket Connections

### Direct TCP Connection

When establishing a direct TCP connection:

1. **Three-Way Handshake**: Client and server perform SYN, SYN-ACK, ACK to establish the TCP connection
2. **Application Protocol**: The application must define its own protocol for message framing, encoding, and control
3. **No Built-in Features**: No standardized support for message framing, fragmentation, or control frames
4. **Port Access**: May be blocked by firewalls and proxies that only allow HTTP/HTTPS traffic
5. **Raw Connection**: Direct access to the TCP stream with full control but also full responsibility

Example TCP connection flow:
```
Client                    Server
  |                         |
  |-------- SYN ----------->|
  |<------ SYN-ACK ---------|
  |-------- ACK ----------->|
  |                         |
  |-- Application Data ---->|
  |<-- Application Data ----|
  |         ...             |
  |-------- FIN ----------->|
  |<------ FIN-ACK ---------|
```

### WebSocket Connection

When establishing a WebSocket connection:

1. **HTTP Handshake**: Connection starts with an HTTP/1.1 request using the `Upgrade` header
2. **Protocol Upgrade**: Server responds with 101 Switching Protocols if it accepts the upgrade
3. **WebSocket Protocol**: After upgrade, the connection uses the WebSocket protocol with standardized framing
4. **Control Frames**: Built-in support for ping/pong (keep-alive), close handshake, and connection management
5. **Message Framing**: Automatic handling of message fragmentation and reassembly
6. **Proxy/Firewall Compatible**: Works over standard HTTP ports (80/443) and can traverse HTTP proxies

Example WebSocket connection flow:
```
Client                    Server
  |                         |
  |-- HTTP Upgrade Req. -->|
  |                         |
  |<- 101 Switching Prot. -|
  |                         |
  |-- WebSocket Frame ----->|
  |<-- WebSocket Frame -----|
  |         ...             |
  |-- Close Frame --------->|
  |<-- Close Frame ---------|
  |-- TCP FIN ------------->|
```

### Key Advantages of WebSocket over Raw TCP

1. **Standardized Protocol**: WebSocket provides a well-defined protocol for framing, control messages, and error handling
2. **HTTP Compatibility**: Can traverse HTTP infrastructure (proxies, load balancers, firewalls)
3. **Security**: WSS (WebSocket Secure) provides TLS encryption, similar to HTTPS
4. **Origin-Based Security**: Built-in origin checking to prevent unauthorized cross-origin connections
5. **Masking**: Client-to-server messages are masked to prevent cache poisoning attacks on proxies
6. **Automatic Fragmentation**: Large messages are automatically fragmented and reassembled

## The HTTP Upgrade Process

WebSocket achieves compatibility with HTTP infrastructure by using the HTTP `Upgrade` mechanism defined in [RFC 7230 Section 6.7](https://datatracker.ietf.org/doc/html/rfc7230#section-6.7). This allows a connection to transition from HTTP to WebSocket protocol.

### WebSocket Handshake Request

The client initiates a WebSocket connection by sending an HTTP/1.1 request with specific headers:

```http
GET /chat HTTP/1.1
Host: server.example.com
Upgrade: websocket
Connection: Upgrade
Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==
Sec-WebSocket-Version: 13
Origin: http://example.com
```

Key headers:

- **`Upgrade: websocket`**: Requests protocol upgrade to WebSocket
- **`Connection: Upgrade`**: Indicates this is an upgrade request
- **`Sec-WebSocket-Key`**: A randomly generated base64-encoded value (16 bytes) used for handshake verification
- **`Sec-WebSocket-Version`**: WebSocket protocol version (13 is the current standard)
- **`Origin`**: The origin of the script establishing the connection (for security)

Optional headers:

- **`Sec-WebSocket-Protocol`**: Requested subprotocols (e.g., "chat", "mqtt")
- **`Sec-WebSocket-Extensions`**: Requested extensions (e.g., "permessage-deflate" for compression)

### WebSocket Handshake Response

If the server accepts the WebSocket connection, it responds with:

```http
HTTP/1.1 101 Switching Protocols
Upgrade: websocket
Connection: Upgrade
Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=
```

Key headers:

- **`101 Switching Protocols`**: Status code indicating successful protocol upgrade
- **`Upgrade: websocket`**: Confirms the upgrade to WebSocket protocol
- **`Connection: Upgrade`**: Confirms this is an upgrade response
- **`Sec-WebSocket-Accept`**: Computed value proving the server understands WebSocket protocol

The `Sec-WebSocket-Accept` value is computed as:
```
Base64(SHA1(Sec-WebSocket-Key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"))
```

This prevents accidentally accepting WebSocket connections by HTTP servers that don't properly understand the protocol.

### After the Handshake

Once the handshake is complete:

1. The HTTP request/response exchange is finished
2. The TCP connection remains open but switches to WebSocket protocol
3. Both client and server can send WebSocket frames at any time
4. The connection remains open until either side sends a Close frame or the TCP connection is terminated

### Why HTTP Ports 80 and 443?

As stated in RFC 6455:

> WebSocket "is designed to work over HTTP ports 443 and 80 as well as to support HTTP proxies and intermediaries", making the WebSocket protocol compatible with HTTP.

This design choice provides several benefits:

1. **Firewall Traversal**: Most firewalls allow outbound traffic on ports 80 and 443
2. **Proxy Support**: HTTP proxies can handle the initial upgrade request
3. **TLS Support**: Port 443 supports encrypted WebSocket connections (WSS) using TLS, just like HTTPS
4. **Infrastructure Reuse**: Existing HTTP infrastructure (load balancers, reverse proxies) can be used
5. **No Special Configuration**: Network administrators don't need to open additional ports

## WebSocket Frame Structure

After the upgrade, data is transmitted using WebSocket frames. Each frame has a small header (2-14 bytes) followed by the payload:

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-------+-+-------------+-------------------------------+
|F|R|R|R| opcode|M| Payload len |    Extended payload length    |
|I|S|S|S|  (4)  |A|     (7)     |             (16/64)           |
|N|V|V|V|       |S|             |   (if payload len==126/127)   |
| |1|2|3|       |K|             |                               |
+-+-+-+-+-------+-+-------------+ - - - - - - - - - - - - - - - +
|     Extended payload length continued, if payload len == 127  |
+ - - - - - - - - - - - - - - - +-------------------------------+
|                               |Masking-key, if MASK set to 1  |
+-------------------------------+-------------------------------+
| Masking-key (continued)       |          Payload Data         |
+-------------------------------- - - - - - - - - - - - - - - - +
:                     Payload Data continued ...                :
+ - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - +
|                     Payload Data continued ...                |
+---------------------------------------------------------------+
```

### Frame Types (Opcodes)

- **0x0**: Continuation frame (for fragmented messages)
- **0x1**: Text frame (UTF-8 encoded text)
- **0x2**: Binary frame (arbitrary binary data)
- **0x8**: Connection close frame
- **0x9**: Ping frame (heartbeat/keep-alive)
- **0xA**: Pong frame (response to ping)

## WebSocket in Java

Java 11 introduced the `java.net.http.WebSocket` API as part of the HTTP Client. This provides a modern, standardized way to work with WebSocket connections.

### Basic Usage

```java
HttpClient client = HttpClient.newHttpClient();

WebSocket webSocket = client.newWebSocketBuilder()
    .buildAsync(URI.create("ws://localhost:8080/chat"), new WebSocket.Listener() {
        @Override
        public void onOpen(WebSocket webSocket) {
            System.out.println("WebSocket opened");
            webSocket.request(1); // Request one message
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            System.out.println("Received: " + data);
            webSocket.request(1); // Request next message
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            System.out.println("Closed: " + statusCode + " " + reason);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            System.err.println("Error: " + error.getMessage());
        }
    })
    .join();

// Send a message
webSocket.sendText("Hello, WebSocket!", true);
```

### Key Differences from HTTP Client Usage

1. **Listener Pattern**: WebSocket uses an event-driven listener model instead of request-response
2. **Flow Control**: The application must explicitly request messages using `webSocket.request(n)`
3. **Bidirectional**: Both client and server can initiate message sending at any time
4. **Connection Lifecycle**: The application manages when to send, receive, and close the connection

## Common Use Cases

WebSocket is particularly well-suited for:

1. **Chat Applications**: Real-time messaging between users
2. **Live Updates**: Stock prices, sports scores, news feeds
3. **Collaborative Editing**: Multiple users editing the same document simultaneously
4. **Gaming**: Real-time multiplayer game state synchronization
5. **IoT Device Communication**: Sensor data streaming and device control
6. **Notifications**: Push notifications from server to client
7. **Live Dashboards**: Real-time metrics and monitoring displays

## Security Considerations

### WebSocket Secure (WSS)

For production use, always use WSS (WebSocket Secure) which runs WebSocket over TLS:

```java
WebSocket webSocket = client.newWebSocketBuilder()
    .buildAsync(URI.create("wss://server.example.com/secure-chat"), listener)
    .join();
```

WSS provides:
- **Encryption**: All data is encrypted in transit
- **Authentication**: Server certificate validation
- **Integrity**: Protection against tampering

### Origin Checking

Servers should validate the `Origin` header in the handshake request to prevent unauthorized cross-origin connections:

```java
if (!isAllowedOrigin(request.getHeader("Origin"))) {
    response.setStatus(403);
    return;
}
```

### Input Validation

Always validate and sanitize data received over WebSocket connections, just as you would with HTTP requests.

## Comparison Summary

### When to Use WebSocket

✅ **Use WebSocket when:**
- You need real-time, bidirectional communication
- Server needs to push data to clients without polling
- You want to minimize latency and overhead
- You have frequent, ongoing communication between client and server

❌ **Don't use WebSocket when:**
- Simple request-response is sufficient
- Communication is infrequent
- You need HTTP caching or intermediary processing
- REST semantics are important for your API

### When to Use HTTP

✅ **Use HTTP when:**
- Traditional request-response pattern is sufficient
- You need caching, load balancing, or other HTTP infrastructure features
- Communication is infrequent or one-directional
- You want to leverage REST principles and HTTP semantics

❌ **Don't use HTTP when:**
- You need server-initiated communication without polling
- Real-time, low-latency updates are critical
- You want to minimize connection establishment overhead for frequent messages

## References

- [RFC 6455 - The WebSocket Protocol](https://datatracker.ietf.org/doc/html/rfc6455)
- [RFC 7230 Section 6.7 - HTTP/1.1 Upgrade](https://datatracker.ietf.org/doc/html/rfc7230#section-6.7)
- [Wikipedia - WebSocket](https://en.wikipedia.org/wiki/WebSocket)
- [Java 11 WebSocket API Documentation](https://docs.oracle.com/en/java/javase/11/docs/api/java.net.http/java/net/http/WebSocket.html)
