package com.customs.ledger.calc;

import com.customs.ledger.calc.Computation.AllocationRow;
import com.customs.ledger.calc.Computation.DiscrepancyDraft;
import com.customs.ledger.calc.Computation.LotState;
import com.customs.ledger.calc.Computation.MaterialResult;
import com.customs.ledger.common.Qty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Pure deterministic calculation. The only inputs are the frozen {@link Snapshot} and this class's
 * algorithm version; no database or clock access. Given the same snapshot it always returns the
 * same result, which is what makes historical reproduction and REBUILD-mode queries possible.
 *
 * <p>Two conservation domains are tracked per material:
 * <ul>
 *   <li><b>stock</b>: opening + inbound + positive adjustments - issues - negative adjustments = closing
 *       stock. An issue moves quantity from the stock pool into the work-in-process pool.</li>
 *   <li><b>work in process</b>: opening WIP + issues - bonded content written off by exports = closing WIP.</li>
 * </ul>
 * Every movement is a batch allocation capped at the source pool's availability, so a stock pool can
 * never be over-consumed; unmet demand becomes an explicit discrepancy instead of a negative balance.
 */
@Component
public class LedgerCalculator {

    public static final String ALGORITHM = "ledger-1.0.0";
    public static final String STOCK = "STOCK";
    public static final String WIP = "WIP";

    public Computation compute(Snapshot snapshot) {
        ZoneId zone = ZoneId.of(snapshot.zone());
        State state = new State(zone);

        for (Snapshot.SnapshotLot lot : snapshot.openingLots()) {
            state.addOpening(lot);
        }

        Map<String, List<Snapshot.SnapshotDoc>> receiptsByReference = snapshot.documents().stream()
                .filter(d -> d.is("DECLARATION_RECEIPT") && d.referenceExternalId() != null)
                .collect(Collectors.groupingBy(d -> referenceKey(d.referenceSourceSystem(), d.referenceExternalId())));

        Map<String, List<Snapshot.SnapshotDoc>> warehouseReceiptsByReference = snapshot.documents().stream()
                .filter(d -> d.is("WAREHOUSE_RECEIPT") && d.referenceExternalId() != null)
                .collect(Collectors.groupingBy(d -> referenceKey(d.referenceSourceSystem(), d.referenceExternalId())));

        Map<String, List<Snapshot.SnapshotDoc>> declarationsByKey = snapshot.documents().stream()
                .filter(d -> d.is("DECLARATION"))
                .collect(Collectors.groupingBy(d -> referenceKey(d.sourceSystem(), d.externalId())));

        for (Snapshot.SnapshotDoc receipt : snapshot.documents()) {
            if (!receipt.is("DECLARATION_RECEIPT") && !receipt.is("WAREHOUSE_RECEIPT")) {
                continue;
            }
            if (receipt.referenceExternalId() == null) {
                state.discrepancy("RECEIPT_WITHOUT_REFERENCE", null, receipt, "receipt references no declaration");
                continue;
            }
            if (lookupDeclarations(declarationsByKey, receipt).isEmpty()) {
                state.discrepancy("RECEIPT_WITHOUT_DECLARATION", null, receipt,
                        "receipt references declaration " + receipt.referenceExternalId() + " which is not in scope");
            }
        }

        for (Snapshot.SnapshotDoc doc : snapshot.documents()) {
            LocalDate businessDate = doc.businessTime().atZone(zone).toLocalDate();
            switch (doc.docType()) {
                case "OPENING_BALANCE" -> {
                    if (snapshot.priorCloseId() != null) {
                        state.discrepancy("OPENING_BALANCE_OUT_OF_PLACE", null, doc,
                                "opening balance received in a period whose opening is carried from the prior close; "
                                        + "kept for audit, not posted, to avoid double counting");
                    } else {
                        state.applyOpeningBalance(doc, businessDate);
                    }
                }
                case "DECLARATION" -> state.applyDeclaration(doc, businessDate, receiptsByReference,
                        warehouseReceiptsByReference);
                case "ISSUE" -> state.applyIssue(doc, businessDate);
                case "OUTBOUND" -> state.applyOutbound(doc, businessDate);
                case "ADJUSTMENT" -> state.applyAdjustment(doc, businessDate);
                case "STOCKTAKE" -> state.applyStocktake(doc, businessDate);
                case "DECLARATION_RECEIPT", "WAREHOUSE_RECEIPT", "VOID" -> {
                    // Receipts are cross-checked above; voids are resolved by the snapshot source.
                }
                default -> state.discrepancy("UNSUPPORTED_DOC_TYPE", null, doc, "unsupported document type");
            }
        }

        return state.finish(snapshot);
    }

