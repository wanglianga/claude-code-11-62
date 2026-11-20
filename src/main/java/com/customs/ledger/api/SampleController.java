package com.customs.ledger.api;

import com.customs.ledger.replay.SampleScenarioService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Replayable sample", description = "Run a full month-end scenario end to end and check the outcome")
public class SampleController {

    private final SampleScenarioService scenarios;

    public SampleController(SampleScenarioService scenarios) {
        this.scenarios = scenarios;
    }

    public record ReplayRequest(String ledgerCode, String period) {
    }

    @PostMapping("/samples/month-end-replay")
    @Operation(summary = "Run the replayable month-end scenario",
            description = "Creates a fresh ledger, feeds a full month of documents from several source systems, "
                    + "closes the month, applies a late customs correction and verifies that quantity is conserved, "
                    + "the same batch is not over-issued, and the pre-correction balance still reproduces.")
    public SampleScenarioService.Scenario replay(@RequestBody(required = false) ReplayRequest request) {
        ReplayRequest body = request == null ? new ReplayRequest(null, "2026-03") : request;
        String period = body.period() == null ? "2026-03" : body.period();
        return scenarios.run(body.ledgerCode(), period);
    }

    @GetMapping("/samples")
    @Operation(summary = "List sample scenarios that can be replayed")
    public List<Map<String, String>> list() {
        return List.of(Map.of(
                "name", "month-end-replay",
                "endpoint", "POST /api/v1/samples/month-end-replay",
                "description", "opening stock, short receipt, item rename, batch contention, export, close, late correction"));
    }
}
