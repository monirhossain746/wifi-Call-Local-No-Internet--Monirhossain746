(function () {
  const canCaptureMedia = !!(window.isSecureContext && navigator.mediaDevices && navigator.mediaDevices.getUserMedia);

  const state = {
    webUserId: sessionStorage.getItem('wcl_web_id') || ('web-' + Math.random().toString(36).slice(2, 10)),
    displayName: sessionStorage.getItem('wcl_web_name') || '',
    activeChatId: 'everyone_room',
    activeTargetPeerId: null,
    ws: null,
    messages: [],
    peers: [],
    groups: [],
    callLogs: [],
    replyTo: null,
    zoomScale: 1,
    typingTimer: null,
    call: {
      active: false,
      callId: null,
      peerId: null,
      peerName: '',
      callType: 'VOICE',
      isIncoming: false,
      pc: null,
      localStream: null,
      muted: false,
      videoOn: true,
      timerHandle: null,
      connectedAt: 0,
      pendingOfferSdp: null
    }
  };
  sessionStorage.setItem('wcl_web_id', state.webUserId);

  const el = {
    hostInfo: document.getElementById('host-info'),
    connPill: document.getElementById('conn-pill'),
    httpsBanner: document.getElementById('https-notice-banner'),
    themeToggleBtn: document.getElementById('theme-toggle-btn'),
    joinModal: document.getElementById('join-modal'),
    nameInput: document.getElementById('web-name-input'),
    joinBtn: document.getElementById('join-btn'),
    qrModal: document.getElementById('qr-modal'),
    qrToggleBtn: document.getElementById('qr-toggle-btn'),
    qrCloseBtn: document.getElementById('qr-close-btn'),
    qrCanvas: document.getElementById('web-qr-canvas'),
    qrUrl: document.getElementById('web-qr-url'),
    groupsList: document.getElementById('groups-list'),
    groupsCount: document.getElementById('groups-count'),
    peersList: document.getElementById('peers-list'),
    peersCount: document.getElementById('peers-count'),
    callsList: document.getElementById('calls-list'),
    messagesBox: document.getElementById('messages-box'),
    composerForm: document.getElementById('composer-form'),
    msgInput: document.getElementById('msg-input'),
    fileInput: document.getElementById('file-input'),
    roomTitle: document.getElementById('active-room-title'),
    roomSub: document.getElementById('active-room-sub'),
    roomAvatar: document.getElementById('active-room-avatar'),
    btnDmVoice: document.getElementById('btn-dm-voice'),
    btnDmVideo: document.getElementById('btn-dm-video'),
    meBadge: document.getElementById('me-badge'),
    typingBanner: document.getElementById('typing-banner'),
    replyBanner: document.getElementById('reply-banner'),
    replyBannerText: document.getElementById('reply-banner-text'),
    replyCancelBtn: document.getElementById('reply-cancel-btn'),
    sidebarPane: document.getElementById('sidebar-pane'),
    chatPane: document.getElementById('chat-pane'),
    mobileTabs: document.getElementById('mobile-tabs'),
    webrtcNote: document.getElementById('webrtc-capability-note'),
    imgModal: document.getElementById('image-modal'),
    imgModalTitle: document.getElementById('img-modal-title'),
    imgModalSub: document.getElementById('img-modal-sub'),
    imgModalPreview: document.getElementById('img-modal-preview'),
    imgModalDownload: document.getElementById('img-modal-download'),
    imgModalClose: document.getElementById('img-modal-close'),
    imgZoomIn: document.getElementById('img-zoom-in'),
    imgZoomOut: document.getElementById('img-zoom-out'),
    imgZoomReset: document.getElementById('img-zoom-reset'),
    callModal: document.getElementById('call-modal'),
    callTypeBadge: document.getElementById('call-type-badge'),
    callPeerAvatar: document.getElementById('call-peer-avatar'),
    callPeerName: document.getElementById('call-peer-name'),
    callStatusText: document.getElementById('call-status-text'),
    callTimerText: document.getElementById('call-timer-text'),
    callVideoStage: document.getElementById('call-video-stage'),
    remoteVideo: document.getElementById('remote-video'),
    localVideo: document.getElementById('local-video'),
    remoteAudio: document.getElementById('remote-audio'),
    incomingActions: document.getElementById('incoming-call-actions'),
    activeActions: document.getElementById('active-call-actions'),
    btnCallAccept: document.getElementById('btn-call-accept'),
    btnCallDecline: document.getElementById('btn-call-decline'),
    btnCallMute: document.getElementById('btn-call-mute'),
    btnCallCam: document.getElementById('btn-call-cam'),
    btnCallEnd: document.getElementById('btn-call-end')
  };

  function makeDmChatId(a, b) {
    return a <= b ? 'dm:' + a + '_' + b : 'dm:' + b + '_' + a;
  }

  function formatBytes(bytes) {
    if (!bytes || bytes <= 0) return '0 B';
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1048576) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / 1048576).toFixed(1) + ' MB';
  }

  function extUpper(name) {
    if (!name || name.indexOf('.') < 0) return 'FILE';
    return name.split('.').pop().slice(0, 6).toUpperCase();
  }

  function sendPacket(type, fields) {
    if (!state.ws || state.ws.readyState !== 1) return false;
    const pkt = Object.assign({
      version: 1,
      packetId: 'pkt-' + Date.now() + '-' + Math.random().toString(36).slice(2, 7),
      type: type,
      event: type,
      timestamp: Date.now(),
      senderId: state.webUserId,
      senderName: state.displayName || 'Web User',
      isWebUser: true
    }, fields || {});
    state.ws.send(JSON.stringify(pkt));
    return true;
  }

  function initThemeAndCapabilities() {
    const savedTheme = localStorage.getItem('wcl_theme') || 'dark';
    document.documentElement.setAttribute('data-theme', savedTheme);
    el.themeToggleBtn.onclick = function () {
      const next = document.documentElement.getAttribute('data-theme') === 'dark' ? 'light' : 'dark';
      document.documentElement.setAttribute('data-theme', next);
      localStorage.setItem('wcl_theme', next);
    };

    if (!canCaptureMedia) {
      el.httpsBanner.classList.remove('hidden');
      el.webrtcNote.textContent = 'WebRTC camera/mic blocked by browser on insecure HTTP origin. Host over HTTPS or use the native Android app for calls.';
    } else {
      el.webrtcNote.textContent = 'Secure context verified: WebRTC Host-ICE voice & video calling ready.';
    }
  }

  function connectWs() {
    const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    const ws = new WebSocket(proto + '//' + location.host + '/ws');
    state.ws = ws;

    ws.onopen = function () {
      el.connPill.textContent = 'Connected';
      el.connPill.className = 'pill online';
      sendPacket('HELLO');
    };

    ws.onclose = function () {
      el.connPill.textContent = 'Reconnecting…';
      el.connPill.className = 'pill offline';
      setTimeout(connectWs, 3000);
    };

    ws.onmessage = function (ev) {
      try {
        const pkt = JSON.parse(ev.data);
        const type = pkt.type || pkt.event;
        if (type === 'CHAT_MESSAGE' && pkt.message) {
          addOrUpdateMessage(pkt.message);
        } else if (type === 'MESSAGE_DELETE' && pkt.messageId) {
          state.messages = state.messages.map(function (m) {
            if (m.messageId === pkt.messageId) {
              return Object.assign({}, m, { isDeletedForEveryone: true, content: '' });
            }
            return m;
          });
          renderMessages();
        } else if (type === 'MESSAGE_REACTION' && pkt.messageId && pkt.emoji) {
          refreshInfo();
        } else if (type === 'TYPING' && pkt.chatId === state.activeChatId && pkt.senderId !== state.webUserId) {
          el.typingBanner.textContent = pkt.typing ? ((pkt.senderName || 'Peer') + ' is typing…') : '';
          el.typingBanner.classList.toggle('hidden', !pkt.typing);
        } else if (type === 'WEB_SNAPSHOT') {
          applySnapshot(pkt);
        } else if (type && type.indexOf('CALL_') === 0) {
          handleIncomingCallSignal(type, pkt);
        }
      } catch (e) {
        console.error(e);
      }
    };
  }

  function applySnapshot(data) {
    if (data.peers) state.peers = data.peers;
    if (data.groups) state.groups = data.groups;
    if (data.callLogs) state.callLogs = data.callLogs;
    if (data.messages) {
      const merged = new Map();
      state.messages.forEach(function (m) { merged.set(m.messageId, m); });
      data.messages.forEach(function (m) { merged.set(m.messageId, m); });
      state.messages = Array.from(merged.values()).sort(function (a, b) {
        return (a.createdAt || 0) - (b.createdAt || 0);
      });
    }
    renderPeers();
    renderGroups();
    renderCalls();
    renderMessages();
  }

  function addOrUpdateMessage(msg) {
    const idx = state.messages.findIndex(function (m) { return m.messageId === msg.messageId; });
    if (idx >= 0) state.messages[idx] = msg;
    else state.messages.push(msg);
    renderMessages();
  }

  function renderPeers() {
    el.peersList.innerHTML = '';
    const others = state.peers.filter(function (p) { return p.deviceId !== state.webUserId; });
    el.peersCount.textContent = String(others.length);

    if (others.length === 0) {
      const li = document.createElement('li');
      li.className = 'muted';
      li.textContent = 'No other nearby peers online';
      el.peersList.appendChild(li);
      return;
    }

    others.forEach(function (p) {
      const dmId = makeDmChatId(state.webUserId, p.deviceId);
      const li = document.createElement('li');
      if (state.activeChatId === dmId) li.className = 'active';

      const left = document.createElement('div');
      left.className = 'peer-row-left';
      const dot = document.createElement('span');
      const st = (p.activeStatus || 'ONLINE').toUpperCase();
      dot.className = 'dot ' + (st === 'BUSY' ? 'busy' : (st === 'AWAY' ? 'away' : 'on'));
      left.appendChild(dot);

      const nameEl = document.createElement('span');
      nameEl.className = 'peer-name';
      nameEl.textContent = p.name;
      left.appendChild(nameEl);

      if (p.isWebUser) {
        const wb = document.createElement('span');
        wb.className = 'badge-web';
        wb.textContent = 'Web';
        left.appendChild(wb);
      }

      const actions = document.createElement('div');
      actions.className = 'peer-actions';
      const voiceBtn = document.createElement('button');
      voiceBtn.type = 'button';
      voiceBtn.className = 'mini-btn';
      voiceBtn.textContent = 'Voice';
      voiceBtn.onclick = function (ev) {
        ev.stopPropagation();
        startOutgoingCall(p, 'VOICE');
      };

      const videoBtn = document.createElement('button');
      videoBtn.type = 'button';
      videoBtn.className = 'mini-btn';
      videoBtn.textContent = 'Video';
      videoBtn.onclick = function (ev) {
        ev.stopPropagation();
        startOutgoingCall(p, 'VIDEO');
      };

      actions.appendChild(voiceBtn);
      actions.appendChild(videoBtn);
      li.appendChild(left);
      li.appendChild(actions);

      li.onclick = function () {
        selectDirectChat(p);
        switchMobileTab('chat');
      };
      el.peersList.appendChild(li);
    });
  }

  function renderGroups() {
    el.groupsList.innerHTML = '';
    const publicGroups = state.groups.filter(function (g) { return !g.isPrivate; });
    if (publicGroups.length === 0) {
      publicGroups.push({ groupId: 'everyone_room', name: 'Everyone Room', description: 'Public local room' });
    }
    el.groupsCount.textContent = String(publicGroups.length);

    publicGroups.forEach(function (g) {
      const li = document.createElement('li');
      if (g.groupId === state.activeChatId) li.className = 'active';
      const label = document.createElement('span');
      label.textContent = g.name;
      li.appendChild(label);
      li.onclick = function () {
        state.activeChatId = g.groupId;
        state.activeTargetPeerId = null;
        el.roomTitle.textContent = g.name;
        el.roomSub.textContent = g.description || 'Public local group';
        el.roomAvatar.textContent = g.name.slice(0, 1).toUpperCase();
        el.btnDmVoice.classList.add('hidden');
        el.btnDmVideo.classList.add('hidden');
        renderGroups();
        renderPeers();
        renderMessages();
        switchMobileTab('chat');
      };
      el.groupsList.appendChild(li);
    });
  }

  function selectDirectChat(peer) {
    state.activeChatId = makeDmChatId(state.webUserId, peer.deviceId);
    state.activeTargetPeerId = peer.deviceId;
    el.roomTitle.textContent = peer.name;
    el.roomSub.textContent = (peer.ipAddress || 'Local Peer') + ' • Direct Chat';
    el.roomAvatar.textContent = (peer.name || 'P').slice(0, 1).toUpperCase();
    el.btnDmVoice.classList.remove('hidden');
    el.btnDmVideo.classList.remove('hidden');
    el.btnDmVoice.onclick = function () { startOutgoingCall(peer, 'VOICE'); };
    el.btnDmVideo.onclick = function () { startOutgoingCall(peer, 'VIDEO'); };
    renderGroups();
    renderPeers();
    refreshInfo();
  }

  function renderCalls() {
    el.callsList.innerHTML = '';
    if (!state.callLogs || state.callLogs.length === 0) {
      const li = document.createElement('li');
      li.className = 'muted';
      li.textContent = 'No recent calls';
      el.callsList.appendChild(li);
      return;
    }
    state.callLogs.slice(0, 12).forEach(function (c) {
      const li = document.createElement('li');
      const left = document.createElement('div');
      left.innerHTML = '<strong>' + escapeHtml(c.peerName || 'Peer') + '</strong><div class="muted">' +
        escapeHtml((c.callType || 'VOICE') + ' • ' + (c.status || '') + (c.durationSeconds ? ' • ' + c.durationSeconds + 's' : '')) + '</div>';
      li.appendChild(left);
      el.callsList.appendChild(li);
    });
  }

  function escapeHtml(s) {
    return String(s || '').replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  function renderMessages() {
    el.messagesBox.innerHTML = '';
    const filtered = state.messages.filter(function (m) {
      return m.chatId === state.activeChatId;
    });

    filtered.forEach(function (m) {
      const isMine = m.senderId === state.webUserId;
      const div = document.createElement('div');
      div.className = 'msg' + (isMine ? ' mine' : '');

      const meta = document.createElement('div');
      meta.className = 'msg-meta';
      meta.appendChild(document.createTextNode((m.senderName || 'Peer') + ' • ' + new Date(m.createdAt || Date.now()).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })));
      if (m.senderIsWeb) {
        const wb = document.createElement('span');
        wb.className = 'badge-web';
        wb.textContent = 'Web';
        meta.appendChild(wb);
      }
      div.appendChild(meta);

      if (m.isDeletedForEveryone) {
        const delNotice = document.createElement('div');
        delNotice.className = 'msg-body muted';
        delNotice.textContent = 'This message was deleted';
        div.appendChild(delNotice);
        el.messagesBox.appendChild(div);
        return;
      }

      if (m.replyToPreview) {
        const rq = document.createElement('div');
        rq.className = 'msg-reply';
        rq.textContent = m.replyToPreview;
        div.appendChild(rq);
      }

      const isImage = m.type === 'IMAGE' || (m.attachmentMime && m.attachmentMime.indexOf('image/') === 0);
      if (isImage && m.attachmentId) {
        const thumbSrc = m.thumbnailBase64
          ? ('data:image/jpeg;base64,' + m.thumbnailBase64)
          : ('/api/thumb?transferId=' + encodeURIComponent(m.attachmentId));
        const wrap = document.createElement('div');
        wrap.className = 'msg-thumb-wrap';
        const img = document.createElement('img');
        img.src = thumbSrc;
        img.alt = m.attachmentName || 'Image';
        img.onerror = function () { wrap.style.display = 'none'; };
        const cap = document.createElement('div');
        cap.className = 'msg-thumb-caption';
        cap.textContent = (m.attachmentName || 'Image') + ' • ' + formatBytes(m.attachmentSize);
        wrap.appendChild(img);
        wrap.appendChild(cap);
        wrap.onclick = function () { openImageModal(m, thumbSrc); };
        div.appendChild(wrap);
      }

      if (m.content && (!m.attachmentId || m.content.indexOf('Shared file:') !== 0)) {
        const body = document.createElement('div');
        body.className = 'msg-body';
        body.textContent = m.content;
        div.appendChild(body);
      }

      if (m.attachmentId && m.attachmentName) {
        const card = document.createElement('div');
        card.className = 'file-card';

        const top = document.createElement('div');
        top.className = 'file-card-top';
        const ext = document.createElement('span');
        ext.className = 'file-ext';
        ext.textContent = extUpper(m.attachmentName);
        const title = document.createElement('strong');
        title.textContent = m.attachmentName;
        top.appendChild(ext);
        top.appendChild(title);

        const info = document.createElement('div');
        info.className = 'file-meta';
        info.textContent = formatBytes(m.attachmentSize) + ' • ' + (m.senderName || 'Peer') +
          (m.attachmentSha256 ? ' • SHA-256 ready' : '');

        const actions = document.createElement('div');
        actions.className = 'file-actions';

        const dlUrl = '/api/download?transferId=' + encodeURIComponent(m.attachmentId);
        if (isImage) {
          const openBtn = document.createElement('button');
          openBtn.type = 'button';
          openBtn.className = 'mini-btn';
          openBtn.textContent = 'Preview';
          openBtn.onclick = function () { openImageModal(m, '/api/thumb?transferId=' + encodeURIComponent(m.attachmentId)); };
          actions.appendChild(openBtn);
        } else {
          const openLink = document.createElement('a');
          openLink.href = dlUrl;
          openLink.target = '_blank';
          openLink.className = 'mini-btn';
          openLink.textContent = 'Open';
          actions.appendChild(openLink);
        }

        const dlLink = document.createElement('a');
        dlLink.href = dlUrl;
        dlLink.download = m.attachmentName;
        dlLink.className = 'mini-btn';
        dlLink.textContent = 'Download';
        actions.appendChild(dlLink);

        card.appendChild(top);
        card.appendChild(info);
        card.appendChild(actions);
        div.appendChild(card);
      }

      const footer = document.createElement('div');
      footer.className = 'msg-footer';
      const reacts = document.createElement('div');
      reacts.className = 'msg-reactions';
      renderReactionsChips(reacts, m.reactionsJson);

      const tools = document.createElement('div');
      tools.className = 'msg-tools';
      const replyBtn = document.createElement('button');
      replyBtn.type = 'button';
      replyBtn.className = 'msg-tool-btn';
      replyBtn.textContent = 'Reply';
      replyBtn.onclick = function () {
        state.replyTo = m;
        el.replyBannerText.textContent = 'Replying to ' + (m.senderName || 'Peer') + ': ' + (m.content || m.attachmentName || '');
        el.replyBanner.classList.remove('hidden');
        el.msgInput.focus();
      };
      tools.appendChild(replyBtn);

      const likeBtn = document.createElement('button');
      likeBtn.type = 'button';
      likeBtn.className = 'msg-tool-btn';
      likeBtn.textContent = '👍';
      likeBtn.onclick = function () {
        sendPacket('MESSAGE_REACTION', { chatId: state.activeChatId, messageId: m.messageId, emoji: '👍' });
        refreshInfo();
      };
      tools.appendChild(likeBtn);

      if (isMine) {
        const delBtn = document.createElement('button');
        delBtn.type = 'button';
        delBtn.className = 'msg-tool-btn danger';
        delBtn.textContent = 'Delete';
        delBtn.onclick = function () {
          sendPacket('MESSAGE_DELETE', { chatId: state.activeChatId, messageId: m.messageId });
          state.messages = state.messages.map(function (x) {
            return x.messageId === m.messageId ? Object.assign({}, x, { isDeletedForEveryone: true, content: '' }) : x;
          });
          renderMessages();
        };
        tools.appendChild(delBtn);
      }

      footer.appendChild(reacts);
      footer.appendChild(tools);
      div.appendChild(footer);
      el.messagesBox.appendChild(div);
    });
    el.messagesBox.scrollTop = el.messagesBox.scrollHeight;
  }

  function renderReactionsChips(container, json) {
    if (!json || json === '{}') return;
    try {
      const map = JSON.parse(json);
      Object.keys(map).forEach(function (emoji) {
        const arr = map[emoji] || [];
        if (arr.length > 0) {
          const span = document.createElement('span');
          span.className = 'react-chip';
          span.textContent = emoji + ' ' + arr.length;
          container.appendChild(span);
        }
      });
    } catch (ignored) {}
  }

  function openImageModal(msg, thumbSrc) {
    state.zoomScale = 1;
    el.imgModalPreview.style.transform = 'scale(1)';
    el.imgModalTitle.textContent = msg.attachmentName || 'Image';
    el.imgModalSub.textContent = (msg.senderName || 'Peer') + ' • ' + formatBytes(msg.attachmentSize);
    el.imgModalPreview.src = thumbSrc;
    const dlUrl = '/api/download?transferId=' + encodeURIComponent(msg.attachmentId);
    el.imgModalDownload.href = dlUrl;
    el.imgModalDownload.download = msg.attachmentName || 'image.jpg';
    el.imgModal.classList.remove('hidden');
  }

  el.imgZoomIn.onclick = function () {
    state.zoomScale = Math.min(4, state.zoomScale + 0.5);
    el.imgModalPreview.style.transform = 'scale(' + state.zoomScale + ')';
  };
  el.imgZoomOut.onclick = function () {
    state.zoomScale = Math.max(1, state.zoomScale - 0.5);
    el.imgModalPreview.style.transform = 'scale(' + state.zoomScale + ')';
  };
  el.imgZoomReset.onclick = function () {
    state.zoomScale = 1;
    el.imgModalPreview.style.transform = 'scale(1)';
  };
  el.imgModalClose.onclick = function () { el.imgModal.classList.add('hidden'); };

  // --- WebRTC Calling (Honest HTTPS / Localhost Context Guard) ---
  async function startOutgoingCall(peer, callType) {
    if (!canCaptureMedia) {
      alert('Voice/Video calling requires HTTPS or localhost because browsers block microphone/camera access on plain HTTP LAN URLs.');
      return;
    }
    if (state.call.active) return;

    const callId = 'call-' + Date.now() + '-' + Math.random().toString(36).slice(2, 7);
    resetCallState();
    state.call.active = true;
    state.call.callId = callId;
    state.call.peerId = peer.deviceId;
    state.call.peerName = peer.name;
    state.call.callType = callType;
    state.call.isIncoming = false;

    showCallModal('Ringing…', false);
    try {
      const constraints = callType === 'VIDEO' ? { audio: true, video: true } : { audio: true, video: false };
      const stream = await navigator.mediaDevices.getUserMedia(constraints);
      state.call.localStream = stream;
      if (callType === 'VIDEO') {
        el.localVideo.srcObject = stream;
        el.callVideoStage.classList.remove('hidden');
        el.btnCallCam.classList.remove('hidden');
      }

      const pc = createPeerConnection(peer.deviceId, callId);
      stream.getTracks().forEach(function (track) { pc.addTrack(track, stream); });
      const offer = await pc.createOffer();
      await pc.setLocalDescription(offer);

      sendPacket('CALL_OFFER', {
        targetId: peer.deviceId,
        callId: callId,
        callType: callType,
        sdp: offer.sdp
      });
    } catch (err) {
      alert('Unable to start media call: ' + (err && err.message ? err.message : 'Permission denied'));
      endCurrentCall(true);
    }
  }

  function createPeerConnection(targetPeerId, callId) {
    const pc = new RTCPeerConnection({ iceServers: [] });
    state.call.pc = pc;

    pc.onicecandidate = function (ev) {
      if (ev.candidate) {
        sendPacket('CALL_ICE', {
          targetId: targetPeerId,
          callId: callId,
          iceCandidate: ev.candidate.candidate,
          iceSdpMid: ev.candidate.sdpMid,
          iceSdpMLineIndex: ev.candidate.sdpMLineIndex
        });
      }
    };

    pc.ontrack = function (ev) {
      const stream = ev.streams[0];
      if (!stream) return;
      if (state.call.callType === 'VIDEO') {
        el.remoteVideo.srcObject = stream;
      } else {
        el.remoteAudio.srcObject = stream;
      }
      if (!state.call.connectedAt) {
        startCallTimer();
      }
    };

    pc.onconnectionstatechange = function () {
      if (pc.connectionState === 'connected') {
        el.callStatusText.textContent = 'Connected';
        if (!state.call.connectedAt) startCallTimer();
      } else if (pc.connectionState === 'failed' || pc.connectionState === 'closed' || pc.connectionState === 'disconnected') {
        endCurrentCall(false);
      }
    };
    return pc;
  }

  async function handleIncomingCallSignal(type, pkt) {
    if (type === 'CALL_OFFER') {
      if (state.call.active) {
        sendPacket('CALL_BUSY', { targetId: pkt.senderId, callId: pkt.callId });
        return;
      }
      resetCallState();
      state.call.active = true;
      state.call.callId = pkt.callId;
      state.call.peerId = pkt.senderId;
      state.call.peerName = pkt.senderName || 'Local Peer';
      state.call.callType = pkt.callType || 'VOICE';
      state.call.isIncoming = true;
      state.call.pendingOfferSdp = pkt.sdp;

      sendPacket('CALL_RINGING', { targetId: pkt.senderId, callId: pkt.callId });
      showCallModal('Incoming ' + (state.call.callType === 'VIDEO' ? 'Video Call' : 'Voice Call'), true);
    } else if (state.call.active && pkt.callId === state.call.callId) {
      if (type === 'CALL_RINGING') {
        el.callStatusText.textContent = 'Ringing…';
      } else if (type === 'CALL_BUSY') {
        el.callStatusText.textContent = 'User is busy';
        setTimeout(function () { endCurrentCall(false); }, 1200);
      } else if (type === 'CALL_ANSWER' && state.call.pc && pkt.sdp) {
        await state.call.pc.setRemoteDescription(new RTCSessionDescription({ type: 'answer', sdp: pkt.sdp }));
        el.callStatusText.textContent = 'Connecting WebRTC…';
      } else if (type === 'CALL_ICE' && state.call.pc && pkt.iceCandidate) {
        try {
          await state.call.pc.addIceCandidate(new RTCIceCandidate({
            candidate: pkt.iceCandidate,
            sdpMid: pkt.iceSdpMid,
            sdpMLineIndex: pkt.iceSdpMLineIndex || 0
          }));
        } catch (ignored) {}
      } else if (type === 'CALL_END') {
        el.callStatusText.textContent = 'Call ended';
        setTimeout(function () { endCurrentCall(false); }, 700);
      }
    }
  }

  el.btnCallAccept.onclick = async function () {
    if (!canCaptureMedia) {
      alert('Cannot answer WebRTC call without HTTPS secure context or localhost (browser blocks microphone/camera on plain HTTP).');
      endCurrentCall(true);
      return;
    }
    try {
      const isVideo = state.call.callType === 'VIDEO';
      const stream = await navigator.mediaDevices.getUserMedia(isVideo ? { audio: true, video: true } : { audio: true, video: false });
      state.call.localStream = stream;
      if (isVideo) {
        el.localVideo.srcObject = stream;
        el.callVideoStage.classList.remove('hidden');
        el.btnCallCam.classList.remove('hidden');
      }
      el.incomingActions.classList.add('hidden');
      el.activeActions.classList.remove('hidden');
      el.callStatusText.textContent = 'Connecting WebRTC…';

      const pc = createPeerConnection(state.call.peerId, state.call.callId);
      stream.getTracks().forEach(function (track) { pc.addTrack(track, stream); });
      await pc.setRemoteDescription(new RTCSessionDescription({ type: 'offer', sdp: state.call.pendingOfferSdp }));
      const answer = await pc.createAnswer();
      await pc.setLocalDescription(answer);
      sendPacket('CALL_ANSWER', {
        targetId: state.call.peerId,
        callId: state.call.callId,
        sdp: answer.sdp
      });
    } catch (err) {
      alert('Media permission error: ' + (err && err.message ? err.message : 'Failed'));
      endCurrentCall(true);
    }
  };

  el.btnCallDecline.onclick = function () { endCurrentCall(true); };
  el.btnCallEnd.onclick = function () { endCurrentCall(true); };
  el.btnCallMute.onclick = function () {
    if (!state.call.localStream) return;
    state.call.muted = !state.call.muted;
    state.call.localStream.getAudioTracks().forEach(function (t) { t.enabled = !state.call.muted; });
    el.btnCallMute.textContent = state.call.muted ? 'Unmute' : 'Mute';
  };
  el.btnCallCam.onclick = function () {
    if (!state.call.localStream) return;
    state.call.videoOn = !state.call.videoOn;
    state.call.localStream.getVideoTracks().forEach(function (t) { t.enabled = state.call.videoOn; });
    el.btnCallCam.textContent = state.call.videoOn ? 'Camera Off' : 'Camera On';
  };

  function showCallModal(status, isIncoming) {
    el.callTypeBadge.textContent = state.call.callType === 'VIDEO' ? 'VIDEO CALL' : 'VOICE CALL';
    el.callPeerName.textContent = state.call.peerName || 'Peer';
    el.callPeerAvatar.textContent = (state.call.peerName || 'P').slice(0, 1).toUpperCase();
    el.callStatusText.textContent = status;
    el.callTimerText.textContent = '00:00';
    el.incomingActions.classList.toggle('hidden', !isIncoming);
    el.activeActions.classList.toggle('hidden', isIncoming);
    el.callVideoStage.classList.add('hidden');
    el.btnCallCam.classList.add('hidden');
    el.callModal.classList.remove('hidden');
  }

  function startCallTimer() {
    state.call.connectedAt = Date.now();
    clearInterval(state.call.timerHandle);
    state.call.timerHandle = setInterval(function () {
      const s = Math.floor((Date.now() - state.call.connectedAt) / 1000);
      const mm = String(Math.floor(s / 60)).padStart(2, '0');
      const ss = String(s % 60).padStart(2, '0');
      el.callTimerText.textContent = mm + ':' + ss;
    }, 1000);
  }

  function endCurrentCall(notifyPeer) {
    if (notifyPeer && state.call.peerId && state.call.callId) {
      sendPacket('CALL_END', { targetId: state.call.peerId, callId: state.call.callId });
    }
    resetCallState();
    el.callModal.classList.add('hidden');
    refreshInfo();
  }

  function resetCallState() {
    clearInterval(state.call.timerHandle);
    if (state.call.pc) {
      try { state.call.pc.close(); } catch (ignored) {}
    }
    if (state.call.localStream) {
      state.call.localStream.getTracks().forEach(function (t) { t.stop(); });
    }
    el.localVideo.srcObject = null;
    el.remoteVideo.srcObject = null;
    el.remoteAudio.srcObject = null;
    state.call = {
      active: false, callId: null, peerId: null, peerName: '', callType: 'VOICE',
      isIncoming: false, pc: null, localStream: null, muted: false, videoOn: true,
      timerHandle: null, connectedAt: 0, pendingOfferSdp: null
    };
  }

  function switchMobileTab(tabName) {
    const btns = el.mobileTabs.querySelectorAll('.tab-btn');
    btns.forEach(function (b) { b.classList.toggle('active', b.getAttribute('data-tab') === tabName); });

    if (tabName === 'chat') {
      el.sidebarPane.classList.remove('mobile-visible');
      el.chatPane.classList.remove('mobile-hidden');
    } else {
      el.sidebarPane.classList.add('mobile-visible');
      el.chatPane.classList.add('mobile-hidden');
      const panels = el.sidebarPane.querySelectorAll('.panel');
      panels.forEach(function (p) {
        const pType = p.getAttribute('data-pane');
        if (tabName === 'nearby') p.style.display = (pType === 'chats' || pType === 'nearby') ? 'block' : 'none';
        else if (tabName === 'calls') p.style.display = pType === 'calls' ? 'block' : 'none';
        else if (tabName === 'info') p.style.display = pType === 'info' ? 'block' : 'none';
      });
    }
  }

  el.mobileTabs.querySelectorAll('.tab-btn').forEach(function (btn) {
    btn.onclick = function () { switchMobileTab(btn.getAttribute('data-tab')); };
  });

  async function refreshInfo() {
    try {
      const res = await fetch('/api/info?peerId=' + encodeURIComponent(state.webUserId));
      const data = await res.json();
      el.hostInfo.textContent = 'Host: ' + data.hostName + ' (' + data.ip + ':' + data.port + ')';
      applySnapshot(data);
    } catch (e) {
      el.hostInfo.textContent = 'Local host unreachable';
    }
  }

  el.joinBtn.onclick = async function () {
    const name = el.nameInput.value.trim();
    if (name.length < 2 || name.length > 30) {
      alert('Display name must be 2–30 characters.');
      return;
    }
    state.displayName = name;
    sessionStorage.setItem('wcl_web_name', name);
    el.meBadge.textContent = name + ' • Web';
    el.joinModal.classList.add('hidden');
    await fetch('/api/web/join', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ deviceId: state.webUserId, name: name })
    });
    await refreshInfo();
    connectWs();
  };

  el.replyCancelBtn.onclick = function () {
    state.replyTo = null;
    el.replyBanner.classList.add('hidden');
  };

  el.msgInput.oninput = function () {
    const typing = el.msgInput.value.trim().length > 0;
    sendPacket('TYPING', { chatId: state.activeChatId, targetId: state.activeTargetPeerId, typing: typing });
    clearTimeout(state.typingTimer);
    if (typing) {
      state.typingTimer = setTimeout(function () {
        sendPacket('TYPING', { chatId: state.activeChatId, targetId: state.activeTargetPeerId, typing: false });
      }, 2000);
    }
  };

  el.composerForm.onsubmit = function (e) {
    e.preventDefault();
    const text = el.msgInput.value.trim();
    if (!text) return;
    const msg = {
      messageId: 'msg-' + Date.now() + '-' + Math.random().toString(36).slice(2, 7),
      chatId: state.activeChatId,
      senderId: state.webUserId,
      senderName: state.displayName || 'Web User',
      senderIsWeb: true,
      type: 'TEXT',
      content: text,
      replyToMessageId: state.replyTo ? state.replyTo.messageId : null,
      replyToPreview: state.replyTo ? (state.replyTo.senderName + ': ' + (state.replyTo.content || state.replyTo.attachmentName || '')) : null,
      createdAt: Date.now(),
      status: 'SENT'
    };
    const sent = sendPacket('CHAT_MESSAGE', {
      chatId: state.activeChatId,
      targetId: state.activeTargetPeerId,
      messageId: msg.messageId,
      message: msg
    });
    if (sent) {
      addOrUpdateMessage(msg);
      el.msgInput.value = '';
      state.replyTo = null;
      el.replyBanner.classList.add('hidden');
      sendPacket('TYPING', { chatId: state.activeChatId, targetId: state.activeTargetPeerId, typing: false });
    }
  };

  el.fileInput.onchange = async function () {
    const file = el.fileInput.files[0];
    if (!file) return;
    const form = new FormData();
    form.append('file', file);
    form.append('chatId', state.activeChatId);
    if (state.activeTargetPeerId) form.append('targetId', state.activeTargetPeerId);
    form.append('senderId', state.webUserId);
    form.append('senderName', state.displayName || 'Web User');
    await fetch('/api/upload', { method: 'POST', body: form });
    el.fileInput.value = '';
    await refreshInfo();
  };

  el.qrToggleBtn.onclick = function () {
    el.qrModal.classList.remove('hidden');
    el.qrUrl.textContent = location.origin;
    if (window.LocalQrRenderer) {
      window.LocalQrRenderer.render(el.qrCanvas, location.origin);
    }
  };
  el.qrCloseBtn.onclick = function () { el.qrModal.classList.add('hidden'); };

  initThemeAndCapabilities();
  if (state.displayName) {
    el.meBadge.textContent = state.displayName + ' • Web';
    el.joinModal.classList.add('hidden');
    refreshInfo();
    connectWs();
  } else {
    refreshInfo();
  }
})();
