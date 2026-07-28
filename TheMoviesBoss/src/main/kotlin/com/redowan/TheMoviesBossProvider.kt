package com.redowan

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

class TheMoviesBossProvider : MainAPI() {
    override var mainUrl = "https://ww2.themoviesboss.blog"
    override var name = "TheMoviesBoss"
    override var lang = "hi"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    override val mainPage = mainPageOf(
        "" to "Latest Updates",
        "/genre/bollywood/" to "Bollywood Movies",
        "/genre/hollywood/" to "Hollywood Movies",
        "/genre/regional/" to "Regional Movies",
        "/genre/anime/" to "Anime"
    )

    override suspend fun getMainPage(
        page: Int, request: MainPageRequest
    ): HomePageResponse {
        val pageUrl = if (page == 1) {
            "$mainUrl${request.data}"
        } else {
            "$mainUrl${request.data}page/$page/"
        }
        val doc = app.get(pageUrl, allowRedirects = true, timeout = 30).document
        val home = doc.select("article.item, .items .item").mapNotNull { toResult(it) }
        return newHomePageResponse(request.name, home, hasNext = home.isNotEmpty())
    }

    private fun toResult(post: Element): SearchResponse? {
        val titleEl = post.selectFirst(".data h3 a, h3.title a, .title a") ?: post.selectFirst("a")
        val url = titleEl?.attr("href") ?: return null
        val title = titleEl.text().ifEmpty { post.selectFirst("img")?.attr("alt") ?: "" }
        if (title.isEmpty()) return null

        val imageUrl = post.selectFirst(".image img, .poster img, img")?.let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        } ?: ""

        val imageWithUrl = "$url + $imageUrl"
        val tvType = if (title.lowercase().contains("season") || title.lowercase().contains("episodes")) {
            TvType.TvSeries
        } else {
            TvType.Movie
        }

        return newMovieSearchResponse(title, imageWithUrl, tvType) {
            this.posterUrl = imageUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query", allowRedirects = true, timeout = 30).document
        val searchResponse = doc.select("article.item, .result-item, .items .item")
        return searchResponse.mapNotNull { toResult(it) }
    }

    override suspend fun load(url: String): LoadResponse {
        val parts = url.split(" + ")
        val pageUrl = parts[0]
        val posterFromUrl = if (parts.size > 1) parts[1] else ""

        val doc = app.get(pageUrl, allowRedirects = true, timeout = 30).document
        val title = doc.selectFirst("h1.entry-title, .single-title, h1")?.text()
            ?.replace("Download", "")
            ?.replace("Watch Online", "")
            ?.trim() ?: "TheMoviesBoss Content"

        val imageUrl = doc.selectFirst(".poster img, .entry-content img, article img")?.let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        }?.ifEmpty { posterFromUrl } ?: posterFromUrl

        val plot = doc.select(".entry-content p, .thecontent p").firstOrNull {
            it.text().lowercase().contains("storyline") || it.text().lowercase().contains("synopsis") || it.text().length > 30
        }?.text() ?: ""

        val downloadLinks = mutableListOf<Pair<String, String>>()
        doc.select(".entry-content a, .thecontent a, a.maxbutton, a.btn").forEach { aTag ->
            val href = aTag.attr("href")
            val linkText = aTag.text().trim()
            if (href.isNotEmpty() && !href.startsWith("#") && !href.contains("javascript:") && (
                href.contains("download") || href.contains("drive") || href.contains("hubcloud") || href.contains("gdflix") || href.contains("link") || linkText.lowercase().contains("download") || linkText.lowercase().contains("link") || linkText.lowercase().contains("720p") || linkText.lowercase().contains("1080p") || linkText.lowercase().contains("480p")
            )) {
                downloadLinks.add(Pair(linkText.ifEmpty { "Download / Stream Link" }, href))
            }
        }

        val isSeries = title.lowercase().contains("season") || title.lowercase().contains("episodes") || title.lowercase().contains("series")

        return if (isSeries) {
            val episodes = downloadLinks.mapIndexed { index, (name, link) ->
                newEpisode(link) {
                    this.name = name
                    this.episode = index + 1
                }
            }
            newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
                this.posterUrl = imageUrl
                this.plot = plot
            }
        } else {
            val linkData = downloadLinks.joinToString("+") { it.second }
            newMovieLoadResponse(title, pageUrl, TvType.Movie, linkData) {
                this.posterUrl = imageUrl
                this.plot = plot
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val links = data.split("+")
        links.forEach { link ->
            if (link.isNotEmpty()) {
                if (link.contains("drive.google.com") || link.contains("mega.nz") || link.contains("fastdrive") || link.contains("gdflix") || link.contains("hubcloud")) {
                    loadExtractor(link, subtitleCallback, callback)
                } else {
                    try {
                        val doc = app.get(link, allowRedirects = true, timeout = 15).document
                        val targetLink = doc.selectFirst("a.btn, a.download-btn, a[href*='http']")?.attr("href") ?: link
                        if (targetLink != link) {
                            loadExtractor(targetLink, subtitleCallback, callback)
                        } else {
                            callback.invoke(
                                newExtractorLink(
                                    name,
                                    name,
                                    url = link
                                )
                            )
                        }
                    } catch (e: Exception) {
                        callback.invoke(
                            newExtractorLink(
                                name,
                                name,
                                url = link
                            )
                        )
                    }
                }
            }
        }
        return true
    }
}
