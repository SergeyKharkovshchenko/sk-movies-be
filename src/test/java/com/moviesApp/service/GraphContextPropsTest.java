package com.moviesApp.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GraphContextPropsTest {

    @Test
    void showsDataValuesSortedAndHidesBookkeeping() {
        Map<String, Object> props = Map.of(
                "Loss_Payment", 1000, "Claim", "12312701",
                "sourceLabel", "insurance", "nodeType", "csv_entity", "csvTables", "x", "name", "Claim_Amount-1");
        assertEquals("Claim=12312701, Loss_Payment=1000", KnowledgeService.formatProps(props));
    }

    @Test
    void emptyOrMissingPropsGiveAnEmptyString() {
        assertEquals("", KnowledgeService.formatProps(null));
        assertEquals("", KnowledgeService.formatProps(Map.of("sourceLabel", "x")));
    }

    @Test
    void longValuesAreTruncated() {
        String out = KnowledgeService.formatProps(Map.of("note", "a".repeat(300)));
        assertEquals("note=" + "a".repeat(120) + "...", out);
    }
}
