package com.roshka.order;

import static org.assertj.core.api.Assertions.*;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.json.JsonMapper;

class ServiceIT {
  @Test
  void listsAllOrders() throws Exception {
    String first = createOrder();
    String second = createOrder();

    var response = request("GET", "/orders", "list-" + UUID.randomUUID(), null);

    assertThat(response.statusCode()).isEqualTo(200);
    var orders = JSON.readTree(response.body());
    assertThat(orders.toString()).contains(first, second);
  }

  static final PostgreSQLContainer DB = new PostgreSQLContainer("postgres:18.3-alpine");
  static final RabbitMQContainer BROKER = new RabbitMQContainer("rabbitmq:4.2.3-management-alpine");
  static ConfigurableApplicationContext app;
  static String base;
  static final JsonMapper JSON = JsonMapper.builder().build();
  static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  @BeforeAll
  static void start() {
    DB.start();
    BROKER.start();
    app =
        SpringApplication.run(
            Application.class,
            "--server.port=0",
            "--spring.datasource.url=" + DB.getJdbcUrl(),
            "--spring.datasource.username=" + DB.getUsername(),
            "--spring.datasource.password=" + DB.getPassword(),
            "--spring.rabbitmq.host=" + BROKER.getHost(),
            "--spring.rabbitmq.port=" + BROKER.getAmqpPort(),
            "--spring.rabbitmq.username=" + BROKER.getAdminUsername(),
            "--spring.rabbitmq.password=" + BROKER.getAdminPassword());
    base = "http://localhost:" + app.getEnvironment().getProperty("local.server.port");
  }

  @AfterAll
  static void stop() {
    if (app != null) app.close();
    BROKER.stop();
    DB.stop();
  }

  static HttpResponse<String> request(String method, String path, String key, String body)
      throws Exception {
    return HTTP.send(
        HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/json").header("Idempotency-Key", key).method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void createReplayAndCancelArePersistent() throws Exception {
    String key = UUID.randomUUID().toString();
    String body = "{\"items\":[{\"productId\":\"" + UUID.randomUUID() + "\",\"quantity\":2}]}";
    var created = request("POST", "/orders", key, body);
    assertThat(created.statusCode()).isEqualTo(202);
    assertThat(request("POST", "/orders", key, body).body()).isEqualTo(created.body());
    String id = JSON.readTree(created.body()).get("orderId").asText();
    var cancelled = request("POST", "/orders/" + id + "/cancel", key, "{}");
    assertThat(cancelled.statusCode()).isEqualTo(202);
    assertThat(JSON.readTree(cancelled.body()).get("status").asText()).isEqualTo("CANCELLED");
    assertThat(request("POST", "/orders/" + id + "/cancel", key, "{}").body()).isEqualTo(cancelled.body());
    assertThat(
            request("POST", "/orders/" + id + "/cancel", UUID.randomUUID().toString(), "{}").statusCode())
        .isEqualTo(200);
    assertThat(JSON.readTree(created.body()).get("statusUrl").asText()).isEqualTo("/orders/" + id);
    assertThat(
            request(
                    "POST",
                    "/orders",
                    key,
                    "{\"items\":[{\"productId\":\"" + UUID.randomUUID() + "\",\"quantity\":1}]}").statusCode())
        .isEqualTo(409);
  }

  static String createOrder() throws Exception {
    String id = UUID.randomUUID().toString();
    var response = request("POST", "/orders", UUID.randomUUID().toString(),
        "{\"items\":[{\"productId\":\"" + id + "\",\"quantity\":1}]}");
    assertThat(response.statusCode()).isEqualTo(202);
    return JSON.readTree(response.body()).get("orderId").asText();
  }
}
