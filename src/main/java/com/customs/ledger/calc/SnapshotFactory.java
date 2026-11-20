package com.customs.ledger.calc;

import com.customs.ledger.common.ApiException;
import com.customs.ledger.common.Qty;
import com.customs.ledger.domain.Alias;
import com.customs.ledger.domain.Conversion;
import com.customs.ledger.domain.Doc;
import com.customs.ledger.domain.DocType;
import com.customs.ledger.domain.Ledger;
import com.customs.ledger.domain.Material;
import com.customs.ledger.domain.ProcessingRelation;
import com.customs.ledger.ingest.PayloadExtractor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Builds the frozen {@link Snapshot} for a period from effective documents, mapping rows and prior close. */
public final class SnapshotFactory {

    private SnapshotFactory() {
    }

    /** Prior-close data the factory needs, already loaded by the close service. */
    public record PriorState(Long closeId, Integer version, String resultHash, String period,
                             List<Snapshot.SnapshotLot> lots, List<Snapshot.PriorTotal> totals) {
    }

    public record Inputs(Ledger ledger, YearMonth period, String mode, String algorithmVersion,
                         List<Doc> documents, Map<Long, String> payloadJson,
                         List<Alias> aliases, List<Conversion> conversions, List<ProcessingRelation> relations,
                         List<Material> materials, PriorState prior) {
    }

    public static Snapshot build(Inputs in) {
        ZoneId zone = ZoneId.of(in.ledger().zone());
        Map<Long, String> codeById = new HashMap<>();
        Map<String, String> unitByCode = new HashMap<>();
        for (Material material : in.materials()) {
            codeById.put(material.id(), material.code());
            unitByCode.put(material.code(), material.baseUnit());
        }

        MappingIndex mapping = new MappingIndex(in);
        Set<Snapshot.MappingRow> usedAliases = new LinkedHashSet<>();
        Set<Snapshot.MappingRow> usedConversions = new LinkedHashSet<>();
        Set<Snapshot.MappingRow> usedRelations = new LinkedHashSet<>();

        List<Doc> ordered = new ArrayList<>(in.documents());
        ordered.sort(Comparator.comparing(Doc::businessTime).thenComparing(Doc::id));

        List<Snapshot.SnapshotDoc> snapshotDocs = new ArrayList<>();
        for (Doc doc : ordered) {
            String json = in.payloadJson().get(doc.id());
            if (json == null) {
                throw new IllegalStateException("missing payload for document " + doc.id());
            }
            LocalDate businessDate = doc.businessTime().atZone(zone).toLocalDate();
            PayloadExtractor.Canonical canonical = doc.type() == DocType.VOID
                    ? new PayloadExtractor.Canonical(null, null, List.of())
                    : PayloadExtractor.canonicalise(JsonPayloads.parse(json), doc.type().name());

            List<Snapshot.SnapshotLine> lines = new ArrayList<>();
            for (PayloadExtractor.CanonicalLine line : canonical.lines()) {
                lines.add(resolveLine(codeById, unitByCode, mapping, line, businessDate,
                        doc.type(), usedAliases, usedConversions, usedRelations));
            }
            snapshotDocs.add(new Snapshot.SnapshotDoc(doc.id(), doc.type().name(), doc.sourceSystem(),
                    doc.externalId(), doc.sourceVersion(), doc.businessTime(), doc.payloadHash(),
                    doc.supersedesId(), doc.voidOfId(), doc.correctionReason(),
                    canonical.referenceExternalId(), canonical.referenceSourceSystem(), lines));
        }

        PriorState prior = in.prior();
        List<Snapshot.SnapshotLot> openingLots = prior == null ? List.of() : prior.lots();
        List<Snapshot.PriorTotal> priorTotals = prior == null ? List.of() : prior.totals();

        List<Snapshot.SnapshotMaterial> materials = in.materials().stream()
                .map(m -> new Snapshot.SnapshotMaterial(m.code(), m.name(), m.baseUnit()))
                .sorted(Comparator.comparing(Snapshot.SnapshotMaterial::code))
                .toList();

        return new Snapshot(in.ledger().code(), in.ledger().zone(), com.customs.ledger.common.Periods.format(in.period()),
                in.mode(), in.algorithmVersion(),
                prior == null ? null : prior.closeId(),
                prior == null ? null : prior.version(),
                prior == null ? null : prior.resultHash(),
                materials, snapshotDocs, openingLots, priorTotals,
                new Snapshot.MappingUsage(List.copyOf(usedAliases), List.copyOf(usedConversions),
                        List.copyOf(usedRelations)));
    }