    private static List<Snapshot.SnapshotDoc> lookupDeclarations(
            Map<String, List<Snapshot.SnapshotDoc>> declarationsByKey, Snapshot.SnapshotDoc receipt) {
        if (receipt.referenceExternalId() == null) {
            return List.of();
        }
        if (receipt.referenceSourceSystem() != null) {
            return declarationsByKey.getOrDefault(
                    referenceKey(receipt.referenceSourceSystem(), receipt.referenceExternalId()), List.of());
        }
        return declarationsByKey.entrySet().stream()
                .filter(e -> e.getKey().endsWith("|" + receipt.referenceExternalId()))
                .flatMap(e -> e.getValue().stream())
                .toList();
    }

    private static String referenceKey(String sourceSystem, String externalId) {
        return (sourceSystem == null ? "*" : sourceSystem) + '|' + externalId;
    }

    private static final class State {

        private final ZoneId zone;
        private final Map<String, MaterialAcc> materials = new TreeMap<>();
        private final List<AllocationRow> allocations = new ArrayList<>();
        private final List<DiscrepancyDraft> discrepancies = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
        private final List<String> fatal = new ArrayList<>();

        State(ZoneId zone) {
            this.zone = zone;
        }

        void addOpening(Snapshot.SnapshotLot lot) {
            MaterialAcc acc = material(lot.materialCode());
            if (WIP.equalsIgnoreCase(lot.pool())) {
                acc.wipOpening = Qty.add(acc.wipOpening, lot.quantity());
                acc.wipLots.add(new Lot(lot.lotKey(), lot.materialCode(), lot.batchNo(), WIP, lot.quantity(),
                        lot.remaining(), lot.businessDate(), lot.sourceDocumentId(), lot.sourceType()));
            } else {
                acc.opening = Qty.add(acc.opening, lot.quantity());
                acc.stockLots.add(new Lot(lot.lotKey(), lot.materialCode(), lot.batchNo(), STOCK, lot.quantity(),
                        lot.remaining(), lot.businessDate(), lot.sourceDocumentId(), lot.sourceType()));
                acc.sortLots();
            }
        }

        void applyOpeningBalance(Snapshot.SnapshotDoc doc, LocalDate businessDate) {
            for (Snapshot.SnapshotLine line : doc.lines()) {
                if (line.baseQuantity() == null) {
                    unresolved(line, doc);
                    continue;
                }
                if (line.baseQuantity().signum() <= 0) {
                    continue;
                }
                String batch = batchOf(line, doc);
                MaterialAcc acc = material(line.materialCode());
                acc.opening = Qty.add(acc.opening, line.baseQuantity());
                acc.addStockLot(line.materialCode(), batch, line.baseQuantity(), businessDate, doc.id(), "OPENING");
            }
        }

