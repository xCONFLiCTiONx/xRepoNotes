namespace RepoNotes.Services;

public static class RepoScanner
{
    private static readonly HashSet<string> Skip = new(StringComparer.OrdinalIgnoreCase)
    {
        "node_modules", "bin", "obj", "$RECYCLE.BIN", "System Volume Information"
    };

    /// <summary>
    /// Lists git-tracked projects under <paramref name="root"/>: folders that contain a .git
    /// directory (or .git file, for worktrees/submodules). Looks in the root's children and,
    /// for plain grouping folders, one level deeper.
    /// </summary>
    public static List<string> Scan(string root)
    {
        var found = new List<string>();
        if (!Directory.Exists(root)) return found;

        foreach (var dir in SafeDirs(root))
        {
            if (IsGitRepo(dir)) { found.Add(dir); continue; }
            foreach (var sub in SafeDirs(dir))
                if (IsGitRepo(sub)) found.Add(sub);
        }
        found.Sort(StringComparer.OrdinalIgnoreCase);
        return found;
    }

    private static bool IsGitRepo(string dir)
    {
        var g = Path.Combine(dir, ".git");
        return Directory.Exists(g) || File.Exists(g);
    }

    private static IEnumerable<string> SafeDirs(string path)
    {
        try
        {
            return Directory.EnumerateDirectories(path)
                .Where(d =>
                {
                    var n = Path.GetFileName(d);
                    return !n.StartsWith('.') && !Skip.Contains(n);
                })
                .ToList();
        }
        catch { return []; }
    }
}

