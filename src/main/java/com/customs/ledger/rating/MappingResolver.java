package com.customs.ledger.rating;

import com.customs.ledger.common.ApiException;
import com.customs.ledger.common.Qty;
import com.customs.ledger.domain.Alias;
import com.customs.ledger.domain.Conversion;
import com.customs.ledger.domain.LedgerRepository;
import com.customs.ledger.domain.ProcessingRelation;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Resolves source item codes and units using the mapping that was effective on the document's
 * business date. Never uses "current" mappings, so a mid-period rename cannot retroactively
 * rewrite the whole month's quantities.
 */
@Service
public class MappingResolver {

    private final LedgerRepository ledger;

    public MappingResolver(LedgerRepository ledger) {
        this.ledger = ledger;
    }

    public record AliasResolution(Alias alias, String materialCode, String materialName, String baseUnit) {
    }

    public record ConversionResolution(Conversion conversion, BigDecimal factor) {
    }

    public record ContentPart(String materialCode, BigDecimal quantity) {
    }

    public AliasResolution resolveAlias(long ledgerId, String sourceItemCode, LocalDate businessDate) {
        List<Alias> candidates = ledger.aliasesEffectiveOn(ledgerId, sourceItemCode, businessDate);
        if (candidates.isEmpty()) {
            throw ApiException.unprocessable("UNMAPPED_ITEM",
                    "no material mapping for source item '" + sourceItemCode + "' effective on " + businessDate);
        }
        // A source system specific alias wins over a source-system agnostic one when both are effective.
        Alias chosen = candidates.getFirst();
        var material = ledger.requireMaterialById(chosen.materialId());
        return new AliasResolution(chosen, material.code(), material.name(), material.baseUnit());
    }

    public Optional<AliasResolution> tryResolveAlias(long ledgerId, String sourceItemCode, LocalDate businessDate) {
        if (ledger.aliasesEffectiveOn(ledgerId, sourceItemCode, businessDate).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(resolveAlias(ledgerId, sourceItemCode, businessDate));
    }

    public ConversionResolution resolveConversion(long ledgerId, long materialId, String materialCode, String baseUnit,
                                                  String sourceUnit, LocalDate businessDate) {
        if (sourceUnit == null || sourceUnit.equalsIgnoreCase(baseUnit)) {
            return new ConversionResolution(null, BigDecimal.ONE);
        }
        List<Conversion> rows = ledger.conversionsFor(ledgerId, materialId, sourceUnit, businessDate);
        if (rows.isEmpty()) {
            throw ApiException.unprocessable("UNMAPPED_UNIT",
                    "no unit conversion from '" + sourceUnit + "' to '" + baseUnit + "' for material "
                            + materialCode + " effective on " + businessDate);
        }
        Conversion chosen = rows.getFirst();
        return new ConversionResolution(chosen, chosen.factor());
    }

    public BigDecimal toBase(ConversionResolution resolution, BigDecimal sourceQuantity) {
        return Qty.multiply(sourceQuantity, resolution.factor());
    }

    /** Single-consumption content of an output material per its effective processing relations. */
    public List<ContentPart> contentParts(long ledgerId, long outputMaterialId, BigDecimal outputQuantity,
                                          LocalDate businessDate) {
        List<ProcessingRelation> relations = ledger.relationsFor(ledgerId, outputMaterialId, businessDate);
        return relations.stream()
                .map(rel -> {
                    var input = ledger.requireMaterialById(rel.inputMaterialId());
                    BigDecimal content = Qty.multiply(outputQuantity, rel.qtyPerOutput());
                    return new ContentPart(input.code(), content);
                })
                .filter(p -> Qty.isPositive(p.quantity()))
                .toList();
    }
}
