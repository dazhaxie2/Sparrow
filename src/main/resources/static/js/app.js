/* Sparrow 前端逻辑:科技树渲染 / 登录 / 会员支付 / AI 向导 */
(function () {
  'use strict';

  var ERA_COLORS = {
    1: '#8d6e63', 2: '#7cb342', 3: '#c08a2d', 4: '#26a69a', 5: '#5c6bc0',
    6: '#ab47bc', 7: '#ef6c00', 8: '#f9a825', 9: '#29b6f6', 10: '#ec407a'
  };
  var COL_W = 230, ROW_H = 66;

  var chart = null;
  var treeData = null;          // {nodes, edges}
  var highlight = null;         // {selectedId, chainIds:Set}

  // ---------- 基础工具 ----------
  function token() { return localStorage.getItem('sparrow_token'); }

  function api(path, options) {
    options = options || {};
    options.headers = Object.assign({ 'Content-Type': 'application/json' }, options.headers || {});
    var t = token();
    if (t) options.headers['Authorization'] = 'Bearer ' + t;
    return fetch(path, options)
      .then(function (r) { return r.json(); })
      .then(function (body) {
        if (body.code !== 0) {
          var err = new Error(body.message || '请求失败');
          err.code = body.code;
          throw err;
        }
        return body.data;
      });
  }

  function toast(msg) {
    var el = document.getElementById('toast');
    el.textContent = msg;
    el.hidden = false;
    clearTimeout(el._timer);
    el._timer = setTimeout(function () { el.hidden = true; }, 2600);
  }

  function $(id) { return document.getElementById(id); }

  function escapeHtml(value) {
    return String(value == null ? '' : value).replace(/[&<>"']/g, function (ch) {
      return ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[ch];
    });
  }

  // ---------- 科技树渲染 ----------
  function layout(nodes) {
    var byEra = {};
    nodes.forEach(function (n) {
      (byEra[n.eraRank] = byEra[n.eraRank] || []).push(n);
    });
    var pos = {};
    Object.keys(byEra).forEach(function (rank) {
      var list = byEra[rank];
      list.forEach(function (n, i) {
        pos[n.id] = {
          x: (rank - 1) * COL_W,
          y: (i - (list.length - 1) / 2) * ROW_H
        };
      });
    });
    return pos;
  }

  function buildOption() {
    var pos = layout(treeData.nodes);
    var chain = highlight ? highlight.chainIds : null;
    var selected = highlight ? highlight.selectedId : null;

    var eras = {};
    treeData.nodes.forEach(function (n) { eras[n.eraRank] = n.era; });
    var eraLabels = Object.keys(eras).map(function (rank) {
      var ys = treeData.nodes.filter(function (n) { return n.eraRank == rank; })
        .map(function (n) { return pos[n.id].y; });
      return {
        id: 'era-' + rank,
        name: eras[rank],
        x: (rank - 1) * COL_W,
        y: Math.min.apply(null, ys) - 70,
        symbol: 'rect',
        symbolSize: [1, 1],
        itemStyle: { color: 'transparent' },
        label: {
          show: true, color: ERA_COLORS[rank], fontSize: 15, fontWeight: 700,
          formatter: eras[rank]
        },
        tooltip: { show: false },
        _isEra: true
      };
    });

    var data = treeData.nodes.map(function (n) {
      var inChain = chain && (chain.has(n.id) || n.id === selected);
      var dim = chain && !inChain;
      return {
        id: String(n.id),
        name: n.name,
        x: pos[n.id].x,
        y: pos[n.id].y,
        symbol: 'roundRect',
        symbolSize: [Math.max(n.name.length * 13 + 18, 56), 30],
        itemStyle: {
          color: ERA_COLORS[n.eraRank],
          opacity: dim ? 0.16 : 1,
          borderColor: n.id === selected ? '#ffffff' : (inChain ? '#f6c244' : 'rgba(0,0,0,0)'),
          borderWidth: n.id === selected ? 2.5 : (inChain ? 2 : 0),
          shadowBlur: inChain ? 12 : 0,
          shadowColor: 'rgba(246,194,68,.6)'
        },
        label: {
          show: true,
          color: '#0d1421',
          fontSize: 12,
          fontWeight: 600,
          formatter: (n.premium ? '👑' : '') + n.name,
          opacity: dim ? 0.25 : 1
        },
        _node: n
      };
    }).concat(eraLabels);

    var edges = treeData.edges.map(function (e) {
      var active = chain && (chain.has(e.from) || e.from === selected) &&
        (chain.has(e.to) || e.to === selected);
      return {
        source: String(e.from),
        target: String(e.to),
        lineStyle: {
          color: active ? '#f6c244' : '#33415e',
          width: active ? 2 : 1,
          opacity: chain ? (active ? 0.95 : 0.12) : 0.55,
          curveness: 0.18
        }
      };
    });

    return {
      backgroundColor: '#0d1421',
      tooltip: {
        formatter: function (p) {
          if (!p.data || !p.data._node) return '';
          var n = p.data._node;
          return '<b>' + escapeHtml(n.name) + '</b><br>' + escapeHtml(n.era) + ' · ' +
            escapeHtml(n.yearLabel || '') + '<br><span style="font-size:12px">' +
            escapeHtml(n.summary) + '</span>';
        },
        confine: true,
        backgroundColor: '#1d2a44',
        borderColor: '#243049',
        textStyle: { color: '#e6edf7', width: 260, overflow: 'break' },
        extraCssText: 'max-width:300px;white-space:normal;'
      },
      series: [{
        type: 'graph',
        layout: 'none',
        roam: true,
        zoom: 0.55,
        center: [4.5 * COL_W, 0],
        data: data,
        edges: edges,
        edgeSymbol: ['none', 'arrow'],
        edgeSymbolSize: 7,
        emphasis: { disabled: true },
        silent: false
      }]
    };
  }

  function renderTree() {
    chart.setOption(buildOption(), true);
  }

  function loadTree() {
    return api('/api/graph/tree').then(function (tree) {
      treeData = tree;
      renderTree();
    });
  }

  // ---------- 节点详情 ----------
  function showNode(id) {
    Promise.all([
      api('/api/graph/node/' + id),
      api('/api/graph/node/' + id + '/prerequisites')
    ]).then(function (rs) {
      var d = rs[0], chain = rs[1];
      highlight = { selectedId: d.id, chainIds: new Set(chain.map(function (n) { return n.id; })) };
      renderTree();

      var html = '';
      html += '<div class="node-era">' + escapeHtml(d.era) + ' · ' + escapeHtml(d.yearLabel || '') + '</div>';
      html += '<div class="node-title">' + escapeHtml(d.name) + (d.premium ? ' <span class="crown">👑会员深度</span>' : '') + '</div>';
      html += '<div class="node-summary">' + escapeHtml(d.summary) + '</div>';
      if (d.locked) {
        html += '<div class="locked-box">🔒 本节点的深度解读为会员专属内容<br>' +
          '<button class="btn gold" id="btn-unlock">👑 开通会员解锁</button></div>';
      } else if (d.detail) {
        html += '<div class="node-detail">' + escapeHtml(d.detail) + '</div>';
      }
      if (d.prerequisites.length) {
        html += '<div class="rel-title">⬅ 直接前置(' + d.prerequisites.length + ')</div>';
        html += '<div class="rel-chips">' + d.prerequisites.map(function (n) {
          return '<span data-id="' + Number(n.id) + '">' + escapeHtml(n.name) + '</span>';
        }).join('') + '</div>';
      }
      if (d.unlocks.length) {
        html += '<div class="rel-title">➡ 直接解锁(' + d.unlocks.length + ')</div>';
        html += '<div class="rel-chips">' + d.unlocks.map(function (n) {
          return '<span data-id="' + Number(n.id) + '">' + escapeHtml(n.name) + '</span>';
        }).join('') + '</div>';
      }
      if (chain.length) {
        html += '<div class="rel-title">🧬 完整前置链(' + chain.length + ' 项,已在图中高亮)</div>';
      }
      var panel = $('panel');
      panel.innerHTML = html;
      panel.querySelectorAll('.rel-chips span').forEach(function (el) {
        el.addEventListener('click', function () { showNode(Number(el.dataset.id)); });
      });
      var unlock = $('btn-unlock');
      if (unlock) unlock.addEventListener('click', openMemberModal);
    }).catch(function (e) { toast(e.message); });
  }

  // ---------- 登录态 ----------
  function refreshMe() {
    if (!token()) { setAuthUi(null); return Promise.resolve(); }
    return api('/api/user/me')
      .then(function (me) { setAuthUi(me); })
      .catch(function () {
        localStorage.removeItem('sparrow_token');
        setAuthUi(null);
      });
  }

  function setAuthUi(me) {
    $('btn-login').hidden = !!me;
    $('btn-logout').hidden = !me;
    $('user-info').hidden = !me;
    $('btn-member').hidden = !me;
    if (me) {
      var userInfo = $('user-info');
      userInfo.textContent = '';
      userInfo.appendChild(document.createTextNode(me.username));
      if (me.member) {
        var badge = document.createElement('span');
        badge.className = 'member-badge';
        badge.textContent = '👑会员';
        userInfo.appendChild(badge);
      }
      $('btn-member').textContent = me.member ? '👑 会员续期' : '👑 开通会员';
    }
  }

  function doAuth(path) {
    var u = $('login-username').value.trim();
    var p = $('login-password').value;
    api(path, { method: 'POST', body: JSON.stringify({ username: u, password: p }) })
      .then(function (data) {
        localStorage.setItem('sparrow_token', data.token);
        $('modal-login').hidden = true;
        toast('欢迎,' + u);
        return refreshMe();
      })
      .catch(function (e) { toast(e.message); });
  }

  // ---------- 会员支付 ----------
  function openMemberModal() {
    if (!token()) { $('modal-login').hidden = false; return; }
    $('modal-member').hidden = false;
  }

  function buy(code) {
    api('/api/trade/order', { method: 'POST', body: JSON.stringify({ productCode: code }) })
      .then(function (r) { window.location.href = r.payUrl; })
      .catch(function (e) {
        if (e.code === 401) { $('modal-member').hidden = true; $('modal-login').hidden = false; }
        else toast(e.message);
      });
  }

  // ---------- AI 向导 ----------
  function appendMsg(cls, text, sources) {
    var box = $('ai-messages');
    var div = document.createElement('div');
    div.className = 'msg ' + cls;
    div.textContent = text;
    if (sources && sources.length) {
      var src = document.createElement('span');
      src.className = 'src';
      src.textContent = '📎 来源:' + sources.map(function (s) { return s.name; }).join('、');
      div.appendChild(src);
    }
    box.appendChild(div);
    box.scrollTop = box.scrollHeight;
    return div;
  }

  function askAi() {
    var input = $('ai-input');
    var q = input.value.trim();
    if (!q) return;
    if (!token()) { $('modal-login').hidden = false; return; }
    input.value = '';
    appendMsg('user', q);
    var pending = appendMsg('bot', '思考中…');
    api('/api/ai/ask', { method: 'POST', body: JSON.stringify({ question: q }) })
      .then(function (r) {
        pending.remove();
        appendMsg('bot', r.answer, r.sources);
        if (r.remainingQuota >= 0) {
          appendMsg('bot', '今日剩余免费次数:' + r.remainingQuota + '(会员不限次)');
        }
      })
      .catch(function (e) {
        pending.remove();
        if (e.code === 401) { appendMsg('bot', '请先登录后再提问'); $('modal-login').hidden = false; }
        else if (e.code === 429) { appendMsg('bot', e.message); openMemberModal(); }
        else appendMsg('bot', '出错了:' + e.message);
      });
  }

  // ---------- 初始化 ----------
  function init() {
    chart = echarts.init($('chart'));
    chart.on('click', function (p) {
      if (p.dataType === 'node' && p.data && p.data._node) showNode(p.data._node.id);
    });
    window.addEventListener('resize', function () { chart.resize(); });
    // 从收银台返回时刷新会员状态
    window.addEventListener('focus', function () { refreshMe(); });

    $('btn-login').addEventListener('click', function () { $('modal-login').hidden = false; });
    $('btn-logout').addEventListener('click', function () {
      localStorage.removeItem('sparrow_token');
      setAuthUi(null);
      toast('已退出');
    });
    $('btn-do-login').addEventListener('click', function () { doAuth('/api/user/login'); });
    $('btn-do-register').addEventListener('click', function () { doAuth('/api/user/register'); });
    $('btn-member').addEventListener('click', openMemberModal);
    document.querySelectorAll('.modal-close').forEach(function (el) {
      el.addEventListener('click', function () { el.closest('.modal').hidden = true; });
    });
    document.querySelectorAll('.product').forEach(function (el) {
      el.addEventListener('click', function () { buy(el.dataset.code); });
    });
    $('ai-toggle').addEventListener('click', function () {
      $('ai-dock').classList.toggle('collapsed');
    });
    $('ai-send').addEventListener('click', askAi);
    $('ai-input').addEventListener('keydown', function (e) {
      if (e.key === 'Enter') askAi();
    });

    loadTree().catch(function (e) { toast('科技树加载失败:' + e.message); });
    refreshMe();
  }

  init();
})();
