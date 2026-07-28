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

class HindiAnimeZoneProvider : MainAPI() {
    override var mainUrl = "https://hindianimezone.me"
    override var name = "HindiAnimeZone"
    override var lang = "hi"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Anime, TvType.TvSeries, TvType.Movie)

    override val mainPage = mainPageOf(
        "" to "Recent Animes & Cartoons",
        "/category/hindi/" to "Hindi Dubbed",
        "/category/tamil/" to "Tamil",
        "/category/telugu/" to "Telugu",
        "/category/bengali/" to "Bengali",
        "/category/malayalam/" to "Malayalam",
        "/category/kannada/" to "Kannada",
        "/category/english/" to "English"
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
        val home = doc.select(".td_module_wrap, .td-module-thumb, .td_module_3, .td_module_10, article.post").mapNotNull { toResult(it) }
        return newHomePageResponse(request.name, home, hasNext = home.isNotEmpty())
    }

    private fun toResult(post: Element): SearchResponse? {
        val titleEl = post.selectFirst(".entry-title a, .td-module-title a, h3 a, h2 a") ?: post.selectFirst("a")
        val url = titleEl?.attr("href") ?: return null
        val title = titleEl.text().ifEmpty { post.selectFirst("img")?.attr("alt") ?: "" }
        if (title.isEmpty()) return null

        val imageUrl = post.selectFirst("img.entry-thumb, .td-module-thumb img, img")?.let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        } ?: ""

        val imageWithUrl = "$url + $imageUrl"
        return newMovieSearchResponse(title, imageWithUrl, TvType.Anime) {
            this.posterUrl = imageUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query", allowRedirects = true, timeout = 30).document
        val searchResponse = doc.select(".td_module_wrap, .td-module-thumb, .td_module_3, article.post")
        return searchResponse.mapNotNull { toResult(it) }
    }

    override suspend fun load(url: String): LoadResponse {
        val parts = url.split(" + ")
        val pageUrl = parts[0]
        val posterFromUrl = if (parts.size > 1) parts[1] else ""

        val doc = app.get(pageUrl, allowRedirects = true, timeout = 30).document
        val title = doc.selectFirst("h1.entry-title, .single-post h1, h1")?.text()
            ?.replace("Download", "")
            ?.replace("Watch Online", "")
            ?.trim() ?: "HindiAnimeZone Title"

        val imageUrl = doc.selectFirst(".td-post-content img, .entry-content img, .td-post-featured-image img")?.let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        }?.ifEmpty { posterFromUrl } ?: posterFromUrl

        val plot = doc.select(".td-post-content p, .entry-content p").firstOrNull {
            it.text().lowercase().contains("synopsis") || it.text().lowercase().contains("story") || it.text().length > 30
        }?.text() ?: ""

        val downloadLinks = mutableListOf<Pair<String, String>>()
        doc.select(".download-container a, .quality-link, .quality-options a, .download-links a, .btn-download, .td-post-content a[href]").forEach { aTag ->
            val href = aTag.attr("href")
            val linkText = aTag.text().trim()
            if (href.isNotEmpty() && !href.startsWith("#") && !href.contains("javascript:") && !href.contains("telegram") && !href.contains("contact")) {
                val name = linkText.ifEmpty {
                    if (href.contains("480p")) "480p Quality"
                    else if (href.contains("720p")) "720p Quality"
                    else if (href.contains("1080p")) "1080p Quality"
                    else if (href.contains("4k")) "4K Quality"
                    else "Download Stream Link"
                }
                downloadLinks.add(Pair(name, href))
            }
        }

        val isSeries = title.lowercase().contains("season") || title.lowercase().contains("episode") || title.lowercase().contains("ep ")

        return if (isSeries) {
            val episodes = downloadLinks.mapIndexed { index, (name, link) ->
                newEpisode(link) {
                    this.name = name
                    this.episode = index + 1
                }
            }
            newTvSeriesLoadResponse(title, pageUrl, TvType.Anime, episodes) {
                this.posterUrl = imageUrl
                this.plot = plot
            }
        } else {
            val linkData = downloadLinks.joinToString("+") { it.second }
            newMovieLoadResponse(title, pageUrl, TvType.Anime, linkData) {
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
                if (link.contains("drive.google.com") || link.contains("mega.nz") || link.contains("gdflix") || link.contains("hubcloud") || link.contains("stream")) {
                    loadExtractor(link, subtitleCallback, callback)
                } else {
                    try {
                        val doc = app.get(link, allowRedirects = true, timeout = 15).document
                        val targetLink = doc.selectFirst("a.download-btn, a.btn, a[href*='http']")?.attr("href") ?: link
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
