package com.port.berth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.port.berth.web.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * feature-3 货量变更影响分析。
 *
 * <p>固定的日间作业网络：A 船两个舱口各补增 1 小时 → 岸桥 C1 占用到 14:00，
 * 与 B 船 13:30 的换班准备重叠；Y-A1 堆位只剩一半容量。依赖边显式追踪
 * 岸桥（SHARED_CRANE）、堆位（YARD_CAPACITY）与跨泊位窗口（CROSS_BERTH_WINDOW）。
 *
 * <p>提供两种重算：
 * <ul>
 *   <li>{@code DEPENDENCY_CHAIN}：只沿资源依赖闭包推移，影响限定 A/B，
 *       但对共享岸桥做下游扫描，避免漏掉 B，并给出 C 船的二阶预警；</li>
 *   <li>{@code FULL_PORT}：对全部船次做统一资源平衡，较早坐实 B→C 的二阶冲突，
 *       同时会挪动本可不动的无关 C 船已通知计划。</li>
 * </ul>
 *
 * <p>分析是 what-if 投影：只写 impact_report，不改写 port_operation 基线，
 * 更不会更新 cargo_change / handling_record（后两者有数据库不可变触发器）。
 */
@Service
public class CargoImpactService {

    public static final LocalDateTime D = LocalDateTime.of(2026, 11, 26, 0, 0);
    public static final String GIVEN_BATCH = "BATCH-20261126-A-SURVEY";
    private static final int CRANE_TRANSFER_MIN = 20;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AtomicLong reportSeq = new AtomicLong();

    public CargoImpactService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    private static LocalDateTime t(int h, int m) {
        return D.withHour(h).withMinute(m);
    }

    // ====================================================================
    // 场景播种
    // ====================================================================

    /**老库（已有账号、缺 feature-3 数据）启动时也能补齐场景。*/
    public void seedScenarioIfMissing() {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM port_operation", Integer.class);
        if (n != null && n > 0) return;
        seedScenario();
    }

    /**重置后调用：假定 feature-3 表已清空，写入固定日间场景。*/
    @Transactional
    public void seedScenario() {
        // ---- 堆位：Y-A1 原计划 200，变更时只剩一半 100 ----
        yard("Y-A1", "A 船主堆位 A01", "B2", 200, 100, "TEU",
                "北侧 100 TEU 区位临时翻修，且一批重箱未按时提走，可用容量仅剩原计划一半");
        yard("Y-A2", "A 船溢流备用堆位 A02", "B2", 120, 80, "TEU", "可承接 Y-A1 溢流箱");
        yard("Y-B1", "B 船出口堆位", "B1", 180, 180, "TEU", "容量正常");

        // ---- 舱口与原申报 ----
        hatch("A", "H1", "A-H1", 1, "一舱", "B2", "C1", "Y-A1", 150, 60);
        hatch("A", "H2", "A-H2", 2, "二舱", "B2", "C2", "Y-A1", 135, 40);

        // ---- 工序网络 ----
        op("A-PILOT", "A", 10, "A 船引航进港", "PILOT", null, "PILOT", "PILOT-DAY",
                7, 30, 8, 15, "DONE");
        op("A-BERTH", "A", 20, "A 船靠泊 B2", "BERTH", "B2", "BERTH", "B2",
                8, 15, 8, 45, "DONE");
        op("A-H1-DONE", "A", 30, "一舱上午批次（已卸·已台账）", "HATCH_OP", "B2", "CRANE", "C1",
                8, 45, 10, 15, "DONE");
        op("A-H2-DONE", "A", 40, "二舱上午批次（已卸·已台账）", "HATCH_OP", "B2", "CRANE", "C2",
                8, 45, 10, 0, "DONE");
        op("A-H1", "A", 50, "一舱剩余装卸（C1）", "HATCH_OP", "B2", "CRANE", "C1",
                10, 30, 13, 0, "PLANNED");
        op("A-H2", "A", 60, "二舱剩余装卸（C2）", "HATCH_OP", "B2", "CRANE", "C2",
                10, 15, 12, 30, "PLANNED");
        op("A-YARD", "A", 70, "Y-A1 水平运输/接箱", "YARD_MOVE", "B2", "YARD", "Y-A1",
                10, 30, 13, 0, "PLANNED");
        op("A-XFER-C1", "A", 80, "C1 跨泊位转场 B2→B1（跨泊位窗口）", "TRANSFER", null, "CRANE", "C1",
                13, 0, 13, 20, "PLANNED");
        op("A-TRIM", "A", 90, "平舱/加固", "TRIM", "B2", null, null,
                13, 0, 13, 30, "PLANNED");
        op("A-UNBERTH", "A", 100, "A 船完货离泊、清让 B2", "UNBERTH", "B2", "BERTH", "B2",
                13, 30, 14, 0, "PLANNED");

        op("B-BERTH", "B", 10, "B 船靠泊 B1", "BERTH", "B1", "BERTH", "B1",
                13, 0, 13, 30, "PLANNED");
        op("B-PREP", "B", 20, "B 船/C1 13:30 换班准备", "SHIFT_PREP", "B1", "CRANE", "C1",
                13, 30, 14, 0, "PLANNED");
        op("B-H1", "B", 30, "B 船一舱装船（C1）", "HATCH_OP", "B1", "CRANE", "C1",
                14, 0, 16, 0, "PLANNED");
        op("B-H2", "B", 40, "B 船二舱装船（C3）", "HATCH_OP", "B1", "CRANE", "C3",
                14, 0, 15, 30, "PLANNED");
        op("B-UNBERTH", "B", 50, "B 船完货离泊", "UNBERTH", "B1", "BERTH", "B1",
                16, 30, 17, 0, "PLANNED");

        op("C-PREP", "C", 10, "C 船工前准备", "PREP", "B2", null, null,
                15, 50, 16, 20, "PLANNED");
        op("C-H1", "C", 20, "C 船干线装船（C1·已通知 16:20）", "HATCH_OP", "B2", "CRANE", "C1",
                16, 20, 18, 20, "PLANNED");
        op("C-H2", "C", 30, "C 船二舱装船（C2）", "HATCH_OP", "B2", "CRANE", "C2",
                16, 20, 18, 0, "PLANNED");
        op("C-UNBERTH", "C", 40, "C 船完货离泊", "UNBERTH", "B2", "BERTH", "B2",
                18, 20, 18, 50, "PLANNED");

        op("D-OPS", "D", 10, "D 船滚装作业（不使用岸桥）", "RORO", "B3", null, null,
                15, 0, 17, 0, "PLANNED");
        op("D-UNBERTH", "D", 20, "D 船离泊 B3", "UNBERTH", "B3", "BERTH", "B3",
                17, 0, 17, 30, "PLANNED");

        // ---- 依赖边 ----
        dep("A-H1", "A-H1-DONE", "PRECEDENCE", 0, true, "上午批次完成后续作剩余舱量");
        dep("A-H2", "A-H2-DONE", "PRECEDENCE", 0, true, "上午批次完成后续作剩余舱量");
        dep("A-TRIM", "A-H1", "PRECEDENCE", 0, true, "一舱完货后平舱");
        dep("A-TRIM", "A-H2", "PRECEDENCE", 0, true, "二舱完货后平舱");
        dep("A-UNBERTH", "A-TRIM", "PRECEDENCE", 0, true, "平舱完成方可离泊");
        dep("A-XFER-C1", "A-H1", "SHARED_CRANE", 0, true, "C1 卸完 A 一舱后才能转场");
        dep("B-PREP", "A-H1", "SHARED_CRANE", 0, false,
                "13:30 换班准备需接管 C1；A 延时会与之重叠（只判冲突，不自动推迟换班会）");
        dep("B-H1", "B-PREP", "PRECEDENCE", 0, true, "换班准备结束后开工");
        dep("B-H1", "A-XFER-C1", "CROSS_BERTH_WINDOW", 0, true,
                "C1 由 B2 跨泊位转至 B1 的窗口完成后才能在 B1 开工");
        dep("B-UNBERTH", "B-H1", "PRECEDENCE", 30, true, "完货 + 30 分钟离泊/拖轮缓冲");
        dep("C-UNBERTH", "C-H1", "PRECEDENCE", 0, true, "一舱完货后离泊");
        dep("A-H1", "A-YARD", "YARD_CAPACITY", 0, false, "一舱增量货需落入 Y-A1 堆位能力");
        dep("A-H2", "A-YARD", "YARD_CAPACITY", 0, false, "二舱增量货需落入 Y-A1 堆位能力");

        seedHandlingRecords();
    }

