package com.xconflictionx.xreponotes

import com.xconflictionx.xreponotes.util.NotesSyncEngine
import org.junit.Assert.assertEquals
import org.junit.Test

class NotesSyncEngineTest {

    @Test
    fun testMergeContent_conflictResolutionWithMAndDTags() {
        val base = "Line 1\nLine 2\nLine 3"
        val desktop = "Line 1\nDesktop Line 2\nLine 3"
        val mobile = "Line 1\nMobile Line 2\nLine 3"

        val merged = NotesSyncEngine.mergeContent(base, desktop, mobile)
        val expected = "Line 1\n[M] Mobile Line 2\n[D] Desktop Line 2\nLine 3"

        assertEquals(expected, merged)
    }

    @Test
    fun testMergeContent_onlyMobileEdited() {
        val base = "Line 1\nLine 2"
        val desktop = "Line 1\nLine 2"
        val mobile = "Line 1\nMobile Updated Line 2"

        val merged = NotesSyncEngine.mergeContent(base, desktop, mobile)
        val expected = "Line 1\nMobile Updated Line 2"

        assertEquals(expected, merged)
    }

    @Test
    fun testMergeContent_onlyDesktopEdited() {
        val base = "Line 1\nLine 2"
        val desktop = "Line 1\nDesktop Updated Line 2"
        val mobile = "Line 1\nLine 2"

        val merged = NotesSyncEngine.mergeContent(base, desktop, mobile)
        val expected = "Line 1\nDesktop Updated Line 2"

        assertEquals(expected, merged)
    }

    @Test
    fun testMergeContent_bothIdenticalEdits() {
        val base = "Line 1\nLine 2"
        val desktop = "Line 1\nIdentical Edit"
        val mobile = "Line 1\nIdentical Edit"

        val merged = NotesSyncEngine.mergeContent(base, desktop, mobile)
        val expected = "Line 1\nIdentical Edit"

        assertEquals(expected, merged)
    }
}
