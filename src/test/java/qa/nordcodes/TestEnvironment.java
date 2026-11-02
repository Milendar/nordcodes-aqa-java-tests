package qa.nordcodes;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.qameta.allure.Allure;
import java.io.IOException;
import java.net.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.extension.*;

final class TestEnvironment
    implements BeforeAllCallback, AfterAllCallback, BeforeEachCallback, AfterEachCallback {
  private WireMockServer mock;
  private Process app;
  private Path log;
  private int port;
  final ResponseGate gate = new ResponseGate();
  static final String KEY = "aqa-local-test-key";

  @Override
  public void beforeAll(ExtensionContext context) throws Exception {
    Path jar = Path.of(System.getProperty("sut.jar")).toAbsolutePath();
    if (!Files.isRegularFile(jar))
      throw new IllegalStateException(
          "Нет JAR приложения: " + jar + ". Передайте -Dsut.jar=<path>");
    try {
      mock = new WireMockServer(options().port(freePort()).extensions(gate));
      mock.start();
      startApplication(jar, context.getRequiredTestClass().getSimpleName());
    } catch (Exception e) {
      close();
      throw e;
    }
  }

  private void startApplication(Path jar, String testClass) throws Exception {
    port = freePort();
    Path logs = Path.of("target", "app-logs");
    Files.createDirectories(logs);
    log = logs.resolve(testClass + ".log");
    String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
    String java = Path.of(System.getProperty("java.home"), "bin", executable).toString();
    app =
        new ProcessBuilder(
                java,
                "-Dsecret=" + KEY,
                "-Dmock=" + mock.baseUrl() + "/",
                "-Dserver.port=" + port,
                "-jar",
                jar.toString())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start();
    long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
    while (System.nanoTime() < deadline && app.isAlive()) {
      try (Socket socket = new Socket()) {
        socket.connect(new InetSocketAddress("127.0.0.1", port), 200);
        return;
      } catch (IOException notReady) {
        Thread.sleep(100);
      }
    }
    throw new IllegalStateException("Приложение не запустилось:\n" + Files.readString(log));
  }

  @Override
  public void beforeEach(ExtensionContext context) {
    gate.reset();
    mock.resetAll();
  }

  @Override
  public void afterEach(ExtensionContext context) throws Exception {
    gate.release();
    Allure.addAttachment(
        "Внешние вызовы текущего сценария",
        "text/plain",
        mock.getAllServeEvents().stream()
            .map(
                e ->
                    e.getRequest().getMethod()
                        + " "
                        + e.getRequest().getUrl()
                        + "\n"
                        + e.getRequest().getHeaders()
                        + e.getRequest().getBodyAsString())
            .collect(java.util.stream.Collectors.joining("\n\n")),
        ".txt");
    if (context.getExecutionException().isPresent() && log != null) {
      Allure.addAttachment("Лог приложения при сбое", "text/plain", Files.readString(log), ".log");
    }
    org.junit.jupiter.api.Assertions.assertTrue(
        mock.findAllUnmatchedRequests().isEmpty(),
        "Приложение отправило запрос без подходящего стаба: " + mock.findAllUnmatchedRequests());
  }

  @Override
  public void afterAll(ExtensionContext context) throws Exception {
    close();
  }

  private void close() throws InterruptedException {
    try {
      gate.release();
      stopApplication();
    } finally {
      if (mock != null) mock.stop();
    }
  }

  private void stopApplication() throws InterruptedException {
    if (app != null && app.isAlive()) {
      app.destroy();
      if (!app.waitFor(8, TimeUnit.SECONDS)) {
        app.destroyForcibly();
        app.waitFor(5, TimeUnit.SECONDS);
      }
    }
  }

  WireMockServer mock() {
    return mock;
  }

  String baseUrl() {
    return "http://127.0.0.1:" + port;
  }

  private static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    }
  }
}
