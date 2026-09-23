package com.jkxt.security;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;

/** Real Tomcat request decoding, not MockMvc's independently supplied servletPath. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="jkxt.deploy.mode=saas")
@ActiveProfiles("test")
class PortalEncodedPathIntegrationTest {
  @LocalServerPort int port;
  private final HttpClient http=HttpClient.newHttpClient();

  @Test void decodedLoginRoutesCannotBypassOriginGuard() throws Exception {
    for(String path:new String[]{"/api/v1/auth/%6cogin","/%61pi/v1/auth/login","/api/v1/%61uth/login"}) {
      URI uri=URI.create("http://127.0.0.1:"+port+path);
      var result=http.send(HttpRequest.newBuilder(uri).header("Content-Type","application/json")
          .POST(HttpRequest.BodyPublishers.ofString("{}")).build(),HttpResponse.BodyHandlers.ofString());
      assertEquals(403,result.statusCode(),path+" reached decoded MVC login without Origin: "+result.body());
      assertTrue(result.body().contains("AUTH_ORIGIN_INVALID"),path+": "+result.body());
      assertTrue(result.headers().firstValue("Set-Cookie").isEmpty());
      // java.net.http explicitly transmits Origin; HttpURLConnection may suppress that header.
      var allowed=http.send(HttpRequest.newBuilder(uri).header("Content-Type","application/json")
          .header("Origin","http://localhost:8080").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),HttpResponse.BodyHandlers.ofString());
      assertEquals(400,allowed.statusCode(),path+": "+allowed.body());
      assertTrue(allowed.body().contains("VALIDATION_FAILED")); // Valid source reaches the same MVC route.
    }
  }
  @Test void utf8BusinessPathsAreNotBlanketRejected() throws Exception {
    var result=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/teachers/%E5%BC%A0%E4%B8%89")).GET().build(),HttpResponse.BodyHandlers.ofString());
    assertEquals(401,result.statusCode());
    assertTrue(result.body().contains("AUTH_UNAUTHENTICATED"));
  }
}
