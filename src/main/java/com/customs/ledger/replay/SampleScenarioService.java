package com.customs.ledger.replay;

import com.customs.ledger.books.Close;
import com.customs.ledger.books.CloseQueryService;
import com.customs.ledger.books.CloseService;
import com.customs.ledger.books.CloseView;
import com.customs.ledger.books.InventoryService;
import com.customs.ledger.diff.DiscrepancyRepository;
import com.customs.ledger.domain.Doc;
import com.customs.ledger.domain.Ledger;
import com.customs.ledger.domain.LedgerRepository;
import com.customs.ledger.ingest.IngestionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Replayable sample scenario. Creates a fresh ledger and walks it through a realistic month:
 * opening stock, a declaration acknowledged and received short, a mid-month item-code rename,
 * two work orders racing for the same batch, a finished-goods export, a close, then a late
 * customs correction that forces an adjustment version. Every step is recorded with the observed
 * numbers so the run can be compared against expectations.
 */
@Service
public class SampleScenarioService {

    private static final Logger log = LoggerFactory.getLogger(SampleScenarioService.class);

    private final LedgerRepository ledgers;
    private final IngestionService ingestion;
    private final CloseService closeService;
    private final CloseQueryService queries;
    private final InventoryService inventory;
    private final DiscrepancyRepository discrepancies;
    private final ReproduceService reproduceService;
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public SampleScenarioService(LedgerRepository ledgers, IngestionService ingestion, CloseService closeService,
                                 CloseQueryService queries, InventoryService inventory,
                                 DiscrepancyRepository discrepancies, ReproduceService reproduceService,
                                 JdbcClient jdbc, ObjectMapper mapper) {
        this.ledgers = ledgers;
        this.ingestion = ingestion;
        this.closeService = closeService;
        this.queries = queries;
        this.inventory = inventory;
        this.discrepancies = discrepancies;
        this.reproduceService = reproduceService;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public record Step(String action, String detail, Object result) {
    }

    public record Scenario(String ledgerCode, String period, List<Step> steps, Map<String, Object> assertions,
                           boolean allPassed) {
    }

    public Scenario run(String requestedCode, String period) {
        String code = requestedCode == null || requestedCode.isBlank()
                ? "DEMO-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase()
                : requestedCode;
        if (ledgers.findLedger(code).isPresent()) {
            throw com.customs.ledger.common.ApiException.conflict("LEDGER_EXISTS",
                    "ledger " + code + " already exists; use a different code or reset the schema");
        }
        YearMonthView ym = YearMonthView.of(period);
        Ledger ledger = ledgers.createLedger(code, "Replay demo " + code, "Asia/Shanghai");
        seedMapping(ledger);

        List<Step> steps = new ArrayList<>();
        Map<String, Object> assertions = new LinkedHashMap<>();
        boolean ok = true;

        Doc opening = receive(ledger, "OPENING_BALANCE", "ERP", "OB-1", 1, ym.at(1, "08:00"),
                """
                {"lines":[{"itemCode":"AL6061-OLD","unit":"KG","quantity":500,"batchNo":"%s"}]}
                """.formatted(ym.batch("OPEN")));
        steps.add(new Step("opening", "500 KG carried in from the ERP stock take", opening.id()));

        Doc declaration = receive(ledger, "DECLARATION", "CUSTOMS", "DEC-1", 1, ym.at(3, "09:00"),
                """
                {"declarationNo":"DEC-1","lines":[{"itemCode":"AL6061-OLD","unit":"KG","quantity":1000,"batchNo":"%s"}]}
                """.formatted(ym.batch("DEC1")));
        steps.add(new Step("declaration", "customs declaration DEC-1, 1000 KG", declaration.id()));

        receive(ledger, "DECLARATION_RECEIPT", "CUSTOMS", "RCPT-DEC-1", 1, ym.at(4, "09:00"),
                """
                {"declarationNo":"DEC-1","declarationSourceSystem":"CUSTOMS","lines":[{"itemCode":"AL6061-OLD","unit":"KG","quantity":1000,"batchNo":"%s"}]}
                """.formatted(ym.batch("DEC1")));
        receive(ledger, "WAREHOUSE_RECEIPT", "WMS", "WMS-IN-1", 1, ym.at(5, "09:00"),
                """
                {"declarationNo":"DEC-1","declarationSourceSystem":"CUSTOMS","lines":[{"itemCode":"AL6061","unit":"KG","quantity":980,"batchNo":"%s"}]}
                """.formatted(ym.batch("DEC1")));
        steps.add(new Step("receipts", "customs receipt + warehouse receipt (980 KG received, 20 KG short)", null));

        // The item code is renamed mid-month; later documents use the new code.
        closeAlias(ledger, "AL6061-OLD", ym.day(15));
        ledgers.addAlias(ledger.id(), "ERP", "AL6061-NEW",
                ledgers.requireMaterial(ledger.id(), "AL-6061").id(), ym.day(16), null, "ERP rename");
        steps.add(new Step("rename", "source item renamed AL6061-OLD -> AL6061-NEW effective day 16", null));

        receive(ledger, "ISSUE", "MES", "WO-A", 1, ym.at(12, "10:00"),
                """
                {"lines":[{"itemCode":"AL6061-OLD","unit":"KG","quantity":400,"batchNo":"%s","workOrder":"WO-A"}]}
                """.formatted(ym.batch("DEC1")));
        receive(ledger, "ISSUE", "MES", "WO-B", 1, ym.at(12, "10:00"),
                """
                {"lines":[{"itemCode":"AL6061-OLD","unit":"KG","quantity":400,"batchNo":"%s","workOrder":"WO-B"}]}
                """.formatted(ym.batch("DEC1")));
        steps.add(new Step("issues", "two work orders request 400 KG each from the same 980 KG batch", null));

        receive(ledger, "OUTBOUND", "CUSTOMS", "EXP-1", 1, ym.at(22, "10:00"),
                """
                {"lines":[{"itemCode":"FG-HOUSING","unit":"PCS","quantity":1200,"workOrder":"WO-A"}]}
                """);
        steps.add(new Step("export", "1200 finished housings exported, 0.5 KG bonded content each", null));

        CloseService.CloseResult firstClose = closeService.close(code, ym.period(), new CloseService.CloseCommand(
                "MATERIALIZED", "demo", "first close", false, true));
        steps.add(new Step("close", "monthly close version " + firstClose.close().version(),
                Map.of("closeId", firstClose.close().id(), "resultHash", firstClose.close().resultHash())));

        CloseView v1 = queries.view(code, firstClose.close().id(), new CloseQueryService.ViewOptions(true, true));
        CloseView.MaterialBalance al = v1.materials().stream()
                .filter(m -> m.material().equals("AL-6061")).findFirst().orElseThrow();
        assertions.put("v1.balances", Map.of(
                "opening", al.opening().toPlainString(),
                "inbound", al.inbound().toPlainString(),
                "consumed", al.consumed().toPlainString(),
                "outboundContent", al.outboundContent().toPlainString(),
                "closingStock", al.closing().toPlainString(),
                "closingWip", al.wipClosing().toPlainString()));
        ok &= assertClose(v1, assertions, "v1");
        ok &= check("consumed cannot exceed the two issues", al.consumed().compareTo(new BigDecimal("800")) <= 0);

        InventoryService.Report inventoryReport = inventory.report(code, firstClose.close().id());
        steps.add(new Step("inventory", "batch report after close", inventoryReport.wip()));

        // A late customs correction arrives after the month is closed.
        Doc original = ingestion.history(code, "CUSTOMS", "DECLARATION", "DEC-1").getFirst();
        receive(ledger, "DECLARATION", "CUSTOMS", "DEC-1", 2, ym.at(3, "09:00"),
                """
                {"declarationNo":"DEC-1","lines":[{"itemCode":"AL6061-OLD","unit":"KG","quantity":960,"batchNo":"%s"}]}
                """.formatted(ym.batch("DEC1")), original.id(), "customs corrected the declared weight to 960 KG");
        steps.add(new Step("correction", "declaration DEC-1 corrected to 960 KG after close", null));

        CloseService.CloseResult secondClose = closeService.close(code, ym.period(), new CloseService.CloseCommand(
                "MATERIALIZED", "demo", "adjustment after correction", true, true));
        steps.add(new Step("reclose", "adjustment version " + secondClose.close().version(),
                Map.of("closeId", secondClose.close().id(), "resultHash", secondClose.close().resultHash())));

        CloseView v2 = queries.view(code, secondClose.close().id(), new CloseQueryService.ViewOptions(false, false));
        assertions.put("v2.inbound", v2.materials().stream().filter(m -> m.material().equals("AL-6061"))
                .findFirst().orElseThrow().inbound().toPlainString());
        assertions.put("v2.changes", v2.changes().stream().map(CloseView.ChangeView::kind).toList());
        ok &= check("adjustment cites the superseded source version",
                v2.changes().stream().anyMatch(c -> c.kind().equals("SUPERSEDED")));

        ReproduceService.Reproduction old = reproduceService.reproduce(code, firstClose.close().id());
        assertions.put("v1.reproduced", old.hashMatch());
        ok &= check("v1 balance still reproduces after the correction", old.hashMatch());

        Close previousAfter = queries.view(code, firstClose.close().id(), new CloseQueryService.ViewOptions(false, false)).close();
        ok &= check("v1 is retained as SUPERSEDED", previousAfter.status().equals("SUPERSEDED"));

        List<DiscrepancyRepository.Row> openDiscrepancies = discrepancies.query(ledger.id(), ym.period(), "OPEN", null, 100);
        assertions.put("openDiscrepancies", openDiscrepancies.stream()
                .map(d -> Map.of("kind", d.kind(), "message", d.message())).toList());

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("ledgerCode", code);
        detail.put("period", ym.period());
        detail.put("allPassed", ok);
        detail.put("assertions", assertions);
        detail.put("steps", steps);
        jdbc.sql("INSERT INTO sample_replay (ledger_code, scenario, detail) VALUES (:c, :s, CAST(:d AS jsonb))")
                .param("c", code).param("s", "month-end-correction")
                .param("d", write(detail)).update();

        log.info("replay scenario {} period {} allPassed={}", code, ym.period(), ok);
        return new Scenario(code, ym.period(), steps, assertions, ok);
    }

    private boolean assertClose(CloseView view, Map<String, Object> assertions, String label) {
        boolean ok = true;
        for (CloseView.MaterialBalance balance : view.materials()) {
            BigDecimal expected = balance.opening().add(balance.inbound()).subtract(balance.consumed())
                    .add(balance.adjust());
            ok &= check(label + " conservation for " + balance.material(),
                    expected.compareTo(balance.closing()) == 0);
            ok &= check(label + " non-negative " + balance.material(),
                    balance.closing().signum() >= 0 && balance.wipClosing().signum() >= 0);
        }
        assertions.put(label + ".materials", view.materials().size());
        return ok;
    }

    private boolean check(String description, boolean condition) {
        if (!condition) {
            log.warn("scenario assertion failed: {}", description);
        }
        return condition;
    }

    private void seedMapping(Ledger ledger) {
        long alId = ledgers.createMaterial(ledger.id(), "AL-6061", "Aluminium 6061", "KG").id();
        long fgId = ledgers.createMaterial(ledger.id(), "FG-HOUSING", "Housing", "PCS").id();
        ledgers.addAlias(ledger.id(), "ERP", "AL6061-OLD", alId, LocalDate.of(2025, 1, 1), null, null);
        ledgers.addAlias(ledger.id(), "WMS", "AL6061", alId, LocalDate.of(2025, 1, 1), null, null);
        ledgers.addAlias(ledger.id(), "ERP", "FG-HOUSING", fgId, LocalDate.of(2025, 1, 1), null, null);
        ledgers.addAlias(ledger.id(), "CUSTOMS", "FG-HOUSING", fgId, LocalDate.of(2025, 1, 1), null, null);
        ledgers.addConversion(ledger.id(), alId, "TON", new BigDecimal("1000"),
                LocalDate.of(2025, 1, 1), null);
        ledgers.addRelation(ledger.id(), fgId, alId, new BigDecimal("0.5"),
                LocalDate.of(2025, 1, 1), null, null);
    }

    private void closeAlias(Ledger ledger, String sourceItemCode, LocalDate validTo) {
        jdbc.sql("UPDATE material_alias SET valid_to = :d WHERE ledger_id = :l AND source_item_code = :c AND valid_to IS NULL")
                .param("d", validTo).param("l", ledger.id()).param("c", sourceItemCode).update();
    }

    private Doc receive(Ledger ledger, String type, String system, String externalId, int version,
                        OffsetDateTime businessTime, String body) {
        return receive(ledger, type, system, externalId, version, businessTime, body, null, null);
    }

    private Doc receive(Ledger ledger, String type, String system, String externalId, int version,
                        OffsetDateTime businessTime, String body, Long supersedes, String reason) {
        return ingestion.receive(ledger.code(), new IngestionService.IntakeCommand(type, system, externalId,
                version, businessTime, body, null, supersedes, null, reason, true)).doc();
    }

    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Small helper to build ISO timestamps from a period plus day-of-month. */
    record YearMonthView(java.time.YearMonth month) {
        static YearMonthView of(String period) {
            return new YearMonthView(com.customs.ledger.common.Periods.parse(period));
        }

        String period() {
            return com.customs.ledger.common.Periods.format(month);
        }

        OffsetDateTime at(int day, String time) {
            return month.atDay(day).atTime(java.time.LocalTime.parse(time)).atOffset(java.time.ZoneOffset.ofHours(8));
        }

        LocalDate day(int day) {
            return month.atDay(day);
        }

        String batch(String tag) {
            return "B-" + month + "-" + tag;
        }
    }
}
