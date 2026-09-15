package cn.huntercat.lieshoucloudpro.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;

/**
 * 行业/商业服务路由（闭源组件，不在 LieShouCloud 开源交付包内）· 条件注册。
 *
 * <p>开源交付包部署：设置 {@code lsc.industry-routes-enabled=false}（compose 环境变量注入）， 不注册 crm / inventory /
 * finance / file / iot / device-gateway 路由——对应服务未随包部署， 避免 /api/customers 等请求 503 与 swagger 聚合页 404
 * 噪音。
 *
 * <p>商业/客户仓部署：保持默认 {@code true}，行为与历史 application.yml 显式路由完全一致 （2026-08 由 YAML 迁至 Java
 * DSL，predicate / filter / uri 逐条对齐）。
 */
@Configuration
public class IndustryRoutesConfig {

  /** 行业路由开关（默认开启，保持客户仓行为不变；开源交付包 compose 显式关闭）。 */
  @Value("${lsc.industry-routes-enabled:true}")
  private boolean industryRoutesEnabled;

  @Bean
  public RouteLocator industryRouteLocator(RouteLocatorBuilder builder) {
    if (!industryRoutesEnabled) {
      return builder.routes().build();
    }

    RouteLocatorBuilder.Builder routes = builder.routes();

    // ----- 业务路由（各行业服务强制 X-Tenant-Id，见对应服务） -----
    routes.route("crm-route", r -> r.path("/api/customers/**").uri("lb://lieshoucloud-crm"));
    routes.route(
        "inventory-route", r -> r.path("/api/products/**").uri("lb://lieshoucloud-inventory"));
    routes.route(
        "finance-route",
        r -> r.path("/api/ledger/**", "/api/bank/**").uri("lb://lieshoucloud-finance"));
    routes.route("file-route", r -> r.path("/api/files/**").uri("lb://lieshoucloud-file"));
    routes.route("iot-route", r -> r.path("/api/iot/**").uri("lb://lieshoucloud-iot"));
    routes.route(
        "device-http-route",
        r -> r.path("/api/devices/v1/**").uri("lb://lieshoucloud-device-gateway"));
    // ADR-0045 · 律师办案（案件/阶段/计时/费用/文书/函件/评审/客户价值/知识/AI）
    routes.route("legal-route", r -> r.path("/api/legal/**").uri("lb://lieshoucloud-legal"));
    // 协会运营系统（xiehui · 会员/会费/财务/预算/审批/活动/报名签到/资讯/邮件/汇报）→ xiehui-service
    routes.route("xiehui-route", r -> r.path("/api/xiehui/**").uri("lb://lieshoucloud-xiehui"));
    // 校友会投融资管理平台（jiangcai · 校友董监高/上市公司画像/商机/数据任务/月报）→ jiangcai-service
    routes.route(
        "jiangcai-route", r -> r.path("/api/jiangcai/**").uri("lb://lieshoucloud-jiangcai"));
    //  项目管控（组织/项目/里程碑/任务）→ project-service
    routes.route("project-route", r -> r.path("/api/projects/**").uri("lb://lieshoucloud-project"));
    routes.route("org-route", r -> r.path("/api/orgs/**").uri("lb://lieshoucloud-project"));
    // 投资组合（被投企业主数据 · 2026-08 信息架构深化）→ project-service
    routes.route(
        "portfolio-route", r -> r.path("/api/portfolio/**").uri("lb://lieshoucloud-project"));
    //  人事档案（R4.2 员工入转调离）→ project-service
    routes.route(
        "employee-route", r -> r.path("/api/employees/**").uri("lb://lieshoucloud-project"));
    //  经营指标回传（R6.1）→ project-service
    routes.route("metric-route", r -> r.path("/api/metrics/**").uri("lb://lieshoucloud-project"));
    //  集团文档管理（C2 企业网盘）→ project-service
    routes.route(
        "document-route", r -> r.path("/api/documents/**").uri("lb://lieshoucloud-project"));
    //  数据资产入表（C4）→ project-service
    routes.route(
        "data-asset-route", r -> r.path("/api/data-assets/**").uri("lb://lieshoucloud-project"));
    //  高管日报（D3）→ project-service
    routes.route(
        "executive-report-route",
        r -> r.path("/api/executive-reports/**").uri("lb://lieshoucloud-project"));

    // ----- OpenAPI 文档转发（/v3/api-docs/{service} → 对应服务） -----
    openapiRoute(routes, "crm", "openapi-crm");
    openapiRoute(routes, "inventory", "openapi-inventory");
    openapiRoute(routes, "finance", "openapi-finance");
    openapiRoute(routes, "file", "openapi-file");
    openapiRoute(routes, "iot", "openapi-iot");
    openapiRoute(routes, "device-gateway", "openapi-device-gateway");
    openapiRoute(routes, "legal", "openapi-legal");

    // ----- Swagger UI 转发 -----
    swaggerUiRoute(routes, "crm", "swagger-ui-crm");
    swaggerUiRoute(routes, "inventory", "swagger-ui-inventory");
    swaggerUiRoute(routes, "finance", "swagger-ui-finance");
    swaggerUiRoute(routes, "file", "swagger-ui-file");
    swaggerUiRoute(routes, "iot", "swagger-ui-iot");
    swaggerUiRoute(routes, "device-gateway", "swagger-ui-device-gateway");
    swaggerUiRoute(routes, "legal", "swagger-ui-legal");

    return routes.build();
  }

  /** /v3/api-docs/{svc} → lb://lieshoucloud-{svc}，RewritePath 剥掉服务名前缀。 */
  private void openapiRoute(RouteLocatorBuilder.Builder routes, String svc, String id) {
    routes.route(
        id,
        r ->
            r.path("/v3/api-docs/" + svc, "/v3/api-docs/" + svc + "/**")
                .filters(
                    f ->
                        f.rewritePath(
                            "/v3/api-docs/" + svc + "(?<segment>/.*)?", "/v3/api-docs${segment}"))
                .uri("lb://lieshoucloud-" + svc));
  }

  /** /swagger-ui/{svc}/** → lb://lieshoucloud-{svc}，RewritePath 剥掉服务名前缀。 */
  private void swaggerUiRoute(RouteLocatorBuilder.Builder routes, String svc, String id) {
    routes.route(
        id,
        r ->
            r.path("/swagger-ui/" + svc, "/swagger-ui/" + svc + "/**")
                .filters(
                    f ->
                        f.rewritePath(
                            "/swagger-ui/" + svc + "(?<segment>/.*)", "/swagger-ui${segment}"))
                .uri("lb://lieshoucloud-" + svc));
  }
}
