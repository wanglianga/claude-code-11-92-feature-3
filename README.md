# 港口靠泊协同服务（Berth Coordination）

船代提交船次（船长/吃水/货类/ETA 区间/作业时长/岸桥数），码头调度枚举并比较候选靠泊计划，
对 **泊位 + 引航员 + 拖轮 + 岸桥整组资源** 做暂留或原子确认；确认后的计划才下发引航、拖轮、岸桥班组。
系统在**整个靠泊操纵与装卸窗口**校验吃水下净空（UKC ≥ 1m）、货类限制、人员值班/交接、
设备工作区间/转场/周转，并提供潮汐修订、岸桥故障、改靠、ETA 迟到的影响分析与版本化重排；
已完成的引航/装卸记录在应用层与数据库触发器双层保证不可覆盖。

- 后端：Java（`--release 21`，已在预装 **JDK 25.0.1** 上构建运行）、Spring Boot 3.5.7、Spring JDBC、PostgreSQL
- 前端：`src/main/resources/static/` 原生 HTML/CSS/JS 单页（无需 Node 构建）
- 接口文档：springdoc-openapi，`/swagger-ui.html`
- 一键启动：`Dockerfile` + `docker-compose.yml`（PostgreSQL 18）

---

## 1. 题目场景与内置样例

固定样例时间为 **2026-11-26 晚**，重置接口/回放会反复恢复该数据：

- **B1 深水泊位**：可容船长 320m、图示水深 9.5m；深水船吃水 11.5m、UKC 要求 1m
  ⇒ 需要潮位 `h ≥ 11.5 + 1.0 − 9.5 = 3.0m`。
- 30 分钟一档的模拟潮汐中，**只有 S=20:00 这一个 15 分钟网格起点**能通过全窗口 UKC 校验
  （19:30 档潮位 2.8m 不够；21:30 档回落到 2.75m，20:15/20:30 起点也失败）。
- 该时段唯一有 DEEP 资质且值班的引航员 **PILOT-1 张远航**（值班 17:00–次日 02:00，交接 30m），
  唯一适配 300m/11.5m 船舶的拖轮 **TUG-1 港拖16**（周转 30m）；岸桥 QC-1~QC-4。
- **VOY-A 远集 A**：ETA `19:30–20:30`，作业 60m，需 2 台岸桥。
- **VOY-B 远集 B**：ETA `19:00–20:00`，已提交 20:00 靠泊，与 A 竞争同一组合。
- 另有 VOY-C/D/E 覆盖集装箱、滚装与“ETA 18–19 改 19–20”的开场故事。
- 次日 08:00 前后还有早潮窗口与 PILOT-3/TUG-3 早班，用于“等潮/等资源”的备选与重排。

---

## 2. 核心设计决定：何时占用整组靠泊资源

两种策略都已实现，并可用接口参数或回放对照：

| 策略 | 行为 | 优点 | 代价 |
| --- | --- | --- | --- |
| **`TENTATIVE_HOLD`（默认）** | 调度“暂存”候选时，即在同一事务写入泊位/引航/拖轮/岸桥的 `TENTATIVE` 占用，TTL 默认 120s；到期由 500ms 扫描器自动释放 | 确认后资源不会被抢 | ETA 尚宽时资源可能空等 → 用短 TTL + 一键取消控制 |
| **`ATOMIC_CONFIRM`** | 暂存只保存方案、**不占资源**；确认时在一个事务内重校全部约束并插入 `FIRM` 占用 | 资源周转率高 | 已讨论方案可能在最后一步被别人锁走，确认必须原子重校+锁定 |

暂存接口可用 `"holdResources": false/true` 临时选择策略；`app.planning.hold-on-stage` 改全局默认。

**唯一性保证（不依赖应用锁）**：所有占用统一落在 `resource_occupation` 表。
PostgreSQL 触发器 `trg_occupation_overlap` 在写入前对 `resource_lock` 中该资源的锁父行 `SELECT … FOR UPDATE`，
再检查半开区间重叠；并发确认同一组合时恰好一个事务成功，另一个收到 `23P01/RESOURCE_OVERLAP` → HTTP 409。
另有部分唯一索引保证同一船次至多一个 `CONFIRMED` 方案。

---

## 3. 约束与时间轴模型

设 `S = berth_from`（靠泊操纵开始）：

