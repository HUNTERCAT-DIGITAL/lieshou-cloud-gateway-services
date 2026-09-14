# lieshou-cloud-gateway-services 制品发布到内网 Nexus 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 `cn.huntercat:lieshoucloud-gateway:0.3.1`（fat jar + pom）进内网 Nexus `maven-releases`，并证明下游只靠 Nexus 就能解析消费。

**Architecture:** 与已完成的 `user`/`auth`/`approval` 三仓**逐字同构**：pin 到 framework `v0.3.1`（parent 才在 Nexus 可解析）→ gateway 的 parent 版本随之 `0.3.1`（制品版本继承 parent）→ `.gitlab-ci.yml` 增加仅 tag 触发的 `deploy` job（`mvn -pl services/gateway -DskipTests deploy`，绝不越界发 `framework-*`）→ 合并后打 annotated tag `v0.3.1` 触发发布。

**Tech Stack:** Maven 3.9 / JDK 21 / Spring Boot 3.5.7（parent `framework-parent`）/ GitLab CI（组级 docker runner `java-maven`，tags `java,wqc`）/ Nexus 3（`maven-releases` + `maven-public`）/ GitLab API（PAT，`api` scope）。

**Spec:** `docs/superpowers/specs/2026-09-14-gateway-services-nexus-release-design.md`（本计划逐条实现其 §3–§6）

---

## 文件结构

| 文件 | 责任 |
| --- | --- |
| `services/gateway/pom.xml` | 只改 `<parent>` 版本一行 → 制品版本 = `0.3.1`（继承口径，spec D3） |
| `framework`（gitlink） | pin → `d023687`（= framework `v0.3.1`），保证 parent 可从 Nexus 解析（spec D4） |
| `.gitlab-ci.yml` | 加 `deploy` stage：tag==版本校验 + CA 注入 + 凭证 settings + `-pl services/gateway deploy`（spec D5/D6/D9） |
| `.gitignore` | `+.codegraph/`（本地索引，对齐三仓） |
| GitLab 项目 22（无文件） | CI 变量 `NEXUS_DEPLOY_*`（D7）、protected tag `v*`（D8） |
| annotated tag `v0.3.1` | 发布点（打在合并后的 main tip） |

**工作区**：`/home/weilai/dev/worktrees/gateway-services-ci-nexus`（分支 `ci/nexus-deploy-2026-09-14`，基于 `origin/main` `1de96e6`）。**不要**在 `~/dev/gitlab-mirror/lieshou-cloud-gateway-services` 里干活（多会话共用 mirror clone）。

**环境变量**（每个 bash 步骤都先设）：

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export WT=/home/weilai/dev/worktrees/gateway-services-ci-nexus
```

---

## Task 1: 代码副本（4 文件 + pin）

**Files:**
- Modify: `services/gateway/pom.xml`
- Modify: `.gitlab-ci.yml`
- Modify: `.gitignore`
- Modify（gitlink）: `framework`

- [ ] **Step 1: parent 版本 0.3.0 → 0.3.1（只改这一行）**

`services/gateway/pom.xml` 内：

```xml
    <parent>
        <groupId>cn.huntercat</groupId>
        <artifactId>framework-parent</artifactId>
        <version>0.3.1</version>
        <relativePath>../../framework/framework-parent/pom.xml</relativePath>
    </parent>
