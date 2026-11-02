package qa.nordcodes;

import static org.junit.jupiter.api.Assertions.*;

import io.qameta.allure.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Epic("API пользователей")
@Feature("Сеанс пользователя")
class SessionTest extends BaseApiTest {
  @ParameterizedTest(name = "Независимые сеансы токенов с различием в позиции {0}")
  @ValueSource(ints = {0, 15, 31})
  @Tag("contract")
  void similarTokensHaveIndependentSessions(int position) throws Exception {
    stubSuccessfulResponses();
    String first = token();
    char replacement = first.charAt(position) == 'A' ? 'B' : 'A';
    String second = first.substring(0, position) + replacement + first.substring(position + 1);
    login(first);
    clearJournal();
    assertActionDenied(second);
    noCalls();
    assertActionAllowed(first);
    assertRequestCount(1);
    clearJournal();
    login(second);
    clearJournal();
    assertActionAllowed(first);
    assertActionAllowed(second);
    assertRequestCount(2);
    clearJournal();
    api.send(first, "LOGOUT").ok();
    assertActionDenied(first);
    noCalls();
    assertActionAllowed(second);
    assertRequestCount(1);
    clearJournal();
    api.send(second, "LOGOUT").ok();
    assertActionDenied(second);
    noCalls();
  }

  @Test
  @Tag("contract")
  @DisplayName("После входа пользователь может выполнить действие")
  void loginAllowsAction() throws Exception {
    stubSuccessfulResponses();
    String token = token();
    login(token);
    assertActionAllowed(token);
    assertRequestCount(2);
  }

  @Test
  @Tag("contract")
  @DisplayName("Без входа действие недоступно")
  void actionRequiresLogin() throws Exception {
    stubSuccessfulResponses();
    assertActionDenied(token());
    noCalls();
  }

  @Test
  @Tag("contract")
  @DisplayName("После выхода действие недоступно")
  void logoutRemovesSession() throws Exception {
    stubSuccessfulResponses();
    String token = token();
    login(token);
    api.send(token, "ACTION").ok();
    clearJournal();
    api.send(token, "LOGOUT").ok();
    assertActionDenied(token);
    noCalls();
  }

  @Test
  @Tag("contract")
  @DisplayName("Вход одного пользователя не авторизует другого")
  void loginIsIsolated() throws Exception {
    stubSuccessfulResponses();
    String firstToken = token(), secondToken = token();
    login(firstToken);
    clearJournal();
    assertActionDenied(secondToken);
    noCalls();
    assertActionAllowed(firstToken);
    assertRequestCount(1);
  }

  @Test
  @Tag("contract")
  @DisplayName("Выход одного пользователя сохраняет сеанс другого")
  void logoutIsIsolated() throws Exception {
    stubSuccessfulResponses();
    String firstToken = token(), secondToken = token();
    login(firstToken);
    login(secondToken);
    clearJournal();
    api.send(firstToken, "LOGOUT").ok();
    assertActionDenied(firstToken);
    assertActionAllowed(secondToken);
    assertRequestCount(1);
  }

  @Test
  @Tag("contract")
  @DisplayName("После выхода можно войти повторно")
  void canLoginAgain() throws Exception {
    stubSuccessfulResponses();
    String token = token();
    login(token);
    api.send(token, "LOGOUT").ok();
    clearJournal();
    login(token);
    assertActionAllowed(token);
    assertRequestCount(2);
  }

  @Test
  @Tag("contract")
  @DisplayName("В одном сеансе можно выполнить несколько действий")
  void repeatedActions() throws Exception {
    stubSuccessfulResponses();
    String token = token();
    login(token);
    clearJournal();
    api.send(token, "ACTION").ok();
    api.send(token, "ACTION").ok();
    verifyUpstreamRequest("/doAction", token, 2);
    assertRequestCount(2);
  }

  @Test
  @Tag("behavior")
  @DisplayName("Повторный вход не повреждает существующий сеанс")
  @Description("Ответ на повторный LOGIN не задан; проверяется целостность ранее открытого сеанса.")
  void repeatedLoginPreservesSession() throws Exception {
    stubSuccessfulResponses();
    String token = token();
    login(token);
    clearJournal();
    api.send(token, "LOGIN").succeeded();
    int count = requests("/auth").size();
    assertTrue(count <= 1);
    verifyUpstreamRequest("/auth", token, count);
    assertRequestCount(count);
    clearJournal();
    assertActionAllowed(token);
    assertRequestCount(1);
  }

  @Test
  @Tag("behavior")
  @DisplayName("Выход без открытого сеанса не создаёт авторизацию")
  void logoutWithoutSessionDoesNotAuthorize() throws Exception {
    stubSuccessfulResponses();
    String token = token();
    api.send(token, "LOGOUT").succeeded();
    assertActionDenied(token);
    noCalls();
  }

  @Test
  @Tag("behavior")
  @DisplayName("Повторный выход сохраняет отсутствие сеанса")
  void repeatedLogoutDoesNotAuthorize() throws Exception {
    stubSuccessfulResponses();
    String token = token();
    login(token);
    api.send(token, "LOGOUT").ok();
    clearJournal();
    api.send(token, "LOGOUT").succeeded();
    assertActionDenied(token);
    noCalls();
  }

  @Test
  @Tag("behavior")
  @DisplayName("Повторный вход при отказе внешнего сервиса не повреждает другие сеансы")
  void repeatedLoginFailureIsIsolated() throws Exception {
    stubSuccessfulResponses();
    String firstToken = token(), secondToken = token();
    login(firstToken);
    login(secondToken);
    clearJournal();
    stub("/auth", 503, "unavailable");
    api.send(firstToken, "LOGIN").error();
    int attempts = requests("/auth").size();
    assertTrue(attempts <= 1);
    verifyUpstreamRequest("/auth", firstToken, attempts);
    assertRequestCount(attempts);
    clearJournal();
    assertActionAllowed(secondToken);
    api.send(firstToken, "LOGOUT").succeeded();
    clearJournal();
    assertActionDenied(firstToken);
    noCalls();
  }
}
