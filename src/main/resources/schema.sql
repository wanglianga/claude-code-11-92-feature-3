-- ============================================================================
-- 港口靠泊协同服务 - PostgreSQL 结构（幂等，可重复执行）
-- 时间一律使用 timestamp（本地模拟时间，不带时区）
-- ============================================================================

-- ---------- 账号（轻量角色登录，测试账号由 DataSeeder 写入） ----------
CREATE TABLE IF NOT EXISTS app_user (
    id           BIGSERIAL PRIMARY KEY,
    username     VARCHAR(64) UNIQUE NOT NULL,
    password     VARCHAR(128) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    role         VARCHAR(32)  NOT NULL
);

CREATE TABLE IF NOT EXISTS auth_token (
    token      VARCHAR(80) PRIMARY KEY,
    user_id    BIGINT NOT NULL REFERENCES app_user(id),
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

-- ---------- 基础模拟数据 ----------
CREATE TABLE IF NOT EXISTS ship (
    id         BIGSERIAL PRIMARY KEY,
    code       VARCHAR(40) UNIQUE NOT NULL,
    name       VARCHAR(128) NOT NULL,
    length_m   NUMERIC(6,2) NOT NULL,   -- 船长
    draft_m    NUMERIC(5,2) NOT NULL,   -- 满载吃水
    cargo_type VARCHAR(32)  NOT NULL    -- CONTAINER/BULK/TANKER/RORO/GENERAL
);

CREATE TABLE IF NOT EXISTS voyage (
    id                BIGSERIAL PRIMARY KEY,
    voyage_no         VARCHAR(40) UNIQUE NOT NULL,
    ship_id           BIGINT NOT NULL REFERENCES ship(id),
    agent_name        VARCHAR(128),
    eta_start         TIMESTAMP NOT NULL,
    eta_end           TIMESTAMP NOT NULL,
    work_duration_min INT NOT NULL,     -- 装卸作业时长（分钟）
    crane_count       INT NOT NULL,     -- 所需岸桥数
    status            VARCHAR(32) NOT NULL, -- SUBMITTED/ETA_UPDATED/WITHDRAWN/CONFIRMED
    eta_revision      INT NOT NULL DEFAULT 0,
    remark            TEXT,
    created_at        TIMESTAMP NOT NULL DEFAULT now(),
    updated_at        TIMESTAMP NOT NULL DEFAULT now(),
    CHECK (eta_end >= eta_start)
);
CREATE INDEX IF NOT EXISTS idx_voyage_status ON voyage(status);

CREATE TABLE IF NOT EXISTS berth (
    id             BIGSERIAL PRIMARY KEY,
    code           VARCHAR(40) UNIQUE NOT NULL,
    name           VARCHAR(128) NOT NULL,
    max_length_m   NUMERIC(6,2) NOT NULL,  -- 可容船长
    chart_depth_m  NUMERIC(5,2) NOT NULL,  -- 图示水深（理论基准面下）
    ukc_min_m      NUMERIC(4,2) NOT NULL DEFAULT 1.0 -- 最小吃水下净空
);

-- 泊位允许货类（显式名单）
CREATE TABLE IF NOT EXISTS berth_cargo_limit (
    berth_id   BIGINT NOT NULL REFERENCES berth(id) ON DELETE CASCADE,
    cargo_type VARCHAR(32) NOT NULL,
    PRIMARY KEY (berth_id, cargo_type)
);

-- 潮汐时段水位：slot [slot_start, slot_end) 内代表水位 height_m
CREATE TABLE IF NOT EXISTS tide_slot (
    id            BIGSERIAL PRIMARY KEY,
    slot_start    TIMESTAMP NOT NULL UNIQUE,
    slot_end      TIMESTAMP NOT NULL,
    height_m      NUMERIC(5,2) NOT NULL,
    revised       BOOLEAN NOT NULL DEFAULT FALSE,
    revision_reason TEXT,
    CHECK (slot_end > slot_start)
);

CREATE TABLE IF NOT EXISTS pilot (
    id             BIGSERIAL PRIMARY KEY,
    code           VARCHAR(40) UNIQUE NOT NULL,
    name           VARCHAR(128) NOT NULL,
    qualification  VARCHAR(40) NOT NULL,   -- DEEP / COASTAL
    max_length_m   NUMERIC(6,2) NOT NULL,
    max_draft_m    NUMERIC(5,2) NOT NULL,
    handover_min   INT NOT NULL DEFAULT 30 -- 交接时间
);

CREATE TABLE IF NOT EXISTS tug (
    id               BIGSERIAL PRIMARY KEY,
    code             VARCHAR(40) UNIQUE NOT NULL,
    name             VARCHAR(128) NOT NULL,
    max_length_m     NUMERIC(6,2) NOT NULL,
    max_draft_m      NUMERIC(5,2) NOT NULL,
    bollard_pull_t   NUMERIC(6,1),
    turnaround_min   INT NOT NULL DEFAULT 30 -- 周转时间
);

CREATE TABLE IF NOT EXISTS quay_crane (
    id             BIGSERIAL PRIMARY KEY,
    code           VARCHAR(40) UNIQUE NOT NULL,
    name           VARCHAR(128) NOT NULL,
    transfer_min   INT NOT NULL DEFAULT 20, -- 转场时间
    active         BOOLEAN NOT NULL DEFAULT TRUE
);

-- 人员/设备的工作区间（同一资源可有多段班次）
CREATE TABLE IF NOT EXISTS work_window (
    id            BIGSERIAL PRIMARY KEY,
    resource_type VARCHAR(16) NOT NULL,  -- PILOT/TUG/CRANE
    resource_id   BIGINT NOT NULL,
    start_at      TIMESTAMP NOT NULL,
    end_at        TIMESTAMP NOT NULL,
    CHECK (end_at > start_at)
);
CREATE INDEX IF NOT EXISTS idx_work_window_res
    ON work_window(resource_type, resource_id);

-- 模拟外部设备状态：故障/停用窗口
CREATE TABLE IF NOT EXISTS equipment_fault (
    id            BIGSERIAL PRIMARY KEY,
    resource_type VARCHAR(16) NOT NULL,  -- CRANE/TUG/PILOT
    resource_id   BIGINT NOT NULL,
    start_at      TIMESTAMP NOT NULL,
    end_at        TIMESTAMP NOT NULL,
    reason        TEXT,
    created_at    TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_fault_res
    ON equipment_fault(resource_type, resource_id, start_at, end_at);

-- ---------- 方案与分段 ----------
CREATE TABLE IF NOT EXISTS berth_plan (
    id               BIGSERIAL PRIMARY KEY,
    plan_no          VARCHAR(48) UNIQUE NOT NULL,
    batch_id         VARCHAR(40),
    voyage_id        BIGINT NOT NULL REFERENCES voyage(id),
    berth_id         BIGINT NOT NULL REFERENCES berth(id),
    status           VARCHAR(24) NOT NULL,
        -- CANDIDATE / STAGED / CONFIRMED / EXPIRED / CANCELLED / SUPERSEDED / REPLANNED
    feasible         BOOLEAN NOT NULL DEFAULT TRUE,
    strategy         VARCHAR(24),         -- TENTATIVE_HOLD / ATOMIC_CONFIRM
    -- 关键时间轴
    berth_from       TIMESTAMP,           -- S：靠泊/靠泊操纵开始
    pilot_start      TIMESTAMP,
    ops_start        TIMESTAMP,
    ops_end          TIMESTAMP,
    berth_to         TIMESTAMP,
    score            INT NOT NULL DEFAULT 0,
    wait_minutes     INT NOT NULL DEFAULT 0,
    reasons_json     TEXT,                -- 候选不可行原因 / 等待原因
    assignments_json TEXT,                -- 资源分配快照
    hold_expires_at  TIMESTAMP,
    staged_at        TIMESTAMP,
    confirmed_at     TIMESTAMP,
    released_at      TIMESTAMP,
    release_reason   VARCHAR(40),
    version_no       INT NOT NULL DEFAULT 1,
    replan_of_id     BIGINT,
    disruption_id    BIGINT,
    impact_json      TEXT,
    note             TEXT,
    created_at       TIMESTAMP NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_plan_voyage ON berth_plan(voyage_id);
CREATE INDEX IF NOT EXISTS idx_plan_status ON berth_plan(status);
CREATE INDEX IF NOT EXISTS idx_plan_batch ON berth_plan(batch_id);
-- 同一船次至多一个 CONFIRMED 方案（唯一确认结果）
CREATE UNIQUE INDEX IF NOT EXISTS ux_plan_one_confirmed
    ON berth_plan(voyage_id) WHERE status = 'CONFIRMED';

CREATE TABLE IF NOT EXISTS plan_segment (
    id            BIGSERIAL PRIMARY KEY,
    plan_id       BIGINT NOT NULL REFERENCES berth_plan(id) ON DELETE CASCADE,
    seq_no        INT NOT NULL,
    seg_type      VARCHAR(24) NOT NULL,
        -- PILOTAGE/TUG_INBOUND/BERTHING/CARGO_OP/TUG_OUTBOUND/UNBERTHING
    title         VARCHAR(80) NOT NULL,
    resource_type VARCHAR(16),
    resource_id   BIGINT,
    resource_code VARCHAR(40),
    start_at      TIMESTAMP NOT NULL,
    end_at        TIMESTAMP NOT NULL,
    status        VARCHAR(16) NOT NULL DEFAULT 'PLANNED',
        -- PLANNED/IN_PROGRESS/DONE/CANCELLED
    affected      BOOLEAN NOT NULL DEFAULT FALSE,
    affect_reason TEXT,
    record_id     BIGINT,
    CHECK (end_at >= start_at)
);
CREATE INDEX IF NOT EXISTS idx_segment_plan ON plan_segment(plan_id);
CREATE INDEX IF NOT EXISTS idx_segment_resource
    ON plan_segment(resource_type, resource_id, start_at, end_at);

-- 已完成作业记录（引航 / 装卸）：只增不改不删，新计划只能引用与延续
CREATE TABLE IF NOT EXISTS work_record (
    id              BIGSERIAL PRIMARY KEY,
    voyage_id       BIGINT NOT NULL,
    plan_id         BIGINT NOT NULL,
    segment_id      BIGINT,
    record_type     VARCHAR(24) NOT NULL,  -- PILOTAGE / CARGO_HANDLING
    operator_name   VARCHAR(128),
    work_minutes    INT NOT NULL DEFAULT 0,-- 本次完成的作业分钟数
    moved_units     INT NOT NULL DEFAULT 0,
    result_note     TEXT,
    started_at      TIMESTAMP,
    completed_at    TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_record_voyage ON work_record(voyage_id);

-- 作业记录不可变触发器、资源占用互斥触发器由 DbTriggerInitializer 以
-- JdbcTemplate 方式创建（避免脚本切分器与 plpgsql 美元引用冲突）。

-- ---------- 资源占用（暂留 TENTATIVE / 确认 FIRM 统一建模） ----------
-- 每种“可占用资源”一个锁父行，触发器内对该行 FOR UPDATE，
-- 把同一资源的并发占用串行化，再做区间重叠检查。
CREATE TABLE IF NOT EXISTS resource_lock (
    resource_key VARCHAR(80) PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS resource_occupation (
    id             BIGSERIAL PRIMARY KEY,
    plan_id        BIGINT NOT NULL,
    voyage_id      BIGINT NOT NULL,
    resource_type  VARCHAR(16) NOT NULL,   -- BERTH/PILOT/TUG/CRANE
    resource_id    BIGINT NOT NULL,
    resource_key   VARCHAR(80) NOT NULL,
    start_at       TIMESTAMP NOT NULL,     -- 已包含准备/交接/转场/周转缓冲
    end_at         TIMESTAMP NOT NULL,
    kind           VARCHAR(16) NOT NULL,   -- TENTATIVE / FIRM
    status         VARCHAR(16) NOT NULL,   -- ACTIVE / RELEASED
    released_at    TIMESTAMP,
    release_reason VARCHAR(40),
    created_at     TIMESTAMP NOT NULL DEFAULT now(),
    CHECK (end_at > start_at)
);
CREATE INDEX IF NOT EXISTS idx_occ_active_lookup
    ON resource_occupation(resource_type, resource_id, start_at, end_at)
    WHERE status = 'ACTIVE';
CREATE INDEX IF NOT EXISTS idx_occ_plan ON resource_occupation(plan_id);

-- 占用互斥触发器 fn_occupation_overlap_guard / trg_occupation_overlap
-- 同样由 DbTriggerInitializer 创建。

-- ---------- 事件 / 扰动 / 影响 ----------
CREATE TABLE IF NOT EXISTS plan_event (
    id          BIGSERIAL PRIMARY KEY,
    voyage_id   BIGINT,
    plan_id     BIGINT,
    event_type  VARCHAR(48) NOT NULL,
    message     TEXT,
    detail_json TEXT,
    created_at  TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_event_voyage ON plan_event(voyage_id);

CREATE TABLE IF NOT EXISTS disruption (
    id           BIGSERIAL PRIMARY KEY,
    dtype        VARCHAR(24) NOT NULL,    -- TIDE_REVISION / CRANE_FAULT
    ref_id       BIGINT,
    payload_json TEXT,
    summary      TEXT,
    created_at   TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS impact_item (
    id          BIGSERIAL PRIMARY KEY,
    disruption_id BIGINT REFERENCES disruption(id),
    plan_id     BIGINT,
    voyage_id   BIGINT,
    segment_id  BIGINT,
    severity    VARCHAR(16),             -- BLOCKER/WARNING
    code        VARCHAR(40),
    message     TEXT,
    start_at    TIMESTAMP,
    end_at      TIMESTAMP,
    handled     BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX IF NOT EXISTS idx_impact_disruption ON impact_item(disruption_id);

-- ============================================================================
-- feature-3：货量变更审计 + 岸桥/堆位/跨泊位窗口依赖 + 两种重排影响分析
-- 该域使用独立的“日间作业网络”固定样例（A/B/C/D 船，岸桥 C1/C2/C3），
-- 与晚间深水靠泊样例（VOY-A..E / QC-1..4）互不干扰。
-- ============================================================================

-- 舱口与原申报作业量（一个船次两个舱口）
CREATE TABLE IF NOT EXISTS cargo_hatch (
    id                         BIGSERIAL PRIMARY KEY,
    voyage_code                VARCHAR(24) NOT NULL,   -- A / B ...
    hatch_code                 VARCHAR(24) NOT NULL,    -- H1 / H2
    op_code                    VARCHAR(60),             -- 对应的未执行工序
    seq_no                     INT NOT NULL DEFAULT 0,
    title                      VARCHAR(160),
    berth_code                 VARCHAR(24),
    crane_code                 VARCHAR(24),
    yard_code                  VARCHAR(24),
    original_declared_minutes  INT NOT NULL DEFAULT 0,  -- 原申报作业分钟
    original_declared_units    INT NOT NULL DEFAULT 0,  -- 原申报货量（箱）
    unit_label                 VARCHAR(16) NOT NULL DEFAULT 'TEU',
    UNIQUE (voyage_code, hatch_code)
);

-- 堆场/堆位能力：planned_capacity 原计划容量，available_capacity 变更时实际可用
CREATE TABLE IF NOT EXISTS yard_stack (
    id                 BIGSERIAL PRIMARY KEY,
    code               VARCHAR(24) UNIQUE NOT NULL,
    name               VARCHAR(160) NOT NULL,
    berth_code         VARCHAR(24),
    planned_capacity   INT NOT NULL,
    available_capacity INT NOT NULL,
    unit_label         VARCHAR(16) NOT NULL DEFAULT 'TEU',
    capacity_note      TEXT
);

-- 工序节点（基线=已通知计划时间；分析只做 what-if 投影，不直接改写基线）
CREATE TABLE IF NOT EXISTS port_operation (
    id              BIGSERIAL PRIMARY KEY,
    op_code         VARCHAR(60) UNIQUE NOT NULL,
    voyage_code     VARCHAR(24) NOT NULL,
    seq_no          INT NOT NULL DEFAULT 0,
    title           VARCHAR(160) NOT NULL,
    op_kind         VARCHAR(28) NOT NULL,
        -- PILOT/BERTH/SHIFT_PREP/HATCH_OP/YARD_MOVE/TRANSFER/TRIM/UNBERTH/RORO/WINDOW
    berth_code      VARCHAR(24),
    resource_type   VARCHAR(16),           -- CRANE / YARD / BERTH / null
    resource_code   VARCHAR(40),           -- C1 / Y-A1 / B2 ...
    start_at        TIMESTAMP NOT NULL,    -- 基线（已通知）开始
    end_at          TIMESTAMP NOT NULL,    -- 基线（已通知）结束
    status          VARCHAR(16) NOT NULL DEFAULT 'PLANNED', -- PLANNED / DONE
    notified        BOOLEAN NOT NULL DEFAULT TRUE,
    detail_json     TEXT,
    CHECK (end_at >= start_at)
);
CREATE INDEX IF NOT EXISTS idx_pop_voyage ON port_operation(voyage_code, seq_no);
CREATE INDEX IF NOT EXISTS idx_pop_resource ON port_operation(resource_type, resource_code);

-- 工序依赖边：op_code 依赖 depends_on_op_code
CREATE TABLE IF NOT EXISTS operation_dependency (
    id                 BIGSERIAL PRIMARY KEY,
    op_code            VARCHAR(60) NOT NULL,
    depends_on_op_code VARCHAR(60) NOT NULL,
    dep_kind           VARCHAR(28) NOT NULL,
        -- PRECEDENCE / SHARED_CRANE / YARD_CAPACITY / CROSS_BERTH_WINDOW
    min_gap_minutes    INT NOT NULL DEFAULT 0,  -- FS 之间最小间隔（转场/缓冲）
    propagate          BOOLEAN NOT NULL DEFAULT TRUE, -- FALSE=仅追踪/判冲突，不自动推移
    detail             TEXT,
    UNIQUE (op_code, depends_on_op_code, dep_kind)
);
CREATE INDEX IF NOT EXISTS idx_opdep_op ON operation_dependency(op_code);
CREATE INDEX IF NOT EXISTS idx_opdep_on ON operation_dependency(depends_on_op_code);

-- feature-3 已完成装卸记录：只增不改不删（触发器 trg_handling_record_immutable）
CREATE TABLE IF NOT EXISTS handling_record (
    id             BIGSERIAL PRIMARY KEY,
    record_no      VARCHAR(48) UNIQUE NOT NULL,
    voyage_code    VARCHAR(24) NOT NULL,
    op_code        VARCHAR(60) NOT NULL,
    hatch_code     VARCHAR(24),
    operator_name  VARCHAR(128),
    work_minutes   INT NOT NULL DEFAULT 0,
    moved_units    INT NOT NULL DEFAULT 0,
    result_note    TEXT,
    started_at     TIMESTAMP,
    completed_at   TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_handling_voyage ON handling_record(voyage_code);

-- 货量变更审计：原申报 / 复测值 / 来源 / 时点，只增不改不删
CREATE TABLE IF NOT EXISTS cargo_change (
    id                            BIGSERIAL PRIMARY KEY,
    change_no                     VARCHAR(48) UNIQUE NOT NULL,
    batch_no                      VARCHAR(48),
    voyage_code                   VARCHAR(24) NOT NULL,
    hatch_code                    VARCHAR(24),
    op_code                       VARCHAR(60),
    metric                        VARCHAR(24) NOT NULL DEFAULT 'CARGO_WORKLOAD',
    original_value                NUMERIC(14,2),   -- 原申报货量
    remeasured_value              NUMERIC(14,2),   -- 复测货量
    delta_value                   NUMERIC(14,2),
    unit_label                    VARCHAR(16) DEFAULT 'TEU',
    original_declared_minutes     INT,             -- 原申报作业分钟
    revised_required_minutes      INT,             -- 复测后所需作业分钟
    delta_minutes                 INT,             -- 补增作业分钟（本题每舱 +60）
    original_declared_units       INT,
    remeasured_units              INT,
    source                        VARCHAR(200) NOT NULL, -- 来源（船代补报/水尺/理货）
    source_ref                    VARCHAR(120),
    observed_at                   TIMESTAMP NOT NULL,    -- 复测/来源时点
    recorded_by                   VARCHAR(64),
    reason                        TEXT,
    created_at                    TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_cargo_change_voyage ON cargo_change(voyage_code);
CREATE INDEX IF NOT EXISTS idx_cargo_change_batch ON cargo_change(batch_no);

-- 两种重排影响分析结果留痕（报告 JSON）
CREATE TABLE IF NOT EXISTS impact_report (
    id           BIGSERIAL PRIMARY KEY,
    report_no    VARCHAR(48) UNIQUE NOT NULL,
    batch_no     VARCHAR(48),
    mode         VARCHAR(24) NOT NULL,   -- DEPENDENCY_CHAIN / FULL_PORT
    choices_json TEXT,
    all_clear    BOOLEAN NOT NULL DEFAULT FALSE,
    report_json  TEXT NOT NULL,
    created_at   TIMESTAMP NOT NULL DEFAULT now()
);
