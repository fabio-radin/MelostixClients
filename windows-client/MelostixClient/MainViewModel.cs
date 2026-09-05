using System.ComponentModel;
using System.Runtime.CompilerServices;

namespace MelostixClient;

/// <summary>
/// Thin binding layer over <see cref="LyricsState"/>: mirrors its fields for XAML binding, plus
/// a few computed properties (which lines to show, what the single-line header should say) so
/// MainWindow.xaml stays free of status-string logic.
/// </summary>
public sealed class MainViewModel : INotifyPropertyChanged
{
    public event PropertyChangedEventHandler? PropertyChanged;

    private void Set<T>(ref T field, T value, [CallerMemberName] string? propertyName = null)
    {
        if (EqualityComparer<T>.Default.Equals(field, value))
        {
            return;
        }
        field = value;
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(propertyName));
    }

    private bool _connected;
    public bool Connected { get => _connected; private set => Set(ref _connected, value); }

    private string _info = string.Empty;
    public string Info { get => _info; private set => Set(ref _info, value); }

    private string _status = "none";
    public string Status { get => _status; private set => Set(ref _status, value); }

    private string? _title;
    public string? Title { get => _title; private set => Set(ref _title, value); }

    private string? _artist;
    public string? Artist { get => _artist; private set => Set(ref _artist, value); }

    private string? _previous;
    public string? Previous { get => _previous; private set => Set(ref _previous, value); }

    private string? _current;
    public string? Current { get => _current; private set => Set(ref _current, value); }

    private string? _next;
    public string? Next { get => _next; private set => Set(ref _next, value); }

    /// <summary>True while the synced-lyrics 3-line block should be shown.</summary>
    public bool ShowSyncedLines => Connected && Status == "synced";

    /// <summary>True while the single-line header (everything except "synced") should be shown.</summary>
    public bool ShowHeaderLine => !ShowSyncedLines;

    public bool ShowNotSyncedNote => Connected && Status == "plain";

    /// <summary>What the single-line header says for every status except "synced" - mirrors the
    /// reference wording used by the other clients' status view.</summary>
    public string HeaderLine
    {
        get
        {
            if (!Connected)
            {
                return Info;
            }
            return Status switch
            {
                "loading" => $"Searching lyrics for \"{Title}\"...",
                "plain" => $"{Title} - {Artist}",
                "not_found" => $"Lyrics not found for \"{Title}\"",
                "error" => "Lyrics search error",
                _ => string.IsNullOrEmpty(Title) ? "No track playing" : $"{Title} - {Artist}",
            };
        }
    }

    public void ApplyState(LyricsState state)
    {
        Connected = state.Connected;
        Info = state.Info;
        Status = state.Status;
        Title = state.Title;
        Artist = state.Artist;
        Previous = state.Previous;
        Current = state.Current;
        Next = state.Next;

        // Proprieta' calcolate: non hanno un backing field con Set(), vanno notificate a mano.
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(HeaderLine)));
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(ShowSyncedLines)));
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(ShowHeaderLine)));
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(ShowNotSyncedNote)));
    }
}
