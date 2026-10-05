package org.telegram.ui.Components;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Build;
import android.text.TextUtils;
import android.view.Surface;

import com.snap.camerakit.ImageProcessor;
import com.snap.camerakit.ImageProcessors;
import com.snap.camerakit.Session;
import com.snap.camerakit.Sessions;
import com.snap.camerakit.lenses.LensesComponent;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;

import java.io.Closeable;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * U message: Snapchat Lenses for round video messages, rendered by Snap Camera Kit.
 * <p>
 * One Camera Kit session for the app loads the lenses of the lens group (SNAP_LENS_GROUP_ID in local.properties).
 * A {@link Renderer} on the camera GL thread draws each camera frame, as the round preview shows it, into Camera Kit's
 * input surface and copies Camera Kit's latest output into a texture in gl_FragCoord space, which the preview and
 * encoder shaders use in place of the camera color (see {@link RoundVideoEffects#GLSL}). The camera itself, the dual
 * camera switch and the recording stay as they are.
 */
public class SnapCameraKit {

    private static final String TAG = "SnapCameraKit";

    private static Session session; // UI thread
    private static volatile List<LensesComponent.Lens> lenses = Collections.emptyList();
    private static final ConcurrentHashMap<String, Bitmap> icons = new ConcurrentHashMap<>();
    private static final ArrayList<Runnable> listeners = new ArrayList<>(); // UI thread
    private static boolean loading, failed;

    /** Camera Kit can run here: a token in local.properties, a lens group and an OpenGL ES 3 capable device. */
    public static boolean isSupported() {
        if (!BuildConfig.SNAP_CAMERAKIT_ENABLED || TextUtils.isEmpty(BuildConfig.SNAP_LENS_GROUP_ID) || failed) {
            return false;
        }
        try {
            return Sessions.supported(ApplicationLoader.applicationContext);
        } catch (Throwable e) {
            FileLog.e(e);
            failed = true;
            return false;
        }
    }

    /** Starts the session and loads the lens list; call on the UI thread. */
    public static void prepare() {
        if (session != null || loading || !isSupported()) {
            return;
        }
        loading = true;
        try {
            session = Sessions.newBuilder(ApplicationLoader.applicationContext)
                    .handleErrorsWith(e -> FileLog.e(TAG + " error", e))
                    .build();
            session.getLenses().getRepository().observe(
                    new LensesComponent.Repository.QueryCriteria.Available(BuildConfig.SNAP_LENS_GROUP_ID),
                    result -> {
                        final List<LensesComponent.Lens> list = result instanceof LensesComponent.Repository.Result.Some
                                ? new ArrayList<>(((LensesComponent.Repository.Result.Some) result).getLenses())
                                : Collections.emptyList();
                        for (LensesComponent.Lens lens : list) {
                            android.util.Log.i(TAG, "lens id=" + lens.getId() + " name=" + lens.getName());
                        }
                        AndroidUtilities.runOnUIThread(() -> {
                            lenses = list;
                            loading = false;
                            loadIcons(list);
                            notifyListeners();
                        });
                    });
        } catch (Throwable e) {
            FileLog.e(e);
            failed = true;
            loading = false;
            session = null;
        }
    }

    /** Called on the UI thread whenever the lens list or a lens icon changes. */
    public static void addListener(Runnable listener) {
        listeners.add(listener);
    }

    public static void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    private static void notifyListeners() {
        for (Runnable listener : new ArrayList<>(listeners)) {
            listener.run();
        }
    }

    public static int getLensCount() {
        return lenses.size();
    }

    private static LensesComponent.Lens getLens(int index) {
        final List<LensesComponent.Lens> list = lenses;
        return index >= 0 && index < list.size() ? list.get(index) : null;
    }

    public static String getLensId(int index) {
        final LensesComponent.Lens lens = getLens(index);
        return lens != null ? lens.getId() : null;
    }

    public static String getLensName(int index) {
        final LensesComponent.Lens lens = getLens(index);
        return lens != null ? lens.getName() : null;
    }

    /** Lens icon for the carousel, null until downloaded. */
    public static Bitmap getLensIcon(int index) {
        final LensesComponent.Lens lens = getLens(index);
        return lens != null ? icons.get(lens.getId()) : null;
    }

    private static LensesComponent.Lens findLens(String id) {
        for (LensesComponent.Lens lens : lenses) {
            if (lens.getId().equals(id)) {
                return lens;
            }
        }
        return null;
    }

    private static void loadIcons(List<LensesComponent.Lens> list) {
        for (LensesComponent.Lens lens : list) {
            final String id = lens.getId(), uri = lens.getIconUri();
            if (icons.containsKey(id) || TextUtils.isEmpty(uri)) {
                continue;
            }
            Utilities.globalQueue.postRunnable(() -> {
                HttpURLConnection connection = null;
                try {
                    connection = (HttpURLConnection) new URL(uri).openConnection();
                    connection.setConnectTimeout(15000);
                    connection.setReadTimeout(15000);
                    try (InputStream in = connection.getInputStream()) {
                        final Bitmap bitmap = BitmapFactory.decodeStream(in);
                        if (bitmap != null) {
                            icons.put(id, bitmap);
                            AndroidUtilities.runOnUIThread(SnapCameraKit::notifyListeners);
                        }
                    }
                } catch (Throwable e) {
                    FileLog.e(e);
                } finally {
                    if (connection != null) {
                        connection.disconnect();
                    }
                }
            });
        }
    }

    /** Applies the lens, null clears it; any thread. */
    private static void applyLens(String id) {
        AndroidUtilities.runOnUIThread(() -> {
            if (session == null) {
                return;
            }
            try {
                final LensesComponent.Lens lens = id != null ? findLens(id) : null;
                if (lens != null) {
                    session.getLenses().getProcessor().apply(lens, applied -> FileLog.d(TAG + " applied " + lens.getName() + " " + applied));
                } else {
                    session.getLenses().getProcessor().clear(cleared -> {});
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    private static Session getSession() {
        return session;
    }

    /**
     * Feeds one GL thread's camera frames through Camera Kit. All methods run on that thread, whose context must be
     * current; the frame is passed exactly as the round preview draws it, so Camera Kit gets it upright.
     */
    public static final class Renderer {

        private static final int SIZE = 720;
        private static final float FIELD_OF_VIEW = 55f;

        private static final String VERTEX_SHADER =
                "uniform mat4 uMVPMatrix;\n" +
                "uniform mat4 uSTMatrix;\n" +
                "attribute vec4 aPosition;\n" +
                "attribute vec4 aTextureCoord;\n" +
                "varying vec2 vTextureCoord;\n" +
                "void main() {\n" +
                "   gl_Position = uMVPMatrix * aPosition;\n" +
                "   vTextureCoord = (uSTMatrix * aTextureCoord).xy;\n" +
                "}\n";

        private static final String FRAGMENT_SHADER =
                "#extension GL_OES_EGL_image_external : require\n" +
                "precision mediump float;\n" +
                "varying vec2 vTextureCoord;\n" +
                "uniform samplerExternalOES sTexture;\n" +
                "void main() {\n" +
                "   gl_FragColor = vec4(texture2D(sTexture, vTextureCoord).rgb, 1.0);\n" +
                "}\n";

        private final FloatBuffer quad = floatBuffer(new float[]{-1, -1, 1, -1, -1, 1, 1, 1});
        private final FloatBuffer fullTexture = floatBuffer(new float[]{0, 0, 1, 0, 0, 1, 1, 1});
        private final float[] identity = new float[16];
        private final float[] outputMatrix = new float[16];
        private final int[] outputFbo = new int[1], outputTexture = new int[1], lensTexture = new int[1];
        private int program, positionHandle, textureHandle, stHandle, mvpHandle;

        private SurfaceTexture inputTexture, lensOutput;
        private Surface inputSurface, lensSurface;
        private EGLSurface inputEglSurface = EGL14.EGL_NO_SURFACE;
        private Closeable inputConnection, outputConnection;
        private Session connectedSession;
        private boolean connectedFront;
        private volatile boolean lensFrameAvailable;
        private String appliedLens;
        private boolean hasOutput;
        private boolean swapIntervalSet;
        private boolean failed;

        /**
         * Feeds the current camera frame to Camera Kit and returns its latest lens frame.
         *
         * @param front the frame shows the front camera
         * @return texture with the lens frame in gl_FragCoord space, 0 while not available
         */
        public int render(String lensId, int cameraTexture, float[] stMatrix, float[] mvpMatrix, FloatBuffer textureCoords, boolean front) {
            final Session session = getSession();
            if (failed || lensId == null || cameraTexture == 0 || textureCoords == null || session == null) {
                return 0;
            }
            final GlState saved = GlState.save();
            try {
                if (program == 0 && !init()) {
                    return 0;
                }
                if (connectedSession != session || connectedFront != front) {
                    connect(session, front);
                }
                if (!lensId.equals(appliedLens)) {
                    appliedLens = lensId;
                    hasOutput = false;
                    applyLens(lensId);
                }

                // the frame as the round preview draws it, into Camera Kit's input
                final EGLDisplay display = EGL14.eglGetCurrentDisplay();
                final EGLContext context = EGL14.eglGetCurrentContext();
                final EGLSurface draw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW);
                final EGLSurface read = EGL14.eglGetCurrentSurface(EGL14.EGL_READ);
                if (EGL14.eglMakeCurrent(display, inputEglSurface, inputEglSurface, context)) {
                    if (!swapIntervalSet) {
                        // a slow lens drops input frames instead of blocking the camera thread
                        EGL14.eglSwapInterval(display, 0);
                        swapIntervalSet = true;
                    }
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
                    GLES20.glViewport(0, 0, SIZE, SIZE);
                    drawExternal(cameraTexture, stMatrix, mvpMatrix != null ? mvpMatrix : identity, textureCoords);
                    EGLExt.eglPresentationTimeANDROID(display, inputEglSurface, System.nanoTime());
                    EGL14.eglSwapBuffers(display, inputEglSurface);
                    EGL14.eglMakeCurrent(display, draw, read, context);
                }

                // Camera Kit's latest output, a frame or two behind
                if (lensFrameAvailable) {
                    lensFrameAvailable = false;
                    lensOutput.updateTexImage();
                    lensOutput.getTransformMatrix(outputMatrix);
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outputFbo[0]);
                    GLES20.glViewport(0, 0, SIZE, SIZE);
                    drawExternal(lensTexture[0], outputMatrix, identity, fullTexture);
                    hasOutput = true;
                }
                return hasOutput ? outputTexture[0] : 0;
            } catch (Throwable e) {
                FileLog.e(e);
                failed = true;
                return 0;
            } finally {
                saved.restore();
            }
        }

        private void drawExternal(int texture, float[] stMatrix, float[] mvpMatrix, FloatBuffer textureCoords) {
            GLES20.glDisable(GLES20.GL_BLEND);
            GLES20.glUseProgram(program);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture);
            GLES20.glUniformMatrix4fv(stHandle, 1, false, stMatrix, 0);
            GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0);
            quad.position(0);
            GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 8, quad);
            GLES20.glEnableVertexAttribArray(positionHandle);
            textureCoords.position(0);
            GLES20.glVertexAttribPointer(textureHandle, 2, GLES20.GL_FLOAT, false, 8, textureCoords);
            GLES20.glEnableVertexAttribArray(textureHandle);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisableVertexAttribArray(positionHandle);
            GLES20.glDisableVertexAttribArray(textureHandle);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
        }

        private boolean init() {
            program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
            if (program == 0) {
                failed = true;
                return false;
            }
            positionHandle = GLES20.glGetAttribLocation(program, "aPosition");
            textureHandle = GLES20.glGetAttribLocation(program, "aTextureCoord");
            stHandle = GLES20.glGetUniformLocation(program, "uSTMatrix");
            mvpHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix");
            android.opengl.Matrix.setIdentityM(identity, 0);

            GLES20.glGenTextures(1, outputTexture, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, outputTexture[0]);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, SIZE, SIZE, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
            GLES20.glGenFramebuffers(1, outputFbo, 0);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outputFbo[0]);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, outputTexture[0], 0);

            // Camera Kit renders the lens into this texture of ours
            GLES20.glGenTextures(1, lensTexture, 0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, lensTexture[0]);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            lensOutput = new SurfaceTexture(lensTexture[0]);
            lensOutput.setDefaultBufferSize(SIZE, SIZE);
            lensOutput.setOnFrameAvailableListener(t -> lensFrameAvailable = true);
            lensSurface = new Surface(lensOutput);

            // Camera Kit attaches its input texture to its own context: it must start detached from ours
            if (Build.VERSION.SDK_INT >= 26) {
                inputTexture = new SurfaceTexture(false);
            } else {
                inputTexture = new SurfaceTexture(0);
                inputTexture.detachFromGLContext();
            }
            inputTexture.setDefaultBufferSize(SIZE, SIZE);
            inputSurface = new Surface(inputTexture);

            final EGLDisplay display = EGL14.eglGetCurrentDisplay();
            final EGLConfig config = currentConfig(display);
            if (config == null) {
                failed = true;
                return false;
            }
            inputEglSurface = EGL14.eglCreateWindowSurface(display, config, inputSurface, new int[]{EGL14.EGL_NONE}, 0);
            if (inputEglSurface == null || inputEglSurface == EGL14.EGL_NO_SURFACE) {
                failed = true;
                return false;
            }
            return true;
        }

        private void connect(Session session, boolean front) {
            disconnect();
            final ImageProcessor processor = session.getProcessor();
            inputConnection = processor.connectInput(ImageProcessors.inputFrom(inputTexture, SIZE, SIZE, 0, front, FIELD_OF_VIEW, FIELD_OF_VIEW));
            outputConnection = processor.connectOutput(ImageProcessors.outputFrom(lensSurface, ImageProcessor.Output.Purpose.RECORDING, 0));
            connectedSession = session;
            connectedFront = front;
            hasOutput = false;
        }

        private void disconnect() {
            close(inputConnection);
            close(outputConnection);
            inputConnection = null;
            outputConnection = null;
            connectedSession = null;
        }

        private static void close(Closeable closeable) {
            if (closeable != null) {
                try {
                    closeable.close();
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
        }

        private static EGLConfig currentConfig(EGLDisplay display) {
            final int[] id = new int[1];
            if (!EGL14.eglQueryContext(display, EGL14.eglGetCurrentContext(), EGL14.EGL_CONFIG_ID, id, 0)) {
                return null;
            }
            final EGLConfig[] configs = new EGLConfig[1];
            final int[] count = new int[1];
            if (!EGL14.eglChooseConfig(display, new int[]{EGL14.EGL_CONFIG_ID, id[0], EGL14.EGL_NONE}, 0, configs, 0, 1, count, 0) || count[0] == 0) {
                return null;
            }
            return configs[0];
        }

        /** Call on the GL thread that rendered. */
        public void release() {
            disconnect();
            if (appliedLens != null) {
                applyLens(null);
                appliedLens = null;
            }
            if (inputEglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(EGL14.eglGetCurrentDisplay(), inputEglSurface);
                inputEglSurface = EGL14.EGL_NO_SURFACE;
            }
            if (inputSurface != null) {
                inputSurface.release();
                inputSurface = null;
            }
            if (inputTexture != null) {
                inputTexture.release();
                inputTexture = null;
            }
            if (lensSurface != null) {
                lensSurface.release();
                lensSurface = null;
            }
            if (lensOutput != null) {
                lensOutput.release();
                lensOutput = null;
            }
            if (program != 0) {
                GLES20.glDeleteProgram(program);
                program = 0;
            }
            if (outputFbo[0] != 0) {
                GLES20.glDeleteFramebuffers(1, outputFbo, 0);
                outputFbo[0] = 0;
            }
            if (outputTexture[0] != 0) {
                GLES20.glDeleteTextures(1, outputTexture, 0);
                outputTexture[0] = 0;
            }
            if (lensTexture[0] != 0) {
                GLES20.glDeleteTextures(1, lensTexture, 0);
                lensTexture[0] = 0;
            }
            hasOutput = false;
        }
    }

    /** State the offscreen passes change, restored so the round frame drawing around them is unaffected. */
    private static final class GlState {
        private final int[] framebuffer = new int[1];
        private final int[] viewport = new int[4];
        private final int[] program = new int[1];
        private boolean blend;

        static GlState save() {
            GlState s = new GlState();
            GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, s.framebuffer, 0);
            GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, s.viewport, 0);
            GLES20.glGetIntegerv(GLES20.GL_CURRENT_PROGRAM, s.program, 0);
            s.blend = GLES20.glIsEnabled(GLES20.GL_BLEND);
            return s;
        }

        void restore() {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer[0]);
            GLES20.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            GLES20.glUseProgram(program[0]);
            if (blend) {
                GLES20.glEnable(GLES20.GL_BLEND);
            } else {
                GLES20.glDisable(GLES20.GL_BLEND);
            }
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        }
    }

    private static int createProgram(String vertexSource, String fragmentSource) {
        final int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        final int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        if (vertexShader == 0 || fragmentShader == 0) {
            return 0;
        }
        final int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertexShader);
        GLES20.glAttachShader(program, fragmentShader);
        GLES20.glLinkProgram(program);
        GLES20.glDeleteShader(vertexShader);
        GLES20.glDeleteShader(fragmentShader);
        final int[] status = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0);
        if (status[0] == 0) {
            FileLog.e(TAG + " link failed " + GLES20.glGetProgramInfoLog(program));
            GLES20.glDeleteProgram(program);
            return 0;
        }
        return program;
    }

    private static int loadShader(int type, String source) {
        final int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        final int[] status = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) {
            FileLog.e(TAG + " shader failed " + GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    private static FloatBuffer floatBuffer(float[] data) {
        FloatBuffer buffer = ByteBuffer.allocateDirect(data.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        buffer.put(data).position(0);
        return buffer;
    }
}
