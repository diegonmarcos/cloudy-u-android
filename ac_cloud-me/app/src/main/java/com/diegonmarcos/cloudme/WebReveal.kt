package com.diegonmarcos.cloudme

/**
 * #1938 ROOT CAUSE of the Personal page reading black: the landpage keeps every `.animated-section` at opacity 0 / visibility hidden until an IntersectionObserver with
 * threshold 0.95 sees the WHOLE section on screen. In the app the page's WebView is a pane between the strips and the bottom nav, shorter than the landpage's hero, so the hero
 * never reaches 95% visible and the page stays an empty dark canvas (a taller screen shows it, a shorter pane never does).
 * Fix, for every embedded page: once it has loaded, the sections the page still holds hidden while any part of them is inside the viewport are shown, now, after each
 * scroll and for a few seconds (the page's own observer may hide them again). [JS] is that script; WebPage runs it from onPageFinished.
 */
object WebReveal {
    const val SELECTOR = ".animated-section,.reveal,[data-animate]"
    const val JS = "(function(){if(window.__cmReveal)return 0;window.__cmReveal=1;" +
        "function show(){var n=0,h=window.innerHeight||document.documentElement.clientHeight;" +
        "document.querySelectorAll('$SELECTOR').forEach(function(e){var c=getComputedStyle(e);" +
        "if(c.visibility==='hidden'||parseFloat(c.opacity)===0){var r=e.getBoundingClientRect();" +
        "if(r.bottom>0&&r.top<h){e.style.opacity='1';e.style.visibility='visible';e.style.transform='none';n++}}});return n}" +
        "var k=0,t=setInterval(function(){show();if(++k>8)clearInterval(t)},700);" +
        "var q=0;window.addEventListener('scroll',function(){if(q)return;q=setTimeout(function(){q=0;show()},250)},{passive:true});" +
        "return show()})()"
}
