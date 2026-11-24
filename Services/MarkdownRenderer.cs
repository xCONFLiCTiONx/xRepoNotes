using System.Diagnostics;
using System.Text.RegularExpressions;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Documents;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace RepoNotes.Services;

/// <summary>
/// Full-featured dependency-free Markdown → FlowDocument renderer for the live preview.
/// Supports headings (ATX and Setext), bold, italic, bold-italic, strikethrough, inline code,
/// fenced code blocks, blockquotes, lists, task lists, tables, horizontal rules, and links.
/// </summary>
public static class MarkdownRenderer
{
    private static readonly Brush Fg = Frozen("#E6E9EE");
    private static readonly Brush Dim = Frozen("#9AA4B2");
    private static readonly Brush CodeBg = Frozen("#0F1115");
    private static readonly Brush CodeFg = Frozen("#F2B880");
    private static readonly Brush Accent = Frozen("#6AA0FF");
    private static readonly Brush BorderColor = Frozen("#2F3640");
    private static readonly Brush TableHeaderBg = Frozen("#1E232B");
    private static readonly FontFamily Mono = new("Consolas");
    private static readonly FontFamily MainFont = new("Segoe UI, -apple-system, BlinkMacSystemFont, Roboto, sans-serif");

    private static Brush Frozen(string hex)
    {
        var b = (SolidColorBrush)new BrushConverter().ConvertFromString(hex)!;
        b.Freeze();
        return b;
    }

    private static readonly Regex Heading = new(@"^\s{0,3}(#{1,6})\s+(.*?)\s*#*\s*$", RegexOptions.Compiled);
    private static readonly Regex Rule = new(@"^\s*([-*_])(\s*\1){2,}\s*$", RegexOptions.Compiled);
    private static readonly Regex Bullet = new(@"^(\s*)[-*+]\s+(?:\[([ xX])\]\s+)?(.*)$", RegexOptions.Compiled);
    private static readonly Regex Numbered = new(@"^(\s*)(\d+[.)])\s+(.*)$", RegexOptions.Compiled);
    private static readonly Regex ImageLine = new(@"^!\[(?<alt>[^\]]*)\]\(xreponotes-image://(?<id>[a-zA-Z0-9_-]+)\)$", RegexOptions.Compiled);

    private static readonly Regex Inline = new(
        @"(?<bolditalic>\*\*\*(?<bitext>.+?)\*\*\*|___(?<bitext>.+?)___)" +
        @"|(?<bold>\*\*(?<btext>.+?)\*\*|__(?<btext>.+?)__)" +
        @"|(?<italic>\*(?!\s)(?<itext>.+?)(?<!\s)\*|(?<!\w)_(?!\s)(?<itext>.+?)(?<!\s)_(?!\w))" +
        @"|(?<strike>~~(?<stext>.+?)~~)" +
        @"|(?<code>`(?<ctext>[^`]+)`)" +
        @"|(?<image>!\[(?<imgalt>[^\]]*)\]\((?<imgurl>[^\s\)]+)\))" +
        @"|(?<link>\[(?<linktext>[^\]]*)\]\((?<linkurl>[^\s\)]+)\))" +
        @"|(?<autolink><(?<autourl>https?:\/\/[^\s>]+)>|(?<autourl2>https?:\/\/[^\s<>\)\]]+))",
        RegexOptions.Compiled);

