package qa.nordcodes;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

import com.github.tomakehurst.wiremock.http.Fault;
import io.qameta.allure.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

@Epic("API пользователей")
@Feature("Внешний сервис")
class UpstreamTest extends BaseApiTest {
  static Stream<Arguments> responseBodies() {
    String[][] bodies = {
      {"пустое тело", ""},
      {"обычный текст", "not JSON"},
      {"JSON с результатом OK", "{\"result\":\"OK\"}"},
      {"JSON с результатом ERROR", "{\"result\":\"ERROR\"}"},
      {"HTML", "<html>response</html>"},
      {"Unicode", "Ответ сервиса: 日本語 🙂"},
      {"64 КиБ текста", "x".repeat(65536)}
    };
    List<Arguments> cases = new ArrayList<>();
    for (String action : List.of("LOGIN", "ACTION")) {
      for (int status : new int[] {200, 503}) {
        for (String[] body : bodies) {
          cases.add(
              Arguments.of(action, status, body[0], body[1].getBytes(StandardCharsets.UTF_8)));
        }
        cases.add(
            Arguments.of(
                action,
                status,
                "бинарные данные",
                new byte[] {0, 1, 127, (byte) 128, (byte) 255, (byte) 192, (byte) 175}));
      }
    }
    return cases.stream();
  }

  @Tag("contract")
  @ParameterizedTest(name = "Тело внешнего ответа: {0}, HTTP {1}, {2}")
  @MethodSource("responseBodies")
  @Description(
      "Результат зависит от внешнего HTTP-кода, а не от содержимого тела. HTTP 200 используется как"
          + " успех, 503 как отказ.")
  void ignoresUpstreamBody(String action, int status, String label, byte[] body) throws Exception {
    Allure.parameter("Тело внешнего ответа", label);
    stubSuccessfulResponses();
    String token = token();
    if (action.equals("ACTION")) {
      login(token);
      clearJournal();
    }
    String path = action.equals("LOGIN") ? "/auth" : "/doAction";
    ENV.mock()
        .stubFor(post(urlEqualTo(path)).willReturn(aResponse().withStatus(status).withBody(body)));
    ApiClient.Reply reply = api.send(token, action);
    if (status == 200) reply.ok();
    else reply.error();
    verifyUpstreamRequest(path, token, 1);
    assertRequestCount(1);
    if (action.equals("LOGIN")) {
      clearJournal();
      if (status == 200) assertActionAllowed(token);
      else assertActionDenied(token);
    }
  }

  static Stream<Arguments> isolatedFailures() {
    return Stream.of("LOGIN", "ACTION")
        .flatMap(
            action ->
                Stream.of("HTTP 503", "обрыв соединения")
                    .map(failure -> Arguments.of(action, failure)));
  }

  @Tag("resilience")
  @ParameterizedTest(name = "Отказ {0} сохраняет сеанс другого пользователя: {1}")
  @MethodSource("isolatedFailures")
  void failureDoesNotAffectOtherSession(String action, String failure) throws Exception {
    stubSuccessfulResponses();
    String first = token(), second = token();
    login(second);
    if (action.equals("ACTION")) login(first);
    clearJournal();
    String path = action.equals("LOGIN") ? "/auth" : "/doAction";
    var response = aResponse();
    if (failure.equals("HTTP 503")) response.withStatus(503);
    else response.withFault(Fault.EMPTY_RESPONSE);
    ENV.mock().stubFor(post(urlEqualTo(path)).willReturn(response));
    api.send(first, action).error();
    verifyUpstreamRequest(path, first, 1);
    assertRequestCount(1);
    clearJournal();
    stubSuccessfulResponses();
    assertActionAllowed(second);
    assertRequestCount(1);
    clearJournal();
    if (action.equals("LOGIN")) {
      assertActionDenied(first);
      noCalls();
    } else {
      api.send(first, "LOGOUT").succeeded();
      assertActionDenied(first);
      noCalls();
    }
    assertActionAllowed(second);
    assertRequestCount(1);
    api.send(second, "LOGOUT").ok();
    clearJournal();
    assertActionDenied(second);
    noCalls();
  }

  static Stream<Arguments> failures() {
    return Stream.of("LOGIN", "ACTION")
        .flatMap(
            action ->
                Stream.of(400, 401, 403, 404, 429, 500, 502, 503, 504)
                    .map(status -> Arguments.of(action, status)));
  }

  @Tag("resilience")
  @ParameterizedTest(name = "Внешний отказ и восстановление входа: {0}, HTTP {1}")
  @MethodSource("failures")
  @Description(
      "Проверяются отказ операции и восстановление через новый вход. Состояние прежнего сеанса"
          + " после ошибки не задано и фиксируется только как наблюдение.")
  void handlesUpstreamFailureAndRecovery(String action, int status) throws Exception {
    stubSuccessfulResponses();
    String token = token();
    if (action.equals("ACTION")) {
      login(token);
      clearJournal();
    }
    String path = action.equals("LOGIN") ? "/auth" : "/doAction";
    stub(path, status, "unavailable");
    api.send(token, action).error();
    verifyUpstreamRequest(path, token, 1);
    assertRequestCount(1);
    clearJournal();
    if (action.equals("LOGIN")) {
      assertActionDenied(token);
      noCalls();
      stub("/auth", 200, "");
      login(token);
      clearJournal();
      assertActionAllowed(token);
      assertRequestCount(1);
    } else {
      stub("/doAction", 200, "");
      boolean active = api.send(token, "ACTION").succeeded();
      Allure.parameter("Наблюдение: сеанс после ошибки", active ? "сохранён" : "завершён");
      verifyUpstreamRequest("/doAction", token, active ? 1 : 0);
      assertRequestCount(active ? 1 : 0);
      if (active) api.send(token, "LOGOUT").ok();
      else api.send(token, "LOGOUT").succeeded();
      clearJournal();
      assertActionDenied(token);
      noCalls();
      login(token);
      clearJournal();
      assertActionAllowed(token);
      assertRequestCount(1);
    }
  }

  static Stream<Arguments> alternativeStatuses() {
    return Stream.of("LOGIN", "ACTION")
        .flatMap(
            action ->
                Stream.of(201, 202, 204, 301, 302, 307, 308)
                    .map(status -> Arguments.of(action, status)));
  }

  @Tag("behavior")
  @ParameterizedTest(name = "Согласованность результата: {0}, внешний HTTP {1}")
  @MethodSource("alternativeStatuses")
  @Description(
      "Успешный диапазон upstream не указан. Проверяются независимость от тела и согласованность"
          + " результата с состоянием сеанса.")
  void keepsStatusHandlingConsistent(String action, int status) throws Exception {
    stubSuccessfulResponses();
    String path = action.equals("LOGIN") ? "/auth" : "/doAction";
    List<Boolean> outcomes = new ArrayList<>();
    for (String body : new String[] {"", "{\"result\":\"OK\"}", "{\"result\":\"ERROR\"}"}) {
      String token = token();
      if (action.equals("ACTION")) {
        login(token);
        clearJournal();
      }
      stub(path, status, body);
      boolean success = api.send(token, action).succeeded();
      outcomes.add(success);
      verifyUpstreamRequest(path, token, 1);
      assertRequestCount(1);
      clearJournal();
      if (action.equals("LOGIN")) {
        if (success) assertActionAllowed(token);
        else assertActionDenied(token);
        clearJournal();
      }
    }
    assertEquals(1, outcomes.stream().distinct().count(), "Тело ответа не должно менять результат");
  }
}