    private static Snapshot.SnapshotLine resolveLine(Map<Long, String> codeById, Map<String, String> unitByCode,
                                                     MappingIndex mapping, PayloadExtractor.CanonicalLine line,
                                                     LocalDate date, DocType docType,
                                                     Set<Snapshot.MappingRow> usedAliases,
                                                     Set<Snapshot.MappingRow> usedConversions,
                                                     Set<Snapshot.MappingRow> usedRelations) {
        if (line.outputItemCode() != null && (line.itemCode() == null || docType == DocType.OUTBOUND)) {
            // Finished-goods outbound: the material written off is the embedded content.
            Alias outputAlias = mapping.alias(line.outputItemCode(), date);
            if (outputAlias == null) {
                return new Snapshot.SnapshotLine(line.lineNo(), line.itemCode(), null, line.unit(),
                        line.quantity(), null, line.batchNo(), line.workOrder(), line.outputItemCode(),
                        List.of(), null, null);
            }
            usedAliases.add(aliasRow(outputAlias, codeById, date));
            String outputCode = codeById.get(outputAlias.materialId());
            String outputUnit = unitByCode.get(outputCode);
            Conversion conv = mapping.conversion(outputAlias.materialId(), line.unit(), outputUnit, date);
            if (conv == null && line.unit() != null && !line.unit().equalsIgnoreCase(outputUnit)) {
                return new Snapshot.SnapshotLine(line.lineNo(), line.itemCode(), outputCode, line.unit(),
                        line.quantity(), null, line.batchNo(), line.workOrder(), line.outputItemCode(),
                        List.of(), outputAlias.id(), null);
            }
            if (conv != null) {
                usedConversions.add(conversionRow(conv, outputCode, date));
            }
            BigDecimal factor = conv == null ? BigDecimal.ONE : conv.factor();
            BigDecimal outputBase = Qty.multiply(line.quantity(), factor);

            List<ProcessingRelation> rels = mapping.relations(outputAlias.materialId(), date);
            List<Snapshot.ContentPart> parts = new ArrayList<>();
            for (ProcessingRelation rel : rels) {
                usedRelations.add(relationRow(rel, outputCode, codeById.get(rel.inputMaterialId()), date));
                BigDecimal content = Qty.multiply(outputBase, rel.qtyPerOutput());
                if (Qty.isPositive(content)) {
                    parts.add(new Snapshot.ContentPart(codeById.get(rel.inputMaterialId()), content));
                }
            }
            return new Snapshot.SnapshotLine(line.lineNo(), line.itemCode(), outputCode, line.unit(),
                    line.quantity(), outputBase, line.batchNo(), line.workOrder(), line.outputItemCode(),
                    parts, outputAlias.id(), conv == null ? null : conv.id());
        }

        Alias alias = mapping.alias(line.itemCode(), date);
        if (alias == null) {
            return new Snapshot.SnapshotLine(line.lineNo(), line.itemCode(), null, line.unit(),
                    line.quantity(), null, line.batchNo(), line.workOrder(), line.outputItemCode(),
                    List.of(), null, null);
        }
        usedAliases.add(aliasRow(alias, codeById, date));
        String materialCode = codeById.get(alias.materialId());
        String baseUnit = unitByCode.get(materialCode);
        Conversion conv = mapping.conversion(alias.materialId(), line.unit(), baseUnit, date);
        BigDecimal baseQuantity = null;
        if (conv != null) {
            usedConversions.add(conversionRow(conv, materialCode, date));
            baseQuantity = Qty.multiply(line.quantity(), conv.factor());
        } else if (line.unit() == null || line.unit().equalsIgnoreCase(baseUnit)) {
            baseQuantity = Qty.normalize(line.quantity());
        }

        // An outbound line may name the finished good without an explicit output flag. When that item
        // is produced from bonded material (it has effective processing relations) and no bonded stock
        // is expected for it, interpret the line as a write-off of the embedded content.
        List<ProcessingRelation> relations = mapping.relations(alias.materialId(), date);
        if (docType == DocType.OUTBOUND && line.outputItemCode() == null && !relations.isEmpty()
                && baseQuantity != null) {
            List<Snapshot.ContentPart> parts = new ArrayList<>();
            for (ProcessingRelation rel : relations) {
                usedRelations.add(relationRow(rel, materialCode, codeById.get(rel.inputMaterialId()), date));
                BigDecimal content = Qty.multiply(baseQuantity, rel.qtyPerOutput());
                if (Qty.isPositive(content)) {
                    parts.add(new Snapshot.ContentPart(codeById.get(rel.inputMaterialId()), content));
                }
            }
            return new Snapshot.SnapshotLine(line.lineNo(), line.itemCode(), materialCode, line.unit(),
                    line.quantity(), baseQuantity, line.batchNo(), line.workOrder(), materialCode,
                    parts, alias.id(), conv == null ? null : conv.id());
        }

        return new Snapshot.SnapshotLine(line.lineNo(), line.itemCode(), materialCode, line.unit(),
                line.quantity(), baseQuantity, line.batchNo(), line.workOrder(), line.outputItemCode(),
                List.of(), alias.id(), conv == null ? null : conv.id());
    }

