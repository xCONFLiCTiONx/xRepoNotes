using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using RepoNotes.Services;

namespace RepoNotes;

/// <summary>Themed replacement for MessageBox (which is always light).</summary>
public sealed class DarkDialog : Window
{
    private DarkDialog(string title, string message, string ok, string? cancel)
    {
        Title = title;
        Background = (Brush)Application.Current.FindResource("Bg0");
        Foreground = (Brush)Application.Current.FindResource("Text");
        FontFamily = new FontFamily("Segoe UI");
        FontSize = 13;
        SizeToContent = SizeToContent.WidthAndHeight;
        MaxWidth = 520; MinWidth = 380;
        ResizeMode = ResizeMode.NoResize;
        ShowInTaskbar = false;
        WindowStartupLocation = WindowStartupLocation.CenterOwner;
        Native.Attach(this);

        var buttons = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right, Margin = new Thickness(0, 22, 0, 0) };
        var okBtn = new Button { Content = ok, Style = (Style)FindResource("PrimaryButton"), MinWidth = 84, IsDefault = true };
        okBtn.Click += (_, _) => DialogResult = true;
        if (cancel != null)
        {
            var c = new Button { Content = cancel, MinWidth = 84, Margin = new Thickness(0, 0, 8, 0), IsCancel = true };
            c.Click += (_, _) => DialogResult = false;
            buttons.Children.Add(c);
        }
        buttons.Children.Add(okBtn);

        Content = new StackPanel
        {
            Margin = new Thickness(24),
            Children =
            {
                new TextBlock { Text = title, FontSize = 17, FontWeight = FontWeights.SemiBold, Margin = new Thickness(0, 0, 0, 10) },
                new TextBlock { Text = message, TextWrapping = TextWrapping.Wrap, Foreground = (Brush)FindResource("TextDim") },
                buttons
            }
        };
    }

    public static bool Show(Window? owner, string title, string message, string ok = "OK", string? cancel = null)
    {
        var d = new DarkDialog(title, message, ok, cancel);
        if (owner is { IsLoaded: true })
        {
            d.Owner = owner;
            if (owner.Icon != null) d.Icon = owner.Icon;
        }
        else d.WindowStartupLocation = WindowStartupLocation.CenterScreen;
        return d.ShowDialog() == true;
    }
}

