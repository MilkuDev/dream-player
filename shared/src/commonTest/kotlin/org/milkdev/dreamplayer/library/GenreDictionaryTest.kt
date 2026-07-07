package org.milkdev.dreamplayer.library

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class GenreDictionaryTest {

    // ── tokenizeAndClean ──────────────────────────────────────────────

    @Test
    fun tokenizeAndCleanReturnsEmptyForNullInput() {
        assertEquals(emptyList(), GenreDictionary.tokenizeAndClean(null))
    }

    @Test
    fun tokenizeAndCleanReturnsEmptyForEmptyString() {
        assertEquals(emptyList(), GenreDictionary.tokenizeAndClean(""))
    }

    @Test
    fun tokenizeAndCleanReturnsEmptyForBlankString() {
        assertEquals(emptyList(), GenreDictionary.tokenizeAndClean("   "))
    }

    @Test
    fun tokenizeAndCleanSplitsByMultipleDelimiters() {
        val input = "Rock,, Metal; Pop / Electronic \\ Synthwave"
        val expected = listOf("rock", "metal", "pop", "electronic", "synthwave")
        assertContentEquals(expected, GenreDictionary.tokenizeAndClean(input))
    }

    @Test
    fun tokenizeAndCleanTrimsAndLowercases() {
        val input = "  rOcK  "
        assertContentEquals(listOf("rock"), GenreDictionary.tokenizeAndClean(input))
    }

    @Test
    fun tokenizeAndCleanDeduplicatesTokens() {
        val input = "Pop / pop / POP"
        assertContentEquals(listOf("pop"), GenreDictionary.tokenizeAndClean(input))
    }

    // ── resolveParentGenres – Priority 1 (Explicit) ────────────────────

    @Test
    fun explicitOverrideMapsShoegazeToRock() {
        assertContentEquals(
            listOf("Rock"),
            GenreDictionary.resolveParentGenres("shoegaze")
        )
    }

    @Test
    fun explicitOverrideMapsJanglePopToRockNotPop() {
        assertContentEquals(
            listOf("Rock"),
            GenreDictionary.resolveParentGenres("jangle pop")
        )
    }

    @Test
    fun explicitOverrideMapsPhonkToHipHop() {
        assertContentEquals(
            listOf("Hip Hop"),
            GenreDictionary.resolveParentGenres("phonk")
        )
    }

    // ── resolveParentGenres – Priority 2 (Root Matching) ──────────────

    @Test
    fun rootMatchResolvesPopToPop() {
        assertContentEquals(
            listOf("Pop"),
            GenreDictionary.resolveParentGenres("pop")
        )
    }

    @Test
    fun rootMatchResolvesMetalToMetal() {
        assertContentEquals(
            listOf("Metal"),
            GenreDictionary.resolveParentGenres("metal")
        )
    }

    @Test
    fun longestRootWinsForKPopVariants() {
        assertContentEquals(listOf("K-Pop"), GenreDictionary.resolveParentGenres("k-pop"))
        assertContentEquals(listOf("K-Pop"), GenreDictionary.resolveParentGenres("kpop"))
        assertContentEquals(listOf("K-Pop"), GenreDictionary.resolveParentGenres("k pop"))
    }

    @Test
    fun longestRootWinsForDeathMetal() {
        assertContentEquals(
            listOf("Death Metal"),
            GenreDictionary.resolveParentGenres("death metal")
        )
    }

    @Test
    fun liveVocalContainsRootVocalAndResolvesToVocalParent() {
        assertContentEquals(
            listOf("Vocal"),
            GenreDictionary.resolveParentGenres("live vocal")
        )
    }

    // ── resolveParentGenres – Priority 3 (Garbage Filter) ─────────────

    @Test
    fun trueGarbageTokensAreFilteredOut() {
        assertEquals(emptyList(), GenreDictionary.resolveParentGenres("podcast"))
        assertEquals(emptyList(), GenreDictionary.resolveParentGenres("audiobook"))
    }

    @Test
    fun garbageTokensDontAffectValidTokens() {
        assertContentEquals(
            listOf("Synthwave", "Vocal"),
            GenreDictionary.resolveParentGenres("synthwave, live vocal")
        )
    }

    // ── resolveParentGenres – Combined End-to-End ─────────────────────

    @Test
    fun endToEndMixedInputWithDuplicatesAndGarbage() {
        val input = "K-Pop, shoegaze, live vocal, ROCK, pop"
        assertContentEquals(
            listOf("K-Pop", "Pop", "Rock", "Vocal"),
            GenreDictionary.resolveParentGenres(input)
        )
    }

    @Test
    fun endToEndFiltersAllGarbageToEmpty() {
        assertEquals(
            emptyList(),
            GenreDictionary.resolveParentGenres("podcast, audiobook")
        )
    }
}
