const STORAGE_KEY = 'nexustv_remote_token';
let userInteracted = false;
['pointerdown', 'touchstart', 'click', 'keydown'].forEach(evt => {
  window.addEventListener(evt, () => { userInteracted = true; }, { once: true, passive: true });
});
function vib() {
  if (!userInteracted) return;
  try {
    if (navigator.vibrate) navigator.vibrate(12);
  } catch (e) {}
}
let ws = null;
let wsFailCount = 0;
let pollTimer = null;
let serverBuildToken = null;
let toastTimer = null;

function setConnectedStatus(connected) {
  const dot = document.getElementById('statusDot');
  if (dot) {
    if (connected) dot.classList.remove('connecting');
    else dot.classList.add('connecting');
  }
}

function startPollingFallback() {
  if (pollTimer) return;
  pollTimer = setInterval(() => {
    fetch('/api/status')
      .then(r => r.json())
      .then(data => {
        setConnectedStatus(true);
        if (data.version && serverBuildToken !== null && serverBuildToken !== data.version) {
          serverBuildToken = data.version;
          doAutoReload('build updated via poll');
          return;
        }
        if (data.version) serverBuildToken = data.version;
        if (data.spotify) updateSpotifyUi(data.spotify);
      })
      .catch(() => {
        setConnectedStatus(false);
      });
  }, 4000);
}

function stopPollingFallback() {
  if (pollTimer) {
    clearInterval(pollTimer);
    pollTimer = null;
  }
}

function showToast(msg) {
  const t = document.getElementById('toast');
  if (t) {
    t.innerText = msg;
    t.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => t.classList.remove('show'), 3500);
  }
}

function doAutoReload(reason) {
  const lastReload = parseInt(sessionStorage.getItem('last_auto_reload') || '0', 10);
  const now = Date.now();
  if (now - lastReload < 10000) {
    console.warn('Reload loop prevented:', reason);
    return;
  }
  sessionStorage.setItem('last_auto_reload', String(now));
  console.log('Auto-reloading remote:', reason);
  showToast('Atualização recebida! Recarregando...');
  setTimeout(() => window.location.reload(), 600);
}

function initWs() {
  if (wsFailCount >= 3) {
    startPollingFallback();
    setTimeout(() => { wsFailCount = 0; initWs(); }, 25000);
    return;
  }
  try {
    const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    ws = new WebSocket(proto + '//' + location.host + '/ws');
    ws.onopen = () => {
      wsFailCount = 0;
      stopPollingFallback();
      setConnectedStatus(true);
    };
    ws.onmessage = (e) => {
      try {
        const msg = JSON.parse(e.data);
        if (msg.type === 'reload' || msg.type === 'update') {
          doAutoReload('server reload broadcast');
          return;
        }
        if (msg.type === 'init') {
          if (serverBuildToken !== null && msg.version && serverBuildToken !== msg.version) {
            serverBuildToken = msg.version;
            doAutoReload('build updated');
            return;
          }
          serverBuildToken = msg.version;
          if (msg.spotify) updateSpotifyUi(msg.spotify);
          if (msg.apps) renderApps(msg.apps);
          if (msg.notifications) updateNotificationsUi(msg.notifications);
        }
        if (msg.type === 'spotify') {
          updateSpotifyUi(msg.data || msg);
        }
        if (msg.type === 'apps') {
          renderApps(msg.data || msg);
        }
        if (msg.type === 'notifications') {
          updateNotificationsUi(msg.data || msg);
        }
      } catch (err) {}
    };
    ws.onclose = () => {
      wsFailCount++;
      if (wsFailCount >= 3) {
        startPollingFallback();
        setTimeout(() => { wsFailCount = 0; initWs(); }, 25000);
      } else {
        setTimeout(initWs, 3000);
      }
    };
    ws.onerror = () => { try { ws.close(); } catch (e) {} };
  } catch (e) {
    wsFailCount++;
    startPollingFallback();
    setTimeout(initWs, 5000);
  }
}
initWs();

function selectTab(name, btn) {
  vib();
  try { sessionStorage.setItem('nexus_tab', name); } catch (e) {}
  document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));
  document.querySelectorAll('.tab-page').forEach(p => p.classList.remove('active'));
  if (btn) btn.classList.add('active');
  if (name === 'remote') document.getElementById('pageRemote').classList.add('active');
  if (name === 'touchpad') document.getElementById('pageTouchpad').classList.add('active');
  if (name === 'spotify') { document.getElementById('pageSpotify').classList.add('active'); refreshSpotify(); }
  if (name === 'notif') { document.getElementById('pageNotif').classList.add('active'); refreshNotif(); }
  if (name === 'apps') { document.getElementById('pageApps').classList.add('active'); loadApps(); }
}

