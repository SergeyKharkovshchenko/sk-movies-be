package com.moviesApp.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InferredCategorySentenceTest {

    @Test
    void namesTheDishTheCategoryAndTheIngredientThatJustifiesIt() {
        assertEquals("Tiramisu includes ingredient dairy (via mascarpone)",
                KnowledgeService.inferredCategorySentence(
                        "Tiramisu", "INCLUDES_INGREDIENT_CATEGORY", "dairy", List.of("mascarpone")));
    }

    @Test
    void listsEveryIngredientAndToleratesNoVia() {
        assertEquals("Pad Thai includes ingredient seafood (via shrimp, fish sauce)",
                KnowledgeService.inferredCategorySentence(
                        "Pad Thai", "INCLUDES_INGREDIENT_CATEGORY", "seafood", List.of("shrimp", "fish sauce")));
        assertEquals("Soup of cuisine italian",
                KnowledgeService.inferredCategorySentence("Soup", "OF_CUISINE_CATEGORY", "italian", List.of()));
    }
}
