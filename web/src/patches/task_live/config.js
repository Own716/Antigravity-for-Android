/**
 * patches/task_live/config.js - task run-state observer state and guard.
 *
 * First fragment: if the patch already installed, exit the whole IIFE.
 */
'use strict';

if (window.__agTaskLive && window.__agTaskLive.__installed) {
  return;
}

var taskLiveState = {
  installed: false,
  running: false,
  title: '',
  timer: null,
  observer: null,
  pending: null,
  pendingSince: 0,
  lastLabel: '',
  lastSignal: '',
  lastSeenRunning: 0,
  absentScans: 0,
  lastKeepalive: 0
};

window.__agTaskLive = { __installed: true, state: taskLiveState };
