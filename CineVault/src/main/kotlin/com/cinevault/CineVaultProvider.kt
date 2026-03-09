package com.cinevault

import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson

@CloudstreamPlugin
class CineVaultPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(CineVaultProvider())
    }
}

class CineVaultProvider : MainAPI() {
    override var name = "CineVault"
    override var mainUrl = "https://cinevault-api.imrannn68d-de2.workers.dev"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Torrent)

    private val tmdbApi = "https://api.themoviedb.org/3"
    private val apiKey = "4ef0d7355d9ffb5151e987764708ce96"
    private val workerApi = "https://cinevault-api.imrannn68d-de2.workers.dev"

    override val mainPage = mainPageOf(
        "$tmdbApi/trending/all/week?api_key=$apiKey" to "Trending",
        "$tmdbApi/movie/popular?api_key=$apiKey" to "Popular Movies",
        "$tmdbApi/tv/popular?api_key=$apiKey" to "Popular TV Shows",
        "$tmdbApi/movie/top_rated?api_key=$apiKey" to "Top Rated Movies",
        "$tmdbApi/tv/top_rated?api_key=$apiKey" to "Top Rated TV Shows"
    )

    private fun getImageUrl(link: String?): String? {
        if (link == null) return null
        return if (link.startsWith("/")) "https://image.tmdb.org/t/p/w500$link" else link
    }

    private fun getType(t: String?): TvType {
        return when (t) {
            "movie" -> TvType.Movie
            else -> TvType.TvSeries
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val type = if (request.data.contains("/movie")) "movie" else "tv"
        val res = app.get("${request.data}&page=$page").parsedSafe<Results>()
            ?: throw ErrorLoadingException("Invalid Json Response")
        val home = res.results?.mapNotNull { it.toSearchResponse(type) } ?: emptyList()
        return newHomePageResponse(request.name, home)
    }

    private fun Media.toSearchResponse(type: String? = null): SearchResponse? {
        val mediaType = type ?: this.media_type ?: return null
        if (mediaType == "person") return null
        val t = title ?: name ?: original_title ?: original_name ?: return null
        val poster = getImageUrl(poster_path)
        val data = Data(id = id, type = mediaType).toJson()
        return if (mediaType == "movie") {
            newMovieSearchResponse(t, data, TvType.Movie) { this.posterUrl = poster }
        } else {
            newTvSeriesSearchResponse(t, data, TvType.TvSeries) { this.posterUrl = poster }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val res = app.get("$tmdbApi/search/multi?api_key=$apiKey&query=$query")
            .parsedSafe<Results>() ?: return emptyList()
        return res.results?.mapNotNull { it.toSearchResponse() } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val data = parseJson<Data>(url)
        val type = getType(data.type)
        val isMovie = type == TvType.Movie

        val resUrl = if (isMovie)
            "$tmdbApi/movie/${data.id}?api_key=$apiKey&append_to_response=external_ids"
        else
            "$tmdbApi/tv/${data.id}?api_key=$apiKey&append_to_response=external_ids,seasons"

        return try {
            if (isMovie) {
                val res = app.get(resUrl).parsedSafe<MovieDetail>() ?: return null
                val imdbId = res.external_ids?.imdb_id ?: return null
                val title = res.title ?: res.original_title ?: return null
                newMovieLoadResponse(title, url, TvType.Movie, LinkData(imdbId, null, null).toJson()) {
                    this.posterUrl = getImageUrl(res.poster_path)
                    this.plot = res.overview
                    this.year = res.release_date?.take(4)?.toIntOrNull()
                }
            } else {
                val res = app.get(resUrl).parsedSafe<TvDetail>() ?: return null
                val imdbId = res.external_ids?.imdb_id ?: return null
                val title = res.name ?: res.original_name ?: return null
                val seasons = res.seasons?.filter { (it.season_number ?: 0) > 0 } ?: emptyList()
                val episodes = seasons.flatMap { season ->
                    val sNum = season.season_number ?: return@flatMap emptyList()
                    val epCount = season.episode_count ?: 0
                    (1..epCount).map { ep ->
                        newEpisode(LinkData(imdbId, sNum, ep).toJson()) {
                            this.season = sNum
                            this.episode = ep
                        }
                    }
                }
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = getImageUrl(res.poster_path)
                    this.plot = res.overview
                }
            }
        } catch (e: Exception) {
            Log.e("CineVault", "Error loading details", e)
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val linkData = parseJson<LinkData>(data)
        val imdbId = linkData.imdbId ?: return false
        val season = linkData.season
        val episode = linkData.episode
        val isMovie = season == null

        val streamUrl = if (isMovie) "$workerApi/streams/$imdbId"
                        else "$workerApi/streams/$imdbId/$season/$episode"

        Log.d("CineVault", "Fetching streams from: $streamUrl")

        return try {
            val response = app.get(streamUrl)
            if (!response.isSuccessful) {
                Log.e("CineVault", "API error: ${response.code}")
                return false
            }
            val responseText = response.text
            // Optional: log first 500 chars for debugging (can be removed in production)
            if (responseText.length > 500) {
                Log.d("CineVault", "Response (first 500 chars): ${responseText.take(500)}...")
            } else {
                Log.d("CineVault", "Response: $responseText")
            }

            val streams = parseJson<List<TorrentioStream>>(responseText)
            if (streams.isEmpty()) {
                Log.e("CineVault", "No streams in response")
                return false
            }

            var linkCount = 0
            streams.forEachIndexed { index, stream ->
                val title = stream.title ?: stream.name ?: "Unknown"
                val hash = stream.infoHash
                if (hash.isNullOrBlank()) {
                    Log.w("CineVault", "Stream $index missing infoHash, skipping")
                    return@forEachIndexed
                }
                val magnet = buildMagnetLink(hash)
                Log.d("CineVault", "Adding link: $title")
                callback(
                    newExtractorLink(name, title, magnet, INFER_TYPE) {
                        this.referer = ""
                        this.quality = getQualityFromName(title)
                    }
                )
                linkCount++
            }

            // Fetch subtitles (optional, don't fail if this errors)
            try {
                val subs = app.get("$workerApi/subtitles/$imdbId?lang=eng").parsedSafe<List<OpenSubtitle>>()
                subs?.forEach { sub ->
                    sub.url?.let { url ->
                        subtitleCallback(SubtitleFile("English", url))
                    }
                }
            } catch (e: Exception) {
                Log.e("CineVault", "Subtitle fetch failed", e)
            }

            if (linkCount == 0) {
                Log.e("CineVault", "No valid links after processing")
                false
            } else {
                Log.d("CineVault", "Successfully added $linkCount links")
                true
            }
        } catch (e: Exception) {
            Log.e("CineVault", "Exception in loadLinks", e)
            false
        }
    }

    private fun buildMagnetLink(hash: String): String {
        return "magnet:?xt=urn:btih:$hash" +
                "&tr=udp://tracker.opentrackr.org:1337/announce" +
                "&tr=udp://open.stealth.si:80/announce" +
                "&tr=udp://exodus.desync.com:6969/announce" +
                "&tr=udp://tracker.torrent.eu.org:451/announce"
    }

    // Data classes (unchanged)
    data class Data(val id: Int? = null, val type: String? = null)
    data class LinkData(val imdbId: String? = null, val season: Int? = null, val episode: Int? = null)
    data class Results(val results: List<Media>? = null)
    data class Media(
        val id: Int? = null,
        val title: String? = null,
        val name: String? = null,
        val original_title: String? = null,
        val original_name: String? = null,
        val poster_path: String? = null,
        val media_type: String? = null,
        val overview: String? = null,
        val release_date: String? = null
    )
    data class MovieDetail(
        val id: Int? = null,
        val title: String? = null,
        val original_title: String? = null,
        val poster_path: String? = null,
        val overview: String? = null,
        val release_date: String? = null,
        val external_ids: ExternalIds? = null
    )
    data class TvDetail(
        val id: Int? = null,
        val name: String? = null,
        val original_name: String? = null,
        val poster_path: String? = null,
        val overview: String? = null,
        val seasons: List<Season>? = null,
        val external_ids: ExternalIds? = null
    )
    data class Season(val season_number: Int? = null, val episode_count: Int? = null)
    data class ExternalIds(val imdb_id: String? = null)
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
