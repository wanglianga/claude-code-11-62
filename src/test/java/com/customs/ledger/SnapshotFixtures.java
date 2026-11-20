package com.customs.ledger;

import com.customs.ledger.calc.Computation;
import com.customs.ledger.calc.LedgerCalculator;
import com.customs.ledger.calc.Snapshot;
import com.customs.ledger.calc.SnapshotFactory;
import com.customs.ledger.domain.Alias;
import com.customs.ledger.domain.Conversion;
import com.customs.ledger.domain.DocType;
import com.customs.ledger.domain.Material;
import com.customs.ledger.domain.ProcessingRelation;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/** Builds in-memory snapshots for pure calculator tests, without a database. */
final class SnapshotFixtures {

    static final ObjectMapper MAPPER = new ObjectMapper();
    static final LocalDate JAN1 = LocalDate.of(2026, 1, 1);

    private SnapshotFixtures() {
    }

    static final class Builder {
        private final List<Snapshot.SnapshotDoc> docs = new ArrayList<>();
        private final List<Snapshot.SnapshotLot> openingLots = new ArrayList<>();
        private final List<Snapshot.PriorTotal> priorTotals = new ArrayList<>();
        private final List<Alias> aliases = new ArrayList<>();
        private final List<Conversion> conversions = new ArrayList<>();
        private final List<ProcessingRelation> relations = new ArrayList<>();
        private final List<Material> materials = new ArrayList<>();
        private long nextId = 1;

        Builder material(String code, String unit) {
            materials.add(new Material(materials.size() + 1, code, code, unit));
            return this;
        }

        long materialId(String code) {
            return materials.stream().filter(m -> m.code().equals(code)).findFirst().orElseThrow().id();
        }

        Builder alias(String sourceItem, String material, String from, String to) {
            aliases.add(new Alias(aliases.size() + 1, null, sourceItem, materialId(material),
                    from == null ? null : LocalDate.parse(from), to == null ? null : LocalDate.parse(to)));
            return this;
        }

        Builder conversion(String material, String unit, String factor, String from, String to) {
            conversions.add(new Conversion(conversions.size() + 1, materialId(material), unit,
                    new BigDecimal(factor), from == null ? null : LocalDate.parse(from),
                    to == null ? null : LocalDate.parse(to)));
            return this;
        }

        Builder relation(String output, String input, String factor, String from, String to) {
            relations.add(new ProcessingRelation(relations.size() + 1, materialId(output), materialId(input),
                    new BigDecimal(factor), from == null ? null : LocalDate.parse(from),
                    to == null ? null : LocalDate.parse(to)));
            return this;
        }

        Builder openingLot(String material, String batch, String qty, LocalDate businessDate) {
            openingLots.add(new Snapshot.SnapshotLot(material + '|' + batch, material, batch, "STOCK",
                    new BigDecimal(qty), new BigDecimal(qty), businessDate, null, "OPENING"));
            return this;
        }

        /** line format: itemCode|qty|unit|batch|workOrder */
        Builder doc(DocType type, String externalId, String businessTime, String... lines) {
            long id = nextId++;
            docs.add(new Snapshot.SnapshotDoc(id, type.name(), "TEST", externalId, 1, Instant.parse(businessTime),
                    "hash-" + id, null, null, null, null, null, parseLines(lines)));
            return this;
        }

        Builder declaration(String externalId, String businessTime, String... lines) {
            return doc(DocType.DECLARATION, externalId, businessTime, lines);
        }

        Builder warehouseReceipt(String externalId, String declarationId, String businessTime, String... lines) {
            long id = nextId++;
            docs.add(new Snapshot.SnapshotDoc(id, DocType.WAREHOUSE_RECEIPT.name(), "TEST", externalId, 1,
                    Instant.parse(businessTime), "hash-" + id, null, null, null, declarationId, null,
                    parseLines(lines)));
            return this;
        }

        Builder receipt(String externalId, String declarationId, String businessTime, String... lines) {
            long id = nextId++;
            docs.add(new Snapshot.SnapshotDoc(id, DocType.DECLARATION_RECEIPT.name(), "TEST", externalId, 1,
                    Instant.parse(businessTime), "hash-" + id, null, null, null, declarationId, null,
                    parseLines(lines)));
            return this;
        }

