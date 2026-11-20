/**
 * patches/task_live/observer.js - watch the composer for the "Stop execution"
 * button (present exactly while a cascade is running) and report run-state
 * transitions to the native Live Updates notification via Android.setTaskState.
 */
function taskLiveTitle() {
  var t = (window.document && window.document.title) || '';
  t = t.replace(/\s*[-|·]\s*Antigravity( App)?\s*$/i, '').trim();
  return t || 'Antigravity';
}

function taskLiveLog(message) {
  try {
    if (window.Android && typeof window.Android.logTaskLive === 'function') {
      window.Android.logTaskLive(window.__AG_NATIVE_BRIDGE_TOKEN__, message);
    }
  } catch (e) {}
}

function taskLiveSend(state) {
  var hasBridge = !!(window.Android
    && typeof window.Android.setTaskState === 'function');
  taskLiveLog('send state=' + state + ' signal=' + (taskLiveState.lastSignal || '-')
    + ' bridge=' + hasBridge);
  try {
    if (hasBridge) {
      window.Android.setTaskState(
        window.__AG_NATIVE_BRIDGE_TOKEN__, state, taskLiveTitle());
    }
  } catch (e) {
    /* bridge not ready yet; the next scan retries */
  }
}

// Running-state signals (ANY one present = a cascade is running):
//  1. A stop button. The composer control is labelled "Stop", a side panel
//     uses "Stop execution"; the zh_i18n patch may translate either.
//  2. The cascade status line. The hub renders exactly "Working" /
//     "Thinking" / "Compacting" while a cascade is active (and "Worked
//     for ..." once it ends) in a muted status element, both under the
//     latest message and in the conversation list - so this signal also
//     survives switching to another conversation, which the composer
//     button does not.
var TASK_LIVE_STOP_LABELS = {
  'Stop execution': true, 'Stop': true, '停止执行': true, '停止': true
};
var TASK_LIVE_STATUS_TEXTS = {
  'Working': true, 'Thinking': true, 'Compacting': true,
  '工作中': true, '思考中': true, '压缩中': true, '正在压缩': true
};

function taskLiveDetect() {
  var els = window.document.querySelectorAll('button[aria-label]');
  for (var i = 0; i < els.length; i++) {
    var label = els[i].getAttribute('aria-label');
    if (TASK_LIVE_STOP_LABELS[label]) {
      taskLiveState.lastSignal = 'btn:' + label;
      return true;
    }
  }
  var muted = window.document.querySelectorAll('[class*="text-muted-foreground"]');
  for (var j = 0; j < muted.length; j++) {
    var el = muted[j];
    if (el.children && el.children.length) continue;
    var t = (el.textContent || '').trim();
    if (TASK_LIVE_STATUS_TEXTS[t]) {
      taskLiveState.lastSignal = 'txt:' + t;
      return true;
    }
  }
  return false;
}

function taskLiveScan() {
  var running = taskLiveDetect();
  var now = Date.now();
  if (running) {
    taskLiveState.lastSeenRunning = now;
    taskLiveState.absentScans = 0;
    if (!taskLiveState.running) {
      // Commit "running" fast (a stop button existing at all is a strong
      // signal); only a brief 300ms settle is required.
      if (taskLiveState.pending !== 'running') {
        taskLiveState.pending = 'running';
        taskLiveState.pendingSince = now;
        return;
      }
      if (now - taskLiveState.pendingSince < 300) return;
      taskLiveState.running = true;
      taskLiveState.pending = null;
      taskLiveState.title = taskLiveTitle();
      taskLiveSend('running');
      return;
    }
    if (taskLiveTitle() !== taskLiveState.title) {
      taskLiveState.title = taskLiveTitle();
      taskLiveSend('running');
      taskLiveState.lastKeepalive = now;
      return;
    }
    // Keepalive: reaffirm "running" every 30s (and breadcrumb WHICH signal
    // is holding the state up) so the native watchdog can tell a live,
    // observed run from one whose web layer went silent.
    if (now - (taskLiveState.lastKeepalive || 0) >= 30000) {
      taskLiveState.lastKeepalive = now;
      taskLiveLog('alive signal=' + (taskLiveState.lastSignal || '-'));
      taskLiveSend('running');
    }
    return;
  }
  if (taskLiveState.running) {
    // Commit "done" only on sustained, repeated absence: every signal gone
    // for 10s from the last actual sighting AND at least 3 consecutive
    // absent scans. Mid-task the visible signal can legitimately vanish
    // for seconds (phase changes, prompts, switching views); the earlier
    // 3s rule fired "done" while tasks were still running.
    taskLiveState.absentScans++;
    if (now - taskLiveState.lastSeenRunning >= 10000
        && taskLiveState.absentScans >= 3) {
      taskLiveState.running = false;
      taskLiveState.pending = null;
      taskLiveState.absentScans = 0;
      taskLiveSend('done');
    }
    return;
  }
  taskLiveState.pending = null;
}

