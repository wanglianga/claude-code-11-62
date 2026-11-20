package com.customs.ledger.calc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Frozen inputs of one monthly close. The close stores this document verbatim; the reported
 * balance is recomputed from it plus the algorithm version, so historical results stay
 * reproducible even after mappings or source documents change.
 */
public record Snapshot(
        String ledgerCode,
        String zone,
        String period,
        String mode,
        String algorithmVersion,
        Long priorCloseId,
        Integer priorCloseVersion,
        String priorCloseResultHash,
        List<SnapshotMaterial> materials,
        List<SnapshotDoc> documents,
        List<SnapshotLot> openingLots,
        List<PriorTotal> priorTotals,
        MappingUsage mappingUsage) {

    public record SnapshotMaterial(String code, String name, String baseUnit) {
    }

    /**
     * @param referenceExternalId upstream declaration id for a receipt, otherwise null
     * @param lines               resolved quantity lines in the material base unit
     */
    public record SnapshotDoc(long id, String docType, String sourceSystem, String externalId, int sourceVersion,
                              Instant businessTime, String payloadHash, Long supersedesId, Long voidOfId,
                              String correctionReason, String referenceExternalId, String referenceSourceSystem,
                              List<SnapshotLine> lines) {

        public boolean is(String type) {
            return docType.equals(type);
        }
    }

    /**
     * @param contents for a finished-goods outbound line: bonded material embedded in the output quantity
     */
    public record SnapshotLine(int lineNo, String sourceItemCode, String materialCode, String unit,
                               BigDecimal quantity, BigDecimal baseQuantity, String batchNo, String workOrder,
                               String outputMaterialCode, List<ContentPart> contents, Long aliasId, Long conversionId) {
    }

    public record ContentPart(String materialCode, BigDecimal quantity) {
    }

    public record SnapshotLot(String lotKey, String materialCode, String batchNo, String pool,
                              BigDecimal quantity, BigDecimal remaining, LocalDate businessDate,
                              Long sourceDocumentId, String sourceType) {

        public SnapshotLot withPool(String pool) {
            return new SnapshotLot(lotKey, materialCode, batchNo, pool, quantity, remaining, businessDate,
                    sourceDocumentId, sourceType);
        }
    }

    public record PriorTotal(String materialCode, BigDecimal cumulativeConsumed, BigDecimal cumulativeOutboundContent) {
    }

    public record MappingUsage(List<MappingRow> aliases, List<MappingRow> conversions, List<MappingRow> relations) {

        public static MappingUsage empty() {
            return new MappingUsage(List.of(), List.of(), List.of());
        }
    }

    /** Audit copy of one effective-dated mapping row used by this close. */
    public record MappingRow(long id, String type, String sourceSystem, String sourceItemCode, String materialCode,
                             String inputMaterialCode, String fromUnit, BigDecimal factor, LocalDate validFrom,
                             LocalDate validTo, String validOn) {
    }
}