```

（`<version>0.3.1</version>` 替换原 `0.3.0`。**不要**给 `lieshoucloud-gateway` 自己加 `<version>`——本批保持继承口径。）

- [ ] **Step 2: 验证只有一行被动**

Run:
```bash
cd $WT && git diff --numstat services/gateway/pom.xml
```
Expected: `1	1	services/gateway/pom.xml`（1 增 1 删）

- [ ] **Step 3: pin → framework `v0.3.1`（`d023687`）**

Run:
```bash
cd $WT
git -C framework fetch -q origin
git -C framework checkout -q d023687cc4c2b9463d17724a2fd699ff92aeebfb
git add framework
git submodule status
grep -m1 -A2 '<artifactId>framework-parent</artifactId>' framework/framework-parent/pom.xml | grep version
```
Expected:
- `git submodule status` → `d023687cc4c2b9463d17724a2fd699ff92aeebfb framework (v0.3.1)`
- `grep` → `<version>0.3.1</version>`（submodule 里的 parent 版本与 Step 1 声明一致）

- [ ] **Step 4: `.gitignore` 追加 `.codegraph/`**

`.gitignore` 最终内容（4 行）：

```gitignore
target/
**/target/
.idea/
.codegraph/
```

- [ ] **Step 5: `.gitlab-ci.yml` 加 `deploy` stage**

把首行 `stages: [verify]` 改成 `stages: [verify, deploy]`，并在文件末尾**原样**追加：

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

- [ ] **Step 6: YAML 语法自检（本地无 gitlab-ci-lint 就做最小结构检查）**

Run:
```bash
cd $WT && python3 -c "
import yaml,sys
d=yaml.safe_load(open('.gitlab-ci.yml'))
assert d['stages']==['verify','deploy'], d['stages']
assert 'deploy' in d and d['deploy']['stage']=='deploy'
assert d['deploy']['rules'][0]['if']=='\$CI_COMMIT_TAG'
print('✅ stages=',d['stages'],'deploy.script 行数=',len(d['deploy']['script']))
"
```
Expected: `✅ stages= ['verify', 'deploy'] deploy.script 行数= 4`

- [ ] **Step 7: 本地门（必须绿）**

Run:
```bash
cd $WT && JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn -B -ntp verify 2>&1 | tail -20
```
Expected: `BUILD SUCCESS`，Reactor 中 `lieshou-cloud · Gateway 0.3.1 ... SUCCESS`（注意版本已变 `0.3.1`），framework 模块为 `0.3.1`

> 基线对照（实测，改动前）：`mvn -B -ntp verify` → BUILD SUCCESS、12 模块、gateway `Tests run: 10, Failures: 0`、84s。

- [ ] **Step 8: 反应堆证明"只发本仓"**

Run:
```bash
cd $WT && JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn -B -ntp -pl services/gateway -DskipTests deploy -Dmaven.deploy.skip=true 2>&1 | grep -A6 "Reactor Summary"
```
Expected: Reactor 里**只有** `lieshou-cloud · Gateway`（没有任何 `framework-*`）

- [ ] **Step 9: 提交**

Run:
```bash
cd $WT
git add .gitignore .gitlab-ci.yml services/gateway/pom.xml framework
git commit -q -m "chore(ci): 制品发布到内网 Nexus maven-releases（tag v0.3.1）

- pin → framework v0.3.1（d023687）：parent 0.3.1 才在 Nexus 可解析
- services/gateway parent 0.3.0 → 0.3.1：制品版本继承（= lieshoucloud-gateway:0.3.1）
- CI 增 deploy stage：仅 tag 触发，-pl services/gateway 只发本仓模块
- 与 user/auth/approval 三仓逐字同构"
git log --oneline -1 && git status -s
```
Expected: 打印新 commit；`git status -s` 干净

---

## Task 2: GitLab 项目 22 基础设施（变量 + 保护）

**Files:** 无（项目配置）

> 顺序要求：**必须在打 tag 之前完成**（变量缺失会让 deploy job 401）。

- [ ] **Step 1: 铸一个任务级 PAT（用完即吊销）**

Run:
```bash
gitlab-pat new pi-gw-nexus-$(date +%Y%m%d) api 30
```
Expected: 输出包含 `pi-gw-nexus-20260914`（或当日日期）、scope `api`、有效期、落盘路径 `~/.gitlab-pat/<name>.token`；**明文不打印**

- [ ] **Step 2: 复制 `NEXUS_DEPLOY_*` 到项目 22（单条命令内完成，绝不回显值）**

Run:
```bash
TOK=$(cat ~/.gitlab-pat/pi-gw-nexus-$(date +%Y%m%d).token)
SRC=$(curl -s -H "PRIVATE-TOKEN: $TOK" "https://gitlab.ts.huntercat.cn/api/v4/projects/13/variables")
for K in NEXUS_DEPLOY_USER NEXUS_DEPLOY_PASS; do
  V=$(python3 -c "import json,sys;print([v['value'] for v in json.loads(sys.argv[1]) if v['key']=='$K'][0])" "$SRC")
  curl -s -f -o /dev/null -X POST -H "PRIVATE-TOKEN: $TOK" \
    --data-urlencode "key=$K" --data-urlencode "value=$V" \
    --data "masked=true" --data "protected=true" --data "environment_scope=*" \
    "https://gitlab.ts.huntercat.cn/api/v4/projects/22/variables" \
    && echo "✅ $K 已写入项目 22" || echo "❌ $K 写入失败（检查 masked 规则：≥8 字符、无空白）"
