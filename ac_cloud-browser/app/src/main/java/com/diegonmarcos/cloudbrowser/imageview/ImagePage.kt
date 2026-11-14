package com.diegonmarcos.cloudbrowser.imageview

/** One picture the image page can show: where the page loads it from, and what the info panel says about it. */
data class ImgEntry(
    val url: String,
    val name: String,
    val size: Long,
    val mime: String,
    val exif: List<Pair<String, String>> = emptyList(),
)

/**
 * #1741 the simple image page Cloud Browser shows for a picture another app hands it (Open with / Share).
 * Pure functions, no Android types: the page is one self-contained HTML string (fit / zoom / pan, rotate, previous / next
 * over [ImgEntry] list, info panel with size, dimensions and EXIF basics). The picture is an <img>: a SVG shown that way
 * never runs its scripts, and an animated GIF / WebP keeps animating. A type the web view cannot decode shows a reason
 * instead of a blank page. The print style shows the picture alone, fitted to the sheet, for Print and Convert to PDF.
 */
object ImagePage {
    /** The extensions of the image types the manifest claims (declared in build.json::image_viewer.extensions; a test holds them equal). */
    val EXTENSIONS: Set<String> = setOf("png", "jpg", "jpeg", "jpe", "jfif", "gif", "webp", "bmp", "svg", "heic", "heif", "avif", "ico", "wbmp")

    fun isImage(mime: String?, name: String?): Boolean {
        val m = mime?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        if (m.startsWith("image/")) return true
        if (m.isNotEmpty() && m != "application/octet-stream" && m != "*/*") return false
        return name?.substringAfterLast('.', "")?.lowercase().orEmpty() in EXTENSIONS
    }