    private static Snapshot.MappingRow aliasRow(Alias alias, Map<Long, String> codeById, LocalDate date) {
        return new Snapshot.MappingRow(alias.id(), "ALIAS", alias.sourceSystem(), alias.sourceItemCode(),
                codeById.get(alias.materialId()), null, null, null, alias.validFrom(), alias.validTo(),
                date.toString());
    }

    private static Snapshot.MappingRow conversionRow(Conversion conv, String materialCode, LocalDate date) {
        return new Snapshot.MappingRow(conv.id(), "CONVERSION", null, null, materialCode, null,
                conv.fromUnit(), conv.factor(), conv.validFrom(), conv.validTo(), date.toString());
    }

    private static Snapshot.MappingRow relationRow(ProcessingRelation rel, String outputCode, String inputCode,
                                                   LocalDate date) {
        return new Snapshot.MappingRow(rel.id(), "PROCESSING", null, null, outputCode, inputCode,
                null, rel.qtyPerOutput(), rel.validFrom(), rel.validTo(), date.toString());
    }

    /** In-memory effective-date index over the mapping rows loaded for the snapshot. */
    static final class MappingIndex {
        private final Map<String, List<Alias>> aliases = new HashMap<>();
        private final Map<String, List<Conversion>> conversions = new HashMap<>();
        private final Map<Long, List<ProcessingRelation>> relations = new HashMap<>();

        MappingIndex(Inputs in) {
            for (Alias alias : in.aliases()) {
                aliases.computeIfAbsent(alias.sourceItemCode(), k -> new ArrayList<>()).add(alias);
            }
            for (Conversion conversion : in.conversions()) {
                conversions.computeIfAbsent(conversion.materialId() + "|" + conversion.fromUnit().toUpperCase(),
                        k -> new ArrayList<>()).add(conversion);
            }
            for (ProcessingRelation relation : in.relations()) {
                relations.computeIfAbsent(relation.outputMaterialId(), k -> new ArrayList<>()).add(relation);
            }
            aliases.values().forEach(list -> list.sort(
                    Comparator.comparing((Alias a) -> a.sourceSystem() != null).reversed()
                            .thenComparing(Alias::validFrom, Comparator.reverseOrder())
                            .thenComparing(Alias::id, Comparator.reverseOrder())));
            conversions.values().forEach(list -> list.sort(
                    Comparator.comparing(Conversion::validFrom, Comparator.reverseOrder())
                            .thenComparing(Conversion::id, Comparator.reverseOrder())));
            relations.values().forEach(list -> list.sort(
                    Comparator.comparing(ProcessingRelation::validFrom, Comparator.reverseOrder())
                            .thenComparing(ProcessingRelation::id, Comparator.reverseOrder())));
        }

        Alias alias(String sourceItemCode, LocalDate date) {
            if (sourceItemCode == null) {
                return null;
            }
            return aliases.getOrDefault(sourceItemCode, List.of()).stream()
                    .filter(a -> a.effectiveOn(date))
                    .findFirst().orElse(null);
        }

        Conversion conversion(long materialId, String fromUnit, String baseUnit, LocalDate date) {
            if (fromUnit == null || fromUnit.equalsIgnoreCase(baseUnit)) {
                return new Conversion(-1L, materialId, baseUnit, BigDecimal.ONE, date, null);
            }
            return conversions.getOrDefault(materialId + "|" + fromUnit.toUpperCase(), List.of()).stream()
                    .filter(c -> c.effectiveOn(date))
                    .findFirst().orElse(null);
        }

        List<ProcessingRelation> relations(long outputMaterialId, LocalDate date) {
            return relations.getOrDefault(outputMaterialId, List.of()).stream()
                    .filter(r -> r.effectiveOn(date))
                    .toList();
        }
    }

    /** Small helper so the factory stays independent from Spring's ObjectMapper wiring. */
    static final class JsonPayloads {
        private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
                new com.fasterxml.jackson.databind.ObjectMapper();

        static com.fasterxml.jackson.databind.JsonNode parse(String json) {
            try {
                return MAPPER.readTree(json);
            } catch (Exception e) {
                throw ApiException.badRequest("INVALID_STORED_PAYLOAD", "stored payload is invalid JSON");
            }
        }
    }
}
