package com.nosfabrica.observer.service.keys

/**
 * Whether [s] is a pubkey as Nostr events carry it: 64 lowercase hex digits. Quartz's
 * `Hex.isHex64` takes either case and doesn't check the length; an owner that differs only by case
 * would match no schedule, and the trigger contract asks for lowercase.
 */
fun isPubKeyHex(s: String?): Boolean = s != null && s.length == 64 && s.all { it in '0'..'9' || it in 'a'..'f' }
