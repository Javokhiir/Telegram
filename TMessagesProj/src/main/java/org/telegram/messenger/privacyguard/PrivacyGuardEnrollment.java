package org.telegram.messenger.privacyguard;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Size;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;

import java.util.ArrayList;

/**
 * Registers the owner's face: guides through five head orientations with the front camera and stores only
 * identity embeddings (encrypted, on device). No photo is taken or kept.
 */
@TargetApi(Build.VERSION_CODES.N)
public final class PrivacyGuardEnrollment implements PrivacyGuardCamera.Listener {

    public static final int STEP_STRAIGHT = 0;
    public static final int STEP_SIDE = 1;
    public static final int STEP_OTHER_SIDE = 2;
    public static final int STEP_UP = 3;
    public static final int STEP_DOWN = 4;
    public static final int STEP_COUNT = 5;

    public static final int HINT_NONE = 0;
    public static final int HINT_NO_FACE = 1;
    public static final int HINT_MULTIPLE_FACES = 2;
    public static final int HINT_MOVE_CLOSER = 3;
    public static final int HINT_TOO_DARK = 4;
    public static final int HINT_OPEN_EYES = 5;
    public static final int HINT_TURN_MORE = 6;
    public static final int HINT_TURN_LESS = 7;

    public static final int ERROR_NONE = 0;
    public static final int ERROR_CAMERA = 1;
    public static final int ERROR_MODELS = 2;
    public static final int ERROR_INCONSISTENT = 3;
    public static final int ERROR_SAVE = 4;

    private static final int SAMPLES_PER_STEP = 3;
    private static final long SAMPLE_INTERVAL_MS = 220;
    private static final long FRAME_INTERVAL_MS = 110;
    private static final float MIN_FACE_FRACTION = 0.2f;
    private static final float MIN_LUMA = 20f;
    private static final float MIN_CONSISTENCY = 0.5f;

    public interface Callback {
        /** UI thread. Preview buffer size as delivered by the camera, before rotation. */
        void onCameraStarted(int width, int height);

        /** UI thread. */
        void onProgress(int step, float stepProgress, int hint);

        /** UI thread. */
        void onFinished(boolean success, int error);
    }

    private final Context context;
    private final Callback callback;
    private final HandlerThread cameraThread = new HandlerThread("PGEnrollCamera");
    private final HandlerThread analysisThread = new HandlerThread("PGEnrollAnalysis");
    private Handler cameraHandler, analysisHandler;
    private PrivacyGuardCamera camera;
    private volatile boolean running;
    private volatile boolean busy;
    private volatile boolean ready;
    private volatile long lastFrame;
    private volatile int sensorOrientation = 270;

    // analysis thread
    private FaceMeshPipeline pipeline;
    private FaceEmbedder embedder;
    private final YuvFrame.Converter converter = new YuvFrame.Converter();
    private final FacePose pose = new FacePose();
    private int step;
    private final ArrayList<ArrayList<float[]>> samples = new ArrayList<>();
    private long lastSample;
    private float baselinePitch;
    private float sideSign;
    private boolean finished;
    private int lastStepPosted = -1;
    private float lastProgressPosted = -1;
    private int lastHintPosted = -1;

    public PrivacyGuardEnrollment(Context context, Callback callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
        for (int i = 0; i < STEP_COUNT; i++) {
            samples.add(new ArrayList<>());
        }
    }

