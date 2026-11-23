package com.shashankanil.halo

import com.shashankanil.halo.core.Effect
import com.shashankanil.halo.core.Effects
import com.shashankanil.halo.core.HaloState
import com.shashankanil.halo.core.Layer
import com.shashankanil.halo.core.Look
import com.shashankanil.halo.core.Morse
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectsTest {
    private val red = 0xFFFF0000.toInt()
    private val blue = 0xFF0000FF.toInt()

    @Test fun solidFillsEveryLed() {
        assertArrayEquals(IntArray(8) { red }, Effects.render(Look(Effect.SOLID, color = red), 0))
    }

    @Test fun brightnessScalesInSoftware() {
        val out = Effects.render(Look(Effect.SOLID, color = red, brightness = 0.5f), 0)
        assertEquals(128, Effects.r(out[0]))
    }

    @Test fun fillLightsProportionOfRing() {
        val out = Effects.render(Look(Effect.FILL, color = red, color2 = 0xFF000000.toInt(), value = 0.5f), 0)
        assertEquals(red, out[0]); assertEquals(red, out[3])
        assertTrue(Effects.isDark(intArrayOf(out[4], out[7])))
    }

    @Test fun countdownDrainsThenFlashes() {
        val look = Look(Effect.COUNTDOWN, color = red, startAt = 0, durationMs = 8000)
        val atStart = Effects.render(look, 1)
        val halfway = Effects.render(look, 4000)
        assertEquals(8, atStart.count { it != Effects.BLACK })
        assertEquals(4, halfway.count { it == red })
        assertArrayEquals(IntArray(8) { red }, Effects.render(look, 8000))      // flash on
        assertTrue(Effects.isDark(Effects.render(look, 8250)))                   // flash off
    }

    @Test fun customPaintsEachLed() {
        val leds = List(8) { if (it % 2 == 0) red else blue }
        assertEquals(leds, Effects.render(Look(Effect.CUSTOM, leds = leds), 0).toList())
    }

    @Test fun spinUsesBothColours() {
        val out = Effects.render(Look(Effect.SPIN, color = red, color2 = blue), 0)
        assertEquals(4, out.count { it == red }); assertEquals(4, out.count { it == blue })
    }

    @Test fun everyEffectRendersOpaqueColours() {
        Effect.entries.forEach { e ->
            val out = Effects.render(Look(e, color = red, color2 = blue, text = "SOS", durationMs = 1000), 123_456)
            assertEquals(8, out.size)
            out.forEach { assertEquals("$e alpha", 0xFF, (it ushr 24) and 0xFF) }
        }
    }

    @Test fun hsvRoundTrips() {
        val c = 0xFF3366CC.toInt()
        val h = Effects.toHsv(c)
        assertEquals(c, Effects.hsv(h[0].toDouble(), h[1].toDouble(), h[2].toDouble()))
    }
}

class MorseTest {
    @Test fun sosPattern() {
        // S = ... , O = --- : on/off units alternate, letter gaps of 3, repeat gap 10
        assertEquals(listOf(1, 1, 1, 1, 1, 3, 3, 1, 3, 1, 3, 3, 1, 1, 1, 1, 1, 10), Morse.pattern("SOS"))
    }

    @Test fun timing() {
        assertTrue(Morse.isOn("E", 0, 100))
        assertFalse(Morse.isOn("E", 150, 100))
        assertTrue(Morse.isOn("E", 1100, 100)) // repeats after 1 + 10 units
    }

    @Test fun ignoresUnknownCharacters() {
        assertEquals(Morse.pattern("SOS"), Morse.pattern("s.o.s!"))
        assertTrue(Morse.pattern("!!!").isEmpty())
    }
}

class StateJsonTest {
    @Test fun roundTrip() {
        val state = HaloState(
            rev = 7, priority = 3, protect = false,
            layers = listOf(
                Layer("alert:x", Look(Effect.MORSE, color = 0xFF112233.toInt(), text = "HI", startAt = 99), until = 1234),
                Layer("live", Look(Effect.CUSTOM, leds = listOf(1, 2, 3).map { it or (0xFF shl 24) }, brightness = 0.25f)),
            ),
        )
        assertEquals(state, HaloState.fromJson(state.toJson()))
    }

