package com.folio.reader.ai

/**
 * Address of a server on the reader's own computer (Ollama, the voice server), as typed in Settings:
 * "192.168.1.65", "192.168.1.65:8880" or a full URL → "http://host:port", without a trailing slash.
 *
 * Phone keyboards mangle the scheme ("Http://", "http:/", "http//"); whatever was typed before the host is dropped
 * instead of getting a second "http://" in front of it.
 */
fun normalizeServerUrl(raw: String, defaultPort: Int): String {
    val typed = raw.trim().trimEnd('/')
    val https = typed.startsWith("https:", ignoreCase = true)
    var rest = typed.replace(SCHEME, "")
    val host = rest.substringBefore('/')
    if (host.isNotEmpty() && !host.contains(':')) rest = host + ":" + defaultPort + rest.substring(host.length)
    return (if (https) "https://" else "http://") + rest
}

/** One or more typed schemes: "http://", "http:/", "http//", "HTTPS:"… (not the "http" of a host like "httpbox"). */
private val SCHEME = Regex("^(?:https?(?::/*|/+))+", RegexOption.IGNORE_CASE)
