using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Interop;

namespace RepoNotes.Services;

/// <summary>
/// Sets Windows 10/11 native immersive dark mode on the title bar, caption, and window borders,
/// and handles single-instance window activation.
/// </summary>
public static class Native
{
    [DllImport("user32.dll", SetLastError = true, CharSet = CharSet.Auto)]
    public static extern uint RegisterWindowMessage(string lpString);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static extern bool PostMessage(IntPtr hWnd, uint Msg, IntPtr wParam, IntPtr lParam);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static extern bool AllowSetForegroundWindow(uint dwProcessId);

    public static readonly uint WM_ACTIVATE_INSTANCE = RegisterWindowMessage("xRepoNotes_SingleInstance_Msg_9832");
    public static readonly IntPtr HWND_BROADCAST = new IntPtr(0xFFFF);

    [DllImport("shell32.dll", SetLastError = true)]
    public static extern int SetCurrentProcessExplicitAppUserModelID([MarshalAs(UnmanagedType.LPWStr)] string AppID);

    public const string AppUserModelId = "RepoNotes.xRepoNotes";

    private const int WM_SETICON = 0x0080;
    private const int ICON_SMALL = 0;
    private const int ICON_BIG = 1;
    private const int GCLP_HICON = -14;
    private const int GCLP_HICONSM = -34;

    [DllImport("shell32.dll", CharSet = CharSet.Auto)]
    private static extern int ExtractIconEx(string lpszFile, int nIconIndex, out IntPtr phiconLarge, out IntPtr phiconSmall, int nIcons);

    [DllImport("user32.dll", CharSet = CharSet.Auto)]
    private static extern IntPtr SendMessage(IntPtr hWnd, uint Msg, IntPtr wParam, IntPtr lParam);

    public static void ApplyWindowIcons(IntPtr hwnd)
    {
        if (hwnd == IntPtr.Zero) return;
        try
        {
            string? exe = Environment.ProcessPath;
            if (!string.IsNullOrEmpty(exe) && File.Exists(exe))
            {
                if (ExtractIconEx(exe, 0, out IntPtr hLarge, out IntPtr hSmall, 1) > 0)
                {
                    if (hSmall != IntPtr.Zero)
                    {
                        SendMessage(hwnd, WM_SETICON, (IntPtr)ICON_SMALL, hSmall);
                        SetClassLongPtr(hwnd, GCLP_HICONSM, hSmall);
                    }
                    if (hLarge != IntPtr.Zero)
                    {
                        SendMessage(hwnd, WM_SETICON, (IntPtr)ICON_BIG, hLarge);
                        SetClassLongPtr(hwnd, GCLP_HICON, hLarge);
                    }
                }
            }
        }
        catch { }
    }

    public static void SignalExistingInstance()
    {
        try
        {
            AllowSetForegroundWindow(0xFFFFFFFF); // ASFW_ANY
        }
        catch { }
        PostMessage(HWND_BROADCAST, WM_ACTIVATE_INSTANCE, IntPtr.Zero, IntPtr.Zero);
    }