        void applyDeclaration(Snapshot.SnapshotDoc doc, LocalDate businessDate,
                              Map<String, List<Snapshot.SnapshotDoc>> receiptsByReference,
                              Map<String, List<Snapshot.SnapshotDoc>> warehouseReceiptsByReference) {
            List<Snapshot.SnapshotDoc> acknowledgements = lookupByReference(receiptsByReference, doc);
            List<Snapshot.SnapshotDoc> warehouseReceipts = lookupByReference(warehouseReceiptsByReference, doc);

            // The declaration is the legal basis and is always posted. A missing customs acknowledgement
            // is a pending difference, not a reason to withhold the goods from the ledger.
            Map<String, BigDecimal> declaredByBucket = new LinkedHashMap<>();
            for (Snapshot.SnapshotLine line : doc.lines()) {
                if (line.baseQuantity() == null) {
                    unresolved(line, doc);
                    continue;
                }
                if (line.baseQuantity().signum() <= 0) {
                    continue;
                }
                String batch = batchOf(line, doc);
                MaterialAcc acc = material(line.materialCode());
                acc.inbound = Qty.add(acc.inbound, line.baseQuantity());
                acc.addStockLot(line.materialCode(), batch, line.baseQuantity(), businessDate, doc.id(), "DECLARATION");
                declaredByBucket.merge(bucket(line.materialCode(), batch), line.baseQuantity(), Qty::add);
            }

            if (acknowledgements.isEmpty()) {
                BigDecimal declared = declaredByBucket.values().stream().reduce(BigDecimal.ZERO, Qty::add);
                discrepancy("UNACKNOWLEDGED_DECLARATION", null, doc,
                        "declaration has no customs acknowledgement receipt yet; the inbound is posted on the "
                                + "declaration and the receipt is still outstanding", declared, BigDecimal.ZERO);
            }

            if (warehouseReceipts.isEmpty()) {
                return;
            }
            Map<String, BigDecimal> received = new HashMap<>();
            for (Snapshot.SnapshotDoc receipt : warehouseReceipts) {
                for (Snapshot.SnapshotLine line : receipt.lines()) {
                    if (line.materialCode() == null || line.baseQuantity() == null) {
                        continue;
                    }
                    received.merge(bucket(line.materialCode(), line.batchNo()), line.baseQuantity(), Qty::add);
                }
            }
            for (Map.Entry<String, BigDecimal> entry : declaredByBucket.entrySet()) {
                BigDecimal declaredQty = entry.getValue();
                BigDecimal receivedQty = received.getOrDefault(entry.getKey(), BigDecimal.ZERO);
                if (receivedQty.compareTo(declaredQty) != 0) {
                    String material = entry.getKey().substring(0, entry.getKey().indexOf('|'));
                    String batch = entry.getKey().substring(entry.getKey().indexOf('|') + 1);
                    discrepancy("DECLARED_VS_RECEIVED", material, doc,
                            "warehouse receipt quantity differs from declaration for batch " + batch
                                    + "; the declaration remains the legal basis and the difference needs a "
                                    + "follow-up adjustment", declaredQty, receivedQty);
                }
            }
        }

        /** Matches a receipt to the declaration it references, falling back to id-only matching. */
        private List<Snapshot.SnapshotDoc> lookupByReference(
                Map<String, List<Snapshot.SnapshotDoc>> byReference, Snapshot.SnapshotDoc declaration) {
            if (declaration.externalId() == null) {
                return List.of();
            }
            List<Snapshot.SnapshotDoc> hits = new ArrayList<>(
                    byReference.getOrDefault(referenceKey(declaration.sourceSystem(), declaration.externalId()),
                            List.of()));
            if (hits.isEmpty()) {
                hits.addAll(byReference.getOrDefault(referenceKey(null, declaration.externalId()), List.of()));
            }
            byReference.forEach((key, docs) -> {
                if (key.endsWith("|" + declaration.externalId()) && !hits.containsAll(docs)) {
                    docs.forEach(d -> {
                        if (!hits.contains(d)) {
                            hits.add(d);
                        }
                    });
                }
            });
            return hits;
        }

