# Data sources

All aircraft positions come from free, community-run ADS-B networks. Volunteers run receivers (typically an RTL-SDR dongle and readsb) that decode the raw 1090 MHz ADS-B and Mode S signals aircraft broadcast, and MLAT fills in aircraft that don't broadcast a position. Each network covers different parts of the world depending on where its feeders live, so Flight View queries all of them in parallel and merges the results by ICAO 24-bit address. The freshest position wins, and gaps (type, registration, operator) are filled from the other feeds.

Terms below were checked on 30 October 2026. They change, so check each provider before launching commercially.

| Feed | Key | Limit we enforce | Commercial use | Attribution shown |
| --- | --- | --- | --- | --- |
| [ADSB.lol](https://api.adsb.lol/docs) | None | 2 requests/s | Yes, ODbL 1.0 with attribution | "ADSB.lol (ODbL 1.0)" |
| [airplanes.live](https://airplanes.live/api-guide) | None | 1 request/s (published) | Non-commercial; ask for approval | "airplanes.live" |
| [adsb.fi](https://github.com/adsbfi/opendata) | None | 1 request/s (published) | Personal and non-commercial; ask for approval | "adsb.fi open data" |
| [FlyItalyADSB](https://flyitalyadsb.com/en/dati/confronto/) | Free, by email | 30 requests/min (published free tier) | Yes, CC BY-SA 4.0 | "FlyItalyADSB (CC BY-SA 4.0)" |
| [OpenSky Network](https://opensky-network.org/) | Optional free account | 1 request/10 s (400 credits/day anonymous, 4,000 with account) | Needs a written licence | "The OpenSky Network" |
| [ADSB One](https://github.com/ADSB-One/api) | None | 1 request/s | Not stated; repository archived April 2026 | "ADSB One" |

ADS-B Exchange is not included because its API is now paid only (its community plan on RapidAPI is non-commercial and costs money).

## How the Worker stays within limits

- **Regional fetches, per-user cut-outs**. Upstream calls are made per 2 degree region with a ~310 km radius (under every feed's 250 nm cap), cached for 4 seconds, and each user's ~80 km circle is cut out of that cached region. At UK latitudes one call covers everyone in a cell about 220 km by 140 km.
- **Global rate gate**. Each feed has a Durable Object that is the single authority on whether we may call it right now, so published limits hold across every Worker isolate worldwide. If a feed is busy, that round goes ahead without it.
- **Circuit breaker**. After three failures in a row a feed rests with exponential backoff, from 15 s up to 5 minutes.
- **Stale fallback**. If every feed is busy or down, the last good answer for the region is served for up to 90 seconds, and the apps dead-reckon positions forward.
- **Identification**. Every upstream call sends a `User-Agent` with a contact URL, which these projects ask for.

## Scaling up

The feeds together allow roughly 5 upstream calls a second, so the Worker can keep about 20 regions refreshed every 4 seconds, with quieter regions refreshed on demand. That comfortably covers a launch across the UK and Europe. Beyond that, in order of preference:

1. Feed data back. These networks exist because people feed them, they ask API users to contribute, and some (ADSB.lol's re-api, for example) give feeders extra access. An RTL-SDR stick on a Raspberry Pi is enough.
2. Add the FlyItalyADSB free key, which has a 500 km radius and allows commercial use.
3. Ask airplanes.live and adsb.fi for written approval if the app becomes commercial, or set `COMMERCIAL_MODE = "true"` to fall back to the feeds whose licences already allow it.
