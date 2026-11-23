package com.shashankanil.halo

import com.shashankanil.halo.core.Look
import java.text.Normalizer

/** What Halo needs from a notification, extracted so the matching logic is testable. */
data class NotifInfo(
    val pkg: String,
    val key: String,
    val title: String = "",
    /** Senders of the newest messages (MessagingStyle), newest last. */
    val senders: List<String> = emptyList(),
    /** Person URIs attached to the notification: tel:, mailto: or contact lookup URIs. */
    val personUris: List<String> = emptyList(),
    val isGroup: Boolean = false,
    val isCall: Boolean = false,
    /** 1 incoming, 2 ongoing, 3 screening, 0 unknown (Notification.CallStyle). */
    val callType: Int = 0,
)

data class Decision(val look: Look, val source: String, val favourite: Boolean)

object NotificationMatch {

    /** Finds the favourite this notification is from, strongest evidence first. */
    fun matchContact(info: NotifInfo, contacts: List<FavContact>): FavContact? {
        if (contacts.isEmpty()) return null
        // 1. A lookup key or phone number in a Person URI is the most reliable signal.
        for (uri in info.personUris) {
            contacts.firstOrNull { c -> c.lookupKey.isNotEmpty() && uri.contains(c.lookupKey) }?.let { return it }
            val digits = digits(uri)
            if (digits.length >= 6) contacts.firstOrNull { c -> c.numbers.any { samePhone(it, digits) } }?.let { return it }
        }
        // 2. The newest message's sender. In a group chat only the sender counts, never the title.
        info.senders.lastOrNull()?.let { s ->
            contacts.firstOrNull { sameName(it.name, s) }?.let { return it }
        }
        if (info.isGroup) return null
        // 3. One-to-one chats and calls usually put the person in the title.
        if (info.title.isNotBlank()) {
            contacts.firstOrNull { sameName(it.name, info.title) }?.let { return it }
            // Titles that are just a phone number (SMS from an unsaved formatting).
            val t = digits(info.title)
            if (t.length >= 6 && t.length * 2 >= info.title.count { !it.isWhitespace() }) {
                contacts.firstOrNull { c -> c.numbers.any { samePhone(it, t) } }?.let { return it }
            }
        }
        return null
    }

    /** Decides the look for a notification, or null when it should not light the ring. */
    fun decide(info: NotifInfo, cfg: Config): Decision? {
        val contact = matchContact(info, cfg.contacts)
        if (info.isCall) {
            if (info.callType != 1 && info.callType != 0) return null
            if (contact != null && contact.calls) return Decision(contact.look, "call:${contact.name}", true)
            return if (cfg.otherCalls.enabled) Decision(cfg.otherCalls.look, "call", false) else null
        }
        if (contact != null && contact.messages) return Decision(contact.look, "contact:${contact.name}", true)
        cfg.appRules.firstOrNull { it.pkg == info.pkg && it.enabled }?.let { return Decision(it.look, "app:${it.pkg}", false) }
        return if (cfg.otherNotifications.enabled) Decision(cfg.otherNotifications.look, "other", false) else null
    }

    fun inQuietHours(cfg: Config, hour: Int): Boolean {
        val s = cfg.quietStart
        val e = cfg.quietEnd
        if (s < 0 || e < 0 || s == e) return false
        return if (s < e) hour in s until e else hour >= s || hour < e
    }

    fun normalizeName(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFKD)
            .lowercase()
            .replace(Regex("\\p{M}"), "")
            .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    fun sameName(a: String, b: String): Boolean {
        val x = normalizeName(a)
        val y = normalizeName(b)
        return x.isNotEmpty() && x == y
    }

    fun digits(s: String) = s.filter { it.isDigit() }

    /** Compares the last 9 digits so +44 7700 900123 and 07700900123 match. */
    fun samePhone(a: String, b: String): Boolean {
        val x = digits(a)
        val y = digits(b)
        if (x.length < 6 || y.length < 6) return false
        val n = minOf(9, x.length, y.length)
        return x.takeLast(n) == y.takeLast(n)
    }
}
