# Raptor-KMP

[![Maven Central](https://img.shields.io/maven-central/v/eu.dotshell/raptor-kmp)](https://central.sonatype.com/artifact/eu.dotshell/raptor-kmp)

RAPTOR (Round-Based Public Transit Optimized Router) implementation in Kotlin Multiplatform (Android + iOS).

## Installation

Add to your `build.gradle.kts`:

```kotlin
dependencies {
    implementation("eu.dotshell:raptor-kmp:2.4.1")
}
```

## Usage (Android)

### Simple Usage (Single Period)

```kotlin
// Place your stops.bin and routes.bin files in assets folder
val raptor = RaptorLibrary(
    stopsBytes = assets.open("stops.bin").readBytes(),
    routesBytes = assets.open("routes.bin").readBytes()
)

// Search for stops
val originStops = raptor.searchStopsByName("Perrache")
val destStops = raptor.searchStopsByName("Cuire")

// Get optimized paths
val departureTime = 8 * 3600 // 08:00:00 in seconds
val journeys = raptor.getOptimizedPaths(
    originStopIds = originStops.map { it.id },
    destinationStopIds = destStops.map { it.id },
    departureTime = departureTime
)

// Display results
for (journey in journeys) {
    raptor.displayJourney(journey)
}
```

### Multi-Period Support

If you have multiple sets of transit data for different time periods (e.g., winter/summer schedules), you can load them all at once:

```kotlin
// Load multiple periods
val raptor = RaptorLibrary(listOf(
    PeriodData(
        periodId = "winter",
        stopsBytes = assets.open("stops_winter.bin").readBytes(),
        routesBytes = assets.open("routes_winter.bin").readBytes()
    ),
    PeriodData(
        periodId = "summer",
        stopsBytes = assets.open("stops_summer.bin").readBytes(),
        routesBytes = assets.open("routes_summer.bin").readBytes()
    )
))

// Check available periods
val periods = raptor.getAvailablePeriods() // Returns: ["winter", "summer"]

// Switch to a specific period
raptor.setPeriod("summer")

// All subsequent queries will use the summer schedule
val journeys = raptor.getOptimizedPaths(
    originStopIds = originStops.map { it.id },
    destinationStopIds = destStops.map { it.id },
    departureTime = departureTime
)

// Get current active period
val currentPeriod = raptor.getCurrentPeriod() // Returns: "summer"
```

### Small Heaps (v2.4.0)

On a phone, keep only the selected period in memory and read each period's files only when it is
selected:

```kotlin
val raptor = RaptorLibrary(listOf(
    PeriodData("weekday", { assets.open("weekday/stops.bin").readBytes() }, { assets.open("weekday/routes.bin").readBytes() }),
    PeriodData("saturday", { assets.open("saturday/stops.bin").readBytes() }, { assets.open("saturday/routes.bin").readBytes() }),
    PeriodData("sunday", { assets.open("sunday/stops.bin").readBytes() }, { assets.open("sunday/routes.bin").readBytes() })
), keepAllPeriods = false)

raptor.setPeriod("saturday")   // drops the weekday network, then builds the Saturday one

// When the app goes to the background: free the search buffers (rebuilt on the next search)
raptor.releaseSearchState()
```

With `keepAllPeriods = true` (the default, as before) every period stays built once selected. In
both modes the files' bytes are let go as soon as a period's network is built.

Since 2.4.0 the network itself is held in flat arrays: on a whole-France network (313 419 stops,
489 575 trips) the heap after loading went from 275 MB to 124 MB, and the smallest JVM heap that
loads it and runs searches from 384 MB to 224 MB, with identical journeys.

### Transfers (v2.4.1)

Between two rides a journey walks at most once, from the stop where the first ride arrived, as
in the RAPTOR paper. Before 2.4.1, the result depended on how the routes happened to be
numbered: a stop reached on foot could pass its walk on to a second walk, or hide a later ride
to the same stop that a better walk could have left from. The same network under other route
numbers then gave other journeys (3 in 1,000 on a whole-France network).

Journeys are now the same whatever the numbering. A few that relied on two walks in a row arrive
later (20 to 39 in 2,000 searches on france-live), and searches take about 15 % longer.

### Arrive-By Search

You can also search for routes that arrive before a specific time (useful for "I need to be there by 9am" scenarios):

```kotlin
// Find the best routes to arrive by 09:00
val arrivalTime = 9 * 3600 // 09:00:00 in seconds
val journeys = raptor.getOptimizedPathsArriveBy(
    originStopIds = originStops.map { it.id },
    destinationStopIds = destStops.map { it.id },
    arrivalTime = arrivalTime,
    searchWindowMinutes = 120 // Search departures up to 2 hours before arrival time
)

// The returned journeys will arrive at or before 09:00
// with the latest possible departure time
for (journey in journeys) {
    raptor.displayJourney(journey)
}
```

### Walking & Address-Based Queries (v2.0.0)

Journeys can start or end at arbitrary WGS84 coordinates (e.g. a geocoded address or POI)
instead of stops. The router walks to/from nearby stops, and walking competes inside the
optimization itself: a journey ending with a longer walk can beat one waiting for a later
connection. A pure-walk journey is proposed when both points are within walking range.

```kotlin
// From home (geocoded address) to a point of interest, departing at 08:00
val journeys = raptor.getOptimizedPaths(
    origin = Location.Point(lat = 45.7578, lon = 4.8320),
    destination = Location.Point(lat = 45.7605, lon = 4.8590),
    departureTime = 8 * 3600
)

// Mixed endpoints work too (stop ids on one side, coordinates on the other),
// and the arrive-by variant accepts the same Location endpoints:
val arriveBy = raptor.getOptimizedPathsArriveBy(
    origin = Location.StopIds(originStops.map { it.id }),
    destination = Location.Point(lat = 45.7605, lon = 4.8590),
    arrivalTime = 9 * 3600
)

// Walking model is configurable per query
val custom = raptor.getOptimizedPaths(
    origin = Location.Point(45.7578, 4.8320),
    destination = Location.Point(45.7605, 4.8590),
    departureTime = 8 * 3600,
    walking = WalkingParams(
        speedMetersPerSecond = 4.8 / 3.6,       // average walking speed
        detourFactor = 1.3,                     // street-network detour vs straight line
        maxAccessEgressDistanceMeters = 500.0,  // stop search radius around each point
        maxDirectWalkDistanceMeters = 1000.0    // max distance for a pure-walk journey
    )
)
```

Walk legs are regular `JourneyLeg`s with `isTransfer = true`, a `legType` of `WALK_ACCESS`,
`WALK_EGRESS` or `WALK_DIRECT`, and the coordinates of both ends (`fromLat`/`fromLon`/
`toLat`/`toLon`). A coordinate endpoint uses stop index `-1` — resolve names only for
indices `>= 0`. `Location.StopIds` on both sides behaves exactly like the classic id-based
methods.

When the app has access to a real pedestrian router (e.g. OSRM foot), it can supply exact
walk times instead of the great-circle estimate:

```kotlin
val journeys = raptor.getOptimizedPaths(
    origin = Location.ResolvedPoint(
        lat = 45.7578, lon = 4.8320,
        stops = listOf(StopWalk(stopId = 1234, walkSeconds = 240), StopWalk(1237, 310))
    ),
    destination = Location.Point(45.7605, 4.8590),
    departureTime = 8 * 3600,
    directWalkSecondsOverride = null // or the router's origin->destination walk duration
)
```

### Route Filtering (Whitelist/Blacklist)

You can restrict which lines are eligible during routing using route names or ids. This is useful to keep a journey within the same fare system or to exclude specific lines.

```kotlin
// Allow only specific lines by name
val journeys = raptor.getOptimizedPaths(
    originStopIds = originStops.map { it.id },
    destinationStopIds = destStops.map { it.id },
    departureTime = departureTime,
    allowedRouteNames = setOf("JD2", "JD3", "RX")
)

// Exclude specific lines by id
val journeysArriveBy = raptor.getOptimizedPathsArriveBy(
    originStopIds = originStops.map { it.id },
    destinationStopIds = destStops.map { it.id },
    arrivalTime = arrivalTime,
    blockedRouteIds = setOf(12, 27)
)

// Works with searchAndDisplayRoute too
raptor.searchAndDisplayRoute(
    originName = "Perrache",
    destinationName = "Cuire",
    departureTime = departureTime,
    allowedRouteNames = setOf("JD2", "JD3", "RX")
)
```

## Performance

Measured with JMH (2 separate JVM forks, 5 warmup + 10 measurement iterations of 1 s each) on an
Intel Core i7-11700F, 32 GB DDR4 2666 MHz, Windows 11, JDK 17. Times are average per query.
Origins and destinations are resolved by stop name (multi-stop sets); forward departs at 08:00,
arrive-by targets 09:00 with the default 120 min search window.

### TCL Lyon (v1.7.0) — 14 334 stops, 1 522 route variants, 35 290 trips (3.6 MB)

| Route | Forward | Arrive-By |
|:------|--------:|----------:|
| Perrache → Vaulx-en-Velin La Soie | 0.19 ms | 0.36 ms |
| Bellecour → Part-Dieu | 0.18 ms | 0.28 ms |
| Gare de Vaise → Oullins Centre | 0.38 ms | 0.69 ms |
| Perrache → Cuire | 0.38 ms | 0.56 ms |
| Laurent Bonnevay → Gorge de Loup | 0.37 ms | 0.75 ms |
| Part-Dieu → Bellecour | 0.17 ms | 0.19 ms |

Aggregate over 1 000 random O-D pairs (JMH, same config): forward **0.35 ms**, arrive-by **0.40 ms**
per query — arrive-by now costs barely more than a forward search thanks to the single backward
RAPTOR pass introduced in v1.7.0.

### RTM Marseille (v1.7.0) — 2 752 stops, 182 route variants, 10 596 trips (1.1 MB)

| Route | Forward | Arrive-By |
|:------|--------:|----------:|
| Vieux-Port → La Rose | 0.12 ms | 0.25 ms |
| Castellane → Bougainville | 0.08 ms | 0.11 ms |
| La Timone → Joliette | 0.10 ms | 0.19 ms |
| La Rose → Castellane | 0.10 ms | 0.22 ms |
| Noailles → Sainte-Marguerite Dromel | 0.06 ms | 0.13 ms |
| Bougainville → La Fourragère | 0.15 ms | 0.25 ms |

### IDFM Paris (v1.7.0) — 54 115 stops, 2 128 route variants, 93 127 trips (12.6 MB)

| Route | Forward | Arrive-By |
|:------|--------:|----------:|
| Gare de Lyon → Gare du Nord | 1.54 ms | 2.76 ms |
| Gare Saint-Lazare → Montparnasse Bienvenue | 2.08 ms | 4.63 ms |
| Charles de Gaulle - Étoile → Nation | 0.71 ms | 0.87 ms |
| République → Bastille | 0.89 ms | 0.92 ms |
| Gare du Nord → Gare Montparnasse | 5.71 ms | 8.09 ms |
| Bastille → Gare Saint-Lazare | 2.07 ms | 3.55 ms |
| Glacière → Bonne Nouvelle | 6.16 ms | 7.22 ms |

Since v1.7.0, arrive-by runs a single backward RAPTOR pass instead of a departure-time binary
search: on these Paris queries it is **4–10× faster** than v1.1.0, and costs barely more than a
forward search (~1.5× on average, versus ~7× before).
