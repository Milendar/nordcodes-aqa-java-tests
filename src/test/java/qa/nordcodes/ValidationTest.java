package qa.nordcodes;

import io.qameta.allure.*;
import io.qameta.allure.model.Status;
import java.util.*;
import java.util.stream.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

@Epic("API пользователей")
@Feature("Входные данные")
@Tag("contract")
class ValidationTest extends BaseApiTest {
  static Stream<Arguments> invalidTokens() {
    return Stream.of(
            Arguments.of("нет параметра", null),
            Arguments.of("пустое значение", ""),
            Arguments.of("один символ", "A"),
            Arguments.of("31 символ", "A".repeat(31)),
            Arguments.of("33 символа", "A".repeat(33)),
            Arguments.of("4096 символов", "A".repeat(4096)),
            Arguments.of("строчные буквы", "a".repeat(32)),
            Arguments.of("кириллица", "А".repeat(32)),
            Arguments.of("арабские цифры", "١".repeat(32)),
            Arguments.of("полноширинные цифры", "１".repeat(32)),
            Arguments.of("пробел внутри", "A".repeat(15) + " " + "A".repeat(16)),
            Arguments.of("пробел в начале", " " + "A".repeat(31)),
            Arguments.of("пробел в конце", "A".repeat(31) + " "),
            Arguments.of("пробел после 32 символов", "A".repeat(32) + " "),
            Arguments.of("плюс", "A".repeat(31) + "+"),
            Arguments.of("слеш", "A".repeat(31) + "/"),
            Arguments.of("двоеточие", "A".repeat(31) + ":"),
            Arguments.of("символ @", "A".repeat(31) + "@"),
            Arguments.of("скобка", "A".repeat(31) + "["),
            Arguments.of("перенос строки", "A".repeat(31) + "\n"),
            Arguments.of("табуляция", "A".repeat(31) + "\t"),
            Arguments.of("одна строчная буква в начале", "a" + "A".repeat(31)),
            Arguments.of("одна строчная буква внутри", "A".repeat(15) + "a" + "A".repeat(16)),
            Arguments.of("одна строчная буква в конце", "A".repeat(31) + "a"),
            Arguments.of("LF после 32 символов", "A".repeat(32) + "\n"),
            Arguments.of("CR после 32 символов", "A".repeat(32) + "\r"),
            Arguments.of("CRLF после 32 символов", "A".repeat(32) + "\r\n"))
        .flatMap(
            a ->
                Stream.of("LOGIN", "ACTION", "LOGOUT")
                    .map(action -> Arguments.of(a.get()[0], a.get()[1], action)));
  }

  @ParameterizedTest(name = "Недопустимый токен: {0}, операция {2}")
  @MethodSource("invalidTokens")
  void rejectsInvalidToken(String label, String value, String action) throws Exception {
    Allure.parameter("Токен", label);
    stubSuccessfulResponses();
    api.send(value, action).error();
    noCalls();
  }

  static Stream<Arguments> validTokens() {
    Stream<Arguments> characters =
        "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
            .chars()
            .mapToObj(c -> Arguments.of("символ " + (char) c, String.valueOf((char) c).repeat(32)));
    return Stream.concat(
        characters,
        Stream.of(
            Arguments.of("буквы и цифры", "ABCDEFGHIJKLMNOPQRSTUVWXYZ012345"),
            Arguments.of("чередование букв и цифр", "A0B1C2D3E4F5A6B7C8D9E0F1A2B3C4D5")));
  }

  @ParameterizedTest(name = "Вход и действие с допустимым токеном: {0}")
  @MethodSource("validTokens")
  void acceptsValidToken(String label, String value) throws Exception {
    Allure.parameter("Токен", label);
    stubSuccessfulResponses();
    try {
      login(value);
    } catch (AssertionError | Exception failure) {
      Allure.step("Действие не проверено: вход не подтверждён", Status.SKIPPED);
      Allure.step("Выход не проверен: вход не подтверждён", Status.SKIPPED);
      throw failure;
    }
    api.send(value, "ACTION").ok();
    verifyUpstreamRequest("/doAction", value, 1);
    assertRequestCount(2);
    api.send(value, "LOGOUT").ok();
    clearJournal();
    assertActionDenied(value);
    noCalls();
  }

