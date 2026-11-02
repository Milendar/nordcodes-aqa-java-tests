package qa.nordcodes;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import io.qameta.allure.Allure;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;

final class ApiClient {
  private static final ObjectMapper JSON =
      new ObjectMapper(
              JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  private final String baseUrl;

  ApiClient(String baseUrl) {
    this.baseUrl = baseUrl;
  }

  record Header(String name, String value) {}

  record Reply(int status, String contentType, String raw) {
    JsonNode body() {
      assertEquals("application/json", mediaType(contentType), "Ожидался ответ в формате JSON");
      JsonNode body =
          assertDoesNotThrow(() -> JSON.readTree(raw), "Ответ должен содержать корректный JSON");
      assertNotNull(body, "Тело ответа не должно быть пустым");
      assertTrue(body.isObject(), "Ответ должен быть JSON-объектом");
      assertTrue(body.path("result").isTextual(), "Поле result должно быть строкой");
      return body;
    }

    void ok() {
      assertSuccess(body());
    }

    void error() {
      assertError(body());
    }

    boolean succeeded() {
      JsonNode body = body();
      String result = body.get("result").asText();
      return switch (result) {
        case "OK" -> {
          assertSuccess(body);
          yield true;
        }
        case "ERROR" -> {
          assertError(body);
          yield false;
        }
        default -> throw new AssertionError("Неизвестный результат операции: " + result);
      };
    }

    private void assertSuccess(JsonNode body) {
      Allure.step(
          "Проверить успешное выполнение",
          () -> {
            assertEquals("OK", body.get("result").asText(), "Операция должна завершиться успешно");
          });
    }

    private void assertError(JsonNode body) {
      Allure.step(
          "Проверить отказ и причину",
          () -> {
            assertEquals("ERROR", body.get("result").asText(), "Операция должна быть отклонена");
            JsonNode message = body.path("message");
            assertTrue(
                message.isTextual() && !message.asText().isBlank(),
                "Ответ должен содержать текстовую причину отказа");
          });
    }

    void clientError() {
      Allure.step(
          "Проверить клиентскую ошибку HTTP",
          () -> assertTrue(status >= 400 && status < 500, "Ожидался HTTP 4xx, получено " + status));
    }
  }

  Reply send(String token, String action) throws Exception {
    return send(token, action, TestEnvironment.KEY);
  }

  Reply send(String token, String action, String key) throws Exception {
    return request("POST", "/endpoint", form(token, action), headers(key), Duration.ofSeconds(8));
  }

  Reply request(String method, String path, String body, List<Header> headers) throws Exception {
    return request(method, path, body, headers, Duration.ofSeconds(8));
  }

  Reply request(String method, String path, String body, List<Header> headers, Duration timeout)
      throws Exception {
    return Allure.step(
        "Отправить " + method + " " + path.split("\\?", 2)[0],
        () -> performRequest(method, path, body, headers, timeout));
  }

  void expectClientTimeout(String token, Duration timeout) throws Exception {
    Allure.step(
        "Проверить прекращение ожидания клиентом",
        () -> {
          assertThrows(
              HttpTimeoutException.class,
              () ->
                  performRequest(
                      "POST",
                      "/endpoint",
                      form(token, "LOGIN"),
                      headers(TestEnvironment.KEY),
                      timeout));
        });
  }

  private Reply performRequest(
      String method, String path, String body, List<Header> headers, Duration timeout)
      throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(timeout)
            .method(method, HttpRequest.BodyPublishers.ofString(body));
    StringBuilder description = new StringBuilder(method + " " + path + "\n");
    for (Header header : headers) {
      builder.header(header.name(), header.value());
      description
          .append(header.name())
          .append(": ")
          .append(header.name().equalsIgnoreCase("X-Api-Key") ? "<скрыт>" : header.value())
          .append('\n');
    }
    Allure.addAttachment("Запрос", "text/plain", description + "\n" + body, ".txt");
    try {
      HttpResponse<String> response =
          http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      String type = response.headers().firstValue("Content-Type").orElse("");
      Allure.addAttachment(
          "Ответ",
          "text/plain",
          "HTTP " + response.statusCode() + "\nContent-Type: " + type + "\n\n" + response.body(),
          ".txt");
      return new Reply(response.statusCode(), type, response.body());
    } catch (HttpTimeoutException e) {
      Allure.addAttachment("Ожидание ответа", "Лимит тестового клиента: " + timeout);
      throw e;
    }
  }

  Reply sendObserved(String token, String action, CountDownLatch sent) throws Exception {
    return Allure.step(
        "Отправить операцию " + action + " с подтверждением отправки",
        () -> {
          byte[] body = form(token, action).getBytes(StandardCharsets.UTF_8);
          HttpURLConnection connection =
              (HttpURLConnection) URI.create(baseUrl + "/endpoint").toURL().openConnection();
          connection.setRequestMethod("POST");
          connection.setConnectTimeout(3000);
          connection.setReadTimeout(8000);
          connection.setInstanceFollowRedirects(false);
          connection.setDoOutput(true);
          // Streaming mode prevents buffering the body until getResponseCode().
          connection.setFixedLengthStreamingMode(body.length);
          for (Header header : headers(TestEnvironment.KEY))
            connection.setRequestProperty(header.name(), header.value());
          Allure.addAttachment(
              "Запрос",
              "text/plain",
              "POST /endpoint\n"
                  + "Content-Type: application/x-www-form-urlencoded\n"
                  + "Accept: application/json\n"
                  + "X-Api-Key: <скрыт>\n\n"
                  + form(token, action),
              ".txt");
          try {
            try (OutputStream output = connection.getOutputStream()) {
              output.write(body);
              output.flush();
            }
            sent.countDown();
            Allure.step("Тело HTTP-запроса отправлено");
            int status = connection.getResponseCode();
            String type = connection.getHeaderField("Content-Type");
            String responseBody;
            try (InputStream input =
                status >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
              responseBody =
                  input == null ? "" : new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
            Allure.addAttachment(
                "Ответ",
                "text/plain",
                "HTTP " + status + "\nContent-Type: " + type + "\n\n" + responseBody,
                ".txt");
            return new Reply(status, type == null ? "" : type, responseBody);
          } finally {
            connection.disconnect();
          }
        });
  }

  static List<Header> headers(String key) {
    List<Header> result = new ArrayList<>();
    result.add(new Header("Content-Type", "application/x-www-form-urlencoded"));
    result.add(new Header("Accept", "application/json"));
    if (key != null) result.add(new Header("X-Api-Key", key));
    return result;
  }

  static String form(String token, String action) {
    List<String> result = new ArrayList<>();
    if (token != null) result.add("token=" + encode(token));
    if (action != null) result.add("action=" + encode(action));
    return String.join("&", result);
  }

  static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  static String mediaType(String value) {
    return value == null ? "" : value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
  }
}
