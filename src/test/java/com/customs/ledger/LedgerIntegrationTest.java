package com.customs.ledger;

import com.customs.ledger.books.Close;
import com.customs.ledger.books.CloseQueryService;
import com.customs.ledger.books.CloseRepository;
import com.customs.ledger.books.CloseService;
import com.customs.ledger.books.CloseView;
import com.customs.ledger.books.InventoryService;
import com.customs.ledger.common.ApiException;
import com.customs.ledger.diff.DiscrepancyRepository;
import com.customs.ledger.domain.Doc;
import com.customs.ledger.domain.Ledger;
import com.customs.ledger.domain.LedgerRepository;
import com.customs.ledger.domain.Material;
import com.customs.ledger.ingest.IngestionService;
import com.customs.ledger.replay.ReproduceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@DirtiesContext
class LedgerIntegrationTest {

    @Autowired LedgerRepository ledgers;
    @Autowired IngestionService ingestion;
    @Autowired CloseService closeService;
    @Autowired CloseQueryService queries;
    @Autowired CloseRepository closes;
    @Autowired DiscrepancyRepository discrepancies;
    @Autowired InventoryService inventory;
    @Autowired ReproduceService reproduceService;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;

    private Ledger ledger;

    @BeforeEach
    void setUp() {
        String code = "IT-" + UUID.randomUUID().toString().substring(0, 8);
        ledger = ledgers.createLedger(code, "Integration " + code, "Asia/Shanghai");
        ledgers.createMaterial(ledger.id(), "AL", "Aluminium", "KG");
        ledgers.createMaterial(ledger.id(), "FG", "Housing", "PCS");
        ledgers.addAlias(ledger.id(), null, "AL", ledgers.requireMaterial(ledger.id(), "AL").id(),
                LocalDate.of(2026, 1, 1), null, null);
        ledgers.addAlias(ledger.id(), null, "FG", ledgers.requireMaterial(ledger.id(), "FG").id(),
                LocalDate.of(2026, 1, 1), null, null);
        ledgers.addConversion(ledger.id(), ledgers.requireMaterial(ledger.id(), "AL").id(), "TON",
                new BigDecimal("1000"), LocalDate.of(2026, 1, 1), null);
        ledgers.addRelation(ledger.id(), ledgers.requireMaterial(ledger.id(), "FG").id(),
                ledgers.requireMaterial(ledger.id(), "AL").id(), new BigDecimal("0.5"),
                LocalDate.of(2026, 1, 1), null, null);
    }

    private Doc receive(String type, String system, String externalId, int version, String time, String body) {
        return receive(type, system, externalId, version, time, body, null, null);
    }

    private Doc receive(String type, String system, String externalId, int version, String time, String body,
                        Long supersedes, String reason) {
        IngestionService.IntakeResult result = ingestion.receive(ledger.code(),
                new IngestionService.IntakeCommand(type, system, externalId, version,
                        OffsetDateTime.parse(time), body, null, supersedes, null, reason, true));
        return result.doc();
    }

    private CloseService.CloseResult close(String period, boolean overwrite) {
        return closeService.close(ledger.code(), period, new CloseService.CloseCommand(
                "MATERIALIZED", "tester", null, overwrite, true));
    }

    @Test
    void closeIsIdempotentAndReproducible() {
        receive("DECLARATION", "CUSTOMS", "D1", 1, "2026-03-02T09:00:00+08:00",
                "{\"declarationNo\":\"D1\",\"lines\":[{\"itemCode\":\"AL\",\"unit\":\"KG\",\"quantity\":1000,\"batchNo\":\"B1\"}]}");
        receive("DECLARATION_RECEIPT", "CUSTOMS", "R1", 1, "2026-03-02T15:00:00+08:00",
                "{\"declarationNo\":\"D1\",\"lines\":[{\"itemCode\":\"AL\",\"unit\":\"KG\",\"quantity\":1000,\"batchNo\":\"B1\"}]}");

        CloseService.CloseResult first = close("2026-03", false);
        CloseService.CloseResult again = close("2026-03", false);
        assertThat(again.reused()).isTrue();
        assertThat(again.close().id()).isEqualTo(first.close().id());

        ReproduceService.Reproduction reproduction = reproduceService.reproduce(ledger.code(), first.close().id());
        assertThat(reproduction.hashMatch()).isTrue();
        assertThat(reproduction.balances()).allMatch(ReproduceService.BalanceComparison::equal);
    }

