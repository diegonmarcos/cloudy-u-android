package com.diegonmarcos.superapp.headless

import org.json.JSONObject

/**
 * #1498 headless-api v1 `readable` for the phone engine ("This App" WebView): the same output contract as the container
 * (cloudy-browser-headless readable.js): format markdown (default) | json | xml, boilerplate/scripts/styles/ads/cookie banners
 * stripped, base64 images replaced by their alt text, tracking params stripped, size cap with a continue cursor.
 * The container runs Mozilla Readability + Turndown (npm); a WebView has neither, so this is the same pipeline as an in-page
 * DOM walk (main/article root, boilerplate strip, GFM tables). Result: {format,title,url,content,meta,chars,truncated,next?}
 * plus markdown/links/tables for the markdown format. Pure rules below are JVM-tested; the script runs on the phone.
 */
object HeadlessReadable {
    val FORMATS = listOf("markdown", "json", "xml")
    const val DEFAULT_MAX_CHARS = 20_000
    const val MIN_MAX_CHARS = 500
    const val MAX_MAX_CHARS = 200_000

    /** Throws bad_request unless the readable options of [c] are valid. */
    fun validate(c: JSONObject) {
        if (c.has("format") && c.optString("format") !in FORMATS) throw HeadlessApi.Err("bad_request", "format markdown|json|xml")
        if (c.has("maxChars")) { val n = c.optInt("maxChars", -1); if (n < MIN_MAX_CHARS || n > MAX_MAX_CHARS) throw HeadlessApi.Err("bad_request", "maxChars $MIN_MAX_CHARS..$MAX_MAX_CHARS") }
        if (c.has("cursor") && !Regex("^\\d{1,9}$").matches(c.optString("cursor"))) throw HeadlessApi.Err("bad_request", "bad cursor")
    }

    /** The function body run in the page: the options are injected as one JSON literal, never concatenated into code. */
    fun script(c: JSONObject): String {
        val p = JSONObject().put("format", c.optString("format", "markdown")).put("maxChars", c.optInt("maxChars", DEFAULT_MAX_CHARS))
            .put("cursor", c.optString("cursor", "0")).put("fetchedAt", java.time.Instant.now().toString())
        return "var P=$p;\n$BODY"
    }

