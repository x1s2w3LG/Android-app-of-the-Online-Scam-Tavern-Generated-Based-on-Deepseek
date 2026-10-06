/* ==================================================================
   音效引擎：全部用 Web Audio 现场合成，不加载任何音频文件
   （离线可用、不增加体积；安卓 WebView 一样能用）

   对外接口：
     SFX.play('card' | 'spin' | 'shot' | 'blank', delayMs)
     SFX.isEnabled() / SFX.setEnabled(bool) / SFX.unlock()
   ================================================================== */
(function (global) {
    'use strict';

    var LS_KEY = 'liarbar.sfx';
    var ctx = null;
    var master = null;
    var enabled = true;

    try {
        if (global.localStorage && localStorage.getItem(LS_KEY) === '0') enabled = false;
    } catch (e) { /* 隐私模式忽略 */ }

    function ensureCtx() {
        if (ctx) return ctx;
        var AC = global.AudioContext || global.webkitAudioContext;
        if (!AC) return null;
        try {
            ctx = new AC();
            master = ctx.createGain();
            master.gain.value = 0.55;
            master.connect(ctx.destination);
        } catch (e) {
            ctx = null;
        }
        return ctx;
    }

    /** 浏览器/WebView 的自动播放限制：必须由用户手势解锁。 */
    function unlock() {
        var c = ensureCtx();
        if (c && c.state === 'suspended' && c.resume) {
            try { c.resume(); } catch (e) { /* 忽略 */ }
        }
    }

    function noiseBuffer(c, seconds) {
        var len = Math.max(1, Math.floor(c.sampleRate * seconds));
        var buf = c.createBuffer(1, len, c.sampleRate);
        var d = buf.getChannelData(0);
        for (var i = 0; i < len; i++) {
            d[i] = Math.random() * 2 - 1;
        }
        return buf;
    }

    function envGain(c, at, peak, attack, release) {
        var g = c.createGain();
        g.gain.setValueAtTime(0.0001, at);
        g.gain.exponentialRampToValueAtTime(peak, at + attack);
        g.gain.exponentialRampToValueAtTime(0.0001, at + attack + release);
        return g;
    }

    /* ---------- 出牌：一声干脆的"唰" ---------- */
    function playCard() {
        var c = ensureCtx();
        if (!c) return;
        var t = c.currentTime;
        var src = c.createBufferSource();
        src.buffer = noiseBuffer(c, 0.18);
        var bp = c.createBiquadFilter();
        bp.type = 'bandpass';
        bp.Q.value = 1.2;
        bp.frequency.setValueAtTime(2800, t);
        bp.frequency.exponentialRampToValueAtTime(900, t + 0.15);
        var g = envGain(c, t, 0.32, 0.012, 0.13);
        src.connect(bp);
        bp.connect(g);
        g.connect(master);
        src.start(t);
        src.stop(t + 0.19);
    }

    /* ---------- 左轮转动：6 声棘轮咔哒 + 低频旋转声 ---------- */
    function playSpin() {
        var c = ensureCtx();
        if (!c) return;
        var t = c.currentTime;

        for (var i = 0; i < 6; i++) {
            (function (idx) {
                var at = t + idx * 0.085;
                var src = c.createBufferSource();
                src.buffer = noiseBuffer(c, 0.05);
                var hp = c.createBiquadFilter();
                hp.type = 'highpass';
                hp.frequency.value = 2000;
                var g = envGain(c, at, 0.24, 0.004, 0.04);
                src.connect(hp);
                hp.connect(g);
                g.connect(master);
                src.start(at);
                src.stop(at + 0.06);
            })(i);
        }

        var osc = c.createOscillator();
        osc.type = 'sawtooth';
        osc.frequency.setValueAtTime(72, t);
        osc.frequency.linearRampToValueAtTime(155, t + 0.5);
        var lp = c.createBiquadFilter();
        lp.type = 'lowpass';
        lp.frequency.value = 420;
        var og = envGain(c, t, 0.1, 0.06, 0.5);
        osc.connect(lp);
        lp.connect(og);
        og.connect(master);
        osc.start(t);
        osc.stop(t + 0.6);
    }

    /* ---------- 开枪命中：枪声（噪声爆 + 低频冲击） ---------- */
    function playShot() {
        var c = ensureCtx();
        if (!c) return;
        var t = c.currentTime;

        var src = c.createBufferSource();
        src.buffer = noiseBuffer(c, 0.55);
        var lp = c.createBiquadFilter();
        lp.type = 'lowpass';
        lp.frequency.setValueAtTime(6500, t);
        lp.frequency.exponentialRampToValueAtTime(280, t + 0.44);
        var g = envGain(c, t, 0.95, 0.004, 0.46);
        src.connect(lp);
        lp.connect(g);
        g.connect(master);
        src.start(t);
        src.stop(t + 0.56);

        var osc = c.createOscillator();
        osc.type = 'sine';
        osc.frequency.setValueAtTime(150, t);
        osc.frequency.exponentialRampToValueAtTime(38, t + 0.3);
        var og = envGain(c, t, 0.85, 0.006, 0.34);
        osc.connect(og);
        og.connect(master);
        osc.start(t);
        osc.stop(t + 0.4);
    }

    /* ---------- 空枪：一声干巴巴的"咔哒" ---------- */
    function playBlank() {
        var c = ensureCtx();
        if (!c) return;
        var t = c.currentTime;
        var src = c.createBufferSource();
        src.buffer = noiseBuffer(c, 0.07);
        var hp = c.createBiquadFilter();
        hp.type = 'highpass';
        hp.frequency.value = 1300;
        var g = envGain(c, t, 0.4, 0.003, 0.055);
        src.connect(hp);
        hp.connect(g);
        g.connect(master);
        src.start(t);
        src.stop(t + 0.08);
    }

    /* ---------- 拍桌子 ---------- */
    function playSlam() {
        var c = ensureCtx();
        if (!c) return;
        var t = c.currentTime;
        var src = c.createBufferSource();
        src.buffer = noiseBuffer(c, 0.25);
        var lp = c.createBiquadFilter();
        lp.type = 'lowpass';
        lp.frequency.setValueAtTime(1800, t);
        lp.frequency.exponentialRampToValueAtTime(160, t + 0.2);
        var g = envGain(c, t, 0.6, 0.003, 0.22);
        src.connect(lp);
        lp.connect(g);
        g.connect(master);
        src.start(t);
        src.stop(t + 0.26);

        var osc = c.createOscillator();
        osc.type = 'sine';
        osc.frequency.setValueAtTime(120, t);
        osc.frequency.exponentialRampToValueAtTime(45, t + 0.18);
        var og = envGain(c, t, 0.5, 0.004, 0.2);
        osc.connect(og);
        og.connect(master);
        osc.start(t);
        osc.stop(t + 0.24);
    }

    /* ---------- 「異議あり！」的合成兜底 ----------
       没有真人录音、系统也没有日文语音时用这个：用共振峰合成四个音节
       （i-gi-a-ri），听起来是"吼了一嗓子"，配合同步弹出的异议图足够有气势。
       想换成真人语音：把 mp3 放到 web/audio/objection.mp3 即可（会自动优先使用）。 */
    function playShout() {
        var c = ensureCtx();
        if (!c) return;
        var t = c.currentTime;
        playSlam();

        var syl = [
            { at: 0.02, dur: 0.15, f0: 196, f1: 340, f2: 2350 },   // い
            { at: 0.19, dur: 0.17, f0: 184, f1: 300, f2: 2150 },   // ぎ
            { at: 0.38, dur: 0.22, f0: 172, f1: 780, f2: 1250 },   // あ
            { at: 0.62, dur: 0.30, f0: 158, f1: 420, f2: 1650 }    // り
        ];

        for (var i = 0; i < syl.length; i++) {
            (function (s) {
                var at = t + s.at;
                var osc = c.createOscillator();
                osc.type = 'sawtooth';
                osc.frequency.setValueAtTime(s.f0, at);
                osc.frequency.linearRampToValueAtTime(s.f0 * 0.9, at + s.dur);
                var vib = c.createOscillator();
                vib.type = 'sine';
                vib.frequency.value = 22;
                var vibGain = c.createGain();
                vibGain.gain.value = 5;
                vib.connect(vibGain);
                vibGain.connect(osc.frequency);

                var f1 = c.createBiquadFilter();
                f1.type = 'bandpass';
                f1.frequency.value = s.f1;
                f1.Q.value = 6;
                var f2 = c.createBiquadFilter();
                f2.type = 'bandpass';
                f2.frequency.value = s.f2;
                f2.Q.value = 9;
                var mix = c.createGain();
                mix.gain.value = 0.6;
                osc.connect(f1);
                osc.connect(f2);
                f1.connect(mix);
                f2.connect(mix);

                var g = c.createGain();
                g.gain.setValueAtTime(0.0001, at);
                g.gain.exponentialRampToValueAtTime(0.55, at + 0.025);
                g.gain.exponentialRampToValueAtTime(0.0001, at + s.dur);
                mix.connect(g);
                g.connect(master);

                osc.start(at);
                osc.stop(at + s.dur + 0.03);
                vib.start(at);
                vib.stop(at + s.dur + 0.03);
            })(syl[i]);
        }
    }

    function dispatch(name) {
        try {
            if (name === 'card') playCard();
            else if (name === 'spin') playSpin();
            else if (name === 'shot') playShot();
            else if (name === 'blank') playBlank();
            else if (name === 'slam') playSlam();
            else if (name === 'shout') playShout();
        } catch (e) {
            /* 出声失败绝不能影响游戏 */
        }
    }

    global.SFX = {
        play: function (name, delayMs) {
            if (!enabled) return;
            unlock();
            if (delayMs && delayMs > 0) {
                setTimeout(function () { dispatch(name); }, delayMs);
            } else {
                dispatch(name);
            }
        },
        isEnabled: function () { return enabled; },
        setEnabled: function (on) {
            enabled = !!on;
            try { localStorage.setItem(LS_KEY, enabled ? '1' : '0'); } catch (e) { /* 忽略 */ }
            if (enabled) {
                unlock();
                dispatch('card');       // 打开时给个反馈音
            }
        },
        unlock: unlock
    };

    /* 第一次交互就解锁音频上下文 */
    var once = function () {
        unlock();
        document.removeEventListener('touchstart', once, true);
        document.removeEventListener('mousedown', once, true);
        document.removeEventListener('keydown', once, true);
    };
    document.addEventListener('touchstart', once, true);
    document.addEventListener('mousedown', once, true);
    document.addEventListener('keydown', once, true);
})(window);
