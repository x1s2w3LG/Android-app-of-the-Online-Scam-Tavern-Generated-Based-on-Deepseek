/* ==========================================================================
   app.js —— 骗子酒馆前端全部逻辑（纯原生 JS，无框架 / 无 CDN）
   契约：PROTOCOL.md（v2 冻结版）
   设计原则：
     1) 任何渲染都由服务端 state 全量驱动：收到 state 就整体重绘；
        本地只保留「选牌」和「动画队列」两类状态。
     2) 协议里可能为 null 的字段（targetCard / currentPlayerId /
        lastPlayerId / winnerId / lanUrl / message）一律做空值保护。
     3) 注释中文，简洁说明关键逻辑。
   ========================================================================== */
(function () {
    'use strict';

    /* ==================================================================
       0. 常量与工具
       ================================================================== */

    /* WebSocket 地址必须动态推导，绝不硬编码主机 / 端口 */
    var WS_URL = (location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ws';

    var LS = {
        token: 'liarbar.token',
        name: 'liarbar.name',
        pid: 'liarbar.pid'
    };

    var MAX_SELECT = 5;            // 最多选 5 张（协议：每回合 1~5 张，正好是一手牌）
    var MAX_SHOTS = 6;             // 左轮弹位 6 个
    var SHOT_SPIN_MS = 1300;       // 开枪前"转轮"的时长（音效与特效都按它对齐）
    var DEFAULT_NAME = '玩家';     // 昵称前缀
    var NAME_MAX = 12;             // 与服务端 cleanName 的上限保持一致

    /** 播个音效；没有 SFX（极老浏览器）或出错都不能影响游戏。 */
    function sfx(name, delayMs) {
        try {
            if (window.SFX) window.SFX.play(name, delayMs);
        } catch (e) { /* 忽略 */ }
    }

    /* 几个人默认都叫"玩家"会分不清谁是谁，所以默认昵称带随机后缀 */
    function genDefaultName() {
        return DEFAULT_NAME + (1000 + Math.floor(Math.random() * 9000));
    }

    /* 座位槽位分配：对手数 → 使用的槽位（自己永远在下方，不占槽位） */
    var SLOT_PLAN = {
        1: ['center'],                      // 1 个对手 → 上方
        2: ['left', 'right'],               // 2 个对手 → 左上 + 右上
        3: ['left', 'center', 'right']      // 3 个对手 → 左上 + 上方 + 右上
    };

    /* 槽位 → 左轮指向类 / 闪光方向 */
    var SLOT_AIM = {
        left:   { cls: 'point-to-left',   dotX: '18%', dotY: '42%' },
        center: { cls: 'point-to-top',    dotX: '50%', dotY: '16%' },
        right:  { cls: 'point-to-right',  dotX: '82%', dotY: '42%' }
    };

    /* 槽位 → 侧边旋转（出牌区整体转向） */
    var SLOT_SIDE = { left: 'seat-side-left', center: 'seat-side-top', right: 'seat-side-right' };

    function $(id) { return document.getElementById(id); }

    function store(key, val) {
        try { localStorage.setItem(key, val); } catch (e) { /* 隐私模式忽略 */ }
    }

    function load(key) {
        try { return localStorage.getItem(key); } catch (e) { return null; }
    }

    function escapeHtml(str) {
        return String(str === null || str === undefined ? '' : str)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    /* 本地视角数据 */
    var L = {
        token: load(LS.token) || '',
        pid: load(LS.pid) || '',
        name: load(LS.name) || genDefaultName()
    };

    /* 全局状态：只保存服务端下发的 state，本地不拼状态 */
    var G = {
        ctrl: null,          // LiarWS 实例
        wsConnected: false,  // WebSocket 是否已连上
        everConnected: false,// 是否成功连上过（用于区分首次连接与断线重连）
        joined: false,       // 是否已经发过 join（决定重连时是否自动重连座位）
        state: null,         // 最近一份 state 快照
        overInfo: null,      // {winnerId, winnerName}，gameover 事件可选补充
        autoJoinTimer: null, // 自动 join 定时器（连上后 5 秒内必须 join）
        stateSig: ''         // 上一次渲染的 state 指纹（用于动画去重）
    };

    /* 本地 UI 状态：选牌、动画队列（唯一允许的增量状态） */
    var U = {
        selected: {},        // { 手牌下标: true }
        handSig: '',         // 手牌内容签名，手牌变了就清空选择
        dealSeen: {},        // 出牌区入场动画去重
        revealSeen: {},      // 本轮已播放过翻牌动画的玩家
        pending: [],         // 待播放动画 [{type,payload}]
        animTimer: null
    };

    /* DOM 引用 */
    var D = {};

    function hasState() {
        return !!(G.state && G.state.t);
    }

    function isPlaying() {
        return hasState() && G.state.phase === 'playing';
    }

    function playerById(id) {
        var list = (hasState() && G.state.players) || [];
        for (var i = 0; i < list.length; i++) {
            if (list[i] && list[i].id === id) return list[i];
        }
        return null;
    }

    function playerName(id, fallback) {
        if (!id) return fallback || '—';
        var p = playerById(id);
        return (p && p.name) ? p.name : (fallback || '玩家');
    }

    function isMyId(id) {
        return !!id && !!G.pid && id === G.pid;
    }

    function seatNoOf(id) {
        var list = (hasState() && G.state.players) || [];
        for (var i = 0; i < list.length; i++) {
            if (list[i] && list[i].id === id) return i + 1;
        }
        return 0;
    }

    function cardsKey(cards) {
        var tmp = {};
        if (!cards) return '';
        for (var i = 0; i < cards.length; i++) {
            var c = String(cards[i]);
            tmp[c] = (tmp[c] || 0) + 1;
        }
        return Object.keys(tmp).sort().map(function (k) { return k + ':' + tmp[k]; }).join('|');
    }

    function selectCount() {
        return Object.keys(U.selected).length;
    }

    /* ==================================================================
       1. 轻提示
       ================================================================== */
    function toast(msg, type, ms) {
        if (msg === null || msg === undefined || msg === '') return;
        type = type || 'info';
        var layer = D.toastLayer;
        if (!layer) return;

        var n = layer.children.length;
        for (var i = 0; i < 3 && n > 3; i++) {
            layer.removeChild(layer.firstChild);
            n--;
        }

        var el = document.createElement('div');
        el.className = 'toast toast-' + type;
        el.textContent = String(msg);
        layer.appendChild(el);

        var life = ms || (type === 'error' ? 3000 : 2000);
        setTimeout(function () {
            el.classList.add('is-out');
            setTimeout(function () {
                if (el.parentNode) el.parentNode.removeChild(el);
            }, 300);
        }, life);
    }

    /* ==================================================================
       2. 渲染入口（收到 state 就整体重绘）
       ================================================================== */
    function render() {
        if (!D.viewLobby || !hasState()) return;
        var s = G.state;

        renderConn();
        renderBanner();

        var lobbyOn = (s.phase === 'lobby');
        D.viewLobby.hidden = !lobbyOn;
        D.viewGame.hidden = lobbyOn;

        /* 观战状态（被淘汰）：收起手牌和操作，但**聊天照旧可用** */
        var spec = isSpectator();
        if (spec !== G.wasSpectator) {
            G.wasSpectator = spec;
            console.log('[spectator] ' + (spec ? 'enter' : 'leave'));
        }
        if (D.viewGame) D.viewGame.classList.toggle('is-spectator', spec);
        if (D.spectatorBar) D.spectatorBar.hidden = !spec;
        if (D.chatInput) {
            D.chatInput.disabled = false;
            D.chatInput.placeholder = spec ? '观战中也能聊…' : '说点什么…';
        }
        if (D.chatSend) D.chatSend.classList.remove('is-disabled');

        if (lobbyOn) {
            renderLobby();
        } else {
            renderGame();
        }
        flushAnims();
        maybeRestoreAvatar();          // 服务端重启后自动补传本地头像
        maybePushStats();              // 房主：把本机存的胜场榜交上去

        /* 视图切换后做一次布局自检（真机适配回归用，见 js/uicheck.js） */
        window.UI_CHECK_VIEW = lobbyOn ? 'lobby' : 'game';
        if (window.UI_CHECK && G.lastView !== window.UI_CHECK_VIEW) {
            G.lastView = window.UI_CHECK_VIEW;
            setTimeout(function () { window.UI_CHECK.check('view'); }, 260);
        }
        /* 渲染后也补一次（防抖）：按钮会因为 canStart 之类的状态变化而挪位置，
           自动化脚本读到的坐标必须是最新的；内容没变时不会重复打印。 */
        if (window.UI_CHECK) {
            clearTimeout(U.uiCheckTimer);
            U.uiCheckTimer = setTimeout(function () { window.UI_CHECK.check('render'); }, 500);
        }
    }

    /* 顶部连接状态 */
    function renderConn() {
        if (!D.conn) return;
        var on = G.wsConnected;
        D.conn.textContent = on ? ('已连接 · ' + (L.name || DEFAULT_NAME)) : '未连接';
        D.conn.className = 'conn ' + (on ? 'is-on' : 'is-off');
    }

    /* 掉线横幅：只要 WebSocket 断了就显示 */
    function renderBanner() {
        if (!D.banner) return;
        D.banner.hidden = !!G.wsConnected;
    }

    /* ==================================================================
       3. 大厅视图
       ================================================================== */
    function renderLobby() {
        var s = G.state;
        var list = s.players || [];
        var minP = (typeof s.minPlayers === 'number') ? s.minPlayers : 2;
        var maxP = (typeof s.maxPlayers === 'number') ? s.maxPlayers : 4;

        /* 人数 */
        if (D.lobbyCount) {
            D.lobbyCount.textContent = '当前 ' + list.length + '/' + maxP + ' 人，最少 ' + minP + ' 人';
        }

        renderBoard();                 // 胜场榜（数据来自 state.leaderboard）

        /* 昵称输入框：任何时候都能改（改完点按钮 / 回车生效） */
        if (D.nameInput) {
            var meNow = playerById(G.pid);
            if (meNow && meNow.name) L.name = meNow.name;   // 服务端可能给重名加了序号
            if (document.activeElement !== D.nameInput) {
                D.nameInput.value = L.name || DEFAULT_NAME;
            }
        }

        /* 局域网 / 隧道入口分享：有对外地址就优先显示它（远程的朋友复制这个） */
        if (D.shareBox) {
            var lan = (typeof s.lanUrl === 'string' && s.lanUrl) ? s.lanUrl : '';
            var pub = (typeof s.publicUrl === 'string' && s.publicUrl) ? s.publicUrl : '';
            var share = pub || lan;
            D.shareBox.hidden = !share;
            /* 正在编辑就别覆盖用户输入 */
            if (share && D.lanUrl && document.activeElement !== D.lanUrl) {
                D.lanUrl.value = share;
            }
            /* 小字提示另一条地址，方便同 WiFi 的朋友 */
            if (D.shareHint) {
                if (pub && lan) {
                    D.shareHint.textContent = '对外地址（异地朋友用这个）· 同一 WiFi 也可以用 ' + lan;
                    D.shareHint.hidden = false;
                } else if (pub) {
                    D.shareHint.textContent = '对外地址（异地朋友用这个）';
                    D.shareHint.hidden = false;
                } else if (lan) {
                    D.shareHint.textContent = '局域网地址（同一 WiFi 的朋友用这个）';
                    D.shareHint.hidden = false;
                } else {
                    D.shareHint.hidden = true;
                }
            }
        }
        /* 只有房主能改分享地址 */
        if (D.lanEditBtn) D.lanEditBtn.hidden = !s.isHost;

        /* 大厅里的头像预览 */
        if (D.myAvatar) {
            var meAv = playerById(G.pid);
            D.myAvatar.innerHTML = avatarHtml(meAv, 'av-lg');
            D.myAvatar.classList.toggle('is-empty', !meAv || numOf(meAv.avatar) === 0);
        }
        if (D.avatarClear) {
            var meAv2 = playerById(G.pid);
            D.avatarClear.hidden = !(meAv2 && numOf(meAv2.avatar) > 0);
        }

        /* 进入房间 / 改名 按钮 */
        if (D.joinBtn) {
            D.joinBtn.hidden = false;
            D.joinBtn.textContent = isSeated() ? '改名' : '进入房间';
            D.joinBtn.classList.toggle('is-disabled', !G.wsConnected);
        }
        if (D.joinHint) {
            if (G.joined) {
                D.joinHint.textContent = '已进入房间 · 座位号 ' + (seatNoOf(G.pid) || '-') +
                    (s.isHost ? ' · 你是房主' : '') + ' · 改完名字点「改名」';
            } else if (!G.wsConnected) {
                D.joinHint.textContent = '正在连接服务器…';
            } else {
                D.joinHint.textContent = '输入昵称后进入房间';
            }
        }

        /* 玩家列表 */
        if (D.lobbyList) {
            if (!list.length) {
                D.lobbyList.innerHTML = '<div class="empty">还没有人进入房间</div>';
            } else {
                var html = '';
                for (var i = 0; i < list.length; i++) {
                    html += renderLobbyRow(list[i], i, s.phase);
                }
                D.lobbyList.innerHTML = html;
            }
        }

        /* 开始游戏（仅房主可见，canStart 为 false 时置灰并给出原因） */
        if (D.lobbyActions) {
            D.lobbyActions.innerHTML = '';
            if (s.isHost) {
                var btn = document.createElement('button');
                btn.type = 'button';
                btn.className = 'btn btn-primary';
                btn.textContent = '开始游戏';
                var reason = startDisabledReason(s);
                if (!G.wsConnected) reason = '连接已断开，正在重连…';
                if (reason) {
                    btn.classList.add('is-disabled');
                    btn.disabled = true;
                }
                btn.onclick = function () {
                    if (btn.disabled) {
                        toast(reason || '现在不能开始', 'warn', 2500);
                        return;
                    }
                    sendStart();
                };
                D.lobbyActions.appendChild(btn);
                if (reason) {
                    var tip = document.createElement('div');
                    tip.className = 'disabled-reason';
                    tip.textContent = reason;
                    D.lobbyActions.appendChild(tip);
                }

                /* 机器人：缺人时可以补齐，单人也能开局 */
                if (s.phase === 'lobby') {
                    var bots = 0;
                    for (var bi = 0; bi < list.length; bi++) {
                        if (list[bi] && list[bi].bot) bots++;
                    }

                    var botRow = document.createElement('div');
                    botRow.className = 'bot-row';

                    var addBtn = document.createElement('button');
                    addBtn.type = 'button';
                    addBtn.className = 'btn btn-ghost btn-sm';
                    addBtn.textContent = '＋ 添加机器人';
                    addBtn.onclick = function () {
                        if (send({ t: 'add-bot' })) toast('已添加机器人', 'info', 1400);
                    };
                    botRow.appendChild(addBtn);

                    if (bots > 0) {
                        var delBtn = document.createElement('button');
                        delBtn.type = 'button';
                        delBtn.className = 'btn btn-ghost btn-sm';
                        delBtn.textContent = '－ 移除机器人';
                        delBtn.onclick = function () {
                            if (send({ t: 'remove-bot' })) toast('已移除机器人', 'info', 1400);
                        };
                        botRow.appendChild(delBtn);
                    }
                    D.lobbyActions.appendChild(botRow);

                    var botTip = document.createElement('div');
                    botTip.className = 'disabled-reason';
                    botTip.textContent = bots > 0
                        ? ('当前 ' + bots + ' 个机器人 · 会自己出牌和质疑，最多 ' + maxP + ' 人')
                        : '一个人也能玩：加几个机器人补齐人数';
                    D.lobbyActions.appendChild(botTip);
                }
            } else {
                var wait = document.createElement('div');
                wait.className = 'wait-host';
                wait.textContent = G.joined ? '等待房主开始游戏…' : '进入房间后等待房主开始游戏…';
                D.lobbyActions.appendChild(wait);
            }
        }

        if (D.waitHost) D.waitHost.hidden = true; // 兼容静态占位节点
    }

    function startDisabledReason(s) {
        if (s.canStart) return '';
        var n = (s.players || []).length;
        var minP = (typeof s.minPlayers === 'number') ? s.minPlayers : 2;
        if (s.phase !== 'lobby') return '游戏进行中，无法开始新的一局';
        if (n < minP) return '至少需要 ' + minP + ' 名玩家（当前 ' + n + ' 人，可加机器人）';
        return '当前无法开始游戏';
    }

    function renderLobbyRow(p, idx, phase) {
        if (!p) return '';
        var cls = 'lobby-row';
        if (p.connected === false) cls += ' is-offline';
        if (p.alive === false) cls += ' is-dead';

        var tags = '';
        if (p.isHost) tags += '<span class="badge badge-host">房主</span>';
        if (isMyId(p.id)) tags += '<span class="badge badge-self">你</span>';
        if (p.bot) tags += '<span class="badge badge-bot">机器人</span>';
        if (p.connected === false) tags += '<span class="badge badge-off">掉线中</span>';
        if (phase && phase !== 'lobby') {
            tags += '<span class="badge">' + (p.alive === false ? '已淘汰' : '存活') +
                ' · 手牌 ' + numOf(p.handCount) + '</span>';
        }

        return '<div class="' + cls + '" data-pid="' + escapeHtml(p.id) + '">' +
            avatarHtml(p, 'av-xs') +
            '<span class="seat-no">' + (idx + 1) + '</span>' +
            '<span class="p-name">' + escapeHtml(p.name || '玩家') + '</span>' +
            '<span class="tags">' + tags + '</span>' +
            '</div>';
    }

    function numOf(v) {
        return (typeof v === 'number' && isFinite(v)) ? v : 0;
    }

    /* ==================================================================
       4. 游戏视图
       ================================================================== */
    function renderGame() {
        renderSeats();
        renderTargetCard();
        renderCenterInfo();
        renderMyStatus();
        renderMyPlayed();
        renderHand();
        renderButtons();
        renderOverPanel();
    }

    /* ---------- 4.1 对手座位（人数自适应） ---------- */
    function renderSeats() {
        var s = G.state;
        var all = s.players || [];
        var opps = [];
        for (var i = 0; i < all.length; i++) {
            if (all[i] && !isMyId(all[i].id)) opps.push(all[i]);
        }

        var slots = SLOT_PLAN[opps.length] || SLOT_PLAN[3];
        var html = '';
        for (var j = 0; j < opps.length && j < slots.length; j++) {
            html += renderSeat(opps[j], slots[j]);
        }
        if (!html) {
            html = '<div class="seat" data-slot="center"><span class="seat-name">等待其他玩家加入…</span></div>';
        }
        if (D.seats) D.seats.innerHTML = html;

        /* 指向牌桌的指针（有当前回合玩家时显示） */
        var tw = document.querySelector('.table-wrap');
        if (tw) tw.classList.toggle('has-turn', !!s.currentPlayerId);
    }

    /* 本轮某个玩家一共出过几张牌（翻开了就按翻开张数算） */
    function playedCountOf(pid) {
        if (!hasState() || !pid) return 0;
        var t = G.state.table;
        if (!t || !t.length) return 0;
        var n = 0;
        for (var i = 0; i < t.length; i++) {
            var e = t[i];
            if (!e || e.playerId !== pid) continue;
            if (e.cards && e.cards.length) {
                n += e.cards.length;
            } else {
                n += numOf(e.count);
            }
        }
        return n;
    }

    function renderSeat(p, slot) {
        var s = G.state;
        var cls = 'seat';
        if (SLOT_SIDE[slot]) cls += ' ' + SLOT_SIDE[slot];
        if (s.currentPlayerId && s.currentPlayerId === p.id) cls += ' is-turn';
        if (p.alive === false) cls += ' is-dead';
        if (p.connected === false) cls += ' is-offline';

        var head = '<div class="seat-head">' +
            avatarHtml(p, 'av-sm') +
            '<span class="seat-name">' + escapeHtml(p.name || '玩家') + '</span>' +
            ((s.currentPlayerId && s.currentPlayerId === p.id) ? '<span class="seat-turn-tag">出牌中</span>' : '') +
            (p.isHost ? '<span class="badge badge-host">房主</span>' : '') +
            (p.bot ? '<span class="badge badge-bot">机器人</span>' : '') +
            (p.connected === false ? '<span class="badge badge-off">掉线中</span>' : '') +
            '</div>';

        /* 出牌张数直接写在这个玩家的方框里，一眼看清谁出了几张 */
        var played = playedCountOf(p.id);
        var meta = '<div class="seat-meta">' +
            shotsHtml(p) +
            '<span class="seat-handcount">手牌 ' + numOf(p.handCount) + '</span>' +
            (played > 0 ? '<span class="seat-played">已出 ' + played + ' 张</span>' : '') +
            '<span class="seat-alive">' + (p.alive === false ? '已淘汰' : '存活') + '</span>' +
            '</div>';

        return '<div class="' + cls + '" data-slot="' + slot + '" data-pid="' + escapeHtml(p.id) + '">' +
            head + meta + '<div class="player-played-cards" data-played="' + escapeHtml(p.id) + '"></div>' +
            '</div>';
    }

    /* shots/6：6 个小圆点 + 文字 */
    function shotsHtml(p) {
        var used = numOf(p.shots);
        if (used < 0) used = 0;
        if (used > MAX_SHOTS) used = MAX_SHOTS;
        var dots = '';
        for (var i = 0; i < MAX_SHOTS; i++) {
            dots += '<span class="dot' + (i < used ? ' is-used' : '') + '"></span>';
        }
        return '<span class="shots" title="已扣扳机次数">' + dots +
            '<span class="shots-text">' + used + '/' + MAX_SHOTS + '</span></span>';
    }

    /* ---------- 4.2 中央：目标牌 + 牌堆 ---------- */
    function renderTargetCard() {
        if (!D.targetCard || !D.targetInner) return;
        var t = G.state.targetCard;
        var card = (typeof t === 'string' && t) ? t : null;
        D.targetInner.textContent = card || '?';
        D.targetCard.classList.toggle('is-empty', !card);
        D.targetCard.classList.toggle('is-red', card === 'A');
    }

    function renderDeck() {
        if (!D.deck) return;
        if (D.deck.children.length) return; // 牌堆只是装饰，建一次即可
        for (var i = 0; i < 5; i++) {
            var c = document.createElement('div');
            c.className = 'deck-card';
            D.deck.appendChild(c);
        }
    }

    /* ---------- 4.3 中央提示：阶段 / 第几次出牌 / 轮到谁 ---------- */
    function renderCenterInfo() {
        var s = G.state;

        if (D.phaseTag) {
            var map = { lobby: '大厅', playing: '游戏进行中', over: '本局结束' };
            D.phaseTag.textContent = map[s.phase] || '准备中';
        }

        /* 本轮出牌次数 = 本轮 table 的条目数 */
        var plays = (s.table && s.table.length) ? s.table.length : 0;
        if (D.playBadge) {
            var show = (s.phase === 'playing' && plays > 0);
            D.playBadge.hidden = !show;
            D.playBadge.textContent = '本轮第 ' + plays + ' 次出牌';
        }

        if (D.turnLine) {
            if (s.judging) {
                /* 判定动画播放中：谁都别动，等这一波播完（服务端也会拦住） */
                D.turnLine.textContent = '判定中…（看完这一波再出牌）';
            } else if (s.phase === 'over') {
                D.turnLine.textContent = '本局结束';
            } else if (!s.currentPlayerId) {
                D.turnLine.textContent = s.phase === 'playing' ? '等待发牌…' : '等待开局…';
            } else if (isMyId(s.currentPlayerId)) {
                D.turnLine.textContent = '轮到你行动' +
                    (s.canDoubt
                        ? '（质疑 ' + playerName(s.lastPlayerId, '上家') + ' / 出 1~5 张 / 不要）'
                        : '（出 1~5 张，或「不要」过牌）');
            } else {
                D.turnLine.textContent = '轮到 ' + playerName(s.currentPlayerId, '其他玩家') + ' 行动…';
            }
        }

        if (D.msgLine) {
            var txt = '';
            if (s.phase === 'playing') {
                txt = '目标牌 ' + ((typeof s.targetCard === 'string' && s.targetCard) ? s.targetCard : '?') +
                    ' · 小丑牌可当任意牌';
            }
            if (typeof s.message === 'string' && s.message) txt = s.message;
            D.msgLine.textContent = txt;
        }
    }

    /* ---------- 4.4 自己的状态条 ---------- */
    function renderMyStatus() {
        if (!D.meStatus) return;
        var s = G.state;
        var me = playerById(G.pid);
        var html = '';

        html += avatarHtml(me, 'av-sm is-mine', 'avatar');
        html += '<span class="me-name">' + escapeHtml((me && me.name) || L.name || DEFAULT_NAME) + '</span>';
        /* 对局中不给改名入口（服务端也会拒绝） */
        if (s.phase !== 'playing') {
            html += '<button class="name-edit" type="button" data-act="rename" title="修改名称">✎ 改名</button>';
        }
        if (me) {
            html += '<span>' + shotsHtml(me) + '</span>';
            html += '<span>手牌 ' + numOf(me.handCount) + '</span>';
            var myPlayed = playedCountOf(me.id);
            if (myPlayed > 0) html += '<span class="seat-played">已出 ' + myPlayed + ' 张</span>';
            if (me.alive === false) html += '<span class="badge badge-off">已淘汰</span>';
            if (me.connected === false) html += '<span class="badge badge-off">掉线中</span>';
        }
        if (s.isHost) html += '<span class="badge badge-host">房主</span>';
        if (typeof s.message === 'string' && s.message) {
            html += '<span class="me-msg">' + escapeHtml(s.message) + '</span>';
        }
        D.meStatus.innerHTML = html;
    }

    /* ---------- 4.5 出牌区（自己 + 每个对手） ---------- */
    function renderMyPlayed() {
        if (!D.mePlayed) return;
        D.mePlayed.innerHTML = renderPlayedBlock(playedEntriesFor(G.pid), true);
    }

    function playedEntriesFor(pid) {
        var out = [];
        var t = (hasState() && G.state.table) || [];
        for (var i = 0; i < t.length; i++) {
            var e = t[i];
            if (e && e.playerId === pid) out.push(e);
        }
        return out;
    }

    /* 渲染某个玩家本轮打出的牌：默认背面朝上，reveal 后 cards 回填即翻正面 */
    function renderPlayedBlock(entries, mine) {
        var s = G.state;
        var blockCls = 'played-block' + (mine ? ' is-mine' : '');
        var html = '';

        for (var i = 0; i < entries.length; i++) {
            var e = entries[i] || {};
            var revealed = !!e.cards;
            var cards = revealed ? e.cards : [];
            var lie = revealed ? isLieCards(cards, s.targetCard) : false;
            var extraCls = revealed ? (lie ? ' is-lie' : ' is-honest') : '';

            var n = revealed ? cards.length : numOf(e.count);
            if (n <= 0) n = 1;
            if (n > MAX_SELECT) n = MAX_SELECT;

            /* 每组牌前面挂一个"几张"的小牌子，一桌人都能看清谁出了几张 */
            var group = '<span class="played-group' + extraCls + '">' +
                '<span class="played-count">' + n + ' 张</span>';
            for (var k = 0; k < n; k++) {
                var val = revealed ? cards[k] : '';
                group += cardHtml(val, {
                    shell: true,
                    ver: s.phase + ':' + e.playerId + ':' + i + ':' + k + ':' + (revealed ? val : 'back'),
                    extra: 'played-card' + (revealed ? (val === 'J' ? ' is-joker' : (lie ? ' is-lie' : ' is-honest')) : ''),
                    label: revealed ? ('翻牌 ' + val) : '背面朝上的牌'
                });
            }
            group += '</span>';
            html += group;
        }
        if (!html) return '';
        return '<div class="' + blockCls + '">' + html + '</div>';
    }

    /* 判定一沓牌是否有欺骗：只要有一张既不是目标牌也不是 J 即算欺骗 */
    function isLieCards(cards, target) {
        if (!cards || !cards.length) return false;
        for (var i = 0; i < cards.length; i++) {
            var c = cards[i];
            if (c !== 'J' && c !== target) return true;
        }
        return false;
    }

    /* ---------- 4.6 手牌 ---------- */
    function renderHand() {
        if (!D.hand) return;
        var s = G.state;
        var hand = (s.hand && s.hand.length) ? s.hand : [];
        var html = '';
        for (var i = 0; i < hand.length; i++) {
            var sel = !!U.selected[i];
            html += cardHtml(hand[i], {
                shell: true,
                selected: sel,
                ver: 'hand:' + U.handSig + ':' + i,
                extra: 'hand-card' + (isPlayableCard(hand[i]) ? ' is-playable' : ''),
                idx: i,
                label: '手牌 ' + hand[i]
            });
        }
        D.hand.innerHTML = html;
        D.hand.classList.toggle('is-locked', !s.canPlay);

        /* 事件委托：点击手牌切换选中 */
        if (!D.hand.dataset.bound) {
            D.hand.dataset.bound = '1';
            D.hand.addEventListener('click', function (ev) {
                var node = ev.target;
                while (node && node !== D.hand && !node.classList.contains('card-shell')) {
                    node = node.parentNode;
                }
                if (!node || node === D.hand) return;
                var raw = node.getAttribute('data-idx');
                if (raw === null) return;
                toggleSelect(parseInt(raw, 10), node);
            });
        }
    }

    /** 小丑牌（协议里就是 J）：可以当任意目标牌出。 */
    function isJoker(v) {
        return v === 'J';
    }

    /* 小丑牌上的小丑图案：内联 SVG，不依赖图片文件与 emoji 字体 */
    function jokerSvg() {
        return '<svg class="joker-svg" viewBox="0 0 100 100" aria-hidden="true">' +
            /* 三个尖帽子 + 铃铛 */
            '<path class="jk-hat" d="M31 38 L13 12 L45 34 Z"/>' +
            '<path class="jk-hat" d="M41 35 L50 5 L59 35 Z"/>' +
            '<path class="jk-hat" d="M55 34 L87 12 L69 38 Z"/>' +
            '<circle class="jk-bell" cx="13" cy="12" r="6.5"/>' +
            '<circle class="jk-bell" cx="50" cy="5" r="6.5"/>' +
            '<circle class="jk-bell" cx="87" cy="12" r="6.5"/>' +
            '<rect class="jk-band" x="25" y="31" width="50" height="10" rx="5"/>' +
            /* 脸 */
            '<ellipse class="jk-face" cx="50" cy="67" rx="25" ry="23"/>' +
            /* 眼睛 + 眉毛 */
            '<circle class="jk-eye" cx="40" cy="63" r="4.6"/>' +
            '<circle class="jk-eye" cx="60" cy="63" r="4.6"/>' +
            '<path class="jk-brow" d="M32 55 q8 -5 15 -1"/>' +
            '<path class="jk-brow" d="M53 54 q8 -4 15 1"/>' +
            /* 坏笑 + 腮红 */
            '<path class="jk-mouth" d="M36 75 q14 13 28 -3"/>' +
            '<circle class="jk-blush" cx="31" cy="73" r="5"/>' +
            '<circle class="jk-blush" cx="69" cy="73" r="5"/>' +
            '</svg>';
    }

    /* ---------- 头像 ---------- */

    /** 机器人头像：画一个小机器人。 */
    function botAvatarSvg() {
        return '<svg class="av-svg" viewBox="0 0 64 64" aria-hidden="true">' +
            '<rect class="av-antenna" x="30" y="7" width="4" height="9" rx="2"/>' +
            '<circle class="av-bulb" cx="32" cy="7" r="4.5"/>' +
            '<rect class="av-ear" x="5" y="27" width="7" height="12" rx="3.5"/>' +
            '<rect class="av-ear" x="52" y="27" width="7" height="12" rx="3.5"/>' +
            '<rect class="av-head" x="11" y="16" width="42" height="34" rx="10"/>' +
            '<rect class="av-eye" x="21" y="27" width="8" height="8" rx="2.5"/>' +
            '<rect class="av-eye" x="35" y="27" width="8" height="8" rx="2.5"/>' +
            '<rect class="av-mouth" x="24" y="40" width="16" height="3.4" rx="1.7"/>' +
            '</svg>';
    }

    /**
     * 生成一个头像元素：
     *   机器人 → 内置机器人图案
     *   上传过 → /avatar/<id>?v=<版本>（版本号破缓存）
     *   都没有 → 名字首字
     */
    function avatarHtml(p, extraCls, act) {
        var cls = 'avatar ' + (extraCls || '');
        var actAttr = act ? ' data-act="' + act + '"' : '';
        if (!p) {
            return '<span class="' + cls + '"' + actAttr + '></span>';
        }
        if (p.bot) {
            return '<span class="' + cls + ' is-bot"' + actAttr + '>' + botAvatarSvg() + '</span>';
        }
        if (numOf(p.avatar) > 0) {
            return '<span class="' + cls + '"' + actAttr + '>' +
                '<img src="avatar/' + encodeURIComponent(p.id) + '?v=' + numOf(p.avatar) +
                '" alt="" loading="lazy">' +
                '</span>';
        }
        var initial = String(p.name || '?').slice(0, 1);
        return '<span class="' + cls + '"' + actAttr + '>' +
            '<i class="av-initial">' + escapeHtml(initial) + '</i></span>';
    }

    /* 该牌是否可能算“真”（目标牌或小丑）——仅用于轻微视觉提示 */
    function isPlayableCard(c) {
        var t = hasState() ? G.state.targetCard : null;
        return isJoker(c) || (!!t && c === t);
    }

    function toggleSelect(idx, node) {
        if (isNaN(idx)) return;
        var s = G.state;
        if (!s.canPlay) {
            toast(playDisabledReason(), 'warn', 2500);
            return;
        }
        if (U.selected[idx]) {
            delete U.selected[idx];
        } else {
            if (selectCount() >= MAX_SELECT) {
                toast('最多只能选 ' + MAX_SELECT + ' 张牌', 'warn', 2500);
                return;
            }
            U.selected[idx] = true;
        }
        updateHandSelection();
    }

    /* 只更新选中样式，避免整块重绘打断动画 */
    function updateHandSelection() {
        if (!D.hand) return;
        var nodes = D.hand.querySelectorAll('.card-shell');
        for (var i = 0; i < nodes.length; i++) {
            var raw = nodes[i].getAttribute('data-idx');
            if (raw === null) continue;
            nodes[i].classList.toggle('is-selected', !!U.selected[parseInt(raw, 10)]);
        }
        renderButtons();
    }

    /* ---------- 4.7 按钮 ---------- */
    function playDisabledReason() {
        if (!hasState()) return '等待服务器状态…';
        var s = G.state;
        if (!G.wsConnected) return '连接已断开，正在重连…';
        if (s.phase !== 'playing') return '游戏还没开始';
        if (!s.currentPlayerId) return '等待发牌…';
        if (!isMyId(s.currentPlayerId)) {
            return s.currentPlayerId
                ? '还没轮到你（轮到 ' + playerName(s.currentPlayerId, '其他玩家') + '）'
                : '还没轮到你';
        }
        if (!s.canPlay) return '现在不能出牌';
        if (selectCount() === 0) return '请先选择 1~5 张手牌';
        return '';
    }

    function doubtDisabledReason() {
        if (!hasState()) return '等待服务器状态…';
        var s = G.state;
        if (!G.wsConnected) return '连接已断开，正在重连…';
        if (s.phase !== 'playing') return '游戏还没开始';
        if (!s.currentPlayerId) return '等待发牌…';
        if (!isMyId(s.currentPlayerId)) {
            return s.currentPlayerId
                ? '还没轮到你（轮到 ' + playerName(s.currentPlayerId, '其他玩家') + '）'
                : '还没轮到你';
        }
        if (!s.lastPlayerId) return '本轮还没有人出牌，无牌可质疑';
        if (isMyId(s.lastPlayerId)) return '不能质疑自己打出的牌';
        if (!s.canDoubt) return '现在不能质疑';
        return '';
    }

    function renderButtons() {
        var s = hasState() ? G.state : null;
        var canPlay = !!(s && s.canPlay && selectCount() >= 1 && selectCount() <= MAX_SELECT && isMyId(s.currentPlayerId));
        var canDoubt = !!(s && s.canDoubt && isMyId(s.currentPlayerId));
        var canPass = !!(s && s.canPass && isMyId(s.currentPlayerId));

        if (D.playBtn) {
            D.playBtn.classList.toggle('is-disabled', !canPlay);
            var sel = selectCount();
            D.playBtn.textContent = sel > 0 ? ('出牌 (' + sel + ')') : '出牌';
        }
        if (D.doubtBtn) {
            D.doubtBtn.classList.toggle('is-disabled', !canDoubt);
        }
        if (D.passBtn) {
            D.passBtn.classList.toggle('is-disabled', !canPass);
        }
    }

    /* ---------- 4.8 结算面板 ---------- */
    function renderOverPanel() {
        if (!D.overPanel) return;
        var s = G.state;
        var over = (s.phase === 'over') && G.showOver;
        D.overPanel.hidden = !over;
        if (!over) return;

        var winnerId = s.winnerId || (G.overInfo && G.overInfo.winnerId) || null;
        var winnerName = (winnerId && playerName(winnerId, null)) ||
            (G.overInfo && G.overInfo.winnerName) || '没有赢家';

        if (D.overWinner) {
            D.overWinner.textContent = winnerName + (isMyId(winnerId) ? '（你）' : '');
        }
        if (D.overSub) {
            D.overSub.textContent = isMyId(winnerId)
                ? '恭喜！你是最后的幸存者。'
                : '存活到最后的人获胜。';
        }
        if (D.restartBtn) {
            D.restartBtn.hidden = !s.isHost;
            D.restartBtn.classList.toggle('is-disabled', !G.wsConnected);
        }
        if (D.overWait) {
            D.overWait.hidden = !!s.isHost;
        }
    }

    /* ==================================================================
       5. 卡牌 DOM
       ================================================================== */
    /* 统一构造卡牌：
         val 为空 => 背面朝上（出牌未翻）
         val 有值 => 正面朝上
       selected：手牌选中（上移）
       ver：内容版本号；指纹变化说明是新出现的牌，播放一次入场动画 */
    function cardHtml(val, opt) {
        opt = opt || {};
        var revealed = (val !== null && val !== undefined && val !== '');
        var joker = revealed && isJoker(val);
        var cls = 'card' + (revealed ? '' : ' is-back') + (joker ? ' is-joker' : '');
        if (opt.extra) cls += ' ' + opt.extra;

        var shellCls = 'card-shell' + (opt.selected ? ' is-selected' : '');
        var anim = '';
        if (opt.ver && !U.dealSeen[opt.ver]) {
            U.dealSeen[opt.ver] = 1;
            anim = ' pop-in';
        }

        var idxAttr = (typeof opt.idx === 'number') ? ' data-idx="' + opt.idx + '"' : '';
        var inner;
        if (!revealed) {
            inner = '<span class="card-back-mark"></span>';
        } else if (joker) {
            /* 小丑牌：牌面画一个小丑 */
            inner = '<span class="card-value card-joker">' + jokerSvg() +
                '<i class="joker-sub">小丑</i></span>';
        } else {
            inner = '<span class="card-value">' + escapeHtml(val) + '</span>';
        }

        return '<div class="' + shellCls + anim + '"' + idxAttr + '>' +
            '<div class="' + cls + '" role="img" aria-label="' + escapeHtml(opt.label || '牌') + '">' +
            inner +
            '</div></div>';
    }

    /* ==================================================================
       6. 动画队列（state 重绘完成后播放，保证 DOM 是最新的）
       事件消息与 state 消息几乎同时到达；事件先入队，
       由紧随其后的 render() 调用 flushAnims() 统一播放。
       若 state 迟迟不来，则退化为一个宏任务后自行播放（兜底）。
       ================================================================== */
    /**
     * 每段演出的"占用时长"。一次质疑的三条消息（doubt / reveal / shot）
     * 几乎同时到达，靠这张表把它们排成：
     *   異議あり！  →  亮出被质疑的牌  →  转轮判定
     * 时间线跨多次 flush 累积在 U.animAt 上，所以中途来的 state 重绘不会插队。
     */
    var ANIM_HOLD = {
        doubt: 3000,        // 异议图出现到完全消失（实测反馈：2100 太短，来不及看清）
        reveal: 2000,       // 亮牌展示
        shot: 3000,         // 转轮旋转 + 结果
        gameover: 400,      // 胜利面板（排在开枪之后）
        play: 650,
        other: 420
    };

    function holdOf(item) {
        var t = item && item.type;
        return ANIM_HOLD[t] || ANIM_HOLD.other;
    }

    function resetAnimTimeline() {
        U.animAt = 0;
    }

    function queueAnim(type, payload) {
        U.pending.push({ type: type, payload: payload || {} });
        if (!U.animTimer) {
            U.animTimer = setTimeout(function () {
                U.animTimer = null;
                flushAnims();
            }, 250);
        }
    }

    function flushAnims() {
        if (U.animTimer) {
            clearTimeout(U.animTimer);
            U.animTimer = null;
        }
        if (!U.pending.length) return;
        var list = U.pending;
        U.pending = [];
        var now = Date.now();
        var at = Math.max(now, U.animAt || 0);
        for (var i = 0; i < list.length; i++) {
            (function (item, wait) {
                if (wait <= 8) playAnim(item);
                else setTimeout(function () { playAnim(item); }, wait);
            })(list[i], at - now);
            at += holdOf(list[i]);
        }
        U.animAt = at;
    }

    function playAnim(item) {
        if (!item) return;
        if (item.type === 'doubt') { playObjection(item.payload); return; }
        if (item.type === 'shot') { playShot(item.payload); return; }
        if (item.type === 'reveal') { playFlip(item.payload); showRevealStage(item.payload); return; }
        if (item.type === 'gameover') {
            /* 到这里说明开枪/淘汰的演出已经播完了 */
            G.showOver = true;
            if (hasState()) render();
            return;
        }
    }

    /* ---------- 6.1 翻牌 ---------- */
    function playFlip(payload) {
        var pid = payload.playerId;
        if (!pid) return;
        sfx('card');
        var host = document.querySelector('[data-played="' + cssEscape(pid) + '"]');
        if (!host) return;
        /* 只对已经翻开的牌播动画（背面还没回填 cards 时跳过） */
        var all = host.querySelectorAll('.played-card');
        var cards = [];
        for (var i = 0; i < all.length; i++) {
            if (!all[i].classList.contains('is-back')) cards.push(all[i]);
        }
        for (var k = 0; k < cards.length; k++) {
            (function (el, idx) {
                setTimeout(function () {
                    el.classList.remove('flip-anim');
                    void el.offsetWidth;         // 强制重排以重播动画
                    el.classList.add('flip-anim');
                }, idx * 170);
            })(cards[k], k);
        }
    }

    /* ---------- 6.1b 亮出被质疑的牌（异议播完之后） ---------- */
    function showRevealStage(msg) {
        if (!msg) return;
        var cards = (msg.cards && msg.cards.length) ? msg.cards : [];
        var who = playerName(msg.playerId, '上家');
        var lie = !!msg.lie;
        var doubt = U.lastDoubt || {};
        var shooterId = lie ? msg.playerId : doubt.byId;
        var shooter = shooterId ? playerName(shooterId, '') : '';

        var faces = '';
        for (var i = 0; i < cards.length; i++) {
            faces += cardHtml(cards[i], { extra: 'reveal-card', label: '被质疑的牌 ' + cards[i] });
        }

        var el = document.createElement('div');
        el.className = 'reveal-stage' + (lie ? ' is-lie' : ' is-truth');
        el.innerHTML =
            '<div class="reveal-box">' +
            '<div class="reveal-title">' + escapeHtml(who) + ' 出的是</div>' +
            '<div class="reveal-cards">' + (faces || '<span class="reveal-none">—</span>') + '</div>' +
            '<div class="reveal-verdict">' + (lie ? '撒谎！' : '说的是真话') + '</div>' +
            (shooter ? '<div class="reveal-shooter">' + escapeHtml(shooter) + ' 要对自己开一枪</div>' : '') +
            '</div>';
        document.body.appendChild(el);
        sfx('card');
        setTimeout(function () {
            el.classList.add('is-out');
            setTimeout(function () {
                if (el.parentNode) el.parentNode.removeChild(el);
            }, 380);
        }, ANIM_HOLD.reveal - 420);
    }


    function playShot(payload) {
        var pid = payload.playerId;
        var hit = !!payload.hit;

        showCylinderStage(playerName(pid, '玩家'));
        sfx('spin');

        setTimeout(function () {
            sfx(hit ? 'shot' : 'blank');
            settleCylinderStage(hit);

            if (hit) {
                shakeScreen();
                document.body.classList.add('shot-red');
                setTimeout(function () { document.body.classList.remove('shot-red'); }, 260);
            }

            showImpact(hit ? '嘭！淘汰' : '咔哒…', !hit);

            if (hit && pid) {
                var seat = document.querySelector('.seat[data-pid="' + cssEscape(pid) + '"]');
                if (seat) {
                    seat.classList.add('just-died');
                    setTimeout(function () { seat.classList.remove('just-died'); }, 700);
                }
            }
        }, SHOT_SPIN_MS);
    }

    /* 六弹巢左轮（SVG 手绘，不依赖图片） */
    function cylinderSvg() {
        var html = '<svg viewBox="0 0 200 200" class="cyl-svg" aria-hidden="true">';
        html += '<circle class="cyl-body" cx="100" cy="100" r="88"/>';
        html += '<circle class="cyl-rim" cx="100" cy="100" r="88"/>';
        for (var i = 0; i < 6; i++) {
            var a = (i / 6) * Math.PI * 2 - Math.PI / 2;
            var x = 100 + Math.cos(a) * 54;
            var y = 100 + Math.sin(a) * 54;
            html += '<circle class="cyl-chamber" cx="' + x.toFixed(1) + '" cy="' + y.toFixed(1) +
                '" r="21"/>';
        }
        html += '<circle class="cyl-pin" cx="100" cy="100" r="13"/>';
        html += '</svg>';
        return html;
    }

    function showCylinderStage(who) {
        removeCylinderStage();
        var el = document.createElement('div');
        el.className = 'revolver-stage';
        el.id = 'revolver-stage';
        el.innerHTML =
            '<div class="cyl-card">' +
            '<div class="cyl-title">' + escapeHtml(who) + ' 扣下扳机</div>' +
            '<div class="cyl-spin">' + cylinderSvg() + '</div>' +
            '<div class="cyl-sub">弹巢转动中…</div>' +
            '</div>';
        document.body.appendChild(el);
    }

    function settleCylinderStage(hit) {
        var el = document.getElementById('revolver-stage');
        if (!el) {
            return;
        }
        el.classList.add(hit ? 'is-hit' : 'is-dry');
        var sub = el.querySelector('.cyl-sub');
        if (sub) {
            sub.textContent = hit ? '嘭！' : '咔哒…';
        }
        setTimeout(function () {
            if (el.parentNode) {
                el.parentNode.removeChild(el);
            }
        }, hit ? 1600 : 1150);
    }

    function removeCylinderStage() {
        var old = document.getElementById('revolver-stage');
        if (old && old.parentNode) {
            old.parentNode.removeChild(old);
        }
    }

    function shakeScreen() {
        document.body.classList.remove('screen-shake');
        void document.body.offsetWidth;              // 强制重排以重播动画
        document.body.classList.add('screen-shake');
        setTimeout(function () { document.body.classList.remove('screen-shake'); }, 620);
    }

    /* ---------- 6.3 质疑：異議あり！ ---------- */
    var OBJECTION_SRC = 'img/objection.png';
    var objectionClipBroken = false;
    /** 播放录音时跳过开头静音的毫秒数（默认值，实际由 calibrateVoiceOnset 实测覆盖）。 */
    var VOICE_SKIP_MS = 180;
    var voiceCalibrated = false;

    function playObjection() {
        console.log('[objection] 异议演出开始（图片 ' + ANIM_HOLD.doubt + 'ms，语音跳过 ' + VOICE_SKIP_MS + 'ms）');
        var el = document.createElement('div');
        el.className = 'objection';
        /*
         * 图片是可替换素材：如果 img/objection.png 不存在（比如你自己画了一张，
         * 或者把它删掉了），就退化成大字「異議あり！」——公开仓库时不用担心素材版权。
         */
        el.innerHTML = '<img class="objection-img" src="' + OBJECTION_SRC + '" alt="異議あり！"'
            + ' onerror="this.style.display=&quot;none&quot;;'
            + 'if(this.parentNode){this.parentNode.className=&quot;objection objection-text&quot;;}">'
            + '<span class="objection-fallback">異議あり！</span>';
        document.body.appendChild(el);
        setTimeout(function () {
            el.classList.add('is-out');
            setTimeout(function () {
                if (el.parentNode) {
                    el.parentNode.removeChild(el);
                }
            }, 360);
        }, ANIM_HOLD.doubt - 400);
        playObjectionVoice();
    }

    /**
     * 三级降级：真人录音 → 系统日文语音 → 合成吼声。
     *
     * <p>录音文件开头通常带一小段静音，直接播会感觉「图片都出来了声音才响」。
     * 所以这里先按经验值跳过一点（VOICE_SKIP_MS），同时启动一次<b>离线</b>解码分析，
     * 实测这段录音到底多久才出声，之后每次播放都从实测位置开始音画对齐。</p>
     */
    function playObjectionVoice() {
        /* 关掉音效时就连异议语音一起静音 */
        if (window.SFX && !window.SFX.isEnabled()) {
            return;
        }
        calibrateVoiceOnset();
        if (!objectionClipBroken) {
            try {
                var a = new Audio('audio/objection.mp3');
                a.volume = 1;
                if (VOICE_SKIP_MS > 0) {
                    try { a.currentTime = VOICE_SKIP_MS / 1000; } catch (e) { /* 忽略 */ }
                }
                var p = a.play();
                if (p && p['catch']) {
                    p['catch'](function () {
                        objectionClipBroken = true;
                        console.log('[objection] 录音不可用，改用系统语音/合成音');
                        speakObjectionJa();
                    });
                } else {
                    console.log('[objection] 播放录音 audio/objection.mp3 (跳过 ' + VOICE_SKIP_MS + 'ms)');
                }
                return;
            } catch (e) {
                objectionClipBroken = true;
            }
        }
        speakObjectionJa();
    }

    /**
     * 离线解码录音，找出第一个"明显有声"的采样点，作为跳过时长。
     * 只用 decodeAudioData 分析，不播放、不接管正在播的音频，所以不会把声音弄没。
     */
    function calibrateVoiceOnset() {
        if (voiceCalibrated) {
            return;
        }
        voiceCalibrated = true;                      // 只做一次
        try {
            var Ctx = window.AudioContext || window.webkitAudioContext;
            if (!Ctx || !window.fetch) {
                return;
            }
            fetch('audio/objection.mp3').then(function (r) {
                return r.arrayBuffer();
            }).then(function (buf) {
                var ctx = new Ctx();
                return ctx.decodeAudioData(buf).then(function (audio) {
                    var ch = audio.getChannelData(0);
                    var rate = audio.sampleRate;
                    var threshold = 0.05;            // 约 -26dB
                    var start = -1;
                    for (var i = 0; i < ch.length; i++) {
                        if (Math.abs(ch[i]) > threshold) {
                            start = i;
                            break;
                        }
                    }
                    if (start > 0) {
                        /* 留 40ms 余量，别把起声的辅音切掉 */
                        VOICE_SKIP_MS = Math.max(0, Math.round(start / rate * 1000) - 40);
                        console.log('[objection] 实测静音 ' + Math.round(start / rate * 1000)
                            + 'ms，之后从 ' + VOICE_SKIP_MS + 'ms 开始播');
                    } else {
                        console.log('[objection] 录音开头没有静音，原速播放');
                    }
                    try { ctx.close(); } catch (e2) { /* 忽略 */ }
                });
            })['catch'](function () {
                /* 取不到文件（比如素材被删了）就走降级语音 */
            });
        } catch (e) {
            /* 分析失败就用默认值，不影响播放 */
        }
    }

    function speakObjectionJa() {
        try {
            var syn = window.speechSynthesis;
            if (syn && window.SpeechSynthesisUtterance) {
                var voices = syn.getVoices() || [];
                var ja = null;
                for (var i = 0; i < voices.length; i++) {
                    var lang = (voices[i].lang || '').toLowerCase();
                    if (lang.indexOf('ja') === 0) {
                        ja = voices[i];
                        break;
                    }
                }
                if (ja) {
                    var u = new SpeechSynthesisUtterance('異議あり！');
                    u.voice = ja;
                    u.lang = ja.lang || 'ja-JP';
                    u.rate = 1.15;
                    u.pitch = 1.05;
                    u.volume = 1;
                    syn.cancel();
                    syn.speak(u);
                    return;
                }
            }
        } catch (e) { /* 忽略 */ }
        sfx('shout');
    }

    function showImpact(text, isDry) {
        var el = document.createElement('div');
        el.className = 'impact-text' + (isDry ? ' is-dry' : '');
        el.textContent = text;
        document.body.appendChild(el);
        setTimeout(function () {
            if (el.parentNode) el.parentNode.removeChild(el);
        }, 1200);
    }

    /* 计算开枪特效的位置与方向：自己朝上，对手在哪个槽位就指向哪个方向 */
    function aimOf(pid) {
        var aim = { cls: 'point-to-top', x: window.innerWidth / 2, y: window.innerHeight - 40, dotX: '50%', dotY: '86%' };
        if (isMyId(pid)) return aim;

        var seat = document.querySelector('.seat[data-pid="' + cssEscape(pid) + '"]');
        if (!seat) return aim;
        var r = seat.getBoundingClientRect();
        var slot = seat.getAttribute('data-slot') || 'center';
        var cfg = SLOT_AIM[slot] || SLOT_AIM.center;
        aim.cls = cfg.cls;
        aim.dotX = cfg.dotX;
        aim.dotY = cfg.dotY;
        aim.x = r.left + r.width / 2;
        aim.y = r.top + Math.min(r.height, 90) / 2 + 6;
        return aim;
    }

    function cssEscape(s) {
        return String(s).replace(/[^a-zA-Z0-9_\-]/g, '\\$&');
    }

    /* 内联 SVG 左轮（不引用任何外部资源） */
    function revolverSvg() {
        return '<svg class="revolver" viewBox="0 0 100 100" aria-hidden="true">' +
            '<rect x="50" y="45" width="42" height="10" rx="3" fill="#4a4a4a" stroke="#2b2b2b" stroke-width="2"/>' +
            '<rect x="86" y="44" width="8" height="12" rx="2" fill="#333"/>' +
            '<g class="cylinder">' +
            '<circle cx="40" cy="50" r="15" fill="#7a7a7a" stroke="#2b2b2b" stroke-width="2"/>' +
            '<circle cx="40" cy="41" r="3" fill="#3d3d3d"/>' +
            '<circle cx="47.8" cy="45.5" r="3" fill="#3d3d3d"/>' +
            '<circle cx="47.8" cy="54.5" r="3" fill="#3d3d3d"/>' +
            '<circle cx="40" cy="59" r="3" fill="#3d3d3d"/>' +
            '<circle cx="32.2" cy="54.5" r="3" fill="#3d3d3d"/>' +
            '<circle cx="32.2" cy="45.5" r="3" fill="#3d3d3d"/>' +
            '<circle cx="40" cy="50" r="3.4" fill="#2b2b2b"/>' +
            '</g>' +
            '<path d="M30 55 l-6 26 h12 l-4 -26 z" fill="#4a3527" stroke="#2b2b2b" stroke-width="2"/>' +
            '<path d="M23 58 h22 v5 h-22 z" fill="#5a5a5a" stroke="#2b2b2b" stroke-width="1.5"/>' +
            '</svg>';
    }

    /* ==================================================================
       7. 收发消息
       ================================================================== */
    function send(obj) {
        if (!G.ctrl || !G.ctrl.isOpen()) {
            toast('连接已断开，正在重连…', 'error', 2500);
            return false;
        }
        return G.ctrl.send(obj);
    }

    /** 是否已经真正入座（收到过带 you 的状态快照）。 */
    function isSeated() {
        return !!(G.state && G.state.you);
    }

    /** 改名字：只在大厅/结算后可用；对局中服务端会拒绝，这里先拦一下给个提示。 */
    function sendRename(name) {
        if (G.state && G.state.phase === 'playing') {
            toast('对局中不能改名，等这一局结束', 'warn', 2400);
            return;
        }
        name = (name || '').trim().slice(0, NAME_MAX);
        if (!name) {
            toast('名称不能为空', 'warn', 2000);
            return;
        }
        L.name = name;
        store(LS.name, name);
        if (D.nameInput) D.nameInput.value = name;
        if (!send({ t: 'rename', name: name })) {
            toast('连接已断开，重连后再改', 'error', 2200);
        }
    }

    function doJoin() {
        clearAutoJoin();
        if (D.joinBtn && D.joinBtn.classList.contains('is-disabled')) {
            toast('正在连接服务器，请稍候…', 'warn', 2500);
            return;
        }
        var raw = (D.nameInput && D.nameInput.value ? D.nameInput.value : '').trim();

        /* 已经在房间里 → 这一下就是"改名" */
        if (isSeated()) {
            if (!raw) {
                toast('名称不能为空', 'warn', 2000);
                return;
            }
            sendRename(raw);
            return;
        }

        var name = (raw || genDefaultName()).slice(0, NAME_MAX);
        L.name = name;
        store(LS.name, name);

        if (D.nameInput) {
            D.nameInput.readOnly = false;
            D.nameInput.value = name;
        }
        var msg = { t: 'join', name: name, uid: ensureUid() };
        if (L.token) msg.token = L.token;      // 断线重连：带上次 token 恢复原座位
        if (send(msg)) {
            G.joined = true;
            toast('正在进入房间…', 'info', 1500);
        } else {
            G.joined = true;                   // 断线期间也记住意图，重连后自动 join
            toast('连接已断开，重连后将自动进入房间', 'error', 3000);
        }
    }

    // ---------------------------------------------------------------- 改名浮层
    function openRename() {
        if (!D.renameLayer) return;
        var me = playerById(G.pid);
        if (D.renameInput) {
            D.renameInput.value = (me && me.name) || L.name || '';
        }
        D.renameLayer.hidden = false;
        if (D.renameInput) {
            D.renameInput.focus();
            try { D.renameInput.select(); } catch (e) { /* 忽略 */ }
        }
    }

    function closeRename() {
        if (D.renameLayer) D.renameLayer.hidden = true;
    }

    function applyRename() {
        var v = (D.renameInput && D.renameInput.value ? D.renameInput.value : '').trim();
        if (!v) {
            toast('名称不能为空', 'warn', 2000);
            return;
        }
        sendRename(v);
        closeRename();
    }

    // ---------------------------------------------------------------- 规则说明
    function openRules() {
        if (!D.rulesLayer) return;
        /* 懒加载：第一次打开才请求 rules.html */
        if (D.rulesFrame && !D.rulesFrame.getAttribute('src')) {
            D.rulesFrame.setAttribute('src', 'rules.html');
        }
        D.rulesLayer.hidden = false;
    }

    function closeRules() {
        if (D.rulesLayer) D.rulesLayer.hidden = true;
    }

    // ---------------------------------------------------------------- 局内聊天
    function toggleChat() {
        if (!D.chatPanel) return;
        D.chatPanel.hidden = !D.chatPanel.hidden;
        if (!D.chatPanel.hidden) {
            if (D.chatDot) D.chatDot.hidden = true;
            if (D.chatInput) D.chatInput.focus();
            scrollChat();
        }
    }

    function sendChat() {
        if (!D.chatInput) return;
        /* 被淘汰也能发言：观战只是不能出牌/质疑，聊天照旧 */
        var text = (D.chatInput.value || '').trim();
        if (!text) return;
        if (text.length > 60) text = text.slice(0, 60);
        if (!send({ t: 'chat', text: text })) {
            toast('连接已断开，发不出去', 'error', 2000);
            return;
        }
        D.chatInput.value = '';
    }

    /**
     * 是否处于「观战」状态：对局中自己已经被淘汰。
     * 观战者能继续看牌桌，但不下手牌、不能出牌/质疑、不能发言。
     */
    function isSpectator() {
        if (!hasState()) return false;
        var s = G.state;
        if (s.phase === 'lobby' || s.phase === 'over') return false;
        var me = playerById(G.pid);
        return !!(me && me.alive === false);
    }

    function appendChat(name, text, system, pid) {
        if (!D.chatLog) return;
        var empty = D.chatLog.querySelector('.chat-empty');
        if (empty) D.chatLog.removeChild(empty);

        var line = document.createElement('div');
        line.className = 'chat-line' + (system ? ' is-system' : '');
        if (system) {
            line.textContent = text;
        } else {
            line.innerHTML = avatarHtml(playerById(pid), 'av-xs') +
                '<span class="chat-name">' + escapeHtml(name) + '</span>' +
                escapeHtml(text);
        }
        D.chatLog.appendChild(line);
        while (D.chatLog.childNodes.length > 60) {
            D.chatLog.removeChild(D.chatLog.firstChild);
        }

        var open = D.chatPanel && !D.chatPanel.hidden;
        if (open) {
            scrollChat();
        } else if (D.chatDot) {
            D.chatDot.hidden = false;
        }
    }

    function scrollChat() {
        if (D.chatLog) D.chatLog.scrollTop = D.chatLog.scrollHeight;
    }

    function onChat(msg) {
        appendChat(msg.name || '玩家', msg.text || '', false, msg.playerId);
    }

    // ---------------------------------------------------------------- 头像
    function pickAvatar() {
        if (!D.avatarFile) {
            toast('这个环境不支持选图片', 'warn', 2200);
            return;
        }
        D.avatarFile.click();
    }

    /** 选完图 → 居中裁成正方形 → 缩到 96×96 的 JPEG → 上传。 */
    function onAvatarFile(ev) {        var input = ev.target;
        var file = input && input.files && input.files[0];
        if (!file) return;
        if (!/^image\//.test(file.type || '')) {
            toast('请选择图片文件', 'warn', 2200);
            input.value = '';
            return;
        }
        var reader = new FileReader();
        reader.onload = function () {
            var img = new Image();
            img.onload = function () {
                try {
                    var size = 96;
                    var cv = document.createElement('canvas');
                    cv.width = size;
                    cv.height = size;
                    var ctx = cv.getContext('2d');
                    var s = Math.min(img.width, img.height) || size;
                    ctx.drawImage(img, (img.width - s) / 2, (img.height - s) / 2, s, s, 0, 0, size, size);
                    sendAvatarData(cv.toDataURL('image/jpeg', 0.82));
                } catch (e) {
                    toast('图片处理失败', 'error', 2400);
                }
            };
            img.onerror = function () {
                toast('这张图片读不出来', 'error', 2200);
            };
            img.src = String(reader.result);
        };
        reader.onerror = function () {
            toast('读取文件失败', 'error', 2200);
        };
        reader.readAsDataURL(file);
        input.value = '';                       // 允许重复选同一张
    }

    function sendAvatarData(dataUrl) {
        var s = String(dataUrl || '');
        var comma = s.indexOf(',');
        if (comma < 0) {
            toast('图片数据异常', 'error', 2200);
            return;
        }
        var mime = 'image/jpeg';
        var semi = s.indexOf(';');
        if (s.indexOf('data:') === 0 && semi > 5) {
            mime = s.slice(5, semi);
        }
        saveAvatarLocal(s);                 // 本地留一份，App 重启后自动补传
        if (send({ t: 'avatar', data: s.slice(comma + 1), mime: mime })) {
            toast('正在上传头像…', 'info', 1200);
        } else {
            toast('连接已断开，换不了头像', 'error', 2400);
        }
    }

    /* ---------- 设备 UID 与胜场榜 ----------
       UID：第一次运行时生成、永久存在本机，改名/换头像/重连都不变，
            胜场就是按它统计的（所以改名不会变成"另一个人"）。
       排行榜数据：服务端只在内存里维护，**持久化放在房主设备上** ——
            房主每次进房把本机存的账本用 stats 交上去，服务端合并后再存回本机。 */
    var LS_UID = 'liarbar.uid';
    var LS_BOARD = 'liarbar.board';

    function ensureUid() {
        if (G.uid) return G.uid;
        var uid = '';
        try { uid = localStorage.getItem(LS_UID) || ''; } catch (e) { uid = ''; }
        if (!uid) {
            uid = 'u' + Date.now().toString(36) + Math.random().toString(36).slice(2, 8);
            try { localStorage.setItem(LS_UID, uid); } catch (e) { /* 隐私模式忽略 */ }
        }
        G.uid = uid;
        return uid;
    }

    function loadBoard() {
        try {
            var raw = localStorage.getItem(LS_BOARD);
            return raw ? JSON.parse(raw) : null;
        } catch (e) {
            return null;
        }
    }

    function saveBoard(list) {
        if (!list || !list.length) return;
        try {
            var board = loadBoard() || { wins: {}, names: {} };
            if (!board.wins) board.wins = {};
            if (!board.names) board.names = {};
            for (var i = 0; i < list.length; i++) {
                var e = list[i];
                if (!e || !e.uid) continue;
                var w = numOf(e.wins);
                var old = board.wins[e.uid];
                if (old === undefined || w > old) board.wins[e.uid] = w;
                if (e.name) board.names[e.uid] = e.name;
            }
            localStorage.setItem(LS_BOARD, JSON.stringify(board));
        } catch (err) {
            /* 空间不足 / 隐私模式：忽略，不影响游戏 */
        }
    }

    /** 房主进房后把本机存的账本交上去（只交一次）。 */
    function maybePushStats() {
        if (U.statsPushed) return;
        var s = G.state;
        if (!s || !s.isHost) return;
        U.statsPushed = true;
        var board = loadBoard();
        if (!board || !board.wins) return;
        var n = 0;
        for (var k in board.wins) {
            if (Object.prototype.hasOwnProperty.call(board.wins, k)) n++;
        }
        if (!n) return;
        send({ t: 'stats', wins: board.wins, names: board.names || {} });
        console.log('[board] 上交本机排行榜 ' + n + " 条");
    }

    /** 排行榜面板（大厅）。 */
    function renderBoard() {
        if (!D.board) return;
        var list = (hasState() && G.state.leaderboard) || [];
        if (!list.length) {
            D.board.hidden = true;
            return;
        }
        D.board.hidden = false;
        var myUid = ensureUid();
        var html = '';
        for (var i = 0; i < list.length && i < 5; i++) {
            var e = list[i] || {};
            html += '<div class="board-row' + (e.uid === myUid ? ' is-me' : '') + '">' +
                '<span class="board-rank">' + (i + 1) + '</span>' +
                '<span class="board-name">' + escapeHtml(e.name || '无名') +
                (e.uid === myUid ? '（你）' : '') + '</span>' +
                '<span class="board-wins">' + numOf(e.wins) + ' 胜</span>' +
                '</div>';
        }
        D.boardList.innerHTML = html;
    }

    /* ---------- 头像本地缓存 ----------
       头像存在服务端内存里（不落盘），App/手机重启后服务端就清空了。
       所以本地也存一份 data URL，进房后如果服务端还没有就自动补传一次，
       这样"重新打开 App 头像不见了"的问题就没有了。 */
    var LS_AVATAR = 'liarbar.avatar';

    function loadAvatarLocal() {
        try {
            return localStorage.getItem(LS_AVATAR) || '';
        } catch (e) {
            return '';
        }
    }

    function saveAvatarLocal(dataUrl) {
        try {
            if (dataUrl) {
                localStorage.setItem(LS_AVATAR, dataUrl);
            } else {
                localStorage.removeItem(LS_AVATAR);
            }
        } catch (e) {
            /* 隐私模式 / 空间不足：忽略，不影响游戏 */
        }
    }

    /** 进房后调用：服务端没有头像但本地有 → 补传。 */
    function maybeRestoreAvatar() {
        if (U.avatarTried) {
            return;
        }
        var s = G.state;
        if (!s || !G.pid || s.you !== G.pid) {
            return;
        }
        var me = playerById(G.pid);
        if (!me) {
            return;
        }
        U.avatarTried = true;
        if (numOf(me.avatar) > 0) {
            return;                          // 服务端已经有一份了
        }
        var local = loadAvatarLocal();
        if (!local) {
            return;                          // 本来就没设过头像
        }
        sendAvatarData(local);
    }

    function clearAvatar() {
        saveAvatarLocal('');
        U.avatarTried = true;                // 别再自动补传回来
        if (send({ t: 'avatar', data: '' })) {
            toast('已清除头像', 'info', 1600);
        }
    }

    // ---------------------------------------------------------------- 分享地址
    function openLan() {
        if (!D.lanLayer) return;
        var s = G.state || {};
        if (D.lanInput) {
            D.lanInput.value = (typeof s.lanUrl === 'string' && s.lanUrl) ? s.lanUrl : '';
        }
        /* 自动检测到的网卡地址，点一下就能填进去 */
        if (D.lanChips) {
            D.lanChips.innerHTML = '';
            var cands = s.lanCandidates || [];
            for (var i = 0; i < cands.length; i++) {
                (function (ip) {
                    var chip = document.createElement('button');
                    chip.type = 'button';
                    chip.className = 'chip';
                    chip.textContent = ip;
                    chip.onclick = function () {
                        if (D.lanInput) D.lanInput.value = ip;
                    };
                    D.lanChips.appendChild(chip);
                })(cands[i]);
            }
            if (!cands.length) {
                var none = document.createElement('span');
                none.className = 'chat-empty';
                none.textContent = '没检测到局域网网卡，可以手动填';
                D.lanChips.appendChild(none);
            }
        }
        D.lanLayer.hidden = false;
        if (D.lanInput) {
            D.lanInput.focus();
            try { D.lanInput.select(); } catch (e) { /* 忽略 */ }
        }
    }

    function closeLan() {
        if (D.lanLayer) D.lanLayer.hidden = true;
    }

    function applyLan() {
        var v = (D.lanInput && D.lanInput.value ? D.lanInput.value : '').trim();
        if (!v) {
            toast('地址不能为空（想恢复自动请点「恢复自动」）', 'warn', 2400);
            return;
        }
        if (send({ t: 'set-lan', url: v })) {
            toast('分享地址已更新', 'success', 1800);
        }
        closeLan();
    }

    function sendStart() {
        if (send({ t: 'start' })) toast('已请求开始游戏', 'info', 1500);
    }

    function doPlay() {
        var reason = playDisabledReason();
        if (reason) {
            toast(reason, 'warn', 2500);
            return;
        }
        var idxs = Object.keys(U.selected).map(function (k) { return parseInt(k, 10); })
            .filter(function (n) { return !isNaN(n); })
            .sort(function (a, b) { return a - b; });
        if (idxs.length < 1 || idxs.length > MAX_SELECT) {
            toast('请选择 1~' + MAX_SELECT + ' 张牌', 'warn', 2500);
            return;
        }
        if (send({ t: 'play', cards: idxs })) {
            U.selected = {};                   // 本地先清掉，等服务端 state 回来再对齐
            renderHand();
            renderButtons();
        }
    }

    function doDoubt() {
        var reason = doubtDisabledReason();
        if (reason) {
            toast(reason, 'warn', 2500);
            return;
        }
        if (send({ t: 'doubt' })) {
            U.selected = {};
        }
    }

    /** 过牌（"不要"）：这一手不打，让下家接手；下家照样能质疑上家。 */
    function doPass() {
        var s = hasState() ? G.state : null;
        if (!s || !isMyId(s.currentPlayerId)) {
            toast('还没轮到你', 'warn', 2200);
            return;
        }
        if (s.judging) {
            toast('判定演出中，等这一波播完', 'warn', 2200);
            return;
        }
        if (send({ t: 'pass' })) {
            U.selected = {};
        }
    }

    function doRestart() {
        var s = G.state;
        if (!hasState() || !s.isHost) {
            toast('只有房主可以重开', 'warn', 2500);
            return;
        }
        if (!G.wsConnected) {
            toast('连接已断开，正在重连…', 'error', 2500);
            return;
        }
        send({ t: 'restart' });
    }

    /* ==================================================================
       8. 处理服务端消息
       ================================================================== */
    function applyMessage(msg) {
        if (!msg || typeof msg !== 'object') return;
        switch (msg.t) {
            case 'welcome': onWelcome(msg); break;
            case 'state': onState(msg); break;
            case 'played': onPlayed(msg); break;
            case 'passed': onPassed(msg); break;
            case 'doubt': onDoubt(msg); break;
            case 'reveal': onReveal(msg); break;
            case 'shot': onShot(msg); break;
            case 'round': onRound(msg); break;
            case 'gameover': onGameover(msg); break;
            case 'chat': onChat(msg); break;
            case 'avatar-ok': toast('头像已更新', 'success', 1400); break;
            case 'renamed':
                if (msg.name) {
                    L.name = msg.name;
                    store(LS.name, msg.name);
                    if (D.nameInput) D.nameInput.value = msg.name;
                    toast('名称已改为「' + msg.name + '」', 'success', 1800);
                }
                break;
            case 'error': onError(msg); break;
            case 'pong': break;                        // 心跳回应，无需处理
            default: break;                            // 未知类型忽略（向后兼容）
        }
    }

    function onWelcome(msg) {
        if (msg.playerId) {
            G.pid = msg.playerId;
            store(LS.pid, G.pid);
        }
        if (msg.token) {
            L.token = msg.token;
            store(LS.token, L.token);
        }
        /* 每次（重新）连上都要重新确认一次头像：主机重启后服务端内存清空，
           这里放开标志，下一次 state 到达时会把本地缓存的那张自动补传上去 */
        U.avatarTried = false;
        if (hasState()) render();
    }

    function onState(msg) {
        var prevPhase = hasState() ? G.state.phase : null;
        var prevSig = G.stateSig;
        G.state = msg;

        /*
         * 房主设备负责持久化胜场榜：**任何阶段**收到带榜的 state 都立刻存档，
         * 不能等回到大厅 —— 否则"刚打完一局就关掉 App"这次胜场就丢了。
         */
        if (msg.isHost && msg.leaderboard) {
            saveBoard(msg.leaderboard);
        }

        /* 自己的 playerId 以 welcome 为准；state.you 作为兜底（例如先收到 state 的情况） */
        if (typeof msg.you === 'string' && msg.you && msg.you !== G.pid) {
            G.pid = msg.you;
            store(LS.pid, G.pid);
        }

        /* 手牌签名变化 => 清空本地选牌 */
        var sig = cardsKey(msg.hand);
        if ((typeof msg.phase === 'string' && msg.phase !== 'playing') || sig !== U.handSig) {
            U.selected = {};
        }
        U.handSig = sig;

        /* 新一轮（换目标牌 / 阶段切换 / table 清空）=> 重置翻牌动画去重表 */
        var tableLen = (msg.table && msg.table.length) ? msg.table.length : 0;
        var roundPrefix = String(msg.phase) + '|' + (msg.targetCard || '-') + '|';
        if (!tableLen || prevSig.indexOf(roundPrefix) !== 0) {
            U.revealSeen = {};
        }
        G.stateSig = roundPrefix + tableLen;
        if (msg.phase !== 'over') {
            G.overInfo = null;
            G.showOver = false;          // 离开结算态：下次结算要重新等演出
        }

        var firstState = (prevPhase === null);
        render();

        if (firstState && msg.phase !== 'lobby') {
            toast('游戏开始！', 'success', 1800);
        }
        if (msg.phase === 'playing' && msg.currentPlayerId && isMyId(msg.currentPlayerId) &&
            prevPhase !== null && msg.canPlay) {
            toast('轮到你出牌了', 'info', 1600);
        }
    }

    function onPlayed(msg) {
        var name = playerName(msg.playerId, '有玩家');
        var n = numOf(msg.count);
        sfx('card');
        toast(name + ' 打出 ' + n + ' 张牌', 'info', 1800);
    }

    function onDoubt(msg) {
        var by = playerName(msg.byId, '有玩家');
        var target = playerName(msg.targetId, '上家');
        U.lastDoubt = { byId: msg.byId, targetId: msg.targetId };
        /* 异议图排在最前面，亮牌与开枪由队列顺延 */
        queueAnim('doubt', msg);
        toast(by + ' 质疑了 ' + target, 'warn', 2200);
    }

    function onReveal(msg) {
        var pid = msg.playerId;
        var cards = msg.cards || [];
        /* 翻牌信息最终以 state.table[].cards 为准，这里只负责动画与即时提示 */
        if (U.revealSeen[pid]) return;
        if (pid) U.revealSeen[pid] = true;
        queueAnim('reveal', msg);
        toast(playerName(pid, '上家') + ' 亮牌 [' + cards.join(' ') + '] · ' +
            (msg.lie ? '撒谎！' : '没撒谎'), msg.lie ? 'error' : 'success', 2600);
    }

    function onShot(msg) {
        var pid = msg.playerId;
        var hit = !!msg.hit;
        /* state 到达后 DOM 才是最新的，开枪特效走动画队列在重绘之后播放 */
        queueAnim('shot', msg);
        if (!hit) toast(playerName(pid, '玩家') + ' 逃过一劫', 'info', 1800);
    }

    function onRound(msg) {
        U.revealSeen = {};
        U.dealSeen = {};
        U.selected = {};
        resetAnimTimeline();                 // 新回合，演出时间线归零
        var target = (typeof msg.targetCard === 'string' && msg.targetCard) ? msg.targetCard : '?';
        var head = (msg.reason === 'given-up')
            ? '两圈没人质疑 · 本轮作废重开'
            : '新一轮开始';
        toast(head + ' · 目标牌 ' + target +
            (msg.firstPlayerId ? ' · ' + playerName(msg.firstPlayerId, '') + ' 先出' : ''), 'info', 2600);
    }

    /** 有人过牌。 */
    function onPassed(msg) {
        toast(playerName(msg.playerId, '有玩家') + ' 不要（过牌）', 'info', 1600);
    }

    function onGameover(msg) {
        G.overInfo = { winnerId: msg.winnerId || null, winnerName: msg.winnerName || null };
        var name = msg.winnerName || playerName(msg.winnerId, null) || '没有赢家';
        toast('游戏结束 · ' + name + ' 获胜', 'success', 3500);
        /* 重点：胜利面板要等"开枪→死亡"的演出播完再亮出来。
           走同一条动画时间线排队，天然排在 shot 之后。 */
        queueAnim('gameover', msg);
        /* 兜底：万一事件丢了（比如重连），3.5 秒后也把面板放出来 */
        setTimeout(function () {
            if (!G.showOver && hasState() && G.state.phase === 'over') {
                G.showOver = true;
                render();
            }
        }, 3500);
    }

    function onError(msg) {
        var text = (typeof msg.msg === 'string' && msg.msg) ? msg.msg : errorTextOf(msg.code);
        toast(text, 'error', 3000);
    }

    /* 协议 4.2 的 error.code 兜底文案 */
    function errorTextOf(code) {
        var map = {
            'bad-message': '消息格式错误',
            'not-in-room': '你还没有进入房间',
            'not-host': '只有房主可以操作',
            'not-your-turn': '还没轮到你',
            'bad-card-count': '出牌数量必须是 1~5 张',
            'dead-cannot-chat': '你已经被淘汰，观战中不能发言',
            'judging': '判定演出中，等这一波播完',
            'card-not-owned': '你没有这些牌',
            'nothing-to-doubt': '没有可以质疑的出牌',
            'not-enough-players': '人数不足，至少需要 2 人',
            'game-in-progress': '游戏正在进行中',
            'name-required': '请先填写昵称',
            'join-timeout': '加入超时，请重试'
        };
        return map[code] || '操作失败，请重试';
    }

    /* ==================================================================
       9. 连接生命周期
       ================================================================== */
    function onOpen() {
        var wasEverConnected = G.everConnected;
        G.wsConnected = true;
        G.everConnected = true;
        render();

        /* 断线重连（或用户此前已进入房间）：立即补发 join，带 token 恢复原座位 / 原手牌 / 原枪 */
        if (G.joined) {
            var msg = { t: 'join', name: L.name || DEFAULT_NAME, uid: ensureUid() };
            if (L.token) msg.token = L.token;
            send(msg);
            if (wasEverConnected) toast('连接已恢复，正在返回座位…', 'success', 2200);
            return;
        }

        /* 主机模式：URL 带 ?join=0 时本机不入座，手机只当服务端，把 4 个位置全留给朋友。
           （Android 端以 host_only 启动时会带上这个参数；用户仍可手动点「进入房间」。） */
        if (/[?&]join=0/.test(String(location.search || ''))) {
            clearAutoJoin();
            if (D.joinHint) D.joinHint.textContent = '主机模式：本机未入座，把地址分享给朋友即可';
            return;
        }

        /* 协议：连上后 5 秒内必须发 join。用户没来得及点按钮时自动用当前昵称加入，
           避免被服务端判定 join-timeout 后反复断开重连。 */
        if (D.joinHint) D.joinHint.textContent = '输入昵称后进入房间（数秒后自动加入）';
        clearAutoJoin();
        var autoJoin = function (left) {
            G.autoJoinTimer = setTimeout(function () {
                if (G.joined || !G.wsConnected) return;
                /* 用户正在输入昵称时再等一会儿 */
                if (D.nameInput && document.activeElement === D.nameInput && left > 0) {
                    autoJoin(left - 1);
                    return;
                }
                doJoin();
            }, 3000);
        };
        autoJoin(2);
    }

    function clearAutoJoin() {
        if (G.autoJoinTimer) {
            clearTimeout(G.autoJoinTimer);
            G.autoJoinTimer = null;
        }
    }

    function onClose() {
        G.wsConnected = false;
        render();
    }

    /* ==================================================================
       10. 初始化
       ================================================================== */
    function initDom() {
        D.conn = $('conn-status');
        D.banner = $('banner');
        D.viewLobby = $('view-lobby');
        D.viewGame = $('view-game');
        D.nameInput = $('name-input');
        D.joinBtn = $('join-btn');
        D.joinHint = $('join-hint');
        D.shareBox = $('share-box');
        D.lanUrl = $('lan-url');
        D.copyBtn = $('copy-btn');
        D.lobbyCount = $('lobby-count');
        D.lobbyList = $('lobby-list');
        D.lobbyActions = $('lobby-actions');
        D.waitHost = $('wait-host');
        D.seats = $('seats');
        D.deck = $('deck');
        D.targetCard = $('target-card');
        D.targetInner = $('target-card-inner');
        D.phaseTag = $('phase-tag');
        D.playBadge = $('play-badge');
        D.turnLine = $('turn-line');
        D.msgLine = $('msg-line');
        D.meStatus = $('me-status');
        D.mePlayed = $('me-played');
        D.hand = $('hand');
        D.playBtn = $('play-btn');
        D.doubtBtn = $('doubt-btn');
        D.passBtn = $('pass-btn');
        D.revolverLayer = $('revolver-layer');
        D.overPanel = $('over-panel');
        D.overWinner = $('over-winner');
        D.overSub = $('over-sub');
        D.restartBtn = $('restart-btn');
        D.overWait = $('over-wait');
        D.toastLayer = $('toast-layer');
        D.renameLayer = $('rename-layer');
        D.renameInput = $('rename-input');
        D.renameOk = $('rename-ok');
        D.renameCancel = $('rename-cancel');
        D.rulesLayer = $('rules-layer');
        D.rulesFrame = $('rules-frame');
        D.rulesBtn = $('rules-btn');
        D.rulesClose = $('rules-close');
        D.chat = $('chat');
        D.chatPanel = $('chat-panel');
        D.chatToggle = $('chat-toggle');
        D.chatDot = $('chat-dot');
        D.chatLog = $('chat-log');
        D.chatInput = $('chat-input');
        D.chatSend = $('chat-send');
        D.lanLayer = $('lan-layer');
        D.lanInput = $('lan-input');
        D.lanChips = $('lan-chips');
        D.lanOk = $('lan-ok');
        D.lanAuto = $('lan-auto');
        D.lanCancel = $('lan-cancel');
        D.lanEditBtn = $('lan-edit-btn');
        D.sfxBtn = $('sfx-btn');
        D.myAvatar = $('my-avatar');
        D.avatarBtn = $('avatar-btn');
        D.avatarClear = $('avatar-clear');
        D.avatarFile = $('avatar-file');
        D.spectatorBar = $('spectator-bar');
        D.board = $('board');
        D.boardList = $('board-list');
        D.shareHint = $('share-hint');
    }

    function updateSfxBtn() {
        if (!D.sfxBtn) return;
        var on = !!(window.SFX && window.SFX.isEnabled());
        D.sfxBtn.textContent = on ? '🔊 音效' : '🔇 静音';
        D.sfxBtn.title = on ? '点击关闭音效' : '点击开启音效';
    }

    function bindEvents() {
        if (D.joinBtn) D.joinBtn.onclick = doJoin;
        if (D.playBtn) D.playBtn.onclick = doPlay;
        if (D.doubtBtn) D.doubtBtn.onclick = doDoubt;
        if (D.passBtn) D.passBtn.onclick = doPass;
        if (D.restartBtn) D.restartBtn.onclick = doRestart;
        if (D.copyBtn) D.copyBtn.onclick = copyLanUrl;
        if (D.renameOk) D.renameOk.onclick = applyRename;
        if (D.renameCancel) D.renameCancel.onclick = closeRename;
        if (D.renameLayer) {
            D.renameLayer.addEventListener('click', function (ev) {
                if (ev.target === D.renameLayer) closeRename();      // 点遮罩关闭
            });
        }
        if (D.renameInput) {
            D.renameInput.addEventListener('keydown', function (ev) {
                if (ev.key === 'Enter') applyRename();
                if (ev.key === 'Escape') closeRename();
            });
        }
        /* 牌桌上的「✎ 改名」/ 头像（状态条是动态重绘的，用事件委托） */
        if (D.meStatus) {
            D.meStatus.onclick = function (ev) {
                var el = ev.target;
                while (el && el !== D.meStatus && !(el.getAttribute && el.getAttribute('data-act'))) {
                    el = el.parentNode;
                }
                var act = (el && el.getAttribute) ? el.getAttribute('data-act') : null;
                if (act === 'rename') {
                    openRename();
                } else if (act === 'avatar') {
                    pickAvatar();
                }
            };
        }

        /* 头像 */
        if (D.avatarBtn) D.avatarBtn.onclick = pickAvatar;
        if (D.avatarClear) D.avatarClear.onclick = clearAvatar;
        if (D.avatarFile) D.avatarFile.onchange = onAvatarFile;

        /* 规则说明 */
        if (D.rulesBtn) D.rulesBtn.onclick = openRules;
        if (D.rulesClose) D.rulesClose.onclick = closeRules;
        if (D.rulesLayer) {
            D.rulesLayer.addEventListener('click', function (ev) {
                if (ev.target === D.rulesLayer) closeRules();
            });
        }

        /* 音效开关 */
        updateSfxBtn();
        if (D.sfxBtn) {
            D.sfxBtn.onclick = function () {
                if (!window.SFX) {
                    toast('这个浏览器不支持音效', 'warn', 2000);
                    return;
                }
                var on = !window.SFX.isEnabled();
                window.SFX.setEnabled(on);
                updateSfxBtn();
                toast(on ? '音效已开启' : '音效已关闭', 'info', 1500);
            };
        }

        /* 局内聊天 */
        if (D.chatToggle) D.chatToggle.onclick = toggleChat;
        if (D.chatSend) D.chatSend.onclick = sendChat;
        if (D.chatInput) {
            D.chatInput.addEventListener('keydown', function (ev) {
                if (ev.key === 'Enter') sendChat();
            });
        }

        /* 分享地址编辑（房主） */
        if (D.lanEditBtn) D.lanEditBtn.onclick = openLan;
        if (D.lanOk) D.lanOk.onclick = applyLan;
        if (D.lanAuto) {
            D.lanAuto.onclick = function () {
                if (send({ t: 'set-lan', url: '' })) toast('已恢复自动检测的地址', 'info', 1800);
                closeLan();
            };
        }
        if (D.lanCancel) D.lanCancel.onclick = closeLan;
        if (D.lanLayer) {
            D.lanLayer.addEventListener('click', function (ev) {
                if (ev.target === D.lanLayer) closeLan();
            });
        }
        if (D.lanInput) {
            D.lanInput.addEventListener('keydown', function (ev) {
                if (ev.key === 'Enter') applyLan();
                if (ev.key === 'Escape') closeLan();
            });
        }

        if (D.nameInput) {
            D.nameInput.addEventListener('change', function () {
                var v = (D.nameInput.value || '').trim();
                if (!v) v = DEFAULT_NAME;
                L.name = v.slice(0, 12);
                store(LS.name, L.name);
            });
            D.nameInput.addEventListener('keydown', function (ev) {
                if (ev.key === 'Enter') doJoin();
            });
        }

        /* 键盘快捷键（桌面） */
        document.addEventListener('keydown', function (ev) {
            if (ev.target && /INPUT|TEXTAREA/.test(ev.target.tagName || '')) return;
            if (ev.key === ' ' || ev.code === 'Space') {
                ev.preventDefault();
                doPlay();
            } else if (ev.key === 'd' || ev.key === 'D') {
                doDoubt();
            } else if (ev.key === 'p' || ev.key === 'P') {
                doPass();
            }
        });
    }

    function copyLanUrl() {
        var text = (D.lanUrl && D.lanUrl.value) ? D.lanUrl.value : '';
        if (!text) {
            toast('暂无局域网地址', 'warn', 2000);
            return;
        }
        var done = function () { toast('已复制：' + text, 'success', 2200); };
        var fail = function () {
            if (D.lanUrl && D.lanUrl.select) {
                D.lanUrl.select();
                try {
                    if (document.execCommand('copy')) { done(); return; }
                } catch (e) { /* ignore */ }
                toast('请长按地址框手动复制', 'warn', 3000);
            }
        };
        if (navigator.clipboard && navigator.clipboard.writeText) {
            navigator.clipboard.writeText(text).then(done, fail);
        } else {
            fail();
        }
    }

    function init() {
        initDom();
        if (!D.hand || !D.viewLobby) return;   // 页面结构缺失时不抛错

        if (D.nameInput) D.nameInput.value = L.name || DEFAULT_NAME;
        renderDeck();
        bindEvents();

        /* 未连接时先渲染一次占位，保证 banner / 状态可见 */
        if (D.conn) {
            D.conn.textContent = '连接中…';
            D.conn.className = 'conn';
        }
        if (D.banner) D.banner.hidden = true;
    }

    function boot() {
        /* 布局自检（uicheck.js）要靠这个知道当前是哪一屏；
           还没收到 state 之前先按"大厅"算，否则首屏自检会漏检。 */
        window.UI_CHECK_VIEW = 'lobby';
        init();
        if (typeof LiarWS !== 'function') {
            toast('浏览器不支持 WebSocket', 'error', 6000);
            return;
        }
        G.ctrl = new LiarWS({
            url: WS_URL,
            onOpen: onOpen,
            onMessage: applyMessage,
            onClose: onClose,
            onError: function () { /* 具体的重连提示由横幅承担 */ }
        });
        window.__liarWS = G.ctrl;
        G.ctrl.connect();
    }

    /* DOM 就绪后启动（脚本放在 body 末尾，仍做一次兜底） */
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', boot);
    } else {
        boot();
    }
})();
