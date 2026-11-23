package com.shashankanil.halo.adb

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.android.AdbMdns
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Halo's own ADB client identity. Wireless debugging remembers this key after pairing, so the
 * app can reconnect to the phone's adbd later without asking again.
 */
class AdbClient private constructor(context: Context) : AbsAdbConnectionManager() {
    private val key: PrivateKey
    private val cert: Certificate

    init {
        api = Build.VERSION.SDK_INT
        val keyFile = File(context.filesDir, "adb.key")
        val certFile = File(context.filesDir, "adb.crt")
        if (keyFile.exists() && certFile.exists()) {
            key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
            cert = certFile.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
        } else {
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val name = X500Name("CN=Pixel Halo")
            val now = System.currentTimeMillis()
            val holder = JcaX509v3CertificateBuilder(
                name, BigInteger.valueOf(now), Date(now - DAY), Date(now + 3650 * DAY), name, pair.public,
            ).build(JcaContentSignerBuilder("SHA256withRSA").build(pair.private))
            key = pair.private
            cert = JcaX509CertificateConverter().getCertificate(holder)
            keyFile.writeBytes(key.encoded)
            certFile.writeBytes(cert.encoded)
        }
    }

    override fun getPrivateKey(): PrivateKey = key
    override fun getCertificate(): Certificate = cert
    override fun getDeviceName(): String = "PixelHalo"

    companion object {
        private const val DAY = 86_400_000L
        @Volatile private var instance: AdbClient? = null

        fun get(context: Context): AdbClient =
            instance ?: synchronized(this) { instance ?: AdbClient(context.applicationContext).also { instance = it } }

        /** Finds the local adbd port advertised over mDNS for [type] (pairing or connect). */
        fun discoverPort(context: Context, type: String, timeoutMs: Long): Int? {
            val port = AtomicInteger(-1)
            val latch = CountDownLatch(1)
            val mdns = AdbMdns(context, type) { host, p ->
                if (host != null && p > 0) {
                    port.set(p)
                    latch.countDown()
                }
            }
            mdns.start()
            try {
                latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            } finally {
                mdns.stop()
            }
            return port.get().takeIf { it > 0 }
        }

        const val PAIRING = AdbMdns.SERVICE_TYPE_TLS_PAIRING
        const val CONNECT = AdbMdns.SERVICE_TYPE_TLS_CONNECT
    }
}
