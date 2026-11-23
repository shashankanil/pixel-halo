package com.shashankanil.halo.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** Pure colour maths shared by the renderer and the in-app preview. No Android dependencies. */
object Effects {
    const val BLACK = 0xFF000000.toInt()

    /** Renders [look] for [n] LEDs at wall-clock time [now] (ms). Returns opaque ARGB colours. */
    fun render(look: Look, now: Long, n: Int = 8): IntArray {
        val out = IntArray(n)
        if (n == 0) return out
        val t = now / 1000.0 * look.speed
        val c = look.color
        val c2 = look.color2
        when (look.effect) {
            Effect.SOLID -> out.fill(c)
            Effect.BREATHE -> {
                val level = 0.06 + 0.94 * (0.5 - 0.5 * cos(2 * PI * t / 3.0))
                out.fill(scale(c, level))
            }
            Effect.PULSE -> {
                val p = frac(t / 1.2)
                val on = p < 0.10 || (p in 0.2..0.3)
                out.fill(if (on) c else BLACK)
            }
            Effect.STROBE -> out.fill(if (frac(t / 0.25) < 0.3) c else BLACK)
            Effect.RAINBOW -> for (i in 0 until n) out[i] = hsv(frac(i.toDouble() / n + t * 0.25), 1.0, 1.0)
            Effect.CHASE -> {
                val head = frac(t * 0.5) * n
                for (i in 0 until n) {
                    val behind = ((head - i) % n + n) % n
                    out[i] = scale(c, max(0.0, 1.0 - behind / 3.0).pow(1.5))
                }
            }
            Effect.SPIN -> {
                val offset = floor(t * n * 0.5).toInt()
                for (i in 0 until n) out[i] = if (((i + offset) % n) < n / 2) c else c2
            }
            Effect.WAVE -> for (i in 0 until n) {
                out[i] = mix(c, c2, 0.5 + 0.5 * sin(2 * PI * (t * 0.5 - i.toDouble() / n)))
            }
            Effect.TWINKLE -> for (i in 0 until n) {
                val rate = 0.35 + hash(i) * 0.6
                val level = max(0.0, sin(2 * PI * (t * rate + hash(i + 31)))).pow(4)
                out[i] = scale(c, level)
            }
            Effect.CANDLE -> for (i in 0 until n) {
                val level = 0.62 + 0.18 * sin(t * 7.3 + i * 1.7) + 0.12 * sin(t * 13.1 + i * 2.9) +
                    0.08 * sin(t * 23.7 + i * 0.6)
                out[i] = scale(c, level.coerceIn(0.2, 1.0))
            }
            Effect.POLICE -> {
                val p = frac(t / 0.8)
                val firstHalf = p < 0.5
                val q = frac(p * 2)
                val flash = q < 0.18 || (q in 0.3..0.48)
                for (i in 0 until n) {
                    val inFirst = i < n / 2
                    out[i] = when {
                        !flash -> BLACK
                        firstHalf && inFirst -> c
                        !firstHalf && !inFirst -> c2
                        else -> BLACK
                    }
                }
            }
            Effect.FILL -> fill(out, look.value.toDouble(), c, c2)
            Effect.COUNTDOWN -> {
                val elapsed = now - look.startAt
                if (look.durationMs <= 0 || elapsed >= look.durationMs) {
                    // Time is up: flash at 2 Hz until the layer expires.
                    out.fill(if (frac(now / 500.0) < 0.5) c else BLACK)
                } else {
                    val remaining = 1.0 - elapsed.toDouble() / look.durationMs
                    fill(out, remaining, c, BLACK)
                }
            }
            Effect.MORSE -> out.fill(if (Morse.isOn(look.text, now - look.startAt, unitMs(look))) c else BLACK)
            Effect.CUSTOM -> {
                if (look.leds.isEmpty()) out.fill(c) else for (i in 0 until n) out[i] = look.leds[i % look.leds.size]
            }
        }
        if (look.brightness < 1f) for (i in 0 until n) out[i] = scale(out[i], look.brightness.toDouble())
        return out
    }

    /** True when the effect changes over time, so the renderer must keep drawing frames. */
    fun isAnimated(look: Look): Boolean = when (look.effect) {
        Effect.SOLID, Effect.FILL, Effect.CUSTOM -> false
        else -> true
    }

    fun unitMs(look: Look): Long = (180 / look.speed.coerceAtLeast(0.1f)).toLong().coerceAtLeast(40)

    private fun fill(out: IntArray, value: Double, c: Int, background: Int) {
        val n = out.size
        val lit = value.coerceIn(0.0, 1.0) * n
        val full = floor(lit).toInt()
        val dimBackground = scale(background, 0.08)
        for (i in 0 until n) out[i] = when {
            i < full -> c
            i == full -> mix(dimBackground, c, lit - full)
            else -> dimBackground
        }
    }

    // --- colour helpers -------------------------------------------------------------------