    public static FlowDocument Render(string markdown, Thickness? pagePadding = null, bool compactImages = false)
    {
        var doc = new FlowDocument
        {
            FontFamily = MainFont,
            FontSize = 13,
            Foreground = Fg,
            PagePadding = pagePadding ?? new Thickness(10, 8, 10, 10),
            LineHeight = 22,
            TextAlignment = TextAlignment.Left,
            IsOptimalParagraphEnabled = false
        };

        var lines = (markdown ?? "").Replace("\r\n", "\n").Split('\n');
        Paragraph? para = null;
        void Flush() { if (para != null) { doc.Blocks.Add(para); para = null; } }

        for (int i = 0; i < lines.Length; i++)
        {
            var line = lines[i];

            // 1. Fenced Code Blocks (``` or ~~~)
            if (line.TrimStart().StartsWith("```") || line.TrimStart().StartsWith("~~~"))
            {
                Flush();
                var codeLines = new List<string>();
                for (i++; i < lines.Length; i++)
                {
                    if (lines[i].TrimStart().StartsWith("```") || lines[i].TrimStart().StartsWith("~~~"))
                        break;
                    codeLines.Add(lines[i].Replace("\t", "    "));
                }
                var p = new Paragraph
                {
                    FontFamily = Mono,
                    FontSize = 12.5,
                    Foreground = CodeFg,
                    Background = CodeBg,
                    BorderBrush = BorderColor,
                    BorderThickness = new Thickness(1),
                    Padding = new Thickness(12),
                    Margin = new Thickness(0, 6, 0, 12),
                    LineHeight = 18
                };
                for (int ci = 0; ci < codeLines.Count; ci++)
                {
                    if (ci > 0) p.Inlines.Add(new LineBreak());
                    p.Inlines.Add(new Run(codeLines[ci]));
                }
                doc.Blocks.Add(p);
                continue;
            }

            // Blank line closes current paragraph
            if (string.IsNullOrWhiteSpace(line)) { Flush(); continue; }

            // 1b. Standalone Note Image (![alt](xreponotes-image://id))
            Match m;
            if ((m = ImageLine.Match(line.Trim())).Success)
            {
                Flush();
                doc.Blocks.Add(CreateImageBlock(m.Groups["id"].Value, m.Groups["alt"].Value, compactImages));
                continue;
            }

            // 2. Setext headings: Line\n=== (H1) or Line\n--- (H2)
            if (i + 1 < lines.Length && !string.IsNullOrWhiteSpace(line))
            {
                var next = lines[i + 1].Trim();
                if (Regex.IsMatch(next, @"^={3,}$"))
                {
                    Flush();
                    i++;
                    var hp = new Paragraph
                    {
                        FontWeight = FontWeights.Bold,
                        FontSize = 22,
                        Foreground = Fg,
                        Margin = new Thickness(0, 16, 0, 8),
                        BorderBrush = BorderColor,
                        BorderThickness = new Thickness(0, 0, 0, 1),
                        Padding = new Thickness(0, 0, 0, 4)
                    };
                    AddInlines(hp, line.Trim());
                    doc.Blocks.Add(hp);
                    continue;
                }
                if (Regex.IsMatch(next, @"^-{3,}$") && !Rule.IsMatch(line))
                {
                    Flush();
                    i++;
                    var hp = new Paragraph
                    {
                        FontWeight = FontWeights.SemiBold,
                        FontSize = 18,
                        Foreground = Fg,
                        Margin = new Thickness(0, 14, 0, 6),
                        BorderBrush = BorderColor,
                        BorderThickness = new Thickness(0, 0, 0, 1),
                        Padding = new Thickness(0, 0, 0, 4)
                    };
                    AddInlines(hp, line.Trim());
                    doc.Blocks.Add(hp);
                    continue;
                }
            }

            // 3. ATX Headings (# Heading)
            if ((m = Heading.Match(line)).Success)
            {
                Flush();
                int lvl = m.Groups[1].Length;
                var hp = new Paragraph
                {
                    FontWeight = lvl <= 2 ? FontWeights.Bold : FontWeights.SemiBold,
                    Margin = new Thickness(0, lvl == 1 ? 16 : 12, 0, 6),
                    FontSize = lvl switch { 1 => 22, 2 => 18, 3 => 15, 4 => 14, _ => 13.5 },
                    Foreground = lvl <= 2 ? Fg : Accent,
                    LineHeight = double.NaN
                };
                if (lvl <= 2)
                {
                    hp.BorderBrush = BorderColor;
                    hp.BorderThickness = new Thickness(0, 0, 0, 1);
                    hp.Padding = new Thickness(0, 0, 0, 4);
                }
                AddInlines(hp, m.Groups[2].Value);
                doc.Blocks.Add(hp);
                continue;
            }

            // 4. Horizontal Rule (---, ***, ___)
            if (Rule.IsMatch(line))
            {
                Flush();
                doc.Blocks.Add(new Paragraph
                {
                    BorderBrush = BorderColor,
                    BorderThickness = new Thickness(0, 0, 0, 1),
                    Margin = new Thickness(0, 10, 0, 14)
                });
                continue;
            }

            // 5. Blockquotes (> quote)
            if (line.TrimStart().StartsWith(">"))
            {
                Flush();
                var quoteLines = new List<string>();
                while (i < lines.Length && lines[i].TrimStart().StartsWith(">"))
                {
                    var qtext = Regex.Replace(lines[i].TrimStart(), @"^>+[ \t]?", "");
                    quoteLines.Add(qtext);
                    i++;
                }
                i--; // Step back for loop increment

                var qp = new Paragraph
                {
                    BorderBrush = Accent,
                    BorderThickness = new Thickness(3, 0, 0, 0),
                    Padding = new Thickness(12, 4, 0, 4),
                    Margin = new Thickness(0, 6, 0, 10),
                    Foreground = Dim
                };
                for (int qi = 0; qi < quoteLines.Count; qi++)
                {
                    if (qi > 0) qp.Inlines.Add(new LineBreak());
                    AddInlines(qp, quoteLines[qi]);
                }
                doc.Blocks.Add(qp);
                continue;
            }

            // 6. Markdown Tables (| Col | Col |)
            if (IsTableRow(line) && i + 1 < lines.Length && IsTableSeparator(lines[i + 1]))
            {
                Flush();
                var headers = SplitTableRow(line);
                i++; // Skip separator line
                var tableRows = new List<string[]>();
                while (i + 1 < lines.Length && IsTableRow(lines[i + 1]))
                {
                    i++;
                    tableRows.Add(SplitTableRow(lines[i]));
                }

                var table = new Table { CellSpacing = 0, Margin = new Thickness(0, 8, 0, 14) };
                var group = new TableRowGroup();
                table.RowGroups.Add(group);

                // Header Row
                var hRow = new TableRow { Background = TableHeaderBg };
                foreach (var h in headers)
                {
                    var cp = new Paragraph { FontWeight = FontWeights.SemiBold, Margin = new Thickness(0) };
                    AddInlines(cp, h);
                    hRow.Cells.Add(new TableCell(cp)
                    {
                        Padding = new Thickness(10, 6, 10, 6),
                        BorderBrush = BorderColor,
                        BorderThickness = new Thickness(1)
                    });
                }
                group.Rows.Add(hRow);

                // Data Rows
                foreach (var rData in tableRows)
                {
                    var tr = new TableRow();
                    for (int c = 0; c < headers.Length; c++)
                    {
                        var val = c < rData.Length ? rData[c] : "";
                        var cp = new Paragraph { Margin = new Thickness(0) };
                        AddInlines(cp, val);
                        tr.Cells.Add(new TableCell(cp)
                        {
                            Padding = new Thickness(10, 5, 10, 5),
                            BorderBrush = BorderColor,
                            BorderThickness = new Thickness(1)
                        });
                    }
                    group.Rows.Add(tr);
                }
                doc.Blocks.Add(table);
                continue;
            }

            // 7. Bullet Lists & Task Lists (- item, * item, - [ ] task, - [x] task)
            if ((m = Bullet.Match(line)).Success)
            {
                Flush();
                string mark = m.Groups[2].Success
                    ? (m.Groups[2].Value.Trim().Length > 0 ? "☑  " : "☐  ")
                    : "•  ";
                int indent = m.Groups[1].Value.Replace("\t", "    ").Length;
                doc.Blocks.Add(ListLine(mark, m.Groups[3].Value, indent));
                continue;
            }

            // 8. Numbered Lists (1. item, 2. item)
            if ((m = Numbered.Match(line)).Success)
            {
                Flush();
                int indent = m.Groups[1].Value.Replace("\t", "    ").Length;
                doc.Blocks.Add(ListLine(m.Groups[2].Value + "  ", m.Groups[3].Value, indent));
                continue;
            }

            // 9. Standard Paragraph (preserves soft line breaks)
            if (para == null)
            {
                para = new Paragraph { Margin = new Thickness(0, 0, 0, 10), LineHeight = 22, TextAlignment = TextAlignment.Left };
            }
            else
            {
                para.Inlines.Add(new LineBreak());
            }
            AddInlines(para, line.Trim());
        }

        Flush();
        return doc;
    }