function api(endpoint, data) {
  const token = localStorage.getItem(STORAGE_KEY) || localStorage.getItem('freemytv_remote_token') || '';
  return fetch('/api/' + endpoint, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + token },
    body: JSON.stringify(data || {})
  }).then(r => r.json()).catch(() => ({}));
}

function sendKey(key) {
  vib();
  if (ws && ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify({ type: 'key', key }));
  } else {
    api('key', { key });
  }
}

function sendKeyDown(key) {
  vib();
  if (ws && ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify({ type: 'keydown', key }));
  } else {
    api('key', { key });
  }
}

function sendKeyUp(key) {
  if (ws && ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify({ type: 'keyup', key }));
  }
}

let activePressedKey = null;

function bindDataKeyButtons() {
  document.querySelectorAll('[data-key]').forEach(btn => {
    const key = btn.getAttribute('data-key');
    if (!key) return;

    btn.addEventListener('contextmenu', e => e.preventDefault());

    btn.addEventListener('pointerdown', (e) => {
      e.preventDefault();
      activePressedKey = key;
      sendKeyDown(key);
    });

    const release = (e) => {
      if (activePressedKey === key) {
        e.preventDefault();
        sendKeyUp(key);
        activePressedKey = null;
      }
    };

    btn.addEventListener('pointerup', release);
    btn.addEventListener('pointercancel', release);
    btn.addEventListener('pointerleave', release);
  });
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', bindDataKeyButtons);
} else {
  bindDataKeyButtons();
}

function sendNav(target) {
  vib();
  if (ws && ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify({ type: 'nav', target }));
  } else {
    api('nav', { target });
  }
}

function sendScroll(wheel) {
  if (ws && ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify({ type: 'scroll', wheel }));
  }
}

function sendClick() {
  sendKey('enter');
}

function sendLaunch(pkg) {
  vib();
  if (ws && ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify({ type: 'launch', pkg }));
  } else {
    api('launch', { pkg });
  }
}

/* REAL-TIME SPOTIFY ENGINE (FAST CENTER MIOLO UPDATER) */
let curSpTrack = '';
let curSpArtist = '';
let curSpPlaying = false;
let curSpHasArt = false;
let curSpArtUrl = '';
let spAutoPollInterval = null;
let lastSpClickTime = 0;

function sendSpotify(action) {
  vib();
  const now = Date.now();
  if (now - lastSpClickTime < 350) return;
  lastSpClickTime = now;

  const titleEl = document.getElementById('spotifyTitle');
  const playBtn = document.getElementById('spotifyPlayPauseBtn');
  const playIcon = document.getElementById('spotifyPlayIcon');

  if (action === 'next' || action === 'prev') {
    if (titleEl) {
      titleEl.style.opacity = '0.5';
      titleEl.innerText = action === 'next' ? 'Avançando faixa...' : 'Voltando faixa...';
    }
  } else if (action === 'playPause') {
    const wasPlaying = curSpPlaying;
    curSpPlaying = !wasPlaying;
    if (playBtn) {
      if (curSpPlaying) playBtn.classList.add('playing');
      else playBtn.classList.remove('playing');
    }
    if (playIcon) {
      playIcon.className = curSpPlaying ? 'mdi mdi-pause' : 'mdi mdi-play';
    }
    if (wasPlaying && titleEl) {
      titleEl.style.opacity = '0.7';
      titleEl.innerText = 'Pausado (fechando Spotify)...';
    }
  }

  // Send action only once (prefer WebSocket, fallback to HTTP API)
  if (ws && ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify({ type: 'spotify', action }));
  } else {
    api('spotify/action', { action });
  }

  triggerSpotifyBurst();
}

function triggerSpotifyBurst() {
  const oldTrack = curSpTrack;
  const delays = [250, 500, 800, 1200, 1800, 2600, 3500];
  delays.forEach(d => {
    setTimeout(() => {
      fetch('/api/spotify/state')
        .then(r => r.json())
        .then(sp => {
          if (sp && (sp.track !== oldTrack || sp.playing !== curSpPlaying)) {
            updateSpotifyUi(sp);
          }
        })
        .catch(() => {});
    }, d);
  });
}

function refreshSpotify() {
  fetch('/api/spotify/state')
    .then(r => r.json())
    .then(updateSpotifyUi)
    .catch(() => {});
}

