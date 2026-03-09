package com.cinevault

import android.content.Context
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
    override var mainUrl = "https://cinevault-api.imrannn68d-de2.workers.dev"
    override var name = "CineVault"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = mutableListOf<HomePageList>()
        val trending = app.get("$mainUrl/trending/all/week?page=$page").parsed<TmdbResponse>()
        items.add(HomePageList("Trending", trending.results.map { it.toSearchResponse() }, true))
        val popular = app.get("$mainUrl/movie/popular?page=$page").parsed<TmdbResponse>()
        items.add(HomePageList("Popular Movies", popular.results.map { it.toSearchResponse() }))
        val tv = app.get("$mainUrl/tv/popular?page=$page").parsed<TmdbResponse>()
        items.add(HomePageList("Popular TV Shows", tv.results.map { it.toSearchResponse() }))
        return newHomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val res = app.get("$mainUrl/search/multi?query=$query").parsed<TmdbResponse>()
        return res.results.mapNotNull {
            if (it.media_type == "person") null else it.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val data = parseJson<TmdbItem>(url)
        val id = data.id ?: return null
        val isMovie = data.media_type == "movie" || data.title != null

        return if (isMovie) {
            val detail = app.get("$mainUrl/movie/$id?append_to_response=external_ids").parsed<TmdbDetail>()
            val imdbId = detail.external_ids?.imdb_id
            newMovieLoadResponse(
                detail.title ?: detail.name ?: "",
                url,
                TvType.Movie,
                "$imdbId|movie"
            ) {
                this.posterUrl = "https://image.tmdb.org/t/p/w500${detail.poster_path}"
                this.plot = detail.overview
                this.year = detail.release_date?.take(4)?.toIntOrNull()
            }
        } else {
            val detail = app.get("$mainUrl/tv/$id?append_to_response=external_ids").parsed<TmdbTvDetail>()
            val imdbId = detail.external_ids?.imdb_id
            val seasons = detail.seasons?.filter { it.season_number > 0 } ?: emptyList()
            newTvSeriesLoadResponse(
                detail.name ?: "",
                url,
                TvType.TvSeries,
                seasons.flatMap { season ->
                    (1..(season.episode_count ?: 0)).map { ep ->
                        newEpisode("$imdbId|tv|${season.season_number}|$ep") {
                            this.season = season.season_number
                            this.episode = ep
                        }
                    }
                }
            ) {
                this.posterUrl = "https://image.tmdb.org/t/p/w500${detail.poster_path}"
                this.plot = detail.overview
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("|")
        val imdbId = parts[0]
        val isMovie = parts[1] == "movie"

        val streamUrl = if (isMovie) "$mainUrl/streams/$imdbId"
        else "$mainUrl/streams/$imdbId/${parts[2]}/${parts[3]}"

        val streams = app.get(streamUrl).parsed<List<TorrentioStream>>()
        streams.forEach { stream ->
            val title = stream.title ?: stream.name ?: "Unknown"
            val hash = stream.infoHash ?: return@forEach
            val magnetUrl = "magnet:?xt=urn:btih:$hash" +
                "&tr=udp://tracker.opentrackr.org:1337/announce" +
                "&tr=udp://open.stealth.si:80/announce" +
                "&tr=udp://exodus.desync.com:6969/announce" +
                "&tr=udp://tracker.torrent.eu.org:451/announce"
            callback(
                newExtractorLink(name, title, magnetUrl) {
                    this.quality = getQualityFromName(title)
                }
            )
        }

        try {
            val subs = app.get("$mainUrl/subtitles/$imdbId?lang=eng").parsed<List<OpenSubtitle>>()
            subs.forEach { sub ->
                subtitleCallback(SubtitleFile("English", sub.url ?: return@forEach))
            }
        } catch (e: Exception) { }

        return true
    }

    private fun TmdbItem.toSearchResponse(): SearchResponse {
        val isMovie = media_type == "movie" || title != null
        val t = title ?: name ?: ""
        val poster = "https://image.tmdb.org/t/p/w500$poster_path"
        val dataJson = toJson()
        return if (isMovie) {
            newMovieSearchResponse(t, dataJson, TvType.Movie) { this.posterUrl = poster }
        } else {
            newTvSeriesSearchResponse(t, dataJson, TvType.TvSeries) { this.posterUrl = poster }
        }
    }

    data class TmdbResponse(val results: List<TmdbItem>)
    data class TmdbItem(
        val id: Int? = null,
        val title: String? = null,
        val name: String? = null,
        val poster_path: String? = null,
        val media_type: String? = null,
        val overview: String? = null,
        val release_date: String? = null
    )
    data class TmdbDetail(
        val title: String? = null,
        val name: String? = null,
        val poster_path: String? = null,
        val overview: String? = null,
        val release_date: String? = null,
        val external_ids: ExternalIds? = null
    )
    data class TmdbTvDetail(
        val name: String? = null,
        val poster_path: String? = null,
        val overview: String? = null,
        val seasons: List<TmdbSeason>? = null,
        val external_ids: ExternalIds? = null
    )
    data class TmdbSeason(val season_number: Int, val episode_count: Int? = null)
    data class ExternalIds(val imdb_id: String? = null)
    data class TorrentioStream(
        val title: String? = null,
        val name: String? = null,
        val url: String? = null,
        val infoHash: String? = null,
        val fileIdx: Int? = null
    )
    data class OpenSubtitle(val url: String? = null)
}
