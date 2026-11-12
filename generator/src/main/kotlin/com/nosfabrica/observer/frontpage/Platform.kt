package com.nosfabrica.observer.frontpage

import okhttp3.OkHttpClient
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.LocalDate
import java.util.Locale

/*
 * The few things Amethyst's engine takes from the rest of Amethyst's `commons`, in the smallest
 * form the engine needs, so the ported files keep their own text.
 */

/**
 * The app's HTTP clients, one per role. Amethyst routes each role through the user's Tor
 * settings; here every role can be the same client, or one with a guard in front of it (the
 * runner's refuses to connect to internal addresses).
 */
interface IRoleBasedHttpClientBuilder {
    /** For calls that spend the reader's money: the AI key's endpoint. */
    fun okHttpClientForMoney(url: String): OkHttpClient

    /** For Blossom uploads and downloads. */
    fun okHttpClientForUploads(url: String): OkHttpClient
}

/** One [OkHttpClient] for every role. */
class SingleHttpClient(
    private val client: OkHttpClient,
) : IRoleBasedHttpClientBuilder {
    override fun okHttpClientForMoney(url: String) = client

    override fun okHttpClientForUploads(url: String) = client
}

/** [value] with the default locale's decimal mark and grouping (Amethyst's `formatDecimal`). */
fun formatDecimal(
    value: Double,
    maxFractionDigits: Int,
    minFractionDigits: Int = 0,
): String {
    val format = DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(Locale.getDefault()))
    format.maximumFractionDigits = maxFractionDigits
    format.minimumFractionDigits = minFractionDigits
    format.roundingMode = RoundingMode.HALF_EVEN
    return format.format(value)
}

/** A calendar date (Amethyst's `SearchDate`), as far as [ObserverToday] uses it. */
class SearchDate(
    year: Int,
    month: Int,
    day: Int,
) {
    private val date: LocalDate? = runCatching { LocalDate.of(year, month, day) }.getOrNull()

    fun plusDays(days: Int): SearchDate? = date?.plusDays(days.toLong())?.let { SearchDate(it.year, it.monthValue, it.dayOfMonth) }

    fun ymd(): String? = date?.toString()

    fun daysFromEpoch(): Long? = date?.toEpochDay()
}
