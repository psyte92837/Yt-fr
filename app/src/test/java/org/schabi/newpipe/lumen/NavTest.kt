package org.schabi.newpipe.lumen

import org.junit.Assert.assertEquals
import org.junit.Test

class NavTest {
    private val p = Nav.LUMEN
    private val home = Nav.HOME
    private val project = p + "ProjectActivity"
    private val video = p + "VideoActivity"
    private val bot = p + "BotActivity"
    private val k0 = Nav.NO_KEY
    private fun kId(id: String) = Nav.key(null, id, null)
    private val tasks = mapOf(1 to home, 2 to Nav.BOTS_HOME)

    @Test fun screenOnTopJustComesForward() {
        val s = listOf(NavScreen(home, k0, 1, 1, 10), NavScreen(project, kId("a"), 1, 2, 11))
        assertEquals(NavPlan.Front(1), Nav.plan(s, project, kId("a"), 0, tasks, 1))
    }

    @Test fun deeperScreenIsReorderedKeepingTheOthers() {
        val s = listOf(NavScreen(home, k0, 1, 1, 10), NavScreen(project, kId("a"), 1, 2, 11), NavScreen(video, k0, 1, 3, 12))
        assertEquals(NavPlan.Reorder(1, listOf(12)), Nav.plan(s, project, kId("a"), 0, tasks, 1))
    }

    @Test fun otherProjectIsPushedNotSwapped() {
        val s = listOf(NavScreen(home, k0, 1, 1, 10), NavScreen(project, kId("a"), 1, 2, 11))
        assertEquals(NavPlan.Push(1), Nav.plan(s, project, kId("b"), 0, tasks, 1))
    }

    @Test fun exactInstanceWinsOverSameKey() {
        val s = listOf(NavScreen(project, kId("a"), 1, 2, 11), NavScreen(project, kId("a"), 1, 5, 12))
        assertEquals(NavPlan.Reorder(1, listOf(12)), Nav.plan(s, project, kId("a"), 11, tasks, 1))
        // the instance is gone: the newest screen with the same key
        assertEquals(NavPlan.Front(1), Nav.plan(s, project, kId("a"), 99, tasks, 1))
    }

    @Test fun botScreensGoToTheBotsTask() {
        assertEquals(NavPlan.Push(2), Nav.plan(emptyList(), bot, kId("x"), 0, tasks, 1))
        assertEquals(NavPlan.Push(1), Nav.plan(emptyList(), project, kId("x"), 0, tasks, 2))
    }

    @Test fun taskRootComesBackAsLeft() {
        val s = listOf(NavScreen(Nav.BOTS_HOME, k0, 2, 1, 20), NavScreen(bot, kId("x"), 2, 2, 21))
        assertEquals(NavPlan.Front(2), Nav.plan(s, Nav.BOTS_HOME, k0, 0, tasks, 1))
        assertEquals(NavPlan.Front(1), Nav.plan(emptyList(), home, k0, 0, tasks, 2))
    }

    @Test fun noTaskStartsFresh() {
        assertEquals(NavPlan.Fresh, Nav.plan(emptyList(), project, kId("a"), 0, emptyMap(), null))
        assertEquals(NavPlan.Fresh, Nav.plan(emptyList(), bot, kId("a"), 0, mapOf(1 to home), 1))
    }

    @Test fun floatingScreenOpensInTheLastTask() {
        val review = p + "SendReviewActivity"
        assertEquals(NavPlan.Push(2), Nav.plan(emptyList(), review, k0, 0, tasks, 2))
        assertEquals(NavPlan.Push(1), Nav.plan(emptyList(), review, k0, 0, tasks, null))
    }

    @Test fun taskKinds() {
        assertEquals(Nav.HOME, Nav.taskHome(project))
        assertEquals(Nav.BOTS_HOME, Nav.taskHome(bot))
        assertEquals(null, Nav.taskHome(p + "HarvestActivity"))
        assertEquals(null, Nav.taskHome("com.other.App"))
    }
}
