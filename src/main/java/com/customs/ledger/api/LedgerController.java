package com.customs.ledger.api;

import com.customs.ledger.common.ApiException;
import com.customs.ledger.domain.Ledger;
import com.customs.ledger.domain.LedgerRepository;
import com.customs.ledger.domain.Material;
import com.customs.ledger.domain.Alias;
import com.customs.ledger.domain.Conversion;
import com.customs.ledger.domain.ProcessingRelation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/ledgers")
@Tag(name = "Ledger setup", description = "Ledger, materials and effective-dated mappings")
public class LedgerController {

    private final LedgerRepository ledgers;

    public LedgerController(LedgerRepository ledgers) {
        this.ledgers = ledgers;
    }

    public record CreateLedger(@NotBlank(message = "code is required") String code,
                               @NotBlank(message = "name is required") String name,
                               String zone) {
    }

    public record LedgerView(long id, String code, String name, String zone,
                             List<MaterialView> materials, List<AliasView> aliases,
                             List<ConversionView> conversions, List<RelationView> relations) {
    }

    public record MaterialView(String code, String name, String baseUnit) {
    }

    public record AliasView(long id, String sourceSystem, String sourceItemCode, String material,
                            LocalDate validFrom, LocalDate validTo) {
    }

    public record ConversionView(long id, String material, String fromUnit, BigDecimal factor,
                                 LocalDate validFrom, LocalDate validTo) {
    }

    public record RelationView(long id, String outputMaterial, String inputMaterial, BigDecimal qtyPerOutput,
                               LocalDate validFrom, LocalDate validTo) {
    }

    public record UpsertMaterial(@NotBlank String code, @NotBlank String name, @NotBlank String baseUnit) {
    }

    public record UpsertAlias(String sourceSystem, @NotBlank String sourceItemCode, @NotBlank String material,
                              @NotNull LocalDate validFrom, LocalDate validTo, String note) {
    }

    public record UpsertConversion(@NotBlank String material, @NotBlank String fromUnit,
                                   @NotNull @Positive BigDecimal factor,
                                   @NotNull LocalDate validFrom, LocalDate validTo) {
    }

    public record UpsertRelation(@NotBlank String outputMaterial, @NotBlank String inputMaterial,
                                 @NotNull @Positive BigDecimal qtyPerOutput,
                                 @NotNull LocalDate validFrom, LocalDate validTo, String note) {
    }

    @PostMapping
    @Operation(summary = "Create a ledger (bonded account book)")
    public LedgerView create(@Valid @RequestBody CreateLedger body) {
        if (ledgers.findLedger(body.code()).isPresent()) {
            throw ApiException.conflict("LEDGER_EXISTS", "ledger " + body.code() + " already exists");
        }
        Ledger ledger = ledgers.createLedger(body.code(), body.name(),
                body.zone() == null || body.zone().isBlank() ? "Asia/Shanghai" : body.zone());
        return view(ledger);
    }

    @GetMapping("/{code}")
    @Operation(summary = "Ledger with its materials and mapping definitions")
    public LedgerView get(@PathVariable String code) {
        return view(ledgers.requireLedger(code));
    }

    @GetMapping
    @Operation(summary = "List ledgers")
    public List<LedgerView> list() {
        return ledgers.allLedgers().stream().map(this::view).toList();
    }

    @PostMapping("/{code}/materials")
    @Operation(summary = "Add or update a material (canonical code + base unit)")
    public MaterialView upsertMaterial(@PathVariable String code, @Valid @RequestBody UpsertMaterial body) {
        Ledger ledger = ledgers.requireLedger(code);
        ledgers.upsertMaterial(ledger.id(), body.code(), body.name(), body.baseUnit());
        Material material = ledgers.requireMaterial(ledger.id(), body.code());
        return new MaterialView(material.code(), material.name(), material.baseUnit());
    }

    @PostMapping("/{code}/aliases")
    @Operation(summary = "Add an effective-dated source item code mapping",
            description = "Mappings are never edited. To rename an item, add a new mapping with a later "
                    + "validFrom and set validTo on the old one; documents are always resolved by their "
                    + "own business date.")
    public AliasView addAlias(@PathVariable String code, @Valid @RequestBody UpsertAlias body) {
        Ledger ledger = ledgers.requireLedger(code);
        Material material = ledgers.requireMaterial(ledger.id(), body.material());
        validateWindow(body.validFrom(), body.validTo());
        Alias alias = ledgers.addAlias(ledger.id(), body.sourceSystem(), body.sourceItemCode(), material.id(),
                body.validFrom(), body.validTo(), body.note());
        return new AliasView(alias.id(), alias.sourceSystem(), alias.sourceItemCode(), material.code(),
                alias.validFrom(), alias.validTo());
    }

