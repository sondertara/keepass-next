/*
 * Copyright 2026 Jeremy Jamet / Kunzisoft.
 *
 * This file is part of KeePassDX.
 *
 *  KeePassDX is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  KeePassDX is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with KeePassDX.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.kunzisoft.keepass.credentialprovider.autofill

import android.util.Log
import com.kunzisoft.keepass.database.ContextualDatabase
import com.kunzisoft.keepass.database.search.SearchParameters
import com.kunzisoft.keepass.model.EntryInfo
import com.kunzisoft.keepass.model.SearchInfo

/**
 * Intelligent matching and ranking engine for Autofill and Credential Provider.
 *
 * Provides:
 * 1. Semantic keyword extraction from Android package names (applicationId) and Web domains.
 * 2. Multi-tier fallback matching when exact AppId / URL custom fields are absent.
 * 3. Confidence scoring and credential prioritization (Passkey / TOTP first).
 */
object AutofillMatchingEngine {

    private const val TAG = "AutofillMatchingEngine"

    /** Confidence threshold for heuristic matching (out of 100+). */
    const val MATCH_THRESHOLD = 40

    /** Known package name aliases for common domestic and international services. */
    val KNOWN_APP_ALIASES = mapOf(
        "com.tencent.mm" to listOf("wechat", "weixin", "tencent"),
        "com.tencent.mobileqq" to listOf("qq", "tencent"),
        "tv.danmaku.bili" to listOf("bilibili", "bili", "danmaku"),
        "com.bilibili.app.in" to listOf("bilibili", "bili"),
        "com.valvesoftware.android.steam.community" to listOf("steam", "valve"),
        "com.alibaba.aliexpresshd" to listOf("aliexpress", "alibaba"),
        "com.autonavi.minimap" to listOf("gaode", "amap", "autonavi"),
        "com.sina.weibo" to listOf("weibo", "sina"),
        "org.thunderdog.challegram" to listOf("telegram"),
        "com.x.android" to listOf("x", "twitter"),
        "com.twitter.android" to listOf("twitter", "x"),
        "com.zhiliaoapp.musically" to listOf("tiktok"),
        "com.ss.android.ugc.aweme" to listOf("douyin", "tiktok"),
        "com.xingin.xhs" to listOf("xiaohongshu", "xhs"),
        "com.google.android.youtube" to listOf("youtube"),
        "com.google.android.apps.docs" to listOf("drive", "google drive", "google"),
        "com.google.android.gm" to listOf("gmail", "google"),
        "com.zhihu.android" to listOf("zhihu"),
        "com.netease.cloudmusic" to listOf("netease", "cloudmusic")
    )

