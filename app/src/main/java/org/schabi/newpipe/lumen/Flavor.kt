/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

/**
 * Which app this code is built into. Lumen (this module) = YouTube analysis on NewPipe.
 * Lumen Studio (module :studio) compiles the Studio and editor files of this folder without NewPipe and
 * replaces this file with its own copy where [STUDIO] = true: YouTube features are hidden there.
 */
object Flavor {
    const val STUDIO = false
}