    private static bool IsTableSeparator(string line) =>
        Regex.IsMatch(line, @"^\s*\|?(\s*:?-{3,}:?\s*\|)+\s*:?-{3,}:?\s*\|?\s*$");

    private static bool IsTableRow(string line) =>
        line.Contains('|') && !string.IsNullOrWhiteSpace(line);

    private static string[] SplitTableRow(string line)
    {
        var trimmed = line.Trim();
        if (trimmed.StartsWith('|')) trimmed = trimmed[1..];
        if (trimmed.EndsWith('|')) trimmed = trimmed[..^1];
        var parts = trimmed.Split('|');
        for (int p = 0; p < parts.Length; p++) parts[p] = parts[p].Trim();
        return parts;
    }

    private static Paragraph ListLine(string mark, string text, int indent)
    {
        var p = new Paragraph
        {
            Margin = new Thickness(16 + indent * 6, 0, 0, 3),
            TextIndent = -16,
            LineHeight = 21,
            TextAlignment = TextAlignment.Left
        };
        p.Inlines.Add(new Run(mark) { Foreground = Accent, FontWeight = FontWeights.SemiBold });
        AddInlines(p, text);
        return p;
    }

    private static void AddInlines(Paragraph p, string text)
    {
        int pos = 0;
        foreach (Match m in Inline.Matches(text))
        {
            if (m.Index > pos) p.Inlines.Add(new Run(text[pos..m.Index]));

            if (m.Groups["bolditalic"].Success)
            {
                p.Inlines.Add(new Bold(new Italic(new Run(m.Groups["bitext"].Value))));
            }
            else if (m.Groups["bold"].Success)
            {
                p.Inlines.Add(new Bold(new Run(m.Groups["btext"].Value)));
            }
            else if (m.Groups["italic"].Success)
            {
                p.Inlines.Add(new Italic(new Run(m.Groups["itext"].Value)));
            }
            else if (m.Groups["strike"].Success)
            {
                p.Inlines.Add(new Run(m.Groups["stext"].Value)
                {
                    TextDecorations = TextDecorations.Strikethrough,
                    Foreground = Dim
                });
            }
            else if (m.Groups["code"].Success)
            {
                p.Inlines.Add(new Run(m.Groups["ctext"].Value)
                {
                    FontFamily = Mono,
                    FontSize = 12.5,
                    Foreground = CodeFg,
                    Background = CodeBg
                });
            }
            else if (m.Groups["image"].Success)
            {
                var alt = m.Groups["imgalt"].Value.Trim();
                var label = string.IsNullOrEmpty(alt) || alt.Equals("image", StringComparison.OrdinalIgnoreCase)
                    ? "[Image]"
                    : $"[Image: {alt}]";
                p.Inlines.Add(new Run(label) { Foreground = Accent });
            }
            else if (m.Groups["link"].Success)
            {
                var lText = m.Groups["linktext"].Value;
                var lUrl = m.Groups["linkurl"].Value;
                var link = new Hyperlink(new Run(string.IsNullOrEmpty(lText) ? lUrl : lText))
                {
                    Foreground = Accent,
                    TextDecorations = TextDecorations.Underline
                };
                if (Uri.TryCreate(lUrl, UriKind.Absolute, out var uri))
                {
                    link.NavigateUri = uri;
                    link.ToolTip = uri.AbsoluteUri;
                    link.RequestNavigate += (_, e) =>
                    {
                        try { Process.Start(new ProcessStartInfo(e.Uri.AbsoluteUri) { UseShellExecute = true }); } catch { }
                    };
                }
                p.Inlines.Add(link);
            }
            else if (m.Groups["autolink"].Success)
            {
                var aUrl = m.Groups["autourl"].Success ? m.Groups["autourl"].Value : m.Groups["autourl2"].Value;
                var link = new Hyperlink(new Run(aUrl))
                {
                    Foreground = Accent,
                    TextDecorations = TextDecorations.Underline
                };
                if (Uri.TryCreate(aUrl, UriKind.Absolute, out var uri))
                {
                    link.NavigateUri = uri;
                    link.ToolTip = uri.AbsoluteUri;
                    link.RequestNavigate += (_, e) =>
                    {
                        try { Process.Start(new ProcessStartInfo(e.Uri.AbsoluteUri) { UseShellExecute = true }); } catch { }
                    };
                }
                p.Inlines.Add(link);
            }

            pos = m.Index + m.Length;
        }

        if (pos < text.Length) p.Inlines.Add(new Run(text[pos..]));
    }

