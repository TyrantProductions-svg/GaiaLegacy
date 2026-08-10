# Audio provenance

## Public credit

**Music by Leo Deng (Leosteeeve) and David Li (Omi Hurricane)**

`Gaia` and `Legacy` are original collaborative works by the credited authors. Both authors have confirmed that GaiaLegacy may preserve the source files in its source-assets area and redistribute the music as part of GaiaLegacy source distributions, installers, and public releases.

This authorization is specific to GaiaLegacy. No Creative Commons license, standalone redistribution license, or independent reuse right is claimed or granted by this document. Contact the authors before extracting or reusing either track outside GaiaLegacy.

## Preserved source assets

The original MP3 files are preserved under `game/src/main/source-assets/audio/`. That directory is provenance/source material and is not a runtime-resource location.

| Track | Preserved path | Bytes | Codec | Duration (s) | Sample rate | Channels | Stream bitrate | File SHA-256 |
| --- | --- | ---: | --- | ---: | ---: | ---: | ---: | --- |
| Gaia | `game/src/main/source-assets/audio/Gaia.mp3` | 6,959,982 | MP3 | 289.828583 | 44,100 Hz | 2 | 192,000 bit/s | `D3F7CB27AE858E9982C7B7D75FFB3677A5BD338F5C0F9776DD1493CE72B1CFB4` |
| Legacy | `game/src/main/source-assets/audio/Legacy.mp3` | 6,068,475 | MP3 | 252.682458 | 44,100 Hz | 2 | 192,000 bit/s | `7872FEC2E9C135411542F6690136EFEF1F63D1566E2D9A26D614FF0F7B6E23DB` |

FFprobe reported container-average bitrates of 192,113 bit/s for `Gaia.mp3` and 192,129 bit/s for `Legacy.mp3`.

## Runtime derivatives

The runtime files are deterministic derivatives of the verified MP3 sources using the fixed conversion settings below. Their `OggS` headers and stream properties were checked after conversion and again after copying into the repository.

| Track | Runtime path | Bytes | Codec | Duration (s) | Sample rate | Channels | Container-average bitrate | File SHA-256 |
| --- | --- | ---: | --- | ---: | ---: | ---: | ---: | --- |
| Gaia | `game/src/main/resources/assets/gaia/audio/music/gaia.ogg` | 6,864,969 | Vorbis in Ogg | 289.828571 | 44,100 Hz | 2 | 189,490 bit/s | `9C738E17AC92A1441C985F316F799CBF4943F46308A443CCE7619799DEC0A842` |
| Legacy | `game/src/main/resources/assets/gaia/audio/music/legacy.ogg` | 6,220,708 | Vorbis in Ogg | 252.682449 | 44,100 Hz | 2 | 196,949 bit/s | `34E7C060D65689BFAC03D35055CA9F692F8E89C796915458F7B23BE8CC7D9918` |

## Conversion record

- Conversion date: 2026-08-10 (Asia/Shanghai).
- Tool archive: Gyan `ffmpeg-9.0-essentials_build.zip` for Windows, ZIP size 111,167,378 bytes.
- Versioned release page: `https://github.com/GyanD/codexffmpeg/releases/tag/9.0`.
- Versioned archive URL used for the supplied download: `https://github.com/GyanD/codexffmpeg/releases/download/9.0/ffmpeg-9.0-essentials_build.zip`.
- Immutable checksum URL: `https://www.gyan.dev/ffmpeg/builds/packages/ffmpeg-9.0-essentials_build.zip.sha256`.
- Verified tool-archive SHA-256: `E6B54767A6065919048F1A098EB27211CA4E12B4348A05D88777A5855D0B6E71`.
- FFmpeg: `ffmpeg version 9.0-essentials_build-www.gyan.dev`, built with `gcc 16.1.0 (Rev2, Built by MSYS2 project)`.
- FFprobe: `ffprobe version 9.0-essentials_build-www.gyan.dev`, built with `gcc 16.1.0 (Rev2, Built by MSYS2 project)`.

The verified portable archive was extracted only into a task-owned system temporary directory. The source MP3s were copied into that temporary conversion directory, and these exact commands were run from there:

```powershell
ffmpeg -nostdin -y -i "Gaia.mp3" -map_metadata -1 -vn -c:a libvorbis -q:a 6 -ar 44100 -ac 2 "gaia.ogg"
ffmpeg -nostdin -y -i "Legacy.mp3" -map_metadata -1 -vn -c:a libvorbis -q:a 6 -ar 44100 -ac 2 "legacy.ogg"
```

The source and derivative metadata was collected with:

```powershell
ffprobe -v error -show_entries format=duration,bit_rate:stream=codec_name,sample_rate,channels,bit_rate -of json "<audio-file>"
```

The portable tool, temporary MP3 copies, and conversion outputs were removed from the temporary directory after the repository copies passed verification. FFmpeg was not installed and the system `PATH` was not modified.

## Runtime acceptance

- Windows development runtime: **PASS** on 2026-08-10 from direct human listening and interaction.
- The native OpenAL path produced audible Gaia playback; the runtime did not rely on the Silent fallback.
- Main Menu/gameplay continuity, pause duck/resume recovery, Master/Music Apply, focus-loss mute/recovery, and return-to-menu duplicate suppression were included in the requested checklist and reported **PASS**.
- No runtime duration, output-device model, or per-step timing was supplied, so none is inferred here.
- Apple Silicon MacBook Air / native arm64 / Java 26 complete Gate 13D audio
  checklist is **HUMAN-REPORTED PASS** on exact implementation candidate
  `a16855c19082a09f21bd53389cd24f711bd13f0e`. Reported coverage includes Gaia
  Main Menu/gameplay continuity, pause duck/resume, Master/Music application,
  mute-unfocused/focus recovery, duplicate suppression across Return to Menu and
  a second session, installDist, clean shutdown, and audible native OpenAL rather
  than Silent fallback.
- Exact macOS version, raw logs, runtime durations, audio-device model, and
  OpenAL device name were not supplied and are not inferred here.