```
引航段    [S-45, S+15]            占用区间前后各加 handover_min 交接缓冲
拖轮靠泊  [S-15, S+30]            占用尾部加 turnaround_min 周转
靠泊操纵  [S, S+30]               泊位占用开始
装卸作业  [S+30, S+30+duration]   每台岸桥占用至 opsEnd + transfer_min 转场
拖轮离泊  [opsEnd, opsEnd+30]     同样加周转缓冲
离泊段    [opsEnd, opsEnd+30]     泊位占用结束 = opsEnd+30
```

- **UKC**：对 `[S, opsEnd]` 与每个重叠潮汐档校验 `chart_depth + tide_height − draft ≥ ukc_min(=1.0)`，并检查潮汐覆盖完整性。
- 泊位：船长 ≤ `max_length_m`，货类必须在 `berth_cargo_limit` 名单内。
- 引航员：船长/吃水/资质（深水大船需 `DEEP`）+ 占用区间（含交接）完整落在某个 `work_window`。
- 拖轮：船长/吃水适配 + 靠/离两区间（含周转）均不冲突、在班。
- 岸桥：需要 N 台不同岸桥，每台工作窗覆盖 `[opsStart, opsEnd+transfer]`，且无占用/故障重叠。
- 候选生成：15 分钟网格，从 `etaStart` 枚举到 `etaEnd + 960m`；ETA 窗口内不可行点全部保留并给出
  结构化原因（`UKC_VIOLATION`、`*_HELD_TENTATIVE`、`*_BOOKED_FIRM`、`PILOT_OFF_DUTY`、
  `INSUFFICIENT_CRANES` 等，含占用方船次/方案/暂留到期时间），可行点按时间先后取前 6。

---

## 4. 目录结构

```
pom.xml / Dockerfile / docker-compose.yml
scripts/replay.sh                 # 命令行一键验证（登录 + /api/replays/full + 断言）
src/main/resources/
  application.yml                 # DB 环境变量、TTL、候选网格配置
  schema.sql                      # 表/索引（幂等）；触发器由 Java 下发
  static/                         # 单页前端 index.html / app.js / styles.css
src/main/java/com/port/berth/
  PortBerthApplication.java
  config/                         # 鉴权拦截器、CORS、OpenAPI、PG 触发器初始化
  domain/                         # 枚举、Catalog 记录、Eval、ConstraintMath
  repo/                           # CatalogDao / PlanDao（JdbcTemplate + 行映射）
  service/                        # PlanningEngine / PlanService / HoldSweeper
                                  # ExecutionService / DisruptionService / ReplanService
                                  # ReplayService / SeedService / AuthService
  web/                            # REST 控制器、全局异常处理
src/test/java/.../                # 4 个纯单元测试（约束数学 + 样例唯一窗口规格）
```

---

## 5. 一键启动

### 5.1 Docker Compose（推荐，干净环境）

```bash
cd /workspace/92-feature-3
docker compose up --build
```

- 应用：<http://localhost:8080>（单页操作界面）
- Swagger UI：<http://localhost:8080/swagger-ui.html>，OpenAPI JSON：<http://localhost:8080/v3/api-docs>
- PostgreSQL 18：容器内 `db:5432`，宿主机映射 **5433**（避免与本机 5432 冲突），
  库/用户/密码 `berth/berth/berth123`，数据卷 `pgdata`
- 镜像构建内已配置 `docker/settings-docker.xml` 走阿里云 Maven 源

> 本任务容器内没有 Docker CLI/守护进程，因此镜像构建与 `docker compose up` 未在本会话实际执行。
> `Dockerfile` 为标准多阶段构建（Maven 构建阶段会从最新源码编译 feature-3），`docker-compose.yml`
> 沿用已通过 YAML 校验的基线；本会话已把 `Dockerfile`/`docker-compose.yml`/`scripts/*.sh` 统一为
> LF 行尾，避免 CRLF 导致 bash 无法执行、Dockerfile 续行异常。应用本身已用等价方式
> （JDK 25 + 可执行 jar + 真实 PostgreSQL 18，含**另建全新空库 `ccdev_f3` 首启**）完整验证，见第 7 节。

### 5.2 本地开发（使用任务容器预装运行时）

PostgreSQL 18 已在 `127.0.0.1:5432` 运行，开发库/账号 `ccdev/ccdev`：

