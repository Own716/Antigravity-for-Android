/**
 * patches/zh_i18n/config.js - Simplified Chinese UI translation layer.
 *
 * The official 2.33 hub UI is English-only (no locale data ships in the
 * bundle), so this patch translates rendered UI text at runtime from the
 * dictionary in dict.js: text nodes and placeholder/title/aria-label
 * attributes are replaced on render and on DOM mutation. Code editor,
 * terminal, and user-input areas are never touched.
 *
 * First fragment: if the patch already installed, exit the whole IIFE.
 */
'use strict';

if (window.__agZh && window.__agZh.__installed) {
  return;
}

var zhState = {
  installed: false,
  observer: null
};

window.__agZh = { __installed: true, state: zhState };
