# lieshou-cloud-gateway-services 制品发布到内网 Nexus 设计（2026-09-14）

> 目标一句话：让 `cn.huntercat:lieshoucloud-gateway:0.3.1`（fat jar + pom）进内网 Nexus
> `maven-releases`，且下游**只靠 Nexus 就能解析消费**；形状与已完成的 `user` / `auth` /
> `approval` 三个 L2 服务仓**逐字同构**。

**关联**

- 同批先例（本设计的直接模板，逐字对齐）：
  - `lieshou-cloud-auth-services` `1372ffd` "chore(ci): 制品发布到内网 Nexus maven-releases（tag v0.3.1）"
  - `lieshou-cloud-approval-services` `02a92e6`、`lieshou-cloud-user-services` `0906448` + `7dae7da`
- `lieshou-framework` `v0.3.1`（`d023687`）/ `v0.3.2`（`8abac69`）；其 `deploy` 先例：`.gitlab-ci.yml` tag 触发
- `huntercat-ai-kit` `SUPPLY_CHAIN.md`（tag = 发布点、只增不改、只选发布点不追 main）
- L2 外围文件设计：`docs/superpowers/specs/2026-09-14-l2-services-peripheral-and-framework-0.2.0-design.md`（本设计**不做**其中的 LICENSE/AGENTS/CHANGELOG/.ai 部分）

## 0. 实测背景（2026-09-14，逐条实跑，非推测）

### 0.1 本仓现状

| 项 | 实测 |
| --- | --- |
| `origin/main` | `1de96e6`；已有 `.gitlab-ci.yml`（**仅 `verify`**）、`.github/` 已删、gateway 的 parent = `framework-parent:0.3.0` |
| 版本 | `services/gateway/pom.xml` **无自带 `<version>`** ⇒ `lieshoucloud-gateway` 版本 = **继承 parent = 0.3.0** |
| reactor | 根 `pom.xml`（聚合器，`0.1.0`）含 `<module>framework</module>` + `<module>services/gateway</module>` |
| submodule pin | `739b696`（= framework `v0.3.0`） |
| 本仓 tag | 只有 `patrol-2026-08` / `release-2026-09` / `share-readonly-2026-08`，**无 `v*`** |
| `distributionManagement` | gateway **已经继承**（来自 `framework-parent`，id=`nexus` → releases/snapshots）⇒ 缺的不是"能不能 deploy" |
| CI 历史 | pipeline `571`（main, 2026-09-14T06:14Z）**success**（只有 verify job） |

### 0.2 GitLab 项目 22（`huntercat-digital/lieshou-cloud-gateway-services`）

| 项 | 项目 22 | 对照：24 / 37 / 34（user / auth / approval） |
| --- | --- | --- |
| CI 变量 `NEXUS_DEPLOY_USER` / `NEXUS_DEPLOY_PASS` | **无** | 均有（masked + protected） |
| protected tag | **无** | 均有 `v*` → Maintainers |
| `main` 保护 | push = **No one**（只能走 MR）⇒ 与 contract-config 同款 | 同 |

`NEXUS_DEPLOY_*` 也存在于 framework 项目 13（masked+protected）。**注意**：masked 变量值 GitLab API 仍返回（实测 `value_len=11 / 32`）⇒ 可编程复制；本设计据此把"配变量"降为一条命令（见 §3 #5），但**明文绝不落对话/终端/仓库**。

组 `huntercat-digital` 下有 **81 个项目**（含全部交付仓/客户仓）⇒ **组级变量一律否决**（等于把发布密钥发给客户仓）。

### 0.3 同批三仓已落地的形状（本设计要与之逐字同构）

`auth` 的 `1372ffd` 改动 = 4 个文件，本设计照抄：

```
.gitignore            |  1 +        → +.codegraph/
.gitlab-ci.yml        | 29 ++++++-   → stages: [verify, deploy] + deploy job
framework             |  2 +-       → pin 739b696 → d023687（= v0.3.1）
services/auth/pom.xml |  2 +-       → parent 0.3.0 → 0.3.1
```

