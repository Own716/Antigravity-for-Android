#!/usr/bin/env python3
"""
web/patch_main.py - Dynamic patcher for web/main.js.

Locates the Appearance settings block and injects the Desktop Mode toggle
if not already present. Safe, idempotent, and resilient to upstream changes.
"""

import sys
import os
import re

def patch_main(file_path):
    if not os.path.isfile(file_path):
        print(f"[web-patch] file not found: {file_path}", file=sys.stderr)
        return False

    with open(file_path, "r", encoding="utf-8") as f:
        content = f.read()

    if "Desktop Mode" in content or "ag_desktop_mode" in content:
        print("[web-patch] Desktop Mode toggle already present in main.js")
        return True

    # 1. State hook injection pattern in Appearance component:
    # Matches: var C=g!==3,D=g!==2,E=F=>G=>{F===t&&v(G)}; or legacy var B=f!==3,C=f!==2,D=E=>G=>{E===r&&u(G)};
    hook_pattern = re.compile(r'(var\s+[A-Za-z0-9_$]+=[A-Za-z0-9_$]+!==3,\s*[A-Za-z0-9_$]+=[A-Za-z0-9_$]+!==2,\s*[A-Za-z0-9_$]+=[A-Za-z0-9_$]+=>\s*[A-Za-z0-9_$]+=>\{.*?\};)')
    hook_match = hook_pattern.search(content)
    new_style = False
    if not hook_match:
        # Core 2.33 Appearance component: the theme-mode comparisons were split
        # into separate statements (B=b!==3; var E=b!==2;) and the setter arrow
        # is gone. Anchor on that pair instead.
        hook_pattern_v2 = re.compile(r'([A-Za-z0-9_$]+=[A-Za-z0-9_$]+!==3;\s*var\s+[A-Za-z0-9_$]+=[A-Za-z0-9_$]+!==2;)')
        hook_match = hook_pattern_v2.search(content)
        new_style = hook_match is not None

    # 2. JSX Theme element injection: find Theme element in Appearance
    theme_idx = content.find('{label:"Theme"')
    theme_match = None
    react_id = "A"
    if theme_idx != -1:
        # Search backwards within a reasonable window (e.g. 100 chars) for createElement
        window_start = max(0, theme_idx - 100)
        window_end = min(len(content), theme_idx + 500)
        window_text = content[window_start:window_end]
        theme_pattern = re.compile(
            r'([A-Za-z0-9_$]+)\.createElement\(([A-Za-z0-9_$]+),\{label:"Theme",.*?onSelect:[A-Za-z0-9_$]+\}\)'
        )
        theme_match = theme_pattern.search(window_text)
        if theme_match:
            react_id = theme_match.group(1)

    if not hook_match or not theme_match:
        # Upstream bundle restructured (seen with core 2.33.0: the Appearance
        # component's state vars no longer match the hook pattern). Skip the
        # Desktop Mode injection instead of failing the build or injecting
        # into the wrong scope; the bundle ships pristine and the toggle is
        # lost until the patterns are re-adapted.
        print(f"[web-patch] WARNING: patterns not matched: hook={bool(hook_match)}, theme={bool(theme_match)}; shipping main.js WITHOUT the Desktop Mode toggle", file=sys.stderr)
        return True

    orig_hook = hook_match.group(1)
    injected_hook = (
        f"var [isDesktop,setIsDesktop]=(0,{react_id}.useState)(()=>{{"
        "try{if(window.Android&&typeof window.Android.isDesktopMode==='function')return window.Android.isDesktopMode();"
        "var s=localStorage.getItem('ag_desktop_mode');return s===null?!1:s==='true'}catch(_){return !1}});"
        f"var onDesktopToggle=(0,{react_id}.useCallback)(v=>{{"
        "setIsDesktop(v);try{localStorage.setItem('ag_desktop_mode',String(v));"
        "if(window.Android&&typeof window.Android.setDesktopMode==='function')window.Android.setDesktopMode(v)}catch(_){}},[]);"
        f"var [isTaskLive,setIsTaskLive]=(0,{react_id}.useState)(()=>{{"
        "try{if(window.Android&&typeof window.Android.isTaskLiveEnabled==='function')return window.Android.isTaskLiveEnabled();"
        "var s=localStorage.getItem('ag_task_live');return s===null?!0:s==='true'}catch(_){return !0}});"
        f"var onTaskLiveToggle=(0,{react_id}.useCallback)(v=>{{"
        "setIsTaskLive(v);try{localStorage.setItem('ag_task_live',String(v));"
        "if(window.Android&&typeof window.Android.setTaskLiveEnabled==='function')window.Android.setTaskLiveEnabled(window.__AG_NATIVE_BRIDGE_TOKEN__,v)}catch(_){}},[]);"
        "var onTaskLiveTest=function(){try{if(window.Android&&typeof window.Android.testTaskLive==='function')window.Android.testTaskLive(window.__AG_NATIVE_BRIDGE_TOKEN__)}catch(_){}};"
        f"var [isBattExempt,setIsBattExempt]=(0,{react_id}.useState)(()=>{{"
        "try{if(window.Android&&typeof window.Android.isBatteryExempt==='function')return window.Android.isBatteryExempt()}catch(_){}return !1});"
        "var onBattOpen=function(){try{if(window.Android&&typeof window.Android.openBatteryOptimizationSettings==='function')window.Android.openBatteryOptimizationSettings(window.__AG_NATIVE_BRIDGE_TOKEN__);setTimeout(function(){try{if(window.Android&&typeof window.Android.isBatteryExempt==='function')setIsBattExempt(window.Android.isBatteryExempt())}catch(_){}},1500)}catch(_){}};"
        + orig_hook
    )

    orig_theme = theme_match.group(0)
    if new_style:
        # The 2.33 bundle's native setting-row/switch components live in other
        # module scopes and cannot be referenced safely from the Appearance
        # component. Build the row from plain DOM elements + the React handle
        # that is in scope here; global Tailwind classes keep it visually
        # consistent with the surrounding settings.
        injected_theme = (
            orig_theme
            + f',{react_id}.createElement("div",{{className:"flex items-center justify-between w-full py-2 px-3"}},'
            + f'{react_id}.createElement("div",null,'
            + f'{react_id}.createElement("div",{{className:"text-sm"}},"Desktop Mode"),'
            + f'{react_id}.createElement("div",{{className:"text-sm text-muted-foreground leading-relaxed"}},"Use desktop viewport and user agent for the workspace")),'
            + f'{react_id}.createElement("input",{{type:"checkbox",checked:isDesktop,onChange:function(e){{onDesktopToggle(e.target.checked)}},className:"w-5 h-5 shrink-0 cursor-pointer"}}))'
            + f',{react_id}.createElement("div",{{className:"flex items-center justify-between w-full py-2 px-3"}},'
            + f'{react_id}.createElement("div",null,'
            + f'{react_id}.createElement("div",{{className:"text-sm"}},"灵动岛实时通知"),'
            + f'{react_id}.createElement("div",{{className:"text-sm text-muted-foreground leading-relaxed"}},"任务运行时在状态栏显示实时进度，结束后停留显示已完成")),'
            + f'{react_id}.createElement("div",{{className:"flex items-center gap-3 shrink-0"}},'
            + f'{react_id}.createElement("button",{{type:"button",onClick:onTaskLiveTest,className:"text-sm px-3 py-1 rounded-md border border-border cursor-pointer"}},"测试灵动岛"),'
            + f'{react_id}.createElement("input",{{type:"checkbox",checked:isTaskLive,onChange:function(e){{onTaskLiveToggle(e.target.checked)}},className:"w-5 h-5 shrink-0 cursor-pointer"}})))'
            + f',{react_id}.createElement("div",{{className:"flex items-center justify-between w-full py-2 px-3"}},'
            + f'{react_id}.createElement("div",null,'
            + f'{react_id}.createElement("div",{{className:"text-sm"}},"后台保活"),'
            + f'{react_id}.createElement("div",{{className:"text-sm text-muted-foreground leading-relaxed"}},"加入电池白名单，任务切到后台不断网、不被系统冻结")),'
            + f'{react_id}.createElement("button",{{type:"button",onClick:onBattOpen,className:"text-sm px-3 py-1 rounded-md border border-border cursor-pointer shrink-0"}},isBattExempt?"已开启":"去开启"))'
        )
    else:
        # Detect switch and setting item components
        # Look for setting item component used with rightElement or contrast setting
        item_comp = "v3"
        switch_comp = "dX"
        re_idx = content.find('rightElement:')
        if re_idx != -1:
            re_window = content[max(0, re_idx - 150):min(len(content), re_idx + 150)]
            item_m = re.search(r'([A-Za-z0-9_$]+)\.createElement\(([A-Za-z0-9_$]+),\{label:[^,]+,description:[^,]+,rightElement:', re_window)
            if item_m:
                item_comp = item_m.group(2)
            sw_m = re.search(r'rightElement:[A-Za-z0-9_$]+\.createElement\(([A-Za-z0-9_$]+),\{checked:', re_window)
            if sw_m:
                switch_comp = sw_m.group(1)

        injected_theme = (
            orig_theme
            + f',{react_id}.createElement({item_comp},{{label:"Desktop Mode",description:"Use desktop viewport and user agent for the workspace",'
            + f'rightElement:{react_id}.createElement({switch_comp},{{checked:isDesktop,onCheckedChange:onDesktopToggle}})}})'
        )

    content = content.replace(orig_hook, injected_hook, 1)
    content = content.replace(orig_theme, injected_theme, 1)

    with open(file_path, "w", encoding="utf-8") as f:
        f.write(content)

    print("[web-patch] Successfully patched Desktop Mode toggle into main.js (mobile by default, English)")
    return True

if __name__ == "__main__":
    path = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(__file__), "main.js")
    if not patch_main(path):
        sys.exit(1)