        void applyIssue(Snapshot.SnapshotDoc doc, LocalDate businessDate) {
            for (Snapshot.SnapshotLine line : doc.lines()) {
                if (line.baseQuantity() == null) {
                    unresolved(line, doc);
                    continue;
                }
                if (line.baseQuantity().signum() <= 0) {
                    continue;
                }
                MaterialAcc acc = material(line.materialCode());
                List<MoveDest> targets = new ArrayList<>();
                BigDecimal allocated = allocate(acc.stockLots, line.baseQuantity(), line.batchNo(), doc, "ISSUE",
                        line.lineNo(), line.workOrder(), acc.code, STOCK, "ISSUE_SHORTAGE", targets);
                acc.consumed = Qty.add(acc.consumed, allocated);
                if (allocated.signum() > 0) {
                    acc.allocationCount++;
                }
                // Move whatever was allocated into the work-in-process pool, keeping the lot identity.
                for (MoveDest t : targets) {
                    acc.addWipFromIssue(t.lot, t.quantity, line.workOrder(), doc.id(), businessDate);
                }
            }
        }

        void applyOutbound(Snapshot.SnapshotDoc doc, LocalDate businessDate) {
            for (Snapshot.SnapshotLine line : doc.lines()) {
                if (line.outputMaterialCode() != null) {
                    if (line.contents() == null || line.contents().isEmpty()) {
                        discrepancy("MISSING_PROCESSING_RELATION", line.outputMaterialCode(), doc,
                                "no processing relation for output " + line.outputMaterialCode()
                                        + " on the outbound business date; embedded material cannot be written off",
                                line.baseQuantity(), BigDecimal.ZERO);
                        continue;
                    }
                    for (Snapshot.ContentPart part : line.contents()) {
                        MaterialAcc acc = material(part.materialCode());
                        String workOrder = line.workOrder();
                        BigDecimal allocated = allocate(acc.wipLotsFor(workOrder), part.quantity(), null, doc,
                                "OUTBOUND_WRITE_OFF", line.lineNo(), workOrder, acc.code, WIP,
                                "OUTBOUND_CONTENT_SHORTAGE", new ArrayList<>());
                        acc.outbound = Qty.add(acc.outbound, allocated);
                        acc.outboundContent = Qty.add(acc.outboundContent, allocated);
                        if (allocated.signum() > 0) {
                            acc.allocationCount++;
                        }
                    }
                } else if (line.baseQuantity() != null) {
                    if (line.baseQuantity().signum() <= 0) {
                        continue;
                    }
                    MaterialAcc acc = material(line.materialCode());
                    BigDecimal allocated = allocate(acc.stockLots, line.baseQuantity(), line.batchNo(), doc,
                            "OUTBOUND_DIRECT", line.lineNo(), null, acc.code, STOCK, "OUTBOUND_SHORTAGE",
                            new ArrayList<>());
                    acc.outbound = Qty.add(acc.outbound, allocated);
                    acc.directOutbound = Qty.add(acc.directOutbound, allocated);
                    if (allocated.signum() > 0) {
                        acc.allocationCount++;
                    }
                } else {
                    unresolved(line, doc);
                }
            }
        }

        void applyAdjustment(Snapshot.SnapshotDoc doc, LocalDate businessDate) {
            if (doc.correctionReason() == null || doc.correctionReason().isBlank()) {
                discrepancy("ADJUSTMENT_WITHOUT_REASON", null, doc,
                        "manual adjustment must state a reason for the customs file");
            }
            for (Snapshot.SnapshotLine line : doc.lines()) {
                if (line.baseQuantity() == null) {
                    unresolved(line, doc);
                    continue;
                }
                MaterialAcc acc = material(line.materialCode());
                if (line.baseQuantity().signum() >= 0) {
                    String batch = batchOf(line, doc);
                    acc.adjust = Qty.add(acc.adjust, line.baseQuantity());
                    acc.addStockLot(line.materialCode(), batch, line.baseQuantity(), businessDate, doc.id(),
                            "ADJUSTMENT");
                } else {
                    BigDecimal demand = line.baseQuantity().negate();
                    BigDecimal allocated = allocate(acc.stockLots, demand, line.batchNo(), doc, "ADJUSTMENT",
                            line.lineNo(), null, acc.code, STOCK, "ADJUSTMENT_SHORTAGE", new ArrayList<>());
                    acc.adjust = Qty.subtract(acc.adjust, allocated);
                    if (allocated.signum() > 0) {
                        acc.allocationCount++;
                    }
                }
            }
        }

