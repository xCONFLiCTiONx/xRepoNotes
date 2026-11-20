package com.xconflictionx.xreponotes.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import java.util.Locale

/**
 * Exact counterpart of xRepoNotes' RepoColors.For implementation.
 * Uses the repository directory name, FNV-1a, and HSL(0.60, 0.64).
 */
fun projectAccentColor(repoPath: String): Color {
    if (repoPath.isBlank()) return Color(0xFF8A94A6)

    val normalized = repoPath.replace('\\', '/').trimEnd('/')
    val name = normalized.substringAfterLast('/').substringAfterLast(':').lowercase(Locale.ROOT)
    return fromHsl(fnv1aHue(name), 0.60, 0.64)
}

fun workNoteAccentColor(noteKey: String): Color {
    if (noteKey.isBlank()) return projectAccentColor("Work Notebook")
    return fromHsl(fnv1aHue(noteKey.lowercase(Locale.ROOT)), 0.68, 0.64)
}

fun workNoteHue(noteKey: String): Double = fnv1aHue(noteKey.lowercase(Locale.ROOT))

fun projectChipColor(repoPath: String): Color =
    chipColorForAccent(projectAccentColor(repoPath))

fun chipColorForAccent(accent: Color): Color =
    Color(accent.red, accent.green, accent.blue, 48f / 255f)

fun projectCardColor(repoPath: String): Color =
    cardColorForAccent(projectAccentColor(repoPath))

fun cardColorForAccent(accent: Color): Color {
    fun mix(background: Int, foreground: Int): Int =
        (background + (foreground - background) * 0.10).toInt()
    val red = mix(0x1E, (accent.red * 255f).toInt())
    val green = mix(0x23, (accent.green * 255f).toInt())
    val blue = mix(0x2B, (accent.blue * 255f).toInt())
    val argb = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
    return Color(argb)
}

private fun fnv1aHue(key: String): Double {
    var hash = 2166136261u
    key.forEach { ch -> hash = (hash xor ch.code.toUInt()) * 16777619u }
    return (hash % 360u).toDouble()
}

private fun fromHsl(h: Double, s: Double, l: Double): Color {
    val c = (1.0 - abs(2.0 * l - 1.0)) * s
    val x = c * (1.0 - abs((h / 60.0) % 2.0 - 1.0))
    val m = l - c / 2.0
    val (r, g, b) = when ((h / 60.0).toInt()) {
        0 -> Triple(c, x, 0.0)
        1 -> Triple(x, c, 0.0)
        2 -> Triple(0.0, c, x)
        3 -> Triple(0.0, x, c)
        4 -> Triple(x, 0.0, c)
        else -> Triple(c, 0.0, x)
    }
    fun channel(value: Double) = ((value + m) * 255.0).toInt() / 255f
    return Color(channel(r), channel(g), channel(b), 1f)
}
