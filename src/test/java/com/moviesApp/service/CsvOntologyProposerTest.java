package com.moviesApp.service;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsvOntologyProposerTest {

    private static final String DISHES = """
            dish,cuisine,price_eur,ingredients
            Tiramisu,Italian,6.5,mascarpone;coffee
            Pad Thai,Thai,11,shrimp;peanuts
            Margherita Pizza,Italian,9,mozzarella;tomato
            """;
    private static final String TAXONOMY = """
            child,child_type,parent
            mascarpone,Ingredient,dairy
            mozzarella,Ingredient,dairy
            shrimp,Ingredient,shellfish
            shellfish,Ingredient,seafood
            """;

    private static List<Map<String, String>> files(String... namesAndContents) {
        List<Map<String, String>> out = new java.util.ArrayList<>();
        for (int i = 0; i < namesAndContents.length; i += 2) {
            out.add(Map.of("name", namesAndContents[i], "content", namesAndContents[i + 1].stripIndent()));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> payload, String key) {
        return (List<Map<String, Object>>) payload.get(key);
    }

    @Test
    void plainCsvsYieldTermsTaxonomyAndRelations() {
        Map<String, Map<String, Object>> p = CsvOntologyProposer.propose(
                files("dishes.csv", DISHES, "taxonomy.csv", TAXONOMY));

        List<String> terms = list(p.get("vocabulary"), "terms").stream().map(t -> (String) t.get("term")).toList();
        assertTrue(terms.containsAll(List.of("Tiramisu", "Pad Thai", "mascarpone", "dairy", "seafood", "Italian")));

        List<Map<String, Object>> pairs = list(p.get("taxonomy"), "pairs");
        assertTrue(pairs.stream().anyMatch(x -> "dairy".equals(x.get("parent")) && "mascarpone".equals(x.get("child"))));
        assertTrue(pairs.stream().anyMatch(x -> "seafood".equals(x.get("parent")) && "shellfish".equals(x.get("child"))));

        List<String> relations = list(p.get("ontology"), "relations").stream().map(r -> (String) r.get("name")).toList();
        assertTrue(relations.contains("INCLUDES_INGREDIENT"), relations.toString());
        assertTrue(relations.contains("OF_CUISINE"), relations.toString());

        Map<String, Object> price = list(p.get("metadata"), "entityAttributes").stream()
                .filter(a -> "price_eur".equals(a.get("name"))).findFirst().orElseThrow();
        assertEquals("number", price.get("datatype"));
    }

    @Test
    void everyProposedStagePassesTheApprovalRules() {
        Map<String, Map<String, Object>> p = CsvOntologyProposer.propose(
                files("dishes.csv", DISHES, "taxonomy.csv", TAXONOMY));
        Map<OntologyStage, Map<String, Object>> approved = new EnumMap<>(OntologyStage.class);
        approved.put(OntologyStage.VOCABULARY, p.get("vocabulary"));

        assertEquals(List.of(), OntologyRules.validate(OntologyStage.VOCABULARY, p.get("vocabulary"), approved));
        assertEquals(List.of(), OntologyRules.validate(OntologyStage.METADATA, p.get("metadata"), approved));
        assertEquals(List.of(), OntologyRules.validate(OntologyStage.TAXONOMY, p.get("taxonomy"), approved));
        assertEquals(List.of(), OntologyRules.validate(OntologyStage.ONTOLOGY, p.get("ontology"), approved));
    }

    @Test
    void identifierConventionTablesBecomeClassesAndForeignKeyRelations() {
        String claim = """
                Claim_Identifier,Policy_Identifier,Claim_Status
                12312701,P1,open
                """;
        String payment = """
                Loss_Payment_Identifier,Claim_Identifier,Amount
                LP1,12312701,1000
                """;
        Map<String, Map<String, Object>> p = CsvOntologyProposer.propose(files("Claim.csv", claim, "Loss_Payment.csv", payment));

        List<String> terms = list(p.get("vocabulary"), "terms").stream().map(t -> (String) t.get("term")).toList();
        assertTrue(terms.contains("Claim-12312701"));
        assertTrue(terms.contains("Policy-P1"));
        assertTrue(terms.contains("Loss_Payment-LP1"));

        List<Map<String, Object>> relations = list(p.get("ontology"), "relations");
        assertTrue(relations.stream().anyMatch(r -> "HAS_POLICY".equals(r.get("name"))
                && "Claim".equals(r.get("domain")) && "Policy".equals(r.get("range"))));
        assertTrue(relations.stream().anyMatch(r -> "HAS_CLAIM".equals(r.get("name"))
                && "Loss_Payment".equals(r.get("domain"))));
        Map<String, Object> amount = list(p.get("metadata"), "entityAttributes").stream()
                .filter(a -> "Amount".equals(a.get("name"))).findFirst().orElseThrow();
        assertEquals("number", amount.get("datatype"));
    }

    @Test
    void emptyInputGivesEmptyStages() {
        Map<String, Map<String, Object>> p = CsvOntologyProposer.propose(List.of());
        assertTrue(list(p.get("vocabulary"), "terms").isEmpty());
        assertTrue(list(p.get("ontology"), "classes").isEmpty());
    }
}
