package com.cinevault

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLinkType

@CloudstreamPlugin
class CineVaultPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(CineVaultProvider())
    }
}

class CineVaultProvider : TmdbProvider() {
    override var name = "CineVault"
    override var mainUrl = "https://cinevault-api.imrannn68d-de2.workers.dev"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Torrent)

    private val tmdbApi = "https://cinevault-api.imrannn68d-de2.workers.dev"

    override val mainPage = mainPageOf(
        "$tmdbApi/trending/all/week" to "Trending",
        "$tmdbApi/movie/popular" to "Popular Movies",
        "$tmdbApi/tv/popular" to "Popular TV Shows",
        "$tmdbApi/movie/top_rated" to "Top Rated Movies",
        "$tmdbApi/tv/top_rated" to "Top Rated TV Shows"
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val tmdbLink = parseJson<TmdbLink>(data)
        val imdbId = tmdbLink.imdbID ?: return false
        val season = tmdbLink.season
        val episode = tmdbLink.episode
        val isMovie = season == null

        val streamUrl = if (isMovie) "$tmdbApi/streams/$imdbId"
        else "$tmdbApi/streams/$imdbId/$season/$episode"

        val streams = app.get(streamUrl).parsed<List<TorrentioStream>>()
        streams.forEach { stream ->
            val title = stream.title ?: stream.name ?: "Unknown"
            val hash = stream.infoHash ?: return@forEach
            val magnet = "magnet:?xt=urn:btih:$hash" +
                "&tr=udp://tracker.opentrackr.org:1337/announce" +
                "&tr=udp://open.stealth.si:80/announce" +
                "&tr=udp://exodus.desync.com:6969/announce" +
                "&tr=udp://tracker.torrent.eu.org:451/announce"
            callback(
                newExtractorLink(
                    name, title, magnet,
                    ExtractorLinkType.MAGNET
                ) {
                    this.referer = ""
                    this.quality = getQualityFromName(title)
                }
            )
        }

        try {
            val subs = app.get("$tmdbApi/subtitles/$imdbId?lang=eng").parsed<List<OpenSubtitle>>()
            subs.forEach { sub ->
                subtitleCallback(SubtitleFile("English", sub.url ?: return@forEach))
            }
        } catch (e: Exception) { }

        return true
    }

    data class TmdbLink(
        val imdbID: String? = null,
        val tmdbID: Int? = null,
        val episode: Int? = null,
        val season: Int? = null,
        val movieName: String? = null
    )
    data class TorrentioStream(
        val title: String? = null,
        val name: String? = null,
        val url: String? = null,
        val infoHash: String? = null,
        val fileIdx: Int? = null,
        val behaviorHints: BehaviorHints? = null
    )
    data class BehaviorHints(val filename: String? = null)
    data class OpenSubtitle(val url: String? = null)
}
