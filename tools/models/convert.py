#!/usr/bin/env python3
"""Converts the coloring book's two line models to the LiteRT models the app runs, reproducibly.

    python tools/models/convert.py [--weights DIR] [--out DIR] [--fixtures DIR]

Writes app/src/main/assets/models/hed.tflite and lineart.tflite, the same networks with the same
weights as the iOS app's Core ML models (paint-by-number's tools/models/convert_hed.py and
convert_lineart.py, whose docstrings are the full provenance), and checks each against PyTorch
at a size other than the one it was converted at. With --fixtures (paint-by-number's
App/PaintByNumberTests), it also maps the iOS tests' fixture photo the way the app does
(tools/edges.py) and reports how far the maps are from the iOS reference maps.

Needs torch 2.14 (CPU) and litert-torch 0.10 (which brings ai-edge-litert and
ai-edge-quantizer); numpy, Pillow.

Sources, both from the Hugging Face repository lllyasviel/Annotators at revision
982e7edaec38759d914a963c48c4726685de7d96, checked by sha256 before anything else:

- ControlNetHED.pth (29,444,406 bytes): HED, Saining Xie and Zhuowen Tu, "Holistically-Nested
  Edge Detection" (ICCV 2015), retrained by Lvmin Zhang for ControlNet on RGB input and released
  under the Apache License 2.0 (ControlNet's annotator/hed/__init__.py; controlnet_aux ships it
  as ControlNetHED_Apache2, written out here). The weights are float16 at the source.
- sk_model.pth (17,173,511 bytes): the contour-style generator of Caroline Chan, Frédo Durand and
  Phillip Isola, "Learning to generate line drawings that convey geometry and semantics" (CVPR
  2022), MIT License (Copyright (c) 2022 Caroline Chan), the network ControlNet's lineart
  annotator runs.

What each model computes (NHWC float32 in and out; any H and W, set with the interpreter's
resizeInput: every op is size-agnostic, which the check below proves):

- hed.tflite: input [1, H, W, 3], RGB 0...255, H and W multiples of 16 (the photo reflect-padded
  at the bottom and right). Outputs the five side outputs, logits at strides 1, 2, 4, 8 and 16
  ([1, H/2^k, W/2^k, 1]). The edge probability is the sides upsampled bilinearly to H × W
  (half-pixel centers, edges clamped), averaged, through a sigmoid: done by the caller
  (MoonlightCore's EdgeModels.swift, tools/edges.py), because a resize to a size fixed at
  conversion would pin the model to one photo shape.
- lineart.tflite: input [1, H, W, 3], RGB 0...1, H and W multiples of 4. Output [1, H, W, 1], the
  ink probability (one minus the generator's sigmoid, which is paper).

Precision: every convolution's weights are stored as float16 with a dequantize op (exact for
HED, whose source is float16; the line generator's float32 weights are rounded, as iOS rounds
them) and the graph computes in float32, so the CPU (XNNPACK) computes what iOS's Core ML does
on its CPU, up to compute-order noise.
"""

import argparse
import hashlib
import os
import sys

import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(ROOT, "tools"))
import edges  # noqa: E402

REPO = "lllyasviel/Annotators"
REVISION = "982e7edaec38759d914a963c48c4726685de7d96"
SOURCES = {
    "hed": ("ControlNetHED.pth", "5ca93762ffd68a29fee1af9d495bf6aab80ae86f08905fb35472a083a4c7a8fa"),
    "lineart": ("sk_model.pth", "c686ced2a666b4850b4bb6ccf0748031c3eda9f822de73a34b8979970d90f0c6"),
}
DEFAULT_OUT = os.path.join(ROOT, "app/src/main/assets/models")


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def fetch(name, directory):
    filename, digest = SOURCES[name]
    path = os.path.join(directory, filename)
    if not os.path.exists(path):
        import urllib.request
        os.makedirs(directory, exist_ok=True)
        url = f"https://huggingface.co/{REPO}/resolve/{REVISION}/{filename}"
        print(f"downloading {url}")
        urllib.request.urlretrieve(url, path)
    found = sha256(path)
    if found != digest:
        sys.exit(f"{path}: sha256 {found}, expected {digest}")
    return path


