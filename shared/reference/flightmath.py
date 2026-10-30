"""
Flight View reference maths.

This module is the single source of truth for the geometry used by the iOS,
Android and Worker code. Every platform implementation is tested against the
fixtures generated from this file (see generate_fixtures.py), and this file is
itself tested against pymap3d (see test_flightmath.py).

Conventions
-----------
* Angles in public functions are degrees unless the name says otherwise.
* World frame is local ENU (east, north, up) at the observer.
* Heights are metres above the WGS84 ellipsoid (HAE).
* Device frame (iOS and Android, portrait): +X right, +Y towards the top of
  the screen, +Z out of the screen towards the user. The rear camera looks
  along -Z.
* A device attitude is a 3x3 row-major matrix R_dw that maps a vector in
  device coordinates to ENU world coordinates: w = R_dw @ d.
"""
from __future__ import annotations

import math
from dataclasses import dataclass

# WGS84
WGS84_A = 6378137.0
WGS84_F = 1.0 / 298.257223563
WGS84_E2 = WGS84_F * (2.0 - WGS84_F)
MEAN_EARTH_RADIUS_M = 6371008.8

KNOT_TO_MPS = 0.514444
FOOT_TO_M = 0.3048

# Dead reckoning is only trusted for a short window; beyond it we hold the
# last extrapolated point and the client marks the aircraft as stale.
MAX_EXTRAPOLATION_S = 60.0


@dataclass(frozen=True)
class LookAngles:
    azimuth_deg: float  # 0 = true north, clockwise, [0, 360)
    elevation_deg: float  # 0 = horizon, +90 = zenith
    slant_range_m: float


@dataclass(frozen=True)
class Intrinsics:
    fx: float
    fy: float
    cx: float
    cy: float


@dataclass(frozen=True)
class ScreenPoint:
    x: float
    y: float
    depth: float  # distance along the optical axis in unit-direction space
    in_front: bool
    on_screen: bool


def geodetic_to_ecef(lat_deg: float, lon_deg: float, h_m: float) -> tuple[float, float, float]:
    lat = math.radians(lat_deg)
    lon = math.radians(lon_deg)
    s = math.sin(lat)
    n = WGS84_A / math.sqrt(1.0 - WGS84_E2 * s * s)
    x = (n + h_m) * math.cos(lat) * math.cos(lon)
    y = (n + h_m) * math.cos(lat) * math.sin(lon)
    z = (n * (1.0 - WGS84_E2) + h_m) * s
    return x, y, z


def ecef_delta_to_enu(dx: float, dy: float, dz: float, lat0_deg: float, lon0_deg: float) -> tuple[float, float, float]:
    lat = math.radians(lat0_deg)
    lon = math.radians(lon0_deg)
    sl, cl = math.sin(lat), math.cos(lat)
    so, co = math.sin(lon), math.cos(lon)
    e = -so * dx + co * dy
    n = -sl * co * dx - sl * so * dy + cl * dz
    u = cl * co * dx + cl * so * dy + sl * dz
    return e, n, u


def enu_of_target(obs_lat: float, obs_lon: float, obs_h: float,
                  tgt_lat: float, tgt_lon: float, tgt_h: float) -> tuple[float, float, float]:
    ox, oy, oz = geodetic_to_ecef(obs_lat, obs_lon, obs_h)
    tx, ty, tz = geodetic_to_ecef(tgt_lat, tgt_lon, tgt_h)
    return ecef_delta_to_enu(tx - ox, ty - oy, tz - oz, obs_lat, obs_lon)


def look_angles(obs_lat: float, obs_lon: float, obs_h: float,
                tgt_lat: float, tgt_lon: float, tgt_h: float) -> LookAngles:
    e, n, u = enu_of_target(obs_lat, obs_lon, obs_h, tgt_lat, tgt_lon, tgt_h)
    horiz = math.hypot(e, n)
    az = math.degrees(math.atan2(e, n)) % 360.0
    el = math.degrees(math.atan2(u, horiz))
    return LookAngles(az, el, math.sqrt(e * e + n * n + u * u))


def direction_enu(azimuth_deg: float, elevation_deg: float) -> tuple[float, float, float]:
    az = math.radians(azimuth_deg)
    el = math.radians(elevation_deg)
    return math.cos(el) * math.sin(az), math.cos(el) * math.cos(az), math.sin(el)


