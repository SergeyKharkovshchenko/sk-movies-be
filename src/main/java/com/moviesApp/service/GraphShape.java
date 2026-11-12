package com.moviesApp.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Pure helpers that shape knowledge-graph data for the UI (no database access, so unit-testable). */
final class GraphShape {

    /** Bookkeeping the importers add to nodes; never shown as the node's own data. */
    static final Set<String> HIDDEN_PROPS = Set.of(
            "sourceLabel", "nodeType", "csvTables", "inferredBy", "inferred", "via");
    /** Shown in dedicated fields, so left out of the generic property list. */
    private static final Set<String> NARRATIVE_PROPS = Set.of("name", "text", "description", "aliases", "definition");

    private GraphShape() {}

    /** The node's own data properties, sorted by key, without bookkeeping or narrative fields. */
    static Map<String, Object> dataProps(Map<String, Object> props) {
        Map<String, Object> out = new TreeMap<>();
        if (props == null) return out;
        props.forEach((k, v) -> {
            if (v != null && !HIDDEN_PROPS.contains(k) && !NARRATIVE_PROPS.contains(k)) out.put(k, v);
        });
        return out;
    }

    /** Adds {@code degree} (edges touching the node, in or out) to every node map. */
    static void addDegrees(List<Map<String, Object>> nodes, List<Map<String, Object>> edges) {
        Map<String, Integer> degree = new HashMap<>();
        for (Map<String, Object> e : edges) {
            degree.merge(String.valueOf(e.get("source")), 1, Integer::sum);
            degree.merge(String.valueOf(e.get("target")), 1, Integer::sum);
        }
        for (Map<String, Object> n : nodes) n.put("degree", degree.getOrDefault(String.valueOf(n.get("id")), 0));
    }

    /** Aliases may be stored as a list or as one delimited string; always returns a clean list. */
    static List<String> aliasList(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> l) {
            l.forEach(o -> {
                if (o != null && !String.valueOf(o).isBlank()) out.add(String.valueOf(o).strip());
            });
        } else if (raw instanceof String s) {
            for (String part : s.split("[;|]")) if (!part.isBlank()) out.add(part.strip());
        }
        return out;
    }

    static Map<String, Object> edgeEntry(String type, String other, boolean inferred, List<String> via) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("type", type);
        e.put("node", other);
        e.put("inferred", inferred);
        if (via != null && !via.isEmpty()) e.put("via", via);
        return e;
    }

    static Map<String, Object> details(String name, List<String> labels, Map<String, Object> props,
                                       List<Map<String, Object>> outgoing, List<Map<String, Object>> incoming,
                                       List<String> ancestors, List<String> children) {
        Map<String, Object> p = props == null ? Map.of() : props;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name);
        out.put("labels", labels);
        out.put("nodeType", p.get("nodeType") == null ? "" : String.valueOf(p.get("nodeType")));
        out.put("description", p.get("description") == null ? "" : String.valueOf(p.get("description")));
        out.put("definition", p.get("definition") == null ? "" : String.valueOf(p.get("definition")));
        out.put("aliases", aliasList(p.get("aliases")));
        out.put("properties", dataProps(p));
        out.put("tables", p.get("csvTables") instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of());
        out.put("outgoing", outgoing);
        out.put("incoming", incoming);
        out.put("ancestors", ancestors);
        out.put("children", children);
        out.put("degree", outgoing.size() + incoming.size());
        return out;
    }
}