// The moment the user sends a message, notify native SYNCHRONOUSLY from
// the event handler - the page is still in the foreground at that instant,
// so this call cannot be frozen by background timer throttling the way
// the observer's scans are. Native posts the live notification at once.
function taskLiveSendHook() {
  try {
    if (window.Android && typeof window.Android.taskStarted === 'function') {
      taskLiveLog('send hook fired');
      window.Android.taskStarted(
        window.__AG_NATIVE_BRIDGE_TOKEN__, taskLiveTitle());
    }
  } catch (e) {}
  // Adopt the run into the observer's own state machine as well, so its
  // absence logic can conclude "done" even if the DOM signals in this
  // conversation never match the detector's patterns.
  taskLiveState.running = true;
  taskLiveState.lastSeenRunning = Date.now();
  taskLiveState.absentScans = 0;
  taskLiveState.title = taskLiveTitle();
}

function taskLiveFindSendButton() {
  return window.document.querySelector('[data-testid="send-button"]')
    || window.document.querySelector(
      'button[aria-label="Send message"],button[aria-label="发送消息"]');
}

function taskLiveComposerRoot(sendBtn) {
  var root = sendBtn;
  for (var i = 0; i < 8 && root && root.parentElement; i++) {
    root = root.parentElement;
    if (root.querySelector('textarea,[contenteditable="true"]')) return root;
  }
  return null;
}

function taskLiveArm() {
  if (taskLiveState.installed) return;
  taskLiveState.installed = true;
  // Fresh grace period after every (re)load: absence measured from before
  // the reload must not count towards "done".
  taskLiveState.lastSeenRunning = Date.now();
  taskLiveLog('observer armed; setTaskState='
    + (window.Android ? typeof window.Android.setTaskState : 'no-android')
    + ' buttons=' + window.document.querySelectorAll('button').length);
  window.document.addEventListener('visibilitychange', function () {
    if (!window.document.hidden) {
      // Back in the foreground, timers run again: scan right away so a
      // task that finished while hidden is reported without delay.
      taskLiveScan();
    }
  });
  window.document.addEventListener('click', function (e) {
    var btn = taskLiveFindSendButton();
    if (btn && e.target && (e.target === btn || btn.contains(e.target))) {
      taskLiveSendHook();
    }
  }, true);
  window.document.addEventListener('keydown', function (e) {
    if (e.key !== 'Enter' || e.shiftKey || e.isComposing) return;
    var btn = taskLiveFindSendButton();
    if (!btn) return;
    var root = taskLiveComposerRoot(btn);
    if (root && e.target && root.contains(e.target)) taskLiveSendHook();
  }, true);
  window.__agTaskLive = { scanNow: taskLiveScan };

  var scheduled = null;
  function schedule() {
    if (scheduled) return;
    scheduled = window.setTimeout(function () {
      scheduled = null;
      taskLiveScan();
    }, 250);
  }

  try {
    taskLiveState.observer = new MutationObserver(schedule);
    taskLiveState.observer.observe(window.document.documentElement, {
      childList: true,
      subtree: true,
      attributes: true,
      attributeFilter: ['aria-label']
    });
  } catch (e) {
    /* fall back to the interval scan below */
  }
  window.setInterval(taskLiveScan, 1000);
  taskLiveScan();
}

if (window.document.readyState === 'loading') {
  window.document.addEventListener('DOMContentLoaded', taskLiveArm);
} else {
  taskLiveArm();
}