        private List<Snapshot.SnapshotLine> parseLines(String[] raw) {
            List<Snapshot.SnapshotLine> lines = new ArrayList<>();
            int no = 1;
            for (String spec : raw) {
                String[] p = spec.split("\\|");
                String item = p[0];
                BigDecimal qty = new BigDecimal(p[1]);
                String unit = p.length > 2 && !p[2].isBlank() ? p[2] : null;
                String batch = p.length > 3 && !p[3].isBlank() ? p[3] : null;
                String workOrder = p.length > 4 && !p[4].isBlank() ? p[4] : null;

                Long materialId = aliases.stream()
                        .filter(a -> a.sourceItemCode().equals(item))
                        .filter(a -> a.validFrom() == null || !a.validFrom().isAfter(JAN1))
                        .map(Alias::materialId).findFirst().orElse(null);
                String code = materialId == null ? null : materials.stream()
                        .filter(m -> m.id() == materialId).findFirst().map(Material::code).orElse(null);
                lines.add(new Snapshot.SnapshotLine(no++, item, code, unit, qty,
                        code == null ? null : qty, batch, workOrder, null, List.of(), null, null));
            }
            return lines;
        }

        /** Resolve finished-goods content for outbound lines, mirroring SnapshotFactory. */
        Builder outbound(String externalId, String businessTime, String item, String outputCode,
                         String qty, String unit, String workOrder) {
            long id = nextId++;
            BigDecimal quantity = new BigDecimal(qty);
            List<Snapshot.ContentPart> parts = new ArrayList<>();
            for (ProcessingRelation rel : relations) {
                if (rel.outputMaterialId() == materialId(outputCode)) {
                    String inputCode = materials.stream().filter(m -> m.id() == rel.inputMaterialId())
                            .findFirst().orElseThrow().code();
                    parts.add(new Snapshot.ContentPart(inputCode, quantity.multiply(rel.qtyPerOutput())));
                }
            }
            docs.add(new Snapshot.SnapshotDoc(id, DocType.OUTBOUND.name(), "TEST", externalId, 1,
                    Instant.parse(businessTime), "hash-" + id, null, null, null, null, null,
                    List.of(new Snapshot.SnapshotLine(1, item, outputCode, unit, quantity, quantity, null,
                            workOrder, outputCode, parts, null, null))));
            return this;
        }

        Builder priorTotal(String material, String consumed, String outboundContent) {
            priorTotals.add(new Snapshot.PriorTotal(material, new BigDecimal(consumed),
                    new BigDecimal(outboundContent)));
            return this;
        }

        Snapshot build(YearMonth period, Long priorCloseId) {
            return new Snapshot("TEST", "UTC", period.toString(), "MATERIALIZED", LedgerCalculator.ALGORITHM,
                    priorCloseId, priorCloseId == null ? null : 1, null,
                    materials.stream().map(m -> new Snapshot.SnapshotMaterial(m.code(), m.name(), m.baseUnit()))
                            .toList(),
                    List.copyOf(docs), List.copyOf(openingLots), List.copyOf(priorTotals),
                    buildMapping());
        }

        private Snapshot.MappingUsage buildMapping() {
            List<Snapshot.MappingRow> aliasRows = aliases.stream()
                    .map(a -> new Snapshot.MappingRow(a.id(), "ALIAS", null, a.sourceItemCode(),
                            materials.stream().filter(m -> m.id() == a.materialId()).findFirst().orElseThrow().code(),
                            null, null, null, a.validFrom(), a.validTo(), "2026-01-01")).toList();
            List<Snapshot.MappingRow> convRows = conversions.stream()
                    .map(c -> new Snapshot.MappingRow(c.id(), "CONVERSION", null, null,
                            null, null, c.fromUnit(), c.factor(), c.validFrom(), c.validTo(), "2026-01-01")).toList();
            List<Snapshot.MappingRow> relRows = relations.stream()
                    .map(r -> new Snapshot.MappingRow(r.id(), "PROCESSING", null, null, null, null, null,
                            r.qtyPerOutput(), r.validFrom(), r.validTo(), "2026-01-01")).toList();
            return new Snapshot.MappingUsage(aliasRows, convRows, relRows);
        }
    }

    static Computation.MaterialResult result(Computation computation, String material) {
        return computation.materials().stream().filter(m -> m.materialCode().equals(material)).findFirst()
                .orElseThrow(() -> new AssertionError("no result for " + material));
    }
}
