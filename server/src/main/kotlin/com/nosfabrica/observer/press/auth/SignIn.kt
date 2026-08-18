package com.nosfabrica.observer.press.auth

import com.vitorpamplona.quartz.nip98HttpAuth.Nip98AuthVerifier

/**
 * Sign-in is a NIP-98 signature over this exact request.
 *
 * Not a challenge/response of our own invention. NIP-98 is the standard shape,
 * every signer already knows how to produce it, and — the reason it matters
 * here — it is IDENTICAL whether the reader signs with a browser extension or a
 * remote signer on their phone. The awkward part of NIP-46 stays in the
 * browser, where the transport lives, instead of leaking into our protocol.
 *
 * quartz owns the verification: the `u` and `method` tags matching the request,
 * the `created_at` window, the signature, and a replay cache keyed on event id.
 * All four of those are things a hand-rolled check gets subtly wrong, and the
 * two that fail open — a `u` tag nobody compares, a replay nobody remembers —
 * fail open silently.
 */
class SignIn(
    private val verifier: Nip98AuthVerifier = Nip98AuthVerifier(),
) {
    sealed interface Result {
        data class Ok(
            val pubkey: String,
        ) : Result

        data class No(
            val reason: String,
        ) : Result
    }

    suspend fun verify(
        header: String?,
        url: String,
        method: String,
        body: ByteArray?,
    ): Result =
        // quartz takes (header, METHOD, URL, body) and all four are Strings, so
        // swapping the middle two compiles and fails at runtime with "method
        // mismatch: expected http://.../api/session, got POST". Named here so
        // the next reader does not have to decode that sentence.
        when (val result = verifier.verify(header, method, url, body)) {
            is Nip98AuthVerifier.Result.Verified -> Result.Ok(result.pubkey)
            is Nip98AuthVerifier.Result.Malformed -> Result.No(result.reason)
            is Nip98AuthVerifier.Result.Missing -> Result.No("no Authorization header")
        }
}
