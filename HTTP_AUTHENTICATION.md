# HTTP Authentication Support in Java HTTP Client

This document provides a comprehensive evaluation of HTTP authentication scheme support in the Java 11+ HTTP Client (`java.net.http.HttpClient`).

## Executive Summary

The Java HTTP Client provides **native support for HTTP Basic and limited support for Digest authentication** through `java.net.Authenticator`. Other authentication schemes (NTLM, SPNEGO/Kerberos) are **not natively supported** and require either application-layer implementation or third-party libraries.

## Authentication Schemes Evaluated

### 1. HTTP Basic Authentication

**Status:** ✅ **Fully Supported (Native)**

**Native Support:** YES via `java.net.Authenticator`

**Description:**
HTTP Basic authentication is the simplest HTTP authentication scheme, transmitting credentials as base64-encoded username:password pairs in the Authorization header.

**Implementation - Native:**
```java
Authenticator authenticator = new Authenticator() {
    @Override
    protected PasswordAuthentication getPasswordAuthentication() {
        return new PasswordAuthentication("username", "password".toCharArray());
    }
};

HttpClient client = HttpClient.newBuilder()
    .authenticator(authenticator)
    .build();

// Authenticator automatically handles 401 challenges and retries
HttpResponse<String> response = client.send(request, 
    HttpResponse.BodyHandlers.ofString());
```

**Implementation - Manual:**
```java
String credentials = username + ":" + password;
String encoded = Base64.getEncoder().encodeToString(
    credentials.getBytes(StandardCharsets.UTF_8));

HttpRequest request = HttpRequest.newBuilder()
    .uri(uri)
    .header("Authorization", "Basic " + encoded)
    .GET()
    .build();
```

**Effort for Manual Implementation:** LOW
- Simple base64 encoding of username:password
- Single header addition
- ~5 lines of code

**Restrictions:** None

**Recommendation:** Use native `Authenticator` for automatic challenge-response handling.

---

### 2. HTTP Digest Authentication

**Status:** ⚠️ **Limited Support (Native)**

**Native Support:** YES (Limited) via `java.net.Authenticator`

**Description:**
HTTP Digest authentication is a challenge-response scheme that applies MD5 hashing to credentials, providing better security than Basic authentication (though still deprecated in favor of TLS).

**Implementation - Native:**
```java
Authenticator authenticator = new Authenticator() {
    @Override
    protected PasswordAuthentication getPasswordAuthentication() {
        return new PasswordAuthentication("username", "password".toCharArray());
    }
};

HttpClient client = HttpClient.newBuilder()
    .authenticator(authenticator)
    .build();

// Authenticator attempts to handle Digest challenges
HttpResponse<String> response = client.send(request, 
    HttpResponse.BodyHandlers.ofString());
```

**Implementation - Manual:**
Manual Digest implementation requires:

1. Parse `WWW-Authenticate` challenge header
2. Extract parameters: realm, nonce, qop, opaque, algorithm
3. Compute HA1 = MD5(username:realm:password)
4. Compute HA2 = MD5(method:uri)
5. Compute response = MD5(HA1:nonce:nc:cnonce:qop:HA2)
6. Construct Authorization header with all parameters

```java
// Simplified example - full implementation is much longer
MessageDigest md5 = MessageDigest.getInstance("MD5");

String ha1 = md5hash(username + ":" + realm + ":" + password);
String ha2 = md5hash(method + ":" + uri);
String response = md5hash(ha1 + ":" + nonce + ":" + nc + ":" + 
                          cnonce + ":" + qop + ":" + ha2);

String authHeader = String.format(
    "Digest username=\"%s\", realm=\"%s\", nonce=\"%s\", " +
    "uri=\"%s\", qop=%s, nc=%s, cnonce=\"%s\", response=\"%s\", opaque=\"%s\"",
    username, realm, nonce, uri, qop, nc, cnonce, response, opaque);
```

