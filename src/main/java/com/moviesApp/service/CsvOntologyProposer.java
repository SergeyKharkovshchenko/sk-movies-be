package com.moviesApp.service;

import com.moviesApp.service.KnowledgeService.CsvColumnRole;
import com.moviesApp.service.KnowledgeService.CsvTable;
import com.moviesApp.service.KnowledgeService.CsvTableProfile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Derives every stage of the ontology pipeline from CSV tables with no LLM: vocabulary (row keys and
 * the values they reference), metadata (typed columns), taxonomy (hierarchy columns) and ontology
 * (classes and the relations the columns imply). It mirrors the column-role rules the CSV importer
 * uses, so what the user reviews is what the importer would have built. Pure and deterministic.
 */
final class CsvOntologyProposer {

    private CsvOntologyProposer() {}

    /** Accumulates a proposal while the tables are walked. */
    private static final class Draft {
        final Map<String, Map<String, Object>> terms = new LinkedHashMap<>(); // lower-cased name -> term
        final List<Map<String, Object>> pairs = new ArrayList<>();
        final Set<String> classes = new LinkedHashSet<>();
        final Map<String, String> classParent = new LinkedHashMap<>();
        final Map<String, Map<String, Object>> relations = new LinkedHashMap<>();
        final Map<String, Map<String, Object>> entityAttributes = new LinkedHashMap<>();
        final Map<String, Map<String, Object>> relationAttributes = new LinkedHashMap<>();

        void term(String name, String type) {
            String key = KnowledgeService.csvKey(name);
            terms.computeIfAbsent(key, k -> {
                classes.add(type);
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("term", name.strip());
                t.put("type", type);
                t.put("aliases", List.of());
                t.put("definition", "");
                return t;
            });
        }

        String typeOf(String name) {
            Map<String, Object> t = terms.get(KnowledgeService.csvKey(name));
            return t == null ? null : String.valueOf(t.get("type"));
        }

        void relation(String name, String domain, String range) {
            classes.add(domain);
            classes.add(range);
            relations.putIfAbsent(name + "|" + domain + "|" + range, relationMap(name, domain, range));
        }

        void pair(String parent, String child) {
            pairs.add(Map.of("parent", parent.strip(), "child", child.strip(), "evidence", ""));
        }

        void attribute(Map<String, Map<String, Object>> into, String name, boolean numeric) {
            into.merge(name, attributeMap(name, numeric ? "number" : "string"), (old, nu) ->
                    "number".equals(old.get("datatype")) && !numeric ? attributeMap(name, "string") : old);
        }
    }

