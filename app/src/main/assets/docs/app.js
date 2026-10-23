/* Makryden Docs - unified suite home. Supabase backend, no build step. */
(function(){
'use strict';

var SUPABASE_URL = 'https://bamrgsjgvakyucyqtwlg.supabase.co';
var SUPABASE_ANON_KEY = 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImJhbXJnc2pndmFreXVjeXF0d2xnIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODExMjUwMzAsImV4cCI6MjA5NjcwMTAzMH0.4rW9kVK1E-YFvjBvQd-VTPh5pd_O7ZTzYAx_BkyPbH0';
var sb = window.supabase.createClient(SUPABASE_URL, SUPABASE_ANON_KEY);

var currentUser = null;
var recents = [];
var filter = '';

function $(id){ return document.getElementById(id); }
function el(tag, cls, text){
  var e = document.createElement(tag);
  if(cls) e.className = cls;
  if(text != null) e.textContent = text;
  return e;
}
function esc(s){ return String(s==null?'':s); }
function toast(msg){
  var t = $('toast'); t.textContent = msg; t.classList.add('show');
  clearTimeout(t._h); t._h = setTimeout(function(){ t.classList.remove('show'); }, 2600);
}
function relTime(iso){
  var d = new Date(iso).getTime(), now = Date.now(), s = Math.max(1, Math.floor((now - d)/1000));
  if(s < 60) return 'just now';
  var m = Math.floor(s/60); if(m < 60) return m + 'm ago';
  var h = Math.floor(m/60); if(h < 24) return h + 'h ago';
  var days = Math.floor(h/24); if(days < 7) return days + 'd ago';
  return new Date(iso).toLocaleDateString('en-US', {month:'short', day:'numeric', year:'numeric'});
}
function fmtSize(b){
  if(!b) return '';
  if(b < 1024) return b + ' B';
  if(b < 1048576) return Math.round(b/1024) + ' KB';
  return (b/1048576).toFixed(1) + ' MB';
}

/* ---------- recents ---------- */
function pdfRecents(){
  try{
    var list = JSON.parse(localStorage.getItem('mk-docs-recent-pdfs') || '[]');
    return list.map(function(p){
      return { kind:'pdf', id:null, title:p.name, sub:fmtSize(p.size), at:p.openedAt, url:pdfUrl('pdf/index.html') };
    });
  }catch(e){ return []; }
}

async function loadRecents(){
  var docs = [], notes = [], pdfs = [];
  if(currentUser){
    var r = await sb.from('documents').select('id,title,updated_at').order('updated_at', {ascending:false}).limit(20);
    if(!r.error) docs = (r.data||[]).map(function(d){
      return { kind:'doc', id:d.id, title:d.title || 'Untitled document', sub:'', at:d.updated_at, url:pdfUrl('writer/index.html?id=' + d.id) };
    });
    var n = await sb.from('notes').select('id,title,updated_at').order('updated_at', {ascending:false}).limit(20);
    if(!n.error) notes = (n.data||[]).map(function(x){
      return { kind:'note', id:x.id, title:x.title || 'Untitled', sub:'', at:x.updated_at, url:pdfUrl('notes/index.html?id=' + x.id) };
    });
    var p = await sb.from('pdf_documents').select('id,filename,file_size,created_at').order('created_at', {ascending:false}).limit(20);
    if(!p.error) pdfs = (p.data||[]).map(function(x){
      return { kind:'pdf', id:x.id, title:x.filename || 'Untitled PDF', sub:fmtSize(x.file_size||0), at:x.created_at, url:pdfUrl('pdf/index.html?id=' + x.id) };
    });
  }
  recents = docs.concat(notes).concat(pdfs).concat(pdfRecents())
    .sort(function(a,b){ return new Date(b.at) - new Date(a.at); })
    .slice(0, 20);
  renderRecents();
}

/* URL helper: file:// for app, relative for website */
function pdfUrl(path){
  if(window.location.protocol === 'file:') return 'file:///android_asset/' + path;
  var base = window.location.pathname;
  /* /docs/index.html -> / ; /docs/ -> / */
  var root = base.replace(/\/docs\/.*$/, '/');
  /* path like 'writer/index.html?id=...' -> '/writer/index.html?id=...' */
  return root + path;
}

var PILL = { note:['NOTE','pill-note'], doc:['DOCUMENT','pill-doc'], pdf:['PDF','pill-pdf'] };

function renderRecents(){
  var list = $('recent-list'); list.innerHTML = '';
  var q = filter.trim().toLowerCase();
  var rows = recents.filter(function(r){ return !q || (r.title||'').toLowerCase().indexOf(q) >= 0; });
  // signed-out users only see local PDFs (already the only items in recents)
  var showSignin = !currentUser && !rows.length && !q;
  var showEmpty = !!currentUser && !rows.length && !q;
  $('recent-signin').hidden = !showSignin;
  $('recent-empty').hidden = !showEmpty;
  rows.forEach(function(r){
    var a = el('a', 'recent-row'); a.href = r.url;
    var pill = el('span', 'pill ' + PILL[r.kind][1], PILL[r.kind][0]);
    var mid = el('div', 'recent-mid');
    mid.appendChild(el('div', 'recent-title', r.title));
    var sub = el('div', 'recent-sub');
    sub.appendChild(el('span', '', relTime(r.at)));
    if(r.sub) sub.appendChild(el('span', '', ' \u00b7 ' + r.sub));
    mid.appendChild(sub);
    a.appendChild(pill); a.appendChild(mid);
    a.appendChild(el('span', 'chev', '\u203a'));
    list.appendChild(a);
  });
  if(currentUser && !rows.length && q){
    var d = el('div', 'empty');
    d.innerHTML = '<h3>No matches</h3><p>Try a different search.</p>';
    list.appendChild(d);
  }
}

/* ---------- create ---------- */
async function ensureUser(){
  if(currentUser) return true;
  toast('Sign in first');
  setTimeout(function(){ location.href = 'https://makrydensystems.com/account/login/'; }, 800);
  return false;
}

async function newNote(){
  if(!(await ensureUser())) return;
  var id = (crypto.randomUUID ? crypto.randomUUID() : 'n' + Date.now());
  var row = { id:id, user_id:currentUser.id, title:'', doc:{type:'doc', content:[{type:'paragraph'}]}, text:'', tags:[], folder_id:null, pinned:false };
  var r = await sb.from('notes').insert(row);
  if(r.error){ toast('Could not create note'); return; }
  location.href = pdfUrl('notes/index.html?id=' + id);
}

async function newDoc(){
  if(!(await ensureUser())) return;
  var r = await sb.from('documents').insert({
    user_id:currentUser.id, title:'Untitled document', content_html:'', template:'blank'
  }).select().single();
  if(r.error){ toast('Could not create document'); return; }
  location.href = pdfUrl('writer/index.html?id=' + r.data.id);
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

/* ---------- boot ---------- */
async function boot(){
  await adoptPortalSession(sb);
  $('search').addEventListener('input', function(e){ filter = e.target.value; renderRecents(); });
  $('new-note').addEventListener('click', newNote);
  $('new-doc').addEventListener('click', newDoc);
  $('open-pdf').addEventListener('click', function(){ location.href = pdfUrl('pdf/index.html'); });

  try{
    var s = await sb.auth.getSession();
    if(s.data && s.data.session){
      currentUser = s.data.session.user;
      var em = currentUser.email || '';
      $('acct-pill').textContent = (em[0] || 'M').toUpperCase();
      $('auth-cta').hidden = true;
    } else {
      $('acct-pill').textContent = '?';
      $('auth-cta').hidden = false;
    }
  }catch(e){
    $('auth-cta').hidden = false;
  }
  sb.auth.onAuthStateChange(function(evt, session){
    if(evt === 'SIGNED_OUT'){ currentUser = null; location.reload(); }
  });

  await loadRecents();
}

document.addEventListener('DOMContentLoaded', boot);
})();