**Effort for Manual Implementation:** MODERATE to HIGH
- Challenge header parsing
- MD5 hash computations (3 operations)
- Client nonce generation
- Request counter management
- Complex header construction
- ~50-100 lines of code

**Restrictions:**
- Native support varies by JDK version
- Some JDK versions have better Digest support than others
- Algorithm variations (MD5, MD5-sess, SHA-256) may not be fully supported

**Recommendation:** 
- Try native `Authenticator` first
- Fallback to manual implementation if needed
- Consider that Digest is deprecated in favor of HTTPS with Basic auth

---

### 3. NTLM Authentication

**Status:** ❌ **Not Supported (Native)**

**Native Support:** NO

**Description:**
NTLM (NT LAN Manager) is a Microsoft proprietary authentication protocol using a challenge-response mechanism. It's commonly used in Windows enterprise environments.

**Implementation - Native:**
```java
// java.net.Authenticator does NOT support NTLM
Authenticator authenticator = new Authenticator() {
    @Override
    protected PasswordAuthentication getPasswordAuthentication() {
        return new PasswordAuthentication("username", "password".toCharArray());
    }
};
// This will NOT work for NTLM endpoints
```

**Implementation - Manual:**
NTLM requires a complex 3-step protocol:

1. **Type 1 Message (Negotiate):** Client sends negotiation message
   - Binary protocol with flags and capabilities
   - Workstation and domain information

2. **Type 2 Message (Challenge):** Server responds with challenge
   - Server challenge (8 bytes)
   - Target information
   - Flags and version info

3. **Type 3 Message (Authenticate):** Client sends authentication
   - LM and NT hashed responses
   - DES and MD4/MD5 cryptographic operations
   - Unicode encoding
   - Target and workstation names

**Cryptographic Requirements:**
- DES encryption
- MD4 hashing
- MD5 and HMAC-MD5
- NTLMv1 and NTLMv2 protocols
- Binary message encoding

**Effort for Manual Implementation:** VERY HIGH
- Complex binary protocol
- Multiple cryptographic operations
- Multi-step challenge-response
- Windows-specific concepts (domain, workstation)
- ~500+ lines of code for complete implementation

**Restrictions:**
- Proprietary Microsoft protocol
- Cannot be easily implemented with standard Java HTTP Client
- Requires deep protocol knowledge
- Security considerations (NTLMv1 is insecure, NTLMv2 is complex)

**Recommendation:**
- **Use Apache HttpClient with JCIFS-NG library** for NTLM support
- Alternative: **OkHttp with okhttp-digest library** (includes NTLM)
- Do NOT attempt manual implementation unless absolutely necessary

**Third-Party Library Example (Apache HttpClient):**
```java
// Add dependency: org.apache.httpcomponents.client5:httpclient5
// Add dependency: eu.agno3.jcifs:jcifs-ng

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.NTCredentials;

NTCredentials credentials = new NTCredentials(
    "username", "password".toCharArray(), 
    "workstation", "domain");

CloseableHttpClient client = HttpClients.custom()
    .setDefaultCredentialsProvider(credsProvider)
    .build();
```

---

### 4. SPNEGO/Kerberos Authentication (Negotiate)

**Status:** ⚠️ **Limited Support via JGSS**

**Native Support:** NO (via `java.net.Authenticator`)  
**Alternative Support:** YES (via Java GSS-API)

**Description:**
SPNEGO (Simple and Protected GSSAPI Negotiation Mechanism) is typically used with Kerberos for single sign-on (SSO) in enterprise environments. It uses the "Negotiate" authentication scheme.

**Implementation - Native Authenticator:**
```java
// java.net.Authenticator does NOT automatically handle SPNEGO
Authenticator authenticator = new Authenticator() {
    @Override
    protected PasswordAuthentication getPasswordAuthentication() {
        return new PasswordAuthentication("username", "password".toCharArray());
    }
};
// This will NOT work for Negotiate/SPNEGO endpoints
```

