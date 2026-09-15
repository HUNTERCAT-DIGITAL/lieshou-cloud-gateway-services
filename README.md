# lieshou-cloud-gateway-services · 开源后端服务:gateway

> 自 lieshou-cloud-core/services/gateway 拆分（2026-08 细粒度）。
> **上游全部走内网 Nexus**：本仓无 submodule；`framework-parent` / `framework-*` 由
> `https://nexus.ts.huntercat.cn/repository/maven-public/` 解析（framework 版本线 **0.3.3**，见 `services/gateway/pom.xml` 的 parent）。
> 本仓只留网关装配（路由 / 鉴权过滤器 / 错误体）+ 启动，业务在 `lieshou-framework`（上游同源唯一）。

## 构建

```bash
# 前提：~/.m2/settings.xml 已 mirror 到内网 Nexus
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn -B -ntp -DskipTests -f pom.xml package
# 只构建 gateway（本仓只有这一个模块，-pl 只是显式化）
mvn -B -ntp -DskipTests -f pom.xml -pl services/gateway package
# 全量门（spotless check + 单测；无 DB 依赖）
mvn -B -ntp verify
```

产物：`services/gateway/target/lieshoucloud-gateway.jar`（fat jar，端口 9000）。

## 镜像

```bash
docker build -f services/gateway/Dockerfile .   # context = 仓根
```

⚠️ Dockerfile 里 **不能**把 settings COPY 到 `/root/.m2/settings.xml`：`--mount=type=cache,target=/root/.m2`
会把它遮住 → Maven 回落公网 central 并写负缓存。故 builder 用 `-s /workspace/services/.m2-settings.xml`，
并先 `keytool` 导入 `services/.ci/caddy-root.crt`（Caddy 内部 CA，否则拉 Nexus 报 PKIX）。

## 发版（制品进内网 Nexus `maven-releases`）

```bash
# 版本 = services/gateway 继承自 framework-parent 的版本（模块自身不写 <version>）
ver=$(mvn -B -q -f pom.xml -pl services/gateway help:evaluate -Dexpression=project.version -DforceStdout | tail -1)
# main 受保护 → 一律特性分支 + MR；合并后打 tag = v + 版本，CI 的 deploy job 才发制品
git tag v$ver && git push origin v$ver
```

`deploy` job 会校验 tag == pom 版本，不符直接拒发；已发布的版本 Nexus releases 拒绝覆盖（要改就发新版本）。