def dead_reckon(lat_deg: float, lon_deg: float, alt_m: float,
                ground_speed_kt: float | None, track_deg: float | None,
                vertical_rate_fpm: float | None, dt_s: float) -> tuple[float, float, float]:
    """Advance an aircraft position along a great circle for dt_s seconds."""
    dt = max(0.0, min(dt_s, MAX_EXTRAPOLATION_S))
    if ground_speed_kt is None or track_deg is None or dt == 0.0:
        new_lat, new_lon = lat_deg, lon_deg
    else:
        dist = ground_speed_kt * KNOT_TO_MPS * dt
        delta = dist / MEAN_EARTH_RADIUS_M
        theta = math.radians(track_deg)
        phi1 = math.radians(lat_deg)
        lam1 = math.radians(lon_deg)
        phi2 = math.asin(math.sin(phi1) * math.cos(delta) +
                         math.cos(phi1) * math.sin(delta) * math.cos(theta))
        lam2 = lam1 + math.atan2(math.sin(theta) * math.sin(delta) * math.cos(phi1),
                                 math.cos(delta) - math.sin(phi1) * math.sin(phi2))
        new_lat = math.degrees(phi2)
        new_lon = (math.degrees(lam2) + 540.0) % 360.0 - 180.0
    new_alt = alt_m + ((vertical_rate_fpm or 0.0) * FOOT_TO_M / 60.0) * dt
    return new_lat, new_lon, max(new_alt, -500.0)


def rotate_about_up(matrix: list[list[float]], degrees_clockwise: float) -> list[list[float]]:
    """Pre-multiply an ENU-target attitude by a heading correction.

    Rotating clockwise (seen from above) by D increases every azimuth by D.
    Used for magnetic declination (Android) and the user's manual nudge.
    """
    d = math.radians(degrees_clockwise)
    c, s = math.cos(d), math.sin(d)
    rz = [[c, s, 0.0], [-s, c, 0.0], [0.0, 0.0, 1.0]]
    return mat_mul(rz, matrix)


def mat_mul(a: list[list[float]], b: list[list[float]]) -> list[list[float]]:
    return [[sum(a[i][k] * b[k][j] for k in range(3)) for j in range(3)] for i in range(3)]


def transpose(m: list[list[float]]) -> list[list[float]]:
    return [[m[j][i] for j in range(3)] for i in range(3)]


def mat_vec(m: list[list[float]], v: tuple[float, float, float]) -> tuple[float, float, float]:
    return (m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2],
            m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2],
            m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2])


def ios_reference_to_enu(r_device_to_ref: list[list[float]]) -> list[list[float]]:
    """CoreMotion xTrueNorthZVertical frame is (X north, Y west, Z up)."""
    c = [[0.0, -1.0, 0.0],   # e = -y_ref
         [1.0, 0.0, 0.0],    # n =  x_ref
         [0.0, 0.0, 1.0]]    # u =  z_ref
    return mat_mul(c, r_device_to_ref)


def intrinsics_aspect_fill(long_side_fov_deg: float, image_aspect_long_over_short: float,
                           view_w: float, view_h: float) -> Intrinsics:
    """Pinhole intrinsics, in view points, for a portrait preview using aspect-fill.

    The camera image in portrait is `1 x aspect` (short side horizontal, long
    side vertical). The preview scales it by max(w/1, h/aspect) and centres it.
    """
    aspect = image_aspect_long_over_short
    f_img = (aspect / 2.0) / math.tan(math.radians(long_side_fov_deg) / 2.0)
    scale = max(view_w / 1.0, view_h / aspect)
    f = f_img * scale
    return Intrinsics(fx=f, fy=f, cx=view_w / 2.0, cy=view_h / 2.0)


def project(r_dw: list[list[float]], world_dir: tuple[float, float, float],
            k: Intrinsics, view_w: float, view_h: float) -> ScreenPoint:
    d = mat_vec(transpose(r_dw), world_dir)  # world -> device
    xc, yc, zc = d[0], d[1], -d[2]           # camera looks along -Z
    if zc <= 1e-6:
        return ScreenPoint(float("nan"), float("nan"), zc, False, False)
    x = k.cx + k.fx * xc / zc
    y = k.cy - k.fy * yc / zc
    on = 0.0 <= x <= view_w and 0.0 <= y <= view_h
    return ScreenPoint(x, y, zc, True, on)


def edge_angle_rad(r_dw: list[list[float]], world_dir: tuple[float, float, float]) -> float:
    """Screen-space direction (0 = right, pi/2 = up) to turn towards a target.

    Used for off-screen indicators. Works for targets behind the camera too.
    """
    d = mat_vec(transpose(r_dw), world_dir)
    return math.atan2(d[1], d[0])
