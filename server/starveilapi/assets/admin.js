(function () {
  var root = document.getElementById('sv-content');
  var nav = document.getElementById('sv-tabs');
  var sub = document.getElementById('sv-sub');
  var toastBox = document.getElementById('sv-toast');
  var bar = document.getElementById('sv-progress');
  var tokenMeta = document.querySelector('meta[name="sv-token"]');
  var current = new URLSearchParams(location.search).get('a') || 'dashboard';

  function toast(msg, isErr) {
    toastBox.textContent = msg;
    toastBox.className = 'sv-toast show' + (isErr ? ' err' : '');
    clearTimeout(toast._t);
    toast._t = setTimeout(function () { toastBox.className = 'sv-toast'; }, 2200);
  }
  function busy(on) {
    if (on) { bar.className = 'sv-progress on'; }
    else { bar.className = 'sv-progress done'; setTimeout(function(){ bar.className='sv-progress'; }, 350); }
  }
  function groupOf(page) {
    var links = document.querySelectorAll('.sv-tabs a, .sv-sub-list a');
    for (var i = 0; i < links.length; i++) {
      if (links[i].getAttribute('data-page') === page) {
        return links[i].getAttribute('data-group') || '';
      }
    }
    return '';
  }
  function setActive(page) {
    current = page;
    var group = groupOf(page);
    Array.prototype.forEach.call(nav.children, function (a) {
      a.className = a.getAttribute('data-group') === group ? 'is-active' : '';
    });
    var hasSub = false;
    Array.prototype.forEach.call(document.querySelectorAll('.sv-sub-list'), function (list) {
      var on = list.getAttribute('data-group') === group;
      list.hidden = !on;
      if (!on) { return; }
      hasSub = true;
      Array.prototype.forEach.call(list.children, function (a) {
        a.className = a.getAttribute('data-page') === page ? 'is-active' : '';
      });
    });
    // 一组只有一页时整条二级栏收起，避免在 tabs 与内容之间留一条空白
    if (sub) { sub.hidden = !hasSub; }
    nav.setAttribute('data-has-sub', hasSub ? '1' : '0');
  }
  function load(page, push) {
    busy(true);
    fetch(location.pathname + '?a=' + encodeURIComponent(page) + '&ajax=1&_=' + Date.now(), {
      headers: { 'X-Requested-With': 'XMLHttpRequest' }, credentials: 'same-origin'
    }).then(function (r) { return r.json(); }).then(function (data) {
      busy(false);
      if (!data.ok) { toast(data.msg || '加载失败', true); return; }
      root.innerHTML = data.html;
      document.title = '星幕 API · ' + data.title;
      setActive(data.page);
      if (push !== false) { history.replaceState(null, '', '?a=' + data.page); }
      bindForms();
    }).catch(function () { busy(false); toast('网络错误，请重试', true); });
  }

  /* 行编辑器：把行控件压成后端认识的隐藏字段（JSON 或逗号串）。
     页面没有 JS 时隐藏字段仍是原值，提交行为与以前一致。 */
  function syncRows() {
    Array.prototype.forEach.call(root.querySelectorAll('[data-sv-rows]'), function (box) {
      var out = box.querySelector('[data-sv-out]');
      if (!out) { return; }
      var rows = Array.prototype.slice.call(box.querySelectorAll('[data-row]'));
      if (box.getAttribute('data-sv-rows') === 'csv') {
        var picked = [];
        rows.forEach(function (row) {
          var on = row.querySelector('.js-on');
          if (!on || on.checked) { picked.push(row.getAttribute('data-row')); }
        });
        out.value = picked.join(',');
        return;
      }
      var list = [];
      rows.forEach(function (row, index) {
        function val(sel) { var n = row.querySelector(sel); return n ? n.value : ''; }
        var vis = row.querySelector('.js-visible');
        list.push({
          id: row.getAttribute('data-row'),
          label: val('.js-label'),
          icon: val('.js-icon'),
          visible: vis ? vis.checked : true,
          order: index + 1,
          mode: val('.js-mode')
        });
      });
      out.value = JSON.stringify(list);
    });
  }

  function bindForms() {
    Array.prototype.forEach.call(root.querySelectorAll('form'), function (form) {
      // data-plain：GET 筛选/搜索表单交给浏览器自己提交，不要被 AJAX POST 接管
      if (form.hasAttribute('data-plain')) { return; }
      form.addEventListener('submit', function (ev) {
        ev.preventDefault();
        // 破坏性操作先问一句：表单里带 `_confirm` 就弹原生确认框（删除分组会连频道一起删）。
        // 取消时直接 return，不再走下面的 syncRows + 提交。
        var confirmField = form.querySelector('input[name="_confirm"]');
        if (confirmField && confirmField.value && !window.confirm(confirmField.value)) {
          return;
        }
        syncRows();
        var data = new FormData(form);
        // FormData 只收「可提交控件」，不含触发提交的那个按钮本身。
        // 直播的批量表单是用 `<button name="op" value="batchDelete">` 区分动作的，
        // 漏掉它就等于 op 为空：服务端一个分支都进不去，什么都不改，前端却因为
        // 拿到 `{ok:true}` 弹一句"已保存"——正是"提示成功但没删"的由来。
        var submitter = ev.submitter
          || (document.activeElement && document.activeElement.form === form
            ? document.activeElement
            : null);
        if (submitter && submitter.name && !data.has(submitter.name)) {
          data.append(submitter.name, submitter.value);
        }
        if (tokenMeta && !data.get('_token')) { data.append('_token', tokenMeta.content); }
        busy(true);
        fetch(location.pathname + '?a=' + current + '&ajax=1&_=' + Date.now(), {
          method: 'POST', body: data, credentials: 'same-origin',
          headers: { 'X-Requested-With': 'XMLHttpRequest' }
        }).then(function (r) { return r.json(); }).then(function (res) {
          busy(false);
          if (!res.ok) { toast(res.msg || '保存失败', true); return; }
          toast(res.msg || '已保存');
          load(current, false);
        }).catch(function () { busy(false); toast('网络错误，请重试', true); });
      });
    });
    // 页面内容是 AJAX 换进来的：换页后必须重跑一次上限逻辑，否则未勾的行不会置灰，
    // 用户能勾上第 6 个，保存时又被服务端裁掉，表现就是"勾了没保存上"。
    tabVisibleLimit();
  }

  nav.addEventListener('click', function (ev) {
    var a = ev.target.closest('a[data-entry]');
    if (!a) { return; }
    ev.preventDefault();
    // 已经在当前分组时只切显示，不重新拉页面
    if (a.getAttribute('data-group') === groupOf(current)) { return; }
    load(a.getAttribute('data-entry'), true);
  });
  if (sub) {
    sub.addEventListener('click', function (ev) {
      var a = ev.target.closest('a[data-page]');
      if (!a) { return; }
      ev.preventDefault();
      var page = a.getAttribute('data-page');
      if (page === current) { return; }
      load(page, true);
    });
  }
  /* 标签不带 href（否则浏览器会在左下角弹一条目标地址预览），但加了 tabindex，
     焦点仍然在；所以回车/空格要自己补一次 click，键盘操作与以前完全一致 */
  function bindKeys(box) {
    if (!box) { return; }
    box.addEventListener('keydown', function (ev) {
      if (ev.key !== 'Enter' && ev.key !== ' ') { return; }
      var a = ev.target.closest('a[data-page]');
      if (!a) { return; }
      ev.preventDefault();
      a.click();
    });
  }
  bindKeys(nav);
  bindKeys(sub);

  /* 自定义下拉：原生 select 只当数据源，这里负责开合与写回 */
  function closeSelectMenus() {
    Array.prototype.forEach.call(document.querySelectorAll('.sv-select-menu'), function (m) {
      m.hidden = true;
    });
    Array.prototype.forEach.call(document.querySelectorAll('.sv-select-btn'), function (b) {
      b.setAttribute('aria-expanded', 'false');
    });
  }
  document.addEventListener('keydown', function (ev) {
    if (ev.key === 'Escape') { closeSelectMenus(); }
  });
  document.addEventListener('click', function (ev) {
    var selBtn = ev.target.closest('.sv-select-btn');
    if (selBtn) {
      var box = selBtn.closest('[data-sv-select]');
      var menu = box.querySelector('.sv-select-menu');
      var willOpen = menu.hidden;
      closeSelectMenus();
      menu.hidden = !willOpen;
      selBtn.setAttribute('aria-expanded', willOpen ? 'true' : 'false');
      return;
    }
    var opt = ev.target.closest('.sv-select-opt');
    if (opt) {
      var wrap = opt.closest('[data-sv-select]');
      var native = wrap.querySelector('select');
      native.value = opt.getAttribute('data-value');
      wrap.querySelector('.sv-select-label').textContent = opt.textContent;
      Array.prototype.forEach.call(wrap.querySelectorAll('.sv-select-opt'), function (o) {
        var on = o === opt;
        o.className = on ? 'sv-select-opt is-selected' : 'sv-select-opt';
        o.setAttribute('aria-selected', on ? 'true' : 'false');
      });
      closeSelectMenus();
      native.dispatchEvent(new Event('change', { bubbles: true }));
      return;
    }
    var ptab = ev.target.closest('[data-pane-tab]');
    if (ptab) {
      var key = ptab.getAttribute('data-pane-tab');
      Array.prototype.forEach.call(document.querySelectorAll('[data-pane-tab]'), function (b) {
        b.className = b.getAttribute('data-pane-tab') === key ? 'is-active' : '';
      });
      Array.prototype.forEach.call(document.querySelectorAll('[data-pane]'), function (p) {
        p.hidden = p.getAttribute('data-pane') !== key;
      });
      var box = document.querySelector('[data-pane="' + key + '"]');
      if (box) { box.scrollIntoView({ block: 'nearest' }); }
      return;
    }
    var move = ev.target.closest('[data-move]');
    if (move) {
      var row = move.closest('[data-row]');
      var up = move.getAttribute('data-move') === 'up';
      var sib = row ? (up ? row.previousElementSibling : row.nextElementSibling) : null;
      if (row && sib && sib.hasAttribute('data-row')) {
        var parent = row.parentNode;
        if (up) { parent.insertBefore(row, sib); } else { parent.insertBefore(sib, row); }
        syncRows();
      }
      return;
    }
    var btn = ev.target.closest('[data-copy]');
    if (!btn) { closeSelectMenus(); return; }
    var text = btn.getAttribute('data-copy');
    var done = function () { toast('已复制'); };
    if (navigator.clipboard) {
      navigator.clipboard.writeText(text).then(done, function () { toast('复制失败', true); });
    } else {
      var ta = document.createElement('textarea');
      ta.value = text; document.body.appendChild(ta); ta.select();
      try { document.execCommand('copy'); done(); } catch (e) { toast('复制失败', true); }
      document.body.removeChild(ta);
    }
  });
  bindForms();
  // 广告平台：选「不接聚合」时，整组 TradPlus 凭证灰置并禁止提交。
  // 放在前端做是为了即时反馈——服务端保存后才知道该灰就该白等一次刷新。
  (function () {
    var sel = document.querySelector('select[name="s[ad_platform]"]');
    if (!sel) { return; }
    var deps = document.querySelectorAll('.sv-dep');
    var sync = function () {
      var on = sel.value === 'tradplus';
      for (var i = 0; i < deps.length; i++) {
        var fields = deps[i].querySelectorAll('input,select,textarea');
        for (var j = 0; j < fields.length; j++) { fields[j].disabled = !on; }
        deps[i].classList.toggle('sv-off', !on);
      }
    };
    sel.addEventListener('change', sync);
    sync();
  })();
  // 表格全选：勾表头那个 .js-all，同步同一 form 下所有行复选框。
  // 行复选框是用 form="xxx" 挂到表格外的表单上的，按属性找而不是在 form 里找。
  document.addEventListener('change', function (e) {
    var box = e.target;
    if (!box.classList || !box.classList.contains('js-all')) return;
    var target = box.getAttribute('form');
    if (!target) return;
    var rows = document.querySelectorAll('input[name="ids[]"][form="' + target + '"]');
    for (var i = 0; i < rows.length; i++) { rows[i].checked = box.checked; }
  });
  // 批量表单提交前，把勾选行收集进 hidden `ids`（逗号串）。
  // 行复选框是用 form 属性外挂到表单上的，实测有的浏览器/插件环境不把它们算进提交，
  // 表现就是"点了设置分组、提示成功、但一条都没改"。这里统一由 JS 兜住。
  document.addEventListener('submit', function (e) {
    var form = e.target;
    if (!form || !form.id) return;
    var hidden = form.querySelector('input[name="ids"][type="hidden"]');
    if (!hidden) return;
    var picked = [];
    var boxes = document.querySelectorAll('input[name="ids[]"][form="' + form.id + '"]');
    for (var i = 0; i < boxes.length; i++) {
      if (boxes[i].checked) picked.push(boxes[i].value);
    }
    hidden.value = picked.join(',');
  }, true);
  // 分组下拉选到「＋ 新建分组…」时，把同一表单里那个 `{name}_new` 输入框显示出来。
  // 下拉是后台统一的自定义组件（View::select），改值后它会派发 change，
  // 所以这里监听 native select 的 class 即可；没这一步用户只能选不能建。
  document.addEventListener('change', function (e) {
    var sel = e.target;
    if (!sel || !sel.classList || !sel.classList.contains('js-group-new')) return;
    var scope = sel.closest('label, form');
    var input = scope && scope.querySelector('input[name="' + sel.name + '_new"]');
    if (!input) return;
    var isNew = sel.value === '__new__';
    input.style.display = isNew ? 'block' : 'none';
    if (isNew) input.focus();
  });

  /* 底栏显示上限：最多勾 5 个。到上限后把没勾的禁用，避免"勾了却不生效"。
     服务端 HomePage::tabsAction 还会再收敛一次，前端只是提前给反馈。
     data-locked：会员关闭时的会员行，不渲染也不占名额。
     data-fixed ：会员体系开启时的会员行，恒显示、恒占一个名额，但脚本不能改写它的勾选。 */
  function tabVisibleLimit() {
    Array.prototype.forEach.call(
      root.querySelectorAll('[data-sv-rows="tabs"][data-max-visible]'),
      function (box) {
        var max = parseInt(box.getAttribute('data-max-visible'), 10) || 5;
        var boxes = Array.prototype.slice.call(box.querySelectorAll('.js-visible'))
          .filter(function (n) { return n.getAttribute('data-locked') !== '1'; });
        var checked = boxes.filter(function (n) { return n.checked; }).length;
        boxes.forEach(function (n) {
          // 固定行占名额但不可操作：它已经勾着，别在这里把它解禁
          if (n.getAttribute('data-fixed') === '1') { n.disabled = true; return; }
          if (n.checked) { n.disabled = false; n.title = ''; return; }
          n.disabled = checked >= max;
          n.title = checked >= max ? '最多显示 ' + max + ' 个按钮' : '';
        });
      }
    );
  }
  function tabCheckedCount(wrap) {
    return Array.prototype.slice.call(wrap.querySelectorAll('.js-visible'))
      .filter(function (n) { return n.getAttribute('data-locked') !== '1' && n.checked; }).length;
  }
  document.addEventListener('change', function (e) {
    var t = e.target;
    if (!t || !t.classList || !t.classList.contains('js-visible')) { return; }
    var wrap = t.closest('[data-sv-rows][data-max-visible]');
    var max = wrap ? (parseInt(wrap.getAttribute('data-max-visible'), 10) || 5) : 0;
    // 已经勾满时又勾了一个：当场退回。留给服务端去裁会弹"已保存"，但勾选消失，
    // 用户只会以为保存坏了。
    if (wrap && max > 0 && t.checked && tabCheckedCount(wrap) > max) {
      t.checked = false;
      toast('底栏最多显示 ' + max + ' 个按钮，先取消一个再勾新的', true);
    }
    tabVisibleLimit();
    syncRows();
  });
  /* 底栏行增删：行数不限（服务端上限 20），"显示"上限仍由 tabVisibleLimit 卡在 5 个。
     id 必须是小写字母开头的短串（见 HomePage::isTabId），所以用 r + 序号生成。 */
  var tabSeq = 0;
  document.addEventListener('click', function (e) {
    var add = e.target.closest('[data-add-row]');
    if (add) {
      var box = add.closest('[data-sv-rows]');
      var tpl = box && box.querySelector('template[data-row-template]');
      var rows = box && box.querySelector('.sv-rows');
      if (!tpl || !rows) { return; }
      var row = tpl.content.firstElementChild.cloneNode(true);
      tabSeq += 1;
      row.setAttribute('data-row', 'r' + Date.now().toString(36) + tabSeq);
      rows.appendChild(row);
      syncRows();
      tabVisibleLimit();
      return;
    }
    var del = e.target.closest('[data-del-row]');
    if (del) {
      var target = del.closest('[data-row]');
      if (target && target.parentNode) {
        target.parentNode.removeChild(target);
        syncRows();
        tabVisibleLimit();
      }
    }
  });
  tabVisibleLimit();
})();
