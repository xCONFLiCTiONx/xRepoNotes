using System.Globalization;
using System.Windows;
using System.Windows.Data;
using System.Windows.Documents;
using RepoNotes.Services;

namespace RepoNotes.Converters;

public class MarkdownToFlowDocumentConverter : IValueConverter
{
    public object? Convert(object? value, Type targetType, object? parameter, CultureInfo culture)
    {
        var text = value as string ?? "";
        return MarkdownRenderer.Render(text, compactImages: true);
    }

    public object ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture)
    {
        throw new NotImplementedException();
    }
}

public class InverseBoolToVisConverter : IValueConverter
{
    public object Convert(object? value, Type targetType, object? parameter, CultureInfo culture)
    {
        bool b = value is bool flag && flag;
        return b ? Visibility.Collapsed : Visibility.Visible;
    }

    public object ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture)
    {
        if (value is Visibility vis) return vis != Visibility.Visible;
        return true;
    }
}
