package cn.huntercat.lieshoucloudpro.gateway.config;

import org.springframework.context.ConfigurableApplicationContext;

import org.springframework.cloud.gateway.filter.factory.RewritePathGatewayFilterFactory;
import org.springframework.cloud.gateway.handler.predicate.PathRoutePredicateFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import reactor.core.publisher.Flux;

/**
 * IndustryRoutesConfig 开关行为（纯单元测试，不加载 Spring 上下文）： - lsc.industry-routes-enabled=false（开源交付包）→
 * 行业/商业路由一个不注册； - true（缺省，商业/客户仓）→ 18 条行业路由全部注册。
 */
class IndustryRoutesConfigTest {

  /** 行业路由 id 全集（与 IndustryRoutesConfig 注册的逐条对应）。 */
  private static final Set<String> INDUSTRY_ROUTE_IDS =
      Set.of(
          "crm-route",
          "inventory-route",
          "finance-route",
          "file-route",
          "iot-route",
          "device-http-route",
          "legal-route",
          "openapi-crm",
          "openapi-inventory",
          "openapi-finance",
          "openapi-file",
          "openapi-iot",
          "openapi-device-gateway",
          "openapi-legal",
          "swagger-ui-crm",
          "swagger-ui-inventory",
          "swagger-ui-finance",
          "swagger-ui-file",
          "swagger-ui-iot",
          "swagger-ui-device-gateway",
          "swagger-ui-legal",
          // 海赞总部 · 项目管控/人事档案（project-service）
          "project-route",
          "org-route",
          "employee-route",
          "metric-route",
          "portfolio-route",
          "document-route",
          "data-asset-route",
          "executive-report-route",
          // ⚠️ 以下两条是**具体客户**的路由（xiehui 协会 / jiangcai 江财），却注册在共享网关里。
          //    它们是本测试漏项的直接原因，也属“共享层写死客户”的设计债：
          //    远期应由 `lsc.industry-routes` 之类的配置（edition 注入）驱动，而不是硬编码。
          "xiehui-route",
          "jiangcai-route");

  private RouteLocator build(boolean enabled) throws Exception {
    IndustryRoutesConfig config = new IndustryRoutesConfig();
    Field f = IndustryRoutesConfig.class.getDeclaredField("industryRoutesEnabled");
    f.setAccessible(true);
    f.set(config, enabled);
    ConfigurableApplicationContext ctx = mock(ConfigurableApplicationContext.class);
    when(ctx.getBean(PathRoutePredicateFactory.class)).thenReturn(new PathRoutePredicateFactory());
    when(ctx.getBean(RewritePathGatewayFilterFactory.class))
        .thenReturn(new RewritePathGatewayFilterFactory());
    RouteLocatorBuilder builder = new RouteLocatorBuilder(ctx);
    return config.industryRouteLocator(builder);
  }

  private List<String> routeIds(RouteLocator locator) {
    return Flux.from(locator.getRoutes()).map(Route::getId).collectList().block();
  }

  @Test
  void disabledRegistersNoIndustryRoutes() throws Exception {
    List<String> ids = routeIds(build(false));
    assertThat(ids).isEmpty();
  }

  @Test
  void enabledRegistersAllIndustryRoutes() throws Exception {
    List<String> ids = routeIds(build(true));
    assertThat(ids).containsExactlyInAnyOrderElementsOf(INDUSTRY_ROUTE_IDS);
  }

  @Test
  void enabledRoutesPointToIndustryServiceUris() throws Exception {
    List<String> uris =
        Flux.from(build(true).getRoutes()).map(r -> r.getUri().toString()).collectList().block();
    assertThat(uris)
        .contains(
            "lb://lieshoucloud-crm",
            "lb://lieshoucloud-inventory",
            "lb://lieshoucloud-finance",
            "lb://lieshoucloud-file",
            "lb://lieshoucloud-iot",
            "lb://lieshoucloud-device-gateway",
            "lb://lieshoucloud-legal");
    // 不含开源服务 URI（开源路由在 application.yml）
    assertThat(uris)
        .noneMatch(u -> u.contains("lieshoucloud-user") || u.contains("lieshoucloud-auth"));
  }
}