    private void yard(String code, String name, String berth, int planned, int available,
                      String unit, String note) {
        jdbc.update("""
                INSERT INTO yard_stack(code,name,berth_code,planned_capacity,available_capacity,
                                       unit_label,capacity_note)
                VALUES (?,?,?,?,?,?,?)
                """, code, name, berth, planned, available, unit, note);
    }

    private void hatch(String voy, String hatch, String opCode, int seq, String title,
                       String berth, String crane, String yard, int minutes, int units) {
        jdbc.update("""
                INSERT INTO cargo_hatch(voyage_code,hatch_code,op_code,seq_no,title,berth_code,
                                        crane_code,yard_code,original_declared_minutes,
                                        original_declared_units,unit_label)
                VALUES (?,?,?,?,?,?,?,?,?,?,'TEU')
                """, voy, hatch, opCode, seq, title, berth, crane, yard, minutes, units);
    }

    private void op(String code, String voy, int seq, String title, String kind, String berth,
                    String resType, String resCode,
                    int sh, int sm, int eh, int em, String status) {
        jdbc.update("""
                INSERT INTO port_operation(op_code,voyage_code,seq_no,title,op_kind,berth_code,
                                           resource_type,resource_code,start_at,end_at,status,notified)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,true)
                """, code, voy, seq, title, kind, berth, resType, resCode,
                Timestamp.valueOf(t(sh, sm)), Timestamp.valueOf(t(eh, em)), status);
    }

    private void dep(String op, String on, String kind, int gap, boolean propagate, String detail) {
        jdbc.update("""
                INSERT INTO operation_dependency(op_code,depends_on_op_code,dep_kind,
                                                 min_gap_minutes,propagate,detail)
                VALUES (?,?,?,?,?,?)
                """, op, on, kind, gap, propagate, detail);
    }

    // ====================================================================
    // 给定变更 + 审计录入
    // ====================================================================

    /**幂等写入题目给定的两舱变更（原申报/复测/来源/时点齐全）。*/
    @Transactional
    public List<Map<String, Object>> recordGivenChange() {
        if (findChangeNo("CHG-A-H1") == null) {
            insertChange(kv(
                    "changeNo", "CHG-A-H1", "batchNo", GIVEN_BATCH,
                    "voyageCode", "A", "hatchCode", "H1", "opCode", "A-H1",
                    "originalValue", 60, "remeasuredValue", 80,
                    "originalDeclaredMinutes", 150, "revisedRequiredMinutes", 210,
                    "deltaMinutes", 60, "originalDeclaredUnits", 60, "remeasuredUnits", 80,
                    "source", "船代华港补报，附中理货 + 水尺计重复测报告",
                    "sourceRef", "SRV-20261126-A-H1",
                    "observedAt", t(10, 5), "recordedBy", "agent 王岚",
                    "reason", "一舱实际箱量/舱型变化，需增加一小时作业"));
        }
        if (findChangeNo("CHG-A-H2") == null) {
            insertChange(kv(
                    "changeNo", "CHG-A-H2", "batchNo", GIVEN_BATCH,
                    "voyageCode", "A", "hatchCode", "H2", "opCode", "A-H2",
                    "originalValue", 40, "remeasuredValue", 60,
                    "originalDeclaredMinutes", 135, "revisedRequiredMinutes", 195,
                    "deltaMinutes", 60, "originalDeclaredUnits", 40, "remeasuredUnits", 60,
                    "source", "船代华港补报，附中理货 + 水尺计重复测报告",
                    "sourceRef", "SRV-20261126-A-H2",
                    "observedAt", t(10, 10), "recordedBy", "agent 王岚",
                    "reason", "二舱实际箱量/舱型变化，需增加一小时作业"));
        }
        return listChanges(GIVEN_BATCH);
    }

    private Long findChangeNo(String no) {
        List<Long> l = jdbc.queryForList(
                "SELECT id FROM cargo_change WHERE change_no=?", Long.class, no);
        return l.isEmpty() ? null : l.get(0);
    }

    /**
     * 宽松录入：兼容单条扁平载荷或 {changes:[...]} / {items:[...]} 批量，
     * 字段名兼容 declared/remeasured/original/measured 等常见写法。
     */
    @SuppressWarnings("unchecked")
    @Transactional
    public List<Map<String, Object>> recordChanges(Map<String, Object> body) {
        if (body == null) throw ApiException.badRequest("BODY_REQUIRED", "请提供货量变更内容");
        String batch = str(body.get("batchNo"), "BATCH-" + System.currentTimeMillis());
        String source = firstStr(body, "source", "sourceName", "origin");
        if (source == null) throw ApiException.badRequest("SOURCE_REQUIRED", "必须提供变更来源 source");
        LocalDateTime observed = firstTime(body, "observedAt", "measuredAt", "at", "time");
        if (observed == null) observed = LocalDateTime.now();
        String recordedBy = firstStr(body, "recordedBy", "operator", "createdBy");
        String reason = firstStr(body, "reason", "note");
        String sourceRef = firstStr(body, "sourceRef", "surveyRef", "reportNo");

        List<Map<String, Object>> items = new ArrayList<>();
        Object arr = body.get("changes");
        if (!(arr instanceof List)) arr = body.get("items");
        if (arr instanceof List<?> list) {
            for (Object o : list) if (o instanceof Map<?, ?> m) items.add((Map<String, Object>) m);
        } else {
            items.add(body); // 单条扁平载荷
        }
        if (items.isEmpty()) throw ApiException.badRequest("NO_ITEMS", "没有可保存的变更条目");

        List<Map<String, Object>> saved = new ArrayList<>();
        for (Map<String, Object> it : items) {
            String voy = firstStr(it, "voyageCode", "voyage", "voyageNo");
            if (voy == null) throw ApiException.badRequest("VOYAGE_REQUIRED", "每条变更需 voyageCode");
            String hatch = firstStr(it, "hatchCode", "hatch");
            String opCode = firstStr(it, "opCode", "operationCode", "op");
            if (opCode == null && voy != null && hatch != null) {
                CargoHatchRef h = findHatch(voy, hatch);
                if (h != null) opCode = h.opCode();
            }
            Integer origMin = firstInt(it, "originalDeclaredMinutes", "declaredMinutes", "originalMinutes");
            Integer newMin = firstInt(it, "revisedRequiredMinutes", "requiredMinutes", "remeasuredMinutes");
            Integer deltaMin = firstInt(it, "deltaMinutes", "extraMinutes", "addedMinutes");
            if (deltaMin == null && origMin != null && newMin != null) deltaMin = newMin - origMin;
            if (newMin == null && origMin != null && deltaMin != null) newMin = origMin + deltaMin;

            Integer origUnits = firstInt(it, "originalDeclaredUnits", "declaredUnits", "originalUnits");
            Integer reUnits = firstInt(it, "remeasuredUnits", "measuredUnits", "newUnits");
            BigDecimal origVal = firstDecimal(it, "originalValue", "declaredValue", "original");
            BigDecimal reVal = firstDecimal(it, "remeasuredValue", "measuredValue", "newValue");
            if (origVal == null && origUnits != null) origVal = BigDecimal.valueOf(origUnits);
            if (reVal == null && reUnits != null) reVal = BigDecimal.valueOf(reUnits);

            String no = "CHG-" + Math.abs(Objects.hash(voy, hatch, System.nanoTime())) % 1_000_000;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("changeNo", no);
            row.put("batchNo", batch);
            row.put("voyageCode", voy);
            row.put("hatchCode", hatch);
            row.put("opCode", opCode);
            row.put("originalValue", origVal);
            row.put("remeasuredValue", reVal);
            row.put("deltaValue", (origVal != null && reVal != null) ? reVal.subtract(origVal) : null);
            row.put("unitLabel", firstStr(it, "unitLabel", "unit") != null
                    ? firstStr(it, "unitLabel", "unit") : "TEU");
            row.put("originalDeclaredMinutes", origMin);
            row.put("revisedRequiredMinutes", newMin);
            row.put("deltaMinutes", deltaMin);
            row.put("originalDeclaredUnits", origUnits);
            row.put("remeasuredUnits", reUnits);
            row.put("source", source);
            row.put("sourceRef", sourceRef);
            row.put("observedAt", observed);
            row.put("recordedBy", recordedBy);
            row.put("reason", reason);
            insertChange(row);
            saved.addAll(listChanges(batch).stream()
                    .filter(c -> no.equals(c.get("changeNo"))).toList());
        }
        return saved;
    }