已产出的制品（Nexus `maven-releases` 实查）：

```
lieshoucloud-auth     0.3.1   jar 59,175,076 B  2026-09-14T14:38:16Z
lieshoucloud-approval 0.3.1
（lieshoucloud-user: tag v0.3.1 已打，pipeline 807 正在跑 / pending）
```

⇒ 三仓的口径：**版本继承 `framework-parent`（故 = framework 版本）、tag `vX.Y.Z` 必须等于制品版本、`-pl services/<svc> -DskipTests deploy` 只发本仓模块**。

### 0.4 消费侧可达性（这一步才决定"发出去有没有用"）

| 探针 | 实测 |
| --- | --- |
| `maven-public` 匿名读 | `curl .../maven-public/cn/huntercat/framework-parent/0.3.1/framework-parent-0.3.1.pom` → **200** |
| `framework-parent:0.3.1` 的 `lieshou-framework.version` | `0.3.1`（与自身版本一致，见 framework-parent 注释约定） |
| Nexus releases 现有 `cn.huntercat` | `framework-*` `0.3.1` + `0.3.2`；`lieshoucloud-auth/approval` `0.3.1`；**零个** `lieshoucloud-gateway` |
| maven-snapshots | 空 |

⇒ 只要 gateway 的 parent 声明成 `0.3.1`，**parent 链 + framework-\* 依赖都能从 Nexus 解析**，制品可被消费。若停在 `0.3.0`，消费方解析 parent 会 404（`0.3.0` 从未入库）——这就是本设计必须 bump pin 的原因，不是"顺手升级"。

### 0.5 CI 运行环境（runner 10，实测读宿主 `config.toml`）

```toml
name = "java-maven (wqc-server-dell · docker)"   id = 10   executor = "docker"
privileged = false
volumes = ["/srv/gitlab-runner-cache/m2:/root/.m2", "/cache"]
extra_hosts = ["gitlab.ts.huntercat.cn:100.64.0.22", "nexus.ts.huntercat.cn:100.64.0.22", "host.docker.internal:host-gateway"]
environment = ["GIT_SSL_NO_VERIFY=true"]
concurrent = 3
```

`/srv/gitlab-runner-cache/m2/caddy-root.crt` **存在**（deploy job 的 `keytool` 依赖它）。

**两个由环境决定的硬约束**：

1. **无 `docker.sock`、`privileged=false`** ⇒ 本仓 GitLab CI **不可能** `docker build/push` ⇒ Harbor 镜像（§9 队列项）**做不了**，必须走 Jenkins（已有 `harbor-admin` 凭据 + `harbor.ts.huntercat.cn` 已在使用）。
2. `concurrent = 3` + 同批三仓正在跑 ⇒ **不要并发打 tag**，避免 runner 排队与 Nexus 重发竞态。

### 0.6 消费侧现状（决定"本批做到哪一步"）

交付仓（如 `lieshou-delivery-dwjk`）当前 **vendor 本仓源码**，在 `services/gateway/Dockerfile` 里
`mvn -f pom.xml -pl services/gateway -am clean package` 自己编 fat jar（`finalName = lieshoucloud-gateway`）。
⇒ **目前没有任何东西从 Nexus 取 gateway 制品**；"交付仓改成从 Nexus 取料"属于另一批（§9），本设计只负责**证明它可以被消费**。

## 1. 目标与非目标

**目标**

1. `cn.huntercat:lieshoucloud-gateway:0.3.1`（fat jar + pom）入 `maven-releases`，坐标 §4 定义
2. **可消费性有实证**：干净本地库里 `mvn dependency:get` 能拉到（= 交付仓未来 `dependency:copy` 的等价物）
3. 形状与 user/auth/approval **逐字同构**（同一份 CI 片段、同一套版本/tag 口径、同一套凭证与保护）
4. 四条 tag 纪律成立：tag 只能打在验证过的 commit 上、tag == 制品版本、版本只增不退、产物发布后自证存在

**非目标（明确不做）**

