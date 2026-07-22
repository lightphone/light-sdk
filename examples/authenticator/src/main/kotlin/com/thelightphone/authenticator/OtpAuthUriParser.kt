package com.thelightphone.authenticator

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object OtpAuthUriParser {
    fun parse(uriString: String): Result<Account> {
        val trimmed = uriString.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("QR code is empty"))
        }

        val uri = URI(trimmed)
        if (uri.scheme?.equals("otpauth", ignoreCase = true) != true) {
            return Result.failure(IllegalArgumentException("Invalid QR Code"))
        }

        val type = uri.host?.lowercase()
        if (type != "totp") {
            return Result.failure(IllegalArgumentException("Only TOTP accounts are supported"))
        }

        val query = parseQuery(uri.rawQuery)
        val secret = query["secret"]?.trim()
            ?: return Result.failure(IllegalArgumentException("Missing secret"))

        val pathLabel = decodePathLabel(uri.path)
        val issuerParam = query["issuer"]?.trim().orEmpty()
        val (pathIssuer, label) = splitIssuerAndLabel(pathLabel)

        val issuer = issuerParam.ifBlank { pathIssuer }
        val digits = query["digits"]?.toIntOrNull() ?: 6
        val period = query["period"]?.toIntOrNull() ?: 30
        val algorithm = runCatching { TotpAlgorithm.normalize(query["algorithm"]) }
            .getOrElse { return Result.failure(it) }

        if (digits <= 0) {
            return Result.failure(IllegalArgumentException("Invalid digits: $digits"))
        }
        if (period <= 0) {
            return Result.failure(IllegalArgumentException("Invalid period: $period"))
        }

        return Result.success(
            Account(
                issuer = issuer,
                label = label,
                secret = secret,
                digits = digits,
                period = period,
                algorithm = algorithm,
            ),
        )
    }

    private fun decodePathLabel(path: String?): String {
        val raw = path?.removePrefix("/").orEmpty()
        if (raw.isEmpty()) return ""
        return URLDecoder.decode(raw, StandardCharsets.UTF_8.name())
    }

    private fun splitIssuerAndLabel(pathLabel: String): Pair<String, String> {
        val colonIndex = pathLabel.indexOf(':')
        if (colonIndex < 0) {
            return "" to pathLabel
        }
        return pathLabel.substring(0, colonIndex) to pathLabel.substring(colonIndex + 1)
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery.split('&').mapNotNull { part ->
            val equalsIndex = part.indexOf('=')
            if (equalsIndex < 0) return@mapNotNull null
            val key = URLDecoder.decode(part.substring(0, equalsIndex), StandardCharsets.UTF_8.name())
            val value = URLDecoder.decode(part.substring(equalsIndex + 1), StandardCharsets.UTF_8.name())
            key to value
        }.toMap()
    }
}
