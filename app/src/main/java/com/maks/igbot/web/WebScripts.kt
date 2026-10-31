package com.maks.igbot.web

/**
 * JavaScript, який виконується на сторінках instagram.com усередині WebView.
 * Скрипти повертають JSON-рядок; лише читають DOM, нічого не змінюють (крім CLICK_JS).
 * Селектори не залежать від мови інтерфейсу: де можливо — структура сторінки, а не підписи.
 */
internal object WebScripts {
    /** Спільна частина: пошук іконки лайка саме в пості (а не логотипу чи меню навігації). */
    val FIND_JS = """
        var LIKE = ['like','подобається','нравится','мне нравится','me gusta','gefällt mir','j’aime',"j'aime",'mi piace','curtir','beğen','lubię to','to se mi líbí','tetszik'];
        var UNLIKE = ['unlike','не подобається','не нравится','ya no me gusta','gefällt mir nicht mehr','je n’aime plus',"je n'aime plus",'non mi piace più','descurtir','beğenmekten vazgeç','przestań lubić','už se mi nelíbí','mégsem tetszik'];
        function norm(s) { return (s || '').toLowerCase().trim(); }
        function findIcon() {
          var scope = document.querySelector('article') || document.querySelector('main') || document.body;
          var all = Array.prototype.slice.call(scope.querySelectorAll('svg[aria-label]'));
          var svgs = all.filter(function (s) {
            return !s.closest('nav, header') && norm(s.getAttribute('aria-label')) !== 'instagram';
          });
          var labels = svgs.map(function (s) { return s.getAttribute('aria-label'); });
          var i, l;
          for (i = 0; i < svgs.length; i++) {
            l = norm(svgs[i].getAttribute('aria-label'));
            if (LIKE.indexOf(l) >= 0 || UNLIKE.indexOf(l) >= 0) return {icon: svgs[i], labels: labels};
          }
          // структурний резерв: перша іконка в блоці дій (section, де є щонайменше 3 іконки)
          for (i = 0; i < svgs.length; i++) {
            var sec = svgs[i].closest('section');
            if (!sec) continue;
            var inSec = svgs.filter(function (s) { return sec.contains(s); });
            if (inSec.length >= 3) return {icon: inSec[0], labels: labels};
          }
          return {icon: null, labels: labels};
        }
    """.trimIndent()

    /** Стан сторінки поста: чи є панель дій, чи вже лайкнуто, чи потрібен вхід. */
    val STATE_JS = """
        (function () {
          $FIND_JS
          var txt = ((document.body && document.body.innerText) || '').replace(/\s+/g, ' ').slice(0, 100);
          var res = {login: false, found: false, liked: false, label: '', labels: '',
                     title: (document.title || '').slice(0, 60), path: location.pathname.slice(0, 40), text: txt};
          if (location.pathname.indexOf('/accounts/login') === 0 || document.querySelector('input[name="username"]')) {
            res.login = true;
            return JSON.stringify(res);
          }
          function rgb(c) {
            if (!c) return null;
            var m = /rgba?\((\d+),\s*(\d+),\s*(\d+)/.exec(c);
            if (m) return [+m[1], +m[2], +m[3]];
            m = /^#([0-9a-f]{6})${'$'}/i.exec(String(c).trim());
            if (m) return [parseInt(m[1].slice(0, 2), 16), parseInt(m[1].slice(2, 4), 16), parseInt(m[1].slice(4, 6), 16)];
            return null;
          }
          function red(c) { var v = rgb(c); return !!v && v[0] > 200 && v[1] < 110 && v[2] < 120; }
          var f = findIcon();
          res.labels = f.labels.slice(0, 10).join('|');
          if (!f.icon) return JSON.stringify(res);
          res.found = true;
          res.label = f.icon.getAttribute('aria-label') || '';
          var cs = getComputedStyle(f.icon);
          res.liked = UNLIKE.indexOf(norm(res.label)) >= 0 || red(f.icon.getAttribute('fill')) || red(cs.fill) || red(cs.color);
          return JSON.stringify(res);
        })();
    """.trimIndent()