- **不做 Harbor 镜像**：本仓 runner 无 docker（0.5）→ 登记 §9
- **不做**"gateway 自立版本 + framework 出 reactor"（要 4 仓一起改，另立一批，见 §9）
- **不改交付仓**（不从本仓切到"只拉 Nexus 制品"）→ §9
- **不动 `lieshou-framework` 上游**（只 bump pin）
- **不改任何 Java 代码**（本批纯 CI/pom/pin + 基础设施）
- **不补外围文件**（LICENSE / AGENTS.md / CHANGELOG.md / `.ai/`）——属 L2 外围文件那批
- 不删 `.github/`（本仓已删）

## 2. 已拍板决策

| # | 决策 | 备选与否决理由 |
| --- | --- | --- |
| D1 | 制品 = **fat jar + pom**（`lieshoucloud-gateway-0.3.1.jar` 可直接 `java -jar`） | 否决"只发 pom"：下游要的是能跑的东西；否决"只发镜像"：本仓 runner 做不了镜像（0.5） |
| D2 | 形状 = **与 user/auth/approval 逐字同构** | 否决"本仓另立新形状"：4 仓要一起被下游同等对待，同构 = 下游一份消费说明覆盖全部；另立形状会造成"同一天里 4 个兄弟仓两套规矩" |
| D3 | 版本 = **继续继承 `framework-parent`**（本批 `0.3.1`） | 否决"gateway 自立版本"（更严）：那要连带改 framework 依赖表达与另外 3 仓，属结构整改，§9 单独立项。**已知代价见 R1/R2** |
| D4 | pin → `d023687`（= `v0.3.1`），parent 同步 `0.3.0 → 0.3.1` | **前提而非顺手升级**：`0.3.0` 从未入库，停在 `0.3.0` 的 parent 会让消费方 404（0.4） |
| D5 | 通道 = **单通道**：仅 `vX.Y.Z` tag → `maven-releases` | 否决 main 快照通道：Nexus 快照只增不删；且与 SUPPLY_CHAIN"只选发布点不追 main"相反 |
| D6 | 发布范围 = **`-pl services/gateway`** | 否决全量 `mvn deploy`：reactor 含 `framework`，会把 `framework-*` 越界重发（撞 Nexus 重发策略） |
| D7 | 凭证 = **项目 22 项目级变量**，值同 framework 项目 13，masked+protected | 否决组级变量：组内 81 个项目含客户交付仓（0.2）；否决新建 Nexus 用户：属基础设施增量，登记 §9 |
| D8 | 保护 = **protected tag `v*` → Maintainers**（对齐 24/37/34/13） | 否决不保护：任何成员可造发布点，与"tag = 不可变发布点"矛盾 |
| D9 | CI **完全同构，不额外加"发布后回读"步骤**；回读作为**验收证据**（§6）由实施时实测执行 | 否决"gateway 特例加一行 curl"：同构优先（D2）；回读的价值由 §6 的实测证据兑现，不必让 4 仓 CI 出现差异。（若日后要"每次发布自证"，作为 4 仓统一改动再做） |
| D10 | Harbor 镜像与"自立版本/reactor 整改"**登记**为后续批（§9） | 见 D2/D3；本批范围 = 一次可验证、可回滚的最小发布能力 |
| D11 | 实施在**隔离 worktree**（`~/dev/worktrees/gateway-services-ci-nexus`）+ **MR** | `main` push = No one（0.2）；且 `~/dev/gitlab-mirror/...` 是多会话共用的 mirror clone，曾有并行会话撞车事故 |

## 3. 交付物

### 3.1 代码副本（4 个文件，与 auth `1372ffd` 同形）

| # | 文件 | 改动 |
| --- | --- | --- |
| 1 | `services/gateway/pom.xml` | `<parent>` 的 `framework-parent` 版本 `0.3.0 → 0.3.1`（**只改这一行**；不带自己的 `<version>`，保持继承 = D3） |
| 2 | `framework`（gitlink） | `739b696 → d023687`（framework `v0.3.1`） |
| 3 | `.gitlab-ci.yml` | `stages: [verify, deploy]` + `deploy` job（§4） |
| 4 | `.gitignore` | `+ .codegraph/`（本地索引目录，对齐三仓） |