        void applyStocktake(Snapshot.SnapshotDoc doc, LocalDate businessDate) {
            for (Snapshot.SnapshotLine line : doc.lines()) {
                if (line.baseQuantity() == null) {
                    unresolved(line, doc);
                    continue;
                }
                MaterialAcc acc = materials.get(line.materialCode());
                BigDecimal onHand = acc == null ? Qty.normalize(BigDecimal.ZERO) : acc.stockRemaining(line.batchNo());
                BigDecimal counted = line.baseQuantity();
                if (onHand.compareTo(counted) != 0) {
                    discrepancy("STOCKTAKE_VARIANCE", line.materialCode(), doc,
                            "stocktake count differs from ledger quantity"
                                    + (line.batchNo() == null ? "" : " for batch " + line.batchNo())
                                    + "; post an ADJUSTMENT once the cause is confirmed", onHand, counted);
                }
            }
        }

        private record MoveDest(Lot lot, BigDecimal quantity) {
        }

        /**
         * Serial allocation over a pool: consumes FIFO (or the named batch only), caps at availability and
         * records residual demand as a discrepancy. Returns the quantity actually allocated.
         */
        private BigDecimal allocate(List<Lot> lots, BigDecimal demand, String batch, Snapshot.SnapshotDoc doc,
                                    String movementType, int lineNo, String workOrder, String materialCode,
                                    String pool, String shortageKind, List<MoveDest> targets) {
            BigDecimal remainingDemand = Qty.normalize(demand);
            BigDecimal allocatedTotal = Qty.normalize(BigDecimal.ZERO);
            if (remainingDemand.signum() <= 0) {
                return allocatedTotal;
            }
            for (Lot lot : new ArrayList<>(lots)) {
                if (remainingDemand.signum() <= 0) {
                    break;
                }
                if (lot.remaining.signum() <= 0) {
                    continue;
                }
                if (batch != null && !batch.equals(lot.batchNo)) {
                    continue;
                }
                BigDecimal take = Qty.normalize(remainingDemand.min(lot.remaining));
                if (take.signum() <= 0) {
                    continue;
                }
                lot.remaining = Qty.subtract(lot.remaining, take);
                remainingDemand = Qty.subtract(remainingDemand, take);
                allocatedTotal = Qty.add(allocatedTotal, take);
                MaterialAcc acc = material(materialCode);
                allocations.add(new AllocationRow(materialCode, lot.lotKey, lot.batchNo, pool, movementType,
                        doc.id(), lineNo, workOrder, take, acc.nextSeq()));
                targets.add(new MoveDest(lot, take));
            }
            if (remainingDemand.signum() > 0) {
                discrepancy(shortageKind, materialCode, doc,
                        "unmet demand of " + Qty.canonical(remainingDemand)
                                + (batch == null ? "" : " for batch " + batch) + " in the " + pool.toLowerCase()
                                + " pool; allocation was capped at available quantity, nothing over-consumed",
                        Qty.normalize(demand), allocatedTotal);
            }
            return allocatedTotal;
        }

        private String batchOf(Snapshot.SnapshotLine line, Snapshot.SnapshotDoc doc) {
            if (line.batchNo() != null && !line.batchNo().isBlank()) {
                return line.batchNo();
            }
            discrepancy("MISSING_BATCH", line.materialCode(), doc,
                    "line has no batch number; an AUTO batch was assigned for traceability");
            return "AUTO-" + doc.externalId() + "-" + line.lineNo();
        }

