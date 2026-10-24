package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder

class ExampleProvider : MainAPI() {
    override var mainUrl = "https://otakudesu.blog" 
    override var name = "Otakudesu"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Anime)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) "$mainUrl/ongoing-anime/" else "$mainUrl/ongoing-anime/page/$page/"
        val document = app.get(url).document
        
        val animeList = document.select(".venz ul li").mapNotNull { element ->
            val title = element.select(".jdlflm").text().ifEmpty { return@mapNotNull null }
            val href = element.select("a").attr("href")
            val poster = element.select("img").attr("src")

            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = poster
            }
        }
        return newHomePageResponse(listOf(HomePageList("Ongoing Anime", animeList)))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl/?s=$encodedQuery&post_type=anime").document
        
        return document.select(".chlist li, .ulannim li").mapNotNull { element ->
            val title = element.select("a").text().ifEmpty { return@mapNotNull null }
            val href = element.select("a").attr("href")
            val poster = element.select("img").attr("src")

            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.select(".fotoanime .infozin p:nth-child(1) span").text().ifEmpty { 
            document.select("h1.jdlsearc").text().ifEmpty { "Otakudesu Anime" }
        }
        val poster = document.select(".fotoanime img").attr("src")
        val plot = document.select(".sinopsc p").text()

        val episodeList = document.select(".episodelist ul li").mapNotNull { element ->
            val epTitle = element.select("a").text()
            val epUrl = element.select("a").attr("href")
            if (epUrl.isEmpty()) return@mapNotNull null
            
            newEpisode(epUrl) {
                this.name = epTitle
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            addEpisodes(DubStatus.Subbed, episodeList)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        var foundLink = false

        // 1. Ambil dari tag iframe utama
        val iframeUrl = document.select("iframe").attr("src")
        if (iframeUrl.isNotEmpty()) {
            if (loadExtractor(iframeUrl, subtitleCallback, callback)) {
                foundLink = true
            }
        }

        // 2. Ambil dari tempat embed player
        document.select(".responsive-embed-stream iframe, .stream-mirror iframe, #embed_holder iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotEmpty()) {
                if (loadExtractor(src, subtitleCallback, callback)) {
                    foundLink = true
                }
            }
        }

        // 3. Ambil dari opsi daftar mirror stream
        document.select(".mirrorstream ul li a").forEach { element ->
            val embedUrl = element.attr("data-content").ifEmpty { element.attr("href") }
            if (embedUrl.isNotEmpty() && embedUrl.startsWith("http")) {
                if (loadExtractor(embedUrl, subtitleCallback, callback)) {
                    foundLink = true
                }
            }
        }

        return foundLink
    }
}