### 3.2 GitLab 基础设施（项目 22）

| # | 对象 | 值 | 手段 |
| --- | --- | --- | --- |
| 5 | CI 变量 `NEXUS_DEPLOY_USER` / `NEXUS_DEPLOY_PASS` | 与项目 13 同值，`masked=true`、`protected=true` | API 直接复制（不打印明文；只回读元数据核对） |
| 6 | protected tag | `v*` → create = Maintainers | API |

### 3.3 发布点

| # | 对象 | 说明 |
| --- | --- | --- |
| 7 | annotated tag `v0.3.1` | 打在**合并后的 main tip**；message = 本次发布说明（含"首个 Nexus 制品"与坐标） |

## 4. CI 设计

`stages: [verify, deploy]`；`verify` 保持现状不动（含 submodule 拉取）。新增（与 auth/approval/user 同构，仅模块名不同）：

```yaml
# 制品发布：仅 tag 触发，发到内网 Nexus maven-releases（凭证在项目 CI 变量 NEXUS_DEPLOY_*）
# 坐标 cn.huntercat:lieshoucloud-gateway:<version>（版本继承 framework-parent，故 tag 必须 vX.Y.Z 对齐）
# 只发本仓模块：framework-* 由 framework 仓自己发布（同坐标重复发布会撞 Nexus 重发策略）
deploy:
  stage: deploy
  timeout: 30m
  rules:
    - if: '$CI_COMMIT_TAG'
  before_script:
    # 根 pom 的 <module>framework</module> 必须真的存在（空目录 → "Child module does not exist"）
    - git config --global url."https://gitlab-ci-token:${CI_JOB_TOKEN}@gitlab.ts.huntercat.cn/huntercat-digital/".insteadOf "ssh://git@gitlab.ts.huntercat.cn:22220/huntercat-digital/"
    - git submodule sync --recursive
    - git submodule update --init --recursive
  script:
    # tag 必须与 pom 版本一致，否则拒发（防止 v0.4.0 下发成 0.3.1）
    - |
      set -e
      ver=$(mvn -B -q -pl services/gateway help:evaluate -Dexpression=project.version -DforceStdout | tail -1)
      [ "$CI_COMMIT_TAG" = "v$ver" ] || { echo "❌ tag $CI_COMMIT_TAG 与 pom 版本 v$ver 不一致"; exit 1; }
      echo "发布 cn.huntercat:lieshoucloud-gateway:$ver → Nexus maven-releases"
    # 内部 CA 进 JVM 信任库（job 容器不认 Caddy 自签 CA；CA 由 runner 挂载卷 /root/.m2 提供）
    - keytool -importcert -noprompt -trustcacerts -alias caddy-root -file /root/.m2/caddy-root.crt -keystore "$JAVA_HOME/lib/security/cacerts" -storepass changeit
    # servers = 发布凭证；mirror 只服务读（*,!nexus 不劫持 distributionManagement 的 nexus）
    - printf '<settings><servers><server><id>nexus</id><username>%s</username><password>%s</password></server></servers><mirrors><mirror><id>nexus-public</id><url>https://nexus.ts.huntercat.cn/repository/maven-public/</url><mirrorOf>*,!nexus</mirrorOf></mirror></mirrors></settings>' "$NEXUS_DEPLOY_USER" "$NEXUS_DEPLOY_PASS" > /tmp/ci-settings.xml
    # -pl 只发 gateway（依赖 framework-* 从 maven-public/本地库解析）；测试已在 verify 阶段卡过
    - mvn -B -ntp -s /tmp/ci-settings.xml -pl services/gateway -DskipTests deploy
```

**为什么 `mirrorOf=*,!nexus` 是必需的**：`distributionManagement` 的 `<id>nexus</id>` 与服务端读镜像同名，若用 `mirrorOf=*` 会把**发布目标**也劫持成 `maven-public`（只读 → 405/401）。`!nexus` 显式放行上传端点。

