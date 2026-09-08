package com.tubelimiter.app.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 감시 대상 목록을 테스트로 고정한다 (documents/QA_REVIEW.md §1.8). 여기서 패키지가 하나 빠지면
 * 그 앱은 집계도 차단도 되지 않는데, 화면에는 아무 표시도 나지 않는다 — 조용한 회귀라 컴파일도
 * 통과하고 수동 확인도 어렵다.
 */
class WatchedPackagesTest {

    @Test
    fun `always watches the youtube and kids apps`() {
        assertEquals(
            listOf("com.google.android.youtube", "com.google.android.apps.youtube.kids"),
            ALWAYS_WATCHED_PACKAGES,
        )
    }

    @Test
    fun `music is left out by default`() {
        assertFalse(YOUTUBE_MUSIC_PACKAGE in watchedPackages(includeMusic = false))
        assertEquals(ALWAYS_WATCHED_PACKAGES, watchedPackages(includeMusic = false))
    }

    @Test
    fun `music joins the list when the toggle is on`() {
        val packages = watchedPackages(includeMusic = true)
        assertEquals(ALWAYS_WATCHED_PACKAGES + YOUTUBE_MUSIC_PACKAGE, packages)
        assertTrue(YOUTUBE_MUSIC_PACKAGE in packages)
    }

    @Test
    fun `list has no duplicates and never drops the main app`() {
        listOf(true, false).forEach { includeMusic ->
            val packages = watchedPackages(includeMusic)
            assertEquals(packages.size, packages.toSet().size)
            assertTrue(YOUTUBE_PACKAGE in packages)
            assertTrue(YOUTUBE_KIDS_PACKAGE in packages)
        }
    }

    @Test
    fun `toggling music does not mutate the shared list`() {
        val before = ALWAYS_WATCHED_PACKAGES.toList()
        watchedPackages(includeMusic = true)
        assertEquals(before, ALWAYS_WATCHED_PACKAGES)
    }
}
