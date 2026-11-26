package com.port.berth.web;

import com.port.berth.config.WebAuth;
import com.port.berth.domain.Role;
import com.port.berth.service.CargoImpactService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * feature-3：货量变更审计 + 依赖追踪 + 依赖链/全港两种重排影响分析。
 * 所有端点仍在 /api 鉴权拦截范围内（需 Bearer Token）。
 */
@RestController
@RequestMapping("/api")
@Tag(name = "货量变更与双轨影响分析", description = "原申报/复测/来源/时点审计，岸桥·堆位·跨泊位依赖，依赖链 vs 全港重排")
public class CargoImpactController {

    private final CargoImpactService service;

    public CargoImpactController(CargoImpactService service) {
        this.service = service;
    }

    @GetMapping("/cargo/scenario")
    @Operation(summary = "feature-3 固定日间场景（A/B/C/D、C1-C3、Y-A1、工序与依赖、给定变更草稿）")
    public Map<String, Object> scenario() {
        return service.scenarioBundle();
    }

    @GetMapping("/cargo/dependencies")
    @Operation(summary = "工序依赖图，可按船次过滤；分组给出岸桥/堆位/跨泊位窗口依赖")
    public Map<String, Object> dependencies(@RequestParam(required = false) String voyageCode) {
        return service.dependencyGraph(voyageCode);
    }

    @GetMapping("/cargo/changes")
    @Operation(summary = "货量变更审计列表（可按 batchNo 过滤）")
    public List<Map<String, Object>> changes(@RequestParam(required = false) String batchNo) {
        return service.listChanges(batchNo);
    }

    @PostMapping("/cargo/changes")
    @Operation(summary = "录入货量变更：保存原申报、复测值、来源 source、时点 observedAt（支持 changes/items 批量）")
    public List<Map<String, Object>> createChanges(@RequestBody(required = false) Map<String, Object> body,
                                                   HttpServletRequest http) {
        WebAuth.requireRole(http, Role.AGENT, Role.DISPATCHER);
        return service.recordChanges(body);
    }

    @PostMapping("/cargo/given-change")
    @Operation(summary = "幂等写入题目给定变更：A 船 H1/H2 各 +60 分钟（含完整审计四要素）")
    public List<Map<String, Object>> givenChange(HttpServletRequest http) {
        WebAuth.requireRole(http, Role.AGENT, Role.DISPATCHER);
        return service.recordGivenChange();
    }

    @GetMapping("/voyages/{voyageCode}/cargo-changes")
    @Operation(summary = "按船次查看货量变更审计（voyageCode 可为 A）")
    public List<Map<String, Object>> voyageChanges(@PathVariable String voyageCode) {
        return service.listChanges(null).stream()
                .filter(c -> voyageCode.equalsIgnoreCase(String.valueOf(c.get("voyageCode"))))
                .toList();
    }

    @PostMapping("/voyages/{voyageCode}/cargo-changes")
    @Operation(summary = "船次作用域录入货量变更（RESTful 别名）")
    public List<Map<String, Object>> createVoyageChange(@PathVariable String voyageCode,
                                                        @RequestBody(required = false) Map<String, Object> body,
                                                        HttpServletRequest http) {
        WebAuth.requireRole(http, Role.AGENT, Role.DISPATCHER);
        Map<String, Object> payload = body == null ? new LinkedHashMap<>() : body;
        payload.putIfAbsent("voyageCode", voyageCode);
        return service.recordChanges(payload);
    }

    @PostMapping({"/cargo/analyze", "/cargo/impact"})
    @Operation(summary = "按批次做影响分析；body：{batchNo?, mode: DEPENDENCY_CHAIN|FULL_PORT, choices:{...}}；batchNo 留空则分析题目给定变更")
    public Map<String, Object> analyze(@RequestBody(required = false) Map<String, Object> body) {
        String batch = body == null ? null : str(body.get("batchNo"), null);
        String mode = str(body == null ? null : body.get("mode"), "DEPENDENCY_CHAIN");
        @SuppressWarnings("unchecked")
        Map<String, Object> choices = body != null && body.get("choices") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : queryChoices(body);
        // 未显式给批次：幂等补录题目给定变更后直接分析，保证一键可跑
        return (batch == null || batch.isBlank())
                ? service.analyzeGiven(mode, choices)
                : service.analyze(batch, mode, choices);
    }