**Implementation - Java GSS-API:**
SPNEGO/Kerberos can be implemented using Java's GSS-API:

```java
import org.ietf.jgss.*;

// System properties configuration
System.setProperty("java.security.krb5.realm", "EXAMPLE.COM");
System.setProperty("java.security.krb5.kdc", "kdc.example.com");
System.setProperty("javax.security.auth.useSubjectCredsOnly", "false");

// Create GSS context
GSSManager manager = GSSManager.getInstance();
GSSName serverName = manager.createName(
    "HTTP@server.example.com", 
    GSSName.NT_HOSTBASED_SERVICE);

Oid krb5Mechanism = new Oid("1.2.840.113554.1.2.2");
Oid spnegoMechanism = new Oid("1.3.6.1.5.5.2");

GSSContext context = manager.createContext(
    serverName, 
    spnegoMechanism, 
    null, 
    GSSContext.DEFAULT_LIFETIME);

context.requestMutualAuth(true);
context.requestCredDeleg(true);

// Generate token
byte[] token = context.initSecContext(new byte[0], 0, 0);
String encodedToken = Base64.getEncoder().encodeToString(token);

// Add to request
HttpRequest request = HttpRequest.newBuilder()
    .uri(uri)
    .header("Authorization", "Negotiate " + encodedToken)
    .GET()
    .build();

// Handle server response and continue context if needed
```

**Required Infrastructure:**
1. **Kerberos KDC (Key Distribution Center)**
   - Active Directory or MIT Kerberos
   - Properly configured realm

2. **Configuration Files:**
   - `krb5.conf` - Kerberos configuration
   - `login.conf` - JAAS login configuration
   - Keytab files (for service accounts)

3. **DNS Configuration:**
   - Proper SPN (Service Principal Name) records
   - Reverse DNS lookups

4. **System Properties:**
   ```properties
   java.security.krb5.realm=EXAMPLE.COM
   java.security.krb5.kdc=kdc.example.com
   java.security.auth.login.config=/path/to/login.conf
   javax.security.auth.useSubjectCredsOnly=false
   ```

**Effort for Manual Implementation:** VERY HIGH
- Kerberos ticket acquisition
- GSS-API context management
- Token generation and exchange
- Multi-step negotiation
- Infrastructure configuration
- ~200+ lines of code plus configuration

**Restrictions:**
- Requires external Kerberos infrastructure (KDC)
- Requires proper DNS and SPN configuration
- Requires krb5.conf and keytab files
- Platform-specific considerations
- Complex troubleshooting

**Recommendation:**
- **Use Java GSS-API** (`org.ietf.jgss`) for Kerberos/SPNEGO
- Ensure proper Kerberos infrastructure is in place
- Test thoroughly in development environment first
- Consider using **Waffle** (Windows) or **Apache Kerby** libraries for simplified setup
- For Windows environments, consider using native Windows authentication

**Example with Proper Error Handling:**
```java
try {
    System.setProperty("sun.security.krb5.debug", "true"); // Enable debug
    LoginContext loginContext = new LoginContext("KerberosLogin", 
        new CallbackHandler() { /* handle callbacks */ });
    loginContext.login();
    
    Subject.doAs(loginContext.getSubject(), (PrivilegedExceptionAction<Void>) () -> {
        // GSS-API code here
        return null;
    });
} catch (LoginException | PrivilegedActionException e) {
    // Handle Kerberos authentication failures
}
```

---

### 5. Bearer Token Authentication (OAuth 2.0)

**Status:** ❌ **Not Supported (Native)**

**Native Support:** NO

**Description:**
Bearer token authentication is commonly used with OAuth 2.0 and OpenID Connect. The client sends a token (typically a JWT) in the Authorization header.

