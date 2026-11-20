package com.customs.ledger.api;

import com.customs.ledger.books.Close;
import com.customs.ledger.books.CloseQueryService;
import com.customs.ledger.books.CloseRepository;
import com.customs.ledger.books.CloseService;
import com.customs.ledger.books.CloseView;
import com.customs.ledger.books.InventoryService;
import com.customs.ledger.common.ApiException;
import com.customs.ledger.common.Periods;
import com.customs.ledger.diff.DiscrepancyRepository;
import com.customs.ledger.domain.Ledger;
import com.customs.ledger.domain.LedgerRepository;
import com.customs.ledger.replay.ReproduceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/ledgers/{code}")
@Tag(name = "Monthly close and reconciliation", description = "Close a period, query balances, drill down to evidence")
public class CloseController {

    private final LedgerRepository ledgers;
    private final CloseService closeService;
    private final CloseQueryService queries;
    private final CloseRepository closes;
    private final DiscrepancyRepository discrepancies;
    private final ReproduceService reproduceService;
    private final InventoryService inventoryService;

    public CloseController(LedgerRepository ledgers, CloseService closeService, CloseQueryService queries,
                           CloseRepository closes, DiscrepancyRepository discrepancies,
                           ReproduceService reproduceService, InventoryService inventoryService) {
        this.ledgers = ledgers;
        this.closeService = closeService;
        this.queries = queries;
        this.closes = closes;
        this.discrepancies = discrepancies;
        this.reproduceService = reproduceService;
        this.inventoryService = inventoryService;
    }

    public record CloseRequest(String mode, String closedBy, String note, Boolean allowOverwrite,
                               Boolean waitForLock) {
    }

    public record CloseResponse(long closeId, String period, int version, String status, String mode,
                                String algorithmVersion, String inputHash, String resultHash,
                                int documentCount, OffsetDateTime closedAt, String closedBy,
                                int openDiscrepancies, List<String> warnings, boolean overwritten,
                                boolean reused) {
    }

    @PostMapping("/periods/{period}/close")
    @Operation(summary = "Close a month", description = """
            Allocates quantities per import batch under a transaction advisory lock, validates opening + inbound
            - issued - outbound + adjustments = closing for every material, and freezes the inputs. If the period
            is already closed and nothing changed, the existing version is returned. If inputs changed, a new
            version is appended (previous one marked SUPERSEDED, still readable) when allowOverwrite=true.
            mode=MATERIALIZED stores the per-batch allocation relation; mode=REBUILD stores only the snapshot
            plus the algorithm version and rebuilds relations at query time; mode=AUTO picks per document volume.""")
    public CloseResponse close(@PathVariable String code, @PathVariable String period,
                               @RequestBody(required = false) CloseRequest request) {
        CloseRequest body = request == null
                ? new CloseRequest(null, null, null, false, false) : request;
        CloseService.CloseResult result = closeService.close(code, period, new CloseService.CloseCommand(
                body.mode(), body.closedBy(), body.note(),
                Boolean.TRUE.equals(body.allowOverwrite()), Boolean.TRUE.equals(body.waitForLock())));
        return toResponse(result);
    }

    @GetMapping("/periods/{period}/close")
    @Operation(summary = "Show the current close version of a period")
    public CloseResponse current(@PathVariable String code, @PathVariable String period) {
        Ledger ledger = ledgers.requireLedger(code);
        Periods.parse(period);
        Close close = closes.latest(ledger.id(), period)
                .orElseThrow(() -> ApiException.notFound("PERIOD_NOT_CLOSED", "period " + period + " is not closed"));
        return toResponse(new CloseService.CloseResult(close,
                openCount(ledger.id(), period), List.of(), false, false));
    }

    @GetMapping("/periods/{period}/history")
    @Operation(summary = "Every close version of a period, newest first",
            description = "Old versions stay readable so a reported balance remains reproducible after corrections.")
    public List<CloseResponse> history(@PathVariable String code, @PathVariable String period) {
        Ledger ledger = ledgers.requireLedger(code);
        Periods.parse(period);
        return closes.history(ledger.id(), period).stream()
                .map(c -> toResponse(new CloseService.CloseResult(c, openCount(ledger.id(), period),
                        List.of(), false, false)))
                .toList();
    }

    @GetMapping("/closes/{closeId}")
    @Operation(summary = "Read a close result (balances, pending differences, changes)")
    public CloseView get(@PathVariable String code, @PathVariable long closeId,
                         @RequestParam(defaultValue = "false") boolean includeAllocations,
                         @RequestParam(defaultValue = "false") boolean verifyHash) {
        return queries.view(code, closeId, new CloseQueryService.ViewOptions(includeAllocations, verifyHash));
    }

    @GetMapping("/closes/{closeId}/balances")
    @Operation(summary = "Material balances of a close")
    public List<CloseView.MaterialBalance> balances(@PathVariable String code, @PathVariable long closeId) {
        return queries.view(code, closeId, new CloseQueryService.ViewOptions(false, false)).materials();
    }