def hed_network():
    """controlnet_aux's ControlNetHED_Apache2, written out: a VGG-16 trunk with five side outputs."""
    import torch
    import torch.nn as nn
    import torch.nn.functional as F

    class DoubleConvBlock(nn.Module):
        def __init__(self, cin, cout, layers):
            super().__init__()
            self.convs = nn.Sequential(
                nn.Conv2d(cin, cout, 3, padding=1), *[nn.Conv2d(cout, cout, 3, padding=1) for _ in range(layers - 1)])
            self.projection = nn.Conv2d(cout, 1, 1)

        def forward(self, x, down_sampling=False):
            h = F.max_pool2d(x, 2, 2) if down_sampling else x
            for conv in self.convs:
                h = F.relu(conv(h))
            return h, self.projection(h)

    class ControlNetHED(nn.Module):
        def __init__(self):
            super().__init__()
            self.norm = nn.Parameter(torch.zeros(1, 3, 1, 1))
            self.block1 = DoubleConvBlock(3, 64, 2)
            self.block2 = DoubleConvBlock(64, 128, 2)
            self.block3 = DoubleConvBlock(128, 256, 3)
            self.block4 = DoubleConvBlock(256, 512, 3)
            self.block5 = DoubleConvBlock(512, 512, 3)

        def forward(self, x):
            h = x - self.norm
            h, p1 = self.block1(h)
            h, p2 = self.block2(h, True)
            h, p3 = self.block3(h, True)
            h, p4 = self.block4(h, True)
            h, p5 = self.block5(h, True)
            return p1, p2, p3, p4, p5

    return ControlNetHED()


def lineart_network():
    """Informative Drawings' Generator(3, 1, n_residual_blocks=3, sigmoid=True), written out."""
    import torch.nn as nn

    class ResidualBlock(nn.Module):
        def __init__(self, features):
            super().__init__()
            self.conv_block = nn.Sequential(
                nn.ReflectionPad2d(1), nn.Conv2d(features, features, 3), nn.InstanceNorm2d(features), nn.ReLU(inplace=True),
                nn.ReflectionPad2d(1), nn.Conv2d(features, features, 3), nn.InstanceNorm2d(features))

        def forward(self, x):
            return x + self.conv_block(x)

    class Generator(nn.Module):
        def __init__(self):
            super().__init__()
            self.model0 = nn.Sequential(nn.ReflectionPad2d(3), nn.Conv2d(3, 64, 7), nn.InstanceNorm2d(64), nn.ReLU(inplace=True))
            self.model1 = nn.Sequential(
                nn.Conv2d(64, 128, 3, stride=2, padding=1), nn.InstanceNorm2d(128), nn.ReLU(inplace=True),
                nn.Conv2d(128, 256, 3, stride=2, padding=1), nn.InstanceNorm2d(256), nn.ReLU(inplace=True))
            self.model2 = nn.Sequential(*[ResidualBlock(256) for _ in range(3)])
            self.model3 = nn.Sequential(
                nn.ConvTranspose2d(256, 128, 3, stride=2, padding=1, output_padding=1), nn.InstanceNorm2d(128), nn.ReLU(inplace=True),
                nn.ConvTranspose2d(128, 64, 3, stride=2, padding=1, output_padding=1), nn.InstanceNorm2d(64), nn.ReLU(inplace=True))
            self.model4 = nn.Sequential(nn.ReflectionPad2d(3), nn.Conv2d(64, 1, 7), nn.Sigmoid())

        def forward(self, x):
            return self.model4(self.model3(self.model2(self.model1(self.model0(x)))))

    return Generator()


def module(name, weights):
    """The converted graph, NHWC in and out, with the weights rounded to float16 as stored."""
    import torch

    torch.manual_seed(0)
    net = hed_network() if name == "hed" else lineart_network()
    net.load_state_dict(torch.load(weights, map_location="cpu"))
    net = net.half().float().eval()
    SubPixel = sub_pixel_class()

    class Sides(torch.nn.Module):
        def __init__(self):
            super().__init__()
            self.net = net

        def forward(self, image):
            return tuple(s.permute(0, 2, 3, 1) for s in self.net(image.permute(0, 3, 1, 2)))

    class Ink(torch.nn.Module):
        def __init__(self):
            super().__init__()
            self.net = net

        def forward(self, image):
            return (1.0 - self.net(image.permute(0, 3, 1, 2))).permute(0, 2, 3, 1)

    if name == "lineart":
        net.model3[0] = SubPixel(net.model3[0])
        net.model3[3] = SubPixel(net.model3[3])
    return (Sides() if name == "hed" else Ink()).eval()


