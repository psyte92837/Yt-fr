package org.schabi.newpipe.lumen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProgressTextTest {
    @Test fun parsesCounter() = assertEquals(0.3f, ProgressText.fraction("Каналы 3/10")!!, 0.001f)
    @Test fun takesLastCounter() = assertEquals(0.5f, ProgressText.fraction("Досье: канал 1/4 · 2/4\nname")!!, 0.001f)
    @Test fun noCounter() = assertNull(ProgressText.fraction("Поиск…"))
    @Test fun badCounters() {
        assertNull(ProgressText.fraction("5/0"))
        assertNull(ProgressText.fraction("12/10"))
    }
}
