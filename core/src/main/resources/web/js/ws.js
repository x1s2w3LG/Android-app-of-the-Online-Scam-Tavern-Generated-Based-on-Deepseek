/* ==========================================================================
   ws.js —— WebSocket 封装（自动重连 + 心跳 + 事件回调）
   协议：PROTOCOL.md 第 2、3、4 节
   - 地址必须动态推导，绝不硬编码主机/端口
   - 断线自动重连：指数退避 1s / 2s / 4s / 8s，上限 10s
   - 由 app.js 在 onOpen 时发送 {"t":"join",...}（重连时带 token）
   ========================================================================== */
(function (global) {
    'use strict';

    var LiarWS = function (opts) {
        opts = opts || {};
        this.url = opts.url ||
            ((location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ws');
        this.onOpen = opts.onOpen || function () {};        // 连接建立（含重连成功）
        this.onMessage = opts.onMessage || function () {};  // 收到 JSON 消息
        this.onClose = opts.onClose || function () {};      // 连接关闭
        this.onError = opts.onError || function () {};      // 连接错误
        this.onRaw = opts.onRaw || function () {};          // 收到非 JSON 文本（调试用）

        this.socket = null;
        this.reconnectAttempts = 0;   // 失败计数，用于计算退避
        this.reconnectTimer = null;
        this.pingTimer = null;
        this.closedByUser = false;
        this.opening = false;
    };

    /* 指数退避：1s、2s、4s、8s，上限 10s */
    LiarWS.prototype.backoffDelay = function (attempt) {
        var base = 1000 * Math.pow(2, Math.max(0, attempt));
        return Math.min(base, 10000);
    };

    LiarWS.prototype.isOpen = function () {
        return !!this.socket && this.socket.readyState === 1; // WebSocket.OPEN === 1
    };

    LiarWS.prototype.connect = function () {
        var self = this;
        if (this.closedByUser || this.opening) return;
        if (this.socket && (this.socket.readyState === 0 || this.socket.readyState === 1)) return;

        this.opening = true;
        var sock;
        try {
            sock = new WebSocket(this.url);
        } catch (e) {
            this.opening = false;
            this._scheduleReconnect();
            return;
        }
        this.socket = sock;

        sock.onopen = function () {
            self.opening = false;
            self.reconnectAttempts = 0;
            self._startPing();
            self.onOpen();
        };

        sock.onmessage = function (ev) {
            var data = ev && ev.data;
            if (typeof data !== 'string') return;
            var msg = null;
            try {
                msg = JSON.parse(data);
            } catch (e) {
                self.onRaw(data);
                return;
            }
            if (msg && typeof msg === 'object') self.onMessage(msg);
        };

        sock.onclose = function (ev) {
            self.opening = false;
            self._stopPing();
            self.onClose(ev);
            if (!self.closedByUser) self._scheduleReconnect();
        };

        sock.onerror = function (ev) {
            self.onError(ev);
        };
    };

    LiarWS.prototype._scheduleReconnect = function () {
        var self = this;
        if (this.closedByUser || this.reconnectTimer) return;
        var delay = this.backoffDelay(this.reconnectAttempts);
        this.reconnectAttempts += 1;
        this.reconnectTimer = setTimeout(function () {
            self.reconnectTimer = null;
            self.connect();
        }, delay);
    };

    /* 立即重连（例如页面重新可见时），不改变退避计数 */
    LiarWS.prototype.reconnectNow = function () {
        if (this.closedByUser) return;
        if (this.isOpen() || this.opening) return;
        if (this.reconnectTimer) {
            clearTimeout(this.reconnectTimer);
            this.reconnectTimer = null;
        }
        this.connect();
    };

    /* 心跳：每 20 秒发一次 {"t":"ping"}（服务端另每秒发 ping 帧保活） */
    LiarWS.prototype._startPing = function () {
        var self = this;
        this._stopPing();
        this.pingTimer = setInterval(function () {
            self.send({ t: 'ping' });
        }, 20000);
    };

    LiarWS.prototype._stopPing = function () {
        if (this.pingTimer) {
            clearInterval(this.pingTimer);
            this.pingTimer = null;
        }
    };

    /* 发送 JSON；连接未就绪返回 false，由调用方决定是否提示 */
    LiarWS.prototype.send = function (obj) {
        if (!this.isOpen()) return false;
        try {
            this.socket.send(JSON.stringify(obj));
            return true;
        } catch (e) {
            return false;
        }
    };

    LiarWS.prototype.close = function () {
        this.closedByUser = true;
        this._stopPing();
        if (this.reconnectTimer) {
            clearTimeout(this.reconnectTimer);
            this.reconnectTimer = null;
        }
        if (this.socket) {
            try { this.socket.close(); } catch (e) { /* ignore */ }
        }
    };

    global.LiarWS = LiarWS;

    /* ------------------------------------------------------------------
       页面生命周期：移动端 WebView 切后台可能被冻结，回到前台立即补齐连接
       ------------------------------------------------------------------ */
    document.addEventListener('visibilitychange', function () {
        if (!document.hidden && global.__liarWS) global.__liarWS.reconnectNow();
    });
    global.addEventListener('online', function () {
        if (global.__liarWS) global.__liarWS.reconnectNow();
    });
    global.addEventListener('focus', function () {
        if (global.__liarWS) global.__liarWS.reconnectNow();
    });
})(window);
