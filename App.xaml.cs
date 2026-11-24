using System.Diagnostics;
using System.IO;
using System.Threading;
using System.Windows;
using System.Windows.Threading;
using RepoNotes.Services;

namespace RepoNotes;

public partial class App : Application
{
    private static Mutex? _singleInstanceMutex;
    private static bool _ownsMutex;

    /// <summary>Database opens on a worker thread while the window is being built.</summary>
    public static Task<Database> DbTask { get; private set; } = null!;

    protected override void OnStartup(StartupEventArgs e)
    {
        try
        {
            Native.SetCurrentProcessExplicitAppUserModelID(Native.AppUserModelId);
        }
        catch { }

        bool createdNew = false;
        try
        {
            _singleInstanceMutex = new Mutex(true, @"Local\xRepoNotes_SingleInstance_Mutex_9832", out createdNew);
            _ownsMutex = createdNew;
        }
        catch (AbandonedMutexException)
        {
            // Previous instance crashed or was killed; current thread now acquired the mutex
            createdNew = true;
            _ownsMutex = true;
        }
        catch
        {
            createdNew = false;
            _ownsMutex = false;
        }

        if (!createdNew)
        {
            // Signal running instance to bring itself forward and restore
            Native.SignalExistingInstance();

            // Terminate second instance immediately without hanging
            Environment.Exit(0);
            return;
        }

        DispatcherUnhandledException += OnUnhandled;
        base.OnStartup(e);

        var db = new Database();
        DbTask = Task.FromResult(db);

        var win = new MainWindow(db);
        win.Show();
    }

    protected override void OnExit(ExitEventArgs e)
    {
        if (_singleInstanceMutex != null && _ownsMutex)
        {
            try { _singleInstanceMutex.ReleaseMutex(); } catch { }
            _singleInstanceMutex.Dispose();
            _singleInstanceMutex = null;
        }
        base.OnExit(e);
    }

    private void OnUnhandled(object sender, DispatcherUnhandledExceptionEventArgs e)
    {
        e.Handled = true;
        DarkDialog.Show(MainWindow, "Something went wrong", e.Exception.Message);
    }
}