    @GetMapping("/closes/{closeId}/allocations")
    @Operation(summary = "Per-batch allocation relation of a close",
            description = "Trace which import batch supplied which work order / outbound line. Served from the "
                    + "materialized relation, or rebuilt from the frozen snapshot for REBUILD closes.")
    public List<CloseView.BatchAllocation> allocations(@PathVariable String code, @PathVariable long closeId,
                                                       @RequestParam(required = false) String material,
                                                       @RequestParam(required = false) String lotKey) {
        return queries.allocations(code, closeId, material, lotKey);
    }

    @GetMapping("/closes/{closeId}/inventory")
    @Operation(summary = "Per-batch stock and work-in-process position of a close")
    public InventoryService.Report inventory(@PathVariable String code, @PathVariable long closeId) {
        return inventoryService.report(code, closeId);
    }

    @GetMapping("/closes/{closeId}/trace/{material}")
    @Operation(summary = "Trace a material balance back to its supporting declarations, receipts, issues and outbound",
            description = "Walks the batch allocation relation and lists, for each allocation, the source document "
                    + "that produced it, so a customs officer can follow a closing balance to its evidence.")
    public Map<String, Object> trace(@PathVariable String code, @PathVariable long closeId,
                                     @PathVariable String material) {
        Ledger ledger = ledgers.requireLedger(code);
        CloseView view = queries.view(code, closeId, new CloseQueryService.ViewOptions(true, false));
        CloseView.MaterialBalance balance = view.materials().stream()
                .filter(m -> m.material().equals(material)).findFirst()
                .orElseThrow(() -> ApiException.notFound("MATERIAL_NOT_IN_CLOSE",
                        "material " + material + " has no balance in this close"));
        List<CloseView.BatchAllocation> allocations = view.allocations().stream()
                .filter(a -> a.material().equals(material)).toList();

        Map<Long, Map<String, Object>> evidence = new LinkedHashMap<>();
        for (CloseView.BatchAllocation allocation : allocations) {
            if (allocation.documentId() == null || evidence.containsKey(allocation.documentId())) {
                continue;
            }
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("documentId", allocation.documentId());
            doc.put("movementType", allocation.movementType());
            evidence.put(allocation.documentId(), doc);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ledger", ledger.code());
        result.put("period", view.close().period());
        result.put("closeVersion", view.close().version());
        result.put("mode", view.close().mode());
        result.put("material", material);
        result.put("balance", balance);
        result.put("allocations", allocations);
        result.put("evidenceDocuments", evidence.values());
        return result;
    }

    @PostMapping("/closes/{closeId}/reproduce")
    @Operation(summary = "Reproduce a reported close from its frozen snapshot",
            description = "Recomputes the whole period with the stored algorithm version and compares the "
                    + "result balances with the stored ones. Used to prove that a corrected late document did "
                    + "not alter the previously reported balance of an earlier close version.")
    public ReproduceService.Reproduction reproduce(@PathVariable String code, @PathVariable long closeId) {
        return reproduceService.reproduce(code, closeId);
    }

    @GetMapping("/discrepancies")
    @Operation(summary = "Query pending differences and their resolution trail")
    public List<DiscrepancyRepository.Row> discrepancies(@PathVariable String code,
                                                         @RequestParam(required = false) String period,
                                                         @RequestParam(required = false) String status,
                                                         @RequestParam(required = false) String kind,
                                                         @RequestParam(defaultValue = "200") int limit) {
        Ledger ledger = ledgers.requireLedger(code);
        if (period != null) {
            Periods.parse(period);
        }
        return discrepancies.query(ledger.id(), period, status, kind, Math.min(Math.max(limit, 1), 2000));
    }

    public record ResolveRequest(String status, String resolution, String note, String resolvedBy,
                                 Long adjustmentDocumentId) {
    }

    @PostMapping("/discrepancies/{id}/resolve")
    @Operation(summary = "Record the cause and the follow-up adjustment of a pending difference")
    public DiscrepancyRepository.Row resolve(@PathVariable String code, @PathVariable long id,
                                             @RequestBody ResolveRequest request) {
        Ledger ledger = ledgers.requireLedger(code);
        String status = request.status() == null ? "RESOLVED" : request.status().toUpperCase();
        if (!status.equals("RESOLVED") && !status.equals("WAIVED")) {
            throw ApiException.badRequest("INVALID_STATUS", "status must be RESOLVED or WAIVED");
        }
        if (request.resolution() == null || request.resolution().isBlank()) {
            throw ApiException.badRequest("RESOLUTION_REQUIRED", "resolution is required to close a difference");
        }
        discrepancies.resolve(ledger.id(), id, status, request.resolution(), request.note(),
                request.resolvedBy() == null ? "customs" : request.resolvedBy(), request.adjustmentDocumentId());
        return discrepancies.find(ledger.id(), id).orElseThrow();
    }

    private int openCount(long ledgerId, String period) {
        return discrepancies.query(ledgerId, period, "OPEN", null, 5000).size();
    }

    private CloseResponse toResponse(CloseService.CloseResult result) {
        Close c = result.close();
        return new CloseResponse(c.id(), c.period(), c.version(), c.status(), c.mode(), c.algorithmVersion(),
                c.inputHash(), c.resultHash(), c.documentCount(), c.closedAt(), c.closedBy(),
                result.openDiscrepancies(), result.warnings(), result.overwritten(), result.reused());
    }
}
