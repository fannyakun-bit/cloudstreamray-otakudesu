package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addEpisodes

class ExampleProvider : MainAPI() {
    override var mainUrl = "https://otakudesu.cloud" 
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
        val document = app.get("$mainUrl/?s=$query&post_type=anime").document
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
            document.select("h1.jdlsearc").text() 
        }
        val poster = document.select(".fotoanime img").attr("src")
        val plot = document.select(".sinopsc p").text()

        val episodes = document.select(".episodelist ul li").mapNotNull { element ->
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
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val iframeUrl = document.select("iframe").attr("src")

        if (iframeUrl.isNotEmpty()) {
            loadExtractor(iframeUrl, subtitleCallback, callback)
            return true
        }
        return false
    }
}