        private void unresolved(Snapshot.SnapshotLine line, Snapshot.SnapshotDoc doc) {
            if (line.materialCode() == null) {
                discrepancy("UNMAPPED_ITEM", null, doc,
                        "no material mapping for source item '" + line.sourceItemCode()
                                + "' effective on the document business date", line.quantity(), BigDecimal.ZERO);
            } else if (line.baseQuantity() == null) {
                discrepancy("UNMAPPED_UNIT", line.materialCode(), doc,
                        "no unit conversion from '" + line.unit() + "' for material " + line.materialCode()
                                + " effective on the document business date", line.quantity(), BigDecimal.ZERO);
            }
        }

        private MaterialAcc material(String code) {
            return materials.computeIfAbsent(code, MaterialAcc::new);
        }

        private void discrepancy(String kind, String materialCode, Snapshot.SnapshotDoc doc, String message) {
            discrepancy(kind, materialCode, doc, message, null, null);
        }

        private void discrepancy(String kind, String materialCode, Snapshot.SnapshotDoc doc, String message,
                                 BigDecimal expected, BigDecimal actual) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("documentId", doc.id());
            detail.put("docType", doc.docType());
            detail.put("sourceSystem", doc.sourceSystem());
            detail.put("externalId", doc.externalId());
            detail.put("sourceVersion", doc.sourceVersion());
            detail.put("businessTime", doc.businessTime().toString());
            detail.put("payloadHash", doc.payloadHash());
            String sourceItemCode = null;
            if (materialCode == null && doc.lines().size() == 1) {
                sourceItemCode = doc.lines().getFirst().sourceItemCode();
            }
            discrepancies.add(new DiscrepancyDraft(kind, materialCode, doc.sourceSystem(), sourceItemCode,
                    expected == null ? null : Qty.normalize(expected),
                    actual == null ? null : Qty.normalize(actual), message, detail));
            if (kind.endsWith("SHORTAGE") || kind.equals("NEGATIVE_BALANCE")) {
                warnings.add(message);
            }
        }

