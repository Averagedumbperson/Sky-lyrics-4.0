# Sky Lyrics (Fabric, Minecraft 26.2)

Synced lyrics appear as real text objects in the sky of your world, in a smooth font, with a "now playing" pill at
the top and a settings menu (press **K**). Inspired by the Valence mod video; this is an original implementation.

The text colour follows the song's album cover: the cover's dominant colour, brightened so it stays readable, fading
from one song to the next. The now-playing pill uses the same colour.

Each line appears at a fixed spot in the sky, facing where you were standing, and stays there while you move and look
around (it is not stuck to your camera). It is drawn only on your own screen; nothing is sent to the server.

**No setup:** put the jar in `mods`, play a song in the Spotify desktop app, done. No account, no login, no API keys.

Client-only: works in singleplayer and on any server/world (Essential included). Each person sees their own lyrics.

## Install
1. Get the jar (see "Getting the jar" below).
2. Put `skylyrics-0.4.0.jar` and **Fabric API for 26.2** in your `mods` folder (Fabric Loader 0.19.3+, Java 25).
3. Start Minecraft, join a world, and play a song in the **Spotify desktop app**. Lyrics appear.

## What counts as "no setup"
Lyrics are read from what your computer says is playing:
- **Windows 10/11:** Spotify desktop app (free or Premium). Also works with other players that show in the Windows
  media overlay (Apple Music, browser players).
- **macOS:** Spotify desktop app. macOS may ask once to let Minecraft/Java control Spotify; click OK.
- **Linux:** needs `playerctl` installed (`sudo apt install playerctl`).
- **Not covered:** Spotify on your phone or the Spotify web player. For those, press K and set
  **Source: Spotify account** (needs Spotify Premium and a free Spotify developer app; see the end).

## Getting the jar
You can't just download a ready jar from here, it has to be built once. Pick one:

**A. In the cloud, nothing to install (easiest)**
1. Make a free account at github.com, then **New repository** (any name, private is fine).
2. Unzip this project and upload everything into the repo (drag the files in on the "uploading an existing file"
   page). Make sure the hidden `.github` folder goes up too. If it won't, use *Add file > Create new file*, name it
   `.github/workflows/build.yml`, and paste in the contents of that file.
3. Open the repo's **Actions** tab. The "Build mod" workflow runs on its own (or click *Run workflow*).
4. When it turns green, open the run and download the **skylyrics-jar** file at the bottom. Unzip it: that is your mod.

**B. On your PC**
1. Install JDK 25 and IntelliJ IDEA. Open this folder in IntelliJ (it fetches Gradle 9.5.1 itself).
2. In the Gradle panel run `Tasks > build`. The jar is `build/libs/skylyrics-0.4.0.jar`.
   Test inside the IDE with the `runClient` task.

If Gradle complains about a version, use the ones the Fabric template generator shows for 26.2
(https://fabricmc.net/develop/template) and edit `gradle.properties`.

## Settings (K)
Lyrics on/off, now-playing pill on/off, Where (In the world / On screen), Show (word by word / whole line),
Colour (Album cover, or a fixed preset), Size, Font (Smooth Poppins / Minecraft), timing (earlier/later), Source.
Click Colour to cycle: Album cover, White, Lavender, Pink, Cyan, Gold, Mint, then back to Album cover.

"On screen" draws flat text on the HUD instead of in the world. Use it if the in-world text ever misbehaves.

## Updating from an older version
Delete the old `skylyrics-*.jar` from `mods` (two versions at once will conflict). To rebuild: download the new zip,
unzip it, and in your GitHub repo use *Add file > Upload files*, drag in everything from the unzipped folder except
`.github` (your build workflow is already there), and commit. The Actions tab then builds the new jar.

If lyrics are early or late, use "Lyrics earlier/later".

## Notes
- Lyrics come from LRCLIB (free community database). Not every song has synced lyrics.
- Album cover colour: on Windows the cover comes from the Windows media overlay; on macOS and Linux and in Spotify
  account mode it is downloaded from the cover's web address. If a cover can't be read, the colour stays soft lavender.
  Grey, black or white covers give a neutral light colour.
- LRCLIB only has line timing, so word-by-word is an estimate spread across each line.
- If the mod starts while a song is already playing and your player doesn't report its position, timing is counted
  from when the mod first saw the song; it corrects itself on the next song.
- Windows uses a small hidden PowerShell helper that reads the media overlay once a second. It closes with the game.
- The smooth font is Poppins Bold, bundled unmodified under the SIL Open Font License (see LICENSE-Poppins-OFL.txt).
- If anything fails, send `logs/latest.log` and the Gradle/Actions output.

## Optional: Spotify account mode (advanced)
Only needed for phone/web-player listening. The account that owns the developer app needs Premium, and one app
allows up to 5 users.
1. https://developer.spotify.com/dashboard > Create app > tick **Web API**.
2. Redirect URI: `http://127.0.0.1:8888/callback` (exactly).
3. Put the app's Client ID into `.minecraft/config/skylyrics.json` as `"spotifyClientId"`.
4. Friends: add their Spotify email under *User Management* in your app; they use the same Client ID.
5. In game press **K**, set **Source: Spotify account**, click **Connect Spotify**, approve in the browser.
`config/skylyrics-spotify.json` holds your login token; don't share it. A 403 means your account isn't added to the app.
