package com.projecthub.gitforge.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The updater must not offer a downgrade and must not hide an upgrade. These tests
 * pin the ordering rules that decide which of those happens, including the
 * lexical-vs-numeric trap that `1.10.0` vs `1.9.0` would otherwise fall into.
 */
class VersionComparatorTest {

    @Test
    fun `numeric components compare as numbers not strings`() {
        assertTrue(VersionComparator.isNewer("1.10.0", "1.9.0"))
        assertFalse(VersionComparator.isNewer("1.9.0", "1.10.0"))
    }

    @Test
    fun `leading v on a release tag is ignored`() {
        assertTrue(VersionComparator.isNewer("V1.1.0", "1.0.1"))
        assertEquals(0, VersionComparator.compare("v1.2.3", "1.2.3"))
        assertEquals(0, VersionComparator.compare("V1.2.3", "v1.2.3"))
    }

    @Test
    fun `missing components are treated as zero`() {
        assertEquals(0, VersionComparator.compare("1.2", "1.2.0"))
        assertTrue(VersionComparator.isNewer("1.2.1", "1.2"))
    }

    @Test
    fun `a release outranks its own pre-release but build metadata is ignored`() {
        assertTrue(VersionComparator.compare("1.1.0-rc1", "1.1.0") < 0)
        assertTrue(VersionComparator.isNewer("1.1.0", "1.1.0-rc1"))
        assertFalse(VersionComparator.isNewer("1.1.0-rc1", "1.1.0"))
        assertEquals(0, VersionComparator.compare("1.1.0+build7", "1.1.0"))
    }

    @Test
    fun `huge numeric components cap instead of overflowing to zero`() {
        assertTrue(VersionComparator.isNewer("1.99999999999", "1.9"))
        assertEquals(0, VersionComparator.compare("1.99999999999", "1.99999999999"))
    }

    @Test
    fun `equal versions are not an update`() {
        assertFalse(VersionComparator.isNewer("1.1.0", "1.1.0"))
        assertFalse(VersionComparator.isNewer("V1.1.0", "1.1.0"))
    }

    @Test
    fun `a newer patch version is an update`() {
        assertTrue(VersionComparator.isNewer("1.0.2", "1.0.1"))
        assertTrue(VersionComparator.isNewer("2.0.0", "1.99.99"))
    }

    @Test
    fun `malformed input does not throw`() {
        assertEquals(emptyList<Int>(), VersionComparator.parse(""))
        assertEquals(listOf(1, 0, 0), VersionComparator.parse("1.x.0"))
        assertFalse(VersionComparator.isNewer("", "1.0.0"))
    }
}