    /** Натискання кнопки лайка. */
    val CLICK_JS = """
        (function () {
          $FIND_JS
          var f = findIcon();
          if (!f.icon) return 'none';
          var b = f.icon.closest('[role="button"], button') || f.icon.parentElement;
          b.click();
          return 'clicked';
        })();
    """.trimIndent()

    /** Сторінка профілю: коди постів або причина їх відсутності (приватний, не знайдено, немає постів). */
    val PROFILE_JS = """
        (function () {
          var txt = ((document.body && document.body.innerText) || '').replace(/\s+/g, ' ');
          var low = txt.toLowerCase();
          var res = {status: 'wait', codes: '', title: (document.title || '').slice(0, 60),
                     path: location.pathname.slice(0, 40), text: txt.slice(0, 100)};
          if (location.pathname.indexOf('/accounts/login') === 0 || document.querySelector('input[name="username"]')) {
            res.status = 'login';
            return JSON.stringify(res);
          }
          var root = document.querySelector('main') || document;
          var as = root.querySelectorAll('a[href*="/p/"], a[href*="/reel/"]');
          var codes = [];
          for (var i = 0; i < as.length && codes.length < 12; i++) {
            var m = /\/(?:p|reel)\/([A-Za-z0-9_-]{8,15})\//.exec(as[i].getAttribute('href') || '');
            if (m && codes.indexOf(m[1]) < 0) codes.push(m[1]);
          }
          if (codes.length) {
            res.status = 'ok';
            res.codes = codes.join(',');
            return JSON.stringify(res);
          }
          function has(list) {
            for (var j = 0; j < list.length; j++) { if (low.indexOf(list[j]) >= 0) return true; }
            return false;
          }
          if (has(['this account is private', 'цей акаунт приватний', 'це приватний акаунт', 'это закрытый аккаунт',
                   'dieses konto ist privat', 'esta cuenta es privada', 'ce compte est privé',
                   'questo account è privato', 'esta conta é privada', 'bu hesap gizli', 'to konto jest prywatne'])) {
            res.status = 'private';
          } else if (has(["isn't available", 'isn’t available', 'page not found', 'сторінку не знайдено', 'сторінка недоступна',
                          'страница не найдена', 'страница недоступна', 'seite nicht gefunden', 'página no disponible',
                          'page introuvable', 'pagina non trovata', 'página não encontrada', 'sayfa bulunamadı',
                          'nie znaleziono strony'])) {
            res.status = 'not_found';
          } else if (has(['no posts yet', 'ще немає дописів', 'ще немає публікацій', 'немає публікацій',
                          'пока нет публикаций', 'публикаций пока нет', 'noch keine beiträge',
                          'aún no hay publicaciones', 'pas encore de publications', 'ancora nessun post',
                          'ainda não há publicações', 'henüz gönderi yok', 'brak postów'])) {
            res.status = 'no_posts';
          }
          if (res.status === 'wait') {
            // постів немає, а чому — невідомо: віддаємо діагностику (що під заголовком профілю і чи є дані в коді сторінки)
            var mainEl = document.querySelector('main') || document.body;
            var mt = ((mainEl && mainEl.innerText) || '').replace(/\s+/g, ' ');
            res.tail = mt.slice(Math.max(0, mt.length - 140));
            try {
              var h = document.documentElement.innerHTML;
              var tk = h.match(/"taken_at":\d{9,11}/g);
              res.diag = 'html=' + h.length + ' taken_at=' + (tk ? tk.length : 0);
            } catch (e) {
              res.diag = 'html=?';
            }
          }
          return JSON.stringify(res);
        })();
    """.trimIndent()