```bash
# 连通性检查
/opt/postgresql-18/bin/psql "postgresql://ccdev:ccdev@127.0.0.1:5432/ccdev" -c 'select 1'

# 若数据库进程未运行，可尝试：
# /opt/postgresql-18/bin/pg_ctl -D /tmp/cc-services/postgres -l /tmp/pg.log start

cd /workspace/92-feature-3
export JAVA_HOME=/opt/jdk-25
mvn spring-boot:run
# 或：mvn package && /opt/jdk-25/bin/java -jar target/berth-coordination.jar
```

零额外配置即可启动：默认连接 `jdbc:postgresql://127.0.0.1:5432/ccdev`（`ccdev/ccdev`），
首次启动自动执行 `schema.sql`、创建两个 PL/pgSQL 触发器并播种样例数据（幂等）。
也可用环境变量覆盖：`DB_HOST/DB_PORT/DB_NAME/DB_USER/DB_PASSWORD/PORT/HOLD_ON_STAGE/HOLD_TTL_SECONDS`。

---

## 6. 测试账号

| 用户名 | 密码 | 角色 | 能做什么 |
| --- | --- | --- | --- |
| `agent` | `agent123` | AGENT 船代 | 提交船次、补报 ETA、撤回授权 |
| `dispatch` | `dispatch123` | DISPATCHER 调度 | 生成候选、暂存/确认/取消、扰动录入、改靠/重排、回放、重置 |
| `pilot` | `pilot123` | TEAM 引航班组 | 看板、开工/完成/登记作业 |
| `tug` | `tug123` | TEAM 拖轮班组 | 同上 |
| `crane` | `crane123` | TEAM 岸桥班组 | 同上（含部分装卸量登记） |
| `admin` | `admin123` | ADMIN | 全部权限 |

登录：

```bash
TOKEN=$(curl -s -X POST http://127.0.0.1:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"dispatch","password":"dispatch123"}' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')
```

除 `POST /api/auth/login` 与 `GET /api/meta` 外，`/api/**` 都需要
`Authorization: Bearer <token>`；角色不足返回 403。Swagger 中点 **Authorize** 填 token。

---

## 7. 本题验证方式与实际验证结论

### 7.1 自动化验证（本会话实际执行）

```bash
mvn test
# Tests run: 4, Failures: 0, Errors: 0, Skipped: 0  → BUILD SUCCESS

mvn package
# 生成 target/berth-coordination.jar（约 28 MB，Spring Boot fat jar）
```

```bash
# 启动 jar 后执行（默认 http://127.0.0.1:8080）
bash scripts/replay.sh
# ==> 断言：75/75 通过；allPassed=True
# ALL_REPLAY_CHECKS_PASSED
```

该脚本真实登录、调用 `POST /api/replays/full`（约 10–12 秒，含真实 2s TTL 等待、
500ms 扫描释放与双线程并发确认竞速），递归统计全部断言并以退出码反映结果。
其中 **feature-3 货量变更双轨影响块 29/29**（也可单独 `POST /api/replays/cargo-change`）。

此外实际执行并通过的验证：

- **全新空库验证**：另建空数据库、以 `DB_NAME=... PORT=8081 java -jar` 启动，
  建表/触发器/播种一次成功，回放同样全部通过（验证了 Compose 首次启动的初始化路径）。
- **HTTP 权限/业务冒烟 8/8**：匿名 401、公开 meta、船代提交船次、船代暂存 403、
  VOY-E ETA 补报状态变 `ETA_UPDATED`、TEAM 看板 200、错误密码 400、OpenAPI 200。
- 前端 `node --check app.js` 通过；所有按钮处理函数均有定义；`/`、`/app.js`、`/styles.css`、
  `/swagger-ui/index.html` 均实际返回 200。
