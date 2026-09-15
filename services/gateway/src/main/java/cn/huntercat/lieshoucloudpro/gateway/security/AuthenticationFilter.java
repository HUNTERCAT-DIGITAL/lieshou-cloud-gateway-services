package cn.huntercat.lieshoucloudpro.gateway.security;

import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.huntercat.lieshou.framework.jwt.JwtSupport;
import cn.huntercat.lieshoucloudpro.gateway.config.ApiErrorWriter;
import io.jsonwebtoken.Claims;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * gateway 全局鉴权过滤器 (Phase 5 闭环鉴权).
 *
 * <p>白名单: {@code /api/auth/**}, {@code /v3/api-docs/**}, {@code /swagger-ui/**}, {@code
 * /actuator/health}. 其他路径必须带有效 Bearer JWT.
 *
 * <p>验证通过后向请求注入 {@code X-User-Id}, {@code X-User-Name}, {@code X-User-Roles} header, 下游服务可直接读取.
 *
 * @see .ai/decisions/0017-spring-security-jwt.md
 */
@Component
public class AuthenticationFilter implements GlobalFilter, Ordered {

  private static final Logger log = LoggerFactory.getLogger(AuthenticationFilter.class);
  private static final String BEARER_PREFIX = "Bearer ";
  private static final String HDR_X_USER_ID = "X-User-Id";
  private static final String HDR_X_USER_NAME = "X-User-Name";
  private static final String HDR_X_USER_ROLES = "X-User-Roles";
  private static final String HDR_X_TENANT_ID = "X-Tenant-Id";
  private static final String HDR_X_TENANT_CODE = "X-Tenant-Code";
  private static final String HDR_X_CROSS_TENANT = "X-Cross-Tenant";

  /** 跨租户只读：PLATFORM_ADMIN（集团超管）可携带 ?tenantId= 覆盖租户上下文（仅 GET） */
  private static final String ROLE_PLATFORM_ADMIN = "PLATFORM_ADMIN";

  private static final String QP_TENANT_ID = "tenantId";

  private final JwtSupport jwt;

  public AuthenticationFilter(JwtSupport jwt) {
    this.jwt = jwt;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    String path = exchange.getRequest().getPath().value();

    // 白名单 —— 不需要鉴权
    if (isWhitelist(path)) {
      return chain.filter(exchange);
    }

    // 鉴权
    String auth = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
    String token = null;
    if (auth != null && auth.startsWith(BEARER_PREFIX)) {
      token = auth.substring(BEARER_PREFIX.length()).trim();
    } else {
      // SSE / EventSource 场景：无法携带 Authorization header，支持 query 参数 access_token（2026-08-30）
      String qToken = exchange.getRequest().getQueryParams().getFirst("access_token");
      if (qToken != null && !qToken.isBlank()) {
        token = qToken.trim();
      }
    }
    if (token == null) {
      return ApiErrorWriter.write(
          exchange.getResponse(),
          HttpStatus.UNAUTHORIZED,
          "MISSING_BEARER_TOKEN",
          "缺少 Bearer Token");
    }
    if (!jwt.validate(token)) {
      return ApiErrorWriter.write(
          exchange.getResponse(),
          HttpStatus.UNAUTHORIZED,
          "INVALID_OR_EXPIRED_TOKEN",
          "登录已过期，请重新登录");
    }

    Claims claims;
    try {
      claims = jwt.parse(token);
    } catch (Exception e) {
      log.warn("Failed to parse JWT claims for path {}: {}", path, e.getMessage());
      return ApiErrorWriter.write(
          exchange.getResponse(), HttpStatus.UNAUTHORIZED, "MALFORMED_TOKEN", "Token 格式非法");
    }

    Long uid = claims.get("uid", Long.class);
    Long tenantId = claims.get("tid", Long.class);
    String tenantCode = claims.get("tcode", String.class);
    String username = claims.getSubject();
    @SuppressWarnings("unchecked")
    List<String> roles = claims.get("roles", List.class);

    // ===== 跨租户只读（B4 · 集团超管） =====
    // PLATFORM_ADMIN + ?tenantId=NNN → 覆盖租户上下文（仅 GET 只读）；其他一律按 JWT tid 隔离。
    String qTenantId = exchange.getRequest().getQueryParams().getFirst(QP_TENANT_ID);
    boolean crossTenant =
        qTenantId != null
            && !qTenantId.isBlank()
            && roles != null
            && roles.contains(ROLE_PLATFORM_ADMIN);
    if (crossTenant) {
      String method = exchange.getRequest().getMethod().name();
      if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
        return ApiErrorWriter.write(
            exchange.getResponse(),
            HttpStatus.FORBIDDEN,
            "CROSS_TENANT_READ_ONLY",
            "跨租户访问仅支持只读（GET），写操作请在目标租户内进行");
      }
      try {
        Long target = Long.parseLong(qTenantId.trim());
        if (target <= 0) throw new NumberFormatException();
        tenantId = target;
        tenantCode = null; // code 未知（需查库），仅覆盖 id；下游按 X-Tenant-Id 过滤
        log.info(
            "[cross-tenant] uid={} username={} → tenant={} path={} (PLATFORM_ADMIN 只读)",
            uid,
            username,
            target,
            path);
      } catch (NumberFormatException e) {
        return ApiErrorWriter.write(
            exchange.getResponse(), HttpStatus.BAD_REQUEST, "INVALID_TENANT_ID", "tenantId 必须为正整数");
      }
    }

    // 注入用户信息到请求头（让下游服务可直接读；Phase 8 含租户维度 ADR-0022）
    ServerHttpRequest mutated =
        exchange
            .getRequest()
            .mutate()
            .header(HDR_X_USER_ID, uid == null ? "" : String.valueOf(uid))
            .header(HDR_X_USER_NAME, username == null ? "" : username)
            .header(HDR_X_USER_ROLES, roles == null ? "" : String.join(",", roles))
            .header(HDR_X_TENANT_ID, tenantId == null ? "" : String.valueOf(tenantId))
            .header(HDR_X_TENANT_CODE, tenantCode == null ? "" : tenantCode)
            .header(HDR_X_CROSS_TENANT, crossTenant ? "true" : "") // 供下游识别跨租户访问（审计/日志）
            .build();
    return chain.filter(exchange.mutate().request(mutated).build());
  }

  @Override
  public int getOrder() {
    // 在路由之前执行
    return Ordered.HIGHEST_PRECEDENCE + 10;
  }

  private static boolean isWhitelist(String path) {
    return path.startsWith("/api/auth/")
        || path.startsWith("/v3/api-docs/")
        || path.startsWith("/swagger-ui/")
        || path.startsWith("/webjars/")
        // actuator: 顶层 + 下游服务的 actuator/health 探针
        || path.startsWith("/actuator")
        || path.contains("/actuator/")
        // 下游服务自定义 health 端点 (user-service: /api/users/_health 等)
        || path.endsWith("/_health")
        // 注意：/api/users/auth/**（鉴权视图，含 BCrypt passwordHash）**不能**放行——
        // auth-service 调 user-service 走 Nacos 服务发现直连，不经网关；
        // 历史上误标「服务间调用」放行，导致该视图公网无凭据可读（BUG_LESSONS E56，2026-09-14）。
        // 租户自助开通（公开 · SaaS 增长路径 · issue #24）
        || path.startsWith("/api/tenants/register")
        // 设备 HTTP 接入（ADR-0040 · X-Device-Secret 设备级认证，不经 JWT）
        || path.startsWith("/api/devices/v1/")
        // 设备照片静态读取（ADR-0040 派生 · <img> 无法带 header，URL 为 UUID 不可枚举）
        || path.startsWith("/api/iot/photos/")
        // 用户头像静态读取（2026-09 头像功能 · 同 <img> 无 header，UUID 不可枚举）
        || path.startsWith("/api/user-files/avatars/")
        // 分享 token 免登录只读（H5 触达层 · POST /api/iot/share 生成仍需登录）
        || path.startsWith("/api/iot/share-access/")
        // 协会运营系统 对外门户公开读（活动/资讯浏览 · 报名等写操作仍需登录）
        || path.startsWith("/api/xiehui/public/")
        // Nacos 注册回调 (Spring Cloud 心跳/注册)
        || path.startsWith("/nacos/");
  }
}