    /** ID профілю: числа поруч з "username":"<нік>" та "profile_id" у коді сторінки (__UNAME__ підставляє Kotlin). */
    val PROFILE_ID_JS = """
        (function () {
          var txt = ((document.body && document.body.innerText) || '').replace(/\s+/g, ' ');
          var low = txt.toLowerCase();
          var res = {status: 'wait', id: '', title: (document.title || '').slice(0, 60),
                     path: location.pathname.slice(0, 40), text: txt.slice(0, 100)};
          if (location.pathname.indexOf('/accounts/login') === 0 || document.querySelector('input[name="username"]')) {
            res.status = 'login';
            return JSON.stringify(res);
          }
          var uname = __UNAME__;
          var html = document.documentElement.innerHTML;
          var counts = {};
          function add(v, w) { if (v && v.length >= 5 && v.length <= 14) counts[v] = (counts[v] || 0) + w; }
          var re = new RegExp('"username":"' + uname.replace(/[.]/g, '\\.') + '"', 'gi');
          var m, n = 0;
          while ((m = re.exec(html)) && n < 12) {
            n++;
            var win = html.slice(Math.max(0, m.index - 400), m.index + 400);
            var r2 = /"(?:pk|id|user_id|profile_id|owner_id)":"(\d{5,14})"/g, m2;
            while ((m2 = r2.exec(win))) add(m2[1], 1);
          }
          var r3 = /"profile_id":"(\d{5,14})"/g, m3;
          while ((m3 = r3.exec(html))) add(m3[1], 2);
          var best = '', bestN = 0;
          for (var k in counts) { if (counts[k] > bestN) { bestN = counts[k]; best = k; } }
          if (best) {
            res.status = 'ok';
            res.id = best;
            return JSON.stringify(res);
          }
          function has(list) {
            for (var j = 0; j < list.length; j++) { if (low.indexOf(list[j]) >= 0) return true; }
            return false;
          }
          if (has(["isn't available", 'isn’t available', 'page not found', 'сторінку не знайдено', 'сторінка недоступна',
                   'страница не найдена', 'страница недоступна', 'seite nicht gefunden', 'página no disponible',
                   'page introuvable', 'pagina non trovata', 'página não encontrada', 'sayfa bulunamadı',
                   'nie znaleziono strony'])) {
            res.status = 'not_found';
          }
          return JSON.stringify(res);
        })();
    """.trimIndent()

    /** Сторінка хештегу: збираємо посилання на пости. */
    val HASHTAG_JS = """
        (function () {
          var txt = ((document.body && document.body.innerText) || '').replace(/\s+/g, ' ');
          var res = {status: 'wait', codes: '', count: 0, title: (document.title || '').slice(0, 60),
                     path: location.pathname.slice(0, 50), text: txt.slice(0, 100)};
          if (location.pathname.indexOf('/accounts/login') === 0 || document.querySelector('input[name="username"]')) {
            res.status = 'login';
            return JSON.stringify(res);
          }
          var as = document.querySelectorAll('a[href*="/p/"], a[href*="/reel/"]');
          var codes = [];
          for (var i = 0; i < as.length; i++) {
            var m = /\/(?:p|reel)\/([A-Za-z0-9_-]{8,15})\//.exec(as[i].getAttribute('href') || '');
            if (m && codes.indexOf(m[1]) < 0) codes.push(m[1]);
          }
          res.count = codes.length;
          res.codes = codes.join(',');
          if (codes.length) res.status = 'ok';
          return JSON.stringify(res);
        })();
    """.trimIndent()