- 回放中包含**数据库层自证不可变**：对 `work_record` 直接 `UPDATE/DELETE` 被触发器拒绝。
- **feature-3（本特性）实际执行并通过**：`POST /api/replays/cargo-change` → 29/29；
  `bash scripts/replay.sh` → **75/75**（原顺序/扰动 46 + 货量变更 29）。另实测：
  匿名 `GET /api/cargo/scenario` 401、TEAM 角色 `POST /api/cargo/changes` 403、
  AGENT 经 `/api/voyages/A/cargo-changes` 用别名字段（declared/measured/extraMinutes/measuredAt）
  录入成功并自动按 H1 解析 `opCode=A-H1`；`/swagger-ui/index.html`、`/v3/api-docs` 均 200，
  OpenAPI 中出现 13 条 `/api/cargo...`、`/api/voyages/{voyageCode}/cargo-changes` 与
  `/api/replays/cargo-change` 路径。前端 `node --check app.js` 通过，新标签 ⑥ 与 `runCargoBoth` 已随 `/app.js` 提供。

### 7.2 界面手工验证路径

1. 打开 <http://localhost:8080>，用 `dispatch/dispatch123` 登录（登录页可点账号芯片填充）。
2. **② 调度工作台** → 选 `VOY-A` → “生成/刷新候选计划”：可见唯一绿色可行卡
   `B1 20:00`，分配 `PILOT-1/TUG-1/QC-1/QC-2`；其余红色卡给出 UKC/值班等原因。
   - 设 TTL=2 秒点“暂存并暂留资源”，卡片变 `STAGED` 并显示到期时间；不确认，数秒后刷新可见 `EXPIRED`。
   - 对 `VOY-B` 生成候选，可见 20:00 卡被 A 的暂留/确认阻挡及结构化等待原因。
   - 可试“仅暂存不占资源”，再让 B 先确认，最后 A “直接原子确认”会收到 409。
3. **③ 班组看板**：只有 `CONFIRMED` 方案出现；可开工、登记部分作业量、完成分段。
4. **④ 模拟与扰动**：修订 21:00 潮位为 `2.20`、插入 QC-2 故障窗口、查看影响项；
   回到调度页对确认方案“扰动后重排”，或用确认卡上的“改靠/改时”。
5. **⑤ 一键回放验证**：三个按钮分别运行顺序集/扰动集/完整集，页面渲染每条断言与完整 JSON。
6. **① 船代申报**：可新建船次、对 VOY-E 把 ETA 18–19 补报为 19–20、撤回授权。

---

## 8. 一键回放到底核对了什么

`POST /api/replays/orderings`、`/api/replays/disruptions`、`/api/replays/cargo-change`、
`/api/replays/full`（DISPATCHER/ADMIN）每次先重置固定样例，再输出带断言与完整时间线的报告：

| 回放块 | 核对点（全部 PASS） |
| --- | --- |
| **顺序1：A 暂存后未确认** | A 的 B1 20:00 候选可行且选到 P1/T1/QC1/QC2；A `STAGED`；B 同时点不可行且原因含 `*_HELD_TENTATIVE`；B 强确认 409；TTL 到期 A `EXPIRED`、≥5 条占用全 `RELEASED`、扫描延迟 <3s；A 确认数 0；释放后 B 可行并成为唯一 `CONFIRMED` |
| **顺序2：A 暂留期内确认** | A `CONFIRMED` 且唯一；B 被 `*_BOOKED_FIRM` 阻挡；B 暂存/确认均 409；B 得到等待 >120m 的次日备选；B 确认数 0 |
| **顺序3：B 先确认** | B 直接原子确认成功；A 候选显示 FIRM 阻挡；A 暂存、最后一步确认均 409；确认计数 B=1/A=0 |
| **并发竞速** | 两线程同时确认 A/B 同一组合：恰好 1 个成功，库中 A/B 合计仅 1 个 `CONFIRMED`（触发器+资源锁行兜底） |
| **策略对照** | `holdResources=false` 时暂存零占用；B 先确认；A 事后确认 409（“最后一步失效”），并附两种策略权衡说明 |
| **潮汐修订** | 先写 1 条引航完成记录 + 1 条 15 分钟装卸台账；21:00 潮位 3.2→2.2 后分析出指向装卸段的 `UKC_VIOLATION`；重排模式 `RESCHEDULE`、次日 08:00 新方案 `CONFIRMED`、只排剩余 45m；2 条记录保留；DB 阻止 UPDATE/DELETE |
| **岸桥故障** | QC-2 20:45–22:30 故障生成 `CRANE_FAULT`；重排为 `SAME_SLOT_REASSIGN`，新分配含 QC-3 不含 QC-2；引航记录保留 |
| **改靠指令** | 对 VOY-D 下达改靠 B2↔B3：新版本确认且泊位变化；≥4 条 `BERTH_CHANGE` 逐段影响说明 |
| **ETA 迟到** | ETA 最早时间晚于计划靠泊点时生成 ≥4 条 `ETA_LATE`（引航/拖轮/靠泊/装卸），随后重排出 `CONFIRMED v2` |
| **feature-3 货量变更（29 项，详见第 9 节）** | 两舱审计四要素齐全；依赖链受影响恰为 `[A,B]`、A-H1 到 14:00、B-H1 14:20(+20)、B 13:30 换班 `NEEDS_DECISION`、C1 重叠 30m、Y-A1 缺口 40 TEU、共享岸桥扫描预警 C +20；全港受影响 `[A,B,C]`、C-H1 16:40(+20) 且有 `SECOND_ORDER_CRANE_CASCADE`；改派 C2 后 B 准点、C 保 16:20、集合缩回 `[A,B]`；4 已完成工序 +2 台账保留；基线 A-H1 不落库改写；DB 阻止 UPDATE/DELETE `handling_record` 与 `cargo_change` |

