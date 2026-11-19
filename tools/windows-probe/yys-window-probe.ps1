# Checks whether one game window can be captured and clicked in the background on Windows.
# Installs nothing and changes no system setting. Every click needs the user's typed confirmation.
# Results stay in the output folder on this computer.

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

Add-Type -ReferencedAssemblies System.Drawing -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;

public class WindowInfo {
    public long Handle; public int ProcessId; public string Title; public int Width; public int Height; public int Dpi;
}

public class Sample {
    public double Mean; public double Deviation; public bool Blank;
}

public static class Probe {
    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
    [StructLayout(LayoutKind.Sequential)] public struct POINT { public int X, Y; }
    delegate bool EnumProc(IntPtr hwnd, IntPtr data);

    [DllImport("user32.dll")] static extern bool EnumWindows(EnumProc proc, IntPtr data);
    [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr hwnd);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern int GetWindowTextW(IntPtr hwnd, StringBuilder text, int size);
    [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr hwnd, out uint pid);
    [DllImport("user32.dll")] static extern bool GetClientRect(IntPtr hwnd, out RECT rect);
    [DllImport("user32.dll")] static extern bool ClientToScreen(IntPtr hwnd, ref POINT point);
    [DllImport("user32.dll")] static extern bool ScreenToClient(IntPtr hwnd, ref POINT point);
    [DllImport("user32.dll")] static extern bool GetCursorPos(out POINT point);
    [DllImport("user32.dll")] static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] static extern uint GetDpiForWindow(IntPtr hwnd);
    [DllImport("user32.dll")] static extern bool IsIconic(IntPtr hwnd);
    [DllImport("user32.dll")] static extern bool SetProcessDpiAwarenessContext(IntPtr value);
    [DllImport("user32.dll")] static extern IntPtr GetDC(IntPtr hwnd);
    [DllImport("user32.dll")] static extern int ReleaseDC(IntPtr hwnd, IntPtr dc);
    [DllImport("user32.dll")] static extern bool PrintWindow(IntPtr hwnd, IntPtr dc, uint flags);
    [DllImport("gdi32.dll")] static extern bool BitBlt(IntPtr dest, int x, int y, int w, int h, IntPtr src, int sx, int sy, uint op);
    [DllImport("user32.dll", SetLastError = true)] static extern bool PostMessageW(IntPtr hwnd, uint msg, IntPtr wParam, IntPtr lParam);
    [DllImport("user32.dll", SetLastError = true)] static extern IntPtr SendMessageTimeoutW(IntPtr hwnd, uint msg, IntPtr wParam, IntPtr lParam, uint flags, uint timeout, out IntPtr result);

    const uint WM_MOUSEMOVE = 0x0200, WM_LBUTTONDOWN = 0x0201, WM_LBUTTONUP = 0x0202;
    const uint SRCCOPY = 0x00CC0020, PW_CLIENTONLY = 1, PW_RENDERFULLCONTENT = 2;

    public static void UsePhysicalPixels() { SetProcessDpiAwarenessContext(new IntPtr(-4)); }

    public static List<WindowInfo> Windows() {
        var found = new List<WindowInfo>();
        EnumWindows(delegate (IntPtr hwnd, IntPtr data) {
            if (!IsWindowVisible(hwnd)) return true;
            var text = new StringBuilder(256);
            GetWindowTextW(hwnd, text, text.Capacity);
            if (text.Length == 0) return true;
            uint pid; GetWindowThreadProcessId(hwnd, out pid);
            RECT rect; GetClientRect(hwnd, out rect);
            found.Add(new WindowInfo { Handle = hwnd.ToInt64(), ProcessId = (int)pid, Title = text.ToString(),
                Width = rect.Right, Height = rect.Bottom, Dpi = (int)GetDpiForWindow(hwnd) });
            return true;
        }, IntPtr.Zero);
        return found;
    }

    public static bool Minimized(long hwnd) { return IsIconic(new IntPtr(hwnd)); }
    public static long Foreground() { return GetForegroundWindow().ToInt64(); }
    public static string Cursor() { POINT p; GetCursorPos(out p); return p.X + "," + p.Y; }

    /// <summary>The cursor position inside the window's client area, or null when it is outside.</summary>
    public static int[] CursorInClient(long hwnd) {
        POINT p; GetCursorPos(out p);
        var handle = new IntPtr(hwnd);
        ScreenToClient(handle, ref p);
        RECT rect; GetClientRect(handle, out rect);
        if (p.X < 0 || p.Y < 0 || p.X >= rect.Right || p.Y >= rect.Bottom) return null;
        return new int[] { p.X, p.Y };
    }

    /// <summary>method: "print" uses PrintWindow, "blt" copies from the window's device context.</summary>
    public static Bitmap Capture(long hwnd, string method) {
        var handle = new IntPtr(hwnd);
        RECT rect; GetClientRect(handle, out rect);
        if (rect.Right <= 0 || rect.Bottom <= 0) return null;
        var image = new Bitmap(rect.Right, rect.Bottom, PixelFormat.Format32bppArgb);
        using (var graphics = Graphics.FromImage(image)) {
            var target = graphics.GetHdc();
            bool done;
            if (method == "print") done = PrintWindow(handle, target, PW_CLIENTONLY | PW_RENDERFULLCONTENT);
            else {
                var source = GetDC(handle);
                done = BitBlt(target, 0, 0, rect.Right, rect.Bottom, source, 0, 0, SRCCOPY);
                ReleaseDC(handle, source);
            }
            graphics.ReleaseHdc(target);
            if (!done) { image.Dispose(); return null; }
        }
        return image;
    }

    static double Light(Color c) { return 0.299 * c.R + 0.587 * c.G + 0.114 * c.B; }

    public static Sample Measure(Bitmap image) {
        int stepX = Math.Max(1, image.Width / 96), stepY = Math.Max(1, image.Height / 54), count = 0;
        double sum = 0, squares = 0;
        for (int y = stepY / 2; y < image.Height; y += stepY)
            for (int x = stepX / 2; x < image.Width; x += stepX) {
                double value = Light(image.GetPixel(x, y));
                sum += value; squares += value * value; count++;
            }
        double mean = sum / count, deviation = Math.Sqrt(Math.Max(0, squares / count - mean * mean));
        return new Sample { Mean = Math.Round(mean, 1), Deviation = Math.Round(deviation, 1), Blank = deviation < 3 };
    }

    /// <summary>Share of sampled pixels that visibly changed between two captures, from 0 to 100.</summary>
    public static double Changed(Bitmap a, Bitmap b) {
        if (a.Width != b.Width || a.Height != b.Height) return 100;
        int stepX = Math.Max(1, a.Width / 192), stepY = Math.Max(1, a.Height / 108), count = 0, changed = 0;
        for (int y = stepY / 2; y < a.Height; y += stepY)
            for (int x = stepX / 2; x < a.Width; x += stepX) {
                count++;
                if (Math.Abs(Light(a.GetPixel(x, y)) - Light(b.GetPixel(x, y))) > 24) changed++;
            }
        return Math.Round(100.0 * changed / count, 2);
    }

    static IntPtr Position(int x, int y) { return new IntPtr((y << 16) | (x & 0xFFFF)); }

    /// <summary>Sends one left click as window messages. The real cursor is never moved.</summary>
    public static string Click(long hwnd, int x, int y, string method) {
        var handle = new IntPtr(hwnd);
        var at = Position(x, y);
        var pressed = new IntPtr(1);
        uint[] messages = { WM_MOUSEMOVE, WM_LBUTTONDOWN, WM_LBUTTONUP };
        IntPtr[] states = { IntPtr.Zero, pressed, IntPtr.Zero };
        for (int i = 0; i < messages.Length; i++) {
            bool accepted;
            if (method == "post") accepted = PostMessageW(handle, messages[i], states[i], at);
            else { IntPtr result; accepted = SendMessageTimeoutW(handle, messages[i], states[i], at, 2, 2000, out result) != IntPtr.Zero; }
            if (!accepted) return "rejected:" + Marshal.GetLastWin32Error();
            Thread.Sleep(i == 1 ? 90 : 30);
        }
        return "sent";
    }
}
'@

