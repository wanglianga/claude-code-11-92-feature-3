package com.port.berth.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * feature-3 一键回放：重置 → 录入给定货量变更（审计四要素）→
 * 分别用依赖链 / 全港 / 人工改派选择做断言，并在数据库层证明
 * 已完成装卸记录与审计记录不可改不可删、基线计划不被分析改写。
 */
@Service
public class CargoReplayService {

    private final SeedService seedService;
    private final CargoImpactService cargo;
    private final JdbcTemplate jdbc;

    public CargoReplayService(SeedService seedService, CargoImpactService cargo, JdbcTemplate jdbc) {
        this.seedService = seedService;
        this.cargo = cargo;
        this.jdbc = jdbc;
    }

    public Map<String, Object> run() {
        seedService.resetAndSeed();
        List<Map<String, Object>> checks = new ArrayList<>();

        // ---- 审计录入 ----
        List<Map<String, Object>> saved = cargo.recordGivenChange();
        check(checks, "保存 A 船两个舱口的货量变更（2 条）", saved.size() == 2, 2, saved.size());
        Map<String, Object> h1 = saved.stream().filter(c -> "H1".equals(c.get("hatchCode")))
                .findFirst().orElseThrow();
        boolean auditFour = h1.get("originalValue") != null
                && h1.get("remeasuredValue") != null
                && h1.get("source") != null
                && h1.get("observedAt") != null
                && Integer.valueOf(60).equals(h1.get("deltaMinutes"));
        check(checks, "审计含原申报/复测值/来源/时点且每舱 +60m", auditFour,
                "original+remeasured+source+observedAt, delta=60",
                Map.of("original", h1.get("originalValue"),
                        "remeasured", h1.get("remeasuredValue"),
                        "source", h1.get("source"),
                        "observedAt", String.valueOf(h1.get("observedAt")),
                        "deltaMinutes", h1.get("deltaMinutes")));

        int recordsBefore = cargo.listHandlingRecords().size();
        check(checks, "场景含 2 条已完成装卸台账", recordsBefore == 2, 2, recordsBefore);

        // ---- 依赖链模式 ----
        Map<String, Object> chain = cargo.analyze(
                CargoImpactService.GIVEN_BATCH, "DEPENDENCY_CHAIN", null);
        @SuppressWarnings("unchecked")
        List<String> chainAffected = (List<String>) chain.get("affectedVoyageCodes");
        check(checks, "依赖链受影响船次恰为 A、B（不扩散到 C/D）",
                chainAffected.equals(List.of("A", "B")), "[A, B]", chainAffected);

        Map<String, Object> aH1 = findOp(chain, "A-H1");
        Map<String, Object> bH1 = findOp(chain, "B-H1");
        Map<String, Object> bPrep = findOp(chain, "B-PREP");
        Map<String, Object> bUnberth = findOp(chain, "B-UNBERTH");
        check(checks, "A-H1 一舱 +1h 后结束于 14:00（C1 占用到 14:00）",
                hourMin(aH1.get("revisedEnd")) != null
                        && hourMin(aH1.get("revisedEnd")).equals("14:00")
                        && "VOLUME_EXTENDED".equals(aH1.get("state")),
                "14:00 / VOLUME_EXTENDED",
                hourMin(aH1.get("revisedEnd")) + " / " + aH1.get("state"));
        check(checks, "A-H2 二舱 +1h 后结束于 13:30",
                "13:30".equals(hourMin(findOp(chain, "A-H2").get("revisedEnd"))),
                "13:30", hourMin(findOp(chain, "A-H2").get("revisedEnd")));
        check(checks, "A 平舱/离泊与 C1 转场各顺延 60m",
                Long.valueOf(60L).equals(findOp(chain, "A-TRIM").get("startDelayMinutes"))
                        && "14:30".equals(hourMin(findOp(chain, "A-UNBERTH").get("revisedStart")))
                        && "14:20".equals(hourMin(findOp(chain, "A-XFER-C1").get("revisedEnd"))),
                "TRIM +60, UNBERTH 14:30, XFER end 14:20",
                "TRIM+" + findOp(chain, "A-TRIM").get("startDelayMinutes")
                        + ", UNBERTH " + hourMin(findOp(chain, "A-UNBERTH").get("revisedStart"))
                        + ", XFER end " + hourMin(findOp(chain, "A-XFER-C1").get("revisedEnd")));
        check(checks, "共享 C1 下游不漏 B：B-H1 最早 14:20、顺延 20m",
                "14:20".equals(hourMin(bH1.get("revisedStart")))
                        && Long.valueOf(20L).equals(bH1.get("startDelayMinutes")),
                "B-H1 14:20 (+20)",
                hourMin(bH1.get("revisedStart")) + " (+" + bH1.get("startDelayMinutes") + ")");
        check(checks, "B 船 13:30 换班准备保持已通知时刻但标记需人工裁决的 C1 冲突",
                "13:30".equals(hourMin(bPrep.get("baselineStart")))
                        && "NEEDS_DECISION".equals(bPrep.get("state"))
                        && Boolean.TRUE.equals(bPrep.get("inConflict")),
                "baseline 13:30 / NEEDS_DECISION / inConflict",
                hourMin(bPrep.get("baselineStart")) + " / " + bPrep.get("state")
                        + " / " + bPrep.get("inConflict"));
        check(checks, "B 离泊顺延 20m 至 16:50",
                "16:50".equals(hourMin(bUnberth.get("revisedStart")))
                        && Long.valueOf(20L).equals(bUnberth.get("startDelayMinutes")),
                "16:50 (+20)",
                hourMin(bUnberth.get("revisedStart")) + " (+" + bUnberth.get("startDelayMinutes") + ")");

        Map<String, Object> prepConflict = findConflict(chain, "CRANE_PREP_OVERLAP");
        boolean prepOverlap30 = prepConflict != null
                && prepConflict.get("window") instanceof Map<?, ?> pw
                && pw.get("overlapMinutes") instanceof Number om
                && om.intValue() == 30;
        check(checks, "列出 C1×B 换班准备冲突且重叠 30 分钟、含人工选项",
                prepOverlap30
                        && ((List<?>) prepConflict.get("options")).size() >= 2,
                "CRANE_PREP_OVERLAP overlap=30 options>=2",
                prepConflict == null ? "缺失"
                        : prepConflict.get("window") + " options="
                                + ((List<?>) prepConflict.get("options")).size());
        Map<String, Object> yardConflict = findConflict(chain, "YARD_CAPACITY_SHORTFALL");
        boolean yardShort40 = yardConflict != null
                && yardConflict.get("capacity") instanceof Map<?, ?> yc
                && yc.get("shortfallUnits") instanceof Number su
                && su.intValue() == 40;
        check(checks, "列出 Y-A1 半容量冲突且缺口 40 TEU、含溢流/疏港选项",
                yardShort40
                        && ((List<?>) yardConflict.get("options")).size() >= 2,
                "shortfall=40 options>=2",
                yardConflict == null ? "缺失" : yardConflict.get("capacity"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> watch = (List<Map<String, Object>>) chain.get("downstreamWatch");
        boolean cWatch = watch.stream().anyMatch(w ->
                "SHARED_CRANE_DOWNSTREAM_WATCH".equals(w.get("code"))
                        && "C".equals(w.get("voyageCode"))
                        && ((Number) w.get("potentialDelayMinutes")).intValue() == 20);
        check(checks, "依赖链对共享岸桥再扫描：预警 C 船 C1 二阶 +20m（不把 C 计入受影响）",
                cWatch && !chainAffected.contains("C"),
                "watch C +20 且 affected=[A,B]",
                watch.stream().map(w -> w.get("voyageCode") + ":" + w.get("potentialDelayMinutes")).toList());

        assertRetained(checks, chain, 4, recordsBefore, "依赖链");

        // ---- 全港模式 ----
        Map<String, Object> full = cargo.analyze(
                CargoImpactService.GIVEN_BATCH, "FULL_PORT", null);
        @SuppressWarnings("unchecked")
        List<String> fullAffected = (List<String>) full.get("affectedVoyageCodes");
        check(checks, "全港重排受影响船次为 A、B、C（D 仍不动）",
                fullAffected.equals(List.of("A", "B", "C")), "[A, B, C]", fullAffected);
        Map<String, Object> cH1 = findOp(full, "C-H1");
        check(checks, "全港较早坐实二阶冲突：C-H1 被全局平衡顺延至 16:40 (+20m)",
                "16:40".equals(hourMin(cH1.get("revisedStart")))
                        && Long.valueOf(20L).equals(cH1.get("startDelayMinutes")),
                "C-H1 16:40 (+20)",
                hourMin(cH1.get("revisedStart")) + " (+" + cH1.get("startDelayMinutes") + ")");
        check(checks, "C-UNBERTH 随 C-H1 连锁顺延",
                Long.valueOf(20L).equals(findOp(full, "C-UNBERTH").get("startDelayMinutes")),
                "+20", findOp(full, "C-UNBERTH").get("startDelayMinutes"));
        check(checks, "全港列出 SECOND_ORDER_CRANE_CASCADE 人工冲突",
                findConflict(full, "SECOND_ORDER_CRANE_CASCADE") != null,
                "存在二阶冲突",
                full.get("manualConflicts") instanceof List<?> l
                        ? l.stream().map(x -> ((Map<?, ?>) x).get("code")).toList() : "?");
        assertRetained(checks, full, 4, recordsBefore, "全港");

        // ---- 人工选择：B 改派 C2 ----
        Map<String, Object> choice = Map.of("CRANE_C1_PREP", "REASSIGN_B_TO_C2");
        Map<String, Object> chain2 = cargo.analyze(
                CargoImpactService.GIVEN_BATCH, "DEPENDENCY_CHAIN", choice);
        Map<String, Object> bH1b = findOp(chain2, "B-H1");
        check(checks, "人工选择 B 改 C2 后：B-H1 准点 14:00、资源为 C2",
                "14:00".equals(hourMin(bH1b.get("revisedStart")))
                        && "C2".equals(bH1b.get("resourceCode"))
                        && Long.valueOf(0L).equals(bH1b.get("startDelayMinutes")),
                "B-H1 14:00 / C2 / +0",
                hourMin(bH1b.get("revisedStart")) + " / " + bH1b.get("resourceCode")
                        + " / +" + bH1b.get("startDelayMinutes"));
        check(checks, "改派后 C1×换班准备冲突消失（堆位冲突仍需选择）",
                findConflict(chain2, "CRANE_PREP_OVERLAP") == null
                        && findConflict(chain2, "YARD_CAPACITY_SHORTFALL") != null,
                "无 PREP 冲突、保留 YARD 冲突",
                chain2.get("manualConflicts") instanceof List<?> l
                        ? l.stream().map(x -> ((Map<?, ?>) x).get("code")).toList() : "?");

        Map<String, Object> full2 = cargo.analyze(
                CargoImpactService.GIVEN_BATCH, "FULL_PORT", choice);
        @SuppressWarnings("unchecked")
        List<String> full2Affected = (List<String>) full2.get("affectedVoyageCodes");
        check(checks, "改派 C2 后全港也能保住无关 C 船：C-H1 准点、受影响集合缩回 A/B",
                "16:20".equals(hourMin(findOp(full2, "C-H1").get("revisedStart")))
                        && full2Affected.equals(List.of("A", "B"))
                        && findConflict(full2, "SECOND_ORDER_CRANE_CASCADE") == null,
                "C-H1 16:20, affected [A,B], 无二阶冲突",
                "C-H1 " + hourMin(findOp(full2, "C-H1").get("revisedStart"))
                        + ", affected " + full2Affected);

        // ---- 基线不被 what-if 分析改写 ----
        Map<String, Object> dbA1 = jdbc.queryForMap(
                "SELECT start_at,end_at FROM port_operation WHERE op_code='A-H1'");
        LocalDateTime a1s = dbA1.get("start_at") instanceof java.sql.Timestamp ts
                ? ts.toLocalDateTime() : (LocalDateTime) dbA1.get("start_at");
        LocalDateTime a1e = dbA1.get("end_at") instanceof java.sql.Timestamp ts
                ? ts.toLocalDateTime() : (LocalDateTime) dbA1.get("end_at");
        check(checks, "多次分析后基线 A-H1 仍是 10:30–13:00（投影不落库改写）",
                "10:30".equals(fmt(a1s)) && "13:00".equals(fmt(a1e)),
                "10:30–13:00", fmt(a1s) + "–" + fmt(a1e));

        // ---- 数据库层不可变自证 ----
        Long hrId = cargo.listHandlingRecords().get(0).get("id") instanceof Number n
                ? n.longValue() : null;
        Map<String, Object> hrUpd = prove("UPDATE handling_record SET result_note='HACKED' WHERE id=" + hrId);
        Map<String, Object> hrDel = prove("DELETE FROM handling_record WHERE id=" + hrId);
        check(checks, "数据库阻止 UPDATE 已完成装卸记录",
                Boolean.TRUE.equals(hrUpd.get("blocked")), true, hrUpd);
        check(checks, "数据库阻止 DELETE 已完成装卸记录",
                Boolean.TRUE.equals(hrDel.get("blocked")), true, hrDel);

        Number ccId = (Number) saved.get(0).get("id");
        Map<String, Object> ccUpd = prove(
                "UPDATE cargo_change SET source='HACKED' WHERE id=" + ccId.longValue());
        Map<String, Object> ccDel = prove(
                "DELETE FROM cargo_change WHERE id=" + ccId.longValue());
        check(checks, "数据库阻止 UPDATE/DELETE 货量变更审计",
                Boolean.TRUE.equals(ccUpd.get("blocked")) && Boolean.TRUE.equals(ccDel.get("blocked")),
                "both blocked", Map.of("update", ccUpd, "delete", ccDel));

        int recordsAfter = cargo.listHandlingRecords().size();
        int changesAfter = cargo.listChanges(CargoImpactService.GIVEN_BATCH).size();
        check(checks, "对抗性篡改后记录条数不变（装卸 2、审计 2）",
                recordsAfter == 2 && changesAfter == 2, "2 / 2",
                recordsAfter + " / " + changesAfter);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("title", "feature-3：货量变更审计 + 依赖链/全港双结果 + 已完成记录固化");
        out.put("checks", checks);
        out.put("allPassed", checks.stream().allMatch(c -> Boolean.TRUE.equals(c.get("pass"))));
        out.put("dependencyChain", chain);
        out.put("fullPort", full);
        out.put("reassignChoice", Map.of("dependencyChain", chain2, "fullPort", full2));
        out.put("immutableProofs", Map.of(
                "handlingUpdate", hrUpd, "handlingDelete", hrDel,
                "cargoChangeUpdate", ccUpd, "cargoChangeDelete", ccDel));
        return out;
    }

    private void assertRetained(List<Map<String, Object>> checks, Map<String, Object> report,
                                 int doneOps, int records, String label) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> retained =
                (List<Map<String, Object>>) report.get("retainedCompletedOperations");
        check(checks, label + "：" + doneOps + " 个已完成工序全部保留且时间不移动",
                retained.size() == doneOps && retained.stream().allMatch(o ->
                        "RETAINED_COMPLETED".equals(o.get("state"))
                                && Long.valueOf(0L).equals(o.get("startDelayMinutes"))),
                doneOps + " RETAINED_COMPLETED +0",
                retained.size());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> recs =
                (List<Map<String, Object>>) report.get("completedHandlingRecords");
        check(checks, label + "：" + records + " 条已完成装卸记录原样保留",
                recs.size() == records, records, recs.size());
    }

    private Map<String, Object> prove(String sql) {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            jdbc.update(sql);
            m.put("blocked", false);
        } catch (Exception e) {
            m.put("blocked", true);
            Throwable t = e;
            while (t.getCause() != null && t.getCause() != t) t = t.getCause();
            m.put("error", t.getMessage());
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> findOp(Map<String, Object> report, String code) {
        for (Map<String, Object> vs : (List<Map<String, Object>>) report.get("operationSequences")) {
            for (Map<String, Object> op : (List<Map<String, Object>>) vs.get("sequence")) {
                if (code.equals(op.get("opCode"))) return op;
            }
        }
        throw new IllegalStateException("报告中缺少工序 " + code);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> findConflict(Map<String, Object> report, String code) {
        for (Map<String, Object> c : (List<Map<String, Object>>) report.get("manualConflicts")) {
            if (code.equals(c.get("code"))) return c;
        }
        return null;
    }

    private Map<String, Object> check(List<Map<String, Object>> checks, String name,
                                      boolean pass, Object expected, Object actual) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("name", name);
        c.put("pass", pass);
        c.put("expected", expected);
        c.put("actual", actual);
        checks.add(c);
        return c;
    }

    private static String hourMin(Object ldt) {
        if (ldt instanceof LocalDateTime t) return fmt(t);
        return null;
    }

    private static String fmt(LocalDateTime t) {
        return t == null ? null : String.format("%02d:%02d", t.getHour(), t.getMinute());
    }
}