---

## 9. 货量变更与“依赖链 / 全港”双轨影响（feature-3）

独立的**日间作业网络**固定样例（`2026-11-26` 白天，船次代号就是 `A/B/C/D`，岸桥 `C1/C2/C3`，
与晚间深水样例 `VOY-A..E / QC-1..4` 互不干扰；重置/回放会一并重建）。

### 9.1 给定变更

船代把 **A 船 H1、H2 两个舱口各补增 60 分钟作业**（`POST /api/cargo/given-change` 幂等写入）：

| 舱口 | 原申报货量 | 复测货量 | 作业时长 | 来源 | 复测时点 |
| --- | --- | --- | --- | --- | --- |
| A/H1（C1，落 Y-A1） | 60 TEU / 150m | **80 TEU** | 150→210m（**+60**） | 船代补报＋中理货/水尺复测 `SRV-…-H1` | 10:05 |
| A/H2（C2，落 Y-A1） | 40 TEU / 135m | **60 TEU** | 135→195m（**+60**） | 同上 `SRV-…-H2` | 10:10 |

- 审计落在 `cargo_change` 表，**原申报 `original_value/original_declared_minutes`、复测值
  `remeasured_value/revised_required_minutes`、来源 `source(+source_ref)`、时点 `observed_at`
  与录入时间 `created_at`** 全部保存；该表有触发器 `trg_cargo_change_immutable`，只增不改不删。
- 堆位 `Y-A1` 原计划 **200 TEU，现仅剩 100（一半）**；A 复测后需 140 TEU ⇒ **缺口 40 TEU**。
- 依赖在 `operation_dependency` 中显式建边，四类：`PRECEDENCE`、`SHARED_CRANE`（共享岸桥）、
  `YARD_CAPACITY`（堆位）、`CROSS_BERTH_WINDOW`（C1 在 B2↔B1 间的跨泊位转场窗口，20m）。

### 9.2 两种重算的确切结果（回放 29 条断言锁定）

| 维度 | `DEPENDENCY_CHAIN` 资源依赖链重算 | `FULL_PORT` 全港重排 |
| --- | --- | --- |
| 评估/受影响船次 | 评估 **A、B**；受影响 **[A, B]**；链外 **C、D 已通知计划不动** | 评估 **A、B、C、D**；受影响 **[A, B, C]**；仅 D 不变 |
| A 船必移工序 | `A-H1` 10:30–**14:00**（完工 +60，C1 占到 14:00）；`A-H2`→**13:30**；`A-XFER-C1`→14:00–14:20；`A-TRIM`→14:00–14:30；`A-UNBERTH`→**14:30–15:00** | 同左 |
| B 船 | `B-PREP` 仍 **13:30–14:00** 但标 `NEEDS_DECISION`；`B-H1` 最早 **14:20**（+20，等 C1＋跨泊位 20m）；`B-UNBERTH` **16:50–17:20**（+20） | 同左 |
| 额外波及 | 共享岸桥下游扫描**不会漏掉 B**，并对链外 **C/C-H1 给出 `SHARED_CRANE_DOWNSTREAM_WATCH`（潜在 +20m）预警**，但不替 C 改计划 | 全局资源平衡坐实二阶冲突：**`C-H1` 16:20→16:40（+20）、`C-UNBERTH` +20**——无关 C 船 16:20 的已通知计划被改动 |
| 需人工选择的冲突 | ① `CRANE_PREP_OVERLAP`：C1 与 B 13:30 换班准备**重叠 30m**，选项 `WAIT_C1`（B +20，推荐）/ `REASSIGN_B_TO_C2`（B 准点）；② `YARD_CAPACITY_SHORTFALL`：缺口 40 TEU，选项 `OVERFLOW_YA2`（溢流 Y-A2 40 TEU，推荐）/ `RUSH_PICKUP`（12:00 前紧急疏港） | 同两项，**外加** ③ `SECOND_ORDER_CRANE_CASCADE`：B 顺延经 C1 连锁撞 C 船 16:20，选项 `SHIFT_C_LATER`（C +20，全局默认，改动无关船）/ `KEEP_C_NOTIFIED`（B 改 C2、保住 C 时刻） |
| 已完成可保留 | **4 个 DONE 工序**（引航/靠泊/两个上午批次）时间零移动，**2 条 `handling_record` 装卸台账原样保留**；DB 触发器拒绝 `UPDATE/DELETE` | 同左 |

