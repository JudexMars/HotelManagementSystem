package dev.hotelbooking.hotel.security

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import java.math.BigInteger
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Base64

internal class JwtTestServer : AutoCloseable {
    private val mapper = jacksonObjectMapper()
    private val trustedKey = generateKey()
    private val untrustedKey by lazy { generateKey() }
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val keyId = "integration-test-key"
    private val server =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/jwks") { exchange ->
                exchange.use {
                    if (exchange.requestMethod != "GET" || exchange.requestURI.path != "/jwks") {
                        exchange.sendResponseHeaders(404, -1)
                    } else {
                        val body = publicKeys()
                        exchange.responseHeaders.set("Content-Type", "application/json")
                        exchange.sendResponseHeaders(200, body.size.toLong())
                        exchange.responseBody.use { it.write(body) }
                    }
                }
            }
            start()
        }

    val uri: String get() = "http://127.0.0.1:${server.address.port}/jwks"

    fun userToken(
        role: String = "USER",
        defect: String? = null,
    ): String =
        token(
            audience = if (defect == "wrong audience") "hotel-inventory" else "hotel-booking-api",
            subject = "11111111-2222-3333-4444-555555555555",
            roles = listOf(role),
            defect = defect,
        )

    fun serviceToken(
        scope: String = "inventory:read inventory:write",
        subject: String = "booking-service",
        defect: String? = null,
    ): String =
        token(
            audience = if (defect == "wrong audience") "hotel-booking-api" else "hotel-inventory",
            subject = subject,
            scope = scope,
            defect = defect,
        )

    private fun token(
        audience: String,
        subject: String,
        roles: List<String> = emptyList(),
        scope: String? = null,
        defect: String? = null,
    ): String {
        val now = Instant.now().epochSecond
        val claims =
            linkedMapOf(
                "iss" to if (defect == "wrong issuer") "untrusted-issuer" else "hotel-booking-system",
                "sub" to subject,
                "aud" to listOf(audience),
                "iat" to now - 120,
            )
        if (defect != "missing expiry") {
            claims["exp"] = if (defect == "expired") now - 1 else now + 600
        }
        if (defect == "future not-before") claims["nbf"] = now + 120
        if (roles.isNotEmpty()) claims["roles"] = roles
        if (scope != null) claims["scope"] = scope
        val header = mapOf("alg" to "RS256", "typ" to "JWT", "kid" to keyId)
        val input = "${encoder.encodeToString(
            mapper.writeValueAsBytes(header),
        )}.${encoder.encodeToString(mapper.writeValueAsBytes(claims))}"
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(if (defect == "forged signature") untrustedKey.private else trustedKey.private)
        signer.update(input.toByteArray(StandardCharsets.US_ASCII))
        return "$input.${encoder.encodeToString(signer.sign())}"
    }

    private fun publicKeys(): ByteArray {
        val key = trustedKey.public as RSAPublicKey
        return mapper.writeValueAsBytes(
            mapOf(
                "keys" to
                    listOf(
                        mapOf(
                            "kty" to "RSA",
                            "use" to "sig",
                            "alg" to "RS256",
                            "kid" to keyId,
                            "n" to unsignedBase64(key.modulus),
                            "e" to unsignedBase64(key.publicExponent),
                        ),
                    ),
            ),
        )
    }

    private fun unsignedBase64(value: BigInteger): String {
        val bytes = value.toByteArray()
        return encoder.encodeToString(if (bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes)
    }

    private fun generateKey(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    override fun close() {
        server.stop(0)
    }
}