    public void start(SurfaceTexture preview) {
        running = true;
        PrivacyGuardController.getInstance().setEnrollmentActive(true);
        cameraThread.start();
        analysisThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        analysisHandler = new Handler(analysisThread.getLooper());
        analysisHandler.post(() -> {
            try {
                pipeline = new FaceMeshPipeline();
                embedder = new FaceEmbedder();
                ready = true;
            } catch (Throwable e) {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.e("PrivacyGuard: enrollment models unavailable (" + e.getClass().getSimpleName() + ")");
                }
                finish(false, ERROR_MODELS);
            }
        });
        cameraHandler.post(() -> {
            camera = new PrivacyGuardCamera(context, cameraHandler, 30, this);
            camera.attach();
            if (!camera.open(preview)) {
                finish(false, ERROR_CAMERA);
            }
        });
    }

    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        ready = false;
        cameraHandler.post(() -> {
            if (camera != null) {
                camera.detach();
            }
            cameraHandler.postDelayed(cameraThread::quitSafely, 700);
        });
        analysisHandler.post(() -> {
            if (pipeline != null) {
                pipeline.close();
                pipeline = null;
            }
            if (embedder != null) {
                embedder.close();
                embedder = null;
            }
            converter.release();
            for (ArrayList<float[]> list : samples) {
                for (float[] s : list) {
                    java.util.Arrays.fill(s, 0);
                }
                list.clear();
            }
            analysisThread.quitSafely();
        });
        PrivacyGuardController.getInstance().setEnrollmentActive(false);
    }

    /** Start over after a failed consistency check. */
    public void restart() {
        analysisHandler.post(() -> {
            for (ArrayList<float[]> list : samples) {
                list.clear();
            }
            step = STEP_STRAIGHT;
            baselinePitch = 0;
            sideSign = 0;
            finished = false;
            lastStepPosted = -1;
            progress(HINT_NONE, 0);
        });
    }

    @Override
    public boolean wantsFrame() {
        return running && ready && !busy && SystemClock.elapsedRealtime() - lastFrame >= FRAME_INTERVAL_MS;
    }

    @Override
    public void onFrame(YuvFrame frame) {
        busy = true;
        lastFrame = SystemClock.elapsedRealtime();
        final PrivacyGuardCamera source = camera;
        analysisHandler.post(() -> {
            try {
                if (running && ready && !finished) {
                    analyze(frame);
                }
            } catch (Throwable e) {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.e("PrivacyGuard: enrollment analysis failed (" + e.getClass().getSimpleName() + ")");
                }
            } finally {
                source.recycle(frame);
                busy = false;
            }
        });
    }

    @Override
    public void onStarted(int sensorOrientation, Size size) {
        this.sensorOrientation = sensorOrientation;
        AndroidUtilities.runOnUIThread(() -> {
            if (running) {
                callback.onCameraStarted(size.getWidth(), size.getHeight());
            }
        });
    }

    @Override
    public void onStopped(boolean interrupted) {
        if (interrupted && running) {
            finish(false, ERROR_CAMERA);
        }
    }

    private void analyze(YuvFrame frame) {
        final long now = SystemClock.elapsedRealtime();
        final Bitmap bitmap = converter.convert(frame, (sensorOrientation + PrivacyGuardCamera.displayRotation(context)) % 360);
        if (converter.meanLuma < MIN_LUMA) {
            progress(HINT_TOO_DARK, currentProgress());
            return;
        }
        final ArrayList<FaceMeshPipeline.Face> faces = pipeline.process(bitmap, 3, null);
        if (faces.isEmpty()) {
            progress(HINT_NO_FACE, currentProgress());
            return;
        }
        final FaceMeshPipeline.Face face = faces.get(0);
        if (faces.size() > 1 && faces.get(1).box.width() > face.box.width() * 0.4f) {
            progress(HINT_MULTIPLE_FACES, currentProgress());
            return;
        }
        FaceGeometry.compute(face, pose);
        if (pose.faceWidth / bitmap.getWidth() < MIN_FACE_FRACTION) {
            progress(HINT_MOVE_CLOSER, currentProgress());
            return;
        }
        if (pose.blink > 0.5f) {
            progress(HINT_OPEN_EYES, currentProgress());
            return;
        }
        final int hint = poseHint();
        if (hint != HINT_NONE) {
            progress(hint, currentProgress());
            return;
        }
        if (now - lastSample < SAMPLE_INTERVAL_MS) {
            return;
        }
        lastSample = now;
        final ArrayList<float[]> list = samples.get(step);
        list.add(embedder.embed(bitmap, face));
        if (step == STEP_STRAIGHT) {
            baselinePitch += (pose.pitch - baselinePitch) / list.size();
        } else if (step == STEP_SIDE) {
            sideSign = Math.signum(pose.yaw);
        }
        if (list.size() >= SAMPLES_PER_STEP) {
            step++;
            if (step >= STEP_COUNT) {
                complete();
                return;
            }
        }
        progress(HINT_NONE, currentProgress());
    }

    /** HINT_NONE when the head is in the orientation the current step asks for. */
    private int poseHint() {
        final float yaw = pose.yaw;
        final float pitch = pose.pitch - baselinePitch;
        switch (step) {
            case STEP_STRAIGHT:
                return Math.abs(pose.yaw) < 10 && Math.abs(pose.pitch) < 18 ? HINT_NONE : HINT_TURN_LESS;
            case STEP_SIDE:
                if (Math.abs(yaw) < 12) return HINT_TURN_MORE;
                return Math.abs(yaw) > 38 || Math.abs(pitch) > 20 ? HINT_TURN_LESS : HINT_NONE;
            case STEP_OTHER_SIDE:
                if (Math.signum(yaw) == sideSign || Math.abs(yaw) < 12) return HINT_TURN_MORE;
                return Math.abs(yaw) > 38 || Math.abs(pitch) > 20 ? HINT_TURN_LESS : HINT_NONE;
            case STEP_UP:
                if (pitch < 8) return HINT_TURN_MORE;
                return pitch > 35 || Math.abs(yaw) > 20 ? HINT_TURN_LESS : HINT_NONE;
            case STEP_DOWN:
                if (pitch > -8) return HINT_TURN_MORE;
                return pitch < -35 || Math.abs(yaw) > 20 ? HINT_TURN_LESS : HINT_NONE;
        }
        return HINT_NONE;
    }

    private float currentProgress() {
        return step < STEP_COUNT ? samples.get(step).size() / (float) SAMPLES_PER_STEP : 1f;
    }

    private void complete() {
        // every orientation must belong to the same person as the straight one
        final float[] reference = mean(samples.get(STEP_STRAIGHT));
        ArrayList<float[]> templates = new ArrayList<>();
        for (int i = 0; i < STEP_COUNT; i++) {
            float[] m = mean(samples.get(i));
            if (FaceEmbedder.cosine(m, reference) < MIN_CONSISTENCY) {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("PrivacyGuard: ENROLL_INCONSISTENT");
                }
                finish(false, ERROR_INCONSISTENT);
                return;
            }
            templates.add(m);
            templates.addAll(samples.get(i));
        }
        final boolean saved = OwnerFaceStore.save(templates.toArray(new float[0][]));
        finish(saved, saved ? ERROR_NONE : ERROR_SAVE);
    }

    private static float[] mean(ArrayList<float[]> list) {
        float[] m = new float[list.get(0).length];
        for (float[] v : list) {
            for (int i = 0; i < m.length; i++) {
                m[i] += v[i];
            }
        }
        FaceEmbedder.normalize(m);
        return m;
    }

    private void progress(int hint, float progress) {
        if (step == lastStepPosted && hint == lastHintPosted && progress == lastProgressPosted) {
            return;
        }
        if (BuildVars.LOGS_ENABLED && (step != lastStepPosted || hint != lastHintPosted)) {
            FileLog.d("PrivacyGuard: ENROLL step=" + step + " hint=" + hint);
        }
        lastStepPosted = step;
        lastHintPosted = hint;
        lastProgressPosted = progress;
        final int s = step;
        AndroidUtilities.runOnUIThread(() -> {
            if (running) {
                callback.onProgress(s, progress, hint);
            }
        });
    }

    private void finish(boolean success, int error) {
        finished = true;
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("PrivacyGuard: ENROLL " + (success ? "DONE" : "FAILED error=" + error));
        }
        AndroidUtilities.runOnUIThread(() -> {
            if (running) {
                callback.onFinished(success, error);
            }
        });
    }
}
