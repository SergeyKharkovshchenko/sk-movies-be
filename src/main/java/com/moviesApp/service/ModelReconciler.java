package com.moviesApp.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.moviesApp.service.OntologyRules.listOf;
import static com.moviesApp.service.OntologyRules.str;

/**
 * Works out what must change in an already built graph so it matches the approved model (strict
 * ontology): nodes the vocabulary does not contain go, relations the ontology does not allow go, the
 * taxonomy becomes the graph's IS_A edges, and properties nobody declared are removed. Pure: it only
 * compares a snapshot with the model, and the caller applies the plan.
 */
public final class ModelReconciler {

    public record NodeInfo(String name, boolean structural) {}

    public record EdgeInfo(String from, String type, String to, boolean inferred) {}

    public record Snapshot(List<NodeInfo> nodes, List<EdgeInfo> edges, Set<String> nodeProps, Set<String> relProps) {}

    public record EdgeRemoval(EdgeInfo edge, String reason) {}

    public record Plan(List<String> deleteNodes,
                       List<EdgeRemoval> deleteEdges,
                       List<Map<String, String>> addIsA,
                       List<Map<String, Object>> upsertTerms,
                       Set<String> removeNodeProps,
                       Set<String> removeRelProps) {}

    private static final Set<String> KEEP_NODE_PROPS = Set.of(
            "name", "text", "description", "aliases", "definition",
            "sourceLabel", "nodeType", "csvTables", "inferredBy", "inferred", "via", "sectionTitle", "forEntity", "origin");
    private static final Set<String> KEEP_REL_PROPS = Set.of(
            "predicate", "inferred", "via", "evidence", "section", "chunk", "origin", "status");

    private ModelReconciler() {}

    private static String norm(String s) {
        return s.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    public static Plan plan(Snapshot graph, Map<String, Object> vocabulary, Map<String, Object> taxonomy,
                            Map<String, Object> ontology, Map<String, Object> metadata) {
        Map<String, String> index = OntologyRules.termIndex(vocabulary);
        Set<String> nodeNames = new LinkedHashSet<>();
        graph.nodes().forEach(n -> nodeNames.add(norm(n.name())));

        List<String> deleteNodes = new ArrayList<>();
        Set<String> structural = new LinkedHashSet<>();
        for (NodeInfo n : graph.nodes()) {
            if (n.structural()) structural.add(n.name());
            else if (!index.containsKey(norm(n.name()))) deleteNodes.add(n.name());
        }
        Set<String> deleted = new LinkedHashSet<>(deleteNodes);

        // The taxonomy as (child, parent) pairs of preferred terms.
        Set<String> wanted = new LinkedHashSet<>();
        List<Map<String, String>> wantedPairs = new ArrayList<>();
        for (Map<String, Object> p : listOf(taxonomy, "pairs")) {
            String parent = index.get(norm(str(p, "parent"))), child = index.get(norm(str(p, "child")));
            if (parent == null || child == null) continue;
            if (wanted.add(norm(child) + ">" + norm(parent))) wantedPairs.add(Map.of("child", child, "parent", parent));
        }

        List<EdgeRemoval> deleteEdges = new ArrayList<>();
        Set<String> haveIsA = new LinkedHashSet<>();
        for (EdgeInfo e : graph.edges()) {
            if (e.inferred()) continue; // derived edges are rebuilt from the taxonomy afterwards
            if (structural.contains(e.from()) || structural.contains(e.to())) continue;
            if (deleted.contains(e.from()) || deleted.contains(e.to())) continue; // goes with its node
            if ("IS_A".equals(e.type()) || "PARENT_OF".equals(e.type())) {
                boolean up = "IS_A".equals(e.type());
                String child = up ? e.from() : e.to(), parent = up ? e.to() : e.from();
                String key = norm(child) + ">" + norm(parent);
                if (wanted.contains(key)) haveIsA.add(key);
                else deleteEdges.add(new EdgeRemoval(e, "not in the approved taxonomy"));
                continue;
            }
            String why = OntologyRules.checkTriple(e.from(), e.type(), e.to(), vocabulary, ontology);
            if (why != null) deleteEdges.add(new EdgeRemoval(e, why));
        }

        List<Map<String, String>> addIsA = new ArrayList<>();
        for (Map<String, String> pair : wantedPairs) {
            if (!haveIsA.contains(norm(pair.get("child")) + ">" + norm(pair.get("parent")))) addIsA.add(pair);
        }

        Set<String> keepNode = new LinkedHashSet<>(KEEP_NODE_PROPS);
        listOf(metadata, "entityAttributes").forEach(a -> keepNode.add(str(a, "name")));
        Set<String> keepRel = new LinkedHashSet<>(KEEP_REL_PROPS);
        listOf(metadata, "relationAttributes").forEach(a -> keepRel.add(str(a, "name")));
        Set<String> removeNode = new LinkedHashSet<>(graph.nodeProps());
        removeNode.removeAll(keepNode);
        Set<String> removeRel = new LinkedHashSet<>(graph.relProps());
        removeRel.removeAll(keepRel);

        List<Map<String, Object>> upserts = new ArrayList<>();
        Map<String, String> types = OntologyRules.termTypes(vocabulary);
        for (Map<String, Object> t : listOf(vocabulary, "terms")) {
            String term = str(t, "term");
            if (term.isEmpty()) continue;
            Map<String, Object> u = new LinkedHashMap<>();
            u.put("term", term);
            u.put("type", types.getOrDefault(term, ""));
            u.put("aliases", OntologyRules.strings(t.get("aliases")));
            u.put("definition", str(t, "definition"));
            u.put("existed", nodeNames.contains(norm(term)));
            upserts.add(u);
        }
        return new Plan(deleteNodes, deleteEdges, addIsA, upserts, removeNode, removeRel);
    }
}
