# On-device AI photo eraser

In the photo attachment editor, open Adjustments → AI Eraser (or hold the adjustments button). Brush over an object, choose Remove, review the result, and choose Done. Undo clears a pending selection or restores the preceding result. Hold the comparison label to see the original. Cancel leaves the attachment untouched.

The first removal downloads a 208,044,816-byte (~199 MiB) LaMa model over HTTPS. Subsequent removals work offline. Photo and mask data never leave the device. Downloads use a pinned revision and SHA-256 validation before the model is made available. Cancelled or failed downloads are discarded. Processing runs on a dedicated queue, and cancellation prevents a late result from being applied.

Model: [Carve/LaMa-ONNX](https://huggingface.co/Carve/LaMa-ONNX), revision `c3c0c9e468934d62e79c329e35d82dd09ff8c444`, `lama_fp32.onnx`, SHA-256 `1faef5301d78db7dda502fe59966957ec4b79dd64e16f03ed96913c7a4eb68d6`. The model card declares Apache-2.0. Original research and implementation: [LaMa](https://github.com/advimman/lama). Runtime: [ONNX Runtime](https://onnxruntime.ai/), Android 1.22.0 (MIT).

Inference uses RGB float32 NCHW inputs at 512×512 and a binary removal mask. A region around the brush selection supplies context. Predictions are resized back and composited only under the original mask, preserving all unselected pixels. Existing crop and drawing layers are reapplied by PhotoViewer when saving. Cleanup becomes the source for subsequent adjustments so reopening filters does not restore removed objects.

This feature applies to still photo attachments. It uses LaMa rather than Apple's proprietary Clean Up model. Detailed textures, large selections and low-memory devices can limit results; inference runs on CPU and may take several seconds.

Validation: the Android Java module compiles. The pinned model was checked against its SHA-256 and exercised with ONNX Runtime 1.22.0 on CPU. A standalone Android `app_process` harness ran the actual Java eraser engine on CONNECT_U7: two consecutive removals passed, pixels outside the selection remained identical, and cancellation was respected. Each removal took about 20 seconds on that device. The harness terminates with `Runtime.halt(0)` to avoid native-library static destruction during a standalone ART shell shutdown. The complete Telegram editor UI has not been exercised on-device.
