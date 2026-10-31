package io.github.zeperus.openpad

/**
 * Iteration budget for random/property tests. CI runs the full count (`-Popenpad.fullProperties=true`, passed to the test JVM as the
 * system property `openpad.fullProperties`); a local run uses a fifth (at least [MIN]) so the edit-test loop stays fast. Seeds stay
 * deterministic (0 until n), so a failure seen in CI can be reproduced locally with the same flag.
 */
object PropertyBudget {
    private const val MIN = 50
    val full: Boolean = System.getProperty("openpad.fullProperties")?.toBoolean() == true

    /** The number of random cases to run: [fullCount] on CI, otherwise a fifth of it (never less than [MIN] or more than [fullCount]). */
    fun cases(fullCount: Int): Int = if (full) fullCount else minOf(fullCount, maxOf(MIN, fullCount / 5))
}
