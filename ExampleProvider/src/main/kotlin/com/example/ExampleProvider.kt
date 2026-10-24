package com.example

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder

private fun JSONObject.str(key: String): String? =
    if (isNull(key)) null else optString(key).trim().takeIf { it.isNotEmpty() }

class ExampleProvider : MainAPI() {
    override var mainUrl = "https://otakudesu.blog"
    override var name = "Otakudesu"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    override val mainPage = mainPageOf(
        "$mainUrl/ongoing-anime/page/" to "Ongoing Anime",
        "$mainUrl/complete-anime/page/" to "Completed Anime"
    )

    private val ajaxUrl get() = "$mainUrl/wp-admin/admin-ajax.php"
    private val ajaxHeaders = mapOf("X-Requested-With" to "XMLHttpRequest")
    private val episodeRegex = Regex("""Episode\s*(\d+)""", RegexOption.IGNORE_CASE)

    private fun b64(s: String): String =
        String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8)

    private fun cleanTitle(t: String): String =
        t.replace(Regex("""\s*Subtitle Indonesia\s*$""", RegexOption.IGNORE_CASE), "").trim()

    private fun getType(t: String): TvType = when {
        t.contains("OVA", true) || t.contains("Special", true) -> TvType.OVA
        t.contains("Movie", true) -> TvType.AnimeMovie
        else -> TvType.Anime
    }

    // ---------------------------------------------------------------- HOME

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = app.get("${request.data}$page/").document
            .select(".venz ul li")
            .mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst(".jdlflm")?.text()?.trim().orEmpty()
        val href = selectFirst("a")?.attr("href").orEmpty()
        if (title.isEmpty() || href.isEmpty()) return null
        val poster = selectFirst("img")?.attr("src")
        val epNum = selectFirst(".epz")?.text()
            ?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() }

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
            addSub(epNum)
        }
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

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val info = document.select(".infozingle p")

        fun getInfo(label: String): String? =
            info.firstOrNull { it.text().startsWith(label, ignoreCase = true) }
                ?.text()?.substringAfter(":")?.trim()

        val title = getInfo("Judul")
            ?: document.selectFirst(".jdlrx h1")?.text()?.let { cleanTitle(it) }
            ?: return null
        val poster = document.selectFirst(".fotoanime img")?.attr("src")
        val sitePlot = document.selectFirst(".sinopc")?.text()?.trim()?.takeIf { it.isNotEmpty() }
        val genres = document.select(".infozingle a[rel=tag]").map { it.text() }
        val year = getInfo("Tanggal Rilis")
            ?.let { Regex("""\d{4}""").find(it)?.value?.toIntOrNull() }
        val type = getType(getInfo("Tipe") ?: "TV")
        val status = if (getInfo("Status").equals("Ongoing", ignoreCase = true))
            ShowStatus.Ongoing else ShowStatus.Completed

        // Metadata tambahan (MAL / AniList / ani.zip). Kalau gagal, lanjut tanpa itu.
        val tracker = runCatching {
            APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year, true)
        }.getOrNull()
        val meta = fetchAniZip(tracker?.malId)
        val epMeta = meta?.optJSONObject("episodes")

        val episodes = document.select(".episodelist ul li").mapNotNull { li ->
            val a = li.selectFirst("a") ?: return@mapNotNull null
            val href = a.attr("href")
            if (!href.contains("/episode/")) return@mapNotNull null
            val epTitle = a.text().trim()
            var num = episodeRegex.find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
            if (type == TvType.AnimeMovie && num == null) num = 1

            val aniEp = num?.let { epMeta?.optJSONObject(it.toString()) }
            val aniTitle = aniEp?.optJSONObject("title")?.let { t -> t.str("en") ?: t.str("ja") }

            newEpisode(href) {
                this.name = aniTitle ?: if (num != null) "Episode $num" else epTitle
                this.episode = num
                this.posterUrl = aniEp?.str("image")
                this.description = aniEp?.str("overview")
            }
        }.distinctBy { it.data }

        val sorted = if (episodes.isNotEmpty() && episodes.all { it.episode != null })
            episodes.sortedBy { it.episode } else episodes.reversed()

        val recommendations = document.select(".isi-recommend-anime-series .isi-konten").mapNotNull {
            val recTitle = it.selectFirst(".judul-anime a")?.text()?.trim()
                ?: return@mapNotNull null
            val recHref = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val recPoster = it.selectFirst("img")?.attr("src")
            newAnimeSearchResponse(recTitle, recHref, TvType.Anime) {
                this.posterUrl = recPoster
            }
        }

        val backdrop = meta?.optJSONArray("images")?.let { arr ->
            (0 until arr.length())
                .mapNotNull { arr.optJSONObject(it) }
                .firstOrNull { it.optString("coverType") == "Fanart" }
                ?.str("url")
        } ?: tracker?.cover

        val titles = meta?.optJSONObject("titles")
        val plot = sitePlot
            ?: meta?.str("description")?.replace(Regex("<.*?>"), "")?.trim()

        return newAnimeLoadResponse(title, url, type) {
            this.engName = titles?.str("en") ?: title
            this.japName = titles?.str("ja") ?: titles?.str("x-jat")
            this.posterUrl = tracker?.image ?: poster
            this.backgroundPosterUrl = backdrop
            this.plot = plot
            this.tags = genres
            this.year = year
            this.showStatus = status
            this.recommendations = recommendations
            addEpisodes(DubStatus.Subbed, sorted)
            addMalId(tracker?.malId)
            addAniListId(tracker?.aniId?.toIntOrNull())
        }
    }

    private suspend fun fetchAniZip(malId: Int?): JSONObject? {
        if (malId == null) return null
        return runCatching {
            JSONObject(app.get("https://api.ani.zip/mappings?mal_id=$malId").text)
        }.getOrNull()
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
            val nonce = try {
                JSONObject(
                    app.post(
                        ajaxUrl,
                        data = mapOf("action" to nonceAction),
                        referer = data,
                        headers = ajaxHeaders
                    ).text
                ).getString("data")
            } catch (e: Exception) {
                null
            }

            if (nonce != null) {
                // semua mirror diproses paralel supaya cepat
                document.select(".mirrorstream ul li a[data-content]").amap { a ->
                    try {
                        resolveMirror(a, nonce, embedAction, data, subtitleCallback, cb)
                    } catch (e: Exception) {
                    }
                }
            }
        }

        return found
    }

    private suspend fun resolveMirror(
        a: Element,
        nonce: String,
        embedAction: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val server = a.text().trim().lowercase()
        if (server == "mega" || a.hasAttr("data-default")) return
        val payload = a.attr("data-content")
        if (payload.isBlank()) return

        val quality = Regex("""m(\d+)p""").find(a.parent()?.parent()?.className().orEmpty())
            ?.groupValues?.get(1)?.let { "${it}p" }.orEmpty()

        val params = JSONObject(b64(payload))
        val response = app.post(
            ajaxUrl,
            data = mapOf(
                "id" to params.get("id").toString(),
                "i" to params.get("i").toString(),
                "q" to params.optString("q"),
                "nonce" to nonce,
                "action" to embedAction
            ),
            referer = referer,
            headers = ajaxHeaders
        ).text

        val html = b64(JSONObject(response).getString("data"))
        val src = Jsoup.parse(html).selectFirst("iframe")?.attr("src").orEmpty()
        if (src.isNotBlank()) {
            handleEmbed(src, referer, quality, subtitleCallback, callback)
        }
    }

    // ------------------------------------------------------------ EXTRACTORS

    private suspend fun handleEmbed(
        rawUrl: String,
        referer: String,
        quality: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        depth: Int = 0
    ) {
        if (depth > 3) return
        val url = if (rawUrl.startsWith("//")) "https:$rawUrl" else rawUrl
        when {
            url.contains("filedon.co") -> extractFiledon(url, referer, quality, callback)
            url.contains("vidhide") || url.contains("earnvids") ->
                extractVidhide(url, referer, quality, callback)
            url.contains("desustream") ->
                extractDesustream(url, referer, quality, subtitleCallback, callback, depth)
            url.contains("blogger.com/video.g") ->
                extractBlogger(url, referer, "Blogger", callback)
            else -> loadExtractor(url, referer, subtitleCallback, callback)
        }
    }

    // vidhide / earnvids: skrip ter-pack (p,a,c,k,e,d) berisi link master.m3u8
    private suspend fun extractVidhide(
        url: String,
        referer: String,
        quality: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = app.get(url, referer = referer).text
        val script = unpackPacker(html) ?: html
        val origin = Regex("""^(https?://[^/]+)""").find(url)?.groupValues?.get(1) ?: url

        val link = Regex("""https?://[^"'\s\\]+?\.m3u8[^"'\s\\]*""").find(script)?.value
            ?: Regex("""["'](/[^"'\s]+\.m3u8[^"'\s]*)["']""").find(script)
                ?.groupValues?.get(1)?.let { origin + it }
            ?: return

        callback(
            newExtractorLink(
                this.name,
                "Vidhide",
                link,
                ExtractorLinkType.M3U8
            ) {
                this.quality = getQualityFromName(quality)
                this.referer = "$origin/"
            }
        )
    }

    private fun unpackPacker(html: String): String? {
        val m = Regex(
            """\}\('(.*?)',(\d+),(\d+),'([^']*)'\.split\('\|'\)""",
            RegexOption.DOT_MATCHES_ALL
        ).find(html) ?: return null

        var payload = m.groupValues[1].replace("\\'", "'").replace("\\\\", "\\")
        val base = m.groupValues[2].toInt()
        val count = m.groupValues[3].toInt()
        val dict = m.groupValues[4].split("|")

        var c = count
        while (c-- > 0) {
            val word = dict.getOrNull(c)
            if (!word.isNullOrEmpty()) {
                payload = payload.replace(
                    Regex("\\b" + c.toString(base) + "\\b"),
                    Regex.escapeReplacement(word)
                )
            }
        }
        return payload
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
                "Filedon",
                link,
                ExtractorLinkType.VIDEO
            ) {
                this.quality = getQualityFromName(label)
                this.referer = ""
            }
        )
    }

    // desustream (ondesu3, odcdn, odstream, ondesuhd, dst.): ada beberapa bentuk halaman,
    // jadi dicoba otomatis: iframe blogger -> link mp4/m3u8 langsung -> iframe lain
    private suspend fun extractDesustream(
        url: String,
        referer: String,
        quality: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        depth: Int
    ) {
        val res = app.get(url, referer = referer)
        val html = res.text
        val doc = res.document
        val origin = Regex("""^(https?://[^/]+)""").find(url)?.groupValues?.get(1) ?: url
        val server = Regex("""/dstream/([A-Za-z0-9]+)""").find(url)?.groupValues?.get(1)
            ?.replaceFirstChar { it.uppercase() } ?: "Desustream"

        val iframeSrc = doc.selectFirst("iframe")?.attr("src").orEmpty()

        // 1. pembungkus blogger (ondesu3)
        if (iframeSrc.contains("blogger.com/video.g")) {
            extractBlogger(iframeSrc, url, server, callback)
            return
        }

        // 2. link video langsung di dalam halaman (odcdn: mp4, lainnya mungkin m3u8)
        val direct = Regex("""https?://[^"'\s\\]+?\.mp4[^"'\s\\]*""").find(html)?.value
            ?: Regex("""https?://[^"'\s\\]+?\.m3u8[^"'\s\\]*""").find(html)?.value
        if (direct != null) {
            val label = Regex("""(\d{3,4})p""").find(direct)?.value ?: quality
            val isHls = direct.contains(".m3u8")
            callback(
                newExtractorLink(
                    this.name,
                    server,
                    direct,
                    if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.quality = getQualityFromName(label)
                    this.referer = "$origin/"
                }
            )
            return
        }

        // 3. iframe lain di dalamnya: diproses ulang (dengan batas kedalaman)
        if (iframeSrc.isNotBlank()) {
            handleEmbed(iframeSrc, url, quality, subtitleCallback, callback, depth + 1)
        }
    }

    // blogger: link googlevideo ada di data-p (ter-escape), harus diambil saat diputar
    private suspend fun extractBlogger(
        url: String,
        referer: String,
        label: String,
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

            callback(
                newExtractorLink(
                    this.name,
                    label,
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
