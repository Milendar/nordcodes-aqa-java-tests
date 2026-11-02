package qa.nordcodes;

import static org.junit.jupiter.api.Assertions.*;

import io.qameta.allure.*;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

@Epic("API пользователей")
@Feature("Разбор параметров")
@Tag("behavior")
class ParameterHandlingTest extends BaseApiTest {
  @BeforeEach
  void describeParsingPolicy() {
    Allure.description(
        "Для неоднозначного ввода политика выбора не задана. Проверяются отсутствие смешанных"
            + " операций и согласованность ответа с состоянием.");
  }

  static Stream<Arguments> actionPairs() {
    return Stream.of(false, true)
        .flatMap(
            active ->
                Stream.of(
                    Arguments.of(active, "LOGIN", "ACTION"),
                        Arguments.of(active, "ACTION", "LOGIN"),
                    Arguments.of(active, "LOGIN", "LOGOUT"),
                        Arguments.of(active, "LOGOUT", "LOGIN"),
                    Arguments.of(active, "ACTION", "LOGOUT"),
                        Arguments.of(active, "LOGOUT", "ACTION")));
  }

  @ParameterizedTest(name = "Два действия в форме: сеанс {0}, {1} и {2}")
  @MethodSource("actionPairs")
  void duplicateActionsHaveSingleEffect(boolean active, String first, String second)
      throws Exception {
    stubSuccessfulResponses();
    String token = token();
    if (active) login(token);
    clearJournal();
    verifyActionChoice(
        token,
        active,
        "/endpoint",
        "token=" + token + "&action=" + first + "&action=" + second,
        Set.of(first, second));
  }

  @ParameterizedTest(name = "Действия в query и body: сеанс {0}, {1} и {2}")
  @MethodSource("actionPairs")
  void queryAndBodyHaveSingleEffect(boolean active, String query, String body) throws Exception {
    stubSuccessfulResponses();
    String token = token();
    if (active) login(token);
    clearJournal();
    verifyActionChoice(
        token,
        active,
        "/endpoint?action=" + query,
        ApiClient.form(token, body),
        Set.of(query, body));
  }

  private void verifyActionChoice(
      String token, boolean active, String path, String form, Set<String> suppliedActions)
      throws Exception {
    boolean success =
        api.request("POST", path, form, ApiClient.headers(TestEnvironment.KEY)).succeeded();
    int auth = requests("/auth").size(), action = requests("/doAction").size();
    assertTrue(auth + action <= 1, "Один запрос не должен выполнять несколько внешних операций");
    assertRequestCount(auth + action);
    verifyUpstreamRequest("/auth", token, auth);
    verifyUpstreamRequest("/doAction", token, action);
    if (!active) assertEquals(0, action, "Действие недоступно без открытого сеанса");
    boolean remainsActive = active;
    if (!success)
      assertEquals(
          0,
          auth + action,
          "Отклонённый запрос не должен выполнять внешнюю операцию при успешных стабах");
    else if (auth == 1) {
      assertTrue(suppliedActions.contains("LOGIN"), "Выполнен вход, отсутствующий в запросе");
      remainsActive = true;
    } else if (action == 1) {
      assertTrue(suppliedActions.contains("ACTION"), "Выполнено действие, отсутствующее в запросе");
    } else {
      clearJournal();
      boolean stillActive = api.send(token, "ACTION").succeeded();
      verifyUpstreamRequest("/doAction", token, stillActive ? 1 : 0);
      assertRequestCount(stillActive ? 1 : 0);
      assertTrue(
          stillActive
              ? active && suppliedActions.contains("LOGIN")
              : suppliedActions.contains("LOGOUT"),
          "Результат и состояние должны соответствовать одному из переданных действий");
      return;
    }
    clearJournal();
    if (remainsActive) assertActionAllowed(token);
    else assertActionDenied(token);
    assertRequestCount(remainsActive ? 1 : 0);
  }

  static Stream<Arguments> mixedTokens() {
    return Stream.of("LOGIN", "ACTION", "LOGOUT")
        .flatMap(
            action ->
                Stream.of(false, true)
                    .flatMap(
                        invalidFirst ->
                            Stream.of(false, true)
                                .map(query -> Arguments.of(action, invalidFirst, query))));
  }

  @ParameterizedTest(name = "Допустимый и неверный токены: {0}, неверный первым {1}, query {2}")
  @MethodSource("mixedTokens")
  void mixedTokensUseOneValidatedValue(String action, boolean invalidFirst, boolean query)
      throws Exception {
    stubSuccessfulResponses();
    String token = token();
    boolean active = !action.equals("LOGIN");
    if (active) login(token);
    clearJournal();
    String first = invalidFirst ? "invalid" : token;
    String second = invalidFirst ? token : "invalid";
    String path = query ? "/endpoint?token=" + first : "/endpoint";
    String form =
        query
            ? ApiClient.form(second, action)
            : "token=" + first + "&" + ApiClient.form(second, action);
    verifyActionChoice(token, active, path, form, Set.of(action));
  }

