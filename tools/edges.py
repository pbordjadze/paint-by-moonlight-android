#!/usr/bin/env python3
"""The coloring book's two maps of a photo, made on the host the way the app makes them.

    python3 tools/edges.py <photo.ppm> <out-dir> [--models DIR]

Writes <out-dir>/contours.pgm (HED, at most 1152 px on the long side) and <out-dir>/drawing.pgm
(the line drawing, at most 768 px), each the 8-bit map the app passes to the core, for
`moonlight-probe <photo.ppm> --contours contours.pgm --drawing drawing.pgm`. It runs the app's
own models (app/src/main/assets/models, written by tools/models/convert.py) with LiteRT's
Python interpreter, which runs the same CPU kernels (XNNPACK) the app's interpreter does, and
mirrors the app's pre- and post-processing (core/Sources/MoonlightCore/EdgeModels.swift): the
photo area-resampled in linear light as PaintCore's Resample.area, reflect-padded at the bottom
and right to the model's stride; HED's five sides upsampled bilinearly, averaged, through a
sigmoid; each probability cropped back and rounded to the nearest of 256 levels.

Needs numpy and ai-edge-litert (pip install ai-edge-litert numpy).
"""

import argparse
import os
import sys
from dataclasses import dataclass

import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODELS_DIR = os.path.join(ROOT, "app/src/main/assets/models")
MAX_LONG_SIDE = 1152


@dataclass(frozen=True)
class Spec:
    multiple: int      # the network's stride: the input is padded to a multiple of it
    scale_max: float   # what a byte of 255 is in the model's input
    long_side: int     # the long side the map is made at


MODELS = {"hed": Spec(16, 255.0, 1152), "lineart": Spec(4, 1.0, 768)}


def read_netpbm(path):
    with open(path, "rb") as f:
        data = f.read()
    tokens, i = [], 0
    while len(tokens) < 4:
        while data[i:i + 1].isspace():
            i += 1
        if data[i:i + 1] == b"#":
            while data[i:i + 1] not in (b"\n", b""):
                i += 1
            continue
        j = i
        while j < len(data) and not data[j:j + 1].isspace():
            j += 1
        tokens.append(data[i:j])
        i = j
    magic, w, h, maxval = tokens[0], int(tokens[1]), int(tokens[2]), int(tokens[3])
    assert magic in (b"P5", b"P6") and maxval == 255, f"{path}: expected an 8-bit binary P5 or P6 file"
    channels = 3 if magic == b"P6" else 1
    pixels = np.frombuffer(data[i + 1:i + 1 + w * h * channels], np.uint8)
    return pixels.reshape(h, w, 3) if channels == 3 else pixels.reshape(h, w)


def write_netpbm(path, array):
    magic = b"P6" if array.ndim == 3 else b"P5"
    with open(path, "wb") as f:
        f.write(magic + b"\n%d %d\n255\n" % (array.shape[1], array.shape[0]))
        f.write(np.ascontiguousarray(array, np.uint8).tobytes())


def map_size(width, height, max_long_side):
    """The photo's size scaled down to fit `max_long_side` (the app's EdgeModels.mapSize)."""
    limit = min(max(max_long_side, 16), MAX_LONG_SIDE)
    long = max(width, height)
    if long <= limit:
        return width, height
    scale = limit / long
    return max(1, int(np.floor(width * scale + 0.5))), max(1, int(np.floor(height * scale + 0.5)))


def _weights(n_in, n_out):
    """PaintCore's Resample.Weights as a dense n_out × n_in matrix."""
    m = np.zeros((n_out, n_in), np.float64)
    scale = n_in / n_out
    for o in range(n_out):
        lo, hi = o * scale, (o + 1) * scale
        if hi - lo < 1:
            c = (lo + hi) / 2
            lo, hi = c - 0.5, c + 0.5
        lo, hi = max(0.0, lo), min(float(n_in), hi)
        i = int(np.floor(lo))
        while i < hi and i < n_in:
            w = min(hi, i + 1) - max(lo, i)
            if w > 1e-9:
                m[o, i] = w
            i += 1
        m[o] /= m[o].sum()
    return m


