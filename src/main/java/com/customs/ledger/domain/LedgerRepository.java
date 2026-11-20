package com.customs.ledger.domain;

import com.customs.ledger.common.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public class LedgerRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public LedgerRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public Ledger createLedger(String code, String name, String zone) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO ledger (code, name, zone) VALUES (:code, :name, :zone)")
                .param("code", code).param("name", name).param("zone", zone)
                .update(keys, "id");
        return findLedger(code).orElseThrow();
    }

    public List<Ledger> allLedgers() {
        return jdbc.sql("SELECT id, code, name, zone FROM ledger ORDER BY code")
                .query((rs, i) -> new Ledger(rs.getLong("id"), rs.getString("code"), rs.getString("name"),
                        rs.getString("zone"))).list();
    }

    public void upsertMaterial(long ledgerId, String code, String name, String baseUnit) {
        jdbc.sql("""
                INSERT INTO material (ledger_id, code, name, base_unit) VALUES (:l, :c, :n, :u)
                ON CONFLICT (ledger_id, code) DO UPDATE
                SET name = EXCLUDED.name, base_unit = EXCLUDED.base_unit
                WHERE material.base_unit = EXCLUDED.base_unit
                """)
                .param("l", ledgerId).param("c", code).param("n", name).param("u", baseUnit).update();
        Material stored = requireMaterial(ledgerId, code);
        if (!stored.baseUnit().equals(baseUnit)) {
            throw ApiException.conflict("BASE_UNIT_IMMUTABLE",
                    "material " + code + " already uses base unit " + stored.baseUnit()
                            + "; changing it would silently rewrite historical quantities, add a new material instead");
        }
    }

    public Optional<Ledger> findLedger(String code) {
        return jdbc.sql("SELECT id, code, name, zone FROM ledger WHERE code = :code")
                .param("code", code)
                .query((rs, i) -> new Ledger(rs.getLong("id"), rs.getString("code"), rs.getString("name"), rs.getString("zone")))
                .optional();
    }

    public Ledger requireLedger(String code) {
        return findLedger(code).orElseThrow(() -> ApiException.notFound("LEDGER_NOT_FOUND", "unknown ledger " + code));
    }

    public Ledger requireLedger(long id) {
        return jdbc.sql("SELECT id, code, name, zone FROM ledger WHERE id = :id")
                .param("id", id)
                .query((rs, i) -> new Ledger(rs.getLong("id"), rs.getString("code"), rs.getString("name"), rs.getString("zone")))
                .optional().orElseThrow(() -> ApiException.notFound("LEDGER_NOT_FOUND", "unknown ledger id " + id));
    }

    // ---------------------------------------------------------------- materials

    public Material createMaterial(long ledgerId, String code, String name, String baseUnit) {
        jdbc.sql("INSERT INTO material (ledger_id, code, name, base_unit) VALUES (:l, :c, :n, :u)")
                .param("l", ledgerId).param("c", code).param("n", name).param("u", baseUnit).update();
        return findMaterial(ledgerId, code).orElseThrow();
    }

    public Optional<Material> findMaterial(long ledgerId, String code) {
        return jdbc.sql("SELECT id, code, name, base_unit FROM material WHERE ledger_id = :l AND code = :c")
                .param("l", ledgerId).param("c", code).query(MATERIAL).optional();
    }

    public Material requireMaterial(long ledgerId, String code) {
        return findMaterial(ledgerId, code)
                .orElseThrow(() -> ApiException.badRequest("MATERIAL_NOT_FOUND", "unknown material " + code));
    }

    public Optional<Material> findMaterialById(long materialId) {
        return jdbc.sql("SELECT id, code, name, base_unit FROM material WHERE id = :id")
                .param("id", materialId).query(MATERIAL).optional();
    }

    public Material requireMaterialById(long materialId) {
        return findMaterialById(materialId)
                .orElseThrow(() -> ApiException.notFound("MATERIAL_NOT_FOUND", "unknown material id " + materialId));
    }

    public List<Material> materials(long ledgerId) {
        return jdbc.sql("SELECT id, code, name, base_unit FROM material WHERE ledger_id = :l ORDER BY code")
                .param("l", ledgerId).query(MATERIAL).list();
    }

    private static final RowMapper<Material> MATERIAL = (rs, i) ->
            new Material(rs.getLong("id"), rs.getString("code"), rs.getString("name"), rs.getString("base_unit"));

    // ---------------------------------------------------------------- aliases

    public Alias addAlias(long ledgerId, String sourceSystem, String sourceItemCode, long materialId,
                          LocalDate from, LocalDate to, String note) {
        jdbc.sql("""
                INSERT INTO material_alias (ledger_id, source_system, source_item_code, material_id, valid_from, valid_to, note)
                VALUES (:l, :s, :c, :m, :f, :t, :n)
                """)
                .param("l", ledgerId).param("s", sourceSystem).param("c", sourceItemCode).param("m", materialId)
                .param("f", from).param("t", to).param("n", note).update();
        return jdbc.sql("SELECT id, source_system, source_item_code, material_id, valid_from, valid_to FROM material_alias WHERE ledger_id = :l ORDER BY id DESC LIMIT 1")
                .param("l", ledgerId).query(ALIAS).single();
    }

    /** All aliases whose validity window contains the given date. Source-specific mappings win ties. */
    public List<Alias> aliasesEffectiveOn(long ledgerId, String sourceItemCode, LocalDate date) {
        return jdbc.sql("""
                SELECT id, source_system, source_item_code, material_id, valid_from, valid_to
                FROM material_alias
                WHERE ledger_id = :l AND source_item_code = :c
                  AND valid_from <= :d AND (valid_to IS NULL OR valid_to >= :d)
                ORDER BY (source_system IS NOT NULL) DESC, valid_from DESC, id DESC
                """)
                .param("l", ledgerId).param("c", sourceItemCode).param("d", date)
                .query(ALIAS).list();
    }

    public List<Alias> allAliases(long ledgerId) {
        return jdbc.sql("""
                SELECT id, source_system, source_item_code, material_id, valid_from, valid_to
                FROM material_alias WHERE ledger_id = :l ORDER BY source_item_code, valid_from
                """).param("l", ledgerId).query(ALIAS).list();
    }

    private static final RowMapper<Alias> ALIAS = (rs, i) -> new Alias(
            rs.getLong("id"), rs.getString("source_system"), rs.getString("source_item_code"),
            rs.getLong("material_id"), rs.getObject("valid_from", LocalDate.class),
            rs.getObject("valid_to", LocalDate.class));

    // ---------------------------------------------------------------- conversions

    public Conversion addConversion(long ledgerId, long materialId, String fromUnit, BigDecimal factor,
                                    LocalDate from, LocalDate to) {
        jdbc.sql("""
                INSERT INTO unit_conversion (ledger_id, material_id, from_unit, factor, valid_from, valid_to)
                VALUES (:l, :m, :u, :f, :vf, :vt)
                """)
                .param("l", ledgerId).param("m", materialId).param("u", fromUnit).param("f", factor)
                .param("vf", from).param("vt", to).update();
        return jdbc.sql("SELECT id, material_id, from_unit, factor, valid_from, valid_to FROM unit_conversion WHERE ledger_id = :l ORDER BY id DESC LIMIT 1")
                .param("l", ledgerId).query(CONVERSION).single();
    }

    public List<Conversion> conversionsFor(long ledgerId, long materialId, String fromUnit, LocalDate date) {
        return jdbc.sql("""
                SELECT id, material_id, from_unit, factor, valid_from, valid_to
                FROM unit_conversion
                WHERE ledger_id = :l AND material_id = :m AND from_unit = :u
                  AND valid_from <= :d AND (valid_to IS NULL OR valid_to >= :d)
                ORDER BY valid_from DESC, id DESC
                """)
                .param("l", ledgerId).param("m", materialId).param("u", fromUnit).param("d", date)
                .query(CONVERSION).list();
    }

    public List<Conversion> allConversions(long ledgerId) {
        return jdbc.sql("""
                SELECT id, material_id, from_unit, factor, valid_from, valid_to
                FROM unit_conversion WHERE ledger_id = :l ORDER BY material_id, from_unit, valid_from
                """).param("l", ledgerId).query(CONVERSION).list();
    }

    private static final RowMapper<Conversion> CONVERSION = (rs, i) -> new Conversion(
            rs.getLong("id"), rs.getLong("material_id"), rs.getString("from_unit"), rs.getBigDecimal("factor"),
            rs.getObject("valid_from", LocalDate.class), rs.getObject("valid_to", LocalDate.class));

    // ---------------------------------------------------------------- processing relations

    public ProcessingRelation addRelation(long ledgerId, long outputMaterialId, long inputMaterialId,
                                          BigDecimal qtyPerOutput, LocalDate from, LocalDate to, String note) {
        jdbc.sql("""
                INSERT INTO processing_relation (ledger_id, output_material_id, input_material_id, qty_per_output, valid_from, valid_to, note)
                VALUES (:l, :o, :i, :q, :f, :t, :n)
                """)
                .param("l", ledgerId).param("o", outputMaterialId).param("i", inputMaterialId)
                .param("q", qtyPerOutput).param("f", from).param("t", to).param("n", note).update();
        return jdbc.sql("SELECT id, output_material_id, input_material_id, qty_per_output, valid_from, valid_to FROM processing_relation WHERE ledger_id = :l ORDER BY id DESC LIMIT 1")
                .param("l", ledgerId).query(RELATION).single();
    }

    public List<ProcessingRelation> relationsFor(long ledgerId, long outputMaterialId, LocalDate date) {
        return jdbc.sql("""
                SELECT id, output_material_id, input_material_id, qty_per_output, valid_from, valid_to
                FROM processing_relation
                WHERE ledger_id = :l AND output_material_id = :o
                  AND valid_from <= :d AND (valid_to IS NULL OR valid_to >= :d)
                ORDER BY valid_from DESC, id DESC
                """)
                .param("l", ledgerId).param("o", outputMaterialId).param("d", date)
                .query(RELATION).list();
    }

    public List<ProcessingRelation> allRelations(long ledgerId) {
        return jdbc.sql("""
                SELECT id, output_material_id, input_material_id, qty_per_output, valid_from, valid_to
                FROM processing_relation WHERE ledger_id = :l ORDER BY output_material_id, valid_from
                """).param("l", ledgerId).query(RELATION).list();
    }

    private static final RowMapper<ProcessingRelation> RELATION = (rs, i) -> new ProcessingRelation(
            rs.getLong("id"), rs.getLong("output_material_id"), rs.getLong("input_material_id"),
            rs.getBigDecimal("qty_per_output"), rs.getObject("valid_from", LocalDate.class),
            rs.getObject("valid_to", LocalDate.class));
}
