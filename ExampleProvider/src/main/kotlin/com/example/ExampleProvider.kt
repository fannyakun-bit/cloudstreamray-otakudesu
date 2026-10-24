package com.example

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder

class ExampleProvider : MainAPI() {
    override var mainUrl = "https://otakudesu.blog"
    override var name = "Otakudesu"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Anime)

    private val ajaxUrl get() = "$mainUrl/wp-admin/admin-ajax.php"
    private val ajaxHeaders = mapOf("X-Requested-With" to "XMLHttpRequest")

    private fun b64(s: String): String =
        String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8)

    private fun cleanTitle(t: String): String =
        t.replace(Regex("""\s*Subtitle Indonesia\s*$""", RegexOption.IGNORE_CASE), "").trim()

    // ---------------------------------------------------------------- HOME

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) "$mainUrl/ongoing-anime/" else "$mainUrl/ongoing-anime/page/$page/"
        val document = app.get(url).document

        val list = document.select(".venz ul li").mapNotNull { el ->
            val title = el.selectFirst(".jdlflm")?.text()?.trim().orEmpty()
            val href = el.selectFirst("a")?.attr("href").orEmpty()
            if (title.isEmpty() || href.isEmpty()) return@mapNotNull null
            val poster = el.selectFirst("img")?.attr("src")

            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = poster
            }
        }
        return newHomePageResponse(listOf(HomePageList("Ongoing Anime", list)))
    }

    // -------------------------------------------------------------- SEARCH

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl/?s=$q&post_type=anime").document

        return document.select("ul.chivsrc li").mapNotNull { el ->
            val a = el.selectFirst("h2 a") ?: return@mapNotNull null
            val title = cleanTitle(a.text())
            val href = a.attr("href")
            if (title.isEmpty() || href.isEmpty()) return@mapNotNull null
            val poster = el.selectFirst("img")?.attr("src")

            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = poster
            }
        }
    }

    // ---------------------------------------------------------------- LOAD

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val info = document.select(".infozingle p")

        fun getInfo(label: String): String? =
            info.firstOrNull { it.text().startsWith(label, ignoreCase = true) }
                ?.text()?.substringAfter(":")?.trim()

        val title = getInfo("Judul")
            ?: document.selectFirst(".jdlrx h1")?.text()?.let { cleanTitle(it) }
            ?: "Otakudesu"
        val poster = document.selectFirst(".fotoanime img")?.attr("src")
        val plot = document.selectFirst(".sinopc")?.text()?.trim()?.takeIf { it.isNotEmpty() }
        val genres = document.select(".infozingle a[rel=tag]").map { it.text() }
        val year = getInfo("Tanggal Rilis")
            ?.let { Regex("""\d{4}""").find(it)?.value?.toIntOrNull() }
        val status = if (getInfo("Status").equals("Ongoing", ignoreCase = true))
            ShowStatus.Ongoing else ShowStatus.Completed

        val epRegex = Regex("""Episode\s+(\d+)""", RegexOption.IGNORE_CASE)
        val episodes = document.select(".episodelist ul li").mapNotNull { li ->
            val a = li.selectFirst("a") ?: return@mapNotNull null
            val href = a.attr("href")
            if (!href.contains("/episode/")) return@mapNotNull null
            val epTitle = a.text().trim()
            val num = epRegex.find(epTitle)?.groupValues?.get(1)?.toIntOrNull()

            newEpisode(href) {
                this.name = if (num != null) "Episode $num" else epTitle
                this.episode = num
            }
        }.distinctBy { it.data }

        val sorted = if (episodes.isNotEmpty() && episodes.all { it.episode != null })
            episodes.sortedBy { it.episode } else episodes.reversed()

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = genres
            this.year = year
            this.showStatus = status
            addEpisodes(DubStatus.Subbed, sorted)
        }
    }

    // ----------------------------------------------------------- LOADLINKS

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val cb: (ExtractorLink) -> Unit = {
            found = true
            callback(it)
        }

        val res = app.get(data)
        val document = res.document
        val raw = res.text

        // 1. Player bawaan (ondesu3 -> desustream -> blogger)
        val defaultSrc = document.selectFirst("#embed_holder iframe")?.attr("src").orEmpty()
        if (defaultSrc.isNotBlank()) {
            try {
                handleEmbed(defaultSrc, data, "", subtitleCallback, cb)
            } catch (e: Exception) {
            }
        }

        // 2. Mirror lain lewat admin-ajax (nama action diambil dari script halaman)
        val nonceAction = Regex("""data:\{action:\x22([0-9a-f]+)\x22\}""")
            .find(raw)?.groupValues?.get(1)
        val embedAction = Regex("""nonce:[\w.]+,action:\x22([0-9a-f]+)\x22""")
            .find(raw)?.groupValues?.get(1)

        if (nonceAction != null && embedAction != null) {
            var nonce: String? = null

            for (ul in document.select(".mirrorstream ul")) {
                val quality = Regex("""m(\d+)p""").find(ul.className())
                    ?.groupValues?.get(1)?.let { "${it}p" }.orEmpty()

                for (a in ul.select("li a")) {
                    val server = a.text().trim().lowercase()
                    if (server == "mega" || server == "ondesu3") continue
                    val payload = a.attr("data-content")
                    if (payload.isBlank()) continue

                    try {
                        val params = JSONObject(b64(payload))

                        if (nonce == null) {
                            val r = app.post(
                                ajaxUrl,
                                data = mapOf("action" to nonceAction),
                                referer = data,
                                headers = ajaxHeaders
                            ).text
                            nonce = JSONObject(r).getString("data")
                        }
                        val n = nonce ?: continue

                        val r2 = app.post(
                            ajaxUrl,
                            data = mapOf(
                                "id" to params.get("id").toString(),
                                "i" to params.get("i").toString(),
                                "q" to params.optString("q"),
                                "nonce" to n,
                                "action" to embedAction
                            ),
                            referer = data,
                            headers = ajaxHeaders
                        ).text

                        val html = b64(JSONObject(r2).getString("data"))
                        val src = Jsoup.parse(html).selectFirst("iframe")?.attr("src").orEmpty()
                        if (src.isNotBlank()) {
                            handleEmbed(src, data, quality, subtitleCallback, cb)
                        }
                    } catch (e: Exception) {
                    }
                }
            }
        }

        return found
    }

    // ------------------------------------------------------------ EXTRACTORS

    private suspend fun handleEmbed(
        rawUrl: String,
        referer: String,
        quality: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val url = if (rawUrl.startsWith("//")) "https:$rawUrl" else rawUrl
        when {
            url.contains("filedon.co") -> extractFiledon(url, referer, quality, callback)
            url.contains("desustream") -> extractDesustream(url, referer, subtitleCallback, callback)
            url.contains("blogger.com/video.g") -> extractBlogger(url, referer, callback)
            else -> loadExtractor(url, referer, subtitleCallback, callback)
        }
    }

    // filedon: link mp4 langsung ada di atribut data-page (props.url), berlaku +- 1 jam
    private suspend fun extractFiledon(
        url: String,
        referer: String,
        quality: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = app.get(url, referer = referer).document
        val json = doc.selectFirst("#app")?.attr("data-page") ?: return
        val props = JSONObject(json).getJSONObject("props")
        val link = props.optString("url")
        if (link.isBlank()) return

        val fileName = props.optJSONObject("files")?.optString("name").orEmpty()
        val label = Regex("""(\d{3,4})p""").find(fileName)?.value ?: quality

        callback(
            newExtractorLink(
                this.name,
                "Filedon $label".trim(),
                link,
                ExtractorLinkType.VIDEO
            ) {
                this.quality = getQualityFromName(label)
                this.referer = ""
            }
        )
    }

    // desustream (ondesu3): halaman pembungkus berisi iframe blogger
    private suspend fun extractDesustream(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = app.get(url, referer = referer).document
        val src = doc.selectFirst("iframe")?.attr("src").orEmpty()
        when {
            src.contains("blogger.com/video.g") -> extractBlogger(src, url, callback)
            src.isNotBlank() -> loadExtractor(src, url, subtitleCallback, callback)
        }
    }

    // blogger: link googlevideo ada di data-p (ter-escape), harus diambil saat diputar
    private suspend fun extractBlogger(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val raw = app.get(url, referer = referer).text
            .replace("&quot;", "\"")
            .replace("\\u003d", "=")
            .replace("\\u0026", "&")

        val links = Regex("""https://[\w.-]+\.googlevideo\.com/videoplayback\?[^"\\\s]+""")
            .findAll(raw)
            .map { it.value }
            .filter { it.contains("itag=") }
            .distinct()
            .toList()

        for (link in links) {
            val itag = Regex("""itag=(\d+)""").find(link)?.groupValues?.get(1)?.toIntOrNull()
            val q = when (itag) {
                18 -> 360
                59 -> 480
                22 -> 720
                37 -> 1080
                else -> Qualities.Unknown.value
            }
            val label = if (q > 0) "${q}p" else ""

            callback(
                newExtractorLink(
                    this.name,
                    "Ondesu3 $label".trim(),
                    link,
                    ExtractorLinkType.VIDEO
                ) {
                    this.quality = q
                    this.referer = "https://www.blogger.com/"
                }
            )
        }
    }
}
