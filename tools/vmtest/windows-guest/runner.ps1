# Runs the PowerShell files the host drops in Z:\runner\queue, one at a time, inside the tester's
# desktop session, so whatever they start appears on the desktop and their window calls see it.
# Each N.ps1 is renamed N.run while it runs; its output lands in N.out and its result in N.rc,
# written last: 0, or 1 when the command threw. A command must never call exit, which would end
# this runner, and one that starts something long-lived has to leave it running in the background.
#
# The scheduled task invmgr-runner, which install-runner.ps1 registers, starts this at logon from
# the share, so a new copy takes effect at the next logon or after "schtasks /end /tn invmgr-runner"
# and "/run".

$ErrorActionPreference = 'Stop'
$queue = 'Z:\runner\queue'
$utf8 = New-Object System.Text.UTF8Encoding $false

Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Text;

public static class Win {
    delegate bool EnumProc(IntPtr h, IntPtr l);
    [DllImport("user32.dll")] static extern bool EnumWindows(EnumProc f, IntPtr l);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern int GetClassName(IntPtr h, StringBuilder s, int n);
    [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr h);
    [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] public static extern int GetWindowLong(IntPtr h, int index);
    [DllImport("user32.dll")] public static extern int SetWindowLong(IntPtr h, int index, int value);
    [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr h, IntPtr after, int x, int y, int w, int height, uint flags);
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
    [DllImport("user32.dll")] public static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);
    [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
    [DllImport("user32.dll")] public static extern int GetSystemMetrics(int index);

    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }

    public class Window { public long Handle; public string Title; public string Class; public uint Pid; }

    // Every visible window with a title, top of the stack first.
    public static List<Window> List() {
        var found = new List<Window>();
        EnumWindows((h, l) => {
            if (!IsWindowVisible(h)) return true;
            var title = new StringBuilder(512);
            GetWindowText(h, title, title.Capacity);
            if (title.Length == 0) return true;
            var cls = new StringBuilder(256);
            GetClassName(h, cls, cls.Capacity);
            uint pid;
            GetWindowThreadProcessId(h, out pid);
            found.Add(new Window { Handle = h.ToInt64(), Title = title.ToString(), Class = cls.ToString(), Pid = pid });
            return true;
        }, IntPtr.Zero);
        return found;
    }

    [DllImport("user32.dll")] static extern bool AttachThreadInput(uint a, uint b, bool attach);
    [DllImport("user32.dll")] static extern bool BringWindowToTop(IntPtr h);
    [DllImport("kernel32.dll")] static extern uint GetCurrentThreadId();

    // Windows lets a program bring a window to the front only if it already owns the front one,
    // so this joins the input queue of the window in front for the moment of the call.
    public static bool Front(IntPtr h) {
        uint pid;
        uint front = GetWindowThreadProcessId(GetForegroundWindow(), out pid);
        uint me = GetCurrentThreadId();
        bool joined = front != me && AttachThreadInput(me, front, true);
        BringWindowToTop(h);
        bool done = SetForegroundWindow(h);
        if (joined) AttachThreadInput(me, front, false);
        return done;
    }

    [DllImport("user32.dll")] static extern bool EnumChildWindows(IntPtr parent, EnumProc f, IntPtr l);

    // The text of every visible control inside a window: a setup wizard's buttons, for one.
    public static List<string> Controls(IntPtr parent) {
        var found = new List<string>();
        EnumChildWindows(parent, (h, l) => {
            var text = new StringBuilder(512);
            GetWindowText(h, text, text.Capacity);
            if (IsWindowVisible(h) && text.Length > 0) found.Add(text.ToString());
            return true;
        }, IntPtr.Zero);
        return found;
    }

    // Takes the window's title bar and frame away and makes it cover the whole screen, the way a
    // window manager's full screen does on Linux. The taskbar steps back for a window that covers
    // the screen while it is in front.
    public static void FillScreen(IntPtr h) {
        const int GWL_STYLE = -16;
        const int WS_CAPTION = 0x00C00000, WS_THICKFRAME = 0x00040000;
        const int WS_MAXIMIZE = 0x01000000;
        int style = GetWindowLong(h, GWL_STYLE) & ~(WS_CAPTION | WS_THICKFRAME | WS_MAXIMIZE);
        SetWindowLong(h, GWL_STYLE, style);
        // SWP_FRAMECHANGED | SWP_SHOWWINDOW
        SetWindowPos(h, IntPtr.Zero, 0, 0, GetSystemMetrics(0), GetSystemMetrics(1), 0x0020 | 0x0040);
        Front(h);
    }
}
'@
[Win]::SetProcessDPIAware() | Out-Null

# A file call on the share can fail once and work a moment later, so each one is retried. One that
# still fails is logged to runner\errors.log and reported as that command's result, rather than
# ending the runner, which would leave every later command waiting for nothing.
function Retry([scriptblock]$step) {
    for ($i = 1; ; $i++) {
        try { return & $step } catch { if ($i -ge 20) { throw }; Start-Sleep -Milliseconds 100 }
    }
}

function Write-Log($what) {
    try { Add-Content -LiteralPath 'Z:\runner\errors.log' -Value ('{0:s} {1}' -f (Get-Date), $what) } catch { }
}

while (-not (Test-Path $queue)) { Start-Sleep -Seconds 1 }
while ($true) {
    try {
        $files = @(Get-ChildItem -Path $queue -Filter *.ps1)
    } catch {
        Write-Log "listing the queue: $_"
        $files = @()
    }
    foreach ($f in $files) {
        $base = $f.FullName.Substring(0, $f.FullName.Length - 4)
        try {
            Retry { Move-Item -LiteralPath $f.FullName -Destination "$base.run" }
            $text = Retry { [IO.File]::ReadAllText("$base.run", $utf8) }
            $rc = 0
            try {
                $out = & ([scriptblock]::Create($text)) 2>&1 | Out-String -Width 4096
            } catch {
                $out = ($_ | Out-String -Width 4096)
                $rc = 1
            }
            Retry { [IO.File]::WriteAllText("$base.out", [string]$out, $utf8) }
            Retry { [IO.File]::WriteAllText("$base.rc.part", "$rc", $utf8) }
            Retry { Move-Item -LiteralPath "$base.rc.part" -Destination "$base.rc" }
        } catch {
            Write-Log "$($f.Name): $_"
            try {
                [IO.File]::WriteAllText("$base.out", "runner: $_", $utf8)
                [IO.File]::WriteAllText("$base.rc", "1", $utf8)
            } catch { }
        }
    }
    Start-Sleep -Milliseconds 50
}