    /** Сторінка поста: автор (нікнейм) та числові ID поруч із його нікнеймом у коді сторінки. */
    val POST_AUTHOR_JS = """
        (function () {
          var txt = ((document.body && document.body.innerText) || '').replace(/\s+/g, ' ');
          var res = {status: 'wait', username: '', ids: '', title: (document.title || '').slice(0, 60),
                     path: location.pathname.slice(0, 40), text: txt.slice(0, 100)};
          if (location.pathname.indexOf('/accounts/login') === 0 || document.querySelector('input[name="username"]')) {
            res.status = 'login';
            return JSON.stringify(res);
          }
          var SKIP = ['explore','reels','reel','direct','accounts','p','stories','notifications','about','legal',
                      'privacy','terms','directory','developers','api','web','static','emails','challenge','session',
                      'login','nametag','settings','create','ads','blog','help','tv','lite','topics','locations',
                      'tags','archive','saved','activity','threads','meta','download','oauth'];
          var scope = document.querySelector('article');
          if (!scope) return JSON.stringify(res);
          var found = '';
          var groups = [scope.querySelectorAll('header a[href]'), scope.querySelectorAll('a[href]')];
          for (var g = 0; g < groups.length && !found; g++) {
            for (var i = 0; i < groups[g].length && !found; i++) {
              var m = /^\/([A-Za-z0-9._]{1,30})\/$/.exec(groups[g][i].getAttribute('href') || '');
              if (m && SKIP.indexOf(m[1].toLowerCase()) < 0) found = m[1];
            }
          }
          if (!found) return JSON.stringify(res);
          res.username = found;
          res.status = 'ok';
          var html = document.documentElement.innerHTML;
          var re = new RegExp('"username":"' + found.replace(/[.]/g, '\\.') + '"', 'gi');
          var ids = [], mm, n = 0;
          while ((mm = re.exec(html)) && n < 8) {
            n++;
            var win = html.slice(Math.max(0, mm.index - 400), mm.index + 400);
            var r2 = /"(?:pk|id|user_id|profile_id|owner_id)":"(\d{5,14})"/g, m2;
            while ((m2 = r2.exec(win))) { if (ids.indexOf(m2[1]) < 0) ids.push(m2[1]); }
          }
          res.ids = ids.join(',');
          return JSON.stringify(res);
        })();
    """.trimIndent()

    /** Екран «Вподобання» під постом: нікнейми людей зі списку (лише з діалогу, якщо він відкритий). */
    val LIKERS_JS = """
        (function () {
          var res = {status: 'wait', names: '', count: 0, title: (document.title || '').slice(0, 60),
                     path: location.pathname.slice(0, 50), text: ''};
          if (location.pathname.indexOf('/accounts/login') === 0 || document.querySelector('input[name="username"]')) {
            res.status = 'login';
            return JSON.stringify(res);
          }
          var SKIP = ['explore','reels','reel','direct','accounts','p','stories','notifications','about','legal',
                      'privacy','terms','directory','developers','api','web','static','emails','challenge','session',
                      'login','nametag','settings','create','ads','blog','help','tv','lite','topics','locations',
                      'tags','archive','saved','activity','threads','meta','download','oauth'];
          var scope = document.querySelector('[role="dialog"]') || document.body;
          var as = scope.querySelectorAll('a[href]');
          var seen = {}, names = [];
          for (var i = 0; i < as.length; i++) {
            var a = as[i];
            var m = /^\/([A-Za-z0-9._]{1,30})\/$/.exec(a.getAttribute('href') || '');
            if (!m) continue;
            var u = m[1], key = u.toLowerCase();
            if (SKIP.indexOf(key) >= 0 || seen[key]) continue;
            if (a.closest('nav, header')) continue;
            seen[key] = 1;
            names.push(u);
          }
          res.count = names.length;
          res.names = names.join(',');
          if (names.length) {
            res.status = 'ok';
          } else {
            res.text = ((document.body && document.body.innerText) || '').replace(/\s+/g, ' ').slice(0, 100);
          }
          return JSON.stringify(res);
        })();
    """.trimIndent()

    /**
     * Прокрутка списку (контейнер із власною прокруткою або вікно) — підвантажує наступну порцію людей.
     * Якщо внизу є кнопка «завантажити більше / переглянути відповіді» (за підписом), натискає її.
     * Кнопки «подобається» та інші кнопки з іконкою НЕ чіпає.
     */
    val SCROLL_JS = """
        (function () {
          var scope = document.querySelector('[role="dialog"]') || document.body;
          var MORE = /load more|more comments|view more|view replies|більше коментар|додатков\w* коментар|завантажити ще|переглянути відповіді|больше комментар|ещё комментар|еще комментар|загрузить ещё|więcej komentarz|mehr kommentare|más comentarios|plus de commentaires|altri commenti|mais comentários/i;
          var clicked = 0;
          var btns = scope.querySelectorAll('button, [role="button"]');
          for (var j = 0; j < btns.length && clicked < 3; j++) {
            var el = btns[j];
            var svg = el.querySelector('svg[aria-label]');
            var label = (svg && svg.getAttribute('aria-label')) || el.getAttribute('aria-label') || '';
            var txt = (el.innerText || '').trim();
            if ((label && MORE.test(label)) || (txt && txt.length < 40 && MORE.test(txt))) {
              el.click();
              clicked++;
            }
          }
          var best = null, bestH = 0, els = scope.querySelectorAll('div');
          for (var i = 0; i < els.length; i++) {
            var e = els[i];
            if (e.scrollHeight <= e.clientHeight + 20) continue;
            var oy = getComputedStyle(e).overflowY;
            if ((oy === 'auto' || oy === 'scroll') && e.scrollHeight > bestH) { best = e; bestH = e.scrollHeight; }
          }
          if (best) best.scrollTop = best.scrollHeight;
          window.scrollTo(0, document.documentElement.scrollHeight || document.body.scrollHeight);
          return (best ? 'container' : 'window') + (clicked ? '+more' + clicked : '');
        })();
    """.trimIndent()

