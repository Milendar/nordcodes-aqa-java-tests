package qa.nordcodes;

import static org.junit.jupiter.api.Assertions.*;

import io.qameta.allure.*;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

@Epic("API пользователей")
@Feature("Формат HTTP-запроса")
@Tag("protocol")
class ProtocolTest extends BaseApiTest {
  @ParameterizedTest(name = "Недопустимый HTTP-метод: {0}")
  @ValueSource(strings = {"GET", "PUT", "DELETE", "PATCH", "HEAD"})
  @Description(
      "Метод вне интерфейса POST ожидается как клиентская ошибка HTTP 4xx, без выполнения"
          + " операции.")
  void rejectsUnsupportedMethod(String method) throws Exception {
    stubSuccessfulResponses();
    String token = token();
    ApiClient.Reply reply =
        api.request(
            method,
            "/endpoint?" + ApiClient.form(token, "LOGIN"),
            "",
            ApiClient.headers(TestEnvironment.KEY));
    noCalls();
    assertActionDenied(token);
    noCalls();
    reply.clientError();
    if (!method.equals("HEAD") && !reply.raw().isEmpty()) reply.error();
  }

  @Test
  @DisplayName("Запрос OPTIONS не выполняет пользовательскую операцию")
  void optionsDoesNotCreateSession() throws Exception {
    stubSuccessfulResponses();
    String token = token();
    ApiClient.Reply reply =
        api.request(
            "OPTIONS",
            "/endpoint?" + ApiClient.form(token, "LOGIN"),
            "",
            ApiClient.headers(TestEnvironment.KEY));
    assertTrue(reply.status() >= 200 && reply.status() < 500);
    noCalls();
    assertActionDenied(token);
    noCalls();
  }

  static Stream<Arguments> unsupportedTypes() {
    return Stream.of(
        Arguments.of("JSON", "application/json"),
        Arguments.of("обычный текст", "text/plain"),
        Arguments.of("XML", "application/xml"),
        Arguments.of("нет заголовка", null));
  }

  @ParameterizedTest(name = "Неподдерживаемый формат тела: {0}")
  @MethodSource("unsupportedTypes")
  @Description(
      "Неподдерживаемый формат ожидается как клиентская ошибка HTTP 4xx; состояние не изменяется.")
  void rejectsUnsupportedContentType(String label, String type) throws Exception {
    stubSuccessfulResponses();
    String token = token();
    List<ApiClient.Header> headers = ApiClient.headers(TestEnvironment.KEY);
    headers.removeIf(h -> h.name().equals("Content-Type"));
    if (type != null) headers.add(new ApiClient.Header("Content-Type", type));
    String body =
        "application/json".equals(type)
            ? "{\"token\":\"" + token + "\",\"action\":\"LOGIN\"}"
            : ApiClient.form(token, "LOGIN");
    ApiClient.Reply reply = api.request("POST", "/endpoint", body, headers);
    noCalls();
    assertActionDenied(token);
    noCalls();
    reply.clientError();
    if (!reply.raw().isEmpty()) reply.error();
  }

  @ParameterizedTest(name = "Форма запроса: {0}")
  @ValueSource(
      strings = {
        "обратный порядок полей",
        "percent-encoding",
        "UTF-8 в Content-Type",
        "без Accept",
        "пустое дополнительное поле"
      })
  void acceptsEquivalentForm(String variant) throws Exception {
    stubSuccessfulResponses();
    String token = token();
    String body = ApiClient.form(token, "LOGIN");
    List<ApiClient.Header> headers = ApiClient.headers(TestEnvironment.KEY);
    switch (variant) {
      case "обратный порядок полей" -> body = "action=LOGIN&token=" + token;
      case "percent-encoding" ->
          body =
              "token="
                  + token
                      .chars()
                      .mapToObj(c -> String.format("%%%02X", c))
                      .reduce("", String::concat)
                  + "&action=%4C%4F%47%49%4E";
      case "UTF-8 в Content-Type" -> {
        headers.removeIf(h -> h.name().equals("Content-Type"));
        headers.add(
            new ApiClient.Header(
                "Content-Type", "application/x-www-form-urlencoded; charset=UTF-8"));
      }
      case "без Accept" -> headers.removeIf(h -> h.name().equals("Accept"));
      case "пустое дополнительное поле" -> body += "&";
    }
    api.request("POST", "/endpoint", body, headers).ok();
    verifyUpstreamRequest("/auth", token, 1);
    assertRequestCount(1);
    clearJournal();
    assertActionAllowed(token);
    assertRequestCount(1);
  }

  @Test
  @DisplayName("Неподдерживаемый формат ответа не создаёт сеанс")
  void incompatibleAcceptDoesNotAuthorize() throws Exception {
    stubSuccessfulResponses();
    String token = token();
    List<ApiClient.Header> headers = ApiClient.headers(TestEnvironment.KEY);
    headers.removeIf(h -> h.name().equals("Accept"));
    headers.add(new ApiClient.Header("Accept", "text/plain"));
    ApiClient.Reply reply =
        api.request("POST", "/endpoint", ApiClient.form(token, "LOGIN"), headers);
    noCalls();
    assertActionDenied(token);
    noCalls();
    reply.clientError();
  }

  @ParameterizedTest(name = "Некорректное кодирование формы: {0}")
  @ValueSource(strings = {"%", "%G0", "%0", "%00"})
  void rejectsMalformedForm(String value) throws Exception {
    stubSuccessfulResponses();
    String token = token();
    ApiClient.Reply reply =
        api.request(
            "POST",
            "/endpoint",
            "token=" + value + "&action=LOGIN",
            ApiClient.headers(TestEnvironment.KEY));
    noCalls();
    assertActionDenied(token);
    noCalls();
    reply.clientError();
  }

  @ParameterizedTest(name = "Имя заголовка ключа: {0}")
  @ValueSource(strings = {"x-api-key", "X-API-KEY", "X-Api-Key"})
  void headerNameIsCaseInsensitive(String name) throws Exception {
    stubSuccessfulResponses();
    String token = token();
    List<ApiClient.Header> headers = ApiClient.headers(null);
    headers.add(new ApiClient.Header(name, TestEnvironment.KEY));
    api.request("POST", "/endpoint", ApiClient.form(token, "LOGIN"), headers).ok();
    verifyUpstreamRequest("/auth", token, 1);
    clearJournal();
    assertActionAllowed(token);
    assertRequestCount(1);
  }

  @ParameterizedTest(name = "Пробелы вокруг значения HTTP-заголовка: {0}")
  @ValueSource(strings = {"слева", "справа", "с двух сторон"})
  void ignoresHeaderOuterWhitespace(String variant) throws Exception {
    stubSuccessfulResponses();
    String token = token();
    String key =
        switch (variant) {
          case "слева" -> " " + TestEnvironment.KEY;
          case "справа" -> TestEnvironment.KEY + " ";
          default -> " " + TestEnvironment.KEY + " ";
        };
    api.send(token, "LOGIN", key).ok();
    verifyUpstreamRequest("/auth", token, 1);
    clearJournal();
    assertActionAllowed(token);
    assertRequestCount(1);
  }
}
