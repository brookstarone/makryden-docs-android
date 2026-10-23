/* Makryden Writer \u2014 standalone SPA. Supabase backend, no build step. */
(function(){
'use strict';

/* ---------- config ---------- */
var SUPABASE_URL = 'https://bamrgsjgvakyucyqtwlg.supabase.co';
var SUPABASE_ANON_KEY = 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImJhbXJnc2pndmFreXVjeXF0d2xnIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODExMjUwMzAsImV4cCI6MjA5NjcwMTAzMH0.4rW9kVK1E-YFvjBvQd-VTPh5pd_O7ZTzYAx_BkyPbH0';
var IMG_BUCKET = 'docs-images';
var IMG_MAX = 10.5 * 1024 * 1024;
var IMG_TYPES = ['image/png','image/jpeg','image/webp','image/gif'];
var SIGNED_URL_TTL = 31536000; // 1 year \u2014 keeps shared-view images working

var sb = window.supabase.createClient(SUPABASE_URL, SUPABASE_ANON_KEY);

/* ---------- dom helpers ---------- */
function $(id){ return document.getElementById(id); }
function el(tag, cls, text){ var e=document.createElement(tag); if(cls)e.className=cls; if(text!=null)e.textContent=text; return e; }
var toastTimer=null;
function toast(msg, isErr){
  var t=$('toast'); t.textContent=msg; t.className=isErr?'show error':'show';
  clearTimeout(toastTimer); toastTimer=setTimeout(function(){ t.className=''; }, 3200);
}

/* ---------- sheets ---------- */
var openSheetEl=null;
function openSheet(id){ closeSheet(); $('scrim').classList.add('show'); openSheetEl=$(id); openSheetEl.classList.add('show'); }
function closeSheet(){ $('scrim').classList.remove('show'); if(openSheetEl){openSheetEl.classList.remove('show'); openSheetEl=null;} }
$('scrim').addEventListener('click', closeSheet);

/* confirm dialog (promise) */
var cfResolve=null;
function confirmDlg(title, desc, yesLabel){
  $('cf-title').textContent=title; $('cf-desc').textContent=desc||'';
  $('cf-yes').textContent=yesLabel||'Delete';
  openSheet('sheet-confirm');
  return new Promise(function(res){ cfResolve=res; });
}
$('cf-yes').addEventListener('click', function(){ closeSheet(); if(cfResolve)cfResolve(true); cfResolve=null; });
$('cf-no').addEventListener('click', function(){ closeSheet(); if(cfResolve)cfResolve(false); cfResolve=null; });

/* text prompt dialog (promise) */
var prResolve=null;
function ensurePromptSheet(){
  var sh=$('sheet-prompt');
  if(sh) return;
  sh=document.createElement('div'); sh.className='sheet'; sh.id='sheet-prompt'; sh.setAttribute('role','dialog');
  sh.setAttribute('aria-label','Input');
  sh.innerHTML='<h3 id="pr-title"></h3><p class="desc" id="pr-desc"></p>'+
    '<div class="field"><input class="input" id="pr-input" autocomplete="off"></div>'+
    '<div class="row"><button class="btn btn-ghost" id="pr-no" type="button">Cancel</button>'+
    '<button class="btn btn-gold" id="pr-yes" type="button">OK</button></div>';
  document.body.appendChild(sh);
  $('pr-no').addEventListener('click', function(){ closeSheet(); if(prResolve){prResolve(null); prResolve=null;} });
  $('pr-yes').addEventListener('click', function(){
    var v=$('pr-input').value.trim(); closeSheet(); if(prResolve){prResolve(v||null); prResolve=null;}
  });
  sh.addEventListener('keydown', function(e){
    if(e.key==='Enter'){ e.preventDefault(); $('pr-yes').click(); }
  });
}
function promptDlg(title, placeholder, initial){
  ensurePromptSheet();
  return new Promise(function(res){
    prResolve=res;
    $('pr-title').textContent=title;
    $('pr-desc').textContent='';
    var inp=$('pr-input'); inp.placeholder=placeholder||''; inp.value=initial||'';
    openSheet('sheet-prompt');
    setTimeout(function(){ inp.focus(); inp.select(); }, 300);
  });
}

/* ---------- views ---------- */
var VIEWS=['view-auth','view-list','view-editor'];
function showView(id){
  VIEWS.forEach(function(v){ $(v).classList.toggle('active', v===id); });
  window.scrollTo(0,0);
}

/* ---------- auth ---------- */
var authMode='login';
var currentUser=null;

function setAuthMode(m){
  authMode=m;
  $('tab-login').classList.toggle('on', m==='login');
  $('tab-signup').classList.toggle('on', m==='signup');
  $('auth-submit').textContent = m==='login' ? 'Log in' : 'Create account';
  $('auth-note').style.display='none';
}
$('tab-login').addEventListener('click', function(){ setAuthMode('login'); });
$('tab-signup').addEventListener('click', function(){ setAuthMode('signup'); });

$('auth-form').addEventListener('submit', async function(e){
  e.preventDefault();
  var email=$('auth-email').value.trim(), pass=$('auth-pass').value;
  var btn=$('auth-submit'); btn.disabled=true;
  try{
    if(authMode==='login'){
      var r=await sb.auth.signInWithPassword({email:email,password:pass});
      if(r.error) throw r.error;
      if(!r.data.session){ // email not confirmed
        var n=$('auth-note'); n.style.display='block';
        n.textContent='Check your email to confirm your account, then log in. (Didn\u2019t get it? Use \u201cResend it\u201d below.)';
        return;
      }
      onSignedIn(r.data.user);
    }else{
      var s=await sb.auth.signUp({email:email,password:pass});
      if(s.error) throw s.error;
      if(s.data.session){ onSignedIn(s.data.user); }
      else{
        var n2=$('auth-note'); n2.style.display='block';
        n2.textContent='Account created \u2014 check your email for the confirmation link, then log in.';
        setAuthMode('login'); toast('Confirmation email sent');
      }
    }
  }catch(err){ toast(err.message||'Authentication failed', true); }
  finally{ btn.disabled=false; }
});

$('link-forgot').addEventListener('click', async function(){
  var email=await promptDlg('Reset password','you@example.com',$('auth-email').value.trim());
  if(!email) return;
  var r=await sb.auth.resetPasswordForEmail(email,{redirectTo:location.href.split('?')[0].split('#')[0]});
  if(r.error) toast(r.error.message,true); else toast('Password reset email sent \u2014 check your inbox');
});
$('link-resend').addEventListener('click', async function(){
  var email=await promptDlg('Resend confirmation','you@example.com',$('auth-email').value.trim());
  if(!email) return;
  var r=await sb.auth.resend({type:'signup',email:email});
  if(r.error) toast(r.error.message,true); else toast('Confirmation email resent');
});

function onSignedIn(user){
  currentUser=user;
  var em=user.email||'';
  $('acct-name').textContent=em.split('@')[0]||'Account';
  $('acct-avatar').textContent=(em[0]||'M').toUpperCase();
  $('acct-email-full').textContent=em;
  showView('view-list');
  loadDocs().then(function(){
    var did=null;
    try{ did=new URLSearchParams(location.search).get('id'); }catch(e){}
    if(did){
      try{ history.replaceState(null,'',location.pathname); }catch(e){}
      openDoc(did);
    }
  });
}

/* Adopt the portal session (makrydensystems.com/account/) if we don't have our own.
   The portal stores its Supabase tokens under 'mk-portal-session' (localStorage or
   sessionStorage); the docs apps use the default Supabase key, so without this bridge
   a signed-in user looks signed-out here. */
async function adoptPortalSession(sb){
  try{
    var s = await sb.auth.getSession();
    if(s.data && s.data.session) return true;
  }catch(e){}
  var stores = [];
  try{ stores.push(window.localStorage); }catch(e){}
  try{ stores.push(window.sessionStorage); }catch(e){}
  for(var i=0;i<stores.length;i++){
    try{
      var raw = stores[i].getItem('mk-portal-session');
      if(!raw) continue;
      var ps = JSON.parse(raw);
      if(!ps || !ps.access || !ps.refresh) continue;
      if(ps.exp && ps.exp < Date.now()) continue;
      var r = await sb.auth.setSession({access_token: ps.access, refresh_token: ps.refresh});
      if(r.data && r.data.session) return true;
    }catch(e){}
  }
  return false;
}

/* Make sure the auth token is fresh before a DB write.
   An expired JWT is what surfaces as RLS violations and "couldn't open" errors. */
async function ensureSession(){
  await adoptPortalSession(sb);
  try{
    var s=await sb.auth.getSession();
    if(s.data && s.data.session){
      var exp=s.data.session.expires_at||0;
      if(exp*1000 - Date.now() < 60000){
        var r=await sb.auth.refreshSession();
        if(r.data && r.data.session && r.data.user) currentUser=r.data.user;
      }
      return true;
    }
    var rr=await sb.auth.refreshSession();
    if(rr.data && rr.data.session){ if(rr.data.user) currentUser=rr.data.user; return true; }
  }catch(e){}
  return false;
}
document.addEventListener('visibilitychange', function(){
  if(!document.hidden && currentUser) ensureSession();
});

/* account sheet */
$('acct-btn').addEventListener('click', function(){ openSheet('sheet-acct'); });
$('acct-close').addEventListener('click', closeSheet);
$('acct-signout').addEventListener('click', async function(){
  await sb.auth.signOut(); currentUser=null; closeSheet(); showView('view-auth'); setAuthMode('login');
});
$('acct-signout-all').addEventListener('click', async function(){
  var ok=await confirmDlg('Sign out everywhere?','This ends your Writer session on all devices.','Sign out');
  if(!ok) return;
  await sb.auth.signOut({scope:'global'}); currentUser=null; closeSheet(); showView('view-auth'); setAuthMode('login');
});
$('acct-changepw').addEventListener('click', async function(){
  var p1=await promptDlg('New password','At least 6 characters');
  if(!p1) return;
  if(p1.length<6){ toast('Password must be at least 6 characters',true); return; }
  var r=await sb.auth.updateUser({password:p1});
  if(r.error) toast(r.error.message,true); else toast('Password changed');
});

/* ---------- templates ---------- */
var TEMPLATES={
  blank:{name:'Blank', title:'Untitled document', header:'', footer:'', pageNumbers:false,
    html:''},
  letter:{name:'Letter', title:'Letter', header:'', footer:'', pageNumbers:false,
    html:'<p>Your Name<br>123 Main Street<br>City, ST 00000</p><p><br></p><p>'+new Date().toLocaleDateString('en-US',{year:'numeric',month:'long',day:'numeric'})+'</p><p><br></p><p>Recipient Name<br>Their Address<br>City, ST 00000</p><p><br></p><p>Dear Recipient,</p><p><br></p><p>Write your letter here. Say what you mean, plainly and kindly.</p><p><br></p><p>Sincerely,</p><p><br></p><p>Your Name</p>'},
  resume:{name:'R\u00e9sum\u00e9', title:'R\u00e9sum\u00e9', header:'', footer:'', pageNumbers:false,
    html:'<h1>Your Name</h1><p>City, ST \u00b7 you@example.com \u00b7 (555) 000-0000</p><hr><h2>Summary</h2><p>Two or three sentences about what you do best.</p><h2>Experience</h2><p><strong>Job Title</strong> \u2014 Company, City<br><em>2022 \u2013 Present</em></p><ul><li>What you accomplished, with numbers if you have them.</li><li>Another strong result.</li></ul><h2>Education</h2><p>Degree \u2014 School, Year</p><h2>Skills</h2><p>Skill one \u00b7 Skill two \u00b7 Skill three</p>'},
  invoice:{name:'Invoice', title:'Invoice', header:'', footer:'Thank you for your business.', pageNumbers:false,
    html:'<h1>Invoice</h1><p><strong>From:</strong> Your Business<br><strong>To:</strong> Client Name<br><strong>Date:</strong> '+new Date().toLocaleDateString('en-US')+'<br><strong>Invoice #:</strong> 001</p><hr><p><strong>Services</strong></p><ul><li>Service description \u2014 $0.00</li><li>Service description \u2014 $0.00</li></ul><p><strong>Total due: $0.00</strong></p><p><br></p><p><em>Payment due within 30 days.</em></p>'},
  meeting:{name:'Meeting notes', title:'Meeting notes', header:'', footer:'', pageNumbers:false,
    html:'<h1>Meeting title</h1><p><strong>Date:</strong> '+new Date().toLocaleDateString('en-US')+'<br><strong>Attendees:</strong> </p><h2>Agenda</h2><ol><li>First topic</li><li>Second topic</li></ol><h2>Notes</h2><ul><li>Key point</li></ul><h2>Action items</h2><ul><li>[ ] Owner \u2014 task</li></ul>'}
};

/* ---------- document list ---------- */
var docs=[], docFilter='';
function fmtDate(iso){
  try{ return new Date(iso).toLocaleDateString('en-US',{month:'short',day:'numeric',year:'numeric'}); }
  catch(e){ return ''; }
}
async function loadDocs(){
  var list=$('doc-list'); list.innerHTML='';
  var r=await sb.from('documents').select('id,title,updated_at,template').order('updated_at',{ascending:false});
  if(r.error){ list.appendChild(el('div','empty','')); list.lastChild.innerHTML='<h3>Couldn\u2019t load</h3><p>'+escapeHtml(r.error.message)+'</p>'; return; }
  docs=r.data||[];
  var sharedIds=await sharedDocIds();
  renderDocList(sharedIds);
}
async function sharedDocIds(){
  var r=await sb.from('share_links').select('document_id');
  if(r.error) return new Set();
  return new Set(r.data.map(function(x){return x.document_id;}));
}
function escapeHtml(s){ return String(s==null?'':s).replace(/[&<>"']/g, function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c];}); }
function renderDocList(sharedIds){
  var list=$('doc-list'); list.innerHTML='';
  var f=docFilter.trim().toLowerCase();
  var rows=docs.filter(function(d){ return !f || (d.title||'').toLowerCase().indexOf(f)>=0; });
  $('doc-count').textContent = docs.length===1 ? '1 document' : docs.length+' documents';
  if(!rows.length){
    var e=el('div','empty');
    e.innerHTML = docs.length? '<h3>No matches</h3><p>Try a different search.</p>'
      : '<h3>A quiet desk, waiting.</h3><p>Create your first document \u2014 pick a template and start writing.</p>';
    list.appendChild(e); return;
  }
  rows.forEach(function(d){
    var b=el('button','doc-row'); b.type='button';
    var meta=el('div','meta');
    meta.appendChild(el('div','title', d.title||'Untitled document'));
    var sub=el('div','sub');
    var badge=el('span','badge', TEMPLATES[d.template]?TEMPLATES[d.template].name:d.template||'Blank');
    sub.appendChild(badge);
    if(sharedIds && sharedIds.has(d.id)){ sub.appendChild(el('span','badge shared','Shared')); }
    sub.appendChild(el('span','', 'Updated '+fmtDate(d.updated_at)));
    meta.appendChild(sub); b.appendChild(meta);
    b.appendChild(el('span','chev','\u203a'));
    b.addEventListener('click', function(){ openDoc(d.id); });
    list.appendChild(b);
  });
}
$('doc-search').addEventListener('input', function(e){ docFilter=e.target.value; sharedDocIds().then(renderDocList); });
$('btn-new').addEventListener('click', function(){ openSheet('sheet-templates'); });
$('tpl-cancel').addEventListener('click', closeSheet);
document.querySelectorAll('.tpl').forEach(function(b){
  b.addEventListener('click', function(){ closeSheet(); createDoc(b.getAttribute('data-tpl')); });
});
async function createDoc(tplKey){
  var ok=await ensureSession();
  if(!ok || !currentUser){ toast('Your session expired \u2014 please log in again', true); return; }
  var t=TEMPLATES[tplKey]||TEMPLATES.blank;
  var r=await sb.from('documents').insert({
    user_id:currentUser.id, title:t.title, content_html:t.html,
    header_text:t.header, footer_text:t.footer, show_page_numbers:t.pageNumbers, template:tplKey
  }).select().single();
  if(r.error){ toast('Couldn\u2019t create document: '+r.error.message, true); return; }
  openDoc(r.data.id);
}

/* ---------- sanitizer ---------- */
var ALLOW_TAGS={P:1,H1:1,H2:1,H3:1,H4:1,BLOCKQUOTE:1,UL:1,OL:1,LI:1,STRONG:1,EM:1,U:1,S:1,A:1,BR:1,HR:1,IMG:1,DIV:1,SPAN:1,B:1,I:1,TABLE:1,THEAD:1,TBODY:1,TR:1,TD:1,TH:1};
/* style attribute allowlist: only safe typographic properties survive */
var ALLOW_STYLE=/^(font-family|font-size|color|background-color|text-align|font-weight|font-style|text-decoration|line-height|width|max-width|margin|margin-left|margin-right)$/i;
function cleanStyle(val){
  var out=[];
  String(val||'').split(';').forEach(function(part){
    var kv=part.split(':'); if(kv.length<2) return;
    var prop=kv[0].trim(), v=kv.slice(1).join(':').trim();
    if(!ALLOW_STYLE.test(prop)) return;
    if(/[<>"']/.test(v)) return;
    if(/expression|javascript:|url\s*\(/i.test(v)) return;
    out.push(prop+':'+v);
  });
  return out.join(';');
}
function sanitize(dirty){
  var doc=new DOMParser().parseFromString('<div>'+String(dirty||'')+'</div>','text/html');
  var root=doc.body.firstChild;
  (function walk(node){
    var kids=Array.prototype.slice.call(node.childNodes);
    kids.forEach(function(ch){
      if(ch.nodeType===1){
        var tag=ch.tagName;
        if(!ALLOW_TAGS[tag]){ // unwrap, keep text
          while(ch.firstChild) node.insertBefore(ch.firstChild, ch);
          node.removeChild(ch); return;
        }
        var attrs=Array.prototype.slice.call(ch.attributes);
        attrs.forEach(function(a){
          var n=a.name.toLowerCase();
          if(n.indexOf('on')===0){ ch.removeAttribute(a.name); return; }
          if(n==='style'){
            var cs=cleanStyle(a.value);
            if(cs) ch.setAttribute('style',cs); else ch.removeAttribute(a.name);
            return;
          }
          if(tag==='A' && n==='href'){
            var v=a.value.trim();
            if(!/^(https?:|mailto:)/i.test(v)){ ch.removeAttribute(a.name); }
            else ch.setAttribute('target','_blank'), ch.setAttribute('rel','noopener');
            return;
          }
          if(tag==='IMG' && (n==='src'||n==='alt'||n==='data-sp')) return;
          ch.removeAttribute(a.name);
        });
        if(tag==='IMG'){
          var src=ch.getAttribute('src')||'';
          if(!/^https:\/\/|^data:image\//i.test(src)) ch.removeAttribute('src');
          ch.removeAttribute('srcset');
        }
        walk(ch);
      } else if(ch.nodeType===8){ node.removeChild(ch); }
    });
  })(root);
  return root.innerHTML;
}

/* ---------- editor ---------- */
var cur=null; // {id,row}
var saveTimer=null, saving=false, dirty=false;

function setSaveState(s, msg){
  var e=$('save-state'); e.className=s||'';
  e.textContent = msg || (s==='saving'?'Saving\u2026':(s==='err'?'Couldn\u2019t save':'Saved'));
}
function markDirty(){ dirty=true; setSaveState('saving'); clearTimeout(saveTimer); saveTimer=setTimeout(saveDoc, 1200); }

async function openDoc(id){
  setSaveState('saving','Opening\u2026');
  await ensureSession();
  var r=await sb.from('documents').select('*').eq('id',id).single();
  if(r.error){ toast('Couldn\u2019t open document', true); setSaveState('err'); return; }
  cur={id:id, row:r.data};
  $('doc-title').value=r.data.title||'';
  $('opt-header').value=r.data.header_text||'';
  $('opt-footer').value=r.data.footer_text||'';
  $('opt-pageno').checked=!!r.data.show_page_numbers;
  $('editor').innerHTML=sanitize(r.data.content_html||'');
  dirty=false; setSaveState('');
  updateWordCount();
  showView('view-editor');
}
$('btn-back').addEventListener('click', async function(){ await saveDoc(); location.href='file:///android_asset/docs/index.html'; });

['doc-title','opt-header','opt-footer'].forEach(function(id){
  $(id).addEventListener('input', markDirty);
});
$('opt-pageno').addEventListener('change', markDirty);
$('editor').addEventListener('input', markDirty);
$('editor').addEventListener('paste', function(e){
  e.preventDefault();
  var html=(e.clipboardData||window.clipboardData).getData('text/html');
  var text=(e.clipboardData||window.clipboardData).getData('text/plain');
  var clean = html ? sanitize(html) : escapeHtml(text).replace(/\n/g,'<br>');
  document.execCommand('insertHTML', false, clean);
  markDirty();
});

/* toolbar */
document.querySelectorAll('#toolbar [data-cmd]').forEach(function(b){
  b.addEventListener('click', function(){
    document.execCommand(b.getAttribute('data-cmd'), false, b.getAttribute('data-arg')||null);
    $('editor').focus(); markDirty();
  });
});
document.querySelectorAll('#toolbar [data-block]').forEach(function(b){
  b.addEventListener('click', function(){
    document.execCommand('formatBlock', false, b.getAttribute('data-block'));
    $('editor').focus(); markDirty();
  });
});

/* Selection preservation: dropdowns and color pickers steal focus from the
   editor, destroying the text selection before the change event fires.
   Save the range on blur, restore it before applying. */
var savedEditorRange=null;
function saveEditorSelection(){
  var sel=window.getSelection();
  if(!sel || !sel.rangeCount) return;
  try{
    var range=sel.getRangeAt(0);
    var node=range.commonAncestorContainer;
    var t=node.nodeType===3 ? node.parentNode : node;
    if(t && ($('editor')===$('editor') && $('editor').contains(t))){
      savedEditorRange=range.cloneRange();
    }
  }catch(e){}
}
function restoreEditorSelection(){
  var ed=$('editor'); ed.focus();
  if(!savedEditorRange) return false;
  var sel=window.getSelection();
  sel.removeAllRanges();
  try{ sel.addRange(savedEditorRange); return true; }
  catch(e){ return false; }
}
$('editor').addEventListener('blur', saveEditorSelection);
document.addEventListener('selectionchange', function(){
  if(document.activeElement===$('editor')) saveEditorSelection();
});
function applySpanStyle(prop, value){
  var ed=$('editor'); ed.focus();
  var sel=window.getSelection();
  if(!sel || !sel.rangeCount) return false;
  var range=sel.getRangeAt(0);
  if(range.collapsed){
    // No selection \u2014 style the current block so typing continues styled
    var node=sel.anchorNode;
    var block=node ? (node.nodeType===3 ? node.parentNode : node) : null;
    while(block && block!==ed && !/^(P|H1|H2|H3|H4|DIV|LI|BLOCKQUOTE)$/i.test(block.tagName)) block=block.parentNode;
    if(block && block!==ed){ block.style[prop]=value; return true; }
    return false;
  }
  var span=document.createElement('span');
  span.style[prop]=value;
  try{ range.surroundContents(span); }
  catch(e){
    span.appendChild(range.extractContents());
    range.insertNode(span);
  }
  sel.removeAllRanges();
  var r2=document.createRange(); r2.selectNodeContents(span); r2.collapse(false);
  sel.addRange(r2);
  return true;
}
$('tool-font').addEventListener('change', function(){
  var v=this.value; this.value='';
  if(v && restoreEditorSelection()) applySpanStyle('fontFamily', v);
  $('editor').focus(); markDirty();
});
var SIZE_PT={'1':'8pt','2':'10pt','3':'12pt','4':'14pt','5':'18pt','6':'24pt','7':'36pt'};
$('tool-size').addEventListener('change', function(){
  var v=this.value; this.value='';
  if(v && restoreEditorSelection()) applySpanStyle('fontSize', SIZE_PT[v]||'12pt');
  $('editor').focus(); markDirty();
});
$('tool-fgcolor').addEventListener('input', function(){
  if(restoreEditorSelection()) applySpanStyle('color', this.value);
  $('editor').focus(); markDirty();
});
$('tool-bgcolor').addEventListener('input', function(){
  if(restoreEditorSelection()) applySpanStyle('backgroundColor', this.value);
  $('editor').focus(); markDirty();
});
$('tool-table').addEventListener('click', async function(){
  var rs=await promptDlg('Table rows','1\u201320','3');
  if(!rs) return;
  var cs=await promptDlg('Table columns','1\u201310','3');
  if(!cs) return;
  var rows=Math.min(Math.max(parseInt(rs,10)||3,1),20);
  var cols=Math.min(Math.max(parseInt(cs,10)||3,1),10);
  var html='<table><tbody>';
  for(var r=0;r<rows;r++){ html+='<tr>'; for(var c=0;c<cols;c++){ html+='<td><br></td>'; } html+='</tr>'; }
  html+='</tbody></table><p><br></p>';
  $('editor').focus();
  document.execCommand('insertHTML', false, html);
  markDirty(); toast('Table inserted');
});

/* ---------- line spacing (Word parity: 1.0 / 1.15 / 1.5 / 2.0 / 2.5) ---------- */
function applyLineHeight(lh){
  var ed=$('editor');
  restoreEditorSelection();
  var sel=window.getSelection();
  if(!sel||!sel.rangeCount){ toast('Put the cursor in the document first', true); return; }
  var range=sel.getRangeAt(0);
  var touched=0;
  function isBlock(n){ return n&&n.nodeType===1&&/^(P|H1|H2|H3|H4|DIV|LI|BLOCKQUOTE|TD|TH)$/i.test(n.tagName); }
  if(range.collapsed){
    var b=sel.anchorNode;
    if(b&&b.nodeType===3) b=b.parentNode;
    while(b&&b!==ed&&!isBlock(b)) b=b.parentNode;
    if(b&&b!==ed){ b.style.lineHeight=lh; touched++; }
  }else{
    var els=ed.querySelectorAll('p,h1,h2,h3,h4,div,li,blockquote,td,th');
    for(var i=0;i<els.length;i++){
      var hit=false;
      try{ hit=range.intersectsNode(els[i]); }
      catch(e){
        try{
          var r2=document.createRange(); r2.selectNodeContents(els[i]);
          hit=range.compareBoundaryPoints(Range.END_TO_START,r2)<0 &&
              range.compareBoundaryPoints(Range.START_TO_END,r2)>0;
        }catch(_){}
      }
      if(hit){ els[i].style.lineHeight=lh; touched++; }
    }
  }
  if(touched){ markDirty(); toast('Line spacing '+lh); }
  else toast('Select some text first', true);
  ed.focus();
}
$('tool-linespacing').addEventListener('change', function(){
  var v=this.value; this.value='';
  if(v) applyLineHeight(v);
  $('editor').focus();
});

/* ---------- find & replace ---------- */
/* Highlights are live-DOM spans only. They are always cleared before save and
   export (sanitizer strips the class anyway), so they never corrupt content. */
var findHits=[], findIdx=-1, findOpen=false;
function highlightMatches(root, query, caseSensitive){
  var hits=[];
  if(!query) return hits; // guard: empty query would loop forever on indexOf('')
  var q=caseSensitive?query:query.toLowerCase();
  function walk(node){
    var kids=[], c;
    for(c=node.firstChild;c;c=c.nextSibling) kids.push(c);
    for(var i=0;i<kids.length;i++){
      var ch=kids[i];
      if(ch.nodeType===3){
        var text=ch.nodeValue;
        var hay=caseSensitive?text:text.toLowerCase();
        var idx=0, start, frag=null;
        while((start=hay.indexOf(q,idx))>=0){
          if(!frag) frag=document.createDocumentFragment();
          if(start>idx) frag.appendChild(document.createTextNode(text.slice(idx,start)));
          var span=document.createElement('span');
          span.className='find-hit';
          span.textContent=text.slice(start,start+query.length);
          frag.appendChild(span);
          hits.push(span);
          idx=start+query.length;
        }
        if(frag){
          if(idx<text.length) frag.appendChild(document.createTextNode(text.slice(idx)));
          node.replaceChild(frag,ch);
        }
      }else if(ch.nodeType===1&&ch.tagName!=='SCRIPT'&&ch.tagName!=='STYLE'){
        walk(ch);
      }
    }
  }
  walk(root);
  return hits;
}
function updateFindStatus(msg){
  var s=$('find-status'); if(!s) return;
  if(msg!=null){ s.textContent=msg; return; }
  if(!findHits.length){ s.textContent=$('find-query').value?'No matches.':'Type to search the document.'; }
  else s.textContent=(findIdx+1)+' of '+findHits.length+' matches';
}
function markCurrentFind(){
  findHits.forEach(function(s,i){ s.classList.toggle('find-current', i===findIdx); });
  if(findIdx>=0&&findHits[findIdx]){
    try{ findHits[findIdx].scrollIntoView({block:'nearest',behavior:'smooth'}); }catch(e){}
  }
  updateFindStatus();
}
function clearFind(){
  findHits.forEach(function(span){
    var tn=document.createTextNode(span.textContent);
    if(span.parentNode) span.parentNode.replaceChild(tn,span);
  });
  findHits=[]; findIdx=-1;
  $('editor').normalize();
  updateFindStatus('');
}
function runFind(){
  clearFind();
  var q=$('find-query').value;
  if(!q){ updateFindStatus('Type to search the document.'); return; }
  findHits=highlightMatches($('editor'), q, $('find-case').checked);
  findIdx=findHits.length?0:-1;
  markCurrentFind();
}
function findNext(){ if(!findHits.length) return; findIdx=(findIdx+1)%findHits.length; markCurrentFind(); }
function findPrev(){ if(!findHits.length) return; findIdx=(findIdx-1+findHits.length)%findHits.length; markCurrentFind(); }
function replaceCurrent(){
  if(findIdx<0||findIdx>=findHits.length){ toast('Nothing to replace', true); return; }
  var span=findHits[findIdx];
  var repl=$('find-replace').value;
  var tn=document.createTextNode(repl);
  if(span.parentNode) span.parentNode.replaceChild(tn,span);
  findHits.splice(findIdx,1);
  $('editor').normalize();
  if(!findHits.length){ findIdx=-1; }
  else if(findIdx>=findHits.length){ findIdx=0; }
  markCurrentFind(); markDirty(); updateWordCount();
}
function replaceAllFind(){
  var repl=$('find-replace').value;
  var n=findHits.length;
  if(!n){ toast('Nothing to replace', true); return; }
  findHits.forEach(function(span){
    var tn=document.createTextNode(repl);
    if(span.parentNode) span.parentNode.replaceChild(tn,span);
  });
  findHits=[]; findIdx=-1;
  $('editor').normalize();
  updateFindStatus('Replaced '+n+(n===1?' match':' matches')+'.');
  markDirty(); updateWordCount();
}
function openFind(focusReplace){
  findOpen=true;
  clearFind();
  openSheet('sheet-find');
  setTimeout(function(){
    var inp=$(focusReplace?'find-replace':'find-query');
    inp.focus(); inp.select();
  },300);
  runFind();
}
$('tool-find').addEventListener('click', function(){ openFind(false); });
$('find-query').addEventListener('input', runFind);
$('find-replace').addEventListener('input', function(){ /* replacement text only */ });
$('find-case').addEventListener('change', runFind);
$('find-query').addEventListener('keydown', function(e){
  if(e.key==='Enter'){ e.preventDefault(); if(e.shiftKey) findPrev(); else findNext(); }
});
$('find-replace').addEventListener('keydown', function(e){
  if(e.key==='Enter'){ e.preventDefault(); replaceCurrent(); }
});
$('find-next').addEventListener('click', findNext);
$('find-prev').addEventListener('click', findPrev);
$('find-replace-btn').addEventListener('click', replaceCurrent);
$('find-replace-all').addEventListener('click', replaceAllFind);
$('find-close').addEventListener('click', function(){ findOpen=false; clearFind(); closeSheet(); $('editor').focus(); });
// Typing in the editor invalidates highlights
$('editor').addEventListener('input', function(){ if(findHits.length) clearFind(); });

/* ---------- table row/column editing ---------- */
var tableAnchorCell=null;
function openTableSheet(fromEl){
  var c=null;
  if(fromEl){
    c=fromEl;
    while(c&&c!==$('editor')&&!/^(TD|TH)$/i.test(c.tagName)) c=c.parentNode;
    if(!(c&&/^(TD|TH)$/i.test(c.tagName))) c=null;
  }
  if(!c) c=tableCellFromSelection();
  if(!c){ toast('Put the cursor in a table first', true); return; }
  tableAnchorCell=c;
  openSheet('sheet-table');
}
function tableCellFromSelection(){
  var sel=window.getSelection();
  if(!sel||!sel.rangeCount) return null;
  var node=sel.anchorNode;
  var n=node?(node.nodeType===3?node.parentNode:node):null;
  while(n&&n!==$('editor')){
    if(/^(TD|TH)$/i.test(n.tagName)) return n;
    n=n.parentNode;
  }
  return null;
}
function tableAnchor(){
  if(tableAnchorCell&&document.contains(tableAnchorCell)&&/^(TD|TH)$/i.test(tableAnchorCell.tagName))
    return tableAnchorCell;
  var c=tableCellFromSelection();
  if(c) tableAnchorCell=c;
  return tableAnchorCell;
}
function tableInsertRow(above){
  var cell=tableAnchor(); if(!cell){ toast('Put the cursor in a table first', true); return; }
  var row=cell.parentNode;
  var newRow=document.createElement('tr');
  for(var i=0;i<row.cells.length;i++){
    var td=document.createElement(row.cells[i].tagName);
    td.innerHTML='<br>'; newRow.appendChild(td);
  }
  row.parentNode.insertBefore(newRow, above?row:row.nextSibling);
  markDirty(); toast(above?'Row inserted above':'Row inserted below');
}
function tableInsertCol(left){
  var cell=tableAnchor(); if(!cell){ toast('Put the cursor in a table first', true); return; }
  var table=cell.closest?cell.closest('table'):null; if(!table){ toast('Put the cursor in a table first', true); return; }
  var idx=cell.cellIndex, rows=table.rows;
  for(var i=0;i<rows.length;i++){
    var first=rows[i].cells[0];
    var td=document.createElement(first?first.tagName:'TD');
    td.innerHTML='<br>';
    var ref=rows[i].cells[left?idx:idx+1];
    rows[i].insertBefore(td, ref||null);
  }
  markDirty(); toast(left?'Column inserted left':'Column inserted right');
}
function tableDeleteRow(){
  var cell=tableAnchor(); if(!cell){ toast('Put the cursor in a table first', true); return; }
  var row=cell.parentNode, table=row.parentNode;
  while(table&&table.tagName!=='TABLE') table=table.parentNode;
  if(table&&table.rows.length<=1){ toast("Can't delete the last row", true); return; }
  row.parentNode.removeChild(row);
  tableAnchorCell=null; markDirty(); closeSheet(); toast('Row deleted');
}
function tableDeleteCol(){
  var cell=tableAnchor(); if(!cell){ toast('Put the cursor in a table first', true); return; }
  var table=cell.closest?cell.closest('table'):null; if(!table){ toast('Put the cursor in a table first', true); return; }
  var idx=cell.cellIndex, rows=table.rows;
  for(var i=0;i<rows.length;i++){ if(rows[i].cells.length<=1){ toast("Can't delete the last column", true); return; } }
  for(var j=0;j<rows.length;j++){ rows[j].deleteCell(idx); }
  tableAnchorCell=null; markDirty(); closeSheet(); toast('Column deleted');
}
function tableDeleteTable(){
  var cell=tableAnchor(); if(!cell){ toast('Put the cursor in a table first', true); return; }
  var t=cell.closest?cell.closest('table'):null; if(!t){ toast('Put the cursor in a table first', true); return; }
  t.parentNode.removeChild(t);
  tableAnchorCell=null; markDirty(); closeSheet(); toast('Table deleted');
}
$('tool-tablemenu').addEventListener('click', function(){ openTableSheet(null); });
$('tbl-row-above').addEventListener('click', function(){ tableInsertRow(true); });
$('tbl-row-below').addEventListener('click', function(){ tableInsertRow(false); });
$('tbl-col-left').addEventListener('click', function(){ tableInsertCol(true); });
$('tbl-col-right').addEventListener('click', function(){ tableInsertCol(false); });
$('tbl-del-row').addEventListener('click', tableDeleteRow);
$('tbl-del-col').addEventListener('click', tableDeleteCol);
$('tbl-del-table').addEventListener('click', tableDeleteTable);
$('tbl-close').addEventListener('click', closeSheet);
// Right-click inside a table opens the table sheet (Word-style)
$('editor').addEventListener('contextmenu', function(e){
  var t=e.target;
  while(t&&t!==$('editor')){
    if(/^(TD|TH|TABLE)$/i.test(t.tagName)){ e.preventDefault(); openTableSheet(t); return; }
    t=t.parentNode;
  }
});

/* ---------- image selection, alignment & resizing ---------- */
var selectedImage=null, resizeHandle=null;
function positionResizeHandle(){
  if(!resizeHandle||!selectedImage) return;
  var r=selectedImage.getBoundingClientRect();
  resizeHandle.style.left=(r.right-13)+'px';
  resizeHandle.style.top=(r.bottom-13)+'px';
}
function deselectImage(){
  if(selectedImage) selectedImage.classList.remove('img-selected');
  selectedImage=null;
  if(resizeHandle&&resizeHandle.parentNode) resizeHandle.parentNode.removeChild(resizeHandle);
  resizeHandle=null;
}
function selectImage(img){
  deselectImage();
  selectedImage=img;
  img.classList.add('img-selected');
  resizeHandle=document.createElement('div');
  resizeHandle.className='mkw-resize-handle';
  resizeHandle.title='Drag to resize';
  document.body.appendChild(resizeHandle);
  positionResizeHandle();
  var startX=0, startW=0, dragging=false;
  function onMove(e){
    if(!dragging) return;
    var cx=(e.touches&&e.touches[0])?e.touches[0].clientX:e.clientX;
    var maxW=$('editor').clientWidth-40;
    var w=Math.max(40, Math.min(maxW, startW+(cx-startX)));
    selectedImage.style.width=Math.round(w)+'px';
    positionResizeHandle();
  }
  function onUp(){
    dragging=false;
    window.removeEventListener('pointermove', onMove);
    window.removeEventListener('pointerup', onUp);
    window.removeEventListener('pointercancel', onUp);
    if(selectedImage) markDirty();
  }
  resizeHandle.addEventListener('pointerdown', function(e){
    e.preventDefault(); e.stopPropagation();
    dragging=true;
    startX=e.clientX;
    startW=img.getBoundingClientRect().width;
    window.addEventListener('pointermove', onMove);
    window.addEventListener('pointerup', onUp);
    window.addEventListener('pointercancel', onUp);
  });
}
function alignSelectedImage(pos){
  if(!selectedImage||!document.contains(selectedImage)){ toast('Tap an image to select it first', true); return; }
  var img=selectedImage;
  img.style.display='block';
  if(pos==='left'){ img.style.marginLeft='0'; img.style.marginRight='auto'; }
  else if(pos==='center'){ img.style.marginLeft='auto'; img.style.marginRight='auto'; }
  else { img.style.marginLeft='auto'; img.style.marginRight='0'; }
  positionResizeHandle();
  markDirty(); toast('Image aligned '+pos);
}
$('tool-imgalign-l').addEventListener('click', function(){ alignSelectedImage('left'); });
$('tool-imgalign-c').addEventListener('click', function(){ alignSelectedImage('center'); });
$('tool-imgalign-r').addEventListener('click', function(){ alignSelectedImage('right'); });
$('editor').addEventListener('click', function(e){
  if(e.target&&e.target.tagName==='IMG') selectImage(e.target);
  else deselectImage();
});
$('editor').addEventListener('input', deselectImage);
window.addEventListener('scroll', function(){ if(resizeHandle) positionResizeHandle(); }, true);
window.addEventListener('resize', function(){ if(resizeHandle) positionResizeHandle(); });

/* ---------- keyboard shortcuts ---------- */
$('editor').addEventListener('keydown', function(e){
  var mod=e.ctrlKey||e.metaKey;
  var k=(e.key||'').toLowerCase();
  if(mod&&!e.altKey&&k==='f'){ e.preventDefault(); openFind(false); return; }
  if(mod&&!e.altKey&&k==='h'){ e.preventDefault(); openFind(true); return; }
  if(e.key==='F3'){ e.preventDefault(); if(e.shiftKey) findPrev(); else findNext(); return; }
  if(e.key==='Escape'){
    if(findOpen){ findOpen=false; clearFind(); closeSheet(); }
    deselectImage();
    return;
  }
  // Word-style line spacing: Ctrl+Shift+1/5/2
  if(mod&&e.shiftKey&&!e.altKey&&(e.key==='1'||e.key==='5'||e.key==='2')){
    var map={'1':'1.0','5':'1.5','2':'2.0'};
    e.preventDefault();
    applyLineHeight(map[e.key]);
  }
});
function updateWordCount(){
  var text = $('editor').innerText || '';
  var words = text.trim() ? text.trim().split(/\s+/).length : 0;
  var chars = text.length;
  $('wordcount').textContent = words + ' words \u00b7 ' + chars + ' characters';
}
$('editor').addEventListener('input', updateWordCount);
$('editor').addEventListener('keyup', updateWordCount);
$('tool-link').addEventListener('click', async function(){
  var sel=window.getSelection();
  var savedRange=(sel && sel.rangeCount && !sel.isCollapsed) ? sel.getRangeAt(0).cloneRange() : null;
  var selectedText=sel ? sel.toString() : '';
  var url=await promptDlg('Link URL','https://example.com','');
  if(!url) return;
  if(!/^https?:\/\//i.test(url)) url='https://'+url;
  // Restore the selection the dialog stole
  $('editor').focus();
  if(savedRange){
    var s2=window.getSelection();
    s2.removeAllRanges();
    try{ s2.addRange(savedRange); }catch(e){}
  }
  if(!savedRange){
    var label=await promptDlg('Link text','Words to show', url);
    if(!label) return;
    $('editor').focus();
    document.execCommand('insertHTML', false, '<a href="'+escapeHtml(url)+'">'+escapeHtml(label)+'</a>');
  }else{
    var ok=false;
    try{ ok=document.execCommand('createLink', false, url); }catch(e){}
    if(!ok){
      // Fallback: wrap selection manually
      var r=window.getSelection().getRangeAt(0);
      var a=document.createElement('a');
      a.href=url; a.target='_blank'; a.rel='noopener';
      try{ r.surroundContents(a); }
      catch(e2){ a.appendChild(r.extractContents()); r.insertNode(a); }
    }
  }
  markDirty(); toast('Link added');
});

/* save \u2014 retries twice on network failure before surfacing the error */
async function saveDoc(){
  if(!cur || !dirty || saving) return;
  saving=true; setSaveState('saving');
  var title=$('doc-title').value.trim().slice(0,300)||'Untitled document';
  clearFind(); // never persist find highlights
  var html=$('editor').innerHTML;
  if(html.length>4000000){ toast('Document is too large to save', true); setSaveState('err'); saving=false; return; }
  var lastErr=null;
  for(var attempt=0; attempt<3; attempt++){
    try{
      var freshHtml=html;
      try{ freshHtml=await refreshImageUrls(html); }catch(e){ /* keep existing urls, don't fail the save */ }
      freshHtml=sanitize(freshHtml);
      var r=await sb.from('documents').update({
        title:title, content_html:freshHtml,
        header_text:$('opt-header').value.slice(0,300),
        footer_text:$('opt-footer').value.slice(0,300),
        show_page_numbers:$('opt-pageno').checked,
        updated_at:new Date().toISOString()
      }).eq('id',cur.id).select().single();
      if(r.error) throw r.error;
      cur.row=r.data; dirty=false; setSaveState('');
      saving=false; return;
    }catch(err){
      lastErr=err;
      // Refresh the session once \u2014 an expired token looks like a network failure
      if(attempt===0) await ensureSession();
      if(attempt<2) await new Promise(function(res){ setTimeout(res, 900*(attempt+1)); });
    }
  }
  setSaveState('err'); toast('Save failed: '+((lastErr&&lastErr.message)||lastErr), true);
  saving=false;
}
window.addEventListener('beforeunload', function(){ if(dirty && cur){ /* best effort */ } });

/* ---------- images ---------- */
function savedRange(){ var s=window.getSelection(); return (s && s.rangeCount)? s.getRangeAt(0):null; }
var pendingRange=null;
$('tool-image').addEventListener('click', function(){
  pendingRange=savedRange();
  // Build the picker on the fly \u2014 display:none inputs can't be
  // programmatically clicked in every browser.
  var fi=document.createElement('input');
  fi.type='file'; fi.accept='image/png,image/jpeg,image/webp,image/gif';
  fi.style.cssText='position:fixed;left:-9999px;top:0;width:1px;height:1px;opacity:0;';
  document.body.appendChild(fi);
  fi.addEventListener('change', function(){
    var f=fi.files[0];
    document.body.removeChild(fi);
    if(f) handleImageFile(f);
  });
  // If the user cancels, clean up
  fi.addEventListener('cancel', function(){ if(fi.parentNode) fi.parentNode.removeChild(fi); });
  fi.click();
});
async function handleImageFile(f){
  if(IMG_TYPES.indexOf(f.type)<0){ toast('Only PNG, JPEG, WebP or GIF images', true); return; }
  if(f.size>IMG_MAX){ toast('Image must be under 10.5 MB', true); return; }
  if(!cur){ toast('Open a document first', true); return; }
  await saveDoc(); // need a persisted doc id for the storage path
  toast('Uploading image\u2026');
  try{
    var imageId=(window.crypto&&crypto.randomUUID)?crypto.randomUUID():'img-'+Date.now()+'-'+Math.floor(Math.random()*1e9);
    var path=currentUser.id+'/'+cur.id+'/'+imageId;
    var up=await sb.storage.from(IMG_BUCKET).upload(path, f, {contentType:f.type, upsert:false});
    if(up.error) throw up.error;
    var mi=await sb.from('document_images').insert({
      id:imageId, user_id:currentUser.id, document_id:cur.id, mime:f.type, storage_path:path
    });
    if(mi.error) throw mi.error;
    var sg=await sb.storage.from(IMG_BUCKET).createSignedUrl(path, SIGNED_URL_TTL);
    if(sg.error) throw sg.error;
    var imgHtml='<img src="'+escapeHtml(sg.data.signedUrl)+'" data-sp="'+escapeHtml(path)+'" alt="Image">';
    var ed=$('editor'); ed.focus();
    if(pendingRange){
      var sel=window.getSelection(); sel.removeAllRanges();
      try{ sel.addRange(pendingRange); }catch(e){}
    }
    document.execCommand('insertHTML', false, imgHtml+'<p><br></p>');
    markDirty(); toast('Image added');
  }catch(err){ toast('Image upload failed: '+(err.message||err), true); }
}

/* refresh every embedded image to a long-lived signed URL before saving */
async function refreshImageUrls(html){
  var tmp=document.createElement('div'); tmp.innerHTML=html;
  var imgs=tmp.querySelectorAll('img[data-sp]');
  for(var i=0;i<imgs.length;i++){
    var sp=imgs[i].getAttribute('data-sp');
    try{
      var r=await sb.storage.from(IMG_BUCKET).createSignedUrl(sp, SIGNED_URL_TTL);
      if(!r.error) imgs[i].setAttribute('src', r.data.signedUrl);
    }catch(e){/* keep existing src */}
  }
  return tmp.innerHTML;
}

/* ---------- system share & email ---------- */
$('dm-sysshare').addEventListener('click', async function(){
  closeSheet(); await saveDoc();
  var doc = getDocContent();
  if(!doc){ toast('Open a document first', true); return; }
  var tmp = document.createElement('div'); tmp.innerHTML = sanitize(doc.html);
  var text = tmp.innerText || tmp.textContent || '';
  var shareData = { title: doc.title, text: doc.title + '\n\n' + text };
  if(navigator.share){
    try{ await navigator.share(shareData); }
    catch(e){ if(e.name !== 'AbortError') toast('Share failed', true); }
  }else if(navigator.clipboard){
    try{ await navigator.clipboard.writeText(shareData.text); toast('Share not available \u2014 document copied to clipboard'); }
    catch(e){ toast('Share not available on this device', true); }
  }else{ toast('Share not available on this device', true); }
});
$('dm-email').addEventListener('click', async function(){
  closeSheet(); await saveDoc();
  var doc = getDocContent();
  if(!doc){ toast('Open a document first', true); return; }
  var tmp = document.createElement('div'); tmp.innerHTML = sanitize(doc.html);
  var text = tmp.innerText || tmp.textContent || '';
  var href='mailto:?subject='+encodeURIComponent(doc.title)+'&body='+encodeURIComponent(text);
  // Anchor click is more reliable than location.href for mailto: from async handlers
  var a=document.createElement('a');
  a.href=href; a.style.display='none';
  document.body.appendChild(a); a.click();
  setTimeout(function(){ if(a.parentNode) a.parentNode.removeChild(a); }, 500);
  toast('Opening your email app\u2026');
});

/* ---------- export: PDF / DOCX / Print ---------- */
function getDocContent(){
  if(!cur) return null;
  clearFind(); // never export find highlights
  return {
    title: $('doc-title').value.trim() || 'Untitled document',
    html: $('editor').innerHTML,
    header: $('opt-header').value.trim(),
    footer: $('opt-footer').value.trim(),
    pageNumbers: $('opt-pageno').checked
  };
}

/* Print + PDF: render into a fresh window so the app DOM is never touched.
   Falls back to same-page print with guaranteed cleanup if popups are blocked. */
var PRINT_CSS=[
  'body{font-family:Georgia,"Times New Roman",serif;font-size:12pt;line-height:1.6;color:#000;background:#fff;max-width:7.5in;margin:0 auto;padding:0.5in;}',
  '.print-header{text-align:center;font-size:10pt;color:#666;border-bottom:1px solid #ccc;padding-bottom:12pt;margin-bottom:24pt;}',
  '.print-title{font-size:24pt;font-weight:bold;margin:0 0 18pt;text-align:center;}',
  '.print-body h1{font-size:18pt;margin:18pt 0 8pt;}',
  '.print-body h2{font-size:15pt;margin:14pt 0 6pt;}',
  '.print-body h3{font-size:13pt;margin:12pt 0 6pt;}',
  '.print-body p{margin:0 0 10pt;}',
  '.print-body ul,.print-body ol{margin:6pt 0 10pt 24pt;}',
  '.print-body blockquote{border-left:3pt solid #999;padding-left:12pt;margin:10pt 0;font-style:italic;color:#444;}',
  '.print-body img{max-width:100%;margin:10pt 0;}',
  '.print-body table{border-collapse:collapse;width:100%;margin:10pt 0;}',
  '.print-body td,.print-body th{border:1pt solid #999;padding:6pt;}',
  '.print-footer{text-align:center;font-size:10pt;color:#666;border-top:1px solid #ccc;padding-top:12pt;margin-top:24pt;}',
  '@page{margin:0.75in;}'
].join('\n');
function buildPrintHtml(doc){
  var html='';
  if(doc.header) html+='<div class="print-header">'+escapeHtml(doc.header)+'</div>';
  html+='<h1 class="print-title">'+escapeHtml(doc.title)+'</h1>';
  html+='<div class="print-body">'+sanitize(doc.html)+'</div>';
  if(doc.footer) html+='<div class="print-footer">'+escapeHtml(doc.footer)+'</div>';
  return html;
}
function doPrint(doc, forPdf){
  var html=buildPrintHtml(doc);
  var w=null;
  try{ w=window.open('', '_blank', 'width=900,height=700'); }catch(e){ w=null; }
  if(w){
    try{
      w.document.write('<!DOCTYPE html><html><head><meta charset="UTF-8"><title>'+
        escapeHtml(doc.title)+'</title><style>'+PRINT_CSS+'</style></head><body>'+
        html+'</body></html>');
      w.document.close(); w.focus();
      toast(forPdf ? 'Choose "Save as PDF" in the print dialog' : 'Opening print preview\u2026');
      setTimeout(function(){ try{ w.print(); }catch(e){} }, 350);
      return;
    }catch(e){ try{ w.close(); }catch(_){} }
  }
  // Fallback: same-page print, with guaranteed cleanup even if afterprint never fires
  preparePrintView(doc);
  toast(forPdf ? 'Choose "Save as PDF" in the print dialog' : 'Opening print preview\u2026');
  setTimeout(function(){ try{ window.print(); }catch(e){} }, 120);
  setTimeout(function(){
    var pv=$('print-view'); if(pv) pv.remove();
  }, 2500);
}

$('dm-export-pdf').addEventListener('click', async function(){
  closeSheet(); await saveDoc();
  var doc = getDocContent();
  if(!doc){ toast('Open a document first', true); return; }
  doPrint(doc, true);
});

$('dm-print').addEventListener('click', async function(){
  closeSheet(); await saveDoc();
  var doc = getDocContent();
  if(!doc){ toast('Open a document first', true); return; }
  doPrint(doc, false);
});

function preparePrintView(doc){
  // Create a print-only container
  var existing = $('print-view');
  if(existing) existing.remove();
  var pv = document.createElement('div');
  pv.id = 'print-view';
  var html = '';
  if(doc.header) html += '<div class="print-header">' + escapeHtml(doc.header) + '</div>';
  html += '<h1 class="print-title">' + escapeHtml(doc.title) + '</h1>';
  html += '<div class="print-body">' + sanitize(doc.html) + '</div>';
  if(doc.footer) html += '<div class="print-footer">' + escapeHtml(doc.footer) + '</div>';
  pv.innerHTML = html;
  document.body.appendChild(pv);
}

// Clean up print view after printing
window.addEventListener('afterprint', function(){
  var pv = $('print-view');
  if(pv) pv.remove();
});

$('dm-export-docx').addEventListener('click', async function(){
  closeSheet(); await saveDoc();
  var doc = getDocContent();
  if(!doc){ toast('Open a document first', true); return; }
  
  if(typeof docx === 'undefined'){
    toast('Word export library not loaded. Check your connection.', true);
    return;
  }
  
  toast('Generating Word document\u2026');
  try{
    var children = [];
    
    // Title
    children.push(new docx.Paragraph({
      text: doc.title,
      heading: docx.HeadingLevel.TITLE,
      spacing: { after: 400 }
    }));
    
    // Header (if any)
    if(doc.header){
      children.push(new docx.Paragraph({
        text: doc.header,
        italics: true,
        spacing: { after: 200 }
      }));
    }
    
    // Convert HTML to paragraphs
    var tmp = document.createElement('div');
    tmp.innerHTML = sanitize(doc.html);
    htmlToDocxParagraphs(tmp, children);
    
    // Footer (if any)
    if(doc.footer){
      children.push(new docx.Paragraph({ text: '', spacing: { before: 400 } }));
      children.push(new docx.Paragraph({
        text: doc.footer,
        italics: true,
        alignment: docx.AlignmentType.CENTER
      }));
    }
    
    var docxDoc = new docx.Document({
      sections: [{ children: children }]
    });
    
    var blob = await docx.Packer.toBlob(docxDoc);
    var url = URL.createObjectURL(blob);
    var a = document.createElement('a');
    a.href = url;
    a.download = (doc.title.replace(/[^a-z0-9]+/gi, '-').toLowerCase() || 'document') + '.docx';
    document.body.appendChild(a);
    a.click();
    setTimeout(function(){
      document.body.removeChild(a);
      URL.revokeObjectURL(url);
    }, 100);
    toast('Word document downloaded');
  }catch(err){
    toast('Export failed: ' + (err.message || err), true);
  }
});

function htmlToDocxParagraphs(container, children){
  var nodes = container.childNodes;
  for(var i = 0; i < nodes.length; i++){
    var node = nodes[i];
    if(node.nodeType === 3){ // text node
      var text = node.textContent.trim();
      if(text){
        children.push(new docx.Paragraph({ text: text }));
      }
    }else if(node.nodeType === 1){
      var tag = node.tagName.toLowerCase();
      var text = node.textContent.trim();
      if(!text && tag !== 'br' && tag !== 'hr') continue;
      
      if(tag === 'h1'){
        children.push(new docx.Paragraph({ text: text, heading: docx.HeadingLevel.HEADING_1 }));
      }else if(tag === 'h2'){
        children.push(new docx.Paragraph({ text: text, heading: docx.HeadingLevel.HEADING_2 }));
      }else if(tag === 'h3'){
        children.push(new docx.Paragraph({ text: text, heading: docx.HeadingLevel.HEADING_3 }));
      }else if(tag === 'p' || tag === 'div'){
        if(text){
          // Check for formatting
          var runs = [];
          extractRuns(node, runs);
          if(runs.length){
            children.push(new docx.Paragraph({ children: runs }));
          }else{
            children.push(new docx.Paragraph({ text: text }));
          }
        }
      }else if(tag === 'ul' || tag === 'ol'){
        var items = node.querySelectorAll('li');
        for(var j = 0; j < items.length; j++){
          children.push(new docx.Paragraph({
            text: items[j].textContent.trim(),
            bullet: tag === 'ul' ? { level: 0 } : undefined,
            numbering: tag === 'ol' ? { reference: 'default-numbering', level: 0 } : undefined
          }));
        }
      }else if(tag === 'blockquote'){
        children.push(new docx.Paragraph({
          text: text,
          italics: true,
          indent: { left: 720 }
        }));
      }else if(tag === 'hr'){
        children.push(new docx.Paragraph({
          text: '\u2500'.repeat(50),
          alignment: docx.AlignmentType.CENTER
        }));
      }else if(tag === 'br'){
        children.push(new docx.Paragraph({ text: '' }));
      }
      // Skip images for now (complex to embed)
    }
  }
}

function extractRuns(node, runs){
  for(var i = 0; i < node.childNodes.length; i++){
    var child = node.childNodes[i];
    if(child.nodeType === 3){
      var text = child.textContent;
      if(text.trim()){
        runs.push(new docx.TextRun({ text: text }));
      }
    }else if(child.nodeType === 1){
      var tag = child.tagName.toLowerCase();
      var text = child.textContent;
      if(!text.trim()) continue;
      var opts = { text: text };
      if(tag === 'strong' || tag === 'b') opts.bold = true;
      if(tag === 'em' || tag === 'i') opts.italics = true;
      if(tag === 'u') opts.underline = {};
      if(tag === 's') opts.strike = true;
      runs.push(new docx.TextRun(opts));
    }
  }
}

/* ---------- doc menu: share / duplicate / delete ---------- */
$('btn-docmenu').addEventListener('click', function(){
  if(!cur) return;
  $('dm-title').textContent=$('doc-title').value.trim()||'Untitled document';
  $('dm-sub').textContent='Updated '+fmtDate(cur.row.updated_at);
  openSheet('sheet-docmenu');
});
$('dm-close').addEventListener('click', closeSheet);

function sharePageUrl(token){
  return new URL('shared.html?token='+encodeURIComponent(token), location.href).toString();
}
$('dm-share').addEventListener('click', async function(){
  closeSheet(); await saveDoc();
  var r=await sb.from('share_links').select('*').eq('document_id',cur.id).maybeSingle();
  if(r.error && r.error.code!=='PGRST116'){ toast(r.error.message,true); return; }
  renderShareSheet(r.data||null);
  openSheet('sheet-share');
});
function renderShareSheet(link){
  var has=!!link;
  $('share-none').style.display=has?'none':'block';
  $('share-exists').style.display=has?'block':'none';
  if(has){
    var url=sharePageUrl(link.share_token);
    var a=$('share-url'); a.href=url; a.textContent=url;
    $('share-copy-toggle').checked=!!link.allow_copy;
    $('share-copy-toggle').onchange=async function(){
      var u=await sb.from('share_links').update({allow_copy:$('share-copy-toggle').checked}).eq('id',link.id);
      if(u.error) toast(u.error.message,true); else toast($('share-copy-toggle').checked?'Readers can copy text':'Copying disabled for readers');
    };
  }
}
function genToken(){
  var b=new Uint8Array(24); (window.crypto||{}).getRandomValues ? crypto.getRandomValues(b) : b.forEach(function(_,i){b[i]=Math.floor(Math.random()*256);});
  var s=''; for(var i=0;i<b.length;i++) s+=String.fromCharCode(b[i]);
  return btoa(s).replace(/\+/g,'-').replace(/\//g,'_').replace(/=+$/,'');
}
$('share-create').addEventListener('click', async function(){
  var btn=$('share-create'); btn.disabled=true;
  try{
    var r=await sb.from('share_links').upsert({
      user_id:currentUser.id, document_id:cur.id, share_token:genToken(), allow_copy:false
    },{onConflict:'document_id'}).select().single();
    if(r.error) throw r.error;
    renderShareSheet(r.data); toast('Share link created');
  }catch(err){ toast('Couldn\u2019t create link: '+(err.message||err), true); }
  btn.disabled=false;
});
$('share-copybtn').addEventListener('click', async function(){
  var url=$('share-url').href;
  try{ await navigator.clipboard.writeText(url); toast('Link copied'); }
  catch(e){
    var ta=document.createElement('textarea'); ta.value=url; document.body.appendChild(ta);
    ta.select(); try{document.execCommand('copy'); toast('Link copied');}catch(_){toast('Copy this link manually',true);}
    document.body.removeChild(ta);
  }
});
$('share-revoke').addEventListener('click', async function(){
  var ok=await confirmDlg('Revoke share link?','The link stops working immediately for anyone who has it.','Revoke link');
  if(!ok) return;
  var r=await sb.from('share_links').delete().eq('document_id',cur.id);
  if(r.error){ toast(r.error.message,true); return; }
  renderShareSheet(null); toast('Share link revoked');
});
$('share-close').addEventListener('click', closeSheet);

$('dm-duplicate').addEventListener('click', async function(){
  closeSheet(); await saveDoc();
  toast('Duplicating\u2026');
  try{
    var src=cur.row;
    var ni=await sb.from('documents').insert({
      user_id:currentUser.id, title:(src.title||'Untitled')+' (copy)',
      content_html:src.content_html, header_text:src.header_text, footer_text:src.footer_text,
      show_page_numbers:src.show_page_numbers, template:src.template
    }).select().single();
    if(ni.error) throw ni.error;
    var newId=ni.data.id;
    // duplicate images: copy bytes to new storage path
    var im=await sb.from('document_images').select('*').eq('document_id',cur.id);
    if(!im.error){
      for(var k=0;k<im.data.length;k++){
        var row=im.data[k];
        try{
          var dl=await sb.storage.from(IMG_BUCKET).download(row.storage_path);
          if(dl.error) continue;
          var newImgId=(crypto.randomUUID?crypto.randomUUID():'img-'+Date.now()+'-'+k);
          var newPath=currentUser.id+'/'+newId+'/'+newImgId;
          var up2=await sb.storage.from(IMG_BUCKET).upload(newPath, dl.data, {contentType:row.mime});
          if(up2.error) continue;
          await sb.from('document_images').insert({id:newImgId,user_id:currentUser.id,document_id:newId,mime:row.mime,storage_path:newPath});
        }catch(e){/* skip broken image */}
      }
      // rewrite data-sp paths in the copy's html
      var tmp=document.createElement('div'); tmp.innerHTML=ni.data.content_html||'';
      // re-fetch new image rows to build old->new path map
      var nim=await sb.from('document_images').select('*').eq('document_id',newId);
      if(!nim.error && nim.data.length===(im.data||[]).length){
        (im.data||[]).forEach(function(oldRow,idx){ map[oldRow.storage_path]=nim.data[idx].storage_path; });
        tmp.querySelectorAll('img[data-sp]').forEach(function(img){
          var np=map[img.getAttribute('data-sp')]; if(np) img.setAttribute('data-sp',np);
        });
        var fixed=await refreshImageUrls(tmp.innerHTML);
        await sb.from('documents').update({content_html:sanitize(fixed)}).eq('id',newId);
      }
    }
    toast('Duplicated'); loadDocs(); openDoc(newId);
  }catch(err){ toast('Duplicate failed: '+(err.message||err), true); }
});

$('dm-delete').addEventListener('click', async function(){
  closeSheet();
  var ok=await confirmDlg('Delete this document?','The document, its images, and its share link are gone for good.','Delete forever');
  if(!ok || !cur) return;
  try{
    await sb.from('share_links').delete().eq('document_id',cur.id);
    var im=await sb.from('document_images').select('storage_path').eq('document_id',cur.id);
    if(!im.error && im.data.length){
      await sb.storage.from(IMG_BUCKET).remove(im.data.map(function(x){return x.storage_path;}));
      await sb.from('document_images').delete().eq('document_id',cur.id);
    }
    var d=await sb.from('documents').delete().eq('id',cur.id);
    if(d.error) throw d.error;
    cur=null; showView('view-list'); loadDocs(); toast('Document deleted');
  }catch(err){ toast('Delete failed: '+(err.message||err), true); }
});

/* ---------- boot ---------- */
async function boot(){
  setAuthMode('login');
  try{
    var s=await sb.auth.getSession();
    if(s.data && s.data.session){ onSignedIn(s.data.session.user); }
    else showView('view-auth');
  }catch(e){ showView('view-auth'); }
  sb.auth.onAuthStateChange(function(evt, session){
    if(evt==='SIGNED_OUT'){ currentUser=null; showView('view-auth'); }
  });
}
document.addEventListener('DOMContentLoaded', boot);

})();
