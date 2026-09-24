package interview.homegrown.common.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SseStreamTest {
  @Test
  @DisplayName("handler 线程能看到调用线程的登录上下文与请求属性（LLM key 解析依赖，丢失会误报未配置 API Key）")
  void propagatesSecurityAndRequestContext() throws Exception {
    var latch = new CountDownLatch(1);
    var seenPrincipal = new AtomicReference<String>();
    var seenAttrs = new AtomicBoolean(false);
    var attrs = new ServletRequestAttributes(new MockHttpServletRequest());
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken("u42", "n/a", List.of()));
    RequestContextHolder.setRequestAttributes(attrs);
    try {
      SseStream.start(sink -> {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        seenPrincipal.set(auth == null ? null : String.valueOf(auth.getPrincipal()));
        seenAttrs.set(RequestContextHolder.getRequestAttributes() == attrs);
        latch.countDown();
      });
      assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(seenPrincipal.get()).isEqualTo("u42");
      assertThat(seenAttrs.get()).isTrue();
    } finally {
      SecurityContextHolder.clearContext();
      RequestContextHolder.resetRequestAttributes();
    }
  }
}
