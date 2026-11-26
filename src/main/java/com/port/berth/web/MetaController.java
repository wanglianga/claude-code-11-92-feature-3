package com.port.berth.web;

import com.port.berth.repo.CatalogDao;
import com.port.berth.service.PlanService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**前端渲染与 Swagger 所需的全部模拟基础数据*/
@RestController
@RequestMapping("/api/meta")
public class MetaController {

    private final CatalogDao catalog;
    private final PlanService planService;

    public MetaController(CatalogDao catalog, PlanService planService) {
        this.catalog = catalog;
        this.planService = planService;
    }

    @GetMapping
    public Map<String, Object> meta() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ships", catalog.listShips());
        m.put("berths", catalog.listBerths());
        m.put("tides", catalog.listTides());
        m.put("pilots", catalog.listPilots());
        m.put("tugs", catalog.listTugs());
        m.put("cranes", catalog.listCranes());
        m.put("faults", catalog.listFaults());
        m.put("accounts", List.of(
                Map.of("username", "agent", "password", "agent123",
                        "displayName", "王代理（华港船代）", "role", "AGENT"),
                Map.of("username", "dispatch", "password", "dispatch123",
                        "displayName", "赵调度（码头计划室）", "role", "DISPATCHER"),
                Map.of("username", "pilot", "password", "pilot123",
                        "displayName", "孙引航班长", "role", "TEAM"),
                Map.of("username", "tug", "password", "tug123",
                        "displayName", "周拖轮调度", "role", "TEAM"),
                Map.of("username", "crane", "password", "crane123",
                        "displayName", "吴岸桥班长", "role", "TEAM"),
                Map.of("username", "admin", "password", "admin123",
                        "displayName", "系统管理员", "role", "ADMIN")));
        m.put("holdOnStageDefault", planService.isHoldOnStageDefault());
        m.put("feature3CargoImpact", Map.of(
                "headline", "A 船两舱各 +1h → C1 占用至 14:00 撞 B 船 13:30 换班准备；Y-A1 堆位仅剩一半",
                "scenario", "/api/cargo/scenario",
                "givenChange", "/api/cargo/given-change",
                "analyze", "/api/cargo/analyze?mode=DEPENDENCY_CHAIN|FULL_PORT",
                "analyzeBoth", "/api/cargo/analyze-both",
                "replay", "/api/replays/cargo-change",
                "modes", List.of("DEPENDENCY_CHAIN", "FULL_PORT")));
        return m;
    }
}
