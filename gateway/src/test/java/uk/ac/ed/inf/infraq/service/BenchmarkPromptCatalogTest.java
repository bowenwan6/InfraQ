package uk.ac.ed.inf.infraq.service;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkPromptCatalogTest {

    @Test
    void uniqueWorkloadProducesDistinctPrompts() {
        List<String> prompts = BenchmarkPromptCatalog.buildPrompts("unique", 80);
        assertEquals(80, prompts.size());
        assertEquals(80, new HashSet<>(prompts).size());
    }

    @Test
    void repeatedWorkloadContainsMixOfRepeatedAndFreshPrompts() {
        List<String> prompts = BenchmarkPromptCatalog.buildPrompts("repeated", 40);
        int distinct = new HashSet<>(prompts).size();

        assertEquals(40, prompts.size());
        assertTrue(distinct < 40, "repeated mode should contain duplicate prompts");
        assertTrue(distinct > 8, "repeated mode should still include some fresh prompts");
    }

    @Test
    void invalidModeFallsBackToUnique() {
        assertEquals("unique", BenchmarkPromptCatalog.normalizeMode("not-a-mode"));
        assertEquals("unique", BenchmarkPromptCatalog.normalizeMode(null));
    }
}
