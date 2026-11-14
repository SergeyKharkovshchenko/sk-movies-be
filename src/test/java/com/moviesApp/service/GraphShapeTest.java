package com.moviesApp.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphShapeTest {

    private static Map<String, Object> node(String id) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("id", id);
        return n;
    }

    private static Map<String, Object> edge(String s, String t) {
        return new LinkedHashMap<>(Map.of("source", s, "target", t, "type", "REL"));
    }

    @Test
    void degreeCountsEdgesInBothDirections() {
        List<Map<String, Object>> nodes = new ArrayList<>(List.of(node("a"), node("b"), node("c"), node("lonely")));
        GraphShape.addDegrees(nodes, List.of(edge("a", "b"), edge("c", "a")));
        assertEquals(2, nodes.get(0).get("degree"));
        assertEquals(1, nodes.get(1).get("degree"));
        assertEquals(0, nodes.get(3).get("degree"));
    }

    @Test
    void dataPropsKeepsOnlyTheNodesOwnValues() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("Loss_Payment", 1000);
        props.put("sourceLabel", "x");
        props.put("nodeType", "csv_entity");
        props.put("name", "n");
        props.put("description", "d");
        props.put("aliases", "a");
        props.put("empty", null);
        assertEquals(List.of("Loss_Payment"), List.copyOf(GraphShape.dataProps(props).keySet()));
    }

    @Test
    void aliasesAcceptListsAndDelimitedStrings() {
        assertEquals(List.of("Bonaparte", "Boney"), GraphShape.aliasList("Bonaparte; Boney ;"));
        assertEquals(List.of("A", "B"), GraphShape.aliasList(List.of("A", " ", "B")));
        assertTrue(GraphShape.aliasList(null).isEmpty());
    }

    @Test
    void detailsCombineNarrativeDataAndEdges() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("description", "He ruled France.");
        props.put("definition", "Emperor");
        props.put("aliases", "Bonaparte");
        props.put("Born", 1769);
        props.put("csvTables", List.of("people"));
        Map<String, Object> out = GraphShape.details("Napoleon", List.of("Person"), props,
                List.of(GraphShape.edgeEntry("RULED", "France", false, List.of())),
                List.of(GraphShape.edgeEntry("DEFEATED", "Wellington", true, List.of("Waterloo"))),
                List.of("Emperor"), List.of());
        assertEquals("He ruled France.", out.get("description"));
        assertEquals("Emperor", out.get("definition"));
        assertEquals(List.of("Bonaparte"), out.get("aliases"));
        assertEquals(Map.of("Born", 1769), out.get("properties"));
        assertEquals(List.of("people"), out.get("tables"));
        assertEquals(2, out.get("degree"));
        @SuppressWarnings("unchecked")
        Map<String, Object> in = ((List<Map<String, Object>>) out.get("incoming")).get(0);
        assertEquals(List.of("Waterloo"), in.get("via"));
        assertTrue((Boolean) in.get("inferred"));
        @SuppressWarnings("unchecked")
        Map<String, Object> o = ((List<Map<String, Object>>) out.get("outgoing")).get(0);
        assertFalse(o.containsKey("via"));
    }
}
