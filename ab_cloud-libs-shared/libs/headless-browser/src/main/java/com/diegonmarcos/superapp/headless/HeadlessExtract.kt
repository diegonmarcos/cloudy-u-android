package com.diegonmarcos.superapp.headless

/** The in-page scripts of the headless-api links and scrape commands (same logic as the container's extract.js). Bodies for WebViewEngine.js(). */
object HeadlessExtract {
    private const val VIS = "var vis=function(e){var r=e.getBoundingClientRect();var s=getComputedStyle(e);return r.width>0&&r.height>0&&s.visibility!=='hidden'&&s.display!=='none'};"

    fun linksScript(sameOrigin: Boolean, visibleOnly: Boolean): String = VIS +
        "var o=[],seen={};var as=document.querySelectorAll('a[href]');" +
        "for(var i=0;i<as.length;i++){var a=as[i];var u;try{u=new URL(a.getAttribute('href'),document.baseURI)}catch(e){continue}" +
        "if(u.protocol!=='http:'&&u.protocol!=='https:')continue;" +
        (if (sameOrigin) "if(u.origin!==location.origin)continue;" else "") +
        (if (visibleOnly) "if(!vis(a))continue;" else "") +
        "var h=u.href;if(seen[h])continue;seen[h]=1;" +
        "o.push({text:((a.innerText||a.textContent||'').trim().replace(/\\s+/g,' ')).slice(0,200),href:h})}" +
        "return {count:o.length,links:o}"

    /** [elementsJson] is the validated elements array (JSON, so it is a JS literal); [limit] per selector. */
    fun scrapeScript(elementsJson: String, limit: Int): String = VIS +
        "var els=$elementsJson;var lim=$limit;var res=[];" +
        "for(var i=0;i<els.length;i++){var el=els[i];var nodes=[];" +
        "try{nodes=Array.prototype.slice.call(document.querySelectorAll(el.selector))}catch(e){res.push({selector:el.selector,count:0,items:[],error:String(e)});continue}" +
        "var items=nodes.slice(0,lim).map(function(n){var at={};(el.attrs||[]).forEach(function(k){var v=n.getAttribute(k);if(v!==null)at[k]=v});" +
        "return {text:(n.innerText||n.textContent||'').trim(),html:n.outerHTML,attrs:at,visible:vis(n)}});" +
        "res.push({selector:el.selector,count:nodes.length,items:items})}" +
        "return {results:res}"
}
