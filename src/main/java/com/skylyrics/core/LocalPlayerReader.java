package com.skylyrics.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * Reads what's playing from the computer itself (no accounts, no setup):
 * Windows media controls (via PowerShell), macOS Spotify (via AppleScript), Linux (via playerctl).
 * A helper process prints one line per second; this class turns those lines into samples.
 *
 * Besides sample lines, the Windows helper prints one "COVER" line per song: a small grid of album-cover pixels.
 */
public final class LocalPlayerReader {
    private static final Logger LOG = LoggerFactory.getLogger("skylyrics");

    public interface Listener {
        /** Called on the reader thread. {@link LocalSample#NOTHING} means nothing is playing. */
        void onSample(LocalSample sample);

        /** Called on the reader thread with the album-cover pixels (0xRRGGBB) for the song with this key. */
        default void onCover(String trackKey, int[] rgbPixels) {
        }
    }

    private final Listener listener;
    private final List<String> command;
    private final int unitDivisor;
    private final long restartDelayMs;

    private volatile boolean running;
    /** Bumped on every start(), so a thread from an earlier start can tell it has been replaced and exit. */
    private volatile int generation;
    private volatile String status = "Starting...";
    private volatile long lastLineNanos;
    private volatile boolean sawError;
    private volatile Process process;
    private Thread thread;
    private boolean hookInstalled;

    public LocalPlayerReader(Listener listener) {
        this(listener, commandForThisOs(), unitDivisorForThisOs(), 5000L);
    }

    LocalPlayerReader(Listener listener, List<String> command, int unitDivisor, long restartDelayMs) {
        this.listener = listener;
        this.command = command;
        this.unitDivisor = unitDivisor;
        this.restartDelayMs = restartDelayMs;
    }

    public String status() {
        return status;
    }

    public boolean isRunning() {
        return running;
    }

    /** Milliseconds since the last valid line, or Long.MAX_VALUE if none has arrived yet. */
    public long lastLineAgeMs() {
        long t = lastLineNanos;
        return t == 0L ? Long.MAX_VALUE : (System.nanoTime() - t) / 1_000_000L;
    }

