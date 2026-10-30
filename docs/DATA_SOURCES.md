# Data sources

All aircraft positions come from free, community-run ADS-B networks. Volunteers run receivers (typically an RTL-SDR dongle and readsb) that decode the raw 1090 MHz ADS-B and Mode S signals aircraft broadcast, and MLAT fills in aircraft that don't broadcast a position. Each network covers different parts of the world depending on where its feeders live, so Flight View combines several of them and merges the results by ICAO 24-bit address. The freshest position wins, and gaps (type, registration, operator) are filled from the other feeds.

## Hybrid fetching

Some networks block requests from cloud providers, including Cloudflare Workers, but serve ordinary clients. So the feeds are split by where they can be reached from:

| Feed | Queried by | Key | Pacing | Licence | Status (30 Oct 2026) |
| --- | --- | --- | --- | --- | --- |
| [ADSB.lol](https://api.adsb.lol/docs) | Worker | None | 2 requests/s, shared per 2 degree region | ODbL 1.0, commercial use with attribution | Working |
| [FlyItalyADSB](https://flyitalyadsb.com/en/dati/confronto/) | Worker | Free, by email | 30 requests/min | CC BY-SA 4.0, commercial use allowed | Ready, skipped until `FLYITALY_API_KEY` is set |
| [adsb.fi](https://github.com/adsbfi) | Each phone | None | 1 request per 2 s per phone | Personal, non-commercial | Working from phones; blocks Cloudflare |
| [OpenSky Network](https://opensky-network.org/) | Each phone | None | 1 request per 20 s per phone (anonymous: 400 credits a day) | Non-commercial; licence needed for commercial use | Working from phones; times out from Cloudflare |
| [airplanes.live](https://airplanes.live/api-guide) | Worker adapter, disabled | Approval required | | Non-commercial | Returns 403 asking API users to email them |
| ADSB One | Worker adapter, disabled | | | | Blocks all automated access; repository archived |

Phones send the direct feeds only the same coarse tile centre (0.1 degree, about 11 km) the Worker receives. Those two feeds can see the IP address of the phone, which the privacy notes state.

ADS-B Exchange is not included because its API is paid only.

## How the Worker stays within limits

- **Regional fetches, per-user cut-outs**. Upstream calls are made per 2 degree region with a ~310 km radius (under every feed's 250 nm cap), cached for 4 seconds, and each user's ~80 km circle is cut out of that cached region. At UK latitudes one call covers everyone in a cell about 220 km by 140 km.
- **Global rate gate**. Each feed has a Durable Object that is the single authority on whether we may call it right now, so published limits hold across every Worker isolate worldwide. If a feed is busy, that round goes ahead without it.
- **Circuit breaker**. After three failures in a row a feed rests with exponential backoff, from 15 s up to 5 minutes.
- **Stale-while-revalidate**. Once a region has been fetched, requests are answered instantly from the last good result (kept up to 90 seconds) while a refresh runs in the background. The apps dead-reckon every aircraft forward, so a few seconds of age is invisible.
- **Identification**. Every upstream call sends a `User-Agent` with a contact URL, which these projects ask for.

## Scaling up

The feeds together allow roughly 5 upstream calls a second, so the Worker can keep about 20 regions refreshed every 4 seconds, with quieter regions refreshed on demand. That comfortably covers a launch across the UK and Europe. Beyond that, in order of preference:

1. Feed data back. These networks exist because people feed them, they ask API users to contribute, and some (ADSB.lol's re-api, for example) give feeders extra access. An RTL-SDR stick on a Raspberry Pi is enough.
2. Add the FlyItalyADSB free key, which has a 500 km radius and allows commercial use.
3. Email airplanes.live for API access, and ask adsb.fi and OpenSky for written approval if the app ever becomes commercial. Until then `COMMERCIAL_MODE = "true"` falls back to the feeds whose licences already allow it.
