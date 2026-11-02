package qa.nordcodes;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.qameta.allure.Allure;
import io.qameta.allure.Feature;
import io.qameta.allure.model.Label;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.RegisterExtension;

abstract class BaseApiTest {
  @RegisterExtension static final TestEnvironment ENV = new TestEnvironment();
  ApiClient api;

  @BeforeEach
  void createClient(TestInfo info) {
    api = new ApiClient(ENV.baseUrl());
    Allure.getLifecycle().updateTestCase(result -> result.setName(info.getDisplayName()));
    Set<String> tags = info.getTags();
    String scope;
    if (tags.contains("observation")) scope = "Наблюдения: правила не заданы";
    else if (tags.contains("protocol")) scope = "Дополнительные HTTP-ожидания";
    else if (tags.contains("behavior")) scope = "Согласованность при неописанной политике";
    else if (tags.contains("resilience")) scope = "Отказы и восстановление";
    else if (tags.contains("concurrency")) scope = "Одновременные запросы";
    else scope = "Требования задания";
    Class<?> testClass = info.getTestClass().orElseThrow();
    Feature feature = testClass.getAnnotation(Feature.class);
    String suite = feature == null ? testClass.getSimpleName() : feature.value();
    Allure.getLifecycle()
        .updateTestCase(
            result -> {
              result
                  .getLabels()
                  .removeIf(
                      label ->
                          Set.of("parentSuite", "suite", "subSuite").contains(label.getName()));
              result.getLabels().add(new Label().setName("parentSuite").setValue(scope));
              result.getLabels().add(new Label().setName("suite").setValue(suite));
            });
  }

  String token() {
    return UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
  }

  void stub(String path, int status, String body) {
    ENV.mock()
        .stubFor(post(urlEqualTo(path)).willReturn(aResponse().withStatus(status).withBody(body)));
  }

  void stubSuccessfulResponses() {
    stub("/auth", 200, "auth response");
    stub("/doAction", 200, "action response");
  }

  void login(String token) throws Exception {
    Allure.step(
        "Войти в систему",
        () -> {
          api.send(token, "LOGIN").ok();
          verifyUpstreamRequest("/auth", token, 1);
        });
  }

  List<LoggedRequest> requests(String path) {
    return ENV.mock().getAllServeEvents().stream()
        .map(e -> e.getRequest())
        .filter(r -> r.getUrl().equals(path))
        .toList();
  }

  void verifyUpstreamRequest(String path, String token, int count) {
    Allure.step(
        "Проверить обращение к внешнему сервису " + path,
        () -> {
          List<LoggedRequest> found =
              requests(path).stream()
                  .filter(request -> request.formParameter("token").containsValue(token))
                  .toList();
          assertEquals(count, found.size(), "Неверное число обращений для токена");
          for (LoggedRequest request : found) {
            assertAll(
                "Формат внешнего запроса",
                () -> assertEquals("POST", request.getMethod().getName()),
                () ->
                    assertEquals(
                        "application/x-www-form-urlencoded",
                        ApiClient.mediaType(request.getHeader("Content-Type"))),
                () -> assertEquals("application/json", request.getHeader("Accept")),
                () ->
                    assertEquals(
                        Set.of("token"),
                        request.formParameters().keySet(),
                        "В форме должен быть только параметр token"),
                () ->
                    assertEquals(
                        List.of(token),
                        request.formParameter("token").values(),
                        "Параметр token должен встречаться один раз"));
          }
        });
  }

  void assertRequestCount(int count) {
    Allure.step(
        "Проверить число внешних обращений",
        () ->
            assertEquals(
                count,
                ENV.mock().getAllServeEvents().size(),
                "Лишние или пропущенные внешние обращения"));
  }

  void noCalls() {
    assertRequestCount(0);
  }

  void clearJournal() {
    ENV.mock().resetRequests();
  }

  void assertActionDenied(String token) throws Exception {
    api.send(token, "ACTION").error();
    verifyUpstreamRequest("/doAction", token, 0);
  }

  void assertActionAllowed(String token) throws Exception {
    api.send(token, "ACTION").ok();
    verifyUpstreamRequest("/doAction", token, 1);
  }
}