    private val BODY = """
var STRIP='script,style,noscript,template,svg,iframe,canvas,nav,footer,aside,form,[role=navigation],[role=banner],[role=contentinfo],[role=dialog],[aria-hidden=true],[hidden]';
var NOISE=/(^|[-_ ])(cookie|cookies|consent|gdpr|banner|advert|adverts|ad|ads|sponsor|sponsored|promo|popup|modal|newsletter|subscribe|share|social|breadcrumb|breadcrumbs)([-_ ]|$)/i;
var TRK=/^(utm_[a-z0-9_]+|fbclid|gclid|dclid|msclkid|yclid|mc_cid|mc_eid|igshid|_hsenc|_hsmi|ref_src|ref_url|spm|trk|trkid)$/i;
function sq(s){return String(s==null?'':s).replace(/\s+/g,' ').trim()}
function clean(h){try{var u=new URL(h,location.href);if(u.protocol!=='http:'&&u.protocol!=='https:')return null;
 Array.from(u.searchParams.keys()).forEach(function(k){if(TRK.test(k))u.searchParams.delete(k)});return u.toString()}catch(e){return null}}
var src=document.querySelector('article,main,[role=main]')||document.body, root=src.cloneNode(true);
root.querySelectorAll(STRIP).forEach(function(e){e.remove()});
root.querySelectorAll('[class],[id]').forEach(function(e){if(e.isConnected&&(NOISE.test(e.getAttribute('class')||'')||NOISE.test(e.getAttribute('id')||'')))e.remove()});
var links=[],seen={},blocks=[],cur=null;
function flush(){if(cur&&(cur.h||cur.text))blocks.push(cur);cur=null}
function addText(t){t=sq(t);if(!t)return;if(!cur)cur={k:'s',h:'',level:0,text:''};cur.text+=(cur.text?'\n':'')+t}
function inline(n){
 if(n.nodeType===3)return n.textContent.replace(/\s+/g,' ');
 if(n.nodeType!==1)return '';
 var t=n.tagName.toLowerCase(),c=Array.from(n.childNodes).map(inline).join('');
 if(t==='a'){var h=clean(n.getAttribute('href')||''),x=sq(c);if(!h||!x)return c;if(!seen[h]){seen[h]=1;links.push({text:x,href:h})}return '['+x+']('+h+')'}
 if(t==='img'){var s=n.getAttribute('src')||'',al=sq(n.getAttribute('alt'));if(!s||/^data:/i.test(s))return al;return al?'!['+al+']('+s+')':''}
 if(t==='strong'||t==='b')return c.trim()?'**'+c.trim()+'**':'';
 if(t==='em'||t==='i')return c.trim()?'*'+c.trim()+'*':'';
 if(t==='code')return '`'+n.textContent+'`';
 if(t==='br')return '\n';
 return c}
var md=[];
function table(t){
 var rows=Array.from(t.querySelectorAll('tr')).map(function(r){return Array.from(r.children).map(function(c){return sq(c.textContent).replace(/\|/g,'\\|')})});
 if(!rows.length)return;var w=rows[0].length,line=function(r){return '| '+r.join(' | ')+' |'};
 md.push(line(rows[0])+'\n'+line(rows[0].map(function(){return '---'}))+'\n'+rows.slice(1).map(line).join('\n'));
 flush();blocks.push({k:'t',headers:rows[0],rows:rows.slice(1)})}
function list(l,d){var items=[],i=0;Array.from(l.children).forEach(function(li){if(li.tagName.toLowerCase()!=='li')return;i++;
  var own=Array.from(li.childNodes).filter(function(x){return !(x.nodeType===1&&/^(ul|ol)$/i.test(x.tagName))}).map(inline).join('');
  var pre=new Array(d+1).join('  ')+(l.tagName.toLowerCase()==='ol'?i+'. ':'- ');
  items.push(pre+sq(own));li.querySelectorAll(':scope > ul, :scope > ol').forEach(function(s){items.push(list(s,d+1))})});
 return items.join('\n')}
function walk(n){Array.from(n.children).forEach(function(c){var t=c.tagName.toLowerCase(),m=/^h([1-6])$/.exec(t);
 if(m){flush();var x=sq(inline(c).replace(/\*/g,''));cur={k:'s',h:x,level:+m[1],text:''};md.push('#'.repeat(+m[1])+' '+x)}
 else if(t==='ul'||t==='ol'){flush();var its=Array.from(c.querySelectorAll('li')).map(function(li){return sq(li.textContent)}).filter(Boolean);md.push(list(c,0));if(its.length)blocks.push({k:'l',items:its})}
 else if(t==='table')table(c);
 else if(t==='pre'){var code=c.textContent.replace(/\n$/,''),cl=c.querySelector('code'),lg=((cl&&cl.getAttribute('class')||'').match(/(?:language|lang)-([\w+-]+)/)||[])[1]||'';md.push('```'+lg+'\n'+code+'\n```');addText(code)}
 else if(['p','blockquote','dd','dt','figcaption','summary'].indexOf(t)>=0){var x=sq(inline(c));if(x){md.push(t==='blockquote'?'> '+x:x);addText(c.textContent)}}
 else if(c.children.length)walk(c);else{var y=sq(inline(c));if(y){md.push(y);addText(c.textContent)}}})}
walk(root);flush();
var title=sq(document.title),lang=document.documentElement.getAttribute('lang')||'',meta={title:title,url:location.href,fetched_at:P.fetchedAt};if(lang)meta.lang=lang;
var start=parseInt(P.cursor,10)||0,first=start===0,max=P.maxChars;
function ye(s){return JSON.stringify(sq(s))}
if(P.format==='markdown'){
 var full=md.join('\n\n').replace(/\n{3,}/g,'\n\n').trim(),end=Math.min(full.length,start+max);
 if(end<full.length){var cut=full.lastIndexOf('\n',end);if(cut>start+max/2)end=cut}
 var head=first?'---\ntitle: '+ye(title)+'\nsource: '+ye(location.href)+'\nfetched_at: '+ye(P.fetchedAt)+'\n---\n\n':'';
 var out=head+full.slice(start,end).trim(),tr=end<full.length;
 var tabs=first?blocks.filter(function(b){return b.k==='t'}).map(function(b){var r=function(x){return '| '+x.join(' | ')+' |'};return [r(b.headers),r(b.headers.map(function(){return '---'}))].concat(b.rows.map(r)).join('\n')}):[];
 var res={format:'markdown',title:title,url:location.href,markdown:out,content:out,links:first?links.slice(0,200):[],tables:tabs,meta:meta,chars:out.length,truncated:tr};if(tr)res.next=String(end);return res}
var size=function(b){return b.k==='s'?40+b.h.length+b.text.length:b.k==='l'?b.items.reduce(function(n,i){return n+i.length+8},10):JSON.stringify([b.headers,b.rows]).length+20};
var lk=links.slice(0,200),reserve=first?Math.min(lk.reduce(function(n,l){return n+l.text.length+l.href.length+20},0),Math.floor(max/4))+200:0;
var e2=start,used=0,budget=max-reserve;while(e2<blocks.length){var z=size(blocks[e2]);if(e2>start&&used+z>budget)break;used+=z;e2++}
var page=blocks.slice(start,e2),shown=[];if(first){var acc=0;shown=lk.filter(function(l){return (acc+=l.text.length+l.href.length+20)<=reserve})}
var content,xe=function(s){return String(s).replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f]/g,'').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;')};
if(P.format==='json'){var d={meta:meta},ss=page.filter(function(b){return b.k==='s'}).map(function(b){var o={};if(b.h)o.h=b.h;if(b.level)o.level=b.level;if(b.text)o.text=b.text;return o}),
 ls=page.filter(function(b){return b.k==='l'}).map(function(b){return b.items}),ts=page.filter(function(b){return b.k==='t'}).map(function(b){return {headers:b.headers,rows:b.rows}});
 if(ss.length)d.sections=ss;if(ls.length)d.lists=ls;if(ts.length)d.tables=ts;if(shown.length)d.links=shown;content=JSON.stringify(d)}
else{content='<doc><meta t="'+xe(meta.title)+'" u="'+xe(meta.url)+'" f="'+xe(meta.fetched_at)+'"'+(lang?' l="'+xe(lang)+'"':'')+'/>'+page.map(function(b){
 if(b.k==='s')return '<s'+(b.h?' h="'+xe(b.h)+'"':'')+(b.level?' l="'+b.level+'"':'')+'>'+xe(b.text)+'</s>';
 if(b.k==='l')return '<ul>'+b.items.map(function(i){return '<i>'+xe(i)+'</i>'}).join('')+'</ul>';
 var r=function(cs,h){return '<r'+(h?' h="1"':'')+'>'+cs.map(function(c){return '<c>'+xe(c)+'</c>'}).join('')+'</r>'};
 return '<table>'+r(b.headers,1)+b.rows.map(function(x){return r(x)}).join('')+'</table>'}).join('')+shown.map(function(l){return '<a href="'+xe(l.href)+'">'+xe(l.text)+'</a>'}).join('')+'</doc>'}
var res2={format:P.format,title:title,url:location.href,content:content,meta:meta,chars:content.length,truncated:e2<blocks.length};if(res2.truncated)res2.next=String(e2);return res2
"""
}