def sub_pixel_class():
    import torch
    import torch.nn.functional as F

    class SubPixel(torch.nn.Module):
        """ConvTranspose2d(k=3, stride=2, padding=1, output_padding=1) as a 2 × 2 convolution into
        the four phases of each output pixel, interleaved: the same sums, in an order LiteRT can
        resize (its transposed convolution bakes in the output size, and the converter can't
        lower the output padding at all). The interleave is written as TensorFlow's depth to
        space, which `depth_to_space` puts in its place after conversion."""

        TAPS = {(0, 0): 1, (1, 0): 2, (1, 1): 0}  # (phase, tap) -> the kernel index it reads

        def __init__(self, transposed):
            super().__init__()
            w = transposed.weight.detach()
            cin, cout = w.shape[:2]
            k = torch.zeros(4 * cout, cin, 2, 2)
            for (py, ty), ky in self.TAPS.items():
                for (px, tx), kx in self.TAPS.items():
                    phase = py * 2 + px
                    k[phase * cout:(phase + 1) * cout, :, ty, tx] = w[:, :, ky, kx].t()
            self.register_buffer("kernel", k)
            self.register_buffer("bias", transposed.bias.detach().repeat(4))
            self.cout = cout

        def forward(self, x):
            y = F.conv2d(F.pad(x, (0, 1, 0, 1)), self.kernel, self.bias)
            n, _, h, w = y.shape
            return y.view(n, 2, 2, self.cout, h, w).permute(0, 3, 4, 1, 5, 2).reshape(n, self.cout, 2 * h, 2 * w)

    return SubPixel