**发布不重复跑测试的依据**：同一 pipeline 的 `verify` stage 已在**同一个 commit** 上跑过 `mvn verify`（`-DskipTests` 只是省第二次执行，不是"跳过门"）。

## 5. 数据流与消费契约

```
MR（pin v0.3.1 + parent 0.3.1 + deploy job）→ verify 绿 → squash 合并入 main
      │ 人工在 main tip 打 annotated tag v0.3.1
      ▼
pipeline(v0.3.1): verify + deploy(tag==0.3.1 → keytool CA → -pl services/gateway deploy)
      ▼
Nexus maven-releases
  cn/huntercat/lieshoucloud-gateway/0.3.1/lieshoucloud-gateway-0.3.1.{jar,pom}
      ▼  下游（§9 那批）：
        mvn dependency:copy ...  → Dockerfile COPY 进镜像（不再 vendor framework 源码）
```

**消费契约（写给下游那批，本批只验证可行性）**

- 坐标：`cn.huntercat:lieshoucloud-gateway:0.3.1`；仓库：`https://nexus.ts.huntercat.cn/repository/maven-public/`（匿名可读，已在 0.4 实测）
- 制品：spring-boot repackage 的 **fat jar**，`java -jar` 可直接起（`finalName = lieshoucloud-gateway`）
- parent 链：`framework-parent:0.3.1` + `framework-*:0.3.1` 均在 `maven-releases`（0.4 实测 200）
- 镜像坐标规则（下一批，**本批不实现**）：`harbor.ts.huntercat.cn/<project>/lieshoucloud-gateway:<version>`，**镜像 tag == jar 版本**（同版本号，杜绝"两个真源"）

## 6. 阶段与验收

### Phase 0 · 基线（已完成）

- `git submodule status` → `739b696`（v0.3.0）；`mvn -B -ntp verify` 基线结果见 §10 证据 1 行

### Phase 1 · 机械改动（worktree 内，4 文件）

按 §3.1 顺序；`git diff --stat` 必须与 auth `1372ffd` 同形（1/29/2/2 行级别）。

**验收**：`git diff --stat` 与 §3.1 逐条一致；`services/gateway/pom.xml` 只动了 parent 版本那一行；`git ls-tree HEAD framework` → `d023687`。

### Phase 2 · 本地验证

| 检查 | 命令 | 期望 |
| --- | --- | --- |
| 门 | `mvn -B -ntp verify` | **BUILD SUCCESS**，无新增失败（对照 Phase 0 基线） |
| 版本解析 | `mvn -B -q -pl services/gateway help:evaluate -Dexpression=project.version -DforceStdout` | `0.3.1` |
| 只发本仓 | `mvn -B -ntp -pl services/gateway -DskipTests deploy -Dmaven.deploy.skip=true`（干跑） | 反应堆只含 `lieshoucloud-gateway`（无 `framework-*`） |
| 可消费性 | 干净本地库 `mvn -Dmaven.repo.local=/tmp/m2-clean dependency:get -Dartifact=cn.huntercat:framework-parent:0.3.1` | BUILD SUCCESS（证明 parent 链可从 Nexus 解析） |

### Phase 3 · 合并与发布

1. 推分支 `ci/nexus-deploy-2026-09-14` → 开 MR → pipeline 绿 → **squash 合并**
2. 配好 §3.2 的变量与保护（**先于**打 tag：变量缺失会让 deploy 401）
3. 在合并后的 main tip 打 annotated `v0.3.1` 并推送
4. 等 pipeline（`concurrent=3`，先看有没有别的仓在跑）

**验收（证据必须贴实测输出）**：

