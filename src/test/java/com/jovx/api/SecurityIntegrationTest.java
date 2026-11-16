package com.jovx.api;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.time.Duration;
import java.util.Base64;
import java.util.Date;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.test.context.ActiveProfiles("security-test")
class SecurityIntegrationTest {
    static final KeyPair KEYS = newKeys();
    static final String ISSUER = "https://identity.jovx.test";
    static final String AUDIENCE = "jovx-api";
    @LocalServerPort int port;
    final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    static KeyPair newKeys() {
        try { var g=KeyPairGenerator.getInstance("RSA"); g.initialize(2048); return g.generateKeyPair(); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        try {
            var file=Files.createTempFile("jovx-test-public-", ".pem");
            var encoded=Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(KEYS.getPublic().getEncoded());
            Files.writeString(file,"-----BEGIN PUBLIC KEY-----\n"+encoded+"\n-----END PUBLIC KEY-----\n");
            file.toFile().deleteOnExit();
            registry.add("jovx.security.jwt.public-key",()->file.toUri().toString());
            registry.add("jovx.security.jwt.issuer",()->ISSUER);
            registry.add("jovx.security.jwt.audience",()->AUDIENCE);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    JWTClaimsSet.Builder claims() {
        return new JWTClaimsSet.Builder().issuer(ISSUER).audience(AUDIENCE)
            .subject("test-member").issueTime(new Date())
            .expirationTime(Date.from(Instant.now().plusSeconds(300))).claim("scope", "profile:read");
    }

    String sign(JWTClaimsSet claims, KeyPair keys) throws Exception {
        var jwt=new SignedJWT(new JWSHeader(JWSAlgorithm.RS256),claims);
        jwt.sign(new RSASSASigner((RSAPrivateKey) keys.getPrivate()));
        return jwt.serialize();
    }

    HttpResponse<String> get(String path,String token) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path))
            .timeout(Duration.ofSeconds(10));
        if(token!=null) request.header("Authorization","Bearer "+token);
        return client.send(request.GET().build(),HttpResponse.BodyHandlers.ofString());
    }

    @Test void healthIsPublicAndDoesNotRevealDetails() throws Exception {
        var response=get("/actuator/health",null);
        assertEquals(200,response.statusCode()); assertFalse(response.body().contains("components"));
    }
    @Test void anonymousAndMalformedTokensAreRejected() throws Exception {
        assertEquals(401,get("/api/v1/session",null).statusCode());
        assertEquals(401,get("/api/v1/session","invalid").statusCode());
    }
    @Test void validSignedTokenIsAcceptedWithoutAnHttpSessionCookie() throws Exception {
        var response=get("/api/v1/session",sign(claims().build(),KEYS));
        assertEquals(200,response.statusCode()); assertTrue(response.body().contains("test-member"));
        assertTrue(response.body().contains("SCOPE_profile:read"));
        assertTrue(response.headers().allValues("set-cookie").isEmpty());
    }
    @Test void wrongSignatureIsRejected() throws Exception {
        assertEquals(401,get("/api/v1/session",sign(claims().build(),newKeys())).statusCode());
    }
    @Test void wrongIssuerAndAudienceAreRejected() throws Exception {
        assertEquals(401,get("/api/v1/session",sign(claims().issuer("https://wrong.test").build(),KEYS)).statusCode());
        assertEquals(401,get("/api/v1/session",sign(claims().audience("other-api").build(),KEYS)).statusCode());
        assertEquals(401,get("/api/v1/session",sign(claims().audience((String) null).build(),KEYS)).statusCode());
    }
    @Test void expiredAndFutureTokensAreRejected() throws Exception {
        assertEquals(401,get("/api/v1/session",sign(claims().expirationTime(Date.from(Instant.now().minusSeconds(300))).build(),KEYS)).statusCode());
        assertEquals(401,get("/api/v1/session",sign(claims().notBeforeTime(Date.from(Instant.now().plusSeconds(300))).build(),KEYS)).statusCode());
    }
    @Test void missingExpirationAndSubjectAreRejected() throws Exception {
        assertEquals(401,get("/api/v1/session",sign(claims().expirationTime(null).build(),KEYS)).statusCode());
        assertEquals(401,get("/api/v1/session",sign(claims().subject(null).build(),KEYS)).statusCode());
    }
    @Test void undeclaredRoutesRemainDenied() throws Exception {
        assertEquals(403,get("/api/v1/not-implemented",sign(claims().build(),KEYS)).statusCode());
    }
}