    /** Known browser packages whose package IDs must never be matched as target service brands. */
    val KNOWN_BROWSER_PACKAGES = setOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary",
        "com.google.android.apps.chrome",
        "com.google.android.apps.chrome_dev",
        "com.microsoft.emmx",
        "com.microsoft.emmx.beta",
        "com.microsoft.emmx.canary",
        "com.microsoft.emmx.dev",
        "org.mozilla.firefox",
        "org.mozilla.firefox_beta",
        "org.mozilla.fenix",
        "org.mozilla.focus",
        "com.brave.browser",
        "com.brave.browser_beta",
        "com.brave.browser_dev",
        "com.brave.browser_nightly",
        "com.opera.browser",
        "com.opera.mini.native",
        "com.opera.touch",
        "com.opera.gx",
        "com.sec.android.app.sbrowser",
        "com.sec.android.app.sbrowser.beta",
        "com.duckduckgo.mobile.android",
        "com.vivaldi.browser",
        "com.kiwibrowser.browser",
        "mark.via",
        "mark.via.gp",
        "alook.browser",
        "com.quark.browser",
        "com.ucmobile",
        "com.tencent.mtt",
        "com.baidu.browser.apps"
    )

    /** Common namespace prefixes in package names. */
    val NAMESPACE_PREFIXES = setOf(
        "com", "org", "net", "io", "cn", "app", "me", "tv", "cc", "xyz", "co", "ai", "dev", "edu", "gov", "uk", "de", "jp"
    )

    /** Common generic segments in package names that do not identify the service brand. */
    val GENERIC_SEGMENTS = setOf(
        "android", "mobile", "app", "apps", "client", "official", "community", "main", "core",
        "global", "in", "frontpage", "shopping", "music", "video", "mediaclient", "messenger",
        "browser", "play", "viewer", "reader", "lite", "pro", "hd", "free", "phone", "pad",
        "service", "services", "system", "tools", "utility", "login", "auth", "account",
        "portal", "web", "online", "network", "cloud", "view", "express", "go", "ugc"
    )

    /** Common top-level domains. */
    val COMMON_TLDS = setOf(
        "com", "org", "net", "io", "cn", "co", "uk", "de", "jp", "fr", "ru", "me", "ai",
        "app", "xyz", "info", "biz", "edu", "gov", "mil", "int", "eu", "ca", "au", "ch",
        "se", "nl", "no", "es", "it", "cc", "tv", "dev", "tech", "site", "online", "store"
    )

    /** Common subdomains indicating auth/login or infrastructure rather than brand. */
    val COMMON_SUBDOMAINS = setOf(
        "login", "auth", "account", "accounts", "sso", "id", "identity", "api", "www", "m",
        "mobile", "secure", "web", "portal", "dash", "dashboard", "admin", "signin", "signup",
        "oauth", "cas", "mail", "pass", "vault"
    )

    /**
     * Extracts candidate brand keywords from an Android package name (applicationId).
     *
     * Example:
     * - `com.spotify.music` -> `["spotify"]`
     * - `tv.danmaku.bili` -> `["bilibili", "bili", "danmaku"]`
     * - `com.github.android` -> `["github"]`
     * - `com.valvesoftware.android.steam.community` -> `["steam", "valve", "valvesoftware"]`
     */
    fun extractAppKeywords(applicationId: String?): List<String> {
        if (applicationId.isNullOrBlank()) return emptyList()
        val pkg = applicationId.trim().lowercase()

        // Browser package names should not match brand keywords for websites visited within them
        if (KNOWN_BROWSER_PACKAGES.contains(pkg)) {
            return emptyList()
        }

        val results = mutableListOf<String>()

        // 1. Check known aliases
        KNOWN_APP_ALIASES[pkg]?.let { aliases ->
            results.addAll(aliases)
        }

        // 2. Tokenize package segments
        val segments = pkg.split('.').filter { it.isNotBlank() }
        for (i in segments.indices) {
            val segment = segments[i]
            // Drop top-level namespaces
            if (i == 0 && NAMESPACE_PREFIXES.contains(segment)) continue
            // Drop generic words
            if (GENERIC_SEGMENTS.contains(segment)) continue

            val clean = segment.replace(Regex("[^a-zA-Z0-9]"), "")
            if (clean.length >= 2 && !NAMESPACE_PREFIXES.contains(clean) && !GENERIC_SEGMENTS.contains(clean)) {
                if (!results.contains(clean)) {
                    results.add(clean)
                }
            }
        }
        return results
    }

    /**
     * Extracts candidate brand keywords from a Web domain name.
     *
     * Example:
     * - `account.jetbrains.com` -> `["jetbrains", "jetbrains.com"]`
     * - `auth.tesla.com` -> `["tesla", "tesla.com"]`
     * - `login.live.com` -> `["live", "live.com"]`
     */
    fun extractDomainKeywords(webDomain: String?): List<String> {
        if (webDomain.isNullOrBlank()) return emptyList()
        var domain = webDomain.trim().lowercase()

        // Strip protocol
        if (domain.startsWith("https://")) domain = domain.substring(8)
        else if (domain.startsWith("http://")) domain = domain.substring(7)

        // Strip path and port
        domain = domain.substringBefore('/').substringBefore(':')

        val segments = domain.split('.').filter { it.isNotBlank() }
        if (segments.isEmpty()) return emptyList()

        val results = mutableListOf<String>()

        // Collect non-subdomain, non-TLD brand segments
        val brandSegments = segments.filter { segment ->
            !COMMON_SUBDOMAINS.contains(segment) && !COMMON_TLDS.contains(segment)
        }

        for (seg in brandSegments) {
            if (seg.length >= 2 && !results.contains(seg)) {
                results.add(seg)
            }
        }

        // Add root domain if multiple segments
        if (segments.size >= 2) {
            val rootDomain = segments.takeLast(2).joinToString(".")
            if (!results.contains(rootDomain)) {
                results.add(rootDomain)
            }
        }

        return results
    }

    /**
     * Calculates the relevance score for an entry against search candidate keywords.
     */
    fun calculateRelevance(
        entry: EntryInfo,
        keywords: List<String>,
        rawIdentifier: String?
    ): Int {
        var score = 0
        val title = entry.title.trim().lowercase()
        val url = entry.url.trim().lowercase()

        for (kw in keywords) {
            val keyword = kw.lowercase()
            if (keyword.isBlank()) continue

            // 1. Title matching
            when {
                title == keyword -> score += 100 // Exact title match
                title.startsWith("$keyword ") || title.startsWith("$keyword-") || title.startsWith("${keyword}_") -> score += 85
                title.endsWith(" $keyword") -> score += 75
                title.contains(keyword) -> score += 60
            }

            // 2. URL matching
            if (url.isNotBlank()) {
                when {
                    url.contains("://$keyword.") || url.contains(".$keyword.") -> score += 90
                    url.contains(keyword) -> score += 50
                }
            }
        }

        // 3. Raw identifier check (e.g. entry notes or custom fields contain app package)
        if (!rawIdentifier.isNullOrBlank()) {
            val raw = rawIdentifier.lowercase()
            if (url.contains(raw)) score += 80
            if (title.contains(raw)) score += 60
        }

        // 4. Quality badges
        if (entry.passkey != null) {
            score += 25
        }
        if (entry.otpModel != null || entry.getOtpToken() != null) {
            score += 15
        }
        if (entry.username.isNotBlank()) {
            score += 10
        }

        return score
    }

    /**
     * Ranks already found entries based on keyword relevance and credential completeness (Passkey/2FA first).
     */
    fun rankEntries(
        entries: List<EntryInfo>,
        searchInfo: SearchInfo?
    ): List<EntryInfo> {
        if (entries.size <= 1) return entries

        val keywords = when {
            !searchInfo?.webDomain.isNullOrBlank() -> extractDomainKeywords(searchInfo?.webDomain)
            !searchInfo?.relyingParty.isNullOrBlank() -> extractDomainKeywords(searchInfo?.relyingParty)
            !searchInfo?.applicationId.isNullOrBlank() -> extractAppKeywords(searchInfo?.applicationId)
            else -> emptyList()
        }

        val rawId = when {
            !searchInfo?.webDomain.isNullOrBlank() -> searchInfo?.webDomain
            !searchInfo?.relyingParty.isNullOrBlank() -> searchInfo?.relyingParty
            else -> searchInfo?.applicationId
        }

        return entries.sortedWith(
            compareByDescending<EntryInfo> { calculateRelevance(it, keywords, rawId) }
                .thenByDescending { it.passkey != null }
                .thenByDescending { it.otpModel != null || it.getOtpToken() != null }
                .thenByDescending { it.lastModificationTime.toMilliseconds() }
        )
    }

    /**
     * Performs a multi-keyword fallback search in the database when Tier 1 exact search yields 0 items.
     */
    fun findFallbackMatches(
        database: ContextualDatabase,
        searchInfo: SearchInfo
    ): List<EntryInfo> {
        val keywords = when {
            !searchInfo.webDomain.isNullOrBlank() -> extractDomainKeywords(searchInfo.webDomain)
            !searchInfo.relyingParty.isNullOrBlank() -> extractDomainKeywords(searchInfo.relyingParty)
            !searchInfo.applicationId.isNullOrBlank() -> extractAppKeywords(searchInfo.applicationId)
            else -> emptyList()
        }

        if (keywords.isEmpty()) return emptyList()

        val rawIdentifier = when {
            !searchInfo.webDomain.isNullOrBlank() -> searchInfo.webDomain
            !searchInfo.relyingParty.isNullOrBlank() -> searchInfo.relyingParty
            else -> searchInfo.applicationId
        }
        val matchedEntriesMap = mutableMapOf<String, EntryInfo>()

        for (keyword in keywords) {
            if (keyword.length < 2) continue

            val searchParams = SearchParameters().apply {
                searchQuery = keyword
                searchInTitles = true
                searchInUrls = true
                searchInAppIds = false
                searchInUsernames = false
                searchInPasswords = false
                searchInNotes = false
                searchInOther = false
                searchInTags = false
                searchInExpired = false
                searchInTemplates = false
                searchInRecycleBin = false
                searchInSearchableGroup = true
                allowEmptyQuery = false
            }

            try {
                val searchGroup = database.createSearchGroupInfo(searchParams, max = 50)
                for (entry in searchGroup.getSearchResults()) {
                    val key = entry.nodeId.toString()
                    if (!matchedEntriesMap.containsKey(key)) {
                        matchedEntriesMap[key] = entry
                    }
                }
            } catch (e: Exception) {
                logWarn(TAG, "Failed fallback search for keyword '$keyword'", e)
            }
        }

        if (matchedEntriesMap.isEmpty()) return emptyList()

        // Score and filter candidates against the threshold
        val scoredList = matchedEntriesMap.values.map { entry ->
            val score = calculateRelevance(entry, keywords, rawIdentifier)
            entry to score
        }.filter { it.second >= MATCH_THRESHOLD }

        logDebug(TAG, "Found ${scoredList.size} smart autofill fallback matches for $searchInfo")

        return scoredList
            .sortedWith(
                compareByDescending<Pair<EntryInfo, Int>> { it.second }
                    .thenByDescending { it.first.passkey != null }
                    .thenByDescending { it.first.otpModel != null || it.first.getOtpToken() != null }
                    .thenByDescending { it.first.lastModificationTime.toMilliseconds() }
            )
            .map { it.first }
    }

    private fun logDebug(tag: String, msg: String) {
        try {
            Log.d(tag, msg)
        } catch (_: Throwable) {
            // Ignored in plain JVM unit tests
        }
    }

    private fun logWarn(tag: String, msg: String, tr: Throwable?) {
        try {
            Log.w(tag, msg, tr)
        } catch (_: Throwable) {
            // Ignored in plain JVM unit tests
        }
    }
}
