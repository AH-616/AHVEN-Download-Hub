package com.aven.downloader

/**
 * SYNTHETIC yt-dlp style JSON written by hand to exercise the parsers.
 * It is NOT captured from real YouTube/Facebook responses.
 */
object TestData {
    /** DASH-style video: separate video-only and audio-only streams plus one combined 360p. */
    val dashJson = """
{"id":"abc","title":"Sample video","uploader":"Some Channel","duration":125.0,
 "thumbnail":"https://example.com/t.jpg","extractor_key":"Youtube","is_live":false,
 "formats":[
  {"format_id":"140","ext":"m4a","vcodec":"none","acodec":"mp4a.40.2","abr":129.5,"tbr":129.5,"filesize":2000000,
   "url":"https://cdn.example/a140","protocol":"https","http_headers":{"User-Agent":"UA","Accept-Encoding":"gzip, deflate"}},
  {"format_id":"18","ext":"mp4","vcodec":"avc1.42001E","acodec":"mp4a.40.2","height":360,"width":640,"tbr":500.0,
   "url":"https://cdn.example/v18","protocol":"https","http_headers":{"User-Agent":"UA"}},
  {"format_id":"137","ext":"mp4","vcodec":"avc1.640028","acodec":"none","height":1080,"width":1920,"tbr":4000.0,"filesize":60000000,
   "url":"https://cdn.example/v137","protocol":"https","http_headers":{"User-Agent":"UA"}},
  {"format_id":"136","ext":"mp4","vcodec":"avc1.4d401f","acodec":"none","height":720,"width":1280,"tbr":2000.0,"filesize":30000000,
   "url":"https://cdn.example/v136","protocol":"https","http_headers":{"User-Agent":"UA"}},
  {"format_id":"sb0","ext":"mhtml","vcodec":"none","acodec":"none","height":90,"url":"https://cdn.example/sb","protocol":"mhtml"},
  {"format_id":"hls-1","ext":"mp4","vcodec":"avc1","acodec":"mp4a","height":480,"url":"https://cdn.example/x.m3u8","protocol":"m3u8_native"}
 ]}
""".trimIndent()

    /** Facebook-style: two progressive files with audio+video (sd/hd). */
    val progressiveJson = """
{"id":"123","title":"FB clip","uploader":null,"duration":30,"extractor_key":"Facebook",
 "formats":[
  {"format_id":"sd","ext":"mp4","vcodec":"avc1.4D401E","acodec":"mp4a.40.2","height":360,"width":640,"url":"https://fb.example/sd.mp4","protocol":"https"},
  {"format_id":"hd","ext":"mp4","vcodec":"avc1.64001F","acodec":"mp4a.40.2","height":720,"width":1280,"url":"https://fb.example/hd.mp4","protocol":"https"}
 ]}
""".trimIndent()

    /** Only an audio stream, no heights at all. */
    val noHeightJson = """
{"id":"x","title":"No heights","duration":10,
 "formats":[{"format_id":"a","ext":"m4a","vcodec":"none","acodec":"mp4a","abr":96,"url":"https://cdn.example/a","protocol":"https"},
            {"format_id":"v","ext":"mp4","acodec":"mp4a","url":"https://cdn.example/v","protocol":"https"}]}
""".trimIndent()
}