    boolean isProcessAlive() {
        Process p = process;
        return p != null && p.isAlive();
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        if (command == null) {
            status = "No-setup mode isn't supported on this operating system. Use a Spotify account instead.";
            return;
        }
        running = true;
        final int gen = ++generation;
        sawError = false;
        lastLineNanos = 0L;
        status = "Starting the media reader...";
        if (!hookInstalled) {
            hookInstalled = true;
            // Child processes survive the game closing, so make sure ours is killed.
            Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "SkyLyrics-reader-shutdown"));
        }
        thread = new Thread(() -> loop(gen), "SkyLyrics-reader");
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void stop() {
        running = false;
        Process p = process;
        if (p != null) {
            p.destroyForcibly();
        }
        Thread t = thread;
        if (t != null) {
            t.interrupt();
        }
    }

    private boolean active(int gen) {
        return running && generation == gen;
    }

    private void loop(int gen) {
        while (active(gen)) {
            Process p = null;
            try {
                ProcessBuilder pb = new ProcessBuilder(command);
                pb.redirectError(ProcessBuilder.Redirect.DISCARD);
                p = pb.start();
                process = p;
                if (!active(gen)) { // stopped (or replaced) while the helper was starting
                    p.destroyForcibly();
                    return;
                }
                p.getOutputStream().close(); // the helper never reads stdin
                status = "Waiting for a player...";
                try (BufferedReader in = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while (active(gen) && (line = in.readLine()) != null) {
                        try {
                            handleLine(line);
                        } catch (RuntimeException e) {
                            // A problem with one line (or with the listener) must never kill the reader.
                            LOG.debug("Ignoring a media reader line that caused an error", e);
                        }
                    }
                }
                int code = p.isAlive() ? 0 : p.waitFor();
                if (active(gen) && !sawError) {
                    status = "The media reader stopped (exit " + code + "), restarting...";
                }
            } catch (InterruptedException e) {
                return;
            } catch (IOException e) {
                if (active(gen)) {
                    status = "Could not start the media reader: " + e.getMessage();
                    LOG.warn("Could not start media reader", e);
                }
            } catch (RuntimeException e) {
                status = "The media reader hit an error: " + e;
                LOG.warn("Media reader error", e);
            } finally {
                if (p != null && p.isAlive() && !active(gen)) {
                    p.destroyForcibly();
                }
            }
            if (!active(gen)) {
                return;
            }
            try {
                Thread.sleep(restartDelayMs);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void handleLine(String line) {
        String cleaned = line.replace("\uFEFF", "");
        if (cleaned.startsWith("ERR:")) {
            sawError = true;
            status = cleaned.substring(4).strip();
            return;
        }
        if (cleaned.startsWith("COVER\t")) {
            String[] f = cleaned.split("\t", -1);
            if (f.length >= 4) {
                int[] pixels = CoverColors.parseHex(f[3].strip());
                if (pixels != null) {
                    listener.onCover(f[1].strip() + "|" + f[2].strip(), pixels);
                }
            }
            return;
        }
        LocalSample sample = LocalSample.parse(cleaned, unitDivisor);
        if (sample == null) {
            return; // not a reading; ignore
        }
        sawError = false;
        lastLineNanos = System.nanoTime();
        status = sample.isNothing() ? "Nothing is playing (start a song in the Spotify app)"
                : "Reading what's playing from this computer";
        listener.onSample(sample);
    }

    // ---- per-OS helper commands ----------------------------------------------------------------

    private static String osName() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    }

    static int unitDivisorForThisOs() {
        String os = osName();
        return (os.contains("win") || os.contains("mac")) ? 1 : 1000; // playerctl reports microseconds
    }

    static List<String> commandForThisOs() {
        long pid = ProcessHandle.current().pid();
        String os = osName();
        if (os.contains("win")) {
            String script = windowsScript(pid);
            String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
            return List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                    "-WindowStyle", "Hidden", "-EncodedCommand", encoded);
        }
        if (os.contains("mac")) {
            return List.of("/bin/sh", "-c", macShellLoop(pid));
        }
        if (os.contains("nux") || os.contains("nix") || os.contains("bsd")) {
            return List.of("/bin/sh", "-c", linuxShellLoop(pid));
        }
        return null;
    }

    static String windowsScript(long parentPid) {
        return WINDOWS_SCRIPT.replace("__PARENT_PID__", Long.toString(parentPid));
    }

    static String macShellLoop(long parentPid) {
        // One osascript call per second; stops by itself if the game process disappears.
        String applescript = String.join("\n",
                "if application \"Spotify\" is running then",
                "tell application \"Spotify\"",
                "set st to player state as string",
                "if st is \"stopped\" then return \"\"",
                "set t to current track",
                "return (name of t) & tab & (artist of t) & tab & (album of t) & tab & (duration of t) & tab"
                        + " & ((player position * 1000) as integer) & tab & st & tab & (artwork url of t)",
                "end tell",
                "end if",
                "return \"\"");
        StringBuilder args = new StringBuilder();
        for (String l : applescript.split("\n")) {
            args.append(" -e '").append(l).append('\'');
        }
        return "while kill -0 " + parentPid + " 2>/dev/null; do osascript" + args + " 2>/dev/null; sleep 1; done";
    }

    static String linuxShellLoop(long parentPid) {
        String fmt = "{{title}}\t{{artist}}\t{{album}}\t{{mpris:length}}\t{{position}}\t{{status}}\t{{mpris:artUrl}}";
        return "command -v playerctl >/dev/null 2>&1 || "
                + "{ echo 'ERR: Install playerctl for no-setup mode (e.g. sudo apt install playerctl)'; exit 1; }; "
                + "while kill -0 " + parentPid + " 2>/dev/null; do "
                + "playerctl -p spotify,%any metadata --format '" + fmt + "' 2>/dev/null || echo; sleep 1; done";
    }

    private static final String WINDOWS_SCRIPT = """
            $ErrorActionPreference = 'SilentlyContinue'
            $parentPid = __PARENT_PID__
            [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
            Add-Type -AssemblyName System.Runtime.WindowsRuntime
            Add-Type -AssemblyName System.Drawing
            $asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]
            function Await($op, $type) {
              $task = $asTaskGeneric.MakeGenericMethod($type).Invoke($null, @($op))
              [void]$task.Wait(-1)
              $task.Result
            }
            [void][Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager,Windows.Media.Control,ContentType=WindowsRuntime]
            [void][Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties,Windows.Media.Control,ContentType=WindowsRuntime]
            [void][Windows.Storage.Streams.IRandomAccessStreamWithContentType,Windows.Storage.Streams,ContentType=WindowsRuntime]
            $mgr = Await ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])
            if ($null -eq $mgr) { [Console]::Out.WriteLine('ERR: Windows media controls are not available (needs Windows 10 or 11)'); [Console]::Out.Flush(); exit 1 }
            $tab = [char]9
            function Clean($s) { if ($null -eq $s) { return '' } return ([string]$s -replace '[\\t\\r\\n]', ' ') }
            # Prefer a Spotify session that is playing, then any session that is playing, then an idle Spotify session.
            function Pick-Session($manager) {
              $spotify = $null
              $spotifyPlaying = $null
              $anyPlaying = $null
              foreach ($s in @($manager.GetSessions())) {
                $isSpotify = ($s.SourceAppUserModelId -like '*spotify*')
                $playing = ($s.GetPlaybackInfo().PlaybackStatus.ToString() -eq 'Playing')
                if ($isSpotify -and $null -eq $spotify) { $spotify = $s }
                if ($isSpotify -and $playing -and $null -eq $spotifyPlaying) { $spotifyPlaying = $s }
                if ($playing -and $null -eq $anyPlaying) { $anyPlaying = $s }
              }
              if ($null -ne $spotifyPlaying) { return $spotifyPlaying }
              if ($null -ne $anyPlaying) { return $anyPlaying }
              if ($null -ne $spotify) { return $spotify }
              return $manager.GetCurrentSession()
            }
            # The album cover as a 16x16 grid of pixels, as hex "RRGGBBRRGGBB...", or '' if it isn't available.
            function Get-CoverHex($ref) {
              try {
                if ($null -eq $ref) { return '' }
                $rs = Await ($ref.OpenReadAsync()) ([Windows.Storage.Streams.IRandomAccessStreamWithContentType])
                $net = [System.IO.WindowsRuntimeStreamExtensions]::AsStreamForRead($rs)
                $ms = New-Object System.IO.MemoryStream
                $net.CopyTo($ms)
                $ms.Position = 0
                $src = New-Object System.Drawing.Bitmap($ms)
                $small = New-Object System.Drawing.Bitmap($src, 16, 16)
                $sb = New-Object System.Text.StringBuilder
                for ($y = 0; $y -lt 16; $y++) {
                  for ($x = 0; $x -lt 16; $x++) {
                    $c = $small.GetPixel($x, $y)
                    [void]$sb.Append(('{0:X2}{1:X2}{2:X2}' -f $c.R, $c.G, $c.B))
                  }
                }
                $small.Dispose()
                $src.Dispose()
                $ms.Dispose()
                $net.Dispose()
                return $sb.ToString()
              } catch { return '' }
            }
            $coverKey = ''
            $coverTries = 0
            $coverDone = $false
            while ($true) {
              if (-not (Get-Process -Id $parentPid -ErrorAction SilentlyContinue)) { exit }
              $line = ''
              $coverLine = ''
              try {
                $chosen = Pick-Session $mgr
                if ($null -ne $chosen) {
                  $props = Await ($chosen.TryGetMediaPropertiesAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties])
                  $status = $chosen.GetPlaybackInfo().PlaybackStatus.ToString()
                  $tl = $chosen.GetTimelineProperties()
                  $title = Clean $props.Title
                  $artist = Clean $props.Artist
                  $dur = 0
                  $pos = -1
                  $span = $tl.EndTime.TotalMilliseconds - $tl.StartTime.TotalMilliseconds
                  if ($span -gt 0) {
                    $dur = [long]$span
                    $age = ([DateTimeOffset]::UtcNow - $tl.LastUpdatedTime).TotalMilliseconds
                    $p = $tl.Position.TotalMilliseconds - $tl.StartTime.TotalMilliseconds
                    if ($status -eq 'Playing') { $p += $age }
                    if ($age -ge 0 -and $age -lt 3600000 -and $p -ge 0 -and $p -le ($dur + 5000)) { $pos = [long]$p }
                  }
                  $line = ($title, $artist, (Clean $props.AlbumTitle), $dur, $pos, $status) -join $tab
                  $key = $title + '|' + $artist
                  if ($key -ne $coverKey) { $coverKey = $key; $coverTries = 0; $coverDone = $false }
                  if (-not $coverDone -and $coverTries -lt 6 -and $title -ne '') {
                    $coverTries++
                    $hex = Get-CoverHex $props.Thumbnail
                    if ($hex.Length -gt 0) {
                      $coverDone = $true
                      $coverLine = ('COVER', $title, $artist, $hex) -join $tab
                    }
                  }
                }
              } catch { $line = '' }
              [Console]::Out.WriteLine($line)
              if ($coverLine.Length -gt 0) { [Console]::Out.WriteLine($coverLine) }
              [Console]::Out.Flush()
              Start-Sleep -Milliseconds 1000
            }
            """;
}
