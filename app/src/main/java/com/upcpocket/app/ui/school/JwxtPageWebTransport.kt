package com.upcpocket.app.ui.school

import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.upcpocket.app.data.school.LoginPageException
import com.upcpocket.app.data.school.fetchJwxtResource
import com.upcpocket.app.data.school.isJwxtPageUrl
import java.io.ByteArrayInputStream

/** Only this import WebView opts in; library/card loading is unchanged. */
internal fun jwxtPageGet(request: WebResourceRequest): WebResourceResponse? {
    val address = request.url.toString()
    if (request.method != "GET" || !isJwxtPageUrl(address)) return null
    return try {
        val cookies = CookieManager.getInstance()
        val result = fetchJwxtResource(address, request.requestHeaders, cookies::getCookie, setCookie = { url, value ->
            cookies.setCookie(url, value)
        })
        WebResourceResponse(result.mime, result.encoding, result.status, "Teaching system response",
            result.headers, ByteArrayInputStream(result.bytes))
    } catch (e: Exception) {
        val message = if (e is LoginPageException) e.message.orEmpty() else "教务页面连接失败，请重新打开入口后重试"
        WebResourceResponse("text/plain", "UTF-8", 502, "Teaching system connection failed",
            emptyMap(), ByteArrayInputStream(message.toByteArray(Charsets.UTF_8)))
    }
}

/** School homepage has a loaded but zero-height primary iframe on this WebView. */
internal const val JWXT_VIEWPORT_REPAIR = """
(function () {
  if (location.origin !== 'https://jwxt.upc.edu.cn' && location.origin !== 'http://jwxt.upc.edu.cn') return;
  if (location.pathname !== '/jsxsd/framework/xsMainV.htmlx') return;
  if (window.__upcPocketJwxtResize) { window.__upcPocketJwxtResize(); return; }
  function repair() {
    var h = Math.round(window.innerHeight);
    if (h < 100 || !document.body) return;
    Array.from(document.querySelectorAll('iframe')).forEach(function (f) {
      var u; try { u = new URL(f.src, location.href); } catch (_) { return; }
      if (u.origin !== location.origin || !u.pathname.startsWith('/jsxsd/') || getComputedStyle(f).display === 'none' || f.getBoundingClientRect().width < 16) return;
      if (f.getBoundingClientRect().height >= 16 && f.dataset.upcPocketAutoHeight !== '1') return;
      if (f.dataset.upcPocketAutoHeight === '1' && f.style.height === h + 'px' && f.style.minHeight === h + 'px') return;
      f.dataset.upcPocketAutoHeight = '1';
      document.documentElement.style.minHeight = h + 'px';
      document.body.style.minHeight = h + 'px';
      // The portal creates further 100%-height frames when a menu is clicked.
      // A min-height alone does not provide their percentage-height containing block.
      document.documentElement.style.height = h + 'px';
      document.body.style.height = h + 'px';
      f.style.setProperty('height', h + 'px', 'important');
      f.style.setProperty('min-height', h + 'px', 'important');
      f.style.setProperty('width', '100%', 'important');
    });
  }
  window.__upcPocketJwxtResize = repair;
  window.addEventListener('resize', repair);
  // The portal inserts or reveals a new pane after the user clicks a menu.
  var queued = false;
  new MutationObserver(function () {
    if (queued) return;
    queued = true;
    requestAnimationFrame(function () { queued = false; repair(); });
  }).observe(document.documentElement, {subtree:true, childList:true, attributes:true, attributeFilter:['style','src','class']});
  repair(); setTimeout(repair, 250); setTimeout(repair, 1000);
})();
"""
