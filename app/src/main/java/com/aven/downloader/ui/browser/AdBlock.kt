package com.aven.downloader.ui.browser

/**
 * A small built-in list of ad, pop-up and tracker hosts for the in-app browser (Settings → Block ads).
 * A request is blocked when its host is one of these or a sub-domain of one.
 */
object AdBlock {
    private val hosts: Set<String> = setOf(
        // Google / big ad networks
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com", "pagead2.googlesyndication.com",
        "googletagservices.com", "googletagmanager.com", "google-analytics.com", "adsense.google.com", "2mdn.net",
        "adnxs.com", "adsrvr.org", "adform.net", "amazon-adsystem.com", "advertising.com", "criteo.com", "criteo.net",
        "rubiconproject.com", "pubmatic.com", "openx.net", "casalemedia.com", "indexww.com", "smartadserver.com",
        "taboola.com", "outbrain.com", "revcontent.com", "mgid.com", "yieldmo.com", "sharethrough.com", "teads.tv",
        "moatads.com", "adsafeprotected.com", "scorecardresearch.com", "quantserve.com", "serving-sys.com", "bidswitch.net",
        "contextweb.com", "lijit.com", "sovrn.com", "media.net", "gumgum.com", "triplelift.com", "33across.com",
        // pop-under / aggressive video-site ad networks
        "popads.net", "popcash.net", "propellerads.com", "propu.sh", "onclickads.net", "clickadu.com", "hilltopads.net",
        "exoclick.com", "exosrv.com", "exdynsrv.com", "juicyads.com", "trafficjunky.net", "trafficstars.com", "tsyndicate.com",
        "adsterra.com", "adsterratools.com", "highperformancecpm.com", "highperformanceformat.com", "profitableratecpm.com",
        "ad-maven.com", "admaven.com", "a-ads.com", "adcash.com", "adskeeper.com", "adspyglass.com", "clkrev.com",
        "ero-advertising.com", "plugrush.com", "realsrv.com", "richpush.co", "pushame.com", "pushails.com", "notix.io",
        "monetag.com", "onesignal-ads.com", "bidgear.com", "evadav.com", "zeropark.com", "mondiad.com", "rtmark.net",
        "betweendigital.com", "ad.gt", "adtng.com", "adxpansion.com", "syndication.exoclick.com", "cdn4ads.com",
        "popunder.net", "popmyads.com", "adsco.re", "yllix.com", "a.o333o.com", "go.oclasrv.com", "vidcpm.com",
        "adnium.com", "ads.yahoo.com", "advertising.yahoo.com", "ads.twitter.com", "static.ads-twitter.com",
        "analytics.twitter.com", "ads.linkedin.com", "px.ads.linkedin.com", "ads.tiktok.com", "analytics.tiktok.com",
        "an.facebook.com", "pixel.facebook.com", "connect.facebook.net", "ads.pinterest.com", "log.pinterest.com",
        // trackers / analytics
        "hotjar.com", "mixpanel.com", "segment.io", "segment.com", "amplitude.com", "fullstory.com", "mouseflow.com",
        "crazyegg.com", "newrelic.com", "nr-data.net", "bugsnag.com", "branch.io", "adjust.com", "appsflyer.com",
        "kochava.com", "chartbeat.com", "chartbeat.net", "parsely.com", "demdex.net", "omtrdc.net", "everesttech.net",
        "krxd.net", "bluekai.com", "exelator.com", "rlcdn.com", "agkn.com", "mathtag.com", "tapad.com", "liadm.com",
        "yandex.ru/ads", "an.yandex.ru", "mc.yandex.ru", "counter.yadro.ru", "top-fwz1.mail.ru", "hit.gemius.pl",
        "statcounter.com", "histats.com", "clicky.com", "cnzz.com", "umeng.com", "baidu.com/hm.js", "hm.baidu.com"
    ).map { it.substringBefore('/') }.toSet()

    fun isBlocked(host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        var h = host.lowercase()
        while (true) {
            if (h in hosts) return true
            val dot = h.indexOf('.')
            if (dot < 0) return false
            h = h.substring(dot + 1)
            if (!h.contains('.')) return false   // don't test bare TLDs
        }
    }

    /** CSS that hides the usual empty ad boxes after a page loads. */
    const val HIDE_CSS = "ins.adsbygoogle,.adsbygoogle,[id^='google_ads'],[id^='div-gpt-ad'],[class*='ad-banner'],[class*='adbox']," +
        "[class*='popup-ad'],[id*='adContainer'],iframe[src*='doubleclick']{display:none!important;}"
}