  @ParameterizedTest(name = "Повторяющийся token: {0}")
  @ValueSource(strings = {"одинаковые значения", "разные значения"})
  void duplicateTokensDoNotAuthorizeBothUsers(String variant) throws Exception {
    stubSuccessfulResponses();
    String firstToken = token(),
        secondToken = variant.equals("одинаковые значения") ? firstToken : token();
    boolean success =
        api.request(
                "POST",
                "/endpoint",
                "token=" + firstToken + "&token=" + secondToken + "&action=LOGIN",
                ApiClient.headers(TestEnvironment.KEY))
            .succeeded();
    if (!success) {
      noCalls();
      assertActionDenied(firstToken);
      if (!firstToken.equals(secondToken)) assertActionDenied(secondToken);
      noCalls();
      return;
    }
    assertRequestCount(1);
    assertEquals(1, requests("/auth").size());
    var request = requests("/auth").get(0);
    var values = request.formParameters();
    assertEquals(Set.of("token"), values.keySet());
    assertEquals(1, values.get("token").values().size());
    String chosen = values.get("token").firstValue();
    assertTrue(chosen.equals(firstToken) || chosen.equals(secondToken));
    verifyUpstreamRequest("/auth", chosen, 1);
    clearJournal();
    assertActionAllowed(chosen);
    assertRequestCount(1);
    clearJournal();
    if (!firstToken.equals(secondToken)) {
      assertActionDenied(chosen.equals(firstToken) ? secondToken : firstToken);
      noCalls();
    }
  }

  @Test
  @DisplayName("Вход через query не обходит проверку ключа")
  void queryParametersStillRequireKey() throws Exception {
    stubSuccessfulResponses();
    String token = token();
    api.request(
            "POST", "/endpoint?" + ApiClient.form(token, "LOGIN"), "", ApiClient.headers("wrong"))
        .error();
    noCalls();
    assertActionDenied(token);
    noCalls();
  }

  @Test
  @DisplayName("Ответ на параметры в query согласован с состоянием сеанса")
  void queryOnlyResponseMatchesSession() throws Exception {
    stubSuccessfulResponses();
    String token = token();
    boolean success =
        api.request(
                "POST",
                "/endpoint?" + ApiClient.form(token, "LOGIN"),
                "",
                ApiClient.headers(TestEnvironment.KEY))
            .succeeded();
    verifyUpstreamRequest("/auth", token, success ? 1 : 0);
    assertRequestCount(success ? 1 : 0);
    clearJournal();
    if (success) assertActionAllowed(token);
    else assertActionDenied(token);
    assertRequestCount(success ? 1 : 0);
  }

  static Stream<Arguments> duplicateKeys() {
    return Stream.of(
        Arguments.of("два правильных", TestEnvironment.KEY, TestEnvironment.KEY),
        Arguments.of("правильный и неверный", TestEnvironment.KEY, "wrong"),
        Arguments.of("неверный и правильный", "wrong", TestEnvironment.KEY),
        Arguments.of("два неверных", "wrong", "another"));
  }

  @ParameterizedTest(name = "Повторяющийся заголовок ключа: {0}")
  @MethodSource("duplicateKeys")
  void duplicateKeyHeadersCannotBypassAuthentication(String label, String first, String second)
      throws Exception {
    stubSuccessfulResponses();
    String token = token();
    List<ApiClient.Header> headers = ApiClient.headers(first);
    headers.add(new ApiClient.Header("X-Api-Key", second));
    boolean success =
        api.request("POST", "/endpoint", ApiClient.form(token, "LOGIN"), headers).succeeded();
    if (!first.equals(TestEnvironment.KEY) && !second.equals(TestEnvironment.KEY))
      assertFalse(success, "Доступ без правильного ключа запрещён");
    verifyUpstreamRequest("/auth", token, success ? 1 : 0);
    assertRequestCount(success ? 1 : 0);
    clearJournal();
    if (success) assertActionAllowed(token);
    else assertActionDenied(token);
    assertRequestCount(success ? 1 : 0);
  }

  @ParameterizedTest(name = "Нормализация действия: {0}")
  @ValueSource(strings = {" LOGIN", "LOGIN ", " LOGIN "})
  void actionWhitespaceDoesNotMixOperations(String value) throws Exception {
    stubSuccessfulResponses();
    String token = token();
    boolean success = api.send(token, value).succeeded();
    verifyUpstreamRequest("/auth", token, success ? 1 : 0);
    assertRequestCount(success ? 1 : 0);
    clearJournal();
    if (success) assertActionAllowed(token);
    else assertActionDenied(token);
    assertRequestCount(success ? 1 : 0);
  }
}
