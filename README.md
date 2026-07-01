# Tensei: SeaDex

A headless background scraper extension for the **Tensei Scraper** anime client.
Looks up an anime by its AniList ID on SeaDex's PocketBase backend
(`releases.moe`) and returns a magnet link for every public-tracker torrent
SeaDex has indexed for that anime.

## How it works

1. **Discovery** — The Tensei Scraper main app finds this extension via the
   `com.blissless.animeclient.EXTENSION_BEACON` broadcast receiver and the
   `"Tensei: "` label prefix.
2. **Query** — The main app calls this extension's `ContentProvider` with the
   URI `content://com.blissless.seadex.provider/scrape?anime=<name>&anilistId=<id>`.
3. **Scrape** — A single HTTP GET to SeaDex's PocketBase REST API:

   ```
   GET https://releases.moe/api/collections/entries/records
       ?filter=alID=<anilistId>
       &expand=trs
       &perPage=500
       &skipTotal=true
   ```

   The response is a JSON object whose `items[0].expand.trs` array contains
   every torrent SeaDex has tagged for that anime:

   ```json
   {
     "items": [{
       "alID": 5114,
       "expand": {
         "trs": [
           {
             "releaseGroup": "McBalls",
             "isBest": true,
             "tracker": "Nyaa",
             "url": "https://nyaa.si/view/1947488",
             "infoHash": "e52c8ec81a53e8dc79ba1bc561e89ba1228b03a1",
             "dualAudio": true
           }
         ]
       }
     }]
   }
   ```

   The extension reads each torrent's `infoHash` field, skips any that are
   `<redacted>` (private-tracker entries, e.g. AnimeBytes), and builds a
   magnet URI from the hash. The release group is URL-encoded as the `dn=`
   (display name) parameter, and four public trackers are appended as `tr=`
   entries to bootstrap peer discovery.

4. **Return** — The list of magnet URIs is serialized to a JSON array and
   returned to the main app.

No `WebView`, no nyaa.si scraping, no `.torrent` file download — SeaDex's
PocketBase API hands us the BitTorrent infohash directly, so the magnet URI
is built entirely client-side.

## Data format returned

```json
[
  "magnet:?xt=urn:btih:e52c8ec81a53e8dc79ba1bc561e89ba1228b03a1&dn=McBalls&tr=udp://tracker.opentrackr.org:1337/announce&tr=...",
  "magnet:?xt=urn:btih:...&dn=...&tr=..."
]
```

On failure (no AniList ID, no SeaDex entry, or network error):

```json
[ ]
```

## Technical details

| | |
|---|---|
| **Dependencies** | Zero. Uses only `java.net.HttpURLConnection` + `org.json`. |
| **HTTP calls per scrape** | 1 |
| **APK size** | ~40 KB after R8 shrinking |
| **Min Android** | API 26 |
| **Parameters read** | `anilistId` (the AniList ID is the primary key — `anime` is ignored) |

## Architecture

| File | Purpose |
|------|---------|
| `SeaDexScraper.kt` | PocketBase API call + magnet URI construction. Returns `List<String>`. |
| `ScraperProvider.kt` | `ContentProvider` entry point. Serializes the list to a JSON array. |
| `ExtensionBeaconReceiver.kt` | Empty `BroadcastReceiver` for discovery. |

## Building

1. Place your release keystore at `app/release.jks` and add its credentials to
   `local.properties` (gitignored):

   ```properties
   storeFile=/absolute/path/to/release.jks
   storePassword=...
   keyAlias=...
   keyPassword=...
   ```

2. Build the shrunk, signed APK:

   ```bash
   ./gradlew assembleRelease
   ```

   Output: `app/build/outputs/apk/release/app-release.apk`

3. Install alongside the Tensei Scraper main app:

   ```bash
   adb install -r app/build/outputs/apk/release/app-release.apk
   ```
