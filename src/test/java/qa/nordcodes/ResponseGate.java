package qa.nordcodes;

import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformerV2;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import java.util.List;
import java.util.concurrent.*;

final class ResponseGate implements ResponseDefinitionTransformerV2 {
  private volatile HeldResponse held;

  record HeldResponse(String path, String token, CountDownLatch entered, CountDownLatch release) {}

  void arm(String path, String token) {
    release();
    held = new HeldResponse(path, token, new CountDownLatch(1), new CountDownLatch(1));
  }

  boolean awaitRequest() throws InterruptedException {
    return held.entered().await(5, TimeUnit.SECONDS);
  }

  void release() {
    if (held != null) held.release().countDown();
  }

  void reset() {
    release();
    held = null;
  }

  @Override
  public String getName() {
    return "controlled-response";
  }

  @Override
  public ResponseDefinition transform(ServeEvent event) {
    HeldResponse current = held;
    if (current != null
        && event.getRequest().getUrl().equals(current.path())
        && event.getRequest().formParameter("token").values().equals(List.of(current.token()))) {
      current.entered().countDown();
      try {
        if (!current.release().await(12, TimeUnit.SECONDS))
          throw new IllegalStateException("Тест не освободил внешний ответ");
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Ожидание ответа прервано", e);
      }
    }
    return event.getResponseDefinition();
  }
}
