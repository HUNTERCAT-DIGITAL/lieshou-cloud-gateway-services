# lieshou-cloud-gateway-services · 开源后端服务:gateway

> 自 lieshou-cloud-core/services/gateway 拆分(2026-08 细粒度)。
> 上游走内网 Nexus（framework 版本线 0.3.3），本仓无 submodule。

```bash
# 在仓库根执行：framework-parent / framework-* 从内网 Nexus maven-public 解析
mvn -B -ntp -s services/.m2-settings.xml -DskipTests -pl services/gateway package
```
