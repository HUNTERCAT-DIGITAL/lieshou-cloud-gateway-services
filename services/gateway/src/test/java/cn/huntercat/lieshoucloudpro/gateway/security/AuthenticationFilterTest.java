package cn.huntercat.lieshoucloudpro.gateway.security;

import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import cn.huntercat.lieshou.framework.jwt.JwtSupport;
import reactor.core.publisher.Mono;

/**
 * 网关鉴权白名单边界（2026-09-14 事故修复的防回归测试）.
 *
 * <p>背景：{@code /api/users/auth/**} 曾被列入白名单，于是「取鉴权视图（含 BCrypt passwordHash）」 这几个 service-to-service
 * 端点**公网无凭据可读**（实测拿到 admin 的 passwordHash）。 实际上 auth-service 调 user-service 走 Nacos
 * 服务发现直连、不经网关，白名单纯属多余。
 *
 * <p>本测试钉死两侧边界：这些端点在网关必须要求 JWT；真正的公开端点（/api/auth/**、health 探针） 仍须放行，避免"一刀切"堵死登录链路。
 */
class AuthenticationFilterTest {

  private final JwtSupport jwt = mock(JwtSupport.class);
  private final AuthenticationFilter filter = new AuthenticationFilter(jwt);
  private final GatewayFilterChain chain = mock(GatewayFilterChain.class);

  private MockServerWebExchange exchange(MockServerHttpRequest.BaseBuilder<?> builder) {
    return MockServerWebExchange.from(builder);
  }

  /** 放行分支要能完成；用 lenient 以便“必须拦下”的用例不产生 unnecessary-stubbing */
  @BeforeEach
  void stubChainPassThrough() {
    lenient().when(chain.filter(any())).thenReturn(Mono.empty());
  }

  /** 手机号鉴权视图（含 passwordHash）——匿名必须 401，且不得进入下游链路 */
  @Test
  void usersAuthByTenantPhone_anonymous_isRejected() {
    MockServerWebExchange ex =
        exchange(MockServerHttpRequest.get("/api/users/auth/by-tenant-phone/default/18607097289"));

    filter.filter(ex, chain).block();

    assertThat(ex.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(ex.getResponse().getBodyAsString().block()).contains("MISSING_BEARER_TOKEN");
    verifyNoInteractions(chain);
  }

  /** 用户名鉴权视图（含 passwordHash）——同样不得匿名可读 */
  @Test
  void usersAuthByTenantUsername_anonymous_isRejected() {
    MockServerWebExchange ex =
        exchange(MockServerHttpRequest.get("/api/users/auth/by-tenant/default/admin"));

    filter.filter(ex, chain).block();

    assertThat(ex.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    verifyNoInteractions(chain);
  }

  /** 公开认证端点仍放行：登录页发码/登录/注册不能被这轮收紧误伤 */
  @Test
  void publicAuthEndpoints_areStillWhitelisted() {
    MockServerWebExchange sendCode = exchange(MockServerHttpRequest.post("/api/auth/send-code"));
    filter.filter(sendCode, chain).block();
    verify(chain).filter(sendCode);

    MockServerWebExchange loginCode = exchange(MockServerHttpRequest.post("/api/auth/login/code"));
    filter.filter(loginCode, chain).block();
    verify(chain).filter(loginCode);
  }

  /** 下游 health 探针仍放行（容器/compose 依赖它） */
  @Test
  void userHealthProbe_staysWhitelisted() {
    MockServerWebExchange ex = exchange(MockServerHttpRequest.get("/api/users/_health"));
    filter.filter(ex, chain).block();

    verify(chain).filter(ex);
  }
}
