# App-specific R8/ProGuard rules.
# kotlinx.serialization ships its own consumer rules; add rules here only when a
# release build actually needs them (verified via :app:assembleRelease + smoke test).

# The packaged Mermaid document calls these names through WebView's JS bridge.
-keepclassmembers class app.hapi.companion.ui.markdown.MermaidBridge {
    @android.webkit.JavascriptInterface <methods>;
}