def depth_to_space(path):
    """Replaces each interleave SubPixel lowered to TRANSPOSE, RESHAPE (rank 6), TRANSPOSE, RESHAPE,
    whose shapes are fixed at conversion, by DEPTH_TO_SPACE and a TRANSPOSE, which follow the
    input's size. Returns how many it replaced."""
    from ai_edge_litert import schema_py_generated as schema
    from ai_edge_litert.tools import flatbuffer_utils

    model = flatbuffer_utils.read_model(path)
    graph = model.subgraphs[0]
    codes = [flatbuffer_utils.get_builtin_code_from_operator_code(c) for c in model.operatorCodes]
    op = schema.BuiltinOperator
    if op.DEPTH_TO_SPACE not in codes:
        code = schema.OperatorCodeT()
        code.builtinCode = code.deprecatedBuiltinCode = op.DEPTH_TO_SPACE
        code.version = 1
        model.operatorCodes.append(code)
        codes.append(op.DEPTH_TO_SPACE)
    d2s = codes.index(op.DEPTH_TO_SPACE)

    def kind(o):
        return codes[o.opcodeIndex]

    def consumers(tensor):
        return [o for o in graph.operators if tensor in list(o.inputs)]

    replaced = 0
    for r1 in [o for o in graph.operators if kind(o) == op.RESHAPE and len(graph.tensors[o.outputs[0]].shape) == 6]:
        t1 = next(o for o in graph.operators if r1.inputs[0] in list(o.outputs))
        (t2,) = consumers(r1.outputs[0])
        (r2,) = consumers(t2.outputs[0])
        assert kind(t1) == op.TRANSPOSE and kind(t2) == op.TRANSPOSE and kind(r2) == op.RESHAPE
        assert len(consumers(t1.outputs[0])) == 1
        source = graph.tensors[t1.inputs[0]]
        n, h, w, c4 = source.shape
        depth = schema.TensorT()
        depth.shape = [n, 2 * h, 2 * w, c4 // 4]
        depth.type = source.type
        depth.buffer = 0
        depth.name = b"depth_to_space_%d" % replaced
        graph.tensors.append(depth)
        to_space = schema.OperatorT()
        to_space.opcodeIndex = d2s
        to_space.inputs = [t1.inputs[0]]
        to_space.outputs = [len(graph.tensors) - 1]
        to_space.builtinOptionsType = schema.BuiltinOptions.DepthToSpaceOptions
        to_space.builtinOptions = schema.DepthToSpaceOptionsT()
        to_space.builtinOptions.blockSize = 2
        # t1 is NHWC to NCHW: its permutation is the one the interleaved result needs too.
        to_nchw = schema.OperatorT()
        to_nchw.opcodeIndex = t1.opcodeIndex
        to_nchw.inputs = [len(graph.tensors) - 1, t1.inputs[1]]
        to_nchw.outputs = [r2.outputs[0]]
        to_nchw.builtinOptionsType = t1.builtinOptionsType
        to_nchw.builtinOptions = t1.builtinOptions
        at = graph.operators.index(t1)
        for o in (t1, r1, t2, r2):
            graph.operators.remove(o)
        graph.operators[at:at] = [to_space, to_nchw]
        replaced += 1
    flatbuffer_utils.write_model(model, path)
    return replaced


def convert(name, graph, out):
    import torch
    import litert_torch
    from ai_edge_quantizer import qtyping, quantizer

    multiple = edges.MODELS[name].multiple
    sample = (torch.rand(1, 12 * multiple, 18 * multiple, 3, generator=torch.Generator().manual_seed(0)) * edges.MODELS[name].scale_max,)
    float32 = out + ".float32"
    litert_torch.convert(graph, sample).export(float32)
    if name == "lineart":
        assert depth_to_space(float32) == 2

    q = quantizer.Quantizer(float32)
    for op in (qtyping.TFLOperationName.CONV_2D, qtyping.TFLOperationName.CONV_2D_TRANSPOSE):
        q.update_quantization_recipe(
            regex=".*", algorithm_key=quantizer.AlgorithmName.FLOAT_CASTING, operation_name=op,
            op_config=qtyping.OpQuantizationConfig(
                weight_tensor_config=qtyping.TensorQuantizationConfig(num_bits=16, dtype=qtyping.TensorDataType.FLOAT),
                compute_precision=qtyping.ComputePrecision.FLOAT, explicit_dequantize=True))
    with open(out, "wb") as f:
        f.write(q.quantize().quantized_model)
    os.remove(float32)


def check(name, graph, path):
    """The model at sizes it wasn't converted at, against PyTorch: the largest probability apart."""
    import torch

    rng = np.random.default_rng(1)
    worst = 0.0
    model = edges.Model(name, path)
    for h, w in ((168, 248), (100, 152), (333, 211)):
        rgb = rng.integers(0, 256, (h, w, 3), dtype=np.uint8)
        x = edges.model_input(name, rgb)
        with torch.no_grad():
            outs = graph(torch.from_numpy(x))
        outs = [o.numpy() for o in (outs if isinstance(outs, tuple) else (outs,))]
        expected = edges.probability(name, outs, h, w)
        worst = max(worst, float(np.abs(model.probability(rgb) - expected).max()))
    return worst


def main(argv):
    import warnings
    warnings.filterwarnings("ignore")
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--weights", default=os.path.join(ROOT, "build/weights"), help="where the source weights are (downloaded if missing)")
    ap.add_argument("--out", default=DEFAULT_OUT)
    ap.add_argument("--fixtures", help="paint-by-number's App/PaintByNumberTests, to compare with the iOS reference maps")
    args = ap.parse_args(argv)
    os.makedirs(args.out, exist_ok=True)
    for name in ("hed", "lineart"):
        weights = fetch(name, args.weights)
        graph = module(name, weights)
        path = os.path.join(args.out, f"{name}.tflite")
        convert(name, graph, path)
        print(f"{os.path.relpath(path, ROOT)}: {os.path.getsize(path):,} bytes, sha256 {sha256(path)}")
        print(f"  against PyTorch at three other sizes: largest |Δp| {check(name, graph, path):.2e}")
    if args.fixtures:
        rgb = edges.read_netpbm(os.path.join(args.fixtures, "HEDFixture.ppm"))
        for name, reference in (("hed", "HEDFixture.pgm"), ("lineart", "LineArtFixture.pgm")):
            ours = edges.quantize(edges.Model(name, os.path.join(args.out, f"{name}.tflite")).probability(rgb))
            theirs = edges.read_netpbm(os.path.join(args.fixtures, reference))
            d = np.abs(ours.astype(int) - theirs.astype(int))
            print(f"{name} vs iOS's {reference}: largest difference {d.max()} levels, "
                  f"{int((d > 0).sum())} of {d.size} pixels differ")


if __name__ == "__main__":
    main(sys.argv[1:])
