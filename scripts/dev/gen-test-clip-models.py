"""Writes the two tiny stand-in ONNX models used by the image-text tests (src/test/resources/onnx/clip-*.onnx).

They have the official Chinese-CLIP export contract (input `image` float [1,3,224,224] -> `unnorm_image_features`,
input `text` int64 [1,52] -> `unnorm_text_features`) but no learned weights:
- image: 56x56 average pooling of the normalised channels -> 48 numbers (R,G,B for a 4x4 grid) padded to 64.
- text: if the token for 火 (fire) is present, a "red" direction over the same 48 numbers; otherwise a neutral axis.
So "火焰" finds red garments and similar colours/layouts give similar vectors, enough to test the whole chain.
Dev only: `python3 scripts/dev/gen-test-clip-models.py <vocab.txt>`; requires `onnx`.
"""
import sys
import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper

vocab = [l.rstrip("\n") for l in open(sys.argv[1], encoding="utf-8")]
red = vocab.index("火")   # 火 (fire): a word that is not a colour filter, so it reaches the picture match
out_dir = "src/test/resources/onnx/"

# Image tower.
pool = helper.make_node("AveragePool", ["image"], ["pooled"], kernel_shape=[56, 56], strides=[56, 56])
shape = numpy_helper.from_array(np.array([1, 48], dtype=np.int64), "flat_shape")
flat = helper.make_node("Reshape", ["pooled", "flat_shape"], ["flat"])
w = np.zeros((48, 64), dtype=np.float32); w[:, :48] = np.eye(48, dtype=np.float32)
weights = numpy_helper.from_array(w, "w")
bias = numpy_helper.from_array(np.full((64,), 0.0, dtype=np.float32), "b")
mm = helper.make_node("MatMul", ["flat", "w"], ["mm"])
add = helper.make_node("Add", ["mm", "b"], ["unnorm_image_features"])
g = helper.make_graph([pool, flat, mm, add], "tiny-clip-image",
    [helper.make_tensor_value_info("image", TensorProto.FLOAT, [1, 3, 224, 224])],
    [helper.make_tensor_value_info("unnorm_image_features", TensorProto.FLOAT, [1, 64])], [shape, weights, bias])
m = helper.make_model(g, opset_imports=[helper.make_opsetid("", 13)], producer_name="smart-closet-test"); m.ir_version = 7
onnx.checker.check_model(m); onnx.save(m, out_dir + "clip-image.onnx")

# Text tower: has_red in {0,1}; features = has_red * RED + (1 - has_red) * NEUTRAL.
red_dir = np.zeros((64,), dtype=np.float32)
for cell in range(16):
    red_dir[cell] = 1.0; red_dir[16 + cell] = -1.0; red_dir[32 + cell] = -1.0   # channel-major: R cells, G cells, B cells
neutral = np.zeros((64,), dtype=np.float32); neutral[63] = 1.0
nodes = [
    helper.make_node("Equal", ["text", "red_id"], ["is_red"]),
    helper.make_node("Cast", ["is_red"], ["is_red_f"], to=TensorProto.FLOAT),
    helper.make_node("ReduceMax", ["is_red_f"], ["has_red"], axes=[1], keepdims=1),   # [1,1]
    helper.make_node("Sub", ["one", "has_red"], ["no_red"]),
    helper.make_node("Mul", ["has_red", "red_dir"], ["a"]),
    helper.make_node("Mul", ["no_red", "neutral"], ["b2"]),
    helper.make_node("Add", ["a", "b2"], ["unnorm_text_features"]),
]
inits = [numpy_helper.from_array(np.array(red, dtype=np.int64), "red_id"), numpy_helper.from_array(np.array([[1.0]], dtype=np.float32), "one"),
         numpy_helper.from_array(red_dir.reshape(1, 64), "red_dir"), numpy_helper.from_array(neutral.reshape(1, 64), "neutral")]
g = helper.make_graph(nodes, "tiny-clip-text", [helper.make_tensor_value_info("text", TensorProto.INT64, [1, 52])],
    [helper.make_tensor_value_info("unnorm_text_features", TensorProto.FLOAT, [1, 64])], inits)
m = helper.make_model(g, opset_imports=[helper.make_opsetid("", 13)], producer_name="smart-closet-test"); m.ir_version = 7
onnx.checker.check_model(m); onnx.save(m, out_dir + "clip-text.onnx")
print("red token id", red)
