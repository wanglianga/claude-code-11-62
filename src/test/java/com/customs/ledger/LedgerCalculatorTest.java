package com.customs.ledger;

import com.customs.ledger.calc.Computation;
import com.customs.ledger.calc.LedgerCalculator;
import com.customs.ledger.calc.Snapshot;
import com.customs.ledger.domain.DocType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests of the deterministic calculation; no Spring or database involved. */
class LedgerCalculatorTest {

    private final LedgerCalculator calculator = new LedgerCalculator();
    private static final YearMonth MARCH = YearMonth.of(2026, 3);

    @Test
    void twoWorkOrdersCannotOverConsumeTheSameBatch() {
        Snapshot snapshot = new SnapshotFixtures.Builder()
                .material("AL", "KG")
                .alias("AL", "AL", "2026-01-01", null)
                .openingLot("AL", "B1", "500", LocalDate.of(2026, 3, 1))
                .doc(DocType.ISSUE, "WO-1", "2026-03-10T10:00:00Z", "AL|300||B1|WO-1")
                .doc(DocType.ISSUE, "WO-2", "2026-03-10T10:00:00Z", "AL|300||B1|WO-2")
                .build(MARCH, null);

        Computation result = calculator.compute(snapshot);
        Computation.MaterialResult al = SnapshotFixtures.result(result, "AL");

        assertThat(result.fatalErrors()).isEmpty();
        assertThat(al.consumed()).isEqualByComparingTo("500");
        assertThat(al.closing()).isEqualByComparingTo("0");
        BigDecimal issued = result.allocations().stream()
                .filter(a -> a.movementType().equals("ISSUE"))
                .map(Computation.AllocationRow::quantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(issued).isEqualByComparingTo("500");
        assertThat(result.discrepancies())
                .anyMatch(d -> d.kind().equals("ISSUE_SHORTAGE")
                        && d.actual().compareTo(new BigDecimal("200")) == 0);
    }

    @Test
    void conservationHoldsAcrossStockAndWip() {
        Snapshot snapshot = new SnapshotFixtures.Builder()
                .material("AL", "KG").material("FG", "PCS")
                .alias("AL", "AL", "2026-01-01", null)
                .alias("FG", "FG", "2026-01-01", null)
                .relation("FG", "AL", "0.5", "2026-01-01", null)
                .declaration("DEC-1", "2026-03-02T00:00:00Z", "AL|1000||B1|")
                .receipt("R-1", "DEC-1", "2026-03-03T00:00:00Z", "AL|1000||B1|")
                .doc(DocType.ISSUE, "WO-1", "2026-03-11T00:00:00Z", "AL|400||B1|WO-1")
                .outbound("OUT-1", "2026-03-20T00:00:00Z", "FG", "FG", "600", "PCS", "WO-1")
                .build(MARCH, null);

        Computation result = calculator.compute(snapshot);
        Computation.MaterialResult al = SnapshotFixtures.result(result, "AL");

        assertThat(result.fatalErrors()).isEmpty();
        // stock: 0 + 1000 - 400 = 600
        assertThat(al.closing()).isEqualByComparingTo("600");
        // WIP: 0 + 400 issued - 300 written off (600 PCS * 0.5) = 100
        assertThat(al.wipClosing()).isEqualByComparingTo("100");
        assertThat(al.consumed()).isEqualByComparingTo("400");
        assertThat(al.outboundContent()).isEqualByComparingTo("300");
    }

    @Test
    void lateCorrectionDoesNotChangeRepreviouslyFrozenResult() {
        Snapshot v1 = new SnapshotFixtures.Builder()
                .material("AL", "KG")
                .alias("AL", "AL", "2026-01-01", null)
                .declaration("DEC-1", "2026-03-02T00:00:00Z", "AL|1000||B1|")
                .receipt("R-1", "DEC-1", "2026-03-03T00:00:00Z", "AL|1000||B1|")
                .build(MARCH, null);
        Snapshot v2 = new SnapshotFixtures.Builder()
                .material("AL", "KG")
                .alias("AL", "AL", "2026-01-01", null)
                .declaration("DEC-1", "2026-03-02T00:00:00Z", "AL|950||B1|")
                .receipt("R-1", "DEC-1", "2026-03-03T00:00:00Z", "AL|1000||B1|")
                .build(MARCH, null);

        Computation first = calculator.compute(v1);
        Computation second = calculator.compute(v2);

        assertThat(SnapshotFixtures.result(first, "AL").inbound()).isEqualByComparingTo("1000");
        assertThat(SnapshotFixtures.result(second, "AL").inbound()).isEqualByComparingTo("950");
        // Recomputing the frozen v1 snapshot always reproduces the original number.
        assertThat(SnapshotFixtures.result(calculator.compute(v1), "AL").inbound()).isEqualByComparingTo("1000");
    }

    @Test
    void unacknowledgedDeclarationIsPostedButFlagged() {
        Snapshot snapshot = new SnapshotFixtures.Builder()
                .material("AL", "KG")
                .alias("AL", "AL", "2026-01-01", null)
                .declaration("DEC-1", "2026-03-02T00:00:00Z", "AL|1000||B1|")
                .build(MARCH, null);

        Computation result = calculator.compute(snapshot);
        assertThat(SnapshotFixtures.result(result, "AL").closing()).isEqualByComparingTo("1000");
        assertThat(result.discrepancies()).anyMatch(d -> d.kind().equals("UNACKNOWLEDGED_DECLARATION"));
    }

    @Test
    void declaredVsReceivedMismatchIsReported() {
        Snapshot snapshot = new SnapshotFixtures.Builder()
                .material("AL", "KG")
                .alias("AL", "AL", "2026-01-01", null)
                .declaration("DEC-1", "2026-03-02T00:00:00Z", "AL|1000||B1|")
                .warehouseReceipt("R-1", "DEC-1", "2026-03-03T00:00:00Z", "AL|980||B1|")
                .build(MARCH, null);

        Computation result = calculator.compute(snapshot);
        assertThat(SnapshotFixtures.result(result, "AL").closing()).isEqualByComparingTo("1000");
        assertThat(result.discrepancies())
                .anyMatch(d -> d.kind().equals("DECLARED_VS_RECEIVED")
                        && d.expected().compareTo(new BigDecimal("1000")) == 0
                        && d.actual().compareTo(new BigDecimal("980")) == 0);
    }

    @Test
    void fifoAllocatesAcrossBatchesInBusinessDateOrder() {
        Snapshot snapshot = new SnapshotFixtures.Builder()
                .material("AL", "KG")
                .alias("AL", "AL", "2026-01-01", null)
                .openingLot("AL", "B1", "100", LocalDate.of(2026, 3, 1))
                .openingLot("AL", "B2", "100", LocalDate.of(2026, 3, 2))
                .doc(DocType.ISSUE, "WO-1", "2026-03-10T00:00:00Z", "AL|150|||WO-1")
                .build(MARCH, null);

        Computation result = calculator.compute(snapshot);
        assertThat(result.allocations()).hasSize(2);
        assertThat(result.allocations().get(0).lotKey()).isEqualTo("AL|B1");
        assertThat(result.allocations().get(0).quantity()).isEqualByComparingTo("100");
        assertThat(result.allocations().get(1).lotKey()).isEqualTo("AL|B2");
        assertThat(result.allocations().get(1).quantity()).isEqualByComparingTo("50");
    }

    @Test
    void unmappedItemIsReportedNotSilentlyDropped() {
        Snapshot snapshot = new SnapshotFixtures.Builder()
                .material("AL", "KG")
                .alias("AL", "AL", "2026-01-01", null)
                .declaration("DEC-1", "2026-03-02T00:00:00Z", "AL|100||B1|", "UNKNOWN|50||B1|")
                .receipt("R-1", "DEC-1", "2026-03-03T00:00:00Z", "AL|100||B1|")
                .build(MARCH, null);

        Computation result = calculator.compute(snapshot);
        assertThat(result.discrepancies()).anyMatch(d -> d.kind().equals("UNMAPPED_ITEM"));
        assertThat(SnapshotFixtures.result(result, "AL").closing()).isEqualByComparingTo("100");
    }

    @Test
    void stocktakeVarianceIsReportedAgainstOnHand() {
        Snapshot snapshot = new SnapshotFixtures.Builder()
                .material("AL", "KG")
                .alias("AL", "AL", "2026-01-01", null)
                .openingLot("AL", "B1", "500", LocalDate.of(2026, 3, 1))
                .doc(DocType.STOCKTAKE, "ST-1", "2026-03-31T00:00:00Z", "AL|480||B1|")
                .build(MARCH, null);

        Computation result = calculator.compute(snapshot);
        assertThat(result.discrepancies())
                .anyMatch(d -> d.kind().equals("STOCKTAKE_VARIANCE")
                        && d.expected().compareTo(new BigDecimal("500")) == 0
                        && d.actual().compareTo(new BigDecimal("480")) == 0);
        // A stocktake never posts by itself.
        assertThat(SnapshotFixtures.result(result, "AL").closing()).isEqualByComparingTo("500");
    }

    @Test
    void outboundContentBeyondIssueIsFlagged() {
        Snapshot snapshot = new SnapshotFixtures.Builder()
                .material("AL", "KG").material("FG", "PCS")
                .alias("AL", "AL", "2026-01-01", null)
                .alias("FG", "FG", "2026-01-01", null)
                .relation("FG", "AL", "0.5", "2026-01-01", null)
                .declaration("DEC-1", "2026-03-02T00:00:00Z", "AL|1000||B1|")
                .receipt("R-1", "DEC-1", "2026-03-03T00:00:00Z", "AL|1000||B1|")
                .doc(DocType.ISSUE, "WO-1", "2026-03-11T00:00:00Z", "AL|100||B1|WO-1")
                .outbound("OUT-1", "2026-03-20T00:00:00Z", "FG", "FG", "600", "PCS", "WO-1")
                .build(MARCH, null);

        Computation result = calculator.compute(snapshot);
        assertThat(result.discrepancies()).anyMatch(d -> d.kind().equals("OUTBOUND_CONTENT_SHORTAGE"));
        assertThat(result.fatalErrors()).isEmpty();
        Computation.MaterialResult al = SnapshotFixtures.result(result, "AL");
        // Only 100 was issued, so at most 100 can be written off.
        assertThat(al.outboundContent()).isEqualByComparingTo("100");
        assertThat(al.wipClosing()).isEqualByComparingTo("0");
    }

    @Test
    void processingRelationMissingIsFlagged() {
        Snapshot snapshot = new SnapshotFixtures.Builder()
                .material("FG", "PCS")
                .alias("FG", "FG", "2026-01-01", null)
                .outbound("OUT-1", "2026-03-20T00:00:00Z", "FG", "FG", "10", "PCS", "")
                .build(MARCH, null);

        Computation result = calculator.compute(snapshot);
        assertThat(result.discrepancies()).anyMatch(d -> d.kind().equals("MISSING_PROCESSING_RELATION"));
    }
}