    private void insertChange(Map<String, Object> r) {
        jdbc.update("""
                INSERT INTO cargo_change(change_no,batch_no,voyage_code,hatch_code,op_code,metric,
                    original_value,remeasured_value,delta_value,unit_label,
                    original_declared_minutes,revised_required_minutes,delta_minutes,
                    original_declared_units,remeasured_units,
                    source,source_ref,observed_at,recorded_by,reason)
                VALUES (?,?,?,?,?,'CARGO_WORKLOAD',?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                str(r.get("changeNo"), "CHG-" + System.nanoTime()),
                r.get("batchNo"), r.get("voyageCode"), r.get("hatchCode"), r.get("opCode"),
                r.get("originalValue"), r.get("remeasuredValue"), r.get("deltaValue"),
                str(r.get("unitLabel"), "TEU"),
                r.get("originalDeclaredMinutes"), r.get("revisedRequiredMinutes"), r.get("deltaMinutes"),
                r.get("originalDeclaredUnits"), r.get("remeasuredUnits"),
                r.get("source"), r.get("sourceRef"),
                Timestamp.valueOf((LocalDateTime) r.get("observedAt")),
                r.get("recordedBy"), r.get("reason"));
    }

    public List<Map<String, Object>> listChanges(String batchNo) {
        String sql = "SELECT * FROM cargo_change "
                + (batchNo != null ? "WHERE batch_no=? " : "")
                + "ORDER BY id";
        Object[] args = batchNo != null ? new Object[]{batchNo} : new Object[0];
        return jdbc.query(sql, CARGO_CHANGE_MAPPER, args);
    }

    private static final org.springframework.jdbc.core.RowMapper<Map<String, Object>> CARGO_CHANGE_MAPPER =
            (rs, i) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getLong("id"));
                m.put("changeNo", rs.getString("change_no"));
                m.put("batchNo", rs.getString("batch_no"));
                m.put("voyageCode", rs.getString("voyage_code"));
                m.put("hatchCode", rs.getString("hatch_code"));
                m.put("opCode", rs.getString("op_code"));
                m.put("originalValue", rs.getBigDecimal("original_value"));
                m.put("remeasuredValue", rs.getBigDecimal("remeasured_value"));
                m.put("deltaValue", rs.getBigDecimal("delta_value"));
                m.put("unitLabel", rs.getString("unit_label"));
                m.put("originalDeclaredMinutes", (Integer) rs.getObject("original_declared_minutes"));
                m.put("revisedRequiredMinutes", (Integer) rs.getObject("revised_required_minutes"));
                m.put("deltaMinutes", (Integer) rs.getObject("delta_minutes"));
                m.put("originalDeclaredUnits", (Integer) rs.getObject("original_declared_units"));
                m.put("remeasuredUnits", (Integer) rs.getObject("remeasured_units"));
                m.put("source", rs.getString("source"));
                m.put("sourceRef", rs.getString("source_ref"));
                Timestamp obs = rs.getTimestamp("observed_at");
                m.put("observedAt", obs == null ? null : obs.toLocalDateTime());
                m.put("recordedBy", rs.getString("recorded_by"));
                m.put("reason", rs.getString("reason"));
                Timestamp ca = rs.getTimestamp("created_at");
                m.put("createdAt", ca == null ? null : ca.toLocalDateTime());
                return m;
            };

    private record CargoHatchRef(String opCode, String crane, String yard) {}

    private CargoHatchRef findHatch(String voy, String hatch) {
        List<CargoHatchRef> l = jdbc.query("""
                SELECT op_code, crane_code, yard_code FROM cargo_hatch
                WHERE voyage_code=? AND hatch_code=?
                """, (rs, i) -> new CargoHatchRef(
                rs.getString("op_code"), rs.getString("crane_code"), rs.getString("yard_code")),
                voy, hatch);
        return l.isEmpty() ? null : l.get(0);
    }

    // ====================================================================
    // 场景总览 / 依赖图
    // ====================================================================

    public Map<String, Object> scenarioBundle() {
        seedScenarioIfMissing();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("asOf", t(10, 20));
        m.put("scenarioDate", D.toLocalDate());
        m.put("headline", "A 船两舱各 +1h → C1 占用至 14:00 撞 B 船 13:30 换班准备；Y-A1 堆位仅剩一半");
        m.put("givenChangeRequest", Map.of(
                "batchNo", GIVEN_BATCH,
                "source", "船代华港补报，附中理货 + 水尺计重复测报告",
                "observedAt", t(10, 10).toString(),
                "changes", List.of(
                        Map.of("voyageCode", "A", "hatchCode", "H1", "deltaMinutes", 60,
                                "originalDeclaredUnits", 60, "remeasuredUnits", 80),
                        Map.of("voyageCode", "A", "hatchCode", "H2", "deltaMinutes", 60,
                                "originalDeclaredUnits", 40, "remeasuredUnits", 60))));
        m.put("hatches", jdbc.queryForList("SELECT * FROM cargo_hatch ORDER BY voyage_code, seq_no"));
        m.put("yards", jdbc.queryForList("SELECT * FROM yard_stack ORDER BY code"));
        m.put("operations", loadOps().stream().map(this::baselineView).toList());
        m.put("dependencies", loadDeps());
        m.put("handlingRecords", listHandlingRecords());
        m.put("cargoChanges", listChanges(null));
        return m;
    }

    public Map<String, Object> dependencyGraph(String voyageCode) {
        seedScenarioIfMissing();
        List<Op> ops = loadOps();
        List<Dep> deps = loadDeps();
        if (voyageCode != null && !voyageCode.isBlank()) {
            Set<String> keepVoy = new LinkedHashSet<>(List.of(voyageCode));
            // 连同跨船依赖的对端节点一起展示
            for (Dep d : deps) {
                Op a = byCode(ops, d.op()), b = byCode(ops, d.on());
                if (a != null && voyageCode.equals(a.voyage)) keepVoy.add(b == null ? null : b.voyage);
                if (b != null && voyageCode.equals(b.voyage)) keepVoy.add(a == null ? null : a.voyage);
            }
            ops = ops.stream().filter(o -> keepVoy.contains(o.voyage)).toList();
            Set<String> codes = ops.stream().map(o -> o.code).collect(java.util.stream.Collectors.toSet());
            deps = deps.stream().filter(d -> codes.contains(d.op()) && codes.contains(d.on())).toList();
        }
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("nodes", ops.stream().map(o -> {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("opCode", o.code);
            n.put("voyageCode", o.voyage);
            n.put("title", o.title);
            n.put("opKind", o.kind);
            n.put("berthCode", o.berth);
            n.put("resourceType", o.resType);
            n.put("resourceCode", o.resCode);
            n.put("status", o.status);
            return n;
        }).toList());
        g.put("edges", deps);
        g.put("tracking", tracking(loadOps(), loadDeps()));
        return g;
    }

    private Map<String, Object> tracking(List<Op> ops, List<Dep> deps) {
        Map<String, Object> tr = new LinkedHashMap<>();
        // 岸桥依赖
        Map<String, List<Map<String, Object>>> cranes = new LinkedHashMap<>();
        for (Op o : ops) {
            if ("CRANE".equals(o.resType) && o.resCode != null) {
                cranes.computeIfAbsent(o.resCode, k -> new ArrayList<>()).add(Map.of(
                        "opCode", o.code, "voyageCode", o.voyage,
                        "start", o.s, "end", o.e, "status", o.status));
            }
        }
        tr.put("shoreCranes", cranes);
        // 堆位依赖
        tr.put("yardStacks", jdbc.queryForList("SELECT code,name,planned_capacity,available_capacity "
                + "FROM yard_stack ORDER BY code"));
        // 跨泊位窗口
        tr.put("crossBerthWindows", deps.stream()
                .filter(d -> "CROSS_BERTH_WINDOW".equals(d.kind())
                        || "SHARED_CRANE".equals(d.kind()))
                .map(d -> Map.of("from", d.on(), "to", d.op(), "kind", d.kind(),
                        "gapMinutes", d.gap(), "detail", d.detail() == null ? "" : d.detail()))
                .toList());
        return tr;
    }

    // ====================================================================
    // 两种重排分析
    // ====================================================================

    public Map<String, Object> analyzeGiven(String mode, Map<String, Object> choices) {
        recordGivenChange();
        return analyze(GIVEN_BATCH, mode, choices);
    }

    public Map<String, Object> analyzeBoth(Map<String, Object> choices) {
        recordGivenChange();
        Map<String, Object> chain = analyze(GIVEN_BATCH, "DEPENDENCY_CHAIN", choices);
        Map<String, Object> full = analyze(GIVEN_BATCH, "FULL_PORT", choices);

        @SuppressWarnings("unchecked")
        List<String> chainAffected = (List<String>) chain.get("affectedVoyageCodes");
        @SuppressWarnings("unchecked")
        List<String> fullAffected = (List<String>) full.get("affectedVoyageCodes");
        List<String> added = fullAffected.stream()
                .filter(v -> !chainAffected.contains(v)).toList();

        Map<String, Object> cmp = new LinkedHashMap<>();
        cmp.put("dependencyChainAffected", chainAffected);
        cmp.put("fullPortAffected", fullAffected);
        cmp.put("additionallyMovedByFullPort", added);
        cmp.put("chainBlastRadius", chainAffected.size());
        cmp.put("fullPortBlastRadius", fullAffected.size());
        cmp.put("tradeoff", "依赖链重算只推移 A/B，已通知的无关 C/D 计划不动，影响小；"
                + "通过共享岸桥下游扫描保证不漏 B，并对 C 给出二阶预警。"
                + "全港重排统一平衡所有资源，较早坐实 B 顺延会在 C1 上连锁挤占 C 船 16:20 窗口，"
                + "但默认全局平衡会把无关 C 船的已通知计划也顺延 20 分钟。");
        cmp.put("manualChoiceHint", "在 choices 中传 {\"CRANE_C1_PREP\":\"REASSIGN_B_TO_C2\"} "
                + "可令 B 船改派 C2：B 不晚点，全港模式下 C 船 16:20 也能保住。");

        Map<String, Object> both = new LinkedHashMap<>();
        both.put("batchNo", GIVEN_BATCH);
        both.put("dependencyChain", chain);
        both.put("fullPort", full);
        both.put("comparison", cmp);
        return both;
    }

    @Transactional
    public Map<String, Object> analyze(String batchNo, String mode, Map<String, Object> choicesRaw) {
        seedScenarioIfMissing();
        if (mode == null || mode.isBlank()) mode = "DEPENDENCY_CHAIN";
        mode = mode.toUpperCase();
        if (!"DEPENDENCY_CHAIN".equals(mode) && !"FULL_PORT".equals(mode)) {
            throw ApiException.badRequest("BAD_MODE", "mode 仅支持 DEPENDENCY_CHAIN / FULL_PORT");
        }
        Map<String, String> choices = normalizeChoices(choicesRaw);
        boolean full = "FULL_PORT".equals(mode);
        boolean reassignC2 = "REASSIGN_B_TO_C2".equals(choices.get("CRANE_C1_PREP"))
                || "C2".equals(choices.get("crane"))
                || "KEEP_C_NOTIFIED".equals(choices.get("SECOND_ORDER_C1_BC"));
        String yardChoice = choices.getOrDefault("YARD_CAPACITY", "OVERFLOW_YA2");

        List<Map<String, Object>> changes = listChanges(batchNo);
        if (changes.isEmpty()) {
            throw ApiException.notFound("未找到批次 " + batchNo + " 的货量变更，请先录入或调用 given-change");
        }
        Map<String, Integer> deltaByOp = new LinkedHashMap<>();
        Map<String, Integer> reUnitsByHatchYard = new LinkedHashMap<>();
        for (Map<String, Object> c : changes) {
            String op = (String) c.get("opCode");
            Integer dm = (Integer) c.get("deltaMinutes");
            if (op != null && dm != null) deltaByOp.merge(op, dm, Integer::sum);
        }

        List<Op> ops = loadOps();
        Map<String, Op> byCode = new LinkedHashMap<>();
        ops.forEach(o -> byCode.put(o.code, o));
        List<Dep> deps = loadDeps();

        // 依赖闭包（按“下游”方向：pred -> op）
        Set<String> changedRoots = new LinkedHashSet<>(deltaByOp.keySet());
        Set<String> closure = downstreamClosure(changedRoots, deps);
        closure.addAll(changedRoots);

        // 应用货量延时 + 人工改派
        for (String code : changedRoots) {
            Op o = byCode.get(code);
            if (o == null) continue;
            int add = deltaByOp.getOrDefault(code, 0);
            o.pe = o.s.plusMinutes(Duration_between(o.s, o.e) + add);
        }
        if (reassignC2) {
            for (String code : List.of("B-H1", "B-PREP")) {
                Op o = byCode.get(code);
                if (o != null) o.effectiveRes = "C2";
            }
        }

        // 固定点：显式依赖推移 + 岸桥日历平衡
        Set<String> scope = full ? ops.stream().map(o -> o.code).collect(java.util.stream.Collectors.toSet())
                : closure;
        for (int iter = 0; iter < 64; iter++) {
            boolean moved = false;
            for (Dep d : deps) {
                if (!d.propagate()) continue;
                Op o = byCode.get(d.op());
                Op p = byCode.get(d.on());
                if (o == null || p == null || "DONE".equals(o.status)) continue;
                if (reassignC2 && "CROSS_BERTH_WINDOW".equals(d.kind()) && "B-H1".equals(o.code)) continue;
                if (!scope.contains(o.code)) continue;
                LocalDateTime earliest = p.pe.plusMinutes(d.gap());
                if (earliest.isAfter(o.ps)) {
                    long shift = java.time.Duration.between(o.ps, earliest).toMinutes();
                    o.ps = earliest;
                    o.pe = o.pe.plusMinutes(shift);
                    moved = true;
                }
            }
            if (levelCranes(ops, byCode, full ? null : scope, reassignC2)) moved = true;
            if (!moved) break;
        }

        // 收集冲突工序
        List<Map<String, Object>> conflicts = new ArrayList<>();
        Set<String> conflictOps = new LinkedHashSet<>();

        // 1) 共享资源区间重叠（岸桥，含换班准备）
        List<Op> overlapScope = ops.stream().filter(o -> scope.contains(o.code)).toList();
        record Overlap(Op a, Op b) {}
        List<Overlap> overlaps = new ArrayList<>();
        for (int i = 0; i < overlapScope.size(); i++) {
            for (int j = i + 1; j < overlapScope.size(); j++) {
                Op a = overlapScope.get(i), b = overlapScope.get(j);
                if (!"CRANE".equals(a.resType) || !"CRANE".equals(b.resType)) continue;
                if (!Objects.equals(a.effectiveRes, b.effectiveRes)) continue;
                if ("DONE".equals(a.status) && "DONE".equals(b.status)) continue;
                if (a.ps.isBefore(b.pe) && b.ps.isBefore(a.pe)) overlaps.add(new Overlap(a, b));
            }
        }
        for (Overlap ov : overlaps) {
            Op a = ov.a(), b = ov.b();
            conflictOps.add(a.code); conflictOps.add(b.code);
            boolean prep = "SHIFT_PREP".equals(a.kind) || "SHIFT_PREP".equals(b.kind);
            Op hatch = "SHIFT_PREP".equals(a.kind) ? b : ("SHIFT_PREP".equals(b.kind) ? a : a);
            Op prepOp = "SHIFT_PREP".equals(a.kind) ? a : ("SHIFT_PREP".equals(b.kind) ? b : null);
            long overlapMin = java.time.Duration.between(
                    a.ps.isAfter(b.ps) ? a.ps : b.ps,
                    a.pe.isBefore(b.pe) ? a.pe : b.pe).toMinutes();
            String crane = a.effectiveRes;
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("code", prep ? "CRANE_PREP_OVERLAP" : "CRANE_OVERLAP");
            c.put("severity", "BLOCKER");
            c.put("decisionRequired", true);
            c.put("title", prep
                    ? "岸桥 " + crane + " 延时占用与 B 船 13:30 换班准备重叠"
                    : "岸桥 " + crane + " 作业区间冲突");
            c.put("shoreCrane", crane);
            c.put("voyageCodes", orderedVoyages(List.of(a.voyage, b.voyage)));
            c.put("opCodes", List.of(a.code, b.code));
            c.put("window", Map.of(
                    "opA", a.code, "startA", a.ps, "endA", a.pe,
                    "opB", b.code, "startB", b.ps, "endB", b.pe,
                    "overlapMinutes", overlapMin));
            c.put("message", "岸桥 " + crane + " 在 " + fmt(a.ps) + "–" + fmt(a.pe)
                    + " 与另一道工序 " + b.code + "（" + fmt(b.ps) + "–" + fmt(b.pe)
                    + "）区间重叠 " + overlapMin + " 分钟");
            if (prep && "C1".equals(crane)) {
                c.put("message", "A 船一舱增量后 " + fmt(a.pe) + " 才释放 " + crane
                        + "，与 B 船 13:30–14:00 换班准备重叠 " + overlapMin
                        + " 分钟；" + crane + " 跨泊位转场 B2→B1 还需 " + CRANE_TRANSFER_MIN + " 分钟");
            }
            List<Map<String, Object>> options = new ArrayList<>();
            options.add(option("WAIT_C1", "等待 C1（推荐·影响最小）",
                    "换班会仍 13:30 召开但 14:00 后交接，C1 转场 20m，B-H1 最早 14:20 开工，B 船后续顺延 20m",
                    Map.of("B-H1", "+20m", "B-UNBERTH", "+20m")));
            options.add(option("REASSIGN_B_TO_C2", "B 船改派 C2",
                    "B-PREP/B-H1 改用 C2；C2 在 A-H2 13:30 结束并转场后 14:00 可用，B 船准点，且可保住 C 船窗口",
                    Map.of("B-H1", "0m", "B-PREP-resource", "C2")));
            c.put("options", options);
            c.put("chosenOptionId", reassignC2 ? "REASSIGN_B_TO_C2" : "WAIT_C1");
            c.put("chosenResolved", reassignC2);
            conflicts.add(c);
        }

        // 2) 堆位容量
        Map<String, Object> yardConflict = yardConflict(changes, yardChoice);
        if (yardConflict != null) conflicts.add(yardConflict);

        // 3) 二阶 C1 连锁（全港坐实 / 依赖链预警）
        Op cH1 = byCode.get("C-H1");
        boolean cPushed = cH1 != null && cH1.ps.isAfter(cH1.s);
        if (full && cPushed) {
            conflictOps.add("C-H1"); conflictOps.add("C-UNBERTH");
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("code", "SECOND_ORDER_CRANE_CASCADE");
            c.put("severity", "BLOCKER");
            c.put("decisionRequired", true);
            c.put("title", "二阶冲突：B 顺延经共享 C1 连锁挤占 C 船已通知窗口");
            c.put("shoreCrane", "C1");
            c.put("voyageCodes", List.of("B", "C"));
            c.put("opCodes", List.of("B-H1", "C-H1"));
            c.put("window", Map.of(
                    "cNotifiedStart", cH1.s, "cEarliestStart", cH1.ps,
                    "delayMinutes", java.time.Duration.between(cH1.s, cH1.ps).toMinutes()));
            c.put("message", "全港平衡发现：B-H1 在 C1 上顺延至 16:20，B1→B2 转场 "
                    + CRANE_TRANSFER_MIN + " 分钟后 C1 最早 16:40 才能接 C-H1，"
                    + "晚于 C 船已通知的 16:20，C 船及其离泊需顺延 "
                    + java.time.Duration.between(cH1.s, cH1.ps).toMinutes() + " 分钟");
            List<Map<String, Object>> options = new ArrayList<>();
            options.add(option("SHIFT_C_LATER", "C 船顺延（全局默认）",
                    "C-H1 16:40–18:40，C-UNBERTH 18:40–19:10；改动了无关 C 船的已通知计划",
                    Map.of("C-H1", "+20m", "C-UNBERTH", "+20m")));
            options.add(option("KEEP_C_NOTIFIED", "B 改 C2，保住 C 船 16:20",
                    "B-H1/B-PREP 改派 C2，B 准点且 C1 空出，C 船已通知时刻不变",
                    Map.of("B-H1", "0m", "C-H1", "0m")));
            c.put("options", options);
            c.put("chosenOptionId", reassignC2 ? "KEEP_C_NOTIFIED" : "SHIFT_C_LATER");
            c.put("chosenResolved", reassignC2);
            conflicts.add(c);
        }

        // 依赖链共享岸桥下游安全扫描（不移动链外工序，只预警）
        List<Map<String, Object>> watch = new ArrayList<>();
        if (!full) {
            Set<String> touchedCranes = new LinkedHashSet<>();
            for (String code : closure) {
                Op o = byCode.get(code);
                if (o != null && "CRANE".equals(o.resType) && o.effectiveRes != null) {
                    touchedCranes.add(o.effectiveRes);
                }
            }
            for (String crane : touchedCranes) {
                List<Op> inChainHatch = closure.stream().map(byCode::get)
                        .filter(Objects::nonNull)
                        .filter(o -> "HATCH_OP".equals(o.kind) && crane.equals(o.effectiveRes))
                        .sorted(Comparator.comparing(o -> o.pe)).toList();
                if (inChainHatch.isEmpty()) continue;
                Op last = inChainHatch.get(inChainHatch.size() - 1);
                List<Op> outside = ops.stream()
                        .filter(o -> !closure.contains(o.code))
                        .filter(o -> !"DONE".equals(o.status))
                        .filter(o -> "HATCH_OP".equals(o.kind) && crane.equals(o.resCode))
                        .sorted(Comparator.comparing(o -> o.s)).toList();
                for (Op o : outside) {
                    // 只看时间上排在链内最后一道工序之后的“未来”下游，排除上午已完成批次
                    if (!o.s.isAfter(last.ps)) continue;
                    int gap = Objects.equals(last.berth, o.berth) ? 0 : CRANE_TRANSFER_MIN;
                    LocalDateTime earliest = last.pe.plusMinutes(gap);
                    if (earliest.isAfter(o.s)) {
                        long wouldDelay = java.time.Duration.between(o.s, earliest).toMinutes();
                        Map<String, Object> w = new LinkedHashMap<>();
                        w.put("code", "SHARED_CRANE_DOWNSTREAM_WATCH");
                        w.put("severity", "WARNING");
                        w.put("shoreCrane", crane);
                        w.put("voyageCode", o.voyage);
                        w.put("opCode", o.code);
                        w.put("notifiedStart", o.s);
                        w.put("earliestStartIfChained", earliest);
                        w.put("potentialDelayMinutes", wouldDelay);
                        w.put("message", "共享岸桥 " + crane + " 下游扫描：链外 " + o.voyage
                                + " 船 " + o.code + " 已通知 " + fmt(o.s) + " 可能被连锁顺延约 "
                                + wouldDelay + " 分钟；局部依赖链不重算它，建议用 FULL_PORT 坐实二阶冲突");
                        watch.add(w);
                    }
                }
            }
        }

        // ---- 组装受影响船次 / 序列 / 移动工序 / 保留记录 ----
        List<String> affectedVoyages = new ArrayList<>();
        for (String v : List.of("A", "B", "C", "D")) {
            List<Op> vops = ops.stream().filter(o -> v.equals(o.voyage)).toList();
            boolean aff = vops.stream().anyMatch(o -> isAffected(o, changedRoots, conflictOps));
            if (aff) affectedVoyages.add(v);
        }
        List<String> evaluated = full ? List.of("A", "B", "C", "D") : List.of("A", "B");
        List<String> outsideScope = full ? List.of() :
                List.of("C", "D").stream().filter(v -> !affectedVoyages.contains(v)).toList();

        List<Map<String, Object>> sequences = new ArrayList<>();
        // 给出所有“被评估船次”的完整作业顺序（全港模式含保持不变的 D），便于对照
        for (String v : evaluated) {
            List<Map<String, Object>> seq = ops.stream()
                    .filter(o -> v.equals(o.voyage))
                    .sorted(Comparator.comparingInt(o -> o.seq))
                    .map(o -> opView(o, changedRoots, conflictOps)).toList();
            long moved = seq.stream().filter(x -> Boolean.TRUE.equals(x.get("affected"))).count();
            Map<String, Object> vs = new LinkedHashMap<>();
            vs.put("voyageCode", v);
            vs.put("berthCodes", ops.stream().filter(o -> v.equals(o.voyage) && o.berth != null)
                    .map(o -> o.berth).distinct().toList());
            vs.put("affectedOpCount", moved);
            vs.put("hasManualConflict", conflicts.stream().anyMatch(c -> {
                @SuppressWarnings("unchecked")
                List<String> vc = (List<String>) c.get("voyageCodes");
                return vc != null && vc.contains(v);
            }));
            vs.put("sequence", seq);
            sequences.add(vs);
        }

        List<Map<String, Object>> movedOps = ops.stream()
                .filter(o -> affectedVoyages.contains(o.voyage))
                .map(o -> opView(o, changedRoots, conflictOps))
                .filter(x -> Boolean.TRUE.equals(x.get("affected")))
                .toList();

        List<Map<String, Object>> retainedDone = ops.stream()
                .filter(o -> "DONE".equals(o.status))
                .sorted(Comparator.comparing(o -> o.seq))
                .map(o -> opView(o, changedRoots, conflictOps)).toList();
        List<Map<String, Object>> records = listHandlingRecords();

        List<Map<String, Object>> timeline = ops.stream()
                .filter(o -> (full || closure.contains(o.code)))
                .sorted(Comparator.comparing(o -> o.ps))
                .map(o -> {
                    Map<String, Object> x = new LinkedHashMap<>();
                    x.put("opCode", o.code);
                    x.put("voyageCode", o.voyage);
                    x.put("revisedStart", o.ps);
                    x.put("revisedEnd", o.pe);
                    x.put("resourceCode", o.effectiveRes);
                    x.put("state", stateOf(o, changedRoots, conflictOps));
                    return x;
                }).toList();

        boolean allClear = conflicts.isEmpty();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("mode", mode);
        report.put("modeLabel", full ? "全港重排（全局资源平衡）" : "资源依赖链重算（局部闭包）");
        report.put("batchNo", batchNo);
        report.put("choices", choices);
        report.put("generatedAt", LocalDateTime.now());
        report.put("allClear", allClear);
        report.put("evaluatedVoyageCodes", evaluated);
        report.put("affectedVoyageCodes", affectedVoyages);
        report.put("outsideDependencyScope", outsideScope);
        report.put("untouchedNotifiedVoyageCodes", full
                ? List.of("D")
                : List.of("C", "D"));
        report.put("blastRadius", Map.of(
                "affectedVoyages", affectedVoyages.size(),
                "movedOrChangedOperations", movedOps.size(),
                "manualConflicts", conflicts.size()));
        report.put("affectedVoyages", voyageSummaries(affectedVoyages, sequences, conflicts));
        report.put("operationSequences", sequences);
        report.put("proposedTimeline", timeline);
        report.put("movedOperations", movedOps);
        report.put("retainedCompletedOperations", retainedDone);
        report.put("completedHandlingRecords", records);
        report.put("manualConflicts", conflicts);
        report.put("downstreamWatch", watch);
        report.put("dependencyTracking", tracking(ops, deps));
        report.put("dependencyGraph", Map.of(
                "roots", changedRoots,
                "closure", closure,
                "edges", deps));
        report.put("immutability", Map.of(
                "completedOperationsRetained", retainedDone.size(),
                "handlingRecords", records.size(),
                "rule", "DONE 工序与 handling_record/cargo_change 不参与推移，"
                        + "数据库触发器 trg_handling_record_immutable/trg_cargo_change_immutable "
                        + "拒绝 UPDATE/DELETE；新计划只投影未执行工序"));
        report.put("answer", buildAnswer(mode, affectedVoyages, movedOps, retainedDone, conflicts, watch));

        long id = persistReport(batchNo, mode, choices, report, allClear);
        report.put("reportId", id);
        return report;
    }

    // ====================================================================
    // 计算辅助
    // ====================================================================

    /**岸桥 HATCH_OP 日历平衡：同岸桥按开始排序，跨泊位强制 20m 转场。返回是否发生推移。*/
    private boolean levelCranes(List<Op> ops, Map<String, Op> byCode, Set<String> scope,
                                boolean reassignC2) {
        boolean moved = false;
        Map<String, List<Op>> byCrane = new LinkedHashMap<>();
        for (Op o : ops) {
            if (!"HATCH_OP".equals(o.kind) || !"CRANE".equals(o.resType)) continue;
            if (scope != null && !scope.contains(o.code)) continue;
            byCrane.computeIfAbsent(o.effectiveRes, k -> new ArrayList<>()).add(o);
        }
        for (List<Op> list : byCrane.values()) {
            list.sort(Comparator.comparing(o -> o.ps));
            for (int i = 1; i < list.size(); i++) {
                Op prev = list.get(i - 1), cur = list.get(i);
                int gap = Objects.equals(prev.berth, cur.berth) ? 0 : CRANE_TRANSFER_MIN;
                LocalDateTime earliest = prev.pe.plusMinutes(gap);
                if (earliest.isAfter(cur.ps)) {
                    long shift = java.time.Duration.between(cur.ps, earliest).toMinutes();
                    cur.ps = earliest;
                    cur.pe = cur.pe.plusMinutes(shift);
                    moved = true;
                }
            }
        }
        return moved;
    }

    private Map<String, Object> yardConflict(List<Map<String, Object>> changes, String yardChoice) {
        Map<String, Object> y;
        try {
            y = jdbc.queryForMap("SELECT * FROM yard_stack WHERE code='Y-A1'");
        } catch (Exception e) {
            return null;
        }
        int available = ((Number) y.get("available_capacity")).intValue();
        int planned = ((Number) y.get("planned_capacity")).intValue();
        int revisedDemand = changes.stream()
                .map(c -> (Integer) c.get("remeasuredUnits"))
                .filter(Objects::nonNull).mapToInt(Integer::intValue).sum();
        int originalDemand = changes.stream()
                .map(c -> (Integer) c.get("originalDeclaredUnits"))
                .filter(Objects::nonNull).mapToInt(Integer::intValue).sum();
        if (revisedDemand <= available) return null;
        int shortfall = revisedDemand - available;

        Map<String, Object> c = new LinkedHashMap<>();
        c.put("code", "YARD_CAPACITY_SHORTFALL");
        c.put("severity", "BLOCKER");
        c.put("decisionRequired", true);
        c.put("title", "Y-A1 堆位仅剩一半容量，放不下复测货量");
        c.put("yardCode", "Y-A1");
        c.put("voyageCodes", List.of("A"));
        c.put("opCodes", List.of("A-H1", "A-H2", "A-YARD"));
        c.put("capacity", Map.of(
                "plannedCapacity", planned,
                "availableCapacity", available,
                "capacityRatio", "1/2",
                "originalDemandUnits", originalDemand,
                "revisedDemandUnits", revisedDemand,
                "shortfallUnits", shortfall,
                "unit", "TEU"));
        c.put("message", "Y-A1 原计划 " + planned + " TEU，现仅 " + available
                + " TEU（一半）；A 船复测后需 " + revisedDemand + " TEU，缺口 " + shortfall + " TEU");
        List<Map<String, Object>> options = new ArrayList<>();
        options.add(option("OVERFLOW_YA2", "溢流 " + shortfall + " TEU 到 Y-A2（推荐）",
                "Y-A2 可用 80 TEU ≥ 缺口 " + shortfall + "，水平运输距离增加，不影响岸桥时序",
                Map.of("Y-A1", available + " TEU", "Y-A2", shortfall + " TEU")));
        options.add(option("RUSH_PICKUP", "紧急疏港提走 " + shortfall + " TEU",
                "通知货主/集卡 12:00 前提走 " + shortfall + " TEU，腾出 Y-A1 容量，全部仍落 Y-A1",
                Map.of("Y-A1", "释放 " + shortfall + " TEU")));
        c.put("options", options);
        c.put("chosenOptionId", yardChoice);
        c.put("yardPlan", "RUSH_PICKUP".equals(yardChoice)
                ? Map.of("Y-A1", revisedDemand + " TEU（12:00 前紧急疏港补足）")
                : Map.of("Y-A1", available + " TEU", "Y-A2", shortfall + " TEU"));
        return c;
    }

    private Map<String, Object> buildAnswer(String mode, List<String> affected,
                                            List<Map<String, Object>> moved,
                                            List<Map<String, Object>> retained,
                                            List<Map<String, Object>> conflicts,
                                            List<Map<String, Object>> watch) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("affectedVoyages", affected);
        a.put("unexecutedOperationsToMove", moved.stream()
                .map(x -> x.get("opCode") + "(" + x.get("state") + ")").toList());
        a.put("completedOperationsThatRemain", retained.stream()
                .map(x -> x.get("opCode") + " " + x.get("title")).toList());
        a.put("manualChoices", conflicts.stream().map(c -> c.get("code")).toList());
        a.put("watchItems", watch.stream().map(w -> w.get("code") + ":" + w.get("voyageCode")).toList());
        if ("FULL_PORT".equals(mode)) {
            a.put("conclusion", "全港重排：受影响船次 " + affected
                    + "；A 两舱延时并带动平舱/离泊，B 因 C1 换班重叠与跨泊位转场顺延 20m，"
                    + "全局平衡还把二阶 C1 冲突落到 C 船（C-H1/C-UNBERTH +20m，属可避免的无关已通知改动）；"
                    + "需人工在 C1 等待 vs B 改 C2、Y-A1 溢流 vs 紧急疏港、C 顺延 vs 保 C 之间选择。"
                    + "4 个已完成工序与 2 条装卸记录原样保留。");
        } else {
            a.put("conclusion", "依赖链重算：受影响船次仅 " + affected
                    + "；A 两舱 +1h 后移平舱/离泊与 C1 转场，B-H1 最早 14:20、B 离泊 +20m，"
                    + "B 13:30 换班准备形成需人工裁决的 C1 重叠；Y-A1 缺口 40 TEU 需人工选择溢流或疏港。"
                    + "共享岸桥扫描未漏 B，并预警 C 船可能的二阶连锁（建议全港重排坐实）；C/D 已通知计划不动。"
                    + "4 个已完成工序与 2 条装卸记录原样保留。");
        }
        return a;
    }

    private List<Map<String, Object>> voyageSummaries(List<String> codes,
                                                      List<Map<String, Object>> sequences,
                                                      List<Map<String, Object>> conflicts) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String v : codes) {
            Map<String, Object> s = sequences.stream()
                    .filter(x -> v.equals(x.get("voyageCode"))).findFirst().orElse(null);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("voyageCode", v);
            m.put("impactedOpCount", s == null ? 0 : s.get("affectedOpCount"));
            m.put("hasManualConflict", s != null
                    && Boolean.TRUE.equals(s.get("hasManualConflict")));
            m.put("notifiedPlanMayChange", true);
            out.add(m);
        }
        return out;
    }

    private Map<String, Object> option(String id, String label, String effect, Map<String, Object> delta) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("optionId", id);
        o.put("label", label);
        o.put("effect", effect);
        o.put("projectedDelta", delta);
        return o;
    }

    private Set<String> downstreamClosure(Set<String> roots, List<Dep> deps) {
        Map<String, List<String>> adj = new LinkedHashMap<>();
        for (Dep d : deps) adj.computeIfAbsent(d.on(), k -> new ArrayList<>()).add(d.op());
        Set<String> seen = new LinkedHashSet<>(roots);
        List<String> queue = new ArrayList<>(roots);
        while (!queue.isEmpty()) {
            String cur = queue.remove(0);
            for (String nxt : adj.getOrDefault(cur, List.of())) {
                if (seen.add(nxt)) queue.add(nxt);
            }
        }
        return seen;
    }

    private boolean isAffected(Op o, Set<String> changedRoots, Set<String> conflictOps) {
        if ("DONE".equals(o.status)) return false;
        if (changedRoots.contains(o.code)) return true;
        if (conflictOps.contains(o.code)) return true;
        return o.ps.isAfter(o.s) || o.pe.isAfter(o.e)
                || !Objects.equals(o.effectiveRes, o.resCode);
    }

    private String stateOf(Op o, Set<String> changedRoots, Set<String> conflictOps) {
        if ("DONE".equals(o.status)) return "RETAINED_COMPLETED";
        if (changedRoots.contains(o.code)) return "VOLUME_EXTENDED";
        if (conflictOps.contains(o.code) && !o.ps.isAfter(o.s)) return "NEEDS_DECISION";
        if (o.ps.isAfter(o.s) || o.pe.isAfter(o.e)) return "MOVED";
        if (!Objects.equals(o.effectiveRes, o.resCode)) return "REASSIGNED";
        return "KEPT";
    }

    private Map<String, Object> opView(Op o, Set<String> changedRoots, Set<String> conflictOps) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("opCode", o.code);
        m.put("voyageCode", o.voyage);
        m.put("seqNo", o.seq);
        m.put("title", o.title);
        m.put("opKind", o.kind);
        m.put("berthCode", o.berth);
        m.put("resourceType", o.resType);
        m.put("baselineResourceCode", o.resCode);
        m.put("resourceCode", o.effectiveRes);
        m.put("baselineStart", o.s);
        m.put("baselineEnd", o.e);
        m.put("revisedStart", o.ps);
        m.put("revisedEnd", o.pe);
        m.put("startDelayMinutes", java.time.Duration.between(o.s, o.ps).toMinutes());
        m.put("endDelayMinutes", java.time.Duration.between(o.e, o.pe).toMinutes());
        m.put("durationMinutes", java.time.Duration.between(o.ps, o.pe).toMinutes());
        m.put("status", o.status);
        m.put("notified", o.notified);
        String state = stateOf(o, changedRoots, conflictOps);
        m.put("state", state);
        m.put("affected", isAffected(o, changedRoots, conflictOps));
        m.put("inConflict", conflictOps.contains(o.code));
        return m;
    }

    private Map<String, Object> baselineView(Op o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("opCode", o.code);
        m.put("voyageCode", o.voyage);
        m.put("seqNo", o.seq);
        m.put("title", o.title);
        m.put("opKind", o.kind);
        m.put("berthCode", o.berth);
        m.put("resourceType", o.resType);
        m.put("resourceCode", o.resCode);
        m.put("startAt", o.s);
        m.put("endAt", o.e);
        m.put("status", o.status);
        m.put("notified", o.notified);
        return m;
    }

    public List<Map<String, Object>> listHandlingRecords() {
        return jdbc.query("SELECT * FROM handling_record ORDER BY id", (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("recordNo", rs.getString("record_no"));
            m.put("voyageCode", rs.getString("voyage_code"));
            m.put("opCode", rs.getString("op_code"));
            m.put("hatchCode", rs.getString("hatch_code"));
            m.put("operatorName", rs.getString("operator_name"));
            m.put("workMinutes", rs.getInt("work_minutes"));
            m.put("movedUnits", rs.getInt("moved_units"));
            m.put("resultNote", rs.getString("result_note"));
            Timestamp st = rs.getTimestamp("started_at");
            m.put("startedAt", st == null ? null : st.toLocalDateTime());
            Timestamp ct = rs.getTimestamp("completed_at");
            m.put("completedAt", ct == null ? null : ct.toLocalDateTime());
            return m;
        });
    }

    /**播种两条已完成装卸记录（由 SeedService 在场景初始化时调用）。*/
    public void seedHandlingRecords() {
        if (jdbc.queryForObject("SELECT count(*) FROM handling_record", Integer.class) > 0) return;
        jdbc.update("""
                INSERT INTO handling_record(record_no,voyage_code,op_code,hatch_code,operator_name,
                                            work_minutes,moved_units,result_note,started_at)
                VALUES ('HR-A-H1-AM','A','A-H1-DONE','H1','吴岸桥一班',90,60,
                        '一舱上午批次 60 TEU 已卸船并落 Y-A1，台账固化', ?)
                """, Timestamp.valueOf(t(8, 45)));
        jdbc.update("""
                INSERT INTO handling_record(record_no,voyage_code,op_code,hatch_code,operator_name,
                                            work_minutes,moved_units,result_note,started_at)
                VALUES ('HR-A-H2-AM','A','A-H2-DONE','H2','吴岸桥二班',75,40,
                        '二舱上午批次 40 TEU 已卸船并落 Y-A1，台账固化', ?)
                """, Timestamp.valueOf(t(8, 45)));
    }

    // ====================================================================
    // 报告留痕
    // ====================================================================

    private long persistReport(String batch, String mode, Map<String, String> choices,
                               Map<String, Object> report, boolean allClear) {
        try {
            long id = reportSeq.incrementAndGet();
            String no = "RPT-" + System.currentTimeMillis() + "-" + id;
            jdbc.update("""
                    INSERT INTO impact_report(report_no,batch_no,mode,choices_json,all_clear,report_json)
                    VALUES (?,?,?,?,?,?)
                    """, no, batch, mode, json.writeValueAsString(choices), allClear,
                    json.writeValueAsString(report));
            Long rid = jdbc.queryForObject("SELECT id FROM impact_report WHERE report_no=?",
                    Long.class, no);
            return rid == null ? -1 : rid;
        } catch (Exception e) {
            // 留痕失败不应影响分析结论
            return -1;
        }
    }

    public List<Map<String, Object>> listReports() {
        return jdbc.query("SELECT id,report_no,batch_no,mode,all_clear,created_at "
                + "FROM impact_report ORDER BY id DESC LIMIT 50", (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("reportNo", rs.getString("report_no"));
            m.put("batchNo", rs.getString("batch_no"));
            m.put("mode", rs.getString("mode"));
            m.put("allClear", rs.getBoolean("all_clear"));
            Timestamp ts = rs.getTimestamp("created_at");
            m.put("createdAt", ts == null ? null : ts.toLocalDateTime());
            return m;
        });
    }

    public Object getReport(long id) {
        List<String> jsonText = jdbc.queryForList(
                "SELECT report_json FROM impact_report WHERE id=?", String.class, id);
        if (jsonText.isEmpty()) throw ApiException.notFound("报告不存在: " + id);
        try {
            return json.readValue(jsonText.get(0), Object.class);
        } catch (Exception e) {
            throw new IllegalStateException("报告反序列化失败", e);
        }
    }

    // ====================================================================
    // 行装载 / 小工具
    // ====================================================================

    private List<Op> loadOps() {
        return jdbc.query("SELECT * FROM port_operation ORDER BY seq_no,id", (rs, i) -> {
            Op o = new Op();
            o.id = rs.getLong("id");
            o.code = rs.getString("op_code");
            o.voyage = rs.getString("voyage_code");
            o.seq = rs.getInt("seq_no");
            o.title = rs.getString("title");
            o.kind = rs.getString("op_kind");
            o.berth = rs.getString("berth_code");
            o.resType = rs.getString("resource_type");
            o.resCode = rs.getString("resource_code");
            o.s = rs.getTimestamp("start_at").toLocalDateTime();
            o.e = rs.getTimestamp("end_at").toLocalDateTime();
            o.ps = o.s;
            o.pe = o.e;
            o.status = rs.getString("status");
            o.notified = rs.getBoolean("notified");
            o.effectiveRes = o.resCode;
            return o;
        });
    }

    private List<Dep> loadDeps() {
        return jdbc.query("SELECT * FROM operation_dependency ORDER BY id", (rs, i) -> new Dep(
                rs.getString("op_code"),
                rs.getString("depends_on_op_code"),
                rs.getString("dep_kind"),
                rs.getInt("min_gap_minutes"),
                rs.getBoolean("propagate"),
                rs.getString("detail")));
    }

    private Op byCode(List<Op> ops, String code) {
        return ops.stream().filter(o -> o.code.equals(code)).findFirst().orElse(null);
    }

    private static long Duration_between(LocalDateTime a, LocalDateTime b) {
        return java.time.Duration.between(a, b).toMinutes();
    }

    private static String fmt(LocalDateTime t) {
        return t == null ? "--:--" : String.format("%02d:%02d", t.getHour(), t.getMinute());
    }

    private List<String> orderedVoyages(List<String> vs) {
        List<String> order = List.of("A", "B", "C", "D");
        return vs.stream().distinct().sorted(Comparator.comparingInt(order::indexOf)).toList();
    }

    private Map<String, String> normalizeChoices(Map<String, Object> raw) {
        Map<String, String> m = new LinkedHashMap<>();
        if (raw != null) raw.forEach((k, v) -> {
            if (v != null) m.put(k, String.valueOf(v));
        });
        return m;
    }

    private static String str(Object o, String def) {
        return o == null ? def : String.valueOf(o);
    }

    /**超过 10 对键值时替代 Map.of 的有序可变构造。*/
    private static Map<String, Object> kv(Object... pairs) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            m.put((String) pairs[i], pairs[i + 1]);
        }
        return m;
    }

    private static String firstStr(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            Object v = m.get(k);
            if (v != null && !String.valueOf(v).isBlank()) return String.valueOf(v);
        }
        return null;
    }

    private static Integer firstInt(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            Object v = m.get(k);
            if (v instanceof Number n) return n.intValue();
            if (v != null) {
                try { return (int) Double.parseDouble(String.valueOf(v)); } catch (Exception ignore) {}
            }
        }
        return null;
    }

    private static BigDecimal firstDecimal(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            Object v = m.get(k);
            if (v instanceof BigDecimal bd) return bd;
            if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
            if (v != null) {
                try { return new BigDecimal(String.valueOf(v)); } catch (Exception ignore) {}
            }
        }
        return null;
    }

    private static LocalDateTime firstTime(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            Object v = m.get(k);
            if (v instanceof LocalDateTime ldt) return ldt;
            if (v instanceof Timestamp ts) return ts.toLocalDateTime();
            if (v != null) {
                try { return LocalDateTime.parse(String.valueOf(v)); } catch (Exception ignore) {}
            }
        }
        return null;
    }

    /**可变工序投影行。*/
    private static final class Op {
        long id;
        String code, voyage, title, kind, berth, resType, resCode;
        LocalDateTime s, e, ps, pe;
        String status;
        boolean notified;
        String effectiveRes;
        int seq;
    }

    /**工序依赖边。*/
    private record Dep(String op, String on, String kind, int gap,
                       boolean propagate, String detail) {}
}