done
unset SRC V
```
Expected: 两行 `✅ ... 已写入项目 22`（若 `masked=true` 被拒，**不要**静默降级——停下来报告）

- [ ] **Step 3: 回读元数据核对（只核对 key/masked/protected，不打印值）**

Run:
```bash
TOK=$(cat ~/.gitlab-pat/pi-gw-nexus-$(date +%Y%m%d).token)
for P in 13 22; do
  printf "project %s: " $P
  curl -s -H "PRIVATE-TOKEN: $TOK" "https://gitlab.ts.huntercat.cn/api/v4/projects/$P/variables" \
    | python3 -c "import sys,json;print([(v['key'],v['masked'],v['protected']) for v in json.load(sys.stdin)])"
done
```
Expected: `project 13` 与 `project 22` 输出**完全一致**（两个 key，`True True`）

- [ ] **Step 4: protected tag `v*` → Maintainers**

Run:
```bash
TOK=$(cat ~/.gitlab-pat/pi-gw-nexus-$(date +%Y%m%d).token)
curl -s -f -o /dev/null -X POST -H "PRIVATE-TOKEN: $TOK" \
  --data-urlencode "name=v*" --data "create_access_level=40" \
  "https://gitlab.ts.huntercat.cn/api/v4/projects/22/protected_tags" && echo "✅ v* 已保护"
curl -s -H "PRIVATE-TOKEN: $TOK" "https://gitlab.ts.huntercat.cn/api/v4/projects/22/protected_tags" \
  | python3 -c "import sys,json;print([(t['name'],[a['access_level_description'] for a in t['create_access_levels']]) for t in json.load(sys.stdin)])"
```
Expected: 先 `✅ v* 已保护`，再 `[('v*', ['Maintainers'])]`（与项目 24/37/34 一致）

---

## Task 3: MR 与合并

**Files:** 无（远端流程）

- [ ] **Step 1: 推分支**

Run:
```bash
cd $WT && git push -q -u origin ci/nexus-deploy-2026-09-14 && git log --oneline -1
```
Expected: 推送成功（若被拒 —— 说明有人动过同名分支，停下来报告）

- [ ] **Step 2: 开 MR**

Run:
```bash
TOK=$(cat ~/.gitlab-pat/pi-gw-nexus-$(date +%Y%m%d).token)
curl -s -H "PRIVATE-TOKEN: $TOK" -X POST \
  --data-urlencode "source_branch=ci/nexus-deploy-2026-09-14" \
  --data-urlencode "target_branch=main" \
  --data-urlencode "title=ci: 制品发布到内网 Nexus maven-releases（tag v0.3.1）" \
  --data-urlencode "squash=true" \
  --data "remove_source_branch=true" \
  "https://gitlab.ts.huntercat.cn/api/v4/projects/22/merge_requests" \
  | python3 -c "import sys,json;d=json.load(sys.stdin);print('MR !%s %s' % (d['iid'], d['web_url']))"
```
Expected: 打印 `MR !<N> https://gitlab.ts.huntercat.cn/huntercat-digital/lieshou-cloud-gateway-services/-/merge_requests/<N>`

- [ ] **Step 3: 等 MR pipeline 绿**

Run（轮询到非 running/pending 为止）:
```bash
TOK=$(cat ~/.gitlab-pat/pi-gw-nexus-$(date +%Y%m%d).token)
MR=<上一步的 iid>
for i in $(seq 1 60); do
  S=$(curl -s -H "PRIVATE-TOKEN: $TOK" "https://gitlab.ts.huntercat.cn/api/v4/projects/22/merge_requests/$MR" | python3 -c "import sys,json;d=json.load(sys.stdin);print(d.get('head_pipeline',{}).get('status'))")
  echo "[$i] $S"; case "$S" in success|failed|canceled) break;; esac; sleep 20
done
```
Expected: 最终 `success`

- [ ] **Step 4: squash 合并**

