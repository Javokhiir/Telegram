package org.telegram.messenger;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.FloatBuffer;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.concurrent.CancellationException;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/** LaMa inference stays on device. Only the model weights are downloaded. */
public final class UMessageAiEraser {
    private static final int SIZE = 512;
    private static final long MODEL_BYTES = 208044816L;
    private static final String MODEL_SHA = "1faef5301d78db7dda502fe59966957ec4b79dd64e16f03ed96913c7a4eb68d6";
    private static final String MODEL_URL = "https://huggingface.co/Carve/LaMa-ONNX/resolve/c3c0c9e468934d62e79c329e35d82dd09ff8c444/lama_fp32.onnx";

    public interface Progress {
        boolean isCancelled();
        void onDownload(int percent);
    }

    public static boolean isModelReady() {
        return modelFile().length() == MODEL_BYTES;
    }

    private static File modelFile() {
        return new File(ApplicationLoader.applicationContext.getFilesDir(), "ai-eraser-lama-v1.onnx");
    }

    private static void checkCancelled(Progress progress) {
        if (progress.isCancelled()) throw new CancellationException();
    }

    private static synchronized File prepareModel(Progress progress) throws Exception {
        File target = modelFile();
        if (isModelReady()) return target;
        File partial = new File(target.getPath() + ".download");
        HttpURLConnection connection = (HttpURLConnection) new URL(MODEL_URL).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(15000);
        try {
            if (connection.getResponseCode() != 200) throw new IOException("Model download failed");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total = 0;
            int lastPercent = -1;
            try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(partial)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    checkCancelled(progress);
                    total += count;
                    if (total > MODEL_BYTES) throw new IOException("Unexpected model size");
                    digest.update(buffer, 0, count);
                    output.write(buffer, 0, count);
                    int percent = (int) (total * 100 / MODEL_BYTES);
                    if (percent != lastPercent) {
                        progress.onDownload(percent);
                        lastPercent = percent;
                    }
                }
            }
            StringBuilder sha = new StringBuilder();
            for (byte value : digest.digest()) sha.append(String.format(java.util.Locale.US, "%02x", value & 255));
            if (total != MODEL_BYTES || !MODEL_SHA.equals(sha.toString())) throw new IOException("Invalid model checksum");
            checkCancelled(progress);
            if (!partial.renameTo(target)) throw new IOException("Cannot store AI model");
            return target;
        } finally {
            connection.disconnect();
            // A partial cache is never used for inference.
            if (partial.exists()) partial.delete();
        }
    }

    public static Bitmap erase(Bitmap source, Bitmap mask, Progress progress) throws Exception {
        File model = prepareModel(progress);
        checkCancelled(progress);
        progress.onDownload(101);
        int width = source.getWidth(), height = source.getHeight();
        int[] maskPixels = new int[width * height];
        mask.getPixels(maskPixels, 0, width, 0, 0, width, height);
        int left = width, top = height, right = -1, bottom = -1;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (Color.alpha(maskPixels[y * width + x]) > 0) {
                    left = Math.min(left, x); top = Math.min(top, y);
                    right = Math.max(right, x); bottom = Math.max(bottom, y);
                }
            }
        }
        if (right < left) throw new IllegalArgumentException("Empty selection");
        // Keep context around the selection, retaining full resolution outside it.
        int side = Math.min(Math.max(width, height), Math.max(256, Math.max(right - left + 1, bottom - top + 1) * 2));
        int cropWidth = Math.min(width, side), cropHeight = Math.min(height, side);
        left = Math.max(0, Math.min(width - cropWidth, (left + right - cropWidth) / 2));
        top = Math.max(0, Math.min(height - cropHeight, (top + bottom - cropHeight) / 2));
        Rect region = new Rect(left, top, left + cropWidth, top + cropHeight);
        Bitmap inputBitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Bitmap maskBitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        new Canvas(inputBitmap).drawBitmap(source, region, new Rect(0, 0, SIZE, SIZE), paint);
        new Canvas(maskBitmap).drawBitmap(mask, region, new Rect(0, 0, SIZE, SIZE), paint);
        int pixelsCount = SIZE * SIZE;
        int[] pixels = new int[pixelsCount], smallMask = new int[pixelsCount];
        inputBitmap.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE);
        maskBitmap.getPixels(smallMask, 0, SIZE, 0, 0, SIZE, SIZE);
        inputBitmap.recycle(); maskBitmap.recycle();
        float[] imageData = new float[pixelsCount * 3], maskData = new float[pixelsCount];
        for (int i = 0; i < pixelsCount; i++) {
            imageData[i] = Color.red(pixels[i]) / 255f;
            imageData[pixelsCount + i] = Color.green(pixels[i]) / 255f;
            imageData[pixelsCount * 2 + i] = Color.blue(pixels[i]) / 255f;
            maskData[i] = Color.alpha(smallMask[i]) > 0 ? 1f : 0f;
        }
        OrtEnvironment environment = OrtEnvironment.getEnvironment();
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(2);
            options.setMemoryPatternOptimization(false);
            try (OrtSession session = environment.createSession(model.getPath(), options);
                 OnnxTensor imageTensor = OnnxTensor.createTensor(environment, FloatBuffer.wrap(imageData), new long[]{1, 3, SIZE, SIZE});
                 OnnxTensor maskTensor = OnnxTensor.createTensor(environment, FloatBuffer.wrap(maskData), new long[]{1, 1, SIZE, SIZE})) {
                checkCancelled(progress);
                HashMap<String, OnnxTensor> inputs = new HashMap<>();
                inputs.put("image", imageTensor); inputs.put("mask", maskTensor);
                try (OrtSession.Result output = session.run(inputs)) {
                    checkCancelled(progress);
                    FloatBuffer values = ((OnnxTensor) output.get(0)).getFloatBuffer();
                    for (int i = 0; i < pixelsCount; i++) {
                        pixels[i] = Color.rgb(channel(values.get(i)), channel(values.get(pixelsCount + i)), channel(values.get(pixelsCount * 2 + i)));
                    }
                }
            }
        }
        Bitmap prediction = Bitmap.createBitmap(pixels, SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Bitmap restored = Bitmap.createScaledBitmap(prediction, cropWidth, cropHeight, true);
        Bitmap result = source.copy(Bitmap.Config.ARGB_8888, true);
        int[] row = new int[cropWidth];
        for (int y = 0; y < cropHeight; y++) {
            checkCancelled(progress);
            restored.getPixels(row, 0, cropWidth, 0, y, cropWidth, 1);
            for (int x = 0; x < cropWidth; x++) {
                int alpha = Color.alpha(maskPixels[(top + y) * width + left + x]);
                int original = source.getPixel(left + x, top + y);
                int generated = row[x];
                row[x] = Color.argb(Color.alpha(original), blend(Color.red(original), Color.red(generated), alpha),
                    blend(Color.green(original), Color.green(generated), alpha), blend(Color.blue(original), Color.blue(generated), alpha));
            }
            result.setPixels(row, 0, cropWidth, left, top + y, cropWidth, 1);
        }
        if (restored != prediction) restored.recycle();
        prediction.recycle();
        return result;
    }

    private static int channel(float value) { return Math.max(0, Math.min(255, Math.round(value))); }
    private static int blend(int original, int generated, int alpha) { return (original * (255 - alpha) + generated * alpha + 127) / 255; }
}
