package com.jkxt.demo;

import static org.assertj.core.api.Assertions.*;
import com.jkxt.notify.service.NotificationService;
import com.jkxt.notify.service.NotificationDispatcher;
import com.jkxt.notify.channel.*;
import com.jkxt.security.auth.AuthException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties={"jkxt.auth.captcha-debug=false","jkxt.notify.email.enabled=false","jkxt.notify.sms.enabled=false"})
@AutoConfigureMockMvc(printOnlyOnFailure=false,print=org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
@ActiveProfiles("test") @Import(DemoTestFixture.Time.class)
class DemoSideEffectTest extends DemoTestFixture.IntegrationBase {
  @Autowired NotificationService notices;
  @Autowired NotificationDispatcher dispatcher;
  @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
  @AfterEach void retire(){DemoTestFixture.retire(jdbc);}
  @Test void directDispatcherInvocationRejectsDemoEvenWithoutHttpContext() {
    assertThatThrownBy(()->dispatcher.dispatch(List.of(new NotificationRecipient(UUID.randomUUID(),"Synthetic","demo@example.invalid","000")),"demo","demo"))
        .isInstanceOf(AuthException.class).hasMessageContaining("演示");
  }
  @Test void localTeachingNotificationIsExplicitlySimulated() throws Exception {
    var b=new DemoTestFixture(application).enter();
    notices.publishSystemNotice(b.org(),null,"IMPORT","导入完成","已验证",null,Map.of("count",1));
    assertThat(jdbc.queryForObject("select title from notification where org_id=?",String.class,b.org())).contains("模拟");
    assertThat(jdbc.queryForObject("select payload->>'simulated' from notification where org_id=?",String.class,b.org())).isEqualTo("true");
  }
  @Test void paidAiBoundaryRejectsBeforeLoopbackReceiverEvenWithoutRequestContext() throws Exception {
    var received=new AtomicInteger();var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/",exchange->{received.incrementAndGet();byte[] body="{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"{}\"}}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});server.start();
    try {
      var properties=new com.jkxt.ai.config.LlmProperties();properties.setEnabled(true);properties.setApiKey("synthetic-loopback-key");properties.setProvider("qwen");properties.setModel("qwen-plus");properties.setMaxRetries(0);properties.setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort());
      var client=new com.jkxt.ai.client.OpenAiCompatibleLlmClient(properties,new com.fasterxml.jackson.databind.ObjectMapper(),application.getBean(DemoSideEffectPolicy.class));
      assertThatThrownBy(()->client.chat(new com.jkxt.ai.client.LlmClient.ChatRequest("parse","qwen","qwen-plus","synthetic","synthetic",Map.of())))
          .isInstanceOf(AuthException.class);
      assertThat(received.get()).isZero();
    }finally{server.stop(0);}
  }
  @Test void emailSmsAndWebhookReceiversSeeNoDemoDelivery() throws Exception {
    var received=new AtomicInteger();var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/",exchange->{received.incrementAndGet();exchange.sendResponseHeaders(204,-1);exchange.close();});server.start();
    try(var client=java.net.http.HttpClient.newHttpClient()) {
      List<NotificationSender> senders=List.of("email","sms","webhook").stream().map(name->new NotificationSender(){
        public String channel(){return name;}
        public boolean isEnabled(){return true;}
        public DeliveryOutcome send(NotificationRecipient recipient,String subject,String body){
          try{var result=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/"+name)).POST(java.net.http.HttpRequest.BodyPublishers.ofString("synthetic")).build(),java.net.http.HttpResponse.BodyHandlers.discarding());return result.statusCode()==204?DeliveryOutcome.DELIVERED:DeliveryOutcome.FAILED;}catch(Exception error){throw new IllegalStateException(error);}
        }
      }).map(sender->(NotificationSender)sender).toList();
      var recipients=List.of(new NotificationRecipient(UUID.randomUUID(),"Synthetic","demo@example.invalid","000"));
      var formal=new com.jkxt.common.config.DeployProperties();formal.setMode("private");
      new NotificationDispatcher(senders,new DemoSideEffectPolicy(formal)).dispatch(recipients,"receiver control","synthetic");
      assertThat(received.get()).isEqualTo(3);received.set(0);
      var guarded=new NotificationDispatcher(senders,application.getBean(DemoSideEffectPolicy.class));
      assertThatThrownBy(()->guarded.dispatch(recipients,"demo","synthetic")).isInstanceOf(AuthException.class);
      assertThat(received.get()).isZero();
      var f=new DemoTestFixture(application);var b=f.enter();
      for(String path:List.of("/api/v1/tasks/"+UUID.randomUUID()+"/publish","/api/v1/nl-policy/parse"))
        assertThat(application.getBean(org.springframework.test.web.servlet.MockMvc.class).perform(f.apply(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path),b).contentType("application/json").content("{}")).andReturn().getResponse().getStatus()).isEqualTo(403);
      assertThat(received.get()).isZero();
    }finally{server.stop(0);}
  }
}
