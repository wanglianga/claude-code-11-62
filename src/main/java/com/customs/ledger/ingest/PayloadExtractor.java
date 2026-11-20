package com.customs.ledger.ingest;

import com.customs.ledger.common.Qty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Canonicalises source documents from different systems into one line format. The full original
 * request is always stored verbatim; this only produces the internal view used for calculation.
 *
 * <p>Canonical payload:
 * <pre>
 * {
 *   "reference": { "type": "DECLARATION", "externalId": "...", "sourceSystem": "..." },
 *   "lines": [
 *     { "lineNo":1, "itemCode":"...", "unit":"KG", "quantity":100, "batchNo":"B1",
 *       "workOrder":"WO-1", "outputItemCode":"FG-1", "barcode":"...", "extra":{...} }
 *   ]
 * }
 * </pre>
 */
public final class PayloadExtractor {

    private PayloadExtractor() {
    }

    private static final List<String> ITEM_KEYS = List.of(
            "itemCode", "item_code", "materialCode", "material_code", "sku", "skuCode", "sku_code",
            "partNo", "part_no", "partNumber", "goodsNo", "goods_no");
    private static final List<String> UNIT_KEYS = List.of("unit", "uom", "unitOfMeasure", "unit_of_measure");
    private static final List<String> QTY_KEYS = List.of(
            "quantity", "qty", "qtyBase", "qty_base", "countedQty", "counted_qty", "amount");
    private static final List<String> BATCH_KEYS = List.of(
            "batchNo", "batch_no", "batch", "lotNo", "lot_no", "lot");
    private static final List<String> WORK_ORDER_KEYS = List.of(
            "workOrder", "work_order", "workOrderNo", "work_order_no", "moNo", "mo_no", "orderNo", "order_no");
    private static final List<String> OUTPUT_KEYS = List.of(
            "outputItemCode", "output_item_code", "finishedGoodsCode", "finished_goods_code",
            "productCode", "product_code", "fgCode", "fg_code");
    private static final List<String> REFERENCE_ID_KEYS = List.of(
            "referenceExternalId", "reference_external_id", "declarationNo", "declaration_no",
            "referenceNo", "reference_no", "declarationId", "declaration_id", "relatedDeclaration");
    private static final List<String> REFERENCE_SYSTEM_KEYS = List.of(
            "referenceSourceSystem", "reference_source_system", "declarationSourceSystem",
            "declaration_source_system", "referenceSystem", "reference_system");
    private static final List<String> LINE_ARRAY_KEYS = List.of("lines", "items", "details", "entries", "rows");

    public record Canonical(String referenceExternalId, String referenceSourceSystem, List<CanonicalLine> lines) {
    }

    public record CanonicalLine(int lineNo, String itemCode, String unit, BigDecimal quantity, String batchNo,
                                String workOrder, String outputItemCode, JsonNode extra) {
    }

    public static Canonical canonicalise(JsonNode root, String docType) {
        String refId = firstText(root, REFERENCE_ID_KEYS);
        String refSystem = firstText(root, REFERENCE_SYSTEM_KEYS);

        JsonNode array = firstArray(root, LINE_ARRAY_KEYS);
        if (array == null && root.isArray()) {
            array = root;
        }
        if (array == null) {
            // A single-line document may put the fields directly on the root, or inside a singular "line".
            JsonNode line = root.has("line") && root.get("line").isObject() ? root.get("line") : null;
            if (line != null || looksLikeLine(root)) {
                ObjectNode synthetic = root.deepCopy();
                ObjectNode wrapper = synthetic.objectNode();
                var arr = wrapper.putArray("lines");
                arr.add(line != null ? line.deepCopy() : synthetic);
                root = wrapper;
                array = arr;
            }
        }
        if (array == null) {
            throw com.customs.ledger.common.ApiException.badRequest("MISSING_LINES",
                    "payload contains no line array (expected one of: " + String.join(", ", LINE_ARRAY_KEYS) + ")");
        }

        List<CanonicalLine> lines = new ArrayList<>();
        int index = 1;
        for (Iterator<JsonNode> it = array.elements(); it.hasNext(); ) {
            JsonNode raw = it.next();
            if (raw == null || !raw.isObject()) {
                throw com.customs.ledger.common.ApiException.badRequest("INVALID_LINE",
                        "line " + index + " is not an object");
            }
            Integer explicitNo = null;
            if (raw.hasNonNull("lineNo")) {
                explicitNo = raw.get("lineNo").asInt();
            } else if (raw.hasNonNull("line_no")) {
                explicitNo = raw.get("line_no").asInt();
            }
            int lineNo = explicitNo == null ? index : explicitNo;

            String itemCode = firstText(raw, ITEM_KEYS);
            String unit = firstText(raw, UNIT_KEYS);
            BigDecimal quantity = firstNumber(raw, QTY_KEYS);
            String batch = firstText(raw, BATCH_KEYS);
            String workOrder = firstText(raw, WORK_ORDER_KEYS);
            String output = firstText(raw, OUTPUT_KEYS);

            if (quantity == null) {
                throw com.customs.ledger.common.ApiException.badRequest("INVALID_LINE",
                        "line " + lineNo + " has no quantity (expected one of: " + String.join(", ", QTY_KEYS) + ")");
            }
            if (itemCode == null && output == null) {
                throw com.customs.ledger.common.ApiException.badRequest("INVALID_LINE",
                        "line " + lineNo + " identifies neither an item code nor an output item code");
            }
            if (itemCode == null && !docType.equals("OUTBOUND")) {
                throw com.customs.ledger.common.ApiException.badRequest("INVALID_LINE",
                        "line " + lineNo + " has no item code (expected one of: " + String.join(", ", ITEM_KEYS) + ")");
            }
            lines.add(new CanonicalLine(lineNo, itemCode, unit, Qty.normalize(quantity), batch, workOrder,
                    output, raw));
            index++;
        }
        return new Canonical(refId, refSystem, lines);
    }

    private static boolean looksLikeLine(JsonNode node) {
        return firstText(node, ITEM_KEYS) != null && firstNumber(node, QTY_KEYS) != null;
    }

    private static JsonNode firstArray(JsonNode node, List<String> names) {
        for (String name : names) {
            if (node.hasNonNull(name) && node.get(name).isArray()) {
                return node.get(name);
            }
        }
        return null;
    }

    private static String firstText(JsonNode node, List<String> names) {
        for (String name : names) {
            if (node.hasNonNull(name) && !node.get(name).isContainerNode()) {
                String value = node.get(name).asText();
                if (!value.isBlank()) {
                    return value.trim();
                }
            }
        }
        return null;
    }

    private static BigDecimal firstNumber(JsonNode node, List<String> names) {
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value == null || value.isNull()) {
                continue;
            }
            if (value.isNumber()) {
                return value.decimalValue();
            }
            if (value.isTextual() && !value.asText().isBlank()) {
                try {
                    return new BigDecimal(value.asText().trim());
                } catch (NumberFormatException ignored) {
                    // try the next alias
                }
            }
        }
        return null;
    }

    public static String normaliseUnit(String unit) {
        return unit == null ? null : unit.trim().toUpperCase(Locale.ROOT);
    }
}
