# Timelapse Feature Design

## Overview

A timer-based job system that captures snapshots from Unifi Protect cameras at calculated intervals, then compiles them into daily, monthly, and yearly timelapse videos using ffmpeg. Source files are deleted after successful compilation at each level.

## Configuration

Environment variables in `config.env`:

| Variable | Description | Example |
|---|---|---|
| `UNIFI_PROTECT_HOST` | Protect controller URL | `https://192.168.1.1` |
| `UNIFI_PROTECT_API_KEY` | API key for authentication | `<key>` |
| `UNIFI_PROTECT_CAMERAS` | Comma-separated `name:camera-id` pairs | `front-door:abc123,backyard:def456` |
| `TIMELAPSE_DAY_DURATION` | Target video duration in seconds for one day | `30` |
| `TIMELAPSE_FPS` | Output video frame rate | `30` |
| `TIMELAPSE_FRAMES_PATH` | Base path for captured frames | `/home/mat/media/timelapse/frames` |
| `TIMELAPSE_VIDEOS_PATH` | Base path for compiled videos | `/home/mat/media/timelapse/videos` |

These are exposed in `config.edn` via `#biff/env` reader macros, namespaced under `:timelapse/*` and `:unifi/*`.

**Derived value:** `capture_interval = 86400 / (fps * day_duration)`. With defaults (30fps, 30s duration) this is ~96 seconds between captures.

## Snapshot Fetching

- HTTP GET to `https://<host>/proxy/protect/integration/v1/cameras/<camera-id>/snapshot`
- Header: `X-API-Key: <key>` (UniFi OS Integration API key)
- TLS verification disabled (Protect controllers commonly use self-signed certs)
- Returns JPEG image
- On HTTP error: log warning, skip frame. One camera failing does not block others.
- Saved to: `<frames-path>/<camera-name>/<YYYY-MM-DD>/frame-<timestamp-millis>.jpg`

## Scheduled Tasks

### 1. Frame Capture Task

- **Schedule:** Every `capture_interval` seconds
- **Action:** Iterates configured cameras, fetches snapshot, writes to disk
- **Error handling:** Per-camera — one failure doesn't block others

### 2. Daily Compilation Task

- **Schedule:** Midnight daily
- **Action:** For each camera, compiles yesterday's frames into a video
- **ffmpeg command:**
  ```
  ffmpeg -framerate <fps> -pattern_type glob -i '*.jpg' \
    -c:v libx264 -preset slow -crf 28 -pix_fmt yuv420p \
    -movflags +faststart <output>.mp4
  ```
- **Settings rationale:** `crf 28` + `preset slow` produces good quality at small file size. `movflags +faststart` makes videos web-playable.
- **Output:** `<videos-path>/daily/<camera-name>/<YYYY-MM-DD>.mp4`
- **On success:** Delete yesterday's frame directory for that camera
- **On failure:** Log error, leave frames intact for manual intervention

### 3. Rollup Task

- **Schedule:** Runs daily alongside compilation
- **Monthly (1st of month):** Concatenate all daily videos from previous month into `<videos-path>/monthly/<camera-name>/<YYYY-MM>.mp4`. Delete daily videos on success.
- **Yearly (Jan 1st):** Concatenate all monthly videos from previous year into `<videos-path>/yearly/<camera-name>/<YYYY>.mp4`. Delete monthly videos on success.
- **Method:** ffmpeg concat demuxer (remux, no re-encoding since all sources share encoding settings)
- **On failure:** Log error, leave source videos intact

## File Layout

```
~/media/timelapse/
  frames/<camera-name>/<YYYY-MM-DD>/
    frame-<timestamp-millis>.jpg
  videos/
    daily/<camera-name>/<YYYY-MM-DD>.mp4
    monthly/<camera-name>/<YYYY-MM>.mp4
    yearly/<camera-name>/<YYYY>.mp4
```

## Namespace Structure

**`src/pasquet/backend/timelapse.clj`** — single namespace containing:

- `fetch-snapshot!` — HTTP GET to Protect, saves JPEG to disk
- `capture-frames!` — iterates cameras, calls `fetch-snapshot!` for each
- `compile-daily!` — ffmpeg frames-to-video for a given camera/date, deletes frames on success
- `compile-rollup!` — checks date, runs monthly/yearly concat as needed, deletes sources on success
- `module` — exposes `:tasks` for Chime/Biff integration

## Integration

- Add `timelapse/module` to the `modules` vector in `backend.clj`
- Config entries added to `config.edn` with `#biff/env` reader macros
- New env vars added to `config.env`

## Dependencies

- `clj-http` — for snapshot HTTP requests (add if not already present)
- `clojure.java.shell` — for ffmpeg invocation (stdlib)
- `clojure.java.io` — for file operations (stdlib)
- ffmpeg must be installed on the host system