**Implementation - Manual:**
```java
HttpClient client = HttpClient.newBuilder().build();

String accessToken = "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9..."; // From OAuth flow

HttpRequest request = HttpRequest.newBuilder()
    .uri(uri)
    .header("Authorization", "Bearer " + accessToken)
    .GET()
    .build();

HttpResponse<String> response = client.send(request, 
    HttpResponse.BodyHandlers.ofString());
```

**Token Acquisition (Separate Concern):**
Bearer tokens must be obtained through an OAuth 2.0 flow:

1. **Authorization Code Flow:**
   ```java
   // 1. Redirect user to authorization endpoint
   // 2. Receive authorization code
   // 3. Exchange code for access token
   HttpRequest tokenRequest = HttpRequest.newBuilder()
       .uri(URI.create("https://auth.example.com/oauth/token"))
       .header("Content-Type", "application/x-www-form-urlencoded")
       .POST(HttpRequest.BodyPublishers.ofString(
           "grant_type=authorization_code&" +
           "code=" + authCode + "&" +
           "client_id=" + clientId + "&" +
           "client_secret=" + clientSecret))
       .build();
   ```

2. **Client Credentials Flow:**
   ```java
   HttpRequest tokenRequest = HttpRequest.newBuilder()
       .uri(URI.create("https://auth.example.com/oauth/token"))
       .header("Content-Type", "application/x-www-form-urlencoded")
       .POST(HttpRequest.BodyPublishers.ofString(
           "grant_type=client_credentials&" +
           "client_id=" + clientId + "&" +
           "client_secret=" + clientSecret))
       .build();
   ```

**Token Management:**
```java
public class TokenManager {
    private String accessToken;
    private Instant expiry;
    
    public String getToken() {
        if (accessToken == null || Instant.now().isAfter(expiry)) {
            refreshToken();
        }
        return accessToken;
    }
    
    private void refreshToken() {
        // Implement token refresh logic
    }
}
```

**Effort for Manual Implementation:** LOW (for header), MODERATE (with token management)
- Simple header addition (~1 line)
- Token acquisition requires OAuth flow implementation (~50-100 lines)
- Token refresh and expiry management
- Secure token storage

**Restrictions:**
- Token acquisition is a separate concern
- Requires OAuth 2.0 provider configuration
- Token refresh logic needed for long-lived applications
- Secure token storage considerations

**Recommendation:**
- Use established OAuth 2.0 libraries:
  - **Nimbus OAuth 2.0 SDK** - Comprehensive OAuth 2.0 implementation
  - **ScribeJava** - Simple OAuth library
  - **Spring Security OAuth** - Full-featured for Spring applications
- Implement token refresh mechanism
- Store tokens securely (not in source code)
- Handle token expiry gracefully

---

## Support Matrix

| Authentication Scheme | Native Support | Manual Effort | Implementation Complexity | Restrictions |
|-----------------------|----------------|---------------|---------------------------|--------------|
| **HTTP Basic** | ✅ YES (Authenticator) | LOW | Very Simple (5 lines) | None |
| **HTTP Digest** | ⚠️ LIMITED (Authenticator) | MODERATE-HIGH | Complex (50-100 lines) | Version-dependent |
| **NTLM** | ❌ NO | VERY HIGH | Very Complex (500+ lines) | Proprietary, needs 3rd party |
| **SPNEGO/Kerberos** | ⚠️ NO (use JGSS) | VERY HIGH | Very Complex (200+ lines) | Requires Kerberos infra |
| **Bearer Token** | ❌ NO | LOW-MODERATE | Simple header, complex flow | Token acquisition separate |

## Recommendations by Use Case

### Internal Applications (Intranet)
- **Windows Environment:** Use Apache HttpClient with JCIFS for NTLM
- **Enterprise SSO:** Use Java GSS-API for Kerberos/SPNEGO
- **Simple Auth:** Use native Authenticator with Basic over HTTPS

