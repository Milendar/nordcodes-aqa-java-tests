package qa.nordcodes;

import static org.junit.jupiter.api.Assertions.*;

import io.qameta.allure.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Epic("API пользователей")
@Feature("Одновременные операции")
@Tag("concurrency")
class ConcurrentSessionTest extends BaseApiTest {
  @ParameterizedTest(name = "Действие во время входа, внешний ответ HTTP {0}")
  @ValueSource(ints = {200, 503})
  void actionCannotRunBeforeSuccessfulAuthentication(int status) throws Exception {
    stubSuccessfulResponses();
    String token = token();
    stub("/auth", status, "");
    ENV.gate.arm("/auth", token);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<ApiClient.Reply> login = pool.submit(() -> api.send(token, "LOGIN"));
      assertTrue(ENV.gate.awaitRequest(), "Запрос авторизации не получен");
      CountDownLatch started = new CountDownLatch(1);
      Future<ApiClient.Reply> action =
          pool.submit(() -> api.sendObserved(token, "ACTION", started));
      awaitSent(started);
      assertFalse(login.isDone(), "Вход должен оставаться в ожидании внешнего ответа");
      ApiClient.Reply early = completedWithin(action, 200);
      if (early != null) early.error();
      verifyUpstreamRequest("/doAction", token, 0);
      ENV.gate.release();
      if (status == 200) login.get(5, TimeUnit.SECONDS).ok();
      else login.get(5, TimeUnit.SECONDS).error();
      boolean accepted = action.get(5, TimeUnit.SECONDS).succeeded();
      if (status != 200) assertFalse(accepted, "Отказ во входе не должен разрешать действие");
      verifyUpstreamRequest("/auth", token, 1);
      verifyUpstreamRequest("/doAction", token, accepted ? 1 : 0);
      assertRequestCount(accepted ? 2 : 1);
      clearJournal();
      if (status == 200) assertActionAllowed(token);
      else assertActionDenied(token);
      assertRequestCount(status == 200 ? 1 : 0);
    } finally {
      finish(pool);
    }
  }

  @Test
  @DisplayName("Медленный вход одного пользователя не смешивает сеансы разных пользователей")
  void delayedLoginIsIsolatedFromOtherUser() throws Exception {
    stubSuccessfulResponses();
    String a = token(), b = token();
    login(b);
    clearJournal();
    ENV.gate.arm("/auth", a);
    ExecutorService pool = Executors.newFixedThreadPool(3);
    try {
      Future<ApiClient.Reply> login = pool.submit(() -> api.send(a, "LOGIN"));
      assertTrue(ENV.gate.awaitRequest());
      Future<ApiClient.Reply> otherAction = pool.submit(() -> api.send(b, "ACTION"));
      Future<ApiClient.Reply> rejected = pool.submit(() -> api.send(token(), "LOGIN", "wrong"));
      otherAction.get(3, TimeUnit.SECONDS).ok();
      rejected.get(3, TimeUnit.SECONDS).error();
      verifyUpstreamRequest("/doAction", b, 1);
      verifyUpstreamRequest("/doAction", a, 0);
      ENV.gate.release();
      login.get(5, TimeUnit.SECONDS).ok();
      verifyUpstreamRequest("/auth", a, 1);
      assertRequestCount(2);
    } finally {
      finish(pool);
    }
  }

  @Test
  @DisplayName("После выхода новые действия недоступны при ранее начатой операции")
  void logoutBlocksNewActionsAfterInflightAction() throws Exception {
    stubSuccessfulResponses();
    String token = token();
    login(token);
    clearJournal();
    ENV.gate.arm("/doAction", token);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<ApiClient.Reply> action = pool.submit(() -> api.send(token, "ACTION"));
      assertTrue(ENV.gate.awaitRequest());
      CountDownLatch logoutSent = new CountDownLatch(1);
      Future<ApiClient.Reply> logout =
          pool.submit(() -> api.sendObserved(token, "LOGOUT", logoutSent));
      awaitSent(logoutSent);
      assertFalse(action.isDone(), "Действие должно оставаться в ожидании внешнего ответа");
      ApiClient.Reply early = completedWithin(logout, 200);
      if (early != null) early.ok();
      ENV.gate.release();
      action.get(5, TimeUnit.SECONDS).succeeded();
      logout.get(5, TimeUnit.SECONDS).ok();
      verifyUpstreamRequest("/doAction", token, 1);
      assertRequestCount(1);
      clearJournal();
      assertActionDenied(token);
      noCalls();
    } finally {
      finish(pool);
    }
  }

  @Test
  @DisplayName("Одновременные действия сохраняют один сеанс пользователя")
  void parallelActionsUseSameSession() throws Exception {
    stubSuccessfulResponses();
    String token = token();
    login(token);
    clearJournal();
    ENV.gate.arm("/doAction", token);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<ApiClient.Reply> first = pool.submit(() -> api.send(token, "ACTION"));
      assertTrue(ENV.gate.awaitRequest(), "Первое действие не достигло внешнего сервиса");
      CountDownLatch secondSent = new CountDownLatch(1);
      Future<ApiClient.Reply> second =
          pool.submit(() -> api.sendObserved(token, "ACTION", secondSent));
      awaitSent(secondSent);
      assertFalse(first.isDone(), "Первое действие должно оставаться в работе");
      ENV.gate.release();
      first.get(5, TimeUnit.SECONDS).ok();
      second.get(5, TimeUnit.SECONDS).ok();
      verifyUpstreamRequest("/doAction", token, 2);
      assertRequestCount(2);
      clearJournal();
      assertActionAllowed(token);
      assertRequestCount(1);
    } finally {
      finish(pool);
    }
  }

  @Test
  @DisplayName("Одновременный вход с одним токеном сохраняет согласованное состояние")
  void parallelLoginsLeaveUsableSession() throws Exception {
    stubSuccessfulResponses();
    String token = token();
    ENV.gate.arm("/auth", token);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<ApiClient.Reply> first = pool.submit(() -> api.send(token, "LOGIN"));
      assertTrue(ENV.gate.awaitRequest());
      CountDownLatch secondSent = new CountDownLatch(1);
      Future<ApiClient.Reply> second =
          pool.submit(() -> api.sendObserved(token, "LOGIN", secondSent));
      awaitSent(secondSent);
      assertFalse(first.isDone(), "Первый вход должен оставаться в работе");
      ENV.gate.release();
      boolean a = first.get(5, TimeUnit.SECONDS).succeeded(),
          b = second.get(5, TimeUnit.SECONDS).succeeded();
      assertTrue(a || b, "Хотя бы один вход должен завершиться успешно");
      int count = requests("/auth").size();
      assertTrue(count >= 1 && count <= 2);
      verifyUpstreamRequest("/auth", token, count);
      assertRequestCount(count);
      clearJournal();
      assertActionAllowed(token);
      assertRequestCount(1);
    } finally {
      finish(pool);
    }
  }

  private ApiClient.Reply completedWithin(Future<ApiClient.Reply> request, long millis)
      throws Exception {
    try {
      return request.get(millis, TimeUnit.MILLISECONDS);
    } catch (TimeoutException pending) {
      return null;
    }
  }

  private void awaitSent(CountDownLatch sent) throws InterruptedException {
    assertTrue(
        sent.await(3, TimeUnit.SECONDS), "Второй HTTP-запрос не отправлен до освобождения первого");
  }

  private void finish(ExecutorService pool) throws InterruptedException {
    ENV.gate.release();
    pool.shutdownNow();
    assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), "Потоки теста не завершились");
  }
}