    fun isImageName(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in EXTENSIONS

    fun escapeHtml(s: String): String = buildString(s.length + 8) {
        for (c in s) when (c) {
            '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;"); '"' -> append("&quot;")
            else -> append(c)
        }
    }

    /** A JSON string literal that is also safe inside a <script> block. */
    fun jsString(s: String): String = buildString(s.length + 2) {
        append('"')
        for (c in s) when {
            c == '"' -> append("\\\""); c == '\\' -> append("\\\\"); c == '\n' -> append("\\n"); c == '\r' -> append("\\r")
            c == '<' -> append("\\u003c"); c == '>' -> append("\\u003e"); c == '&' -> append("\\u0026")
            c.code < 0x20 -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
        append('"')
    }

    fun entriesJson(entries: List<ImgEntry>): String = entries.joinToString(",", "[", "]") { e ->
        "{\"url\":${jsString(e.url)},\"name\":${jsString(e.name)},\"size\":${e.size},\"mime\":${jsString(e.mime)}," +
            "\"exif\":" + e.exif.joinToString(",", "[", "]") { (k, v) -> "[${jsString(k)},${jsString(v)}]" } + "}"
    }

    fun html(entries: List<ImgEntry>, index: Int): String {
        val i = index.coerceIn(0, (entries.size - 1).coerceAtLeast(0))
        val title = entries.getOrNull(i)?.name ?: "image"
        return TEMPLATE.replace("__TITLE__", escapeHtml(title)).replace("__INDEX__", i.toString()).replace("__ENTRIES__", entriesJson(entries))
    }

    private const val TEMPLATE = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<title>__TITLE__</title>
<style>
html,body{margin:0;height:100%;overflow:hidden;background:#101214;color:#e8e8e8;font:14px system-ui,sans-serif}
#stage{position:fixed;inset:0;touch-action:none;overflow:hidden}
#img{position:absolute;left:50%;top:50%;transform-origin:center center;max-width:none;user-select:none;-webkit-user-drag:none}
#bar{position:fixed;left:0;right:0;bottom:0;display:flex;gap:4px;justify-content:center;align-items:center;padding:6px;background:rgba(20,22,24,.92);flex-wrap:nowrap;overflow-x:auto}
#bar button{background:#23272b;color:#e8e8e8;border:1px solid #3a4046;border-radius:8px;padding:8px 9px;min-width:40px;font-size:13px}
#bar button:disabled{opacity:.35}
#pos{padding:0 6px;opacity:.8;white-space:nowrap}
#msg{position:fixed;left:16px;right:16px;top:40%;text-align:center;display:none}
#info{position:fixed;left:8px;right:8px;top:8px;max-height:60%;overflow:auto;background:rgba(20,22,24,.96);border:1px solid #3a4046;border-radius:10px;padding:10px 14px;display:none}
#info table{border-collapse:collapse;width:100%}#info td{padding:3px 6px;vertical-align:top;border-bottom:1px solid #2c3136}
#info td:first-child{opacity:.7;white-space:nowrap}
@media print{html,body{background:#fff;overflow:visible;height:auto}#bar,#info,#msg{display:none!important}
#stage{position:static;overflow:visible}#img{position:static;transform:none!important;width:100%!important;height:96vh!important;margin:0!important;object-fit:contain;display:block}}
</style></head><body>
<div id="stage"><img id="img" alt=""></div>
<div id="msg"></div><div id="info"></div>
<div id="bar">
<button id="prev" aria-label="Previous">&#9664;</button><span id="pos"></span><button id="next" aria-label="Next">&#9654;</button>
<button id="rl" aria-label="Rotate left">&#8634;</button><button id="rr" aria-label="Rotate right">&#8635;</button>
<button id="fit" aria-label="Fit">Fit</button><button id="one" aria-label="Actual size">1:1</button><button id="inf" aria-label="Info">Info</button>
</div>
<script>
var E=__ENTRIES__,idx=__INDEX__,S=1,tx=0,ty=0,rot=0,nw=0,nh=0;
var img=document.getElementById('img'),st=document.getElementById('stage'),msg=document.getElementById('msg'),info=document.getElementById('info');
function vw(){return st.clientWidth||window.innerWidth}function vh(){return (st.clientHeight||window.innerHeight)-52}
function fitS(){var w=rot%180?nh:nw,h=rot%180?nw:nh;return Math.min(vw()/w,vh()/h,8)}
function apply(){var f=fitS();img.style.transform='translate('+tx+'px,'+ty+'px) rotate('+rot+'deg) scale('+(f*S)+')'}
function reset(){S=1;tx=0;ty=0;apply()}
function sz(n){return n>1048576?(n/1048576).toFixed(2)+' MB':n>1024?(n/1024).toFixed(1)+' KB':n+' B'}
function show(i){var e=E[i];if(!e)return;idx=i;rot=0;S=1;tx=0;ty=0;msg.style.display='none';img.style.visibility='hidden';
img.onload=function(){nw=img.naturalWidth||800;nh=img.naturalHeight||800;img.style.width=nw+'px';img.style.height=nh+'px';
img.style.marginLeft=(-nw/2)+'px';img.style.marginTop=(-nh/2)+'px';img.style.visibility='visible';apply();fillInfo()};
img.onerror=function(){msg.textContent='This image type cannot be shown here ('+(e.mime||'unknown')+'): '+e.name;msg.style.display='block'};
img.src=e.url;document.title=e.name;document.getElementById('pos').textContent=(i+1)+' / '+E.length;
document.getElementById('prev').disabled=i<=0;document.getElementById('next').disabled=i>=E.length-1}
function fillInfo(){var e=E[idx],r=[['Name',e.name],['Type',e.mime||'?'],['Size',sz(e.size)],['Dimensions',nw+' x '+nh+' px']].concat(e.exif),h='<table>';
r.forEach(function(x){var a=document.createElement('div');a.textContent=x[0];var b=document.createElement('div');b.textContent=x[1];h+='<tr><td>'+a.innerHTML+'</td><td>'+b.innerHTML+'</td></tr>'});info.innerHTML=h+'</table>'}
document.getElementById('prev').onclick=function(){show(idx-1)};document.getElementById('next').onclick=function(){show(idx+1)};
document.getElementById('rl').onclick=function(){rot=(rot+270)%360;tx=0;ty=0;apply()};document.getElementById('rr').onclick=function(){rot=(rot+90)%360;tx=0;ty=0;apply()};
document.getElementById('fit').onclick=reset;document.getElementById('one').onclick=function(){S=1/fitS();tx=0;ty=0;apply()};
document.getElementById('inf').onclick=function(){info.style.display=info.style.display==='block'?'none':'block'};
var P={},d0=0,s0=1,last=0;
st.addEventListener('pointerdown',function(e){P[e.pointerId]=[e.clientX,e.clientY];st.setPointerCapture(e.pointerId);var k=Object.keys(P);if(k.length==2){d0=dist();s0=S}
var t=Date.now();if(k.length==1&&t-last<300){S>1.01?reset():(S=2.5,apply())}last=t});
st.addEventListener('pointermove',function(e){var p=P[e.pointerId];if(!p)return;var k=Object.keys(P);
if(k.length==1){tx+=e.clientX-p[0];ty+=e.clientY-p[1]}P[e.pointerId]=[e.clientX,e.clientY];
if(k.length==2&&d0){S=Math.max(.2,Math.min(40,s0*dist()/d0))}apply()});
function up(e){delete P[e.pointerId];d0=0}st.addEventListener('pointerup',up);st.addEventListener('pointercancel',up);
function dist(){var k=Object.keys(P),a=P[k[0]],b=P[k[1]];return Math.hypot(a[0]-b[0],a[1]-b[1])||1}
st.addEventListener('wheel',function(e){e.preventDefault();S=Math.max(.2,Math.min(40,S*(e.deltaY<0?1.15:1/1.15)));apply()},{passive:false});
window.addEventListener('resize',apply);
document.addEventListener('keydown',function(e){if(e.key==='ArrowRight')show(idx+1);if(e.key==='ArrowLeft')show(idx-1)});
if(E.length)show(idx);else{msg.textContent='No image to show.';msg.style.display='block'}
</script></body></html>"""
}