    [DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int value, int size);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetForegroundWindow(IntPtr hWnd);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);

    [DllImport("user32.dll")]
    private static extern bool EnumWindows(EnumWindowsProc lpEnumFunc, IntPtr lParam);

    [DllImport("user32.dll")]
    private static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint lpdwProcessId);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool IsWindowVisible(IntPtr hWnd);

    private delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool IsIconic(IntPtr hWnd);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool BringWindowToTop(IntPtr hWnd);

    [StructLayout(LayoutKind.Sequential)]
    private struct POINT { public int X; public int Y; }

    [StructLayout(LayoutKind.Sequential)]
    private struct RECT { public int Left; public int Top; public int Right; public int Bottom; }

    [StructLayout(LayoutKind.Sequential)]
    private struct WINDOWPLACEMENT
    {
        public int length;
        public int flags;
        public int showCmd;
        public POINT ptMinPosition;
        public POINT ptMaxPosition;
        public RECT rcNormalPosition;
    }

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetWindowPlacement(IntPtr hWnd, ref WINDOWPLACEMENT lpwndpl);

    private const int SW_SHOW = 5;
    private const int SW_SHOWMAXIMIZED = 3;
    private const int SW_RESTORE = 9;
    private const int WPF_RESTORETOMAXIMIZED = 2;

    private const int DWMWA_CLOAK = 13;
    private const int DWMWA_USE_IMMERSIVE_DARK_MODE_OLD = 19;
    private const int DWMWA_USE_IMMERSIVE_DARK_MODE = 20;
    private const int DWMWA_BORDER_COLOR = 34;
    private const int DWMWA_CAPTION_COLOR = 35;
    private const int DWMWA_TEXT_COLOR = 36;

    public static void ActivateProcess(Process process)
    {
        IntPtr hwnd = process.MainWindowHandle;
        if (hwnd == IntPtr.Zero)
        {
            uint pid = (uint)process.Id;
            EnumWindows((h, _) =>
            {
                GetWindowThreadProcessId(h, out uint windowPid);
                if (windowPid == pid && IsWindowVisible(h))
                {
                    hwnd = h;
                    return false;
                }
                return true;
            }, IntPtr.Zero);
        }

        if (hwnd != IntPtr.Zero)
        {
            if (IsIconic(hwnd))
            {
                var wp = new WINDOWPLACEMENT { length = Marshal.SizeOf<WINDOWPLACEMENT>() };
                if (GetWindowPlacement(hwnd, ref wp) &&
                    ((wp.flags & WPF_RESTORETOMAXIMIZED) != 0 || wp.showCmd == SW_SHOWMAXIMIZED))
                {
                    ShowWindow(hwnd, SW_SHOWMAXIMIZED);
                }
                else
                {
                    ShowWindow(hwnd, SW_RESTORE);
                }
            }
            else
            {
                // Window is already open and not minimized (e.g. behind another window)
                // Bring it to view without changing its maximized/restored state
                ShowWindow(hwnd, SW_SHOW);
            }
            BringWindowToTop(hwnd);
            SetForegroundWindow(hwnd);
        }
    }

    [DllImport("gdi32.dll")]
    private static extern IntPtr CreateSolidBrush(int crColor);

    [DllImport("user32.dll", EntryPoint = "SetClassLongPtr")]
    private static extern IntPtr SetClassLongPtr64(IntPtr hWnd, int nIndex, IntPtr dwNewLong);

    [DllImport("user32.dll", EntryPoint = "SetClassLong")]
    private static extern int SetClassLong32(IntPtr hWnd, int nIndex, int dwNewLong);

    private const int GCLP_HBRBACKGROUND = -10;

    private static IntPtr SetClassLongPtr(IntPtr hWnd, int nIndex, IntPtr dwNewLong)
    {
        if (IntPtr.Size == 8)
            return SetClassLongPtr64(hWnd, nIndex, dwNewLong);
        return new IntPtr(SetClassLong32(hWnd, nIndex, dwNewLong.ToInt32()));
    }

    public static void Attach(Window w, bool cloakUntilRendered = true, Action? onActivateInstance = null)
    {
        w.SourceInitialized += (_, _) =>
        {
            try
            {
                var h = new WindowInteropHelper(w).Handle;
                if (h == IntPtr.Zero) return;

                // Cloaking is optional because modal/owned windows must never remain invisible.
                if (cloakUntilRendered)
                {
                    int cloak = 1;
                    DwmSetWindowAttribute(h, DWMWA_CLOAK, ref cloak, sizeof(int));
                }

                int trueVal = 1;
                // Try modern immersive dark mode (build 19041+) then fallback
                DwmSetWindowAttribute(h, DWMWA_USE_IMMERSIVE_DARK_MODE, ref trueVal, sizeof(int));
                DwmSetWindowAttribute(h, DWMWA_USE_IMMERSIVE_DARK_MODE_OLD, ref trueVal, sizeof(int));

                // Dark title bar caption, text, and border colors (Windows 11)
                int captionColor = Rgb(0x0F, 0x11, 0x15);
                int textColor = Rgb(0xE6, 0xE9, 0xEE);
                int borderColor = Rgb(0x2F, 0x36, 0x40);

                DwmSetWindowAttribute(h, DWMWA_CAPTION_COLOR, ref captionColor, sizeof(int));
                DwmSetWindowAttribute(h, DWMWA_TEXT_COLOR, ref textColor, sizeof(int));
                DwmSetWindowAttribute(h, DWMWA_BORDER_COLOR, ref borderColor, sizeof(int));

                // Replace the default white Win32 class background brush with dark brush
                IntPtr darkBrush = CreateSolidBrush(captionColor);
                SetClassLongPtr(h, GCLP_HBRBACKGROUND, darkBrush);

                // Explicitly bind the taskbar/window icons to avoid default Windows fallback icon
                ApplyWindowIcons(h);

                // Intercept WM_ERASEBKGND and handle single-instance activation broadcast
                var source = HwndSource.FromHwnd(h);
                source?.AddHook((IntPtr hwnd, int msg, IntPtr wParam, IntPtr lParam, ref bool handled) =>
                {
                    if (msg == 0x0014) // WM_ERASEBKGND
                    {
                        handled = true;
                        return (IntPtr)1;
                    }
                    if ((uint)msg == WM_ACTIVATE_INSTANCE)
                    {
                        w.Dispatcher.BeginInvoke(System.Windows.Threading.DispatcherPriority.Normal, () =>
                        {
                            Uncloak(hwnd);
                            w.Opacity = 1;
                            var wp = new WINDOWPLACEMENT { length = Marshal.SizeOf<WINDOWPLACEMENT>() };
                            bool wasMax = (GetWindowPlacement(hwnd, ref wp) &&
                                          ((wp.flags & WPF_RESTORETOMAXIMIZED) != 0 || wp.showCmd == SW_SHOWMAXIMIZED));

                            if (w.WindowState == WindowState.Minimized)
                            {
                                w.WindowState = wasMax ? WindowState.Maximized : WindowState.Normal;
                            }
                            w.Show();
                            w.Topmost = true;
                            w.Topmost = false;
                            w.Activate();
                            w.Focus();
                            onActivateInstance?.Invoke();
                        });
                        handled = true;
                        return (IntPtr)1;
                    }
                    return IntPtr.Zero;
                });
            }
            catch
            {
                // Non-critical API on older Windows versions
            }
        };

        // Uncloak as soon as the first frame is fully rendered and committed
        w.ContentRendered += (_, _) =>
        {
            var h = new WindowInteropHelper(w).Handle;
            ApplyWindowIcons(h);
            Uncloak(h);
            w.Opacity = 1;
        };

        // Fallback: ensure uncloaked on Loaded
        w.Loaded += (_, _) =>
        {
            w.Dispatcher.BeginInvoke(System.Windows.Threading.DispatcherPriority.Loaded, () =>
            {
                var h = new WindowInteropHelper(w).Handle;
                ApplyWindowIcons(h);
                Uncloak(h);
                w.Opacity = 1;
            });
        };
    }

    public static void Uncloak(IntPtr hwnd)
    {
        if (hwnd == IntPtr.Zero) return;
        try
        {
            int uncloak = 0;
            DwmSetWindowAttribute(hwnd, DWMWA_CLOAK, ref uncloak, sizeof(int));
        }
        catch { }
    }

    private static IntPtr WndProc(IntPtr hwnd, int msg, IntPtr wParam, IntPtr lParam, ref bool handled)
    {
        if (msg == 0x0014) // WM_ERASEBKGND
        {
            handled = true;
            return (IntPtr)1;
        }
        return IntPtr.Zero;
    }

    private static int Rgb(int r, int g, int b) => r | (g << 8) | (b << 16);
}
