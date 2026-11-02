package qa.nordcodes;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

import com.github.tomakehurst.wiremock.http.Fault;
import io.qameta.allure.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

@Epic("API пользователей")
@Feature("Отказы и восстановление связи")
@Tag("resilience")
class ResilienceTest extends BaseApiTest {
  static Stream<Arguments> faults() {
    return Stream.of("LOGIN", "ACTION")
        .flatMap(
            action ->
                Stream.of(
                        Fault.EMPTY_RESPONSE,
                        Fault.RANDOM_DATA_THEN_CLOSE,
                        Fault.MALFORMED_RESPONSE_CHUNK)
                    .map(fault -> Arguments.of(action, fault)));
  }

  @ParameterizedTest(name = "Сбой соединения: {0}, {1}")
  @MethodSource("faults")
  void recoversAfterTransportFault(String action, Fault fault) throws Exception {
    stubSuccessfulResponses();
    String token = token();
    if (action.equals("ACTION")) {
      login(token);
      clearJournal();
    }
    String path = action.equals("LOGIN") ? "/auth" : "/doAction";
    ENV.mock().stubFor(post(urlEqualTo(path)).willReturn(aResponse().withFault(fault)));
    boolean accepted = api.send(token, action).succeeded();
    // MALFORMED_RESPONSE_CHUNK содержит HTTP 200: клиент может принять заголовки до ошибки тела.
    if (fault != Fault.MALFORMED_RESPONSE_CHUNK)
      assertFalse(accepted, "Соединение без HTTP-ответа не должно подтверждать операцию");
    verifyUpstreamRequest(path, token, 1);
    assertRequestCount(1);
    clearJournal();
    if (action.equals("LOGIN")) {
      if (accepted) {
        assertActionAllowed(token);
        api.send(token, "LOGOUT").ok();
        clearJournal();
      } else {
        assertActionDenied(token);
        noCalls();
      }
    } else {
      stub("/doAction", 200, "");
      boolean active = api.send(token, "ACTION").succeeded();
      Allure.parameter("Наблюдение: сеанс после сетевой ошибки", active ? "сохранён" : "завершён");
      verifyUpstreamRequest("/doAction", token, active ? 1 : 0);
      assertRequestCount(active ? 1 : 0);
      if (active) api.send(token, "LOGOUT").ok();
      else api.send(token, "LOGOUT").succeeded();
      clearJournal();
      assertActionDenied(token);
      noCalls();
    }
    stubSuccessfulResponses();
    login(token);
    clearJournal();
    assertActionAllowed(token);
    assertRequestCount(1);
  }

  @ParameterizedTest(name = "Недоступность внешнего сервиса при {0}")
  @ValueSource(strings = {"LOGIN", "ACTION"})
  void recoversAfterConnectionRefusal(String action) throws Exception {
    stubSuccessfulResponses();
    String token = token();
    if (action.equals("ACTION")) login(token);
    clearJournal();
    ApiClient.Reply reply;
    ENV.mock().stop();
    try {
      reply = api.send(token, action);
    } finally {
      ENV.mock().start();
    }
    reply.error();
    noCalls();
    stubSuccessfulResponses();
    if (action.equals("LOGIN")) {
      assertActionDenied(token);
      noCalls();
    } else {
      api.send(token, "LOGOUT").succeeded();
      clearJournal();
      assertActionDenied(token);
      noCalls();
    }
    login(token);
    clearJournal();
    assertActionAllowed(token);
    assertRequestCount(1);
  }

  @Test
  @DisplayName("Задержанный ответ внешнего сервиса сохраняет корректность входа")
  void handlesDelayedResponse() throws Exception {
    stubSuccessfulResponses();
    String token = token();
    ENV.mock()
        .stubFor(
            post(urlEqualTo("/auth")).willReturn(aResponse().withStatus(200).withFixedDelay(500)));
    login(token);
    clearJournal();
    assertActionAllowed(token);
    assertRequestCount(1);
  }

  @Test
  @Tag("observation")
  @DisplayName("После прекращения ожидания клиентом сервис остаётся доступен")
  @Description(
      "Лимит относится к тестовому клиенту, а не к SLA приложения. Проверяется восстановление после"
          + " позднего внешнего ответа.")
  void handlesClientTimeoutAndLateResponse() throws Exception {
    stubSuccessfulResponses();
    String token = token();
    ENV.gate.arm("/auth", token);
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Future<?> pending =
          pool.submit(
              () -> {
                api.expectClientTimeout(token, Duration.ofSeconds(1));
                return null;
              });
      assertTrue(ENV.gate.awaitRequest(), "Внешний запрос не получен");
      pending.get(3, TimeUnit.SECONDS);
      ENV.gate.release();
      boolean active = api.send(token, "ACTION").succeeded();
      Allure.parameter("Наблюдение: сеанс после позднего ответа", active ? "создан" : "не создан");
      verifyUpstreamRequest("/auth", token, 1);
      verifyUpstreamRequest("/doAction", token, active ? 1 : 0);
      assertRequestCount(active ? 2 : 1);
      api.send(token, "LOGOUT").succeeded();
      clearJournal();
      assertActionDenied(token);
      noCalls();
      login(token);
      clearJournal();
      assertActionAllowed(token);
      assertRequestCount(1);
    } finally {
      ENV.gate.release();
      pool.shutdownNow();
      assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
    }
  }
}