Run:
```bash
TOK=$(cat ~/.gitlab-pat/pi-gw-nexus-$(date +%Y%m%d).token)
MR=<iid>
curl -s -H "PRIVATE-TOKEN: $TOK" -X PUT "https://gitlab.ts.huntercat.cn/api/v4/projects/22/merge_requests/$MR/merge" \
  | python3 -c "import sys,json;d=json.load(sys.stdin);print(d.get('state'), d.get('squash_commit_sha') or d.get('merge_commit_sha'))"
```
Expected: `merged <sha>`（记下这个 sha，tag 就打在它上面）

---

## Task 4: 打 tag 触发发布

**Files:** 无

- [ ] **Step 1: 确认没有别的 pipeline 在抢 runner（`concurrent=3`）**

Run:
```bash
TOK=$(cat ~/.gitlab-pat/pi-gw-nexus-$(date +%Y%m%d).token)
curl -s -H "PRIVATE-TOKEN: $TOK" "https://gitlab.ts.huntercat.cn/api/v4/projects?per_page=100&simple=true&membership=true" \
 | python3 -c "import sys,json;print('projects:',len(json.load(sys.stdin)))" 2>/dev/null
curl -s -H "PRIVATE-TOKEN: $TOK" "https://gitlab.ts.huntercat.cn/api/v4/projects/22/pipelines?per_page=3" \
 | python3 -c "import sys,json;print([(p['id'],p['status'],p['ref']) for p in json.load(sys.stdin)])"
```
Expected: 本仓无 running/pending（其它仓在跑不阻塞，只是排队）

- [ ] **Step 2: 在合并后的 main tip 打 annotated tag 并推送**

Run:
```bash
cd $WT
git fetch -q origin main
SHA=$(git rev-parse origin/main)
git tag -a v0.3.1 "$SHA" -m "gateway 首个 Nexus 制品（继承 framework-parent 0.3.1 版本）

坐标：cn.huntercat:lieshoucloud-gateway:0.3.1
仓库：https://nexus.ts.huntercat.cn/repository/maven-releases/
pin：framework v0.3.1 (d023687)
说明：版本继承 framework-parent，故与 framework 版本同号（见 spec R1/R2 与整改队列）"
git push -q origin v0.3.1
git ls-remote --tags origin v0.3.1
```
Expected: `git ls-remote` 两行 —— `refs/tags/v0.3.1`（tag 对象）与 `refs/tags/v0.3.1^{}` = 上一步的 `$SHA`

- [ ] **Step 3: 等 tag pipeline 完成**

Run:
```bash
TOK=$(cat ~/.gitlab-pat/pi-gw-nexus-$(date +%Y%m%d).token)
for i in $(seq 1 60); do
  P=$(curl -s -H "PRIVATE-TOKEN: $TOK" "https://gitlab.ts.huntercat.cn/api/v4/projects/22/pipelines?ref=v0.3.1" | python3 -c "import sys,json;d=json.load(sys.stdin);print(d[0]['id'],d[0]['status'])" 2>/dev/null)
  echo "[$i] $P"; case "$P" in *success|*failed|*canceled) break;; esac; sleep 20
done
PID=$(curl -s -H "PRIVATE-TOKEN: $TOK" "https://gitlab.ts.huntercat.cn/api/v4/projects/22/pipelines?ref=v0.3.1" | python3 -c "import sys,json;print(json.load(sys.stdin)[0]['id'])")
curl -s -H "PRIVATE-TOKEN: $TOK" "https://gitlab.ts.huntercat.cn/api/v4/projects/22/pipelines/$PID/jobs" \
 | python3 -c "import sys,json;[print(j['name'],j['status'],j['web_url']) for j in json.load(sys.stdin)]"
```
Expected: `verify success` + `deploy success`；`deploy` 日志含 `发布 cn.huntercat:lieshoucloud-gateway:0.3.1 → Nexus maven-releases`

---

## Task 5: 验收（证据必须贴实测输出）

**Files:**
- Modify: `docs/superpowers/specs/2026-09-14-gateway-services-nexus-release-design.md`（回填 §10）

- [ ] **Step 1: 回读产物（存在性）**

Run:
```bash
BASE=https://nexus.ts.huntercat.cn/repository/maven-releases/cn/huntercat/lieshoucloud-gateway/0.3.1
curl -sI $BASE/lieshoucloud-gateway-0.3.1.jar | head -3
curl -sI $BASE/lieshoucloud-gateway-0.3.1.pom | head -3
```
Expected: 两条都是 `HTTP/1.1 200`；jar 的 `Content-Length` 为 59MB 量级（与 job 日志一致）