  static Stream<Arguments> invalidActions() {
    return Stream.of(
            Arguments.of("нет параметра", null),
            Arguments.of("пустое значение", ""),
            Arguments.of("неизвестное действие", "DELETE"),
            Arguments.of("нижний регистр", "login"),
            Arguments.of("смешанный регистр", "Login"),
            Arguments.of("пробел", " "),
            Arguments.of("цифра", "1"))
        .flatMap(
            a ->
                Stream.of(false, true).map(active -> Arguments.of(a.get()[0], a.get()[1], active)));
  }

  @ParameterizedTest(name = "Недопустимое действие: {0}, открытый сеанс {2}")
  @MethodSource("invalidActions")
  void rejectsInvalidAction(String label, String value, boolean active) throws Exception {
    stubSuccessfulResponses();
    String token = token();
    if (active) login(token);
    clearJournal();
    api.send(token, value).error();
    noCalls();
    if (active) assertActionAllowed(token);
    else assertActionDenied(token);
    assertRequestCount(active ? 1 : 0);
  }

  static Stream<Arguments> changedTokens() {
    return Stream.of(
            "пробел в начале",
            "пробел в конце",
            "строчная буква в начале",
            "строчная буква внутри",
            "строчная буква в конце",
            "LF в конце",
            "CRLF в конце",
            "31 символ",
            "33 символа")
        .flatMap(
            label ->
                Stream.of("LOGIN", "ACTION", "LOGOUT").map(action -> Arguments.of(label, action)));
  }

  @ParameterizedTest(name = "Изменённый токен действующего сеанса: {0}, {1}")
  @MethodSource("changedTokens")
  void changedTokenDoesNotAccessSession(String variant, String action) throws Exception {
    stubSuccessfulResponses();
    String token = "A" + token().substring(1);
    login(token);
    clearJournal();
    String changed =
        switch (variant) {
          case "пробел в начале" -> " " + token;
          case "пробел в конце" -> token + " ";
          case "строчная буква в начале" -> "a" + token.substring(1);
          case "строчная буква внутри" -> token.substring(0, 16) + "a" + token.substring(17);
          case "строчная буква в конце" -> token.substring(0, 31) + "a";
          case "LF в конце" -> token + "\n";
          case "CRLF в конце" -> token + "\r\n";
          case "31 символ" -> token.substring(0, 31);
          case "33 символа" -> token + "A";
          default -> throw new IllegalArgumentException(variant);
        };
    api.send(changed, action).error();
    noCalls();
    assertActionAllowed(token);
    assertRequestCount(1);
    api.send(token, "LOGOUT").ok();
    clearJournal();
    assertActionDenied(token);
    noCalls();
  }

  static Stream<Arguments> invalidKeys() {
    return Stream.of("LOGIN", "ACTION", "LOGOUT")
        .flatMap(
            action ->
                Stream.of(
                    Arguments.of(action, "нет заголовка", null),
                    Arguments.of(action, "пустой ключ", ""),
                    Arguments.of(action, "другой ключ", "wrong-key"),
                    Arguments.of(
                        action,
                        "неполный ключ",
                        TestEnvironment.KEY.substring(0, TestEnvironment.KEY.length() - 1)),
                    Arguments.of(action, "дополнительный символ", TestEnvironment.KEY + "X"),
                    Arguments.of(
                        action, "другой регистр", TestEnvironment.KEY.toUpperCase(Locale.ROOT))));
  }

  @ParameterizedTest(name = "Доступ к операции {0}: {1}")
  @MethodSource("invalidKeys")
  void requiresKeyForEveryOperation(String action, String label, String key) throws Exception {
    stubSuccessfulResponses();
    String token = token();
    if (!action.equals("LOGIN")) login(token);
    clearJournal();
    api.send(token, action, key).error();
    noCalls();
    if (action.equals("LOGIN")) {
      assertActionDenied(token);
      noCalls();
    } else {
      assertActionAllowed(token);
      assertRequestCount(1);
    }
  }

  @Test
  @DisplayName("Пустой запрос отклоняется без изменения сеанса")
  void rejectsEmptyForm() throws Exception {
    stubSuccessfulResponses();
    api.send(null, null).error();
    noCalls();
  }
}
