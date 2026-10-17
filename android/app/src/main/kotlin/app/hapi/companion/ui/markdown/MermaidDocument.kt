package app.hapi.companion.ui.markdown

import java.util.Base64

internal const val MERMAID_ORIGIN = "https://appassets.androidplatform.net"
internal const val MERMAID_ASSET = "/assets/mermaid/mermaid.min.js"

/** Cache by exact source and theme, never hash-only: different code blocks must
 * not inherit another diagram. Cached SVG avoids recompiling on chat re-entry. */
internal object MermaidCache {
    private data class Key(val source: String, val dark: Boolean)
    private val entries = LinkedHashMap<Key, String>(32, 0.75f, true)
    private var cost = 0
    @Synchronized fun get(source: String, dark: Boolean): String? = entries[Key(source, dark)]
    @Synchronized fun put(source: String, dark: Boolean, svg: String) {
        val key = Key(source, dark)
        entries.remove(key)?.let { cost -= it.length + source.length }
        if (svg.length + source.length > 2_000_000) return
        entries[key] = svg
        cost += svg.length + source.length
        while (entries.size > 48 || cost > 4_000_000) {
            val oldest = entries.entries.iterator().next()
            cost -= oldest.key.source.length + oldest.value.length
            entries.remove(oldest.key)
        }
    }
}

/** Base64 keeps arbitrary diagram text out of HTML/script syntax, including
 * Chinese, backticks and literal closing-script tags. Nothing is fetched. */
internal fun mermaidDocument(source: String, dark: Boolean, expanded: Boolean, cachedSvg: String?): String {
    fun encode(text: String) = Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))
    val payload = encode(cachedSvg ?: source)
    val engine = if (cachedSvg == null) "<script src=\"$MERMAID_ORIGIN$MERMAID_ASSET\"></script>" else ""
    return """<!doctype html>
<html><head><meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=10,user-scalable=${if (expanded) "yes" else "no"}">
<style>html,body{margin:0;padding:0;background:transparent;color:${if (dark) "#edf1f5" else "#242a30"};font-family:sans-serif}#diagram{padding:8px;box-sizing:border-box}svg{display:block;margin:auto}*{-webkit-tap-highlight-color:transparent}</style>
</head><body><div id="diagram"></div>$engine
<script>
(async function(){
  try {
    const value=new TextDecoder().decode(Uint8Array.from(atob('$payload'),c=>c.charCodeAt(0)));
    let svg;
    if (${cachedSvg != null}) { svg=value; } else {
      mermaid.initialize({startOnLoad:false,securityLevel:'strict',suppressErrorRendering:true,
        theme:'${if (dark) "dark" else "default"}',htmlLabels:false,fontFamily:'sans-serif',maxTextSize:50000,
        flowchart:{htmlLabels:false},sequence:{useMaxWidth:true}});
      svg=(await mermaid.render('hapi-diagram',value)).svg;
    }
    const host=document.getElementById('diagram');host.innerHTML=svg;
    const root=host.querySelector('svg');if(!root)throw new Error('Diagram produced no SVG');
    const box=root.viewBox.baseVal;
    const width=Math.max(1,box.width||root.getBoundingClientRect().width);
    const height=Math.max(1,box.height||root.getBoundingClientRect().height);
    root.style.maxWidth='none';root.style.width='100%';
    // SVG viewport units can resolve against the SVG's own unset viewport in
    // Android WebView. Use explicit CSS pixels from the actual browser size.
    function fit(){
      const available=Math.max(1,document.documentElement.clientWidth-16);
      root.style.height=Math.max(1,${if (expanded) "height*available/width" else "innerHeight-16"})+'px';
    }
    fit();window.addEventListener('resize',fit);
    root.setAttribute('preserveAspectRatio','xMidYMid meet');
    await document.fonts.ready;
    NativeMermaid.rendered(svg,width,height);
  } catch(error) { NativeMermaid.failed(String(error && error.message || error)); }
})();
</script></body></html>"""
}