- [ ] **Step 2: 回读 pom 的 parent（消费方解析链）**

Run:
```bash
curl -s https://nexus.ts.huntercat.cn/repository/maven-releases/cn/huntercat/lieshoucloud-gateway/0.3.1/lieshoucloud-gateway-0.3.1.pom \
 | grep -A3 '<artifactId>framework-parent</artifactId>'
```
Expected: `<version>0.3.1</version>`（消费方能从 Nexus 解析 parent）

- [ ] **Step 3: 可消费性铁证（干净本地库，模拟下游）**

Run:
```bash
cd /tmp && JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn -B -ntp -Dmaven.repo.local=/tmp/m2-gw-acceptance dependency:get \
  -Dartifact=cn.huntercat:lieshoucloud-gateway:0.3.1 \
  -DremoteRepositories=nexus::default::https://nexus.ts.huntercat.cn/repository/maven-public/ 2>&1 | tail -5
ls -la /tmp/m2-gw-acceptance/cn/huntercat/lieshoucloud-gateway/0.3.1/ | head
```
Expected: `BUILD SUCCESS`，目录里出现 `lieshoucloud-gateway-0.3.1.jar`（59MB 量级）+ `.pom`

- [ ] **Step 4: Nexus 检索接口确认制品清单**

Run:
```bash
curl -s "https://nexus.ts.huntercat.cn/service/rest/v1/search?repository=maven-releases&name=lieshoucloud-gateway" \
 | python3 -c "
import sys,json
d=json.load(sys.stdin)
for i in d['items']:
    for a in i['assets']: print(a['path'], a['fileSize'], a['lastModified'])"
```
Expected: 列出 `0.3.1` 的 `.jar` / `.pom` 及 sha1/md5（`lastModified` 为本次发布时刻）

- [ ] **Step 5: 回填 spec §10 证据 + §9 队列状态，提交**

把 Step 1–4 的**实测输出**写进 `docs/superpowers/specs/2026-09-14-gateway-services-nexus-release-design.md` 的 §10 证据清单（勾选 + 贴结果），并更新 §9 队列（划掉"admin-services 待做"里属于本仓的部分不适用，保留；把"服务仓专用 Nexus 用户"保留）。

Run:
```bash
cd $WT
git add docs/superpowers/specs/2026-09-14-gateway-services-nexus-release-design.md
git commit -q -m "docs(release): 回填 v0.3.1 发布验收证据"
git log --oneline -3
```

- [ ] **Step 6: 吊销任务 PAT**

Run:
```bash
gitlab-pat revoke pi-gw-nexus-$(date +%Y%m%d)
```
Expected: 输出吊销结果；汇报只报元数据

---

## 回滚（照 spec §8）

| 阶段 | 回滚 |
| --- | --- |
| 未合并 | 关 MR、删分支、删项目 22 的 `NEXUS_DEPLOY_*` 与 protected tag（本批新增物） |
| 已合并未打 tag | `git revert <merge sha>` 后走新 MR |
| 已发布且致命 | **删不掉**（releases 不可变）：发 `0.3.2` 修正并在发布说明标注 `0.3.1` 作废；`v0.3.1` 受保护，**禁止 force push** |
| CI 出问题 | 删 `deploy` job 即回到 verify-only（现状） |

## 自检

- **Spec 覆盖**：§3.1（4 文件）↔ Task 1；§3.2（变量 + 保护）↔ Task 2；§3.3 + §6 Phase 3（tag）↔ Task 4；§6 Phase 3 验收 1–6 ↔ Task 5 Step 1–4；§6 Phase 4（回填）↔ Task 5 Step 5；§9 队列 ↔ spec 保留（本批不实现）
- **占位符扫描**：无 TBD/TODO；4 个文件的改动都给了完整内容/行级替换；所有命令都带期望输出
- **一致性**：`ver` 一律来自 `help:evaluate project.version`（= `0.3.1`）；tag 名一律 `v0.3.1`；模块名一律 `services/gateway`；Nexus 路径一律 `.../cn/huntercat/lieshoucloud-gateway/0.3.1/`；PAT 名一律 `pi-gw-nexus-<当日>`
