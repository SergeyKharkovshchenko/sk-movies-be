package com.moviesApp.service;

import com.moviesApp.service.ModelReconciler.EdgeInfo;
import com.moviesApp.service.ModelReconciler.NodeInfo;
import com.moviesApp.service.ModelReconciler.Plan;
import com.moviesApp.service.ModelReconciler.Snapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelReconcilerTest {

    private static Map<String, Object> term(String t, String type) {
        return Map.of("term", t, "type", type, "aliases", List.of());
    }

    private static final Map<String, Object> VOCAB = Map.of("terms", List.of(
            term("Tiramisu", "Dish"), term("mascarpone", "Ingredient"), term("dairy", "Ingredient"),
            term("Italian", "Cuisine")));
    private static final Map<String, Object> ONTOLOGY = Map.of(
            "classes", List.of(Map.of("name", "Dish"), Map.of("name", "Ingredient"), Map.of("name", "Cuisine")),
            "relations", List.of(
                    Map.of("name", "INCLUDES_INGREDIENT", "domain", "Dish", "range", "Ingredient"),
                    Map.of("name", "OF_CUISINE", "domain", "Dish", "range", "Cuisine")));
    private static final Map<String, Object> TAXONOMY = Map.of("pairs", List.of(
            Map.of("parent", "dairy", "child", "mascarpone")));
    private static final Map<String, Object> METADATA = Map.of(
            "entityAttributes", List.of(Map.of("name", "price_eur", "datatype", "number")),
            "relationAttributes", List.of());

    private static Snapshot graph(List<NodeInfo> nodes, List<EdgeInfo> edges, Set<String> nodeProps) {
        return new Snapshot(nodes, edges, nodeProps, Set.of());
    }

    @Test
    void nodesOutsideTheVocabularyAreRemovedButStructuralOnesStay() {
        Snapshot g = graph(List.of(
                new NodeInfo("Tiramisu", false), new NodeInfo("Pad Thai", false), new NodeInfo("Intro", true)),
                List.of(), Set.of());
        Plan plan = ModelReconciler.plan(g, VOCAB, TAXONOMY, ONTOLOGY, METADATA);
        assertEquals(List.of("Pad Thai"), plan.deleteNodes());
    }

    @Test
    void relationsTheOntologyDoesNotAllowAreRemovedWithTheReason() {
        Snapshot g = graph(
                List.of(new NodeInfo("Tiramisu", false), new NodeInfo("mascarpone", false), new NodeInfo("Italian", false)),
                List.of(new EdgeInfo("Tiramisu", "INCLUDES_INGREDIENT", "mascarpone", false),
                        new EdgeInfo("Tiramisu", "LIKES", "Italian", false),
                        new EdgeInfo("mascarpone", "OF_CUISINE", "Italian", false)),
                Set.of());
        Plan plan = ModelReconciler.plan(g, VOCAB, TAXONOMY, ONTOLOGY, METADATA);
        assertEquals(2, plan.deleteEdges().size());
        assertTrue(plan.deleteEdges().get(0).reason().contains("LIKES is not in the ontology"));
        assertTrue(plan.deleteEdges().get(1).reason().contains("does not allow Ingredient -> Cuisine"));
    }

    @Test
    void theTaxonomyBecomesTheGraphsIsAEdges() {
        Snapshot g = graph(
                List.of(new NodeInfo("Tiramisu", false), new NodeInfo("mascarpone", false), new NodeInfo("dairy", false)),
                List.of(new EdgeInfo("Tiramisu", "IS_A", "dairy", false)), Set.of());
        Plan plan = ModelReconciler.plan(g, VOCAB, TAXONOMY, ONTOLOGY, METADATA);
        assertEquals("not in the approved taxonomy", plan.deleteEdges().get(0).reason());
        assertEquals(List.of(Map.of("child", "mascarpone", "parent", "dairy")), plan.addIsA());
    }

    @Test
    void anIsAEdgeTheTaxonomyWantsIsKeptNotRecreated() {
        Snapshot g = graph(
                List.of(new NodeInfo("mascarpone", false), new NodeInfo("dairy", false)),
                List.of(new EdgeInfo("mascarpone", "IS_A", "dairy", false)), Set.of());
        Plan plan = ModelReconciler.plan(g, VOCAB, TAXONOMY, ONTOLOGY, METADATA);
        assertTrue(plan.deleteEdges().isEmpty());
        assertTrue(plan.addIsA().isEmpty());
    }

    @Test
    void derivedEdgesAndEdgesOfDeletedNodesAreLeftToTheRebuild() {
        Snapshot g = graph(
                List.of(new NodeInfo("Tiramisu", false), new NodeInfo("Ghost", false)),
                List.of(new EdgeInfo("Tiramisu", "INCLUDES_INGREDIENT_CATEGORY", "dairy", true),
                        new EdgeInfo("Ghost", "WHATEVER", "Tiramisu", false)),
                Set.of());
        Plan plan = ModelReconciler.plan(g, VOCAB, TAXONOMY, ONTOLOGY, METADATA);
        assertEquals(List.of("Ghost"), plan.deleteNodes());
        assertTrue(plan.deleteEdges().isEmpty());
    }

    @Test
    void onlyDeclaredPropertiesSurvive() {
        Snapshot g = new Snapshot(List.of(), List.of(), Set.of("price_eur", "calories", "name", "sourceLabel", "definition"),
                Set.of("predicate", "weight"));
        Plan plan = ModelReconciler.plan(g, VOCAB, TAXONOMY, ONTOLOGY, METADATA);
        assertEquals(Set.of("calories"), plan.removeNodeProps());
        assertEquals(Set.of("weight"), plan.removeRelProps());
    }

    @Test
    void everyVocabularyTermIsUpsertedAndNewOnesAreMarked() {
        Snapshot g = graph(List.of(new NodeInfo("Tiramisu", false)), List.of(), Set.of());
        Plan plan = ModelReconciler.plan(g, VOCAB, TAXONOMY, ONTOLOGY, METADATA);
        assertEquals(4, plan.upsertTerms().size());
        assertEquals(true, plan.upsertTerms().get(0).get("existed"));
        assertEquals(false, plan.upsertTerms().get(1).get("existed"));
    }
}