function formatSecs(sec) {
  if (!sec || sec < 0) return '0:00';
  const m = Math.floor(sec / 60);
  const s = Math.floor(sec % 60);
  return m + ':' + (s < 10 ? '0' : '') + s;
}

function updateSpotifyUi(sp) {
  if (!sp) return;
  const trackChanged = sp.track !== curSpTrack;
  curSpTrack = sp.track || '';
  curSpArtist = sp.artist || '';
  curSpPlaying = !!sp.playing;
  curSpHasArt = !!sp.hasArt;
  curSpArtUrl = sp.artUrl || '';

  const titleEl = document.getElementById('spotifyTitle');
  const artistEl = document.getElementById('spotifyArtist');
  const artEl = document.getElementById('spotifyArt');
  const phEl = document.getElementById('spotifyPlaceholder');
  const playIcon = document.getElementById('spotifyPlayIcon');
  const playBtn = document.getElementById('spotifyPlayPauseBtn');

  if (titleEl) {
    titleEl.style.opacity = '1';
    titleEl.innerText = (curSpTrack && curSpTrack.length) ? curSpTrack : 'Nenhuma música tocando';
  }
  if (artistEl) {
    artistEl.innerText = (curSpArtist && curSpArtist.length) ? curSpArtist : ((curSpTrack && curSpTrack.length) ? 'Spotify na TV' : 'Abra o Spotify para tocar');
  }
  if (playIcon) {
    playIcon.className = curSpPlaying ? 'mdi mdi-pause' : 'mdi mdi-play';
  }
  if (playBtn) {
    if (curSpPlaying) playBtn.classList.add('playing');
    else playBtn.classList.remove('playing');
  }

  const curTimeEl = document.getElementById('spotifyCurTime');
  const durTimeEl = document.getElementById('spotifyDurTime');
  const fillEl = document.getElementById('spotifyProgressFill');
  if (sp.duration && sp.duration > 0) {
    const pos = Math.min(sp.duration, sp.position || 0);
    const dur = sp.duration;
    const pct = Math.min(100, Math.max(0, (pos / dur) * 100));
    if (fillEl) fillEl.style.width = pct + '%';
    if (curTimeEl) curTimeEl.innerText = formatSecs(Math.floor(pos / 1000));
    if (durTimeEl) durTimeEl.innerText = formatSecs(Math.floor(dur / 1000));
  } else if (!curSpTrack) {
    if (fillEl) fillEl.style.width = '0%';
    if (curTimeEl) curTimeEl.innerText = '0:00';
    if (durTimeEl) durTimeEl.innerText = '0:00';
  }
  if (artEl) {
    // High-resolution real album photo: direct Spotify CDN URL preferred!
    const artSrc = curSpArtUrl || ((curSpHasArt || (curSpTrack && curSpTrack.length)) ? ('/api/spotify/art?t=' + Date.now()) : null);
    if (artSrc) {
      if (trackChanged || !artEl.src || artEl.style.display === 'none') {
        const preImg = new Image();
        preImg.onload = function() {
          artEl.src = artSrc;
          artEl.style.display = 'block';
          if (phEl) phEl.style.display = 'none';
        };
        preImg.onerror = function() {
          if (artSrc !== '/api/spotify/art?t=' + Date.now()) {
            artEl.src = '/api/spotify/art?t=' + Date.now();
          } else {
            artEl.style.display = 'none';
            if (phEl) phEl.style.display = 'flex';
          }
        };
        preImg.src = artSrc;
      }
    } else {
      artEl.style.display = 'none';
      if (phEl) phEl.style.display = 'flex';
    }
  }
}

function startSpotifyAutoPoll() {
  if (spAutoPollInterval) clearInterval(spAutoPollInterval);
  spAutoPollInterval = setInterval(() => {
    const spPage = document.getElementById('pageSpotify');
    if (spPage && spPage.classList.contains('active')) {
      fetch('/api/spotify/state')
        .then(r => r.json())
        .then(sp => {
          if (sp && (sp.track !== curSpTrack || sp.artist !== curSpArtist || sp.playing !== curSpPlaying || sp.artUrl !== curSpArtUrl)) {
            updateSpotifyUi(sp);
          }
        })
        .catch(() => {});
    }
  }, 2000);
}
startSpotifyAutoPoll();

