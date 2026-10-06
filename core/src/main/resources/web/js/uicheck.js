/* ==========================================================================
   uicheck.js —— 布局自检

   真机适配最容易踩的坑：控件被挤出屏幕、或者被别的层盖住导致"点了没反应"。
   这里在页面加载后（以及旋转/改变尺寸后）检查每个关键控件：
     1) 是否完全落在可视区内
     2) 中心点用 elementFromPoint 能不能真的点到它
   结果打到 console，配合 adb logcat 就能批量回归各种机型参数。

   每个页面可以用 window.UI_CHECK_SELECTORS 指定要检查的元素：
       window.UI_CHECK_SELECTORS = [[".card button", "启动页按钮"], ...];
   ========================================================================== */
(function (global) {
    'use strict';

    var SELECTORS = global.UI_CHECK_SELECTORS || [];

    function rectText(r) {
        return Math.round(r.left) + ',' + Math.round(r.top) + ' ' +
            Math.round(r.width) + 'x' + Math.round(r.height);
    }

    /** 元素上的短文字，方便自动化脚本按文字找按钮。 */
    function labelOf(el) {
        var t = (el.textContent || '').replace(/\s+/g, ' ').trim();
        return t.slice(0, 14);
    }

    function visible(el) {
        if (!el || el.hasAttribute('hidden')) return false;
        if (el.offsetParent === null) {
            // position:fixed 的元素 offsetParent 也是 null，用尺寸再判一次
            var r0 = el.getBoundingClientRect();
            return r0.width >= 1 && r0.height >= 1;
        }
        return true;
    }

    var lastSig = '';

    function check(tag) {
        var vw = global.innerWidth;
        var vh = global.innerHeight;
        var problems = [];
        var checked = 0;
        var rectLines = [];

        /* 有全屏浮层（结算面板等）时，下面的元素被盖住是设计如此，跳过遮挡检查 */
        var overlayUp = false;
        try {
            var ov = document.querySelector('.over-panel:not([hidden]), .reveal-stage, .revolver-stage, .objection');
            overlayUp = !!ov;
        } catch (e) {
            overlayUp = false;
        }

        for (var i = 0; i < SELECTORS.length; i++) {
            var sel = SELECTORS[i][0];
            var name = SELECTORS[i][1];
            var nodes;
            try {
                nodes = document.querySelectorAll(sel);
            } catch (e) {
                continue;
            }
            for (var k = 0; k < nodes.length; k++) {
                var el = nodes[k];
                if (!visible(el)) continue;
                var r = el.getBoundingClientRect();
                if (r.width < 1 || r.height < 1) continue;
                checked++;

                /* 把控件位置也报出来（CSS 像素），自动化脚本据此点击 */
                rectLines.push('[rect] ' + name + '#' + k + ' "' + labelOf(el) + '" ' +
                    Math.round(r.left + r.width / 2) + ',' + Math.round(r.top + r.height / 2) +
                    ' ' + Math.round(r.width) + 'x' + Math.round(r.height));

                if (r.top < -1 || r.left < -1 || r.bottom > vh + 1 || r.right > vw + 1) {
                    problems.push('OUT[' + name + '#' + k + ' ' + rectText(r) + ']');
                    continue;
                }

                /* 结算面板之类的全屏浮层盖住牌桌是正常的，这时候不查"被遮挡" */
                if (overlayUp) {
                    continue;
                }

                var cx = Math.min(vw - 2, Math.max(2, r.left + r.width / 2));
                var cy = Math.min(vh - 2, Math.max(2, r.top + r.height / 2));
                var hit = document.elementFromPoint(cx, cy);
                if (hit && hit !== el && !el.contains(hit) && !hit.contains(el)) {
                    problems.push('COVERED[' + name + '#' + k + ' ' + rectText(r) + ' <- ' +
                        (hit.className || hit.tagName) + ']');
                }
            }
        }

        var line = '[layout]' + (tag ? '(' + tag + ')' : '') +
            ' page=' + (global.location ? global.location.pathname : '?') +
            ' view=' + (global.UI_CHECK_VIEW || '-') +
            ' vp=' + vw + 'x' + vh + ' checked=' + checked +
            (problems.length ? ' FAIL ' + problems.join(' | ') : ' OK');

        /*
         * 只在"控件位置或检查结果发生变化"时打印：
         * 渲染很频繁，每次都打会把 logcat 刷爆；但自动化脚本需要的是**最新的**坐标，
         * 所以这里不能只在 load/resize 时跑（layouts 会因为 canStart 之类的状态变化而改变）。
         */
        var sig = rectLines.join('|') + '||' + problems.join('|') + '||' + vw + 'x' + vh;
        if (sig === lastSig) {
            return problems.length === 0;
        }
        lastSig = sig;

        try {
            if (rectLines.length) {
                console.log(rectLines.join('\n'));
            }
            console.log(line);
        } catch (e) {
            /* 忽略 */
        }
        return problems.length === 0;
    }

    global.UI_CHECK = { check: check };

    function later(tag, ms) {
        clearTimeout(global.__uiCheckTimer);
        global.__uiCheckTimer = setTimeout(function () {
            check(tag);
        }, ms);
    }

    if (document.readyState === 'complete') {
        later('load', 700);
    } else {
        global.addEventListener('load', function () {
            later('load', 700);
        });
    }
    global.addEventListener('resize', function () {
        later('resize', 500);
    });
    global.addEventListener('orientationchange', function () {
        later('rotate', 700);
    });
})(window);