    private static Map<String, Object> relationMap(String name, String domain, String range) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("name", name);
        r.put("domain", domain);
        r.put("range", range);
        r.put("description", "");
        return r;
    }

    private static Map<String, Object> attributeMap(String name, String datatype) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("name", name);
        a.put("datatype", datatype);
        a.put("required", false);
        return a;
    }

    /** One map per stage: vocabulary, metadata, taxonomy, ontology (payload shapes of {@link OntologyRules}). */
    static Map<String, Map<String, Object>> propose(List<Map<String, String>> files) {
        List<CsvTable> tables = new ArrayList<>();
        for (Map<String, String> file : files) {
            String name = file.get("name"), content = file.get("content");
            if (name == null || content == null || content.isBlank()) continue;
            List<Map<String, String>> rows = KnowledgeService.parseCsv(content);
            if (rows.isEmpty()) continue;
            tables.add(new CsvTable(name.replaceAll("(?i)\\.csv$", ""), new ArrayList<>(rows.get(0).keySet()), rows));
        }
        Draft d = new Draft();
        boolean convention = tables.stream()
                .anyMatch(t -> t.headers().stream().anyMatch(h -> h.endsWith("_Identifier")));
        if (convention) conventionTables(tables, d);
        else inferredTables(tables, d);
        return result(d);
    }

    // ── plain CSVs: roles come from column names and values ─────────────────────

    private static void inferredTables(List<CsvTable> tables, Draft d) {
        List<CsvTableProfile> profiles = tables.stream().map(KnowledgeService::profileCsvTable).toList();

        // Row keys first, so a column can be recognised as a reference to another file's keys.
        Map<String, String> rowTypes = new LinkedHashMap<>();
        for (CsvTableProfile p : profiles) {
            for (Map<String, String> row : p.table().rows()) {
                String key = row.get(p.keyCol());
                if (key != null && !key.isBlank()) rowTypes.put(KnowledgeService.csvKey(key), KnowledgeService.rowType(p, row));
            }
        }
        for (CsvTableProfile p : profiles) {
            p.roles().replaceAll((col, role) -> {
                if (role != CsvColumnRole.PROPERTY) return role;
                Set<String> distinct = KnowledgeService.distinctValues(p.table(), col);
                long known = distinct.stream().filter(v -> rowTypes.containsKey(KnowledgeService.csvKey(v))).count();
                return !distinct.isEmpty() && known * 2 >= distinct.size() ? CsvColumnRole.REFERENCE : role;
            });
        }

        for (CsvTableProfile p : profiles) {
            for (Map<String, String> row : p.table().rows()) {
                String key = row.get(p.keyCol());
                if (key == null || key.isBlank()) continue;
                String type = KnowledgeService.rowType(p, row);
                d.term(key, type);
                for (Map.Entry<String, CsvColumnRole> col : p.roles().entrySet()) {
                    String raw = row.get(col.getKey());
                    if (raw == null || raw.isBlank()) continue;
                    String value = raw.trim(), column = col.getKey();
                    switch (col.getValue()) {
                        case NUMBER -> d.attribute(d.entityAttributes, column, true);
                        case PROPERTY -> d.attribute(d.entityAttributes, column, false);
                        case HIERARCHY -> {
                            String parentType = KnowledgeService.dominantKnownType(p.table(), column, rowTypes);
                            d.term(value, rowTypes.getOrDefault(KnowledgeService.csvKey(value), parentType));
                            d.pair(value, key);
                        }
                        case LIST -> {
                            String itemType = KnowledgeService.csvPascal(KnowledgeService.csvSingular(column));
                            for (String item : KnowledgeService.CSV_LIST_SEPARATOR.split(value)) {
                                if (item.isBlank()) continue;
                                d.term(item, rowTypes.getOrDefault(KnowledgeService.csvKey(item), itemType));
                                d.relation("INCLUDES_" + KnowledgeService.csvRelName(KnowledgeService.csvSingular(column)),
                                        type, d.typeOf(item));
                            }
                        }
                        case REFERENCE -> {
                            String targetType = KnowledgeService.csvPascal(KnowledgeService.csvSingular(column));
                            d.term(value, rowTypes.getOrDefault(KnowledgeService.csvKey(value), targetType));
                            d.relation("OF_" + KnowledgeService.csvRelName(column), type, d.typeOf(value));
                        }
                        case CATEGORY -> {
                            d.term(value, KnowledgeService.csvPascal(column));
                            d.relation("OF_" + KnowledgeService.csvRelName(column), type, d.typeOf(value));
                        }
                    }
                }
            }
        }
    }

    // ── convention batches: "<Table>_Identifier" columns are keys and foreign keys ──

    private static void conventionTables(List<CsvTable> tables, Draft d) {
        for (CsvTable table : tables) {
            String tableLabel = KnowledgeService.sanitizeNeo4jName(table.name());
            List<String> idCols = table.headers().stream().filter(h -> h.endsWith("_Identifier")).toList();
            String ownPk = idCols.stream().filter(h -> h.equalsIgnoreCase(table.name() + "_Identifier"))
                    .findFirst().orElse(null);

            if (ownPk != null) {
                List<String> fkCols = idCols.stream().filter(c -> !c.equals(ownPk)).toList();
                for (Map<String, String> row : table.rows()) {
                    String id = row.get(ownPk);
                    if (id == null || id.isBlank()) continue;
                    d.term(tableLabel + "-" + id, tableLabel);
                    for (String fk : fkCols) {
                        String value = row.get(fk);
                        if (value == null || value.isBlank()) continue;
                        String target = KnowledgeService.sanitizeNeo4jName(KnowledgeService.stripIdentifierSuffix(fk));
                        d.term(target + "-" + value, target);
                        d.relation("HAS_" + target.toUpperCase(), tableLabel, target);
                    }
                }
                attributes(table, idCols, d.entityAttributes, d);
            } else if (idCols.size() >= 2) {
                String from = KnowledgeService.sanitizeNeo4jName(KnowledgeService.stripIdentifierSuffix(idCols.get(0)));
                String to = KnowledgeService.sanitizeNeo4jName(KnowledgeService.stripIdentifierSuffix(idCols.get(1)));
                for (Map<String, String> row : table.rows()) {
                    String a = row.get(idCols.get(0)), b = row.get(idCols.get(1));
                    if (a == null || a.isBlank() || b == null || b.isBlank()) continue;
                    d.term(from + "-" + a, from);
                    d.term(to + "-" + b, to);
                    d.relation(tableLabel.toUpperCase(), from, to);
                }
                attributes(table, idCols.subList(0, 2), d.relationAttributes, d);
            } else if (idCols.size() == 1) {
                // A tag table marks existing nodes as one more kind of thing: a subclass of the target.
                String target = KnowledgeService.sanitizeNeo4jName(KnowledgeService.stripIdentifierSuffix(idCols.get(0)));
                d.classes.add(target);
                d.classes.add(tableLabel);
                d.classParent.put(tableLabel, target);
                for (Map<String, String> row : table.rows()) {
                    String value = row.get(idCols.get(0));
                    if (value != null && !value.isBlank()) d.term(target + "-" + value, target);
                }
                attributes(table, idCols, d.entityAttributes, d);
            } else {
                // Small lookup table keyed by its first column.
                String key = table.headers().get(0);
                for (Map<String, String> row : table.rows()) {
                    String value = row.get(key);
                    if (value != null && !value.isBlank()) d.term(value, tableLabel);
                }
                attributes(table, List.of(key), d.entityAttributes, d);
            }
        }
    }

    private static void attributes(CsvTable table, List<String> skip, Map<String, Map<String, Object>> into, Draft d) {
        for (String col : table.headers()) {
            if (skip.contains(col) || col.endsWith("_Identifier")) continue;
            List<String> values = table.rows().stream().map(r -> r.get(col))
                    .filter(v -> v != null && !v.isBlank()).map(String::trim).toList();
            if (values.isEmpty()) continue;
            d.attribute(into, col, values.stream().allMatch(KnowledgeService::isCsvNumber));
        }
    }

    // ── assembling the stage payloads ───────────────────────────────────────────

    private static Map<String, Map<String, Object>> result(Draft d) {
        Map<String, Object> vocabulary = Map.of("terms", List.copyOf(d.terms.values()));
        Map<String, Object> metadata = Map.of(
                "entityAttributes", List.copyOf(d.entityAttributes.values()),
                "relationAttributes", List.copyOf(d.relationAttributes.values()));
        Map<String, Object> taxonomy = Map.of("pairs", d.pairs);

        List<Map<String, Object>> classes = new ArrayList<>();
        for (String c : d.classes) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", c);
            m.put("parent", d.classParent.getOrDefault(c, ""));
            m.put("description", "");
            classes.add(m);
        }
        Map<String, Object> ontology = Map.of("classes", classes, "relations", List.copyOf(d.relations.values()));

        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        out.put("vocabulary", vocabulary);
        out.put("metadata", metadata);
        out.put("taxonomy", taxonomy);
        out.put("ontology", ontology);
        return out;
    }
}