    private static BlockUIContainer CreateImageBlock(string imageId, string alt, bool compactImages)
    {
        double maxWidth = compactImages ? 240 : 640;
        double maxHeight = compactImages ? 110 : 480;

        var border = new Border
        {
            Background = CodeBg,
            BorderBrush = BorderColor,
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(6),
            Padding = compactImages ? new Thickness(2) : new Thickness(4),
            Margin = compactImages ? new Thickness(0, 4, 0, 6) : new Thickness(0, 6, 0, 10),
            HorizontalAlignment = HorizontalAlignment.Left
        };

        Image CreateImageElement(BitmapSource source)
        {
            var img = new Image
            {
                Source = source,
                Stretch = Stretch.Uniform,
                MaxWidth = maxWidth,
                MaxHeight = maxHeight,
                ToolTip = string.IsNullOrWhiteSpace(alt) ? null : alt
            };
            RenderOptions.SetBitmapScalingMode(img, BitmapScalingMode.HighQuality);
            return img;
        }

        var cached = compactImages
            ? NoteImageStore.TryGetCachedThumbnail(imageId)
            : NoteImageStore.TryGetCachedImage(imageId);
        if (cached != null)
        {
            border.Child = CreateImageElement(cached);
        }
        else
        {
            border.Child = new TextBlock
            {
                Text = "Loading image…",
                Foreground = Dim,
                FontSize = compactImages ? 11 : 12,
                Padding = new Thickness(8, 4, 8, 4)
            };

            var dispatcher = Application.Current?.Dispatcher;
            _ = Task.Run(async () =>
            {
                var loaded = compactImages
                    ? await NoteImageStore.LoadThumbnailAsync(imageId).ConfigureAwait(false)
                    : await NoteImageStore.LoadImageAsync(imageId).ConfigureAwait(false);
                dispatcher?.BeginInvoke(() =>
                {
                    if (loaded != null)
                    {
                        border.Child = CreateImageElement(loaded);
                    }
                    else
                    {
                        border.Child = new TextBlock
                        {
                            Text = "[Image unavailable]",
                            Foreground = Dim,
                            FontSize = compactImages ? 11 : 12,
                            Padding = new Thickness(8, 4, 8, 4),
                            ToolTip = string.IsNullOrWhiteSpace(NoteImageStore.LastError) ? null : NoteImageStore.LastError
                        };
                    }
                });
            });
        }

        return new BlockUIContainer(border) { Margin = new Thickness(0) };
    }
}