    /** Діагностика: підписи останніх кнопок на сторінці (щоб зрозуміти, чим «підвантажується» список). */
    val TAIL_JS = """
        (function () {
          var out = [];
          var els = document.querySelectorAll('button, [role="button"]');
          for (var i = Math.max(0, els.length - 10); i < els.length; i++) {
            var e = els[i], svg = e.querySelector('svg[aria-label]');
            var t = (svg && svg.getAttribute('aria-label')) || e.getAttribute('aria-label') || (e.innerText || '').trim();
            out.push(String(t).slice(0, 30));
          }
          return JSON.stringify({tail: out.join('|')});
        })();
    """.trimIndent()

    /**
     * Сторінка коментарів поста: нікнейми авторів коментарів.
     * Автор коментаря — перше посилання на профіль у блоці коментаря (згадки @нік у тексті йдуть пізніше).
     * Блок коментаря знаходимо від позначки часу <time>: піднімаємось до найближчого елемента з посиланням на профіль.
     */
    val COMMENTERS_JS = """
        (function () {
          var res = {status: 'wait', names: '', count: 0, title: (document.title || '').slice(0, 60),
                     path: location.pathname.slice(0, 50), text: ''};
          if (location.pathname.indexOf('/accounts/login') === 0 || document.querySelector('input[name="username"]')) {
            res.status = 'login';
            return JSON.stringify(res);
          }
          var SKIP = ['explore','reels','reel','direct','accounts','p','stories','notifications','about','legal',
                      'privacy','terms','directory','developers','api','web','static','emails','challenge','session',
                      'login','nametag','settings','create','ads','blog','help','tv','lite','topics','locations',
                      'tags','archive','saved','activity','threads','meta','download','oauth'];
          function firstUser(root) {
            var as = root.querySelectorAll('a[href]');
            for (var i = 0; i < as.length; i++) {
              if (as[i].closest('nav, header')) continue;
              var m = /^\/([A-Za-z0-9._]{1,30})\/$/.exec(as[i].getAttribute('href') || '');
              if (m && SKIP.indexOf(m[1].toLowerCase()) < 0) return m[1];
            }
            return '';
          }
          var seen = {}, names = [];
          function add(u) {
            var key = u.toLowerCase();
            if (u && !seen[key]) { seen[key] = 1; names.push(u); }
          }
          var times = document.querySelectorAll('time');
          for (var t = 0; t < times.length; t++) {
            var node = times[t].parentElement, u = '';
            for (var depth = 0; node && depth < 8 && !u; depth++) {
              u = firstUser(node);
              node = node.parentElement;
            }
            add(u);
          }
          if (!names.length) {  // резерв: коментарі як елементи списку
            var items = document.querySelectorAll('ul li, [role="listitem"]');
            for (var k = 0; k < items.length; k++) add(firstUser(items[k]));
          }
          res.count = names.length;
          res.names = names.join(',');
          if (names.length) {
            res.status = 'ok';
          } else {
            res.text = ((document.body && document.body.innerText) || '').replace(/\s+/g, ' ').slice(0, 100);
          }
          return JSON.stringify(res);
        })();
    """.trimIndent()
}
