"""Generate golden fixtures consumed by the Swift, Kotlin and TypeScript tests.

Run from this directory:  python3 generate_fixtures.py
Output: ../fixtures/flightmath_fixtures.json
"""
import json
import math
import random
from pathlib import Path

import flightmath as fm


def random_rotation(rng: random.Random) -> list[list[float]]:
    # Uniform random unit quaternion (Shoemake) -> rotation matrix
    u1, u2, u3 = rng.random(), rng.random(), rng.random()
    q = (math.sqrt(1 - u1) * math.sin(2 * math.pi * u2),
         math.sqrt(1 - u1) * math.cos(2 * math.pi * u2),
         math.sqrt(u1) * math.sin(2 * math.pi * u3),
         math.sqrt(u1) * math.cos(2 * math.pi * u3))
    x, y, z, w = q
    return [
        [1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
        [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
        [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)],
    ]


def main() -> None:
    rng = random.Random(20261030)
    out: dict = {"version": 1, "tolerance": {"angle_deg": 1e-6, "metres": 1e-3, "pixels": 1e-4}}

    look = []
    for _ in range(40):
        olat, olon, oh = rng.uniform(-60, 65), rng.uniform(-180, 180), rng.uniform(0, 400)
        tlat, tlon, th = olat + rng.uniform(-0.6, 0.6), olon + rng.uniform(-0.6, 0.6), rng.uniform(200, 12500)
        la = fm.look_angles(olat, olon, oh, tlat, tlon, th)
        look.append({"observer": [olat, olon, oh], "target": [tlat, tlon, th],
                     "azimuth_deg": la.azimuth_deg, "elevation_deg": la.elevation_deg,
                     "slant_range_m": la.slant_range_m})
    out["look_angles"] = look

    dr = []
    for i in range(25):
        lat, lon, alt = rng.uniform(-70, 70), rng.uniform(-180, 180), rng.uniform(0, 12000)
        gs = None if i == 0 else rng.uniform(80, 560)
        trk = None if i == 0 else rng.uniform(0, 360)
        vr = None if i in (0, 1) else rng.uniform(-3000, 3000)
        dt = [-3.0, 0.0, 5.0, 12.5, 90.0][i % 5] if i < 5 else rng.uniform(0, 45)
        res = fm.dead_reckon(lat, lon, alt, gs, trk, vr, dt)
        dr.append({"input": {"lat": lat, "lon": lon, "alt_m": alt, "ground_speed_kt": gs,
                             "track_deg": trk, "vertical_rate_fpm": vr, "dt_s": dt},
                   "expected": list(res)})
    out["dead_reckon"] = dr

    proj = []
    for _ in range(60):
        r = random_rotation(rng)
        fov = rng.uniform(55, 75)
        aspect = rng.choice([4 / 3, 16 / 9])
        w, h = rng.choice([(390, 844), (430, 932), (412, 915), (360, 800)])
        k = fm.intrinsics_aspect_fill(fov, aspect, w, h)
        # Bias directions towards the camera axis so most land on screen
        cam_axis = fm.mat_vec(r, (0.0, 0.0, -1.0))
        jitter = [rng.gauss(0, 0.35) for _ in range(3)]
        d = [cam_axis[i] + jitter[i] for i in range(3)]
        if rng.random() < 0.15:
            d = [-x for x in d]
        n = math.sqrt(sum(x * x for x in d))
        d = tuple(x / n for x in d)
        p = fm.project(r, d, k, w, h)
        proj.append({"r_dw": r, "fov_long_deg": fov, "aspect": aspect, "view": [w, h],
                     "intrinsics": [k.fx, k.fy, k.cx, k.cy], "world_dir": list(d),
                     "in_front": p.in_front, "on_screen": p.on_screen,
                     "x": None if not p.in_front else p.x, "y": None if not p.in_front else p.y,
                     "edge_angle_rad": fm.edge_angle_rad(r, d)})
    out["projection"] = proj

    rot = []
    for _ in range(10):
        r = random_rotation(rng)
        deg = rng.uniform(-30, 30)
        rot.append({"r_dw": r, "degrees": deg, "expected": fm.rotate_about_up(r, deg)})
    out["rotate_about_up"] = rot

    ios = []
    for _ in range(5):
        r = random_rotation(rng)
        ios.append({"r_device_to_ref": r, "expected_r_dw": fm.ios_reference_to_enu(r)})
    out["ios_reference_to_enu"] = ios

    path = Path(__file__).resolve().parent.parent / "fixtures" / "flightmath_fixtures.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(out, indent=1) + "\n")
    print(f"wrote {path}")


if __name__ == "__main__":
    main()