        Computation finish(Snapshot snapshot) {
            Map<String, Snapshot.PriorTotal> prior = snapshot.priorTotals().stream()
                    .collect(Collectors.toMap(Snapshot.PriorTotal::materialCode, p -> p, (a, b) -> b));

            List<MaterialResult> results = new ArrayList<>();
            List<LotState> lotStates = new ArrayList<>();

            for (MaterialAcc acc : materials.values()) {
                BigDecimal stockRemaining = BigDecimal.ZERO.setScale(Qty.SCALE);
                for (Lot lot : acc.stockLots) {
                    checkLot(lot);
                    stockRemaining = Qty.add(stockRemaining, lot.remaining);
                    lotStates.add(new LotState(lot.lotKey, acc.code, lot.batchNo, STOCK, lot.quantity,
                            lot.remaining, lot.businessDate, lot.sourceDocumentId, lot.sourceType));
                }
                BigDecimal wipRemaining = BigDecimal.ZERO.setScale(Qty.SCALE);
                for (Lot lot : acc.wipLots) {
                    checkLot(lot);
                    wipRemaining = Qty.add(wipRemaining, lot.remaining);
                    lotStates.add(new LotState(lot.lotKey, acc.code, lot.batchNo, WIP, lot.quantity,
                            lot.remaining, lot.businessDate, lot.sourceDocumentId, lot.sourceType));
                }

                BigDecimal expectedStock = Qty.add(
                        Qty.subtract(Qty.add(acc.opening, acc.inbound), acc.consumed), acc.adjust);
                // Direct outbound from stock also leaves the stock pool.
                expectedStock = Qty.subtract(expectedStock, acc.directOutbound);
                if (expectedStock.compareTo(stockRemaining) != 0) {
                    fatal.add("material " + acc.code + " stock closing " + Qty.canonical(stockRemaining)
                            + " does not equal opening+inbound-issued-direct outbound+adjust "
                            + Qty.canonical(expectedStock));
                }
                BigDecimal expectedWip = Qty.subtract(Qty.add(acc.wipOpening, acc.consumed), acc.outboundContent);
                if (expectedWip.compareTo(wipRemaining) != 0) {
                    fatal.add("material " + acc.code + " WIP closing " + Qty.canonical(wipRemaining)
                            + " does not equal WIP opening + issued - outbound content "
                            + Qty.canonical(expectedWip));
                }
                if (stockRemaining.signum() < 0 || wipRemaining.signum() < 0) {
                    discrepancy("NEGATIVE_BALANCE", acc.code, snapshotMarker(snapshot),
                            "closing balance is negative", BigDecimal.ZERO,
                            stockRemaining.signum() < 0 ? stockRemaining : wipRemaining);
                }

                Snapshot.PriorTotal p = prior.getOrDefault(acc.code, new Snapshot.PriorTotal(acc.code,
                        BigDecimal.ZERO.setScale(Qty.SCALE), BigDecimal.ZERO.setScale(Qty.SCALE)));
                BigDecimal cumulativeConsumed = Qty.add(p.cumulativeConsumed(), acc.consumed);
                BigDecimal cumulativeContent = Qty.add(p.cumulativeOutboundContent(), acc.outboundContent);
                if (cumulativeContent.compareTo(cumulativeConsumed) > 0) {
                    discrepancy("CONTENT_EXCEEDS_ISSUE", acc.code, snapshotMarker(snapshot),
                            "cumulative finished-goods content " + Qty.canonical(cumulativeContent)
                                    + " exceeds cumulative issue " + Qty.canonical(cumulativeConsumed)
                                    + "; output may embed unbooked bonded material",
                            cumulativeConsumed, cumulativeContent);
                }

                boolean touched = acc.opening.signum() != 0 || acc.inbound.signum() != 0
                        || acc.consumed.signum() != 0 || acc.outbound.signum() != 0
                        || acc.adjust.signum() != 0 || acc.wipOpening.signum() != 0
                        || wipRemaining.signum() != 0;
                if (!touched) {
                    continue;
                }
                results.add(new MaterialResult(acc.code, acc.opening, acc.inbound, acc.consumed, acc.outbound,
                        acc.outboundContent, acc.adjust, stockRemaining, acc.wipOpening, wipRemaining,
                        cumulativeConsumed, cumulativeContent, acc.allocationCount));
            }

            allocations.sort(Comparator.comparing(AllocationRow::materialCode)
                    .thenComparing(AllocationRow::allocationSeq));
            lotStates.sort(Comparator.comparing(LotState::materialCode).thenComparing(LotState::lotKey));

            return new Computation(results, allocations, lotStates, discrepancies, List.of(), List.of(),
                    List.copyOf(new java.util.LinkedHashSet<>(warnings)),
                    List.copyOf(new java.util.LinkedHashSet<>(fatal)));
        }

        private void checkLot(Lot lot) {
            if (lot.remaining.signum() < 0) {
                fatal.add("lot " + lot.lotKey + " has negative remaining " + Qty.canonical(lot.remaining));
            }
            if (lot.allocated().compareTo(lot.quantity) > 0) {
                fatal.add("lot " + lot.lotKey + " allocated " + Qty.canonical(lot.allocated())
                        + " exceeds its quantity " + Qty.canonical(lot.quantity));
            }
        }

        private Snapshot.SnapshotDoc snapshotMarker(Snapshot snapshot) {
            return new Snapshot.SnapshotDoc(0L, "PERIOD", "ledger", snapshot.period(), 0,
                    java.time.Instant.EPOCH, "", null, null, null, null, null, List.of());
        }

