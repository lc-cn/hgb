package io.github.yysassist.server

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object Secrets {
    private val random = SecureRandom()
    private val url = Base64.getUrlEncoder().withoutPadding()
    fun bytes(size: Int) = ByteArray(size).also(random::nextBytes)
    fun token(size: Int = 32): String = url.encodeToString(bytes(size))
    fun code(): String = random.nextInt(1_000_000).toString().padStart(6, '0')
    fun sha256(value: String): String = url.encodeToString(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()))
    fun challenge(verifier: String): String = sha256(verifier)
    fun hmac(key: ByteArray, value: String): String = url.encodeToString(Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(value.toByteArray()))
    fun same(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
}

/** Argon2id with the parameters OWASP lists for 19 MiB of memory. */
object Passwords {
    private const val MEMORY = 19_456; private const val ITERATIONS = 2; private const val LANES = 1; private const val LENGTH = 32
    private val encoder = Base64.getEncoder().withoutPadding(); private val decoder = Base64.getDecoder()
    private val decoy by lazy { hash(Secrets.token()) }

    private fun derive(password: String, salt: ByteArray, memory: Int, iterations: Int, lanes: Int, length: Int): ByteArray {
        val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id).withSalt(salt).withMemoryAsKB(memory)
            .withIterations(iterations).withParallelism(lanes).build()
        return ByteArray(length).also { Argon2BytesGenerator().apply { init(parameters) }.generateBytes(password.toCharArray(), it) }
    }
    fun hash(password: String): String {
        val salt = Secrets.bytes(16)
        return "argon2id\$$MEMORY\$$ITERATIONS\$$LANES\$${encoder.encodeToString(salt)}\$${encoder.encodeToString(derive(password, salt, MEMORY, ITERATIONS, LANES, LENGTH))}"
    }
    /** A missing account still costs one full hash, so timing does not reveal who is registered. */
    fun verify(password: String, stored: String?): Boolean {
        val parts = (stored ?: decoy).split('$')
        if (parts.size != 6 || parts[0] != "argon2id") return false
        val expected = decoder.decode(parts[5])
        val actual = derive(password, decoder.decode(parts[4]), parts[1].toInt(), parts[2].toInt(), parts[3].toInt(), expected.size)
        return MessageDigest.isEqual(expected, actual) && stored != null
    }
}

class RateLimiter(private val limit: Int, private val windowMillis: Long, private val clock: () -> Long) {
    private val hits = ConcurrentHashMap<String, ArrayDeque<Long>>()
    fun allow(key: String): Boolean {
        val now = clock(); val queue = hits.computeIfAbsent(key) { ArrayDeque() }
        synchronized(queue) {
            while (queue.isNotEmpty() && now - queue.first() >= windowMillis) queue.removeFirst()
            if (queue.size >= limit) return false
            queue.addLast(now); return true
        }
    }
}