    fun r(c: Int) = (c shr 16) and 0xFF
    fun g(c: Int) = (c shr 8) and 0xFF
    fun b(c: Int) = c and 0xFF

    fun rgb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    fun scale(c: Int, f: Double): Int {
        val k = f.coerceIn(0.0, 1.0)
        return rgb((r(c) * k + 0.5).toInt(), (g(c) * k + 0.5).toInt(), (b(c) * k + 0.5).toInt())
    }

    fun mix(a: Int, b: Int, f: Double): Int {
        val k = f.coerceIn(0.0, 1.0)
        return rgb(
            (r(a) + (r(b) - r(a)) * k + 0.5).toInt(),
            (g(a) + (g(b) - g(a)) * k + 0.5).toInt(),
            (b(a) + (b(b) - b(a)) * k + 0.5).toInt(),
        )
    }

    /** h, s, v in 0..1. */
    fun hsv(h: Double, s: Double, v: Double): Int {
        val hh = frac(h) * 6
        val i = floor(hh).toInt()
        val f = hh - i
        val p = v * (1 - s)
        val q = v * (1 - s * f)
        val u = v * (1 - s * (1 - f))
        val (rr, gg, bb) = when (i) {
            0 -> Triple(v, u, p)
            1 -> Triple(q, v, p)
            2 -> Triple(p, v, u)
            3 -> Triple(p, q, v)
            4 -> Triple(u, p, v)
            else -> Triple(v, p, q)
        }
        return rgb((rr * 255 + 0.5).toInt(), (gg * 255 + 0.5).toInt(), (bb * 255 + 0.5).toInt())
    }

    /** Returns floatArrayOf(h, s, v), each 0..1. */
    fun toHsv(c: Int): FloatArray {
        val rr = r(c) / 255f
        val gg = g(c) / 255f
        val bb = b(c) / 255f
        val mx = max(rr, max(gg, bb))
        val mn = min(rr, min(gg, bb))
        val d = mx - mn
        val h = when {
            d == 0f -> 0f
            mx == rr -> ((gg - bb) / d).let { if (it < 0) it + 6 else it } / 6f
            mx == gg -> ((bb - rr) / d + 2) / 6f
            else -> ((rr - gg) / d + 4) / 6f
        }
        return floatArrayOf(h, if (mx == 0f) 0f else d / mx, mx)
    }

    fun isDark(colors: IntArray) = colors.all { (it and 0xFFFFFF) == 0 }

    private fun frac(x: Double) = x - floor(x)

    /** Deterministic 0..1 pseudo-random value per index. */
    private fun hash(i: Int): Double = abs(sin(i * 12.9898 + 78.233) * 43758.5453).let { it - floor(it) }
}

/** International Morse timing: dot = 1 unit, dash = 3, gap 1, letter gap 3, word gap 7, then repeat. */
object Morse {
    private val CODE = mapOf(
        'A' to ".-", 'B' to "-...", 'C' to "-.-.", 'D' to "-..", 'E' to ".", 'F' to "..-.", 'G' to "--.",
        'H' to "....", 'I' to "..", 'J' to ".---", 'K' to "-.-", 'L' to ".-..", 'M' to "--", 'N' to "-.",
        'O' to "---", 'P' to ".--.", 'Q' to "--.-", 'R' to ".-.", 'S' to "...", 'T' to "-", 'U' to "..-",
        'V' to "...-", 'W' to ".--", 'X' to "-..-", 'Y' to "-.--", 'Z' to "--..", '0' to "-----",
        '1' to ".----", '2' to "..---", '3' to "...--", '4' to "....-", '5' to ".....", '6' to "-....",
        '7' to "--...", '8' to "---..", '9' to "----.",
    )

    /** Alternating on/off durations in units, starting with "on". Ends with the repeat gap. */
    fun pattern(text: String): List<Int> {
        val out = mutableListOf<Int>()
        val words = text.uppercase().split(Regex("\\s+")).map { w -> w.filter { it in CODE } }.filter { it.isNotEmpty() }
        for ((wi, word) in words.withIndex()) {
            for ((li, ch) in word.withIndex()) {
                val code = CODE.getValue(ch)
                for ((si, sym) in code.withIndex()) {
                    out += if (sym == '.') 1 else 3
                    val last = si == code.lastIndex
                    out += when {
                        !last -> 1
                        li != word.lastIndex -> 3
                        wi != words.lastIndex -> 7
                        else -> 10 // pause before repeating the message
                    }
                }
            }
        }
        return out
    }

    fun isOn(text: String, elapsedMs: Long, unitMs: Long): Boolean {
        val p = pattern(text)
        if (p.isEmpty() || unitMs <= 0) return false
        val total = p.sum()
        var pos = ((elapsedMs / unitMs) % total + total) % total
        for ((i, units) in p.withIndex()) {
            if (pos < units) return i % 2 == 0
            pos -= units
        }
        return false
    }
}