[Probe]::UsePhysicalPixels()
$output = Join-Path ([Environment]::GetFolderPath('Desktop')) 'yys-window-probe'
New-Item -ItemType Directory -Force -Path $output | Out-Null
$reportPath = Join-Path $output 'report.txt'
Set-Content -Path $reportPath -Value '' -Encoding UTF8

function Log([string]$text) { Write-Host $text; Add-Content -Path $reportPath -Value $text -Encoding UTF8 }
function Ask([string]$text) { return (Read-Host $text).Trim() }
function Pause-Step([string]$text) { [void](Read-Host "$text，完成后按回车") }

function Test-Capture([long]$hwnd, [string]$state) {
    foreach ($method in 'print', 'blt') {
        try {
            $image = [Probe]::Capture($hwnd, $method)
            if ($null -eq $image) { Log "  $state / $method：失败"; continue }
            $sample = [Probe]::Measure($image)
            $verdict = if ($sample.Blank) { '空白' } else { '有画面' }
            Log "  $state / $method：$verdict（亮度 $($sample.Mean)，起伏 $($sample.Deviation)，$($image.Width)x$($image.Height)）"
            $image.Save((Join-Path $output "capture-$state-$method.png"), [System.Drawing.Imaging.ImageFormat]::Png)
            $image.Dispose()
        } catch { Log "  $state / $method：出错 $($_.Exception.Message)" }
    }
}

function Find-GameWindows {
    [Probe]::Windows() | Where-Object {
        $name = try { (Get-Process -Id $_.ProcessId -ErrorAction Stop).ProcessName } catch { '' }
        $_.Title -match '阴阳师|Onmyoji' -or $name -match 'onmyoji'
    }
}