| # | 命令 | 期望 |
| --- | --- | --- |
| 1 | `git ls-tree HEAD framework` | `d023687…` |
| 2 | pipeline(tag `v0.3.1`) | `verify` + `deploy` 均 **success**；日志含 `发布 cn.huntercat:lieshoucloud-gateway:0.3.1 → Nexus maven-releases` |
| 3 | `curl -sI .../maven-releases/cn/huntercat/lieshoucloud-gateway/0.3.1/lieshoucloud-gateway-0.3.1.jar` | **200**，字节数与 job 日志一致（约 59MB 量级） |
| 4 | `curl -s .../maven-releases/.../lieshoucloud-gateway-0.3.1.pom \| grep -A3 framework-parent` | `<version>0.3.1</version>`（消费方能解析 parent） |
| 5 | 干净本地库 `mvn -Dmaven.repo.local=/tmp/m2-clean dependency:get -Dartifact=cn.huntercat:lieshoucloud-gateway:0.3.1 -DremoteRepositories=nexus::default::https://nexus.ts.huntercat.cn/repository/maven-public/` | **BUILD SUCCESS**（可消费性铁证） |
| 6 | `curl -s ".../service/rest/v1/search?repository=maven-releases&name=lieshoucloud-gateway"` | 列出 `0.3.1` 的 `jar`/`pom` 及其 sha1/md5 |

### Phase 4 · 收尾

- 回填 §9 队列项状态与本 spec §10 证据
- 通知下游（下一次交付仓批次）制品可用

## 7. 风险与缓解

| # | 风险 | 影响 | 缓解 |
| --- | --- | --- | --- |
| R1 | **`0.3.1` 与 framework `0.3.1` 撞号**：读作"gateway 0.3.1"时无法区分"是 gateway 的迭代"还是"framework 0.3.1 时的 gateway" | 沟通/审计歧义（不破坏构建） | 本批接受（D2/D3）；§9 的"自立版本"整改批统一解决，并在 CHANGELOG/发布说明写明语义 = "继承 framework-parent 的版本" |
| R2 | **gateway 单独改动但 pin 未动 ⇒ 版本号不变 ⇒ 无法发布第二个版本**（releases 不可变） | 一个 gateway 自己的 bugfix 发不出去，只能顺带 bump framework pin | 登记 §9；本批实际影响为零（首次发布）。绕过方式：显式给 gateway pom 加 `<version>`（正是整改批内容） |
| R3 | 下游仍 vendor 源码；本批只做到"可被消费" | 交付仓构建仍慢/仍需 framework 源码 | §9 交付仓切换批；本批用 §6 验收 5 证明"切换的可行性已成立" |
| R4 | **与并行会话撞车**：同批 user 的 tag pipeline 正在跑（`pending`），approval 仓工作区有未提交改动，mirror clone 为多会话共用 | 误提交 / 分支被切走（历史上有过） | D11：隔离 worktree + 独立分支；不并发打 tag（0.5 约束 2） |
| R5 | masked 变量复制过程泄漏明文 | 密钥泄漏 | 全程单条命令内完成（读→写），**不 echo 值**；只回读 key/masked/protected 元数据；不在 shell history 里落值（用 `$(...)` 直接传参，不落文件） |
| R6 | Nexus 重发同版本失败（不可变） | 发布中断 | tag 只能前进（版本只增不退）；重发 = 发新版本号；**禁止 force push tag** |
| R7 | `main` push = No one 导致误以为能直推 | 工作白做 | 走 MR（D11）；tag 直接指向合并后 commit，不重打历史 tag |
| R8 | runner `concurrent=3` 排队导致 tag pipeline 长时间 pending | 发布延迟 | 等；必要时先看 `pipelines` 列表确认无其他仓在跑 |

## 8. 回滚

| 动作 | 回滚方式 |
| --- | --- |
| MR（未合并） | 关闭 MR / 删分支 |
| 合并后发现问题 | `git revert <squash commit>`（CI 改动与 pom/pin 在同一 commit，可整批回退） |
| pin bump 引起意外 | `git checkout 739b696 -- framework`（回到 v0.3.0，但会连带 parent 404 的消费问题） |
| 制品已发布且致命 | **不能删**（发布点只增不改）：发 `0.3.2` 修正，并在发布说明标注 `0.3.1` 作废；`v0.3.1` tag 保留（受保护，不可 force） |
| CI 改动 | 删 `deploy` job 即回到现状（verify-only） |
| 变量/保护 | 删除项目 22 的 `NEXUS_DEPLOY_*` / protected tag（本批新增物，删除即复原） |