/* NOTIFICATIONS UI */
function refreshNotif() {
  fetch('/api/notifications').then(r => r.json()).then(updateNotificationsUi).catch(() => {});
}
function updateNotificationsUi(data) {
  if (!data) return;
  const count = data.count || (data.items ? data.items.length : 0);
  const countPill = document.getElementById('notifCountPill');
  if (countPill) countPill.innerText = count;
  const tabBadge = document.getElementById('tabNotifBadge');
  if (tabBadge) {
    tabBadge.innerText = count;
    tabBadge.style.display = count > 0 ? 'block' : 'none';
  }
  const list = document.getElementById('notifList');
  if (!list) return;
  const items = data.items || [];
  if (!items.length) {
    list.innerHTML = '<div style="color:#888; text-align:center; padding:30px;">Nenhuma notificação na TV</div>';
    return;
  }
  list.innerHTML = items.map(it => `
    <div class="notif-card">
      <div class="notif-card-body">
        <div class="notif-app-tag">${it.appName || it.pkg || 'Sistema'}</div>
        <div class="notif-item-title">${it.title || ''}</div>
        <div class="notif-item-text">${it.text || ''}</div>
      </div>
      <button class="notif-dismiss-btn" onclick="sendDismissNotification('${it.key}')">✕</button>
    </div>
  `).join('');
}
function sendDismissNotification(key) {
  vib();
  if (ws && ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify({ type: 'notif_dismiss', key }));
  } else {
    api('notifications/dismiss', { key });
  }
  setTimeout(refreshNotif, 200);
}
function sendClearNotifications() {
  vib();
  if (ws && ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify({ type: 'notif_clear' }));
  } else {
    api('notifications/clear', {});
  }
  setTimeout(refreshNotif, 200);
}

/* TOUCHPAD GESTURES */
let touchMode = 'dpad';
function setTouchMode(mode) {
  touchMode = mode;
  const hint = document.getElementById('touchpadHint');
  if (hint) {
    hint.innerHTML = mode === 'mouse'
      ? 'Deslize para mover o cursor suavemente<br>Toque para clicar<br>Deslize com 2 dedos para rolar'
      : 'Deslize para navegar (cima, baixo, esquerda, direita)<br>Toque no centro para confirmar (OK)';
  }
}
const pad = document.getElementById('touchpad');
let startX = 0, startY = 0, lastX = 0, lastY = 0;
let touching = false, hasMoved = false;
let startT = 0;
let twoFingerScroll = false;
let startDistY = 0;

pad.addEventListener('touchstart', (e) => {
  if (e.touches.length === 1) {
    touching = true;
    hasMoved = false;
    startX = e.touches[0].clientX;
    startY = e.touches[0].clientY;
    lastX = startX;
    lastY = startY;
    startT = Date.now();
    twoFingerScroll = false;
  } else if (e.touches.length === 2) {
    twoFingerScroll = true;
    startDistY = (e.touches[0].clientY + e.touches[1].clientY) / 2;
  }
}, { passive: false });

pad.addEventListener('touchmove', (e) => {
  e.preventDefault();
  if (twoFingerScroll && e.touches.length === 2) {
    const curY = (e.touches[0].clientY + e.touches[1].clientY) / 2;
    const dy = curY - startDistY;
    if (Math.abs(dy) > 10) {
      sendScroll(dy > 0 ? 1 : -1);
      startDistY = curY;
    }
    return;
  }
  if (!touching || e.touches.length !== 1) return;
  const curX = e.touches[0].clientX;
  const curY = e.touches[0].clientY;
  const dx = curX - lastX;
  const dy = curY - lastY;
  if (Math.abs(curX - startX) > 8 || Math.abs(curY - startY) > 8) {
    hasMoved = true;
  }
  if (touchMode === 'mouse') {
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({ type: 'move', dx: Math.round(dx * 1.5), dy: Math.round(dy * 1.5) }));
    }
  }
  lastX = curX;
  lastY = curY;
}, { passive: false });

pad.addEventListener('touchend', (e) => {
  if (twoFingerScroll) {
    twoFingerScroll = false;
    return;
  }
  if (touching && !hasMoved && (Date.now() - startT < 300)) {
    sendKey('enter');
  } else if (touching && hasMoved && touchMode === 'dpad') {
    const totalDx = lastX - startX;
    const totalDy = lastY - startY;
    if (Math.abs(totalDx) > Math.abs(totalDy)) {
      if (totalDx > 30) sendKey('right');
      else if (totalDx < -30) sendKey('left');
    } else {
      if (totalDy > 30) sendKey('down');
      else if (totalDy < -30) sendKey('up');
    }
  }
  touching = false;
  hasMoved = false;
  twoFingerScroll = false;
});