def area_resample(rgb, width, height):
    """PaintCore's Resample.area of an opaque sRGB image: box filter in linear light."""
    h, w = rgb.shape[:2]
    if (w, h) == (width, height):
        return rgb
    c = rgb.astype(np.float64) / 255
    linear = np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)
    out = np.einsum("oy,yxc->oxc", _weights(h, height), np.einsum("ox,yxc->yoc", _weights(w, width), linear))
    out = np.clip(out, 0, 1)
    encoded = np.where(out <= 0.0031308, out * 12.92, 1.055 * out ** (1 / 2.4) - 0.055)
    return np.clip(np.floor(encoded * 255 + 0.5), 0, 255).astype(np.uint8)


def reflect_pad(rgb, multiple):
    h, w = rgb.shape[:2]
    ph, pw = (-h) % multiple, (-w) % multiple
    return rgb if ph == 0 and pw == 0 else np.pad(rgb, ((0, ph), (0, pw), (0, 0)), mode="reflect")


def model_input(name, rgb):
    """`rgb` (already at its map size) as the model's [1, H, W, 3] float input."""
    spec = MODELS[name]
    return (reflect_pad(rgb, spec.multiple).astype(np.float32) * np.float32(spec.scale_max / 255))[None]


def upsample(side, factor):
    """Bilinear upsampling by an integer factor: half-pixel centers, edges clamped (cv2.INTER_LINEAR)."""
    h, w = side.shape

    def axis(n):
        src = (np.arange(n * factor) + 0.5) / factor - 0.5
        src = np.clip(src, 0, n - 1)
        i0 = np.minimum(np.floor(src).astype(int), n - 1)
        i1 = np.minimum(i0 + 1, n - 1)
        return i0, i1, (src - i0).astype(np.float32)

    y0, y1, fy = axis(h)
    x0, x1, fx = axis(w)
    columns = side[:, x0] * (1 - fx)[None] + side[:, x1] * fx[None]
    return columns[y0] * (1 - fy)[:, None] + columns[y1] * fy[:, None]


def probability(name, outputs, height, width):
    """The model's outputs ([1, h, w, 1] arrays) as the map's probability at height × width."""
    if name == "lineart":
        return outputs[0][0, :height, :width, 0]
    sides = sorted((o[0, :, :, 0] for o in outputs), key=lambda s: -s.size)
    full = sides[0].shape
    up = [upsample(s, full[0] // s.shape[0]) for s in sides]
    mean = np.mean(np.stack(up, 0), 0, dtype=np.float32)
    return (1 / (1 + np.exp(-mean.astype(np.float64)))).astype(np.float32)[:height, :width]


def quantize(p):
    """A probability as a map level: the nearest of 256 (half up)."""
    return np.clip(np.floor(p.astype(np.float64) * 255 + 0.5), 0, 255).astype(np.uint8)


class Model:
    def __init__(self, name, path=None):
        from ai_edge_litert.interpreter import Interpreter
        self.name = name
        self.interpreter = Interpreter(path or os.path.join(MODELS_DIR, f"{name}.tflite"), num_threads=4)

    def probability(self, rgb):
        """The probability map of `rgb`, at its own size."""
        x = model_input(self.name, rgb)
        it = self.interpreter
        index = it.get_input_details()[0]["index"]
        it.resize_tensor_input(index, list(x.shape))
        it.allocate_tensors()
        it.set_tensor(index, x)
        it.invoke()
        outputs = [it.get_tensor(o["index"]) for o in it.get_output_details()]
        return probability(self.name, outputs, rgb.shape[0], rgb.shape[1])

    def map(self, photo):
        """The 8-bit map of a photo of any size, at the model's long side."""
        w, h = map_size(photo.shape[1], photo.shape[0], MODELS[self.name].long_side)
        return quantize(self.probability(area_resample(photo, w, h)))


def main(argv):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("photo")
    ap.add_argument("out")
    ap.add_argument("--models", default=MODELS_DIR)
    args = ap.parse_args(argv)
    import time
    photo = read_netpbm(args.photo)[:, :, :3]
    os.makedirs(args.out, exist_ok=True)
    for name, file in (("hed", "contours.pgm"), ("lineart", "drawing.pgm")):
        start = time.monotonic()
        m = Model(name, os.path.join(args.models, f"{name}.tflite")).map(photo)
        write_netpbm(os.path.join(args.out, file), m)
        print(f"{file}: {m.shape[1]}x{m.shape[0]}, {time.monotonic() - start:.2f} s")


if __name__ == "__main__":
    main(sys.argv[1:])