## 9. 外部队列项（本批登记，不在本批做）

- [ ] **Harbor 镜像发布**（本仓 runner 无 docker.sock → 走 Jenkins）：`harbor.ts.huntercat.cn/<project>/lieshoucloud-gateway:<version>`，**镜像 tag == jar 版本**；前置：确认镜像所属 project、机器人账号/凭据
- [ ] **4 仓统一整改：服务仓自立版本 + framework 出 reactor**（解决 R1/R2）：版本与 framework 解耦（显式 `<version>`）、`<parent>` 去 `relativePath` 或接受 Nexus 解析、根 pom 去掉 `framework` 模块、CI 防漂移校验（submodule 里 `framework-parent` 版本 == 声明版本 == Nexus 存在）
- [ ] **交付仓从 Nexus 取 gateway 制品**（去掉 vendor `framework` 源码与 `-am`）：`Dockerfile` 改 `dependency:copy` + 镜像构建走 Jenkins；同批处理 user/auth/admin/approval
- [ ] **admin-services** 尚未在本批（同批三仓已覆盖 user/auth/approval，admin 待做）
- [ ] **修 approval-services 的 `deploy` 缺 `before_script`**（本轮审查实测发现，非本仓）：其 deploy 日志明写 `Skipping Git submodules setup`，能跑通只是因为同 pipeline 的 `verify`（有 `before_script`）先把**共享 build 目录**里的 `framework/` 填好了 —— 一旦 verify 不先跑或 build 目录被清，`-pl` 仍会在 POM collection 阶段报 `Child module .../framework ... does not exist`。gateway 采用了带 `before_script` 的正确形式
- [ ] **收紧 deploy 触发与守卫**（本仓有历史非 `v*` tag：`patrol-2026-08` / `release-2026-09` / `share-readonly-2026-08`）：① rules 改 `$CI_COMMIT_TAG =~ /^v/`；② 校验块加 `set -eo pipefail`（现 `| tail -1` 会吞掉 `help:evaluate` 的失败，仅靠下一行相等判定兜底）。现状是 **fail-closed**（打了会红、但绝不误发），故与 4 仓一致优先，纳入统一整改批
- [ ] 新建**服务仓专用 Nexus 用户**（最小权限、可单独吊销）替代共享 deploy 账号（D7 的升级路径）
- [ ] runner 支持 docker（挂 `/var/run/docker.sock` 或 dind）——同时也是 L2 外围文件批的 PG 测试缺口前置
- [ ] 重置 runner token（历史遗留项）
- [ ] L2 外围文件（LICENSE / AGENTS.md / CHANGELOG.md / `.ai/`）——本仓仍缺，属 L2 那份 spec

## 10. 证据清单（2026-09-14 实测回填）

**基线与本地门**

- [x] `baseline: dev@1de96e6（worktree）mvn -B -ntp verify → BUILD SUCCESS`；Reactor 13 条（framework-* 0.3.0 ×11 + gateway 0.3.0 + aggregator 0.1.0），gateway `Tests run: 10, Failures: 0`，84s
- [x] `pom: mvn -B -q -pl services/gateway help:evaluate -Dexpression=project.version -DforceStdout → 0.3.1`
- [x] `diff: git show --name-status 924abcd → 恰好 4 路径`（`.gitignore` / `.gitlab-ci.yml` / `framework` / `services/gateway/pom.xml`），无越界；`git diff --numstat services/gateway/pom.xml → 1 1`
- [x] `pin: git ls-tree 924abcd framework → 160000 commit d023687cc4c2b9463d17724a2fd699ff92aeebfb`（= framework `v0.3.1`；submodule 内 `framework-parent` 也是 `0.3.1`）
- [x] `gate（改动后）: mvn -B -ntp verify → BUILD SUCCESS`，`lieshou-cloud · Gateway 0.3.1 … SUCCESS`，`Tests run: 10, Failures: 0`（与基线同数）
- [x] `范围证明: mvn -B -ntp -pl services/gateway -DskipTests deploy -Dmaven.deploy.skip=true →` 只有 `Building lieshou-cloud · Gateway 0.3.1`，`Building framework*` 计数 **0**，`Skipping artifact deployment`
- [x] `sibling 同构: 与 user 逐字同构（仅注释措辞）；与 auth 仅注释差异；与 approval 差在 approval 缺 deploy 的 before_script`（见 §9 新增项）

