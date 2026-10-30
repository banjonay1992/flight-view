"""Validate the reference maths against pymap3d and hand-checked geometry."""
import math
import random

import pymap3d
import pytest

import flightmath as fm

UPRIGHT_FACING_NORTH = [
    # columns are the device X, Y, Z axes expressed in ENU
    [1.0, 0.0, 0.0],
    [0.0, 0.0, -1.0],
    [0.0, 1.0, 0.0],
]


def test_ecef_matches_pymap3d():
    rng = random.Random(7)
    for _ in range(500):
        lat, lon, h = rng.uniform(-89, 89), rng.uniform(-180, 180), rng.uniform(-100, 15000)
        ours = fm.geodetic_to_ecef(lat, lon, h)
        ref = pymap3d.geodetic2ecef(lat, lon, h)
        for a, b in zip(ours, ref):
            assert a == pytest.approx(b, abs=1e-6)


def test_look_angles_match_pymap3d():
    rng = random.Random(11)
    for _ in range(500):
        olat, olon, oh = rng.uniform(-70, 70), rng.uniform(-180, 180), rng.uniform(0, 500)
        tlat = olat + rng.uniform(-0.8, 0.8)
        tlon = olon + rng.uniform(-0.8, 0.8)
        th = rng.uniform(300, 13000)
        ours = fm.look_angles(olat, olon, oh, tlat, tlon, th)
        az, el, rng_m = pymap3d.geodetic2aer(tlat, tlon, th, olat, olon, oh)
        assert ours.azimuth_deg == pytest.approx(az % 360.0, abs=1e-7)
        assert ours.elevation_deg == pytest.approx(el, abs=1e-7)
        assert ours.slant_range_m == pytest.approx(rng_m, rel=1e-9)


def test_zenith_and_horizon():
    la = fm.look_angles(50.15, -5.07, 0, 50.15, -5.07, 10000)
    assert la.elevation_deg == pytest.approx(90.0, abs=1e-6)
    # 100 km north at sea level sits below the geometric horizon (Earth curvature)
    la = fm.look_angles(50.0, -5.0, 0, 50.9, -5.0, 0)
    assert la.azimuth_deg == pytest.approx(0.0, abs=1e-6) or la.azimuth_deg == pytest.approx(360.0, abs=1e-6)
    assert la.elevation_deg < -0.3


def test_dead_reckon_due_east_on_equator():
    lat, lon, alt = fm.dead_reckon(0.0, 0.0, 1000.0, 360.0, 90.0, 600.0, 30.0)
    dist = 360.0 * fm.KNOT_TO_MPS * 30.0
    expected_lon = math.degrees(dist / fm.MEAN_EARTH_RADIUS_M)
    assert lat == pytest.approx(0.0, abs=1e-9)
    assert lon == pytest.approx(expected_lon, rel=1e-9)
    assert alt == pytest.approx(1000.0 + 600 * 0.3048 / 60 * 30, rel=1e-9)


def test_dead_reckon_clamps_and_handles_missing():
    a = fm.dead_reckon(51.0, -1.0, 9000.0, 450.0, 45.0, 0.0, 600.0)
    b = fm.dead_reckon(51.0, -1.0, 9000.0, 450.0, 45.0, 0.0, fm.MAX_EXTRAPOLATION_S)
    assert a == b
    assert fm.dead_reckon(51.0, -1.0, 9000.0, None, None, None, 10.0) == (51.0, -1.0, 9000.0)
    assert fm.dead_reckon(51.0, -1.0, 9000.0, 300, 10, 0, -5.0) == (51.0, -1.0, 9000.0)


def test_dead_reckon_wraps_antimeridian():
    lat, lon, _ = fm.dead_reckon(10.0, 179.99, 10000.0, 500.0, 90.0, 0.0, 60.0)
    assert -180.0 <= lon < -179.0


def test_projection_centre_and_directions():
    k = fm.intrinsics_aspect_fill(65.0, 4 / 3, 390, 844)
    centre = fm.project(UPRIGHT_FACING_NORTH, fm.direction_enu(0, 0), k, 390, 844)
    assert centre.x == pytest.approx(195) and centre.y == pytest.approx(422)
    right = fm.project(UPRIGHT_FACING_NORTH, fm.direction_enu(5, 0), k, 390, 844)
    assert right.x > 195 and right.y == pytest.approx(422)
    up = fm.project(UPRIGHT_FACING_NORTH, fm.direction_enu(0, 5), k, 390, 844)
    assert up.y < 422 and up.x == pytest.approx(195)
    behind = fm.project(UPRIGHT_FACING_NORTH, fm.direction_enu(180, 0), k, 390, 844)
    assert not behind.in_front


def test_projection_edge_matches_fov():
    # Portrait: the long sensor side is vertical. With a 390x844 view and a
    # 4:3 image, aspect-fill is height-limited, so the top edge of the view
    # sits at exactly half the long-side FOV.
    fov = 65.0
    k = fm.intrinsics_aspect_fill(fov, 4 / 3, 390, 844)
    top = fm.project(UPRIGHT_FACING_NORTH, fm.direction_enu(0, fov / 2), k, 390, 844)
    assert top.y == pytest.approx(0.0, abs=1e-6)


def test_declination_rotation():
    rotated = fm.rotate_about_up(UPRIGHT_FACING_NORTH, 10.0)
    # Camera axis is -Z; after +10 deg correction it should point at azimuth 10
    cam = fm.mat_vec(rotated, (0.0, 0.0, -1.0))
    az = math.degrees(math.atan2(cam[0], cam[1]))
    assert az == pytest.approx(10.0)


def test_ios_reference_conversion():
    # Device facing north upright, expressed in CoreMotion (N, W, U) frame
    r_ref = [[0.0, 0.0, -1.0],   # X_ref(north) row
             [-1.0, 0.0, 0.0],   # Y_ref(west) row
             [0.0, 1.0, 0.0]]    # Z_ref(up) row
    assert fm.ios_reference_to_enu(r_ref) == UPRIGHT_FACING_NORTH