$identity = [Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
$isAdmin = $identity.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
$os = Get-CimInstance Win32_OperatingSystem
$cpu = Get-CimInstance Win32_Processor | Select-Object -First 1

Log '=== 阴阳师窗口验证报告 ==='
Log "时间：$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
Log "系统：$($os.Caption) $($os.Version)"
Log "CPU：$($cpu.Name)，$($cpu.NumberOfCores) 核 $($cpu.NumberOfLogicalProcessors) 线程"
Log "内存：$([math]::Round($os.TotalVisibleMemorySize / 1MB, 1)) GB"
Log "管理员运行：$isAdmin"
Log ''

Pause-Step '请打开阴阳师桌面端并进入庭院'
$games = @(Find-GameWindows)
if ($games.Count -eq 0) { Log '没有找到阴阳师窗口，验证结束。'; return }

Log "--- 1. 找到 $($games.Count) 个游戏窗口 ---"
for ($i = 0; $i -lt $games.Count; $i++) {
    $g = $games[$i]
    $process = try { (Get-Process -Id $g.ProcessId -ErrorAction Stop).ProcessName } catch { '未知' }
    Log "  [$($i + 1)] 进程 $process，画面 $($g.Width)x$($g.Height)，DPI $($g.Dpi)"
}
$index = 0
if ($games.Count -gt 1) { $index = [int](Ask '要测试第几个窗口？输入序号') - 1 }
$game = $games[$index]
$hwnd = [long]$game.Handle

Log ''
Log '--- 2. 后台截图 ---'
Test-Capture $hwnd '前台'
Pause-Step '请用其他窗口完全挡住游戏窗口'
Test-Capture $hwnd '被遮挡'
Pause-Step '请把游戏窗口最小化'
if ([Probe]::Minimized($hwnd)) { Test-Capture $hwnd '最小化' } else { Log '  最小化：窗口没有处于最小化状态，已跳过' }
Pause-Step '请恢复游戏窗口并回到庭院'

Log ''
Log '--- 3. 后台点击 ---'
Write-Host '接下来会向游戏发送一次点击。请选一个只打开界面的按钮，例如好友或式神录。' -ForegroundColor Yellow
Write-Host '不要选商店、召唤、购买、充值，也不要选任何会消耗资源的按钮。' -ForegroundColor Yellow
if ((Ask '要继续测试后台点击吗？输入 yes 继续') -ne 'yes') { Log '  用户跳过了点击测试' }
else {
    Pause-Step '请把鼠标移到要点击的按钮上，不要点击'
    $point = [Probe]::CursorInClient($hwnd)
    if ($null -eq $point) { Log '  鼠标不在游戏窗口内，已跳过点击测试' }
    else {
        Log "  点击位置：$($point[0]),$($point[1])"
        Pause-Step '请把鼠标移开，并点一下其他窗口让游戏处于后台'
        foreach ($method in 'post', 'send') {
            if ((Ask "发送一次 $method 点击？输入 yes 发送，其他内容跳过") -ne 'yes') { Log "  $method：用户跳过"; continue }
            $cursorBefore = [Probe]::Cursor(); $focusBefore = [Probe]::Foreground()
            $before = [Probe]::Capture($hwnd, 'print')
            $sent = [Probe]::Click($hwnd, $point[0], $point[1], $method)
            Start-Sleep -Milliseconds 1800
            $after = [Probe]::Capture($hwnd, 'print')
            $change = if ($before -and $after) { [Probe]::Changed($before, $after) } else { '无法比较' }
            $cursorKept = $cursorBefore -eq [Probe]::Cursor()
            $focusKept = $focusBefore -eq [Probe]::Foreground()
            $answer = Ask '游戏有没有响应这次点击？输入 y 或 n'
            Log "  $method：发送结果 $sent，画面变化 $change%，鼠标未移动 $cursorKept，焦点未改变 $focusKept，游戏响应 $answer"
            if ($after) { $after.Save((Join-Path $output "click-$method-after.png"), [System.Drawing.Imaging.ImageFormat]::Png) }
            if ($before) { $before.Dispose() }; if ($after) { $after.Dispose() }
            if ($answer -eq 'y') { break }
            Pause-Step '如果界面有变化，请先手动回到庭院'
        }
    }
}

Log ''
Log '--- 4. 多开 ---'
if ((Ask '要试试打开第二个客户端吗？需要你手动打开，输入 yes 继续') -eq 'yes') {
    Pause-Step '请手动再打开一个阴阳师客户端'
    Log "  现在找到 $(@(Find-GameWindows).Count) 个游戏窗口"
    Log "  用户观察：$(Ask '第二个客户端能正常打开吗？请简单描述')"
} else { Log '  用户跳过了多开测试' }

Log ''
Log '验证结束。'
Write-Host ''
Write-Host "报告在：$reportPath" -ForegroundColor Green
Write-Host '把 report.txt 的内容发给我就行。截图只留在这台电脑上，不用发送。' -ForegroundColor Green