**发布链**

- [x] `MR: !5 → pipeline 828 success → squash 合并 dbcdb07d7a540381ff2ba0804990f2250fb0457b（main tip d631494647508feeff50026c43710d78c6b6a30c）`
- [x] `tag: git ls-remote origin 'refs/tags/v0.3.1*' → 7856333… refs/tags/v0.3.1 / d631494… refs/tags/v0.3.1^{}`（peel 到 main tip）
- [x] `publish: pipeline 853（ref v0.3.1）→ verify success + deploy success`；`deploy` job 2565 日志：`发布 cn.huntercat:lieshoucloud-gateway:0.3.1 → Nexus maven-releases` + `BUILD SUCCESS`
- [x] `readback: curl -sI .../lieshoucloud-gateway-0.3.1.jar → HTTP/2 200`；`.pom → HTTP/2 200`；pom 内 `<artifactId>framework-parent</artifactId>` 版本 = `0.3.1`
- [x] `nexus search: name=lieshoucloud-gateway → 0.3.1 .jar 52,659,099 B（15:54:59Z）/ .pom 3,915 B（15:54:57Z）` + sha1/md5
- [x] `consume（铁证）: 干净本地库 /tmp/m2-gw-acceptance 执行 mvn dependency:get -Dartifact=cn.huntercat:lieshoucloud-gateway:0.3.1 -DremoteRepositories=nexus::default::…/maven-public/ → BUILD SUCCESS（4:00）`；落盘 `lieshoucloud-gateway-0.3.1.jar 52,659,099 B`，`sha1 bdb717b2a6d2bb95d824805b3ba77e3f906476b3` **与 Nexus 登记值逐字相同**
- [x] `制品形态: MANIFEST.MF → Main-Class: org.springframework.boot.loader.launch.JarLauncher / Start-Class: cn.huntercat.lieshoucloudpro.gateway.GatewayApplication`（fat jar，可直接 `java -jar`）

**基础设施**

- [x] `project 22 variables → NEXUS_DEPLOY_USER / NEXUS_DEPLOY_PASS 均 masked=true protected=true`；与 project 13 的**值 sha256 前缀逐字相同**（`f051247f20fa` / `cb86d4771149`，只比摘要、未打印明文）
- [x] `project 22 protected_tags → [('v*', ['Maintainers'])]`
- [x] `runner 观测: 发布期间 runner 10 被另两仓 job 占满（concurrent=3）→ 本仓 tag pipeline 排队约 13 分钟才 pickup`（R8 实测命中，未造成失败）

## 11. Spec 自检

- **占位符扫描**：无 TBD/TODO；每个交付物都给了文件与行级改动，每个阶段都给了命令与期望
- **内部一致性**：D3（继承版本）↔ §3.1 #1（只改 parent 版本）↔ §6 验收 2（`ver` 取自 `help:evaluate`）；D5（单通道）↔ §4（仅 `CI_COMMIT_TAG` 规则）↔ §9（否决快照）；D6（`-pl`）↔ §4 最后一行；D9（CI 不加回读）↔ §6 验收 3–6（回读放在验收）
- **范围检查**：单一实现计划可承载（4 文件 + 2 项 GitLab 配置 + 1 个 tag）；Harbor、自立版本、交付仓切换全部移出到 §9
- **歧义检查**：① "版本" 一律指 `help:evaluate` 得到的 `project.version`（= 继承值 `0.3.1`），不用 framework 仓的 0.3.2 混说；② "验证" 一律指 `mvn verify`（不含 `-DskipTests` 的 deploy）；③ "回读" 特指发布后从 Nexus 读回产物（§6 验收 3/6），非 CI 步骤（D9）