**人工改派的实际效果**：在 `choices` 传 `{"CRANE_C1_PREP":"REASSIGN_B_TO_C2"}`
（或 GET `?crane=C2`、界面勾选“B 改派 C2”）后，`B-H1/B-PREP` 改用 C2，B-H1 **14:00 准点**、
C1×换班冲突消失；全港模式下 `C-H1` 也**保住 16:20**，受影响集合缩回 `[A,B]`、无二阶冲突。
分析全程是 **what-if 投影**：只写 `impact_report`，不更新 `port_operation` 基线
（回放会断言多次分析后库中 `A-H1` 仍是 10:30–13:00），更不触碰已完成记录。

### 9.3 怎么验证 / 调用

界面：用 `dispatch/dispatch123` 登录 → 标签 **⑥ 货量变更·双轨影响** →
“录入给定变更并计算两种结果”，可勾选“B 改派 C2”对照；页内可直接跑 feature-3 回放断言。

```bash
TOKEN=$(curl -s -X POST http://127.0.0.1:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"dispatch","password":"dispatch123"}' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')

# 一次拿两种结果 + 对照（该调用会幂等补录给定变更）
curl -s -X POST http://127.0.0.1:8080/api/cargo/analyze-both \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool

# 分别跑两种模式
curl -s "http://127.0.0.1:8080/api/cargo/analyze?mode=DEPENDENCY_CHAIN" -H "Authorization: Bearer $TOKEN"
curl -s "http://127.0.0.1:8080/api/cargo/analyze?mode=FULL_PORT"        -H "Authorization: Bearer $TOKEN"
# 人工选择 B 改 C2
curl -s "http://127.0.0.1:8080/api/cargo/analyze-both?crane=C2"         -H "Authorization: Bearer $TOKEN"
# 仅 feature-3 的 29 条断言
curl -s -X POST http://127.0.0.1:8080/api/replays/cargo-change \
  -H "Authorization: Bearer $TOKEN" | python3 -c 'import sys,json;d=json.load(sys.stdin);print(d["allPassed"],sum(c["pass"] for c in d["checks"]),"/",len(d["checks"]))'
```

自定义录入（AGENT/DISPATCHER，字段名宽松兼容）：

```bash
curl -s -X POST http://127.0.0.1:8080/api/voyages/A/cargo-changes \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"hatchCode":"H1","declaredValue":60,"measuredValue":88,
       "extraMinutes":80,"source":"SGS 水尺复测","measuredAt":"2026-11-26T10:40:00"}'
```

主要读取端点：`GET /api/cargo/scenario`（场景总览）、`/api/cargo/dependencies?voyageCode=A`
（依赖图与岸桥/堆位/跨泊位分组）、`/api/cargo/changes?batchNo=…`、`/api/cargo/reports[/{id}]`。

---

## 10. 主要 HTTP API