### Internet-Facing Applications
- **Modern APIs:** Use Bearer tokens with OAuth 2.0 libraries
- **Legacy Support:** Use native Authenticator with Basic over HTTPS
- **Avoid:** Digest (deprecated), NTLM (Windows-specific)

### Microservices
- **Service-to-Service:** Use Bearer tokens (JWT) or mutual TLS
- **User-to-Service:** Use Bearer tokens with OAuth 2.0/OIDC
- **API Gateway:** Centralize authentication at gateway level

## Security Considerations

### HTTP Basic Authentication
- ⚠️ **ALWAYS use HTTPS** - credentials are only base64-encoded
- ✅ Simple and widely supported
- ✅ Suitable for server-to-server communication over TLS
- ❌ Never use over unencrypted HTTP

### HTTP Digest Authentication
- ⚠️ Deprecated in favor of HTTPS + Basic
- ✅ Better than Basic over HTTP (but still avoid)
- ❌ Complex implementation
- ❌ MD5 is cryptographically weak

### NTLM Authentication
- ⚠️ NTLMv1 is insecure (DES, MD4)
- ⚠️ NTLMv2 is better but still proprietary
- ✅ Suitable for Windows enterprise networks
- ❌ Avoid for internet-facing applications

### SPNEGO/Kerberos
- ✅ Strong authentication for enterprise SSO
- ✅ Mutual authentication support
- ⚠️ Complex setup and infrastructure
- ⚠️ Ticket expiry must be handled

### Bearer Tokens
- ✅ Modern standard for API authentication
- ✅ Works well with OAuth 2.0 and JWT
- ⚠️ Token must be protected (HTTPS)
- ⚠️ Token expiry and refresh needed
- ⚠️ Secure token storage critical

## Testing

The test suite (`JavaHttpClientAuthenticationTest`) demonstrates:

1. ✅ Native Basic authentication with Authenticator
2. ✅ Manual Basic authentication with header
3. ✅ Basic authentication over HTTPS
4. ✅ Challenge-response flow handling
5. ⚠️ Digest authentication (limited native support)
6. ❌ NTLM authentication (not supported)
7. ❌ SPNEGO/Kerberos authentication (not supported via Authenticator)
8. ✅ Bearer token authentication (manual implementation)

Run tests:
```bash
mvn test -Dtest=JavaHttpClientAuthenticationTest
```

## References

- [RFC 7617 - HTTP Basic Authentication](https://datatracker.ietf.org/doc/html/rfc7617)
- [RFC 7616 - HTTP Digest Authentication](https://datatracker.ietf.org/doc/html/rfc7616)
- [RFC 4559 - SPNEGO-based Kerberos and NTLM HTTP Authentication](https://datatracker.ietf.org/doc/html/rfc4559)
- [RFC 6750 - OAuth 2.0 Bearer Token Usage](https://datatracker.ietf.org/doc/html/rfc6750)
- [Java GSS-API Documentation](https://docs.oracle.com/en/java/javase/11/security/java-generic-security-services-java-gss-api1.html)
- [Microsoft NTLM Documentation](https://docs.microsoft.com/en-us/windows/win32/secauthn/microsoft-ntlm)
- [OAuth 2.0 Specification (RFC 6749)](https://datatracker.ietf.org/doc/html/rfc6749)

## Conclusion

The Java HTTP Client provides **excellent native support for HTTP Basic authentication** through `java.net.Authenticator`, making it suitable for most common authentication scenarios when used over HTTPS.

For other authentication schemes:
- **Digest:** Limited native support, consider manual implementation if needed
- **NTLM:** Use Apache HttpClient with JCIFS-NG library
- **SPNEGO/Kerberos:** Use Java GSS-API with proper infrastructure
- **Bearer Token:** Simple manual implementation, use OAuth libraries for token management

**General Recommendation:** For new applications, use **Bearer tokens with OAuth 2.0** for API authentication, or **HTTP Basic over HTTPS** for simple server-to-server communication.