    @PostMapping("/{code}/conversions")
    @Operation(summary = "Add an effective-dated unit conversion for a material")
    public ConversionView addConversion(@PathVariable String code, @Valid @RequestBody UpsertConversion body) {
        Ledger ledger = ledgers.requireLedger(code);
        Material material = ledgers.requireMaterial(ledger.id(), body.material());
        validateWindow(body.validFrom(), body.validTo());
        Conversion conversion = ledgers.addConversion(ledger.id(), material.id(), body.fromUnit().toUpperCase(),
                body.factor(), body.validFrom(), body.validTo());
        return new ConversionView(conversion.id(), material.code(), conversion.fromUnit(), conversion.factor(),
                conversion.validFrom(), conversion.validTo());
    }

    @PostMapping("/{code}/processing-relations")
    @Operation(summary = "Add an effective-dated processing (BOM) relation: output consumes input")
    public RelationView addRelation(@PathVariable String code, @Valid @RequestBody UpsertRelation body) {
        Ledger ledger = ledgers.requireLedger(code);
        Material output = ledgers.requireMaterial(ledger.id(), body.outputMaterial());
        Material input = ledgers.requireMaterial(ledger.id(), body.inputMaterial());
        if (output.id() == input.id()) {
            throw ApiException.badRequest("INVALID_RELATION", "output and input material must differ");
        }
        validateWindow(body.validFrom(), body.validTo());
        ProcessingRelation relation = ledgers.addRelation(ledger.id(), output.id(), input.id(),
                body.qtyPerOutput(), body.validFrom(), body.validTo(), body.note());
        return new RelationView(relation.id(), output.code(), input.code(), relation.qtyPerOutput(),
                relation.validFrom(), relation.validTo());
    }

    @GetMapping("/{code}/aliases")
    @Operation(summary = "List material aliases")
    public List<AliasView> aliases(@PathVariable String code) {
        Ledger ledger = ledgers.requireLedger(code);
        Map<Long, String> materialCodes = new java.util.HashMap<>();
        ledgers.materials(ledger.id()).forEach(m -> materialCodes.put(m.id(), m.code()));
        return ledgers.allAliases(ledger.id()).stream()
                .map(a -> new AliasView(a.id(), a.sourceSystem(), a.sourceItemCode(),
                        materialCodes.get(a.materialId()), a.validFrom(), a.validTo()))
                .toList();
    }

    private void validateWindow(LocalDate from, LocalDate to) {
        if (to != null && to.isBefore(from)) {
            throw ApiException.badRequest("INVALID_VALIDITY_WINDOW", "validTo must not precede validFrom");
        }
    }

    private LedgerView view(Ledger ledger) {
        Map<Long, String> materialCodes = new java.util.HashMap<>();
        for (Material material : ledgers.materials(ledger.id())) {
            materialCodes.put(material.id(), material.code());
        }
        List<MaterialView> materials = ledgers.materials(ledger.id()).stream()
                .map(m -> new MaterialView(m.code(), m.name(), m.baseUnit())).toList();
        List<AliasView> aliases = ledgers.allAliases(ledger.id()).stream()
                .map(a -> new AliasView(a.id(), a.sourceSystem(), a.sourceItemCode(),
                        materialCodes.get(a.materialId()), a.validFrom(), a.validTo())).toList();
        List<ConversionView> conversions = ledgers.allConversions(ledger.id()).stream()
                .map(c -> new ConversionView(c.id(), materialCodes.get(c.materialId()), c.fromUnit(), c.factor(),
                        c.validFrom(), c.validTo())).toList();
        List<RelationView> relations = ledgers.allRelations(ledger.id()).stream()
                .map(r -> new RelationView(r.id(), materialCodes.get(r.outputMaterialId()),
                        materialCodes.get(r.inputMaterialId()), r.qtyPerOutput(), r.validFrom(), r.validTo())).toList();
        return new LedgerView(ledger.id(), ledger.code(), ledger.name(), ledger.zone(), materials, aliases,
                conversions, relations);
    }
}
