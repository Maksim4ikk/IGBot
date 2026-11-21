package com.maks.igbot.web

/**
 * JavaScript для підписки / відписки на сторінці профілю чужого акаунта: кнопки натискає сам скрипт,
 * як людина (так само працює лайк у WebScripts). Кнопки шукаємо за підписами зі словників нижче;
 * якщо Instagram покаже незнайомий підпис, він потрапить у `labels=` / `dialog=` журналу «Події» —
 * тоді його досить додати у відповідний масив.
 */
internal object FriendScripts {
    /** Спільна частина: словники підписів і пошук головної кнопки в шапці профілю. */
    private val COMMON = """
        var FOLLOW = ['follow','follow back','підписатися','підписатись','стежити','підписатися у відповідь','подписаться','подписаться в ответ','seguir','seguir também','seguir también','suivre','folgen','zurückfolgen','segui','takip et','obserwuj','sledovat'];
        var FOLLOWING = ['following','підписки','ви підписані','ви стежите','стежу','підписано','подписки','вы подписаны','siguiendo','suivi(e)','suivi','abonné(e)','abonné','gefolgt','abonniert','seguindo','takip ediliyor','obserwujesz','sledujete'];
        var REQUESTED = ['requested','запит надіслано','запрос отправлен','solicitado','demandé','angefragt','richiesta inviata','pedido enviado','istek gönderildi','wysłano prośbę'];
        var UNFOLLOW = ['unfollow','відписатися','відписатись','скасувати підписку','перестати стежити','отписаться','dejar de seguir','ne plus suivre','entfolgen','smetti di seguire','deixar de seguir','takibi bırak','przestań obserwować','přestat sledovat'];
        var BLOCKED = /try again later|action blocked|we restrict certain activity|temporarily blocked|спробуйте пізніше|повторіть спробу пізніше|дію заблоковано|попробуйте позже|действие заблокировано/;
        var MISSING = /this page isn.t available|page isn.t available|link you followed may be broken|сторінка недоступна|страница недоступна|ссылка, по которой вы перешли/;
        function norm(s) { return (s || '').replace(/\s+/g, ' ').trim().toLowerCase(); }
        function visible(e) { return !!(e.offsetWidth || e.offsetHeight || (e.getClientRects && e.getClientRects().length)); }
        function classify(t) {
          if (FOLLOWING.indexOf(t) >= 0) return 'following';
          if (REQUESTED.indexOf(t) >= 0) return 'requested';
          if (FOLLOW.indexOf(t) >= 0) return 'follow';
          return '';
        }
        // Головна кнопка шапки профілю: перша за порядком у DOM (кнопки «Підписатись» у блоці рекомендацій нижче).
        function findMain() {
          var root = document.querySelector('main') || document.body;
          var labels = [], hit = null, i, t, c;
          var btns = root.querySelectorAll('button, [role="button"]');
          for (i = 0; i < btns.length; i++) {
            if (btns[i].closest('[role="dialog"]')) continue;
            t = norm(btns[i].innerText);
            if (!t) continue;
            if (labels.length < 12) labels.push(t.slice(0, 24));
            c = classify(t);
            if (c && !hit) hit = {el: btns[i], state: c};
          }
          if (!hit) {
            // резерв: напис без role=button (кінцевий елемент поза посиланнями та списками)
            var leaves = root.querySelectorAll('div, span');
            for (i = 0; i < leaves.length && !hit; i++) {
              var e = leaves[i];
              if (e.children.length || e.closest('[role="dialog"], a, li')) continue;
              c = classify(norm(e.innerText));
              if (c) hit = {el: e.closest('button, [role="button"]') || e, state: c};
            }
          }
          return {hit: hit, labels: labels};
        }
    """.trimIndent()

    /** Стан сторінки профілю: login | missing | blocked | state = follow / following / requested / none. */
    val STATE_JS = """
        (function () {
          $COMMON
          var txt = norm(document.body ? document.body.innerText : '');
          var dlg = document.querySelector('[role="dialog"]');
          var res = {login: false, missing: false, blocked: false, state: 'none', labels: '',
                     dialog: dlg ? norm(dlg.innerText).slice(0, 120) : '',
                     title: (document.title || '').slice(0, 60), path: location.pathname.slice(0, 40),
                     text: txt.slice(0, 100)};
          if (location.pathname.indexOf('/accounts/login') === 0 || document.querySelector('input[name="username"]')) {
            res.login = true;
            return JSON.stringify(res);
          }
          var f = findMain();
          res.labels = f.labels.join('|');
          if (f.hit) res.state = f.hit.state;
          else if (MISSING.test(txt.slice(0, 600))) res.missing = true;
          res.blocked = BLOCKED.test(txt);
          return JSON.stringify(res);
        })();
    """.trimIndent()

    /** Натискає головну кнопку шапки профілю («Підписатись» / «Підписки» / «Запит надіслано»). */
    val CLICK_MAIN_JS = """
        (function () {
          $COMMON
          var f = findMain();
          if (!f.hit) return 'none';
          f.hit.el.click();
          return 'clicked';
        })();
    """.trimIndent()

    /**
     * Натискає «Відписатись» у меню чи діалозі підтвердження, що відкрилися після кліку по кнопці «Підписки».
     * Беремо останній видимий збіг: діалог підтвердження малюється поверх меню.
     */
    val CLICK_UNFOLLOW_JS = """
        (function () {
          $COMMON
          var all = document.querySelectorAll('button, [role="button"], [role="menuitem"], div[tabindex], span');
          var hits = [];
          for (var i = 0; i < all.length; i++) {
            if (UNFOLLOW.indexOf(norm(all[i].innerText)) >= 0) hits.push(all[i]);
          }
          if (!hits.length) return 'none';
          var vis = hits.filter(visible);
          var pool = vis.length ? vis : hits;
          var el = pool[pool.length - 1];
          (el.closest('button, [role="button"], [role="menuitem"]') || el).click();
          return 'clicked';
        })();
    """.trimIndent()
}