    @Test
    void concurrentClosesOfTheSamePeriodAreSerialized() throws Exception {
        receive("DECLARATION", "CUSTOMS", "D1", 1, "2026-03-02T09:00:00+08:00",
                "{\"declarationNo\":\"D1\",\"lines\":[{\"itemCode\":\"AL\",\"unit\":\"KG\",\"quantity\":500,\"batchNo\":\"B1\"}]}");
        receive("DECLARATION_RECEIPT", "CUSTOMS", "R1", 1, "2026-03-02T10:00:00+08:00",
                "{\"declarationNo\":\"D1\",\"lines\":[{\"itemCode\":\"AL\",\"unit\":\"KG\",\"quantity\":500,\"batchNo\":\"B1\"}]}");

        int threads = 6;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Object>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit((Callable<Object>) () -> {
                start.await();
                try {
                    return closeService.close(ledger.code(), "2026-03",
                            new CloseService.CloseCommand("MATERIALIZED", "racer", null, false, true));
                } catch (ApiException e) {
                    return e.getCode();
                }
            }));
        }
        start.countDown();
        List<Object> outcomes = new ArrayList<>();
        for (Future<Object> future : futures) {
            outcomes.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        // Serialized: exactly one version was written; every other request either reused it or lost the
        // lock race, but no duplicate version rows exist.
        List<Close> history = closes.history(ledger.id(), "2026-03");
        assertThat(history).hasSize(1);
        long reused = outcomes.stream().filter(o -> o instanceof CloseService.CloseResult).count();
        assertThat(reused).isGreaterThanOrEqualTo(1);
    }

    @Test
    void twoWorkOrdersCannotMakeTotalIssueExceedInbound() {
        receive("DECLARATION", "CUSTOMS", "D1", 1, "2026-03-02T09:00:00+08:00",
                "{\"declarationNo\":\"D1\",\"lines\":[{\"itemCode\":\"AL\",\"unit\":\"KG\",\"quantity\":500,\"batchNo\":\"B1\"}]}");
        receive("DECLARATION_RECEIPT", "CUSTOMS", "R1", 1, "2026-03-02T10:00:00+08:00",
                "{\"declarationNo\":\"D1\",\"lines\":[{\"itemCode\":\"AL\",\"unit\":\"KG\",\"quantity\":500,\"batchNo\":\"B1\"}]}");
        receive("ISSUE", "MES", "WO-1", 1, "2026-03-10T10:00:00+08:00",
                "{\"lines\":[{\"itemCode\":\"AL\",\"unit\":\"KG\",\"quantity\":300,\"batchNo\":\"B1\",\"workOrder\":\"WO-1\"}]}");
        receive("ISSUE", "MES", "WO-2", 1, "2026-03-10T10:00:00+08:00",
                "{\"lines\":[{\"itemCode\":\"AL\",\"unit\":\"KG\",\"quantity\":300,\"batchNo\":\"B1\",\"workOrder\":\"WO-2\"}]}");

        CloseService.CloseResult result = close("2026-03", false);
        CloseView view = queries.view(ledger.code(), result.close().id(),
                new CloseQueryService.ViewOptions(true, false));
        CloseView.MaterialBalance al = view.materials().stream()
                .filter(m -> m.material().equals("AL")).findFirst().orElseThrow();

        assertThat(al.consumed()).isEqualByComparingTo("500");
        assertThat(al.closing()).isEqualByComparingTo("0");
        BigDecimal allocated = view.allocations().stream()
                .filter(a -> a.movementType().equals("ISSUE"))
                .map(CloseView.BatchAllocation::quantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(allocated).isEqualByComparingTo("500");
        assertThat(view.discrepancies()).anyMatch(d -> d.kind().equals("ISSUE_SHORTAGE"));
    }

    @Test
    void lateCorrectionCreatesNewVersionAndOldResultStillReproduces() {
        receive("DECLARATION", "CUSTOMS", "D1", 1, "2026-03-02T09:00:00+08:00",
                "{\"declarationNo\":\"D1\",\"lines\":[{\"itemCode\":\"AL\",\"unit\":\"KG\",\"quantity\":1000,\"batchNo\":\"B1\"}]}");
        receive("DECLARATION_RECEIPT", "CUSTOMS", "R1", 1, "2026-03-02T10:00:00+08:00",
                "{\"declarationNo\":\"D1\",\"lines\":[{\"itemCode\":\"AL\",\"unit\":\"KG\",\"quantity\":1000,\"batchNo\":\"B1\"}]}");
        CloseService.CloseResult v1 = close("2026-03", false);
        BigDecimal reportedClosing = queries.view(ledger.code(), v1.close().id(),
                        new CloseQueryService.ViewOptions(false, false)).materials().getFirst().closing();
        assertThat(reportedClosing).isEqualByComparingTo("1000");

        Doc original = ingestion.history(ledger.code(), "CUSTOMS", "DECLARATION", "D1").getFirst();
        receive("DECLARATION", "CUSTOMS", "D1", 2, "2026-03-02T09:00:00+08:00",
                "{\"declarationNo\":\"D1\",\"lines\":[{\"itemCode\":\"AL\",\"unit\":\"KG\",\"quantity\":950,\"batchNo\":\"B1\"}]}",
                original.id(), "customs corrected the declared weight");

        assertThatThrownBy(() -> close("2026-03", false))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "CLOSE_INPUTS_CHANGED");

        CloseService.CloseResult v2 = close("2026-03", true);
        assertThat(v2.close().version()).isEqualTo(2);
        assertThat(v2.close().adjustmentOfCloseId()).isEqualTo(v1.close().id());

        CloseView v2View = queries.view(ledger.code(), v2.close().id(),
                new CloseQueryService.ViewOptions(false, false));
        assertThat(v2View.materials().getFirst().inbound()).isEqualByComparingTo("950");
        assertThat(v2View.changes()).anyMatch(c -> c.kind().equals("SUPERSEDED")
                && c.priorDocumentId() != null && c.documentId() != null);

        // The originally reported balance must still recompute (reproducibility).
        ReproduceService.Reproduction old = reproduceService.reproduce(ledger.code(), v1.close().id());
        assertThat(old.hashMatch()).isTrue();
        assertThat(old.balances().getFirst().reproducedClosing()).isEqualByComparingTo("1000");

        // And the earlier version is marked superseded, not deleted.
        assertThat(closes.requireById(v1.close().id()).status()).isEqualTo("SUPERSEDED");
    }

    @Test
    void rebuildModeProducesSameBalancesWithoutMaterializedAllocations() {
        receive("DECLARATION", "CUSTOMS", "D1", 1, "2026-03-02T09:00:00+08:00",
                "{\"declarationNo\":\"D1\",\"lines\":[{\"itemCode\":\"AL\",\"unit\":\"KG\",\"quantity\":1000,\"batchNo\":\"B1\"}]}");
        receive("DECLARATION_RECEIPT", "CUSTOMS", "R1", 1, "2026-03-02T10:00:00+08:00",
                "{\"declarationNo\":\"D1\",\"lines\":[{\"itemCode\":\"AL\",\"unit\":\"KG\",\"quantity\":1000,\"batchNo\":\"B1\"}]}");
        receive("ISSUE", "MES", "WO-1", 1, "2026-03-10T10:00:00+08:00",
                "{\"lines\":[{\"itemCode\":\"AL\",\"unit\":\"KG\",\"quantity\":200,\"batchNo\":\"B1\",\"workOrder\":\"WO-1\"}]}");

        CloseService.CloseResult rebuild = closeService.close(ledger.code(), "2026-03",
                new CloseService.CloseCommand("REBUILD", "tester", null, false, true));
        assertThat(rebuild.close().mode()).isEqualTo("REBUILD");

        CloseView view = queries.view(ledger.code(), rebuild.close().id(),
                new CloseQueryService.ViewOptions(true, true));
        assertThat(view.materialized()).isFalse();
        assertThat(view.allocations()).isNotEmpty();
        assertThat(view.warnings()).anyMatch(w -> w.contains("REBUILD"));
        assertThat(view.materials().stream().filter(m -> m.material().equals("AL")).findFirst().orElseThrow()
                .closing()).isEqualByComparingTo("800");
        Integer materialized = jdbc.sql("SELECT count(*) FROM close_batch_allocation WHERE close_id = :c")
                .param("c", rebuild.close().id()).query(Integer.class).single();
        assertThat(materialized).isZero();
    }

    @Test
    void effectiveDatedMappingIsAppliedPerDocumentBusinessDate() {
        // Rename mid-period: the first issue uses the old code, the second the new code.
        receive("DECLARATION", "CUSTOMS", "D1", 1, "2026-03-02T09:00:00+08:00",
                "{\"declarationNo\":\"D1\",\"lines\":[{\"itemCode\":\"AL\",\"unit\":\"KG\",\"quantity\":1000,\"batchNo\":\"B1\"}]}");
        receive("DECLARATION_RECEIPT", "CUSTOMS", "R1", 1, "2026-03-02T10:00:00+08:00",
                "{\"declarationNo\":\"D1\",\"lines\":[{\"itemCode\":\"AL\",\"unit\":\"KG\",\"quantity\":1000,\"batchNo\":\"B1\"}]}");
        receive("ISSUE", "MES", "WO-OLD", 1, "2026-03-05T10:00:00+08:00",
                "{\"lines\":[{\"itemCode\":\"AL\",\"unit\":\"KG\",\"quantity\":100,\"batchNo\":\"B1\",\"workOrder\":\"WO-OLD\"}]}");

        Material al = ledgers.requireMaterial(ledger.id(), "AL");
        // Close the old mapping on 2026-03-10 and open a new source code from 2026-03-11.
        jdbc.sql("UPDATE material_alias SET valid_to = DATE '2026-03-10' WHERE ledger_id = :l AND source_item_code = 'AL'")
                .param("l", ledger.id()).update();
        ledgers.addAlias(ledger.id(), null, "AL-RENAMED", al.id(), LocalDate.of(2026, 3, 11), null, "renamed");
        receive("ISSUE", "MES", "WO-NEW", 1, "2026-03-20T10:00:00+08:00",
                "{\"lines\":[{\"itemCode\":\"AL-RENAMED\",\"unit\":\"KG\",\"quantity\":150,\"batchNo\":\"B1\",\"workOrder\":\"WO-NEW\"}]}");

        CloseService.CloseResult result = close("2026-03", false);
        CloseView view = queries.view(ledger.code(), result.close().id(),
                new CloseQueryService.ViewOptions(false, false));
        CloseView.MaterialBalance balance = view.materials().stream()
                .filter(m -> m.material().equals("AL")).findFirst().orElseThrow();
        // Both issues resolve to AL using the mapping in force on each business date.
        assertThat(balance.consumed()).isEqualByComparingTo("250");
        assertThat(balance.closing()).isEqualByComparingTo("750");

        // The snapshot records which alias row was used for each line.
        var snapshot = closes.snapshot(result.close().id());
        long aliasRows = snapshot.mappingUsage().aliases().stream()
                .filter(r -> r.sourceItemCode().equals("AL") || r.sourceItemCode().equals("AL-RENAMED"))
                .count();
        assertThat(aliasRows).isGreaterThanOrEqualTo(2);
    }

    @Test
    void unitConversionIsAppliedFromTheSourceUnit() {
        receive("DECLARATION", "CUSTOMS", "D1", 1, "2026-03-02T09:00:00+08:00",
                "{\"declarationNo\":\"D1\",\"lines\":[{\"itemCode\":\"AL\",\"unit\":\"TON\",\"quantity\":2,\"batchNo\":\"B1\"}]}");
        receive("DECLARATION_RECEIPT", "CUSTOMS", "R1", 1, "2026-03-02T10:00:00+08:00",
                "{\"declarationNo\":\"D1\",\"lines\":[{\"itemCode\":\"AL\",\"unit\":\"TON\",\"quantity\":2,\"batchNo\":\"B1\"}]}");

        CloseService.CloseResult result = close("2026-03", false);
        BigDecimal closing = queries.view(ledger.code(), result.close().id(),
                        new CloseQueryService.ViewOptions(false, false)).materials().getFirst().closing();
        assertThat(closing).isEqualByComparingTo("2000");
    }

    @Test
    void discrepancyCanBeResolvedWithEvidence() {
        receive("DECLARATION", "CUSTOMS", "D1", 1, "2026-03-02T09:00:00+08:00",
                "{\"declarationNo\":\"D1\",\"lines\":[{\"itemCode\":\"AL\",\"unit\":\"KG\",\"quantity\":1000,\"batchNo\":\"B1\"}]}");
        CloseService.CloseResult result = close("2026-03", false);
        List<DiscrepancyRepository.Row> open = discrepancies.query(ledger.id(), "2026-03", "OPEN", null, 50);
        assertThat(open).anyMatch(d -> d.kind().equals("UNACKNOWLEDGED_DECLARATION"));

        DiscrepancyRepository.Row target = open.getFirst();
        discrepancies.resolve(ledger.id(), target.id(), "RESOLVED", "receipt received by fax",
                "acknowledgement R1 filed", "officer", result.close().id());
        List<DiscrepancyRepository.Row> after = discrepancies.query(ledger.id(), "2026-03", "OPEN", null, 50);
        assertThat(after).noneMatch(d -> d.id() == target.id());
    }

    @Test
    void carriedForwardOpeningUsesRemainingNotOriginalQuantity() {
        receive("DECLARATION", "CUSTOMS", "D1", 1, "2026-03-02T09:00:00+08:00",
                "{\"declarationNo\":\"D1\",\"lines\":[{\"itemCode\":\"AL\",\"unit\":\"KG\",\"quantity\":1000,\"batchNo\":\"B1\"}]}");
        receive("DECLARATION_RECEIPT", "CUSTOMS", "R1", 1, "2026-03-02T10:00:00+08:00",
                "{\"declarationNo\":\"D1\",\"lines\":[{\"itemCode\":\"AL\",\"unit\":\"KG\",\"quantity\":1000,\"batchNo\":\"B1\"}]}");
        receive("ISSUE", "MES", "WO-1", 1, "2026-03-10T10:00:00+08:00",
                "{\"lines\":[{\"itemCode\":\"AL\",\"unit\":\"KG\",\"quantity\":600,\"batchNo\":\"B1\",\"workOrder\":\"WO-1\"}]}");
        CloseService.CloseResult march = close("2026-03", false);
        BigDecimal marchClosing = queries.view(ledger.code(), march.close().id(),
                new CloseQueryService.ViewOptions(false, false)).materials().getFirst().closing();
        assertThat(marchClosing).isEqualByComparingTo("400");

        // April carries 400 forward; issuing 150 must leave 250, not 850.
        receive("ISSUE", "MES", "WO-2", 1, "2026-04-10T10:00:00+08:00",
                "{\"lines\":[{\"itemCode\":\"AL\",\"unit\":\"KG\",\"quantity\":150,\"batchNo\":\"B1\",\"workOrder\":\"WO-2\"}]}");
        CloseService.CloseResult april = close("2026-04", false);
        CloseView.MaterialBalance balance = queries.view(ledger.code(), april.close().id(),
                        new CloseQueryService.ViewOptions(false, false)).materials().stream()
                .filter(m -> m.material().equals("AL")).findFirst().orElseThrow();
        assertThat(balance.opening()).isEqualByComparingTo("400");
        assertThat(balance.inbound()).isEqualByComparingTo("0");
        assertThat(balance.consumed()).isEqualByComparingTo("150");
        assertThat(balance.closing()).isEqualByComparingTo("250");
        assertThat(reproduceService.reproduce(ledger.code(), april.close().id()).hashMatch()).isTrue();
    }

    @Test
    void voidedDocumentIsDroppedFromRecloseButOldVersionReproduces() {
        receive("DECLARATION", "CUSTOMS", "D1", 1, "2026-03-02T09:00:00+08:00",
                "{\"declarationNo\":\"D1\",\"lines\":[{\"itemCode\":\"AL\",\"unit\":\"KG\",\"quantity\":500,\"batchNo\":\"B1\"}]}");
        receive("DECLARATION_RECEIPT", "CUSTOMS", "R1", 1, "2026-03-02T10:00:00+08:00",
                "{\"declarationNo\":\"D1\",\"lines\":[{\"itemCode\":\"AL\",\"unit\":\"KG\",\"quantity\":500,\"batchNo\":\"B1\"}]}");
        Doc adjustment = receive("ADJUSTMENT", "ERP", "A1", 1, "2026-03-05T10:00:00+08:00",
                "{\"reason\":\"found stock\",\"lines\":[{\"itemCode\":\"AL\",\"unit\":\"KG\",\"quantity\":300,\"batchNo\":\"B1\"}]}");
        receive("ISSUE", "MES", "W1", 1, "2026-03-10T10:00:00+08:00",
                "{\"lines\":[{\"itemCode\":\"AL\",\"unit\":\"KG\",\"quantity\":100,\"batchNo\":\"B1\",\"workOrder\":\"W1\"}]}");

        CloseService.CloseResult v1 = close("2026-03", false);
        assertThat(queries.view(ledger.code(), v1.close().id(), new CloseQueryService.ViewOptions(false, false))
                .materials().getFirst().closing()).isEqualByComparingTo("700");

        Doc voidDoc = ingestion.receive(ledger.code(), new IngestionService.IntakeCommand(
                "VOID", "ERP", "VOID-A1", 1, OffsetDateTime.parse("2026-03-31T10:00:00+08:00"),
                "{\"reason\":\"adjustment entered twice\"}", null, null, adjustment.id(), null, true)).doc();
        assertThat(voidDoc.type().name()).isEqualTo("VOID");
        assertThat(ingestion.document(ledger.code(), adjustment.id()).effective()).isFalse();

        CloseService.CloseResult v2 = close("2026-03", true);
        CloseView view = queries.view(ledger.code(), v2.close().id(), new CloseQueryService.ViewOptions(false, false));
        CloseView.MaterialBalance al = view.materials().getFirst();
        assertThat(al.adjust()).isEqualByComparingTo("0");
        assertThat(al.closing()).isEqualByComparingTo("400");
        assertThat(view.changes()).anyMatch(c -> c.kind().equals("VOIDED"));

        // The pre-void reported balance still reproduces from its own snapshot.
        ReproduceService.Reproduction old = reproduceService.reproduce(ledger.code(), v1.close().id());
        assertThat(old.hashMatch()).isTrue();
        assertThat(old.balances().getFirst().reproducedClosing()).isEqualByComparingTo("700");
    }

    @Test
    void inventoryReportShowsStockAndWipPerBatch() {
        receive("DECLARATION", "CUSTOMS", "D1", 1, "2026-03-02T09:00:00+08:00",
                "{\"declarationNo\":\"D1\",\"lines\":[{\"itemCode\":\"AL\",\"unit\":\"KG\",\"quantity\":1000,\"batchNo\":\"B1\"}]}");
        receive("DECLARATION_RECEIPT", "CUSTOMS", "R1", 1, "2026-03-02T10:00:00+08:00",
                "{\"declarationNo\":\"D1\",\"lines\":[{\"itemCode\":\"AL\",\"unit\":\"KG\",\"quantity\":1000,\"batchNo\":\"B1\"}]}");
        receive("ISSUE", "MES", "WO-1", 1, "2026-03-10T10:00:00+08:00",
                "{\"lines\":[{\"itemCode\":\"AL\",\"unit\":\"KG\",\"quantity\":400,\"batchNo\":\"B1\",\"workOrder\":\"WO-1\"}]}");

        CloseService.CloseResult result = close("2026-03", false);
        var report = inventory.report(ledger.code(), result.close().id());
        assertThat(report.stock()).anyMatch(r -> r.batchNo().equals("B1") && r.material().equals("AL"));
        assertThat(report.wip()).isNotEmpty();
    }
}
