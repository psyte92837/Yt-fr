/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.junit.Assert.assertEquals
import org.junit.Test

class WizardStepsTest {
    @Test
    fun appearanceComesRightAfterLanguage() {
        assertEquals(listOf("lang", "look"), WizardSteps.KEYS.take(2))
        assertEquals(WizardSteps.KEYS.size, WizardSteps.KEYS.toSet().size)
        assertEquals("backup", WizardSteps.KEYS.last())
    }

    @Test
    fun everyStepHasNextAndLastFinishes() {
        val last = WizardSteps.KEYS.size - 1
        (0 until last).forEach { assertEquals("Далее →", WizardSteps.nextLabel(it)) }
        assertEquals("Готово", WizardSteps.nextLabel(last))
    }
}
