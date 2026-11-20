/**
 * patches/zh_i18n/engine.js - dictionary lookup, DOM walk, mutation observer.
 */
var zhDict = window.__agZhDict || {};

var ZH_SKIP_SELECTOR = 'script, style, code, pre, textarea, .monaco-editor, '
  + '.cm-editor, .xterm, [data-language], [contenteditable="true"]';

function zhSkipParent(node) {
  var el = node.parentElement;
  return !el || !!el.closest(ZH_SKIP_SELECTOR);
}

function zhTranslateTextNode(node) {
  var v = node.nodeValue;
  if (!v || v.length < 2) return;
  var trimmed = v.trim();
  if (!trimmed) return;
  var zh = zhDict[trimmed];
  if (!zh || zh === trimmed) return;
  if (zhSkipParent(node)) return;
  node.nodeValue = v.replace(trimmed, zh);
}

var ZH_ATTRS = ['placeholder', 'title', 'aria-label'];

function zhTranslateAttrs(el) {
  if (!el || el.nodeType !== 1 || !el.getAttribute) return;
  if (el.closest && el.closest(ZH_SKIP_SELECTOR)) return;
  for (var i = 0; i < ZH_ATTRS.length; i++) {
    var name = ZH_ATTRS[i];
    var val = el.getAttribute(name);
    if (val && val.length >= 2) {
      var zh = zhDict[val.trim()];
      if (zh && zh !== val) {
        el.setAttribute(name, val.replace(val.trim(), zh));
      }
    }
  }
}

function zhWalk(root) {
  if (!root) return;
  if (root.nodeType === 3) {
    zhTranslateTextNode(root);
    return;
  }
  if (root.nodeType !== 1 && root.nodeType !== 9 && root.nodeType !== 11) return;
  if (root.nodeType === 1) zhTranslateAttrs(root);
  var doc = window.document;
  var walker = doc.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
    acceptNode: function (n) {
      return (n.nodeValue && n.nodeValue.trim())
        ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_REJECT;
    }
  });
  var nodes = [];
  var cur;
  while ((cur = walker.nextNode())) nodes.push(cur);
  for (var i = 0; i < nodes.length; i++) zhTranslateTextNode(nodes[i]);
  if (root.querySelectorAll) {
    var els = root.querySelectorAll('[placeholder], [title], [aria-label]');
    for (var j = 0; j < els.length; j++) zhTranslateAttrs(els[j]);
  }
}

function zhInstall() {
  if (zhState.installed) return;
  zhState.installed = true;
  zhWalk(window.document.body || window.document.documentElement);

  var pending = [];
  var flushScheduled = false;
  function flush() {
    flushScheduled = false;
    var batch = pending;
    pending = [];
    for (var i = 0; i < batch.length; i++) zhWalk(batch[i]);
  }
  function enqueue(node) {
    pending.push(node);
    if (!flushScheduled) {
      flushScheduled = true;
      window.setTimeout(flush, 120);
    }
  }

  try {
    zhState.observer = new MutationObserver(function (mutations) {
      for (var i = 0; i < mutations.length; i++) {
        var m = mutations[i];
        if (m.type === 'characterData') {
          zhTranslateTextNode(m.target);
        } else if (m.type === 'attributes') {
          zhTranslateAttrs(m.target);
        } else {
          for (var j = 0; j < m.addedNodes.length; j++) enqueue(m.addedNodes[j]);
        }
      }
    });
    zhState.observer.observe(window.document.documentElement, {
      childList: true,
      subtree: true,
      characterData: true,
      attributes: true,
      attributeFilter: ZH_ATTRS
    });
  } catch (e) {
    /* observer unavailable; initial walk already applied */
  }
}

if (window.document.readyState === 'loading') {
  window.document.addEventListener('DOMContentLoaded', zhInstall);
} else {
  zhInstall();
}