/* APPS LOADING */
function loadApps() {
  fetch('/api/apps').then(r => r.json()).then(renderApps).catch(() => {});
}
function renderApps(apps) {
  const list = document.getElementById('appsList');
  if (!list) return;
  if (!apps || !apps.length) {
    list.innerHTML = '<div style="color:#888; text-align:center; grid-column:1/-1; padding:20px;">Nenhum app favorito no Nexus Launcher</div>';
    return;
  }
  list.innerHTML = apps.map(a => `
    <div class="app-card" onclick="sendLaunch('${a.pkg}')">
      <div class="app-icon-wrap">
        <img class="app-icon-img" src="/api/app/icon?pkg=${encodeURIComponent(a.pkg)}" alt="${a.label}" onerror="this.onerror=null;this.src='/api/spotify/icon';" />
      </div>
      <div class="app-name">${a.label}</div>
    </div>
  `).join('');
}

/* PAIRING AND PERMANENT SESSION */
function submitPairCode() {
  const input = document.getElementById('pairCodeInput');
  const code = (input ? input.value : '').trim();
  if (!code) return;
  document.getElementById('pairingStatus').innerText = 'Conectando ao Nexus TV...';
  api('pair', { pairToken: code }).then(res => {
    if (res && res.success && res.token) {
      localStorage.setItem(STORAGE_KEY, res.token);
      window.history.replaceState({}, document.title, window.location.pathname);
      document.getElementById('pairingModal').style.display = 'none';
      showToast('Conectado ao Nexus TV!');
    } else {
      document.getElementById('pairingStatus').innerText = 'Código incorreto ou expirado. Tente novamente.';
    }
  }).catch(() => {
    document.getElementById('pairingStatus').innerText = 'Erro ao conectar. Verifique o Wi-Fi da TV.';
  });
}

function checkAuth() {
  const urlParams = new URLSearchParams(window.location.search);
  let pairFromUrl = urlParams.get('pair') || urlParams.get('code') || '';
  if (pairFromUrl) {
    if (pairFromUrl.indexOf('?') >= 0) pairFromUrl = pairFromUrl.split('?')[0];
    if (pairFromUrl.indexOf('&') >= 0) pairFromUrl = pairFromUrl.split('&')[0];
    pairFromUrl = pairFromUrl.trim();
    const input = document.getElementById('pairCodeInput');
    if (input) input.value = pairFromUrl;
    showPairModal();
    document.getElementById('pairingStatus').innerText = 'Conectando ao Nexus TV...';
    api('pair', { pairToken: pairFromUrl }).then(res => {
      if (res && res.success && res.token) {
        localStorage.setItem(STORAGE_KEY, res.token);
        window.history.replaceState({}, document.title, window.location.pathname);
        document.getElementById('pairingModal').style.display = 'none';
      } else {
        document.getElementById('pairingStatus').innerText = 'Falha no emparelhamento. Aponte novamente para o QR Code na TV ou digite o código.';
      }
    }).catch(() => {
      document.getElementById('pairingStatus').innerText = 'Erro ao conectar. Verifique o Wi-Fi da TV.';
    });
    return;
  }
  const token = localStorage.getItem(STORAGE_KEY) || localStorage.getItem('freemytv_remote_token');
  if (!token) {
    showPairModal();
    return;
  }
  api('auth', { token }).then(res => {
    if (!res || !res.authorized) {
      localStorage.removeItem(STORAGE_KEY);
      localStorage.removeItem('freemytv_remote_token');
      showPairModal();
    } else {
      document.getElementById('pairingModal').style.display = 'none';
    }
  }).catch(() => {});
}

function showPairModal() {
  document.getElementById('pairingModal').style.display = 'flex';
  document.getElementById('pairingStatus').innerText = 'Aponte a câmera do celular para o QR Code na TV para conectar instantaneamente ao Nexus TV.';
}

function doLogout() {
  if (!confirm('Deseja desconectar este controle do Nexus TV?')) return;
  api('logout', {}).then(() => {
    localStorage.removeItem(STORAGE_KEY);
    localStorage.removeItem('freemytv_remote_token');
    showPairModal();
  });
}

checkAuth();
try {
  const lastTab = sessionStorage.getItem('nexus_tab');
  if (lastTab) {
    const btn = document.querySelector(`.tab-btn[onclick*="'${lastTab}'"]`);
    if (btn) selectTab(lastTab, btn);
  }
} catch (e) {}