    @GetMapping({"/cargo/analyze", "/cargo/impact"})
    @Operation(summary = "GET 方式影响分析，例如 /api/cargo/impact?mode=FULL_PORT&crane=C2；不带 batchNo 时自动使用题目给定变更")
    public Map<String, Object> analyzeGet(@RequestParam(required = false) String batchNo,
                                          @RequestParam(defaultValue = "DEPENDENCY_CHAIN") String mode,
                                          @RequestParam(required = false) String crane,
                                          @RequestParam(required = false) String yard,
                                          @RequestParam(required = false) String secondOrder) {
        Map<String, Object> choices = new LinkedHashMap<>();
        if (crane != null) choices.put("crane", crane);
        if (yard != null) choices.put("YARD_CAPACITY", yard);
        if (secondOrder != null) choices.put("SECOND_ORDER_C1_BC", secondOrder);
        return (batchNo == null || batchNo.isBlank())
                ? service.analyzeGiven(mode, choices)
                : service.analyze(batchNo, mode, choices);
    }

    @PostMapping("/cargo/analyze-given")
    @Operation(summary = "写入给定两舱变更并分析（默认依赖链模式）")
    public Map<String, Object> analyzeGivenPost(@RequestBody(required = false) Map<String, Object> body) {
        String mode = str(body == null ? null : body.get("mode"), "DEPENDENCY_CHAIN");
        @SuppressWarnings("unchecked")
        Map<String, Object> choices = body != null && body.get("choices") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
        return service.analyzeGiven(mode, choices);
    }

    @GetMapping("/cargo/analyze-given")
    @Operation(summary = "写入给定变更并分析，?mode=FULL_PORT 或 DEPENDENCY_CHAIN")
    public Map<String, Object> analyzeGivenGet(@RequestParam(defaultValue = "DEPENDENCY_CHAIN") String mode,
                                              @RequestParam(required = false) String crane) {
        Map<String, Object> choices = new LinkedHashMap<>();
        if (crane != null) choices.put("crane", crane);
        return service.analyzeGiven(mode, choices);
    }

    @PostMapping({"/cargo/analyze-both", "/cargo/impact-both"})
    @Operation(summary = "一次返回依赖链与全港两种结果及对照（受影响船次/作业顺序/人工冲突）")
    public Map<String, Object> both(@RequestBody(required = false) Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        Map<String, Object> choices = body != null && body.get("choices") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
        return service.analyzeBoth(choices);
    }

    @GetMapping({"/cargo/analyze-both", "/cargo/impact-both"})
    @Operation(summary = "GET 一次返回两种结果；?crane=C2 可演示人工改派效果")
    public Map<String, Object> bothGet(@RequestParam(required = false) String crane) {
        Map<String, Object> choices = new LinkedHashMap<>();
        if (crane != null) choices.put("crane", crane);
        return service.analyzeBoth(choices);
    }

    @GetMapping("/cargo/reports")
    @Operation(summary = "历史影响分析报告（最近 50 条）")
    public List<Map<String, Object>> reports() {
        return service.listReports();
    }

    @GetMapping("/cargo/reports/{id}")
    @Operation(summary = "读取某次影响分析报告完整 JSON")
    public Object report(@PathVariable long id) {
        return service.getReport(id);
    }

    private Map<String, Object> queryChoices(Map<String, Object> body) {
        Map<String, Object> c = new LinkedHashMap<>();
        if (body == null) return c;
        for (String k : List.of("crane", "yard", "CRANE_C1_PREP", "YARD_CAPACITY",
                "SECOND_ORDER_C1_BC")) {
            Object v = body.get(k);
            if (v != null) c.put(k, v);
        }
        return c;
    }

    private static String str(Object o, String def) {
        return o == null || String.valueOf(o).isBlank() ? def : String.valueOf(o);
    }
}