    @Test fun configRoundTrip() {
        val cfg = Config(
            contacts = listOf(FavContact(name = "Ana", numbers = listOf("+1 555 0100"), lookupKey = "k1", look = Look(Effect.PULSE))),
            appRules = listOf(AppRule("com.whatsapp", "WhatsApp", Look(Effect.BREATHE, color = 0xFF00FF00.toInt()))),
            chargingMode = ChargingMode.WHILE_CHARGING, quietStart = 22, quietEnd = 7, paired = true,
        )
        assertEquals(cfg, Config.fromJson(cfg.toJson()))
    }

    @Test fun layerExpiry() {
        assertTrue(Layer("a", Look(), until = 0).liveAt(5))
        assertTrue(Layer("a", Look(), until = 10).liveAt(5))
        assertFalse(Layer("a", Look(), until = 10).liveAt(10))
    }
}

class NotificationMatchTest {
    private val ana = FavContact(name = "Ana María", numbers = listOf("+44 7700 900123"), lookupKey = "0r1-ABC", look = Look(color = 1))
    private val bob = FavContact(name = "Bob", numbers = listOf("555-0199"), look = Look(color = 2))
    private val cfg = Config(contacts = listOf(ana, bob))

    @Test fun matchesByPhoneUri() {
        assertEquals(ana, NotificationMatch.matchContact(NotifInfo("p", "k", personUris = listOf("tel:07700900123")), cfg.contacts))
    }

    @Test fun matchesByLookupKey() {
        val uri = "content://com.android.contacts/contacts/lookup/0r1-ABC/12"
        assertEquals(ana, NotificationMatch.matchContact(NotifInfo("p", "k", personUris = listOf(uri)), cfg.contacts))
    }

    @Test fun matchesNameIgnoringAccentsCaseAndEmoji() {
        assertEquals(ana, NotificationMatch.matchContact(NotifInfo("p", "k", title = "ana maria ❤️"), cfg.contacts))
    }

    @Test fun groupChatUsesSenderNotTitle() {
        val inGroup = NotifInfo("p", "k", title = "Bob", senders = listOf("Carol"), isGroup = true)
        assertEquals(null, NotificationMatch.matchContact(inGroup, cfg.contacts))
        assertEquals(bob, NotificationMatch.matchContact(inGroup.copy(senders = listOf("Bob")), cfg.contacts))
    }

    @Test fun favouriteCallBeatsAppRule() {
        val c = cfg.copy(appRules = listOf(AppRule("dialer", "Phone", Look(color = 9))))
        val d = NotificationMatch.decide(NotifInfo("dialer", "k", title = "Bob", isCall = true, callType = 1), c)!!
        assertEquals(bob.look, d.look); assertTrue(d.favourite)
    }

    @Test fun ongoingCallDoesNotLight() {
        assertEquals(null, NotificationMatch.decide(NotifInfo("dialer", "k", title = "Bob", isCall = true, callType = 2), cfg))
    }

    @Test fun appRuleThenFallback() {
        val c = cfg.copy(appRules = listOf(AppRule("chat", "Chat", Look(color = 9))))
        assertEquals(9, NotificationMatch.decide(NotifInfo("chat", "k", title = "Zed"), c)!!.look.color)
        assertEquals(null, NotificationMatch.decide(NotifInfo("other", "k", title = "Zed"), c))
        val withOther = c.copy(otherNotifications = Trigger(true, Look(color = 5)))
        assertEquals(5, NotificationMatch.decide(NotifInfo("other", "k", title = "Zed"), withOther)!!.look.color)
    }

    @Test fun quietHoursWrapMidnight() {
        val c = Config(quietStart = 22, quietEnd = 7)
        assertTrue(NotificationMatch.inQuietHours(c, 23)); assertTrue(NotificationMatch.inQuietHours(c, 3))
        assertFalse(NotificationMatch.inQuietHours(c, 7)); assertFalse(NotificationMatch.inQuietHours(c, 12))
    }
}