        private static String bucket(String materialCode, String batch) {
            return materialCode + '|' + (batch == null ? "" : batch);
        }
    }

    private static final class MaterialAcc {
        final String code;
        final List<Lot> stockLots = new ArrayList<>();
        final List<Lot> wipLots = new ArrayList<>();
        BigDecimal opening = BigDecimal.ZERO.setScale(Qty.SCALE);
        BigDecimal inbound = BigDecimal.ZERO.setScale(Qty.SCALE);
        BigDecimal consumed = BigDecimal.ZERO.setScale(Qty.SCALE);
        BigDecimal directOutbound = BigDecimal.ZERO.setScale(Qty.SCALE);
        BigDecimal outbound = BigDecimal.ZERO.setScale(Qty.SCALE);
        BigDecimal outboundContent = BigDecimal.ZERO.setScale(Qty.SCALE);
        BigDecimal adjust = BigDecimal.ZERO.setScale(Qty.SCALE);
        BigDecimal wipOpening = BigDecimal.ZERO.setScale(Qty.SCALE);
        int allocationCount;
        private int seq;

        MaterialAcc(String code) {
            this.code = code;
        }

        void addStockLot(String materialCode, String batch, BigDecimal quantity, LocalDate businessDate,
                         Long documentId, String sourceType) {
            String lotKey = materialCode + '|' + batch;
            for (Lot lot : stockLots) {
                if (lot.lotKey.equals(lotKey)) {
                    lot.quantity = Qty.add(lot.quantity, quantity);
                    lot.remaining = Qty.add(lot.remaining, quantity);
                    return;
                }
            }
            stockLots.add(new Lot(lotKey, materialCode, batch, STOCK, quantity, quantity, businessDate,
                    documentId, sourceType));
            sortLots();
        }

        void addWipFromIssue(Lot source, BigDecimal quantity, String workOrder, Long documentId,
                             LocalDate businessDate) {
            String wo = workOrder == null || workOrder.isBlank() ? "UNASSIGNED" : workOrder;
            String lotKey = code + "|WIP|" + source.batchNo + '|' + wo;
            for (Lot lot : wipLots) {
                if (lot.lotKey.equals(lotKey)) {
                    lot.quantity = Qty.add(lot.quantity, quantity);
                    lot.remaining = Qty.add(lot.remaining, quantity);
                    return;
                }
            }
            Lot created = new Lot(lotKey, code, source.batchNo, WIP, quantity, quantity, businessDate, documentId,
                    "ISSUE");
            created.workOrder = wo;
            wipLots.add(created);
            wipLots.sort(Comparator.comparing((Lot l) -> l.businessDate).thenComparing(l -> l.lotKey));
        }

        List<Lot> wipLotsFor(String workOrder) {
            if (workOrder == null || workOrder.isBlank()) {
                return wipLots;
            }
            List<Lot> matching = new ArrayList<>();
            for (Lot lot : wipLots) {
                if (workOrder.equals(lot.workOrder)) {
                    matching.add(lot);
                }
            }
            // Fall back to any WIP when the work order was not recorded on the issue.
            return matching.isEmpty() ? wipLots : matching;
        }

        void sortLots() {
            stockLots.sort(Comparator.comparing((Lot l) -> l.businessDate).thenComparing(l -> l.lotKey));
        }

        BigDecimal stockRemaining(String batch) {
            BigDecimal total = BigDecimal.ZERO.setScale(Qty.SCALE);
            for (Lot lot : stockLots) {
                if (batch == null || batch.equals(lot.batchNo)) {
                    total = Qty.add(total, lot.remaining);
                }
            }
            return total;
        }

        int nextSeq() {
            return ++seq;
        }
    }

    private static final class Lot {
        final String lotKey;
        final String materialCode;
        final String batchNo;
        final String pool;
        BigDecimal quantity;
        BigDecimal remaining;
        final LocalDate businessDate;
        final Long sourceDocumentId;
        final String sourceType;
        String workOrder;

        Lot(String lotKey, String materialCode, String batchNo, String pool, BigDecimal quantity,
            BigDecimal remaining, LocalDate businessDate, Long sourceDocumentId, String sourceType) {
            this.lotKey = lotKey;
            this.materialCode = materialCode;
            this.batchNo = batchNo;
            this.pool = pool;
            this.quantity = quantity;
            this.remaining = remaining;
            this.businessDate = businessDate;
            this.sourceDocumentId = sourceDocumentId;
            this.sourceType = sourceType;
        }

        BigDecimal allocated() {
            return Qty.subtract(quantity, remaining);
        }
    }
}