| 方法/路径 | 角色 | 说明 |
| --- | --- | --- |
| `POST /api/auth/login` | 公开 | 登录拿 token |
| `GET /api/meta` | 公开 | 船舶/泊位/潮汐/人员/设备/故障/演示账号 |
| `GET/POST /api/voyages` | 登录 / AGENT+ | 船次列表/提交 |
| `GET /api/voyages/{id}` | 登录 | 船次 + 全部方案详情 + 记录 + 事件 |
| `PATCH /api/voyages/{id}/eta` | AGENT+ | 补报 ETA；若有确认方案同时做 ETA 迟到影响分析 |
| `POST /api/voyages/{id}/withdraw` | AGENT+ | 撤回授权并释放该船全部资源 |
| `POST /api/voyages/{id}/candidates/generate` | DISPATCHER+ | 枚举候选并落库 |
| `GET /api/plans`、`GET /api/plans/{id}` | 登录 | 方案/分段/原因/分配 |
| `POST /api/plans/{id}/stage` | DISPATCHER+ | body：`{"holdResources":true,"holdTtlSeconds":120}` |
| `POST /api/plans/{id}/confirm` | DISPATCHER+ | 事务内重校 + 原子锁定 FIRM |
| `POST /api/plans/{id}/cancel` | DISPATCHER+ | 取消暂存并释放 |
| `POST /api/segments/{id}/start|complete|log` | TEAM/DISPATCHER | 开工/完成/部分作业台账 |
| `GET /api/teams/board` | 登录 | 仅 CONFIRMED 方案的引航/拖轮/岸桥/泊位看板 |
| `PUT /api/simulation/tides/{slotId}` | DISPATCHER+ | 修订潮汐并分析 |
| `POST /api/simulation/crane-faults` | DISPATCHER+ | 插入设备故障窗口并分析 |
| `POST /api/plans/{id}/replan` | DISPATCHER+ | 扰动重排（默认自动确认） |
| `POST /api/plans/{id}/change-berth` | DISPATCHER+ | 改靠/改时指令 |
| `GET /api/disruptions[/{id}]` | 登录 | 扰动与影响项 |
| `GET /api/cargo/scenario` | 登录 | feature-3 日间场景：舱口/堆位/工序/依赖/给定变更草稿 |
| `GET /api/cargo/dependencies[?voyageCode=A]` | 登录 | 工序依赖图 + 岸桥/堆位/跨泊位窗口依赖分组 |
| `GET /api/cargo/changes[?batchNo=]`、`GET /api/voyages/{A}/cargo-changes` | 登录 | 货量变更审计（原申报/复测/来源/时点） |
| `POST /api/cargo/changes`、`POST /api/voyages/{code}/cargo-changes` | AGENT+ | 录入变更（宽松字段，支持 `changes/items` 批量） |
| `POST /api/cargo/given-change` | AGENT+ | 幂等写入 A 两舱各 +60m 的题目给定变更 |
| `POST/GET /api/cargo/analyze`（别名 `/api/cargo/impact`） | 登录 | `body/query`：`batchNo`、`mode=DEPENDENCY_CHAIN\|FULL_PORT`、`choices` |
| `POST/GET /api/cargo/analyze-both`（别名 `/api/cargo/impact-both`） | 登录 | 一次返回两种结果 + 对照；GET 支持 `?crane=C2` |
| `POST /api/cargo/analyze-given`、`GET /api/cargo/reports[/{id}]` | 登录 | 补录给定变更后分析 / 历史报告 |
| `POST /api/replays/orderings|disruptions|cargo-change|full` | DISPATCHER+ | 一键回放（`full` 含全部，当前 75 条断言） |
| `POST /api/admin/reset` | DISPATCHER+ | 清空并恢复固定样例（含 feature-3 网络） |

---

## 11. 配置与运维说明

- `app.planning.hold-on-stage`、`app.planning.default-hold-ttl-seconds`、
  `app.planning.candidate-step-minutes`、`app.planning.max-wait-minutes` 见 `application.yml`，
  均可用同名环境变量覆盖（`HOLD_ON_STAGE`、`HOLD_TTL_SECONDS` …）。
- `HoldSweeper` 每 500ms 扫描 `STAGED` 且到期的方案，释放其 `TENTATIVE` 占用并写 `HOLD_EXPIRED` 事件。
- `ccdev/ccdev` 是任务容器的隔离开发账号，**不要直接作为生产配置**；Compose 使用独立的 `berth` 库与密码。
- 服务不依赖 Redis/港口实机/第三方账号；外部潮汐与设备状态全部来自库内模拟表和模拟录入接口。
