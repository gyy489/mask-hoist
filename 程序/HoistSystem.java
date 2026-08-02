import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDouble;
import org.opencv.core.MatOfFloat;
import org.opencv.core.MatOfInt;
import org.opencv.core.MatOfRect;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.CLAHE;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.CascadeClassifier;
import org.opencv.objdetect.HOGDescriptor;
import org.opencv.videoio.VideoCapture;
import org.opencv.videoio.Videoio;
import org.opencv.videoio.VideoWriter;

import com.fazecast.jSerialComm.SerialPort;

public class HoistSystem {

    static {
        System.setProperty(
            "OPENCV_FFMPEG_CAPTURE_OPTIONS",
            "rtsp_transport;tcp|stimeout;3000000|buffer_size;10240|max_delay;500000|fflags;nobuffer|flags;low_delay"
        );
    }

    private static final String HELMET_CAMERA_URL = config("HELMET_CAMERA_URL", "http://192.168.1.98:8080/stream");
    private static final boolean DEFAULT_FLIP_HELMET_VIEW = configBoolean("FLIP_HELMET_CAMERA", true);
    private static final boolean AUTO_OPEN_CAMERA_PAGE = configBoolean("AUTO_OPEN_CAMERA_PAGE", false);
    private static final boolean OPEN_CAMERA_PAGE_ON_RECONNECT = configBoolean("OPEN_CAMERA_PAGE_ON_RECONNECT", false);
    private static final String CAMERA_PAGE_URL = config("CAMERA_PAGE_URL", defaultCameraPageUrl(HELMET_CAMERA_URL));

    private static final String CONTROLLER_PORT_MATCH = config(
        "CONTROLLER_PORT_MATCH",
        config("ARDUINO_PORT_MATCH", "usb,arduino,esp32,cp210,ch340,wchusbserial,usbserial")
    );
    private static final int CONTROLLER_BAUD_RATE = configInt(
        "CONTROLLER_BAUD_RATE",
        configInt("ARDUINO_BAUD_RATE", 115200)
    );
    private static final int CONTROLLER_BOOT_WAIT_MS = configInt(
        "CONTROLLER_BOOT_WAIT_MS",
        configInt("ARDUINO_BOOT_WAIT_MS", 2000)
    );

    private static final String CONTRACT_COMMAND = config("HOIST_CONTRACT_COMMAND", "DOWN");
    private static final String DESCEND_COMMAND = config("HOIST_DESCEND_COMMAND", "UP");
    private static final String STOP_COMMAND = config("HOIST_STOP_COMMAND", "STOP");

    private static final int PROCESS_INTERVAL_MS = configInt("PROCESS_INTERVAL_MS", 33);
    private static final int COMMAND_INTERVAL_MS = configInt("COMMAND_INTERVAL_MS", 100);
    private static final boolean ESP32_ENABLED_ON_START = configBoolean("ESP32_ENABLED_ON_START", false);
    private static final int MANUAL_AUTO_RESUME_DELAY_MS = configInt("MANUAL_AUTO_RESUME_DELAY_MS", 3000);
    private static final int STREAM_RECONNECT_MS = configInt("STREAM_RECONNECT_MS", 1000);
    private static final int STREAM_STALE_MS = configInt("STREAM_STALE_MS", 3000);
    private static final int FRAME_CONTENT_CHECK_MS = configInt("FRAME_CONTENT_CHECK_MS", 250);
    private static final int SERIAL_SCAN_INTERVAL_MS = configInt("SERIAL_SCAN_INTERVAL_MS", 1000);
    private static final int OPENCV_THREADS = configInt("OPENCV_THREADS", 2);

    private static final int DETECT_EVERY_N_FRAMES = configInt("DETECT_EVERY_N_FRAMES", 1);
    private static final int CONTACT_START_FRAMES = configInt("CONTACT_START_FRAMES", 2);
    private static final int CONTACT_START_HOLD_MS = configInt("CONTACT_START_HOLD_MS", 180);
    private static final int CONTACT_LOST_FRAMES = configInt("CONTACT_LOST_FRAMES", 3);
    private static final int DETECT_MAX_WIDTH = configInt("DETECT_MAX_WIDTH", 640);
    private static final int UI_MAX_FPS = configInt("UI_MAX_FPS", 20);
    private static final double FACE_SCALE_FACTOR = configDouble("FACE_SCALE_FACTOR", 1.1);
    private static final int FACE_MIN_NEIGHBORS = configInt("FACE_MIN_NEIGHBORS", 6);
    private static final double PERSON_HOG_HIT_THRESHOLD = configDouble("PERSON_HOG_HIT_THRESHOLD", 0.35);
    private static final double PERSON_HOG_SCALE = configDouble("PERSON_HOG_SCALE", 1.05);
    private static final double PERSON_HOG_FINAL_THRESHOLD = configDouble("PERSON_HOG_FINAL_THRESHOLD", 2.0);
    private static final double PERSON_MIN_HOG_WEIGHT = configDouble("PERSON_MIN_HOG_WEIGHT", 0.35);
    private static final double PERSON_FACE_FALLBACK_MIN_WEIGHT = configDouble("PERSON_FACE_FALLBACK_MIN_WEIGHT", 0.8);
    private static final double PERSON_MIN_HEIGHT_RATIO = configDouble("PERSON_MIN_HEIGHT_RATIO", 0.12);
    private static final double PERSON_MIN_AREA_RATIO = configDouble("PERSON_MIN_AREA_RATIO", 0.008);
    private static final double PERSON_MIN_ASPECT_RATIO = configDouble("PERSON_MIN_ASPECT_RATIO", 0.25);
    private static final double PERSON_MAX_ASPECT_RATIO = configDouble("PERSON_MAX_ASPECT_RATIO", 0.85);
    private static final boolean PERSON_SCAN_ALL_ROTATIONS = configBoolean("PERSON_SCAN_ALL_ROTATIONS", true);
    private static final int PERSON_ROTATION_STEP_DEGREES = configInt("PERSON_ROTATION_STEP_DEGREES", 45);
    private static final boolean DEBUG_UI_ENABLED = configBoolean("DEBUG_UI_ENABLED", true);
    private static final Size MIN_FACE_SIZE = new Size(40, 40);
    private static final int OVERLAY_FONT_FACE = Imgproc.FONT_HERSHEY_DUPLEX;
    private static final int OVERLAY_MAIN_FONT_FACE = Imgproc.FONT_HERSHEY_TRIPLEX;
    private static final String UI_STATUS_FONT = config("UI_STATUS_FONT", "Helvetica Neue");

    private static final boolean CAPTURE_ENABLED = configBoolean("CAPTURE_ENABLED", false);
    private static final String CAPTURE_DIR = config("CAPTURE_DIR", "captures/helmet");
    private static final int CAPTURE_INTERVAL_MS = configInt("CAPTURE_INTERVAL_MS", 200);
    private static final int CAPTURE_JPEG_QUALITY = configInt("CAPTURE_JPEG_QUALITY", 92);
    private static final boolean CAPTURE_MP4_ENABLED = configBoolean("CAPTURE_MP4_ENABLED", true);
    private static final String CAPTURE_MP4_FILENAME = config("CAPTURE_MP4_FILENAME", "helmet.mp4");
    private static final double CAPTURE_MP4_FPS = configDouble("CAPTURE_MP4_FPS", 30.0);
    private static final int CAPTURE_MP4_INTERVAL_MS = configInt("CAPTURE_MP4_INTERVAL_MS", 0);
    private static final boolean PERF_LOG_ENABLED = configBoolean("PERF_LOG_ENABLED", false);
    private static final int PERF_LOG_INTERVAL_MS = configInt("PERF_LOG_INTERVAL_MS", 5000);
    private static final DateTimeFormatter SESSION_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");
    private static final DateTimeFormatter LOG_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final DateTimeFormatter UI_STATUS_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Object frameLock = new Object();
    private Mat latestFrame;
    private long latestFrameAtMs;
    private long latestFrameSequence;
    private long lastProcessedFrameSequence = -1;

    private final Object serialLock = new Object();
    private SerialPort controllerPort;
    private long controllerReadyAtMs;

    private CascadeClassifier faceFrontal;
    private HOGDescriptor personHog;
    private CLAHE clahe;

    private final Object captureLock = new Object();
    private Path captureSessionDir;
    private Path captureFramesDir;
    private BufferedWriter captureLogWriter;
    private VideoWriter captureVideoWriter;
    private Path captureVideoPath;
    private Size captureVideoSize;
    private long nextCaptureAtMs;
    private long nextCaptureVideoAtMs;
    private int captureFrameIndex;
    private int captureVideoFrameIndex;
    private boolean captureMp4Available;

    private JFrame frameHelmet;
    private HelmetViewPanel viewHelmetPanel;
    private javax.swing.Timer statusRefreshTimer;
    private final AtomicBoolean helmetViewUpdatePending = new AtomicBoolean(false);
    private final AtomicBoolean shutdownStarted = new AtomicBoolean(false);
    private long nextHelmetViewAtMs;
    private volatile boolean debugUiVisible = DEBUG_UI_ENABLED;
    private volatile DetectionDebugInfo lastDetectionDebugInfo = DetectionDebugInfo.empty();

    private Rect[] lastFaces = new Rect[0];
    private int frameCounter;
    private int contactCandidateFrames;
    private long contactCandidateStartedAtMs;
    private int missingFaceFrames = CONTACT_LOST_FRAMES;
    private long lastDetectionErrorLogAtMs;

    private volatile boolean flipHelmetView = DEFAULT_FLIP_HELMET_VIEW;
    private volatile boolean eyeContactActive;
    private volatile String contactState = "NONE";
    private volatile int faceCount;
    private volatile List<BufferedImage> lastFaceSnapshots = new ArrayList<>();
    private volatile String currentCommand = STOP_COMMAND;
    private volatile boolean manualModeActive;
    private volatile boolean manualUpPressed;
    private volatile boolean manualDownPressed;
    private volatile boolean manualInterlockActive;
    private volatile String manualCommand = STOP_COMMAND;
    private volatile long autoResumeAtMs;
    private volatile String controlMode = "AUTO";
    private volatile boolean streamConnected;
    private volatile String streamStatus = "Camera: scanning";
    private volatile boolean cameraPageOpened;
    private volatile boolean espControlEnabled = ESP32_ENABLED_ON_START;
    private volatile boolean serialConnected;
    private volatile String serialStatus = "ESP32: scanning";
    private volatile boolean captureAvailable;
    private volatile String captureStatus = CAPTURE_ENABLED ? "Capture: starting" : "Capture: disabled";

    private long perfWindowStartedMs = System.currentTimeMillis();
    private int perfLoopCount;
    private int perfFreshFrameCount;
    private int perfStaleFrameCount;
    private int perfNoFrameCount;
    private long perfLoopTotalNs;
    private long perfLoopMaxNs;
    private int perfDetectCount;
    private long perfDetectTotalNs;
    private long perfDetectMaxNs;
    private int perfJpgCount;
    private long perfJpgTotalNs;
    private long perfJpgMaxNs;
    private int perfMp4Count;
    private long perfMp4TotalNs;
    private long perfMp4MaxNs;
    private int perfLastFrameWidth;
    private int perfLastFrameHeight;

    public HoistSystem() {
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME);
        Core.setNumThreads(Math.max(1, OPENCV_THREADS));
        validateCommandConfig();

        faceFrontal = loadCascadeClassifier("haarcascade_frontalface_alt.xml");
        if (faceFrontal.empty()) {
            throw new IllegalStateException("Cannot load haarcascade_frontalface_alt.xml");
        }
        personHog = new HOGDescriptor();
        MatOfFloat peopleDetector = HOGDescriptor.getDefaultPeopleDetector();
        try {
            personHog.setSVMDetector(peopleDetector);
        } finally {
            peopleDetector.release();
        }
        clahe = Imgproc.createCLAHE(2.0, new Size(8, 8));

        initCaptureSession();
        initWindowUI();
        startStatusRefreshTimer();
        startHelmetCameraLoop();
        startSerialMonitorLoop();

        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        timer.scheduleAtFixedRate(this::mainLoop, 0, PROCESS_INTERVAL_MS, TimeUnit.MILLISECONDS);

        new Thread(this::heartbeatLoop, "HoistCommandHeartbeat").start();
        Runtime.getRuntime().addShutdownHook(new Thread(this::safeShutdown, "HoistSafeShutdown"));
    }

    private void mainLoop() {
        long loopStartedNs = System.nanoTime();
        Mat frame = null;
        boolean hadFrame = false;
        boolean hasBufferedFrame = false;
        boolean freshFrame = false;
        long frameAgeMs = Long.MAX_VALUE;
        synchronized (frameLock) {
            if (latestFrame != null && !latestFrame.empty()) {
                hasBufferedFrame = true;
                frameAgeMs = System.currentTimeMillis() - latestFrameAtMs;
                if (latestFrameSequence != lastProcessedFrameSequence) {
                    frame = latestFrame.clone();
                    lastProcessedFrameSequence = latestFrameSequence;
                }
            }
        }

        try {
            if (frame == null) {
                if (!hasBufferedFrame) {
                    forceStopWithoutContact();
                    updateWaitingText();
                } else if (frameAgeMs > STREAM_STALE_MS) {
                    streamConnected = false;
                    streamStatus = "Camera: stale";
                    forceStopWithoutContact();
                }
                return;
            }

            hadFrame = true;
            freshFrame = streamConnected && frameAgeMs <= STREAM_STALE_MS;
            if (!freshFrame) {
                streamConnected = false;
                streamStatus = "Camera: stale";
                forceStopWithoutContact();
            }

            processHelmetView(frame, freshFrame, frameAgeMs);
        } catch (Exception e) {
            e.printStackTrace();
            forceStopWithoutContact();
        } finally {
            if (frame != null) {
                frame.release();
            }
            recordPerfLoop(hadFrame, freshFrame, System.nanoTime() - loopStartedNs);
        }
    }

    private void processHelmetView(Mat frame, boolean freshFrame, long frameAgeMs) {
        if (flipHelmetView) {
            Core.flip(frame, frame, 0);
        }

        boolean detectionRan = freshFrame && frameCounter % Math.max(1, DETECT_EVERY_N_FRAMES) == 0;
        if (detectionRan) {
            try {
                detectPeople(frame);
                updatePersonPresenceState(lastFaces.length);
            } catch (RuntimeException e) {
                forceStopWithoutContact();
                logDetectionError(e);
            }
        }

        updateEffectiveCommand(freshFrame);

        saveCaptureFrame(frame, freshFrame);

        updateFaceSnapshots(frame, freshFrame);

        drawFaceBoxes(frame, freshFrame);

        drawOverlay(frame, freshFrame);
        if (shouldUpdateHelmetView()) {
            updateHelmetView(frame);
        }
        frameCounter++;
    }

    private void detectPeople(Mat frame) {
        long startedNs = System.nanoTime();
        Mat detectFrame = frame;
        Mat resizedFrame = null;
        double detectScale = 1.0;
        Size actualMinFaceSize = MIN_FACE_SIZE;
        String detectorName = PERSON_SCAN_ALL_ROTATIONS
            ? "HOG PEOPLE MULTI-ROTATION"
            : "HOG DEFAULT PEOPLE DETECTOR";
        Size detectorWindowSize = personHog.get_winSize();
        try {
            if (DETECT_MAX_WIDTH > 0 && frame.cols() > DETECT_MAX_WIDTH) {
                detectScale = DETECT_MAX_WIDTH / (double) frame.cols();
                resizedFrame = new Mat();
                Imgproc.resize(
                    frame,
                    resizedFrame,
                    new Size(DETECT_MAX_WIDTH, Math.max(1, Math.round(frame.rows() * detectScale))),
                    0,
                    0,
                    Imgproc.INTER_AREA
                );
                detectFrame = resizedFrame;
            }

            actualMinFaceSize = new Size(
                Math.max(20, MIN_FACE_SIZE.width * detectScale),
                Math.max(20, MIN_FACE_SIZE.height * detectScale)
            );
            DetectionSortResult sorted = detectHogPeopleAllRotations(detectFrame);
            if (sorted.faces.length == 0) {
                DetectionSortResult faceFallback = detectFaceFallbackAllRotations(detectFrame, actualMinFaceSize);
                if (faceFallback.faces.length > 0) {
                    sorted = faceFallback;
                    detectorName = PERSON_SCAN_ALL_ROTATIONS
                        ? "HAAR FACE MULTI-ROTATION > PERSON ESTIMATE"
                        : "HAAR FRONTAL FACE > PERSON ESTIMATE";
                    detectorWindowSize = faceFrontal.getOriginalWindowSize();
                }
            }

            Rect[] peopleOnDetectImage = sorted.faces;
            lastFaces = scaleFaces(peopleOnDetectImage, 1.0 / detectScale);
            faceCount = lastFaces.length;
            lastDetectionDebugInfo = new DetectionDebugInfo(
                copyFaces(peopleOnDetectImage),
                detectorName,
                sorted.rejectLevels,
                sorted.levelWeights,
                frame.cols(),
                frame.rows(),
                detectFrame.cols(),
                detectFrame.rows(),
                detectScale,
                (int) Math.round(actualMinFaceSize.width),
                (int) Math.round(actualMinFaceSize.height),
                (int) Math.round(detectorWindowSize.width),
                (int) Math.round(detectorWindowSize.height),
                nsToMs(System.nanoTime() - startedNs),
                frameCounter,
                System.currentTimeMillis()
            );
        } finally {
            if (resizedFrame != null) {
                resizedFrame.release();
            }
            recordPerfDetect(System.nanoTime() - startedNs);
        }
    }

    private void logDetectionError(RuntimeException error) {
        long now = System.currentTimeMillis();
        if (now - lastDetectionErrorLogAtMs < 5000) {
            return;
        }
        lastDetectionErrorLogAtMs = now;
        System.err.println(">>> [Detector] Failed; video remains active and command is forced to STOP");
        error.printStackTrace();
    }

    private Rect[] copyFaces(Rect[] faces) {
        Rect[] copy = new Rect[faces.length];
        for (int i = 0; i < faces.length; i++) {
            Rect r = faces[i];
            copy[i] = new Rect(r.x, r.y, r.width, r.height);
        }
        return copy;
    }

    private Rect[] expandFacesToPeople(Rect[] faces, int maxWidth, int maxHeight) {
        Rect[] people = new Rect[faces.length];
        for (int i = 0; i < faces.length; i++) {
            Rect face = faces[i];
            int personWidth = Math.max(face.width * 3, face.width + 24);
            int personHeight = Math.max(face.height * 5, face.height + 48);
            int x = face.x + face.width / 2 - personWidth / 2;
            int y = face.y - Math.round(face.height * 0.45f);
            int x1 = clamp(x, 0, Math.max(0, maxWidth - 1));
            int y1 = clamp(y, 0, Math.max(0, maxHeight - 1));
            int x2 = clamp(x + personWidth, x1 + 1, maxWidth);
            int y2 = clamp(y + personHeight, y1 + 1, maxHeight);
            people[i] = new Rect(x1, y1, x2 - x1, y2 - y1);
        }
        return people;
    }

    private DetectionSortResult detectHogPeopleAllRotations(Mat detectFrame) {
        List<Rect> allRects = new ArrayList<>();
        List<Integer> allRejects = new ArrayList<>();
        List<Double> allWeights = new ArrayList<>();
        for (int rotation : detectionRotations()) {
            DetectionSortResult oriented = detectHogPeopleAtRotation(detectFrame, rotation);
            if (oriented.faces.length > 0) {
                return oriented;
            }
            appendDetections(allRects, allRejects, allWeights, oriented);
        }
        return suppressOverlappingDetections(allRects, allRejects, allWeights);
    }

    private DetectionSortResult detectHogPeopleAtRotation(Mat sourceFrame, int rotation) {
        RotatedDetectionFrame rotatedFrame = rotateFrameForDetection(sourceFrame, rotation);
        Mat scanFrame = rotatedFrame.frame;
        MatOfRect peopleRects = new MatOfRect();
        MatOfDouble peopleWeights = new MatOfDouble();
        try {
            personHog.detectMultiScale(
                scanFrame,
                peopleRects,
                peopleWeights,
                PERSON_HOG_HIT_THRESHOLD,
                new Size(8, 8),
                new Size(16, 16),
                PERSON_HOG_SCALE,
                PERSON_HOG_FINAL_THRESHOLD,
                false
            );
            Rect[] detectedPeople = peopleRects.toArray();
            double[] detectedPeopleWeights = detectedPeople.length == 0 || peopleWeights.empty()
                ? new double[0]
                : peopleWeights.toArray();
            int[] detectedPeopleRejects = new int[detectedPeople.length];
            for (int i = 0; i < detectedPeopleRejects.length; i++) {
                detectedPeopleRejects[i] = -1;
            }
            DetectionSortResult filtered = filterPersonDetections(
                detectedPeople,
                detectedPeopleRejects,
                detectedPeopleWeights,
                scanFrame.cols(),
                scanFrame.rows(),
                PERSON_MIN_HOG_WEIGHT
            );
            return mapDetectionsToOriginal(filtered, rotatedFrame, sourceFrame.cols(), sourceFrame.rows());
        } finally {
            peopleRects.release();
            peopleWeights.release();
            rotatedFrame.close();
        }
    }

    private DetectionSortResult detectFaceFallbackAllRotations(Mat detectFrame, Size minFaceSize) {
        List<Rect> allRects = new ArrayList<>();
        List<Integer> allRejects = new ArrayList<>();
        List<Double> allWeights = new ArrayList<>();
        for (int rotation : detectionRotations()) {
            DetectionSortResult oriented = detectFaceFallbackAtRotation(detectFrame, rotation, minFaceSize);
            if (oriented.faces.length > 0) {
                return oriented;
            }
            appendDetections(allRects, allRejects, allWeights, oriented);
        }
        return suppressOverlappingDetections(allRects, allRejects, allWeights);
    }

    private DetectionSortResult detectFaceFallbackAtRotation(Mat sourceFrame, int rotation, Size minFaceSize) {
        RotatedDetectionFrame rotatedFrame = rotateFrameForDetection(sourceFrame, rotation);
        Mat scanFrame = rotatedFrame.frame;
        Mat gray = new Mat();
        MatOfRect frontalFaces = new MatOfRect();
        MatOfInt rejectLevels = new MatOfInt();
        MatOfDouble levelWeights = new MatOfDouble();
        try {
            Imgproc.cvtColor(scanFrame, gray, Imgproc.COLOR_BGR2GRAY);
            clahe.apply(gray, gray);
            faceFrontal.detectMultiScale3(
                gray,
                frontalFaces,
                rejectLevels,
                levelWeights,
                FACE_SCALE_FACTOR,
                FACE_MIN_NEIGHBORS,
                0,
                minFaceSize,
                new Size(),
                true
            );
            Rect[] detectedFaces = frontalFaces.toArray();
            int[] detectedRejectLevels = detectedFaces.length == 0 || rejectLevels.empty()
                ? new int[0]
                : rejectLevels.toArray();
            double[] detectedLevelWeights = detectedFaces.length == 0 || levelWeights.empty()
                ? new double[0]
                : levelWeights.toArray();
            Rect[] expandedPeople = expandFacesToPeople(detectedFaces, scanFrame.cols(), scanFrame.rows());
            DetectionSortResult filtered = filterPersonDetections(
                expandedPeople,
                detectedRejectLevels,
                detectedLevelWeights,
                scanFrame.cols(),
                scanFrame.rows(),
                PERSON_FACE_FALLBACK_MIN_WEIGHT
            );
            return mapDetectionsToOriginal(filtered, rotatedFrame, sourceFrame.cols(), sourceFrame.rows());
        } finally {
            gray.release();
            frontalFaces.release();
            rejectLevels.release();
            levelWeights.release();
            rotatedFrame.close();
        }
    }

    private int[] detectionRotations() {
        if (!PERSON_SCAN_ALL_ROTATIONS) {
            return new int[] {0};
        }

        int step = Math.max(1, Math.min(180, Math.abs(PERSON_ROTATION_STEP_DEGREES)));
        List<Integer> angles = new ArrayList<>();
        angles.add(0);
        for (int offset = step; offset < 180; offset += step) {
            addAngleIfMissing(angles, offset);
            addAngleIfMissing(angles, -offset);
        }
        addAngleIfMissing(angles, 180);

        int[] result = new int[angles.size()];
        for (int i = 0; i < angles.size(); i++) {
            result[i] = normalizeAngle(angles.get(i));
        }
        return result;
    }

    private void addAngleIfMissing(List<Integer> angles, int angle) {
        int normalized = normalizeAngle(angle);
        if (!angles.contains(normalized)) {
            angles.add(normalized);
        }
    }

    private RotatedDetectionFrame rotateFrameForDetection(Mat sourceFrame, int angleDegrees) {
        int angle = normalizeAngle(angleDegrees);
        if (angle == 0) {
            return new RotatedDetectionFrame(sourceFrame, false, 1.0, 0.0, 0.0, 1.0, 0.0, 0.0);
        }

        double centerX = sourceFrame.cols() / 2.0;
        double centerY = sourceFrame.rows() / 2.0;
        Mat rotationMatrix = Imgproc.getRotationMatrix2D(new Point(centerX, centerY), angle, 1.0);
        double a = rotationMatrix.get(0, 0)[0];
        double b = rotationMatrix.get(0, 1)[0];
        double tx = rotationMatrix.get(0, 2)[0];
        double c = rotationMatrix.get(1, 0)[0];
        double d = rotationMatrix.get(1, 1)[0];
        double ty = rotationMatrix.get(1, 2)[0];
        int rotatedWidth = Math.max(1, (int) Math.round(sourceFrame.rows() * Math.abs(b) + sourceFrame.cols() * Math.abs(a)));
        int rotatedHeight = Math.max(1, (int) Math.round(sourceFrame.rows() * Math.abs(d) + sourceFrame.cols() * Math.abs(c)));
        tx += rotatedWidth / 2.0 - centerX;
        ty += rotatedHeight / 2.0 - centerY;
        rotationMatrix.put(0, 2, tx);
        rotationMatrix.put(1, 2, ty);

        Mat rotated = new Mat();
        try {
            Imgproc.warpAffine(sourceFrame, rotated, rotationMatrix, new Size(rotatedWidth, rotatedHeight));
        } finally {
            rotationMatrix.release();
        }
        return new RotatedDetectionFrame(rotated, true, a, b, c, d, tx, ty);
    }

    private DetectionSortResult mapDetectionsToOriginal(
        DetectionSortResult oriented,
        RotatedDetectionFrame transform,
        int originalWidth,
        int originalHeight
    ) {
        Rect[] mappedRects = new Rect[oriented.faces.length];
        for (int i = 0; i < oriented.faces.length; i++) {
            mappedRects[i] = mapRectToOriginal(oriented.faces[i], transform, originalWidth, originalHeight);
        }
        return new DetectionSortResult(mappedRects, oriented.rejectLevels, oriented.levelWeights);
    }

    private Rect mapRectToOriginal(
        Rect rect,
        RotatedDetectionFrame transform,
        int originalWidth,
        int originalHeight
    ) {
        double[][] corners = new double[][] {
            {rect.x, rect.y},
            {rect.x + rect.width, rect.y},
            {rect.x, rect.y + rect.height},
            {rect.x + rect.width, rect.y + rect.height}
        };
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (double[] corner : corners) {
            double[] mapped = transform.mapScanPointToSource(corner[0], corner[1]);
            minX = Math.min(minX, mapped[0]);
            minY = Math.min(minY, mapped[1]);
            maxX = Math.max(maxX, mapped[0]);
            maxY = Math.max(maxY, mapped[1]);
        }
        return clampedRect(
            (int) Math.floor(minX),
            (int) Math.floor(minY),
            Math.max(1, (int) Math.ceil(maxX - minX)),
            Math.max(1, (int) Math.ceil(maxY - minY)),
            originalWidth,
            originalHeight
        );
    }

    private int normalizeAngle(int angle) {
        int normalized = angle % 360;
        return normalized < 0 ? normalized + 360 : normalized;
    }

    private Rect clampedRect(int x, int y, int width, int height, int maxWidth, int maxHeight) {
        int x1 = clamp(x, 0, Math.max(0, maxWidth - 1));
        int y1 = clamp(y, 0, Math.max(0, maxHeight - 1));
        int x2 = clamp(x + width, x1 + 1, maxWidth);
        int y2 = clamp(y + height, y1 + 1, maxHeight);
        return new Rect(x1, y1, x2 - x1, y2 - y1);
    }

    private DetectionSortResult filterPersonDetections(
        Rect[] rects,
        int[] rejectLevels,
        double[] weights,
        int frameWidth,
        int frameHeight,
        double minWeight
    ) {
        List<Rect> keptRects = new ArrayList<>();
        List<Integer> keptRejects = new ArrayList<>();
        List<Double> keptWeights = new ArrayList<>();
        for (int i = 0; i < rects.length; i++) {
            Rect rect = rects[i];
            double weight = i < weights.length ? weights[i] : 0.0;
            if (weight < minWeight || !isLikelyPersonRect(rect, frameWidth, frameHeight)) {
                continue;
            }
            keptRects.add(new Rect(rect.x, rect.y, rect.width, rect.height));
            keptRejects.add(i < rejectLevels.length ? rejectLevels[i] : -1);
            keptWeights.add(weight);
        }

        Rect[] filteredRects = keptRects.toArray(new Rect[0]);
        int[] filteredRejects = new int[keptRejects.size()];
        double[] filteredWeights = new double[keptWeights.size()];
        for (int i = 0; i < keptRects.size(); i++) {
            filteredRejects[i] = keptRejects.get(i);
            filteredWeights[i] = keptWeights.get(i);
        }
        return sortDetections(filteredRects, filteredRejects, filteredWeights);
    }

    private boolean isLikelyPersonRect(Rect rect, int frameWidth, int frameHeight) {
        if (rect == null || rect.width <= 0 || rect.height <= 0 || frameWidth <= 0 || frameHeight <= 0) {
            return false;
        }

        double aspect = rect.width / (double) rect.height;
        double heightRatio = rect.height / (double) frameHeight;
        double areaRatio = (rect.width * (double) rect.height) / (frameWidth * (double) frameHeight);
        return aspect >= PERSON_MIN_ASPECT_RATIO
            && aspect <= PERSON_MAX_ASPECT_RATIO
            && heightRatio >= PERSON_MIN_HEIGHT_RATIO
            && areaRatio >= PERSON_MIN_AREA_RATIO;
    }

    private void appendDetections(
        List<Rect> rects,
        List<Integer> rejectLevels,
        List<Double> weights,
        DetectionSortResult source
    ) {
        for (int i = 0; i < source.faces.length; i++) {
            Rect r = source.faces[i];
            rects.add(new Rect(r.x, r.y, r.width, r.height));
            rejectLevels.add(i < source.rejectLevels.length ? source.rejectLevels[i] : -1);
            weights.add(i < source.levelWeights.length ? source.levelWeights[i] : 0.0);
        }
    }

    private DetectionSortResult suppressOverlappingDetections(
        List<Rect> rects,
        List<Integer> rejectLevels,
        List<Double> weights
    ) {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < rects.size(); i++) {
            order.add(i);
        }
        order.sort((a, b) -> {
            int cmp = Double.compare(weights.get(b), weights.get(a));
            if (cmp != 0) {
                return cmp;
            }
            Rect ra = rects.get(a);
            Rect rb = rects.get(b);
            return Integer.compare(rb.width * rb.height, ra.width * ra.height);
        });

        List<Rect> keptRects = new ArrayList<>();
        List<Integer> keptRejects = new ArrayList<>();
        List<Double> keptWeights = new ArrayList<>();
        for (int sourceIndex : order) {
            Rect candidate = rects.get(sourceIndex);
            boolean overlapsExisting = false;
            for (Rect kept : keptRects) {
                if (intersectionOverUnion(candidate, kept) > 0.45) {
                    overlapsExisting = true;
                    break;
                }
            }
            if (overlapsExisting) {
                continue;
            }
            keptRects.add(new Rect(candidate.x, candidate.y, candidate.width, candidate.height));
            keptRejects.add(rejectLevels.get(sourceIndex));
            keptWeights.add(weights.get(sourceIndex));
        }

        Rect[] filteredRects = keptRects.toArray(new Rect[0]);
        int[] filteredRejects = new int[keptRejects.size()];
        double[] filteredWeights = new double[keptWeights.size()];
        for (int i = 0; i < keptRects.size(); i++) {
            filteredRejects[i] = keptRejects.get(i);
            filteredWeights[i] = keptWeights.get(i);
        }
        return sortDetections(filteredRects, filteredRejects, filteredWeights);
    }

    private double intersectionOverUnion(Rect a, Rect b) {
        int x1 = Math.max(a.x, b.x);
        int y1 = Math.max(a.y, b.y);
        int x2 = Math.min(a.x + a.width, b.x + b.width);
        int y2 = Math.min(a.y + a.height, b.y + b.height);
        int intersectionWidth = Math.max(0, x2 - x1);
        int intersectionHeight = Math.max(0, y2 - y1);
        double intersection = intersectionWidth * (double) intersectionHeight;
        double union = a.width * (double) a.height + b.width * (double) b.height - intersection;
        return union <= 0.0 ? 0.0 : intersection / union;
    }

    private DetectionSortResult sortDetections(Rect[] faces, int[] rejectLevels, double[] levelWeights) {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < faces.length; i++) {
            order.add(i);
        }
        order.sort((a, b) -> {
            Rect ra = faces[a];
            Rect rb = faces[b];
            int cmp = Integer.compare(ra.x, rb.x);
            if (cmp != 0) {
                return cmp;
            }
            cmp = Integer.compare(ra.y, rb.y);
            if (cmp != 0) {
                return cmp;
            }
            return Integer.compare(ra.width * ra.height, rb.width * rb.height);
        });

        Rect[] sortedFaces = new Rect[faces.length];
        int[] sortedRejects = new int[faces.length];
        double[] sortedWeights = new double[faces.length];
        for (int i = 0; i < order.size(); i++) {
            int sourceIndex = order.get(i);
            Rect r = faces[sourceIndex];
            sortedFaces[i] = new Rect(r.x, r.y, r.width, r.height);
            sortedRejects[i] = sourceIndex < rejectLevels.length ? rejectLevels[sourceIndex] : -1;
            sortedWeights[i] = sourceIndex < levelWeights.length ? levelWeights[sourceIndex] : 0.0;
        }
        return new DetectionSortResult(sortedFaces, sortedRejects, sortedWeights);
    }

    private Rect[] scaleFaces(Rect[] faces, double inverseScale) {
        if (Math.abs(inverseScale - 1.0) < 0.001) {
            return faces;
        }

        Rect[] scaled = new Rect[faces.length];
        for (int i = 0; i < faces.length; i++) {
            Rect r = faces[i];
            scaled[i] = new Rect(
                (int) Math.round(r.x * inverseScale),
                (int) Math.round(r.y * inverseScale),
                (int) Math.round(r.width * inverseScale),
                (int) Math.round(r.height * inverseScale)
            );
        }
        return scaled;
    }

    private boolean shouldUpdateHelmetView() {
        if (UI_MAX_FPS <= 0) {
            return true;
        }

        long now = System.currentTimeMillis();
        if (now < nextHelmetViewAtMs) {
            return false;
        }
        nextHelmetViewAtMs = now + Math.max(1, 1000 / UI_MAX_FPS);
        return true;
    }

    private void updatePersonPresenceState(int detectedPeople) {
        long now = System.currentTimeMillis();
        if (detectedPeople > 0) {
            missingFaceFrames = 0;
            if (eyeContactActive) {
                contactState = "PRESENT";
                return;
            }

            if (contactCandidateFrames == 0) {
                contactCandidateStartedAtMs = now;
            }
            contactCandidateFrames++;
            long candidateAgeMs = now - contactCandidateStartedAtMs;
            boolean enoughFrames = contactCandidateFrames >= Math.max(1, CONTACT_START_FRAMES);
            boolean enoughTime = candidateAgeMs >= Math.max(0, CONTACT_START_HOLD_MS);
            if (enoughFrames && enoughTime) {
                eyeContactActive = true;
                contactState = "PRESENT";
            } else {
                contactState = String.format(
                    Locale.ROOT,
                    "SEEN %d/%d %.1fs",
                    contactCandidateFrames,
                    Math.max(1, CONTACT_START_FRAMES),
                    Math.max(0, CONTACT_START_HOLD_MS - candidateAgeMs) / 1000.0
                );
            }
            return;
        }

        boolean wasActive = eyeContactActive;
        contactCandidateFrames = 0;
        contactCandidateStartedAtMs = 0;
        missingFaceFrames++;
        if (missingFaceFrames >= CONTACT_LOST_FRAMES) {
            eyeContactActive = false;
            contactState = "NONE";
        } else if (wasActive) {
            contactState = "LOST HOLD";
        } else {
            contactState = "NONE";
        }
    }

    private void forceStopWithoutContact() {
        eyeContactActive = false;
        faceCount = 0;
        lastFaces = new Rect[0];
        lastFaceSnapshots = new ArrayList<>();
        contactCandidateFrames = 0;
        contactCandidateStartedAtMs = 0;
        contactState = "NONE";
        missingFaceFrames = CONTACT_LOST_FRAMES;
        updateEffectiveCommand(false);
    }

    private void updateEffectiveCommand(boolean freshFrame) {
        long now = System.currentTimeMillis();
        if (manualModeActive) {
            if (manualInterlockActive) {
                currentCommand = STOP_COMMAND;
                controlMode = "MANUAL LOCK";
            } else {
                currentCommand = manualCommand;
                controlMode = "MANUAL " + manualDirectionName(manualCommand);
            }
            return;
        }

        if (now < autoResumeAtMs) {
            currentCommand = STOP_COMMAND;
            controlMode = String.format(Locale.ROOT, "AUTO WAIT %.1fs", (autoResumeAtMs - now) / 1000.0);
            return;
        }

        controlMode = "AUTO";
        currentCommand = freshFrame && eyeContactActive ? CONTRACT_COMMAND : STOP_COMMAND;
    }

    private String manualDirectionName(String command) {
        if (CONTRACT_COMMAND.equals(command)) {
            return "UP";
        }
        if (DESCEND_COMMAND.equals(command)) {
            return "DOWN";
        }
        return "STOP";
    }

    private void startHelmetCameraLoop() {
        new Thread(() -> {
            while (true) {
                VideoCapture cap = new VideoCapture();
                try {
                    streamConnected = false;
                    streamStatus = "Camera: connecting";
                    forceStopWithoutContact();
                    System.out.println(">>> [Camera] Connecting: " + HELMET_CAMERA_URL);

                    if (!openCamera(cap)) {
                        streamStatus = "Camera: open failed";
                        sleep(STREAM_RECONNECT_MS);
                        continue;
                    }

                    cap.set(Videoio.CAP_PROP_BUFFERSIZE, 1);
                    streamConnected = false;
                    streamStatus = "Camera: validating";
                    System.out.println(">>> [Camera] Transport connected; validating frame changes");
                    openCameraPageAfterConnect();

                    try (FrameMats mats = new FrameMats()) {
                        byte[] fingerprintBytes = new byte[16 * 12 * 4];
                        long lastGoodFrameAt = System.currentTimeMillis();
                        long nextContentCheckAt = 0;
                        long lastContentChangeAt = lastGoodFrameAt;
                        long lastFingerprint = Long.MIN_VALUE;
                        boolean contentValidated = false;
                        boolean frozenContent = false;
                        while (true) {
                            boolean ok = cap.read(mats.frame);
                            if (!ok || mats.frame.empty()) {
                                if (System.currentTimeMillis() - lastGoodFrameAt > STREAM_STALE_MS) {
                                    break;
                                }
                                sleep(30);
                                continue;
                            }

                            lastGoodFrameAt = System.currentTimeMillis();
                            if (lastGoodFrameAt >= nextContentCheckAt) {
                                nextContentCheckAt = lastGoodFrameAt + Math.max(50, FRAME_CONTENT_CHECK_MS);
                                long fingerprint = frameFingerprint(mats.frame, mats.fingerprintFrame, fingerprintBytes);
                                if (lastFingerprint == Long.MIN_VALUE) {
                                    lastFingerprint = fingerprint;
                                    lastContentChangeAt = lastGoodFrameAt;
                                } else if (fingerprint != lastFingerprint) {
                                    lastFingerprint = fingerprint;
                                    lastContentChangeAt = lastGoodFrameAt;
                                    frozenContent = false;
                                    if (!contentValidated) {
                                        contentValidated = true;
                                        streamConnected = true;
                                        streamStatus = "Camera: online";
                                        System.out.println(">>> [Camera] Frame content validated");
                                    }
                                } else if (!frozenContent && lastGoodFrameAt - lastContentChangeAt > STREAM_STALE_MS) {
                                    frozenContent = true;
                                    contentValidated = false;
                                    streamConnected = false;
                                    streamStatus = "Camera: frozen";
                                    forceStopWithoutContact();
                                    System.out.println(">>> [Camera] Frame content frozen");
                                }
                            }
                            synchronized (frameLock) {
                                if (latestFrame != null) {
                                    latestFrame.release();
                                }
                                latestFrame = mats.frame.clone();
                                latestFrameAtMs = lastGoodFrameAt;
                                latestFrameSequence++;
                            }
                        }
                    }
                    streamStatus = "Camera: stream ended";
                    System.out.println(">>> [Camera] Stream ended, retrying");
                } catch (Exception e) {
                    streamStatus = "Camera: error";
                    e.printStackTrace();
                } finally {
                    streamConnected = false;
                    forceStopWithoutContact();
                    cap.release();
                }

                sleep(STREAM_RECONNECT_MS);
            }
        }, "HelmetCamera").start();
    }

    private long frameFingerprint(Mat frame, Mat fingerprintFrame, byte[] fingerprintBytes) {
        Imgproc.resize(frame, fingerprintFrame, new Size(16, 12), 0, 0, Imgproc.INTER_AREA);
        int byteCount = (int) (fingerprintFrame.total() * fingerprintFrame.channels());
        fingerprintFrame.get(0, 0, fingerprintBytes);

        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < byteCount; i++) {
            hash ^= fingerprintBytes[i] & 0xffL;
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private boolean openCamera(VideoCapture cap) {
        String source = HELMET_CAMERA_URL.trim();
        if (source.matches("\\d+")) {
            return cap.open(Integer.parseInt(source));
        }
        return cap.open(source, Videoio.CAP_FFMPEG);
    }

    private void openCameraPageAfterConnect() {
        if (!AUTO_OPEN_CAMERA_PAGE || CAMERA_PAGE_URL.isBlank()) {
            return;
        }
        if (cameraPageOpened && !OPEN_CAMERA_PAGE_ON_RECONNECT) {
            return;
        }

        cameraPageOpened = true;
        new Thread(() -> {
            try {
                if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    System.out.println(">>> [Camera Page] Browser open is not supported on this system");
                    return;
                }
                URI pageUri = new URI(withHttpScheme(CAMERA_PAGE_URL));
                Desktop.getDesktop().browse(pageUri);
                System.out.println(">>> [Camera Page] Opened: " + pageUri);
            } catch (IOException | URISyntaxException e) {
                System.out.println(">>> [Camera Page] Failed to open: " + CAMERA_PAGE_URL);
                e.printStackTrace();
            }
        }, "CameraPageOpener").start();
    }

    private void startSerialMonitorLoop() {
        new Thread(() -> {
            while (true) {
                ensureControllerConnected();
                sleep(SERIAL_SCAN_INTERVAL_MS);
            }
        }, "ControllerSerialMonitor").start();
    }

    private void ensureControllerConnected() {
        synchronized (serialLock) {
            if (controllerPort != null && controllerPort.isOpen()) {
                serialConnected = true;
                serialStatus = "ESP32: " + describePort(controllerPort);
                return;
            }
            closeControllerPortLocked();
        }

        SerialPort candidate = findControllerPort();
        if (candidate == null) {
            serialConnected = false;
            serialStatus = "ESP32: scanning";
            return;
        }

        candidate.setBaudRate(CONTROLLER_BAUD_RATE);
        candidate.setComPortTimeouts(SerialPort.TIMEOUT_NONBLOCKING, 0, 0);

        if (!candidate.openPort()) {
            serialConnected = false;
            serialStatus = "ESP32: open failed " + describePort(candidate);
            return;
        }

        synchronized (serialLock) {
            controllerPort = candidate;
            controllerReadyAtMs = System.currentTimeMillis() + CONTROLLER_BOOT_WAIT_MS;
            serialConnected = true;
            serialStatus = "ESP32: " + describePort(candidate);
        }
        System.out.println(">>> [ESP32] Connected: " + describePort(candidate));
    }

    private SerialPort findControllerPort() {
        String[] tokens = CONTROLLER_PORT_MATCH.toLowerCase(Locale.ROOT).split("[,;\\s]+");
        for (SerialPort port : SerialPort.getCommPorts()) {
            String text = (safeLower(port.getSystemPortName()) + " " + safeLower(port.getDescriptivePortName()));
            for (String token : tokens) {
                if (!token.isBlank() && text.contains(token)) {
                    return port;
                }
            }
        }
        return null;
    }

    private void heartbeatLoop() {
        while (true) {
            if (espControlEnabled) {
                sendCommand(currentCommand);
            }
            sleep(COMMAND_INTERVAL_MS);
        }
    }

    private void sendCommand(String cmd) {
        sendCommand(cmd, true);
    }

    private void sendCommand(String cmd, boolean respectBootWait) {
        byte[] bytes = (cmd + "\n").getBytes(StandardCharsets.UTF_8);
        synchronized (serialLock) {
            if (controllerPort == null || !controllerPort.isOpen()) {
                serialConnected = false;
                return;
            }
            if (respectBootWait && System.currentTimeMillis() < controllerReadyAtMs) {
                return;
            }

            int written = controllerPort.writeBytes(bytes, bytes.length);
            if (written != bytes.length) {
                serialConnected = false;
                serialStatus = "ESP32: write failed";
                closeControllerPortLocked();
            }
        }
    }

    private void closeControllerPortLocked() {
        if (controllerPort == null) {
            return;
        }
        try {
            controllerPort.closePort();
        } catch (Exception ignored) {
        } finally {
            controllerPort = null;
            controllerReadyAtMs = 0;
        }
    }

    private void initWindowUI() {
        KeyAdapter keyHandler = new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                handleKeyPress(e.getKeyCode());
            }

            @Override
            public void keyReleased(KeyEvent e) {
                handleKeyRelease(e.getKeyCode());
            }
        };

        frameHelmet = new JFrame("Helmet View");
        frameHelmet.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frameHelmet.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                requestExit();
            }

            @Override
            public void windowLostFocus(WindowEvent e) {
                finishManualMode();
            }
        });
        viewHelmetPanel = new HelmetViewPanel();
        frameHelmet.add(viewHelmetPanel, BorderLayout.CENTER);
        frameHelmet.setMinimumSize(new Dimension(640, 480));
        frameHelmet.setSize(1280, 960);
        frameHelmet.setLocation(0, 0);
        frameHelmet.addKeyListener(keyHandler);
        viewHelmetPanel.addKeyListener(keyHandler);
        viewHelmetPanel.setFocusable(true);
        frameHelmet.setExtendedState(JFrame.MAXIMIZED_BOTH);
        frameHelmet.setVisible(true);
        viewHelmetPanel.requestFocusInWindow();
    }

    private void startStatusRefreshTimer() {
        statusRefreshTimer = new javax.swing.Timer(1000, e -> updateConnectionStatusOverlay());
        statusRefreshTimer.setInitialDelay(0);
        statusRefreshTimer.start();
    }

    private void updateConnectionStatusOverlay() {
        if (viewHelmetPanel == null) {
            return;
        }
        updateEffectiveCommand(streamConnected);
        viewHelmetPanel.setConnectionStatus(new ConnectionStatus(
            streamConnected,
            streamStatus,
            serialConnected,
            serialStatus,
            espControlEnabled,
            currentCommand,
            controlMode,
            faceCount,
            eyeContactActive,
            contactState,
            captureAvailable,
            captureStatus,
            flipHelmetView,
            debugUiVisible,
            currentFaceDetailBlocks(),
            LocalDateTime.now().format(UI_STATUS_TIME_FORMAT)
        ));
    }

    private List<FaceDetailBlock> currentFaceDetailBlocks() {
        List<FaceDetailBlock> blocks = new ArrayList<>();
        if (!debugUiVisible || lastFaces.length == 0) {
            return blocks;
        }

        List<BufferedImage> snapshots = lastFaceSnapshots;
        int count = lastFaces.length;
        for (int i = 0; i < count; i++) {
            BufferedImage snapshot = i < snapshots.size() ? snapshots.get(i) : null;
            blocks.add(new FaceDetailBlock(snapshot, faceDetailLines(i), eyeContactActive));
        }
        return blocks;
    }

    private void handleKeyPress(int keyCode) {
        switch (keyCode) {
            case KeyEvent.VK_ESCAPE:
                requestExit();
                break;
            case KeyEvent.VK_UP:
                manualUpPressed = true;
                updateManualModeFromKeys();
                break;
            case KeyEvent.VK_DOWN:
                manualDownPressed = true;
                updateManualModeFromKeys();
                break;
            case KeyEvent.VK_F:
                flipHelmetView = !flipHelmetView;
                break;
            case KeyEvent.VK_D:
                toggleDebugUI();
                break;
            case KeyEvent.VK_SPACE:
                toggleEspControl();
                break;
            default:
                break;
        }
    }

    private void handleKeyRelease(int keyCode) {
        switch (keyCode) {
            case KeyEvent.VK_UP:
                manualUpPressed = false;
                updateManualModeFromKeys();
                break;
            case KeyEvent.VK_DOWN:
                manualDownPressed = false;
                updateManualModeFromKeys();
                break;
            default:
                break;
        }
    }

    private void startManualMode(String command) {
        manualModeActive = true;
        manualInterlockActive = false;
        manualCommand = command;
        autoResumeAtMs = 0;
        updateEffectiveCommand(false);
    }

    private void toggleEspControl() {
        setEspControlEnabled(!espControlEnabled);
    }

    private void setEspControlEnabled(boolean enabled) {
        if (espControlEnabled == enabled) {
            return;
        }
        if (!enabled) {
            espControlEnabled = false;
            forceStopEspOutput();
        } else {
            espControlEnabled = true;
        }
        updateConnectionStatusOverlay();
    }

    private void forceStopEspOutput() {
        for (int i = 0; i < 3; i++) {
            sendCommand(STOP_COMMAND, false);
            sleep(30);
        }
    }

    private void updateManualModeFromKeys() {
        if (manualUpPressed && manualDownPressed) {
            startManualInterlockMode();
            return;
        }
        if (manualUpPressed) {
            startManualMode(CONTRACT_COMMAND);
            return;
        }
        if (manualDownPressed) {
            startManualMode(DESCEND_COMMAND);
            return;
        }
        finishManualMode();
    }

    private void startManualInterlockMode() {
        manualModeActive = true;
        manualInterlockActive = true;
        manualCommand = STOP_COMMAND;
        currentCommand = STOP_COMMAND;
        autoResumeAtMs = 0;
        updateEffectiveCommand(false);
    }

    private void finishManualMode() {
        if (!manualModeActive && manualCommand.equals(STOP_COMMAND)) {
            return;
        }
        manualModeActive = false;
        manualInterlockActive = false;
        manualCommand = STOP_COMMAND;
        currentCommand = STOP_COMMAND;
        autoResumeAtMs = System.currentTimeMillis() + Math.max(0, MANUAL_AUTO_RESUME_DELAY_MS);
        updateEffectiveCommand(false);
    }

    private void requestExit() {
        new Thread(() -> {
            safeShutdown();
            System.exit(0);
        }, "HoistExit").start();
    }

    private void safeShutdown() {
        if (!shutdownStarted.compareAndSet(false, true)) {
            return;
        }

        manualModeActive = false;
        manualUpPressed = false;
        manualDownPressed = false;
        manualInterlockActive = false;
        manualCommand = STOP_COMMAND;
        autoResumeAtMs = 0;
        forceStopWithoutContact();
        if (statusRefreshTimer != null) {
            SwingUtilities.invokeLater(statusRefreshTimer::stop);
        }
        for (int i = 0; i < 3; i++) {
            sendCommand(STOP_COMMAND, false);
            sleep(50);
        }
        synchronized (serialLock) {
            closeControllerPortLocked();
        }
        closeCaptureResources();
    }

    private void toggleDebugUI() {
        debugUiVisible = !debugUiVisible;
        viewHelmetPanel.requestFocusInWindow();
    }

    private void drawOverlay(Mat frame, boolean freshFrame) {
        Scalar green = new Scalar(0, 255, 0);
        Scalar red = new Scalar(0, 0, 255);
        Scalar mainColor = eyeContactActive && freshFrame ? green : red;

        String centerText = eyeContactActive && freshFrame ? "CONTRACT" : "STOP";
        drawHugeCenteredText(frame, centerText, mainColor);
    }

    private void drawFaceBoxes(Mat frame, boolean freshFrame) {
        if (!freshFrame || lastFaces.length == 0) {
            return;
        }

        Scalar boxColor = new Scalar(0, 255, 0);
        for (int i = 0; i < lastFaces.length; i++) {
            Rect r = lastFaces[i];
            Imgproc.rectangle(frame, r, boxColor, 2);
        }
    }

    private void updateFaceSnapshots(Mat frame, boolean freshFrame) {
        if (!freshFrame || lastFaces.length == 0 || frame.empty()) {
            lastFaceSnapshots = new ArrayList<>();
            return;
        }

        List<BufferedImage> snapshots = new ArrayList<>();
        for (Rect face : lastFaces) {
            Rect cropRect = paddedFaceRect(face, frame.cols(), frame.rows());
            if (cropRect.width <= 0 || cropRect.height <= 0) {
                snapshots.add(null);
                continue;
            }

            Mat roi = new Mat(frame, cropRect);
            Mat crop = roi.clone();
            try {
                snapshots.add(matToBufferedImage(crop));
            } finally {
                crop.release();
                roi.release();
            }
        }
        lastFaceSnapshots = snapshots;
    }

    private Rect paddedFaceRect(Rect face, int maxWidth, int maxHeight) {
        int padX = Math.max(8, Math.round(face.width * 0.25f));
        int padY = Math.max(8, Math.round(face.height * 0.30f));
        int x1 = clamp(face.x - padX, 0, Math.max(0, maxWidth - 1));
        int y1 = clamp(face.y - padY, 0, Math.max(0, maxHeight - 1));
        int x2 = clamp(face.x + face.width + padX, x1 + 1, maxWidth);
        int y2 = clamp(face.y + face.height + padY, y1 + 1, maxHeight);
        return new Rect(x1, y1, x2 - x1, y2 - y1);
    }

    private List<String> faceDetailLines(int faceIndex) {
        List<String> lines = new ArrayList<>();
        DetectionDebugInfo detection = lastDetectionDebugInfo;
        Rect r = faceIndex < detection.facesOnDetectImage.length ? detection.facesOnDetectImage[faceIndex] : null;
        if (r == null) {
            return lines;
        }

        String faceState = eyeContactActive ? "PERSON PRESENT" : "PERSON DETECTED";
        lines.add(String.format(Locale.ROOT, "PERSON %02d / %s / FRAME #%d", faceIndex + 1, faceState, frameCounter));
        lines.add("DETECTOR: " + detection.detectorName);
        lines.add(String.format(
            Locale.ROOT,
            "PIPELINE: BGR > GRAY > CLAHE / %dx%d > %dx%d",
            detection.sourceWidth,
            detection.sourceHeight,
            detection.detectWidth,
            detection.detectHeight
        ));
        lines.add(String.format(
            Locale.ROOT,
            "PARAM: HOG SCALE %.2f / FACE NEIGHBORS %d / MIN %dx%d",
            PERSON_HOG_SCALE,
            FACE_MIN_NEIGHBORS,
            detection.minFaceWidth,
            detection.minFaceHeight
        ));
        lines.add(String.format(
            Locale.ROOT,
            "BBOX: X %d / Y %d / W %d / H %d",
            scaleBack(r.x, detection),
            scaleBack(r.y, detection),
            scaleBack(r.width, detection),
            scaleBack(r.height, detection)
        ));
        lines.add(String.format(
            Locale.ROOT,
            "RESPONSE: WEIGHT %.2f / REJECT %d / %.1f MS / %s",
            weightAt(faceIndex, detection),
            rejectLevelAt(faceIndex, detection),
            detection.detectMs,
            currentCommand
        ));
        return lines;
    }

    private int scaleBack(int value, DetectionDebugInfo detection) {
        if (detection.detectScale <= 0.0) {
            return value;
        }
        return (int) Math.round(value / detection.detectScale);
    }

    private double weightAt(int index, DetectionDebugInfo detection) {
        return index >= 0 && index < detection.levelWeights.length ? detection.levelWeights[index] : 0.0;
    }

    private int rejectLevelAt(int index, DetectionDebugInfo detection) {
        return index >= 0 && index < detection.rejectLevels.length ? detection.rejectLevels[index] : -1;
    }

    private void drawHugeCenteredText(Mat frame, String text, Scalar color) {
        double fontScale = Math.max(2.9, Math.min(frame.cols(), frame.rows()) / 260.0);
        int thickness = 5;
        Size textSize = Imgproc.getTextSize(text, OVERLAY_MAIN_FONT_FACE, fontScale, thickness, new int[1]);
        Point center = new Point((frame.cols() - textSize.width) / 2, (frame.rows() + textSize.height) / 2);
        Imgproc.putText(frame, text, center, OVERLAY_MAIN_FONT_FACE, fontScale, new Scalar(0, 0, 0), thickness + 3);
        Imgproc.putText(frame, text, center, OVERLAY_MAIN_FONT_FACE, fontScale, color, thickness);
    }

    private void updateHelmetView(Mat mat) {
        if (!helmetViewUpdatePending.compareAndSet(false, true)) {
            return;
        }

        final BufferedImage img = matToBufferedImage(mat);
        if (img != null) {
            SwingUtilities.invokeLater(() -> {
                try {
                    viewHelmetPanel.setImage(img);
                } finally {
                    helmetViewUpdatePending.set(false);
                }
            });
            return;
        }
        helmetViewUpdatePending.set(false);
    }

    private void updateWaitingText() {
        if (!helmetViewUpdatePending.compareAndSet(false, true)) {
            return;
        }

        String text = "Connecting helmet camera...\n"
            + streamStatus + "\n"
            + serialStatus + "\n"
            + "ESP Control: " + (espControlEnabled ? "ENABLED" : "DISABLED") + "\n"
            + captureStatus + "\n"
            + "Command: " + currentCommand;
        SwingUtilities.invokeLater(() -> {
            try {
                viewHelmetPanel.setStatusText(text);
            } finally {
                helmetViewUpdatePending.set(false);
            }
        });
    }

    private void initCaptureSession() {
        if (!CAPTURE_ENABLED) {
            captureAvailable = false;
            captureStatus = "Capture: disabled";
            return;
        }

        try {
            String sessionName = LocalDateTime.now().format(SESSION_TIME_FORMAT);
            captureSessionDir = Path.of(CAPTURE_DIR).resolve(sessionName);
            captureFramesDir = captureSessionDir.resolve("frames");
            captureVideoPath = captureSessionDir.resolve(CAPTURE_MP4_FILENAME);
            Files.createDirectories(captureFramesDir);

            Path metadataPath = captureSessionDir.resolve("metadata.csv");
            captureLogWriter = Files.newBufferedWriter(
                metadataPath,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
            );
            captureLogWriter.write("index,time,epoch_ms,file,people,person_active,command,esp_enabled,stream_status,serial_status\n");
            captureLogWriter.flush();

            captureAvailable = true;
            captureMp4Available = CAPTURE_MP4_ENABLED;
            captureStatus = "Capture: " + captureSessionDir.toString();
            System.out.println(">>> [Capture] Session: " + captureSessionDir.toAbsolutePath());
            if (CAPTURE_MP4_ENABLED) {
                System.out.println(">>> [Capture] MP4: " + captureVideoPath.toAbsolutePath());
            }
        } catch (IOException e) {
            captureAvailable = false;
            captureMp4Available = false;
            captureStatus = "Capture: init failed";
            e.printStackTrace();
        }
    }

    private void saveCaptureFrame(Mat frame, boolean freshFrame) {
        if (!CAPTURE_ENABLED || !captureAvailable || !freshFrame) {
            return;
        }

        long now = System.currentTimeMillis();
        synchronized (captureLock) {
            boolean wroteVideo = writeCaptureVideoFrame(frame, now);
            if (CAPTURE_INTERVAL_MS > 0 && now < nextCaptureAtMs) {
                if (wroteVideo) {
                    updateCaptureStatus();
                }
                return;
            }
            nextCaptureAtMs = now + Math.max(0, CAPTURE_INTERVAL_MS);

            int index = ++captureFrameIndex;
            String filename = String.format(Locale.ROOT, "frame_%06d.jpg", index);
            Path imagePath = captureFramesDir.resolve(filename);

            MatOfInt params = new MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, clamp(CAPTURE_JPEG_QUALITY, 1, 100));
            boolean saved = false;
            try {
                long startedNs = System.nanoTime();
                saved = Imgcodecs.imwrite(imagePath.toString(), frame, params);
                recordPerfJpg(System.nanoTime() - startedNs);
                if (!saved) {
                    captureStatus = "Capture: write failed";
                    return;
                }

                writeCaptureLog(index, now, captureSessionDir.relativize(imagePath).toString());
                updateCaptureStatus();
            } catch (IOException e) {
                captureAvailable = false;
                captureStatus = "Capture: log failed";
                e.printStackTrace();
            } finally {
                params.release();
            }
        }
    }

    private boolean writeCaptureVideoFrame(Mat frame, long now) {
        if (!CAPTURE_MP4_ENABLED || !captureMp4Available) {
            return false;
        }
        if (CAPTURE_MP4_INTERVAL_MS > 0 && now < nextCaptureVideoAtMs) {
            return false;
        }
        nextCaptureVideoAtMs = now + Math.max(0, CAPTURE_MP4_INTERVAL_MS);

        if (captureVideoWriter == null && !openCaptureVideoWriter(frame)) {
            return false;
        }

        Mat videoFrame = frame;
        Mat resized = null;
        try {
            if (captureVideoSize != null && (frame.cols() != (int) captureVideoSize.width || frame.rows() != (int) captureVideoSize.height)) {
                resized = new Mat();
                Imgproc.resize(frame, resized, captureVideoSize);
                videoFrame = resized;
            }
            long startedNs = System.nanoTime();
            captureVideoWriter.write(videoFrame);
            recordPerfMp4(System.nanoTime() - startedNs);
            captureVideoFrameIndex++;
            return true;
        } catch (Exception e) {
            captureMp4Available = false;
            captureStatus = "Capture: MP4 write failed";
            e.printStackTrace();
            return false;
        } finally {
            if (resized != null) {
                resized.release();
            }
        }
    }

    private boolean openCaptureVideoWriter(Mat firstFrame) {
        if (captureVideoPath == null || firstFrame.empty()) {
            return false;
        }

        captureVideoSize = new Size(firstFrame.cols(), firstFrame.rows());
        String[] codecs = {"mp4v", "avc1", "H264"};
        for (String codec : codecs) {
            VideoWriter writer = new VideoWriter(
                captureVideoPath.toString(),
                fourcc(codec),
                CAPTURE_MP4_FPS,
                captureVideoSize,
                true
            );
            if (writer.isOpened()) {
                captureVideoWriter = writer;
                System.out.println(">>> [Capture] MP4 writer opened: " + captureVideoPath.toAbsolutePath()
                    + " codec=" + codec
                    + " fps=" + CAPTURE_MP4_FPS
                    + " size=" + (int) captureVideoSize.width + "x" + (int) captureVideoSize.height);
                return true;
            }
            writer.release();
        }

        captureMp4Available = false;
        captureStatus = "Capture: MP4 open failed";
        System.out.println(">>> [Capture] MP4 writer failed: " + captureVideoPath.toAbsolutePath());
        return false;
    }

    private void updateCaptureStatus() {
        if (CAPTURE_MP4_ENABLED) {
            captureStatus = "Capture: " + captureFrameIndex + " jpg, " + captureVideoFrameIndex + " mp4 frames";
            return;
        }
        captureStatus = "Capture: " + captureFrameIndex + " frames";
    }

    private void writeCaptureLog(int index, long epochMs, String relativeFile) throws IOException {
        if (captureLogWriter == null) {
            return;
        }
        String time = LocalDateTime.now().format(LOG_TIME_FORMAT);
        captureLogWriter.write(index + ","
            + csv(time) + ","
            + epochMs + ","
            + csv(relativeFile) + ","
            + faceCount + ","
            + eyeContactActive + ","
            + csv(currentCommand) + ","
            + espControlEnabled + ","
            + csv(streamStatus) + ","
            + csv(serialStatus)
            + "\n");
        captureLogWriter.flush();
    }

    private void closeCaptureResources() {
        synchronized (captureLock) {
            if (captureLogWriter != null) {
                try {
                    captureLogWriter.flush();
                    captureLogWriter.close();
                } catch (IOException e) {
                    e.printStackTrace();
                } finally {
                    captureLogWriter = null;
                }
            }
            if (captureVideoWriter != null) {
                try {
                    captureVideoWriter.release();
                    if (captureVideoPath != null) {
                        System.out.println(">>> [Capture] MP4 closed: " + captureVideoPath.toAbsolutePath());
                    }
                } finally {
                    captureVideoWriter = null;
                }
            }
        }
    }

    private void recordPerfLoop(boolean hadFrame, boolean freshFrame, long durationNs) {
        if (!PERF_LOG_ENABLED) {
            return;
        }

        perfLoopCount++;
        perfLoopTotalNs += durationNs;
        perfLoopMaxNs = Math.max(perfLoopMaxNs, durationNs);
        if (!hadFrame) {
            perfNoFrameCount++;
        } else if (freshFrame) {
            perfFreshFrameCount++;
            synchronized (frameLock) {
                if (latestFrame != null && !latestFrame.empty()) {
                    perfLastFrameWidth = latestFrame.cols();
                    perfLastFrameHeight = latestFrame.rows();
                }
            }
        } else {
            perfStaleFrameCount++;
        }
        maybePrintPerfLog();
    }

    private void recordPerfDetect(long durationNs) {
        if (!PERF_LOG_ENABLED) {
            return;
        }
        perfDetectCount++;
        perfDetectTotalNs += durationNs;
        perfDetectMaxNs = Math.max(perfDetectMaxNs, durationNs);
    }

    private void recordPerfJpg(long durationNs) {
        if (!PERF_LOG_ENABLED) {
            return;
        }
        perfJpgCount++;
        perfJpgTotalNs += durationNs;
        perfJpgMaxNs = Math.max(perfJpgMaxNs, durationNs);
    }

    private void recordPerfMp4(long durationNs) {
        if (!PERF_LOG_ENABLED) {
            return;
        }
        perfMp4Count++;
        perfMp4TotalNs += durationNs;
        perfMp4MaxNs = Math.max(perfMp4MaxNs, durationNs);
    }

    private void maybePrintPerfLog() {
        long now = System.currentTimeMillis();
        long elapsedMs = now - perfWindowStartedMs;
        if (elapsedMs < Math.max(1000, PERF_LOG_INTERVAL_MS)) {
            return;
        }

        double seconds = elapsedMs / 1000.0;
        System.out.println(String.format(Locale.ROOT,
            ">>> [Perf] %.1fs loops=%d %.1f/s frame=%dx%d fresh=%d stale=%d no_frame=%d "
                + "loop_avg=%.1fms loop_max=%.1fms detect=%d avg/max=%.1f/%.1fms "
                + "jpg=%d avg/max=%.1f/%.1fms mp4=%d avg/max=%.1f/%.1fms",
            seconds,
            perfLoopCount,
            perfLoopCount / seconds,
            perfLastFrameWidth,
            perfLastFrameHeight,
            perfFreshFrameCount,
            perfStaleFrameCount,
            perfNoFrameCount,
            avgMs(perfLoopTotalNs, perfLoopCount),
            nsToMs(perfLoopMaxNs),
            perfDetectCount,
            avgMs(perfDetectTotalNs, perfDetectCount),
            nsToMs(perfDetectMaxNs),
            perfJpgCount,
            avgMs(perfJpgTotalNs, perfJpgCount),
            nsToMs(perfJpgMaxNs),
            perfMp4Count,
            avgMs(perfMp4TotalNs, perfMp4Count),
            nsToMs(perfMp4MaxNs)
        ));
        resetPerfWindow(now);
    }

    private void resetPerfWindow(long now) {
        perfWindowStartedMs = now;
        perfLoopCount = 0;
        perfFreshFrameCount = 0;
        perfStaleFrameCount = 0;
        perfNoFrameCount = 0;
        perfLoopTotalNs = 0;
        perfLoopMaxNs = 0;
        perfDetectCount = 0;
        perfDetectTotalNs = 0;
        perfDetectMaxNs = 0;
        perfJpgCount = 0;
        perfJpgTotalNs = 0;
        perfJpgMaxNs = 0;
        perfMp4Count = 0;
        perfMp4TotalNs = 0;
        perfMp4MaxNs = 0;
    }

    private static double avgMs(long totalNs, int count) {
        if (count <= 0) {
            return 0.0;
        }
        return nsToMs(totalNs) / count;
    }

    private static double nsToMs(long ns) {
        return ns / 1_000_000.0;
    }

    private static class DetectionDebugInfo {
        private final Rect[] facesOnDetectImage;
        private final String detectorName;
        private final int[] rejectLevels;
        private final double[] levelWeights;
        private final int sourceWidth;
        private final int sourceHeight;
        private final int detectWidth;
        private final int detectHeight;
        private final double detectScale;
        private final int minFaceWidth;
        private final int minFaceHeight;
        private final int cascadeWindowWidth;
        private final int cascadeWindowHeight;
        private final double detectMs;
        private final int detectionFrame;
        private final long createdAtMs;

        DetectionDebugInfo(
            Rect[] facesOnDetectImage,
            String detectorName,
            int[] rejectLevels,
            double[] levelWeights,
            int sourceWidth,
            int sourceHeight,
            int detectWidth,
            int detectHeight,
            double detectScale,
            int minFaceWidth,
            int minFaceHeight,
            int cascadeWindowWidth,
            int cascadeWindowHeight,
            double detectMs,
            int detectionFrame,
            long createdAtMs
        ) {
            this.facesOnDetectImage = facesOnDetectImage == null ? new Rect[0] : facesOnDetectImage;
            this.detectorName = detectorName == null ? "UNKNOWN PERSON DETECTOR" : detectorName;
            this.rejectLevels = rejectLevels == null ? new int[0] : rejectLevels;
            this.levelWeights = levelWeights == null ? new double[0] : levelWeights;
            this.sourceWidth = sourceWidth;
            this.sourceHeight = sourceHeight;
            this.detectWidth = detectWidth;
            this.detectHeight = detectHeight;
            this.detectScale = detectScale;
            this.minFaceWidth = minFaceWidth;
            this.minFaceHeight = minFaceHeight;
            this.cascadeWindowWidth = cascadeWindowWidth;
            this.cascadeWindowHeight = cascadeWindowHeight;
            this.detectMs = detectMs;
            this.detectionFrame = detectionFrame;
            this.createdAtMs = createdAtMs;
        }

        static DetectionDebugInfo empty() {
            return new DetectionDebugInfo(
                new Rect[0],
                "UNKNOWN PERSON DETECTOR",
                new int[0],
                new double[0],
                0,
                0,
                0,
                0,
                1.0,
                0,
                0,
                0,
                0,
                0.0,
                0,
                0
            );
        }
    }

    private static class DetectionSortResult {
        private final Rect[] faces;
        private final int[] rejectLevels;
        private final double[] levelWeights;

        DetectionSortResult(Rect[] faces, int[] rejectLevels, double[] levelWeights) {
            this.faces = faces == null ? new Rect[0] : faces;
            this.rejectLevels = rejectLevels == null ? new int[0] : rejectLevels;
            this.levelWeights = levelWeights == null ? new double[0] : levelWeights;
        }
    }

    private static class RotatedDetectionFrame implements AutoCloseable {
        private final Mat frame;
        private final boolean ownsFrame;
        private final double a;
        private final double b;
        private final double c;
        private final double d;
        private final double tx;
        private final double ty;

        RotatedDetectionFrame(
            Mat frame,
            boolean ownsFrame,
            double a,
            double b,
            double c,
            double d,
            double tx,
            double ty
        ) {
            this.frame = frame;
            this.ownsFrame = ownsFrame;
            this.a = a;
            this.b = b;
            this.c = c;
            this.d = d;
            this.tx = tx;
            this.ty = ty;
        }

        double[] mapScanPointToSource(double x, double y) {
            double shiftedX = x - tx;
            double shiftedY = y - ty;
            double det = a * d - b * c;
            if (Math.abs(det) < 0.000001) {
                return new double[] {shiftedX, shiftedY};
            }
            double sourceX = (d * shiftedX - b * shiftedY) / det;
            double sourceY = (-c * shiftedX + a * shiftedY) / det;
            return new double[] {sourceX, sourceY};
        }

        @Override
        public void close() {
            if (ownsFrame) {
                frame.release();
            }
        }
    }

    private static class FrameMats implements AutoCloseable {
        private final Mat frame = new Mat();
        private final Mat fingerprintFrame = new Mat();

        @Override
        public void close() {
            fingerprintFrame.release();
            frame.release();
        }
    }

    private static class FaceDetailBlock {
        private final BufferedImage snapshot;
        private final List<String> lines;
        private final boolean contactLocked;

        FaceDetailBlock(BufferedImage snapshot, List<String> lines, boolean contactLocked) {
            this.snapshot = snapshot;
            this.lines = lines == null ? new ArrayList<>() : lines;
            this.contactLocked = contactLocked;
        }
    }

    private static class ConnectionStatus {
        private final boolean cameraConnected;
        private final String cameraStatus;
        private final boolean esp32Connected;
        private final String esp32Status;
        private final boolean espControlEnabled;
        private final String command;
        private final String controlMode;
        private final int faceCount;
        private final boolean eyeContact;
        private final String contactState;
        private final boolean captureAvailable;
        private final String captureStatus;
        private final boolean flipEnabled;
        private final boolean debugVisible;
        private final List<FaceDetailBlock> faceDetailBlocks;
        private final String updatedAt;

        ConnectionStatus(
            boolean cameraConnected,
            String cameraStatus,
            boolean esp32Connected,
            String esp32Status,
            boolean espControlEnabled,
            String command,
            String controlMode,
            int faceCount,
            boolean eyeContact,
            String contactState,
            boolean captureAvailable,
            String captureStatus,
            boolean flipEnabled,
            boolean debugVisible,
            List<FaceDetailBlock> faceDetailBlocks,
            String updatedAt
        ) {
            this.cameraConnected = cameraConnected;
            this.cameraStatus = cameraStatus == null ? "Camera: unknown" : cameraStatus;
            this.esp32Connected = esp32Connected;
            this.esp32Status = esp32Status == null ? "ESP32: unknown" : esp32Status;
            this.espControlEnabled = espControlEnabled;
            this.command = command == null ? STOP_COMMAND : command;
            this.controlMode = controlMode == null ? "AUTO" : controlMode;
            this.faceCount = faceCount;
            this.eyeContact = eyeContact;
            this.contactState = contactState == null ? "NONE" : contactState;
            this.captureAvailable = captureAvailable;
            this.captureStatus = captureStatus == null ? "Capture: unknown" : captureStatus;
            this.flipEnabled = flipEnabled;
            this.debugVisible = debugVisible;
            this.faceDetailBlocks = faceDetailBlocks == null ? new ArrayList<>() : faceDetailBlocks;
            this.updatedAt = updatedAt == null ? "--:--:--" : updatedAt;
        }

        static ConnectionStatus empty() {
            return new ConnectionStatus(
                false,
                "Camera: scanning",
                false,
                "ESP32: scanning",
                false,
                STOP_COMMAND,
                "AUTO",
                0,
                false,
                "NONE",
                false,
                "Capture: disabled",
                false,
                false,
                new ArrayList<>(),
                "--:--:--"
            );
        }
    }

    private static class HelmetViewPanel extends JPanel {
        private BufferedImage image;
        private String statusText = "Connecting helmet camera...";
        private ConnectionStatus connectionStatus = ConnectionStatus.empty();

        HelmetViewPanel() {
            setBackground(Color.BLACK);
            setDoubleBuffered(true);
        }

        void setImage(BufferedImage nextImage) {
            image = nextImage;
            statusText = null;
            repaint();
        }

        void setStatusText(String text) {
            image = null;
            statusText = text;
            repaint();
        }

        void setConnectionStatus(ConnectionStatus nextStatus) {
            connectionStatus = nextStatus == null ? ConnectionStatus.empty() : nextStatus;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setColor(Color.BLACK);
                g2.fillRect(0, 0, getWidth(), getHeight());
                if (image != null) {
                    drawScaledImage(g2);
                } else {
                    drawStatusText(g2);
                }
                drawConnectionStatus(g2);
            } finally {
                g2.dispose();
            }
        }

        private void drawScaledImage(Graphics2D g2) {
            int panelWidth = Math.max(1, getWidth());
            int panelHeight = Math.max(1, getHeight());
            int imageWidth = image.getWidth();
            int imageHeight = image.getHeight();
            double scale = panelHeight / (double) imageHeight;
            int drawWidth = (int) Math.round(imageWidth * scale);
            int drawHeight = panelHeight;

            if (drawWidth > panelWidth) {
                scale = panelWidth / (double) imageWidth;
                drawWidth = panelWidth;
                drawHeight = (int) Math.round(imageHeight * scale);
            }

            int x = (panelWidth - drawWidth) / 2;
            int y = (panelHeight - drawHeight) / 2;
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
            g2.drawImage(image, x, y, drawWidth, drawHeight, null);
        }

        private void drawStatusText(Graphics2D g2) {
            String text = statusText == null ? "" : statusText;
            String[] lines = text.split("\\n", -1);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setFont(new Font(UI_STATUS_FONT, Font.PLAIN, 22));
            g2.setColor(new Color(220, 220, 220));
            int lineHeight = g2.getFontMetrics().getHeight();
            int totalHeight = lineHeight * lines.length;
            int y = Math.max(lineHeight, (getHeight() - totalHeight) / 2 + g2.getFontMetrics().getAscent());
            for (String line : lines) {
                int lineWidth = g2.getFontMetrics().stringWidth(line);
                int x = Math.max(12, (getWidth() - lineWidth) / 2);
                g2.drawString(line, x, y);
                y += lineHeight;
            }
        }

        private void drawConnectionStatus(Graphics2D g2) {
            if (getWidth() < 160 || getHeight() < 120) {
                return;
            }

            ConnectionStatus status = connectionStatus;
            int margin = 24;
            int panelWidth = Math.max(1, getWidth());
            int availableWidth = Math.max(1, panelWidth - margin * 2);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 24));

            int columnGap = 28;
            int columnWidth = Math.max(1, (availableWidth - columnGap * 2) / 3);
            int y = margin + 28;
            Color green = new Color(70, 235, 110);
            Color yellow = new Color(255, 210, 70);
            Color red = new Color(255, 82, 82);
            Color white = new Color(235, 235, 235);
            Color muted = new Color(175, 185, 190);
            Color cameraColor = status.cameraConnected ? green : yellow;
            Color esp32Color = status.esp32Connected ? green : yellow;
            Color espControlColor = status.espControlEnabled ? green : yellow;
            Color contactColor = status.faceCount > 0 ? green : muted;

            drawStatusColumn(
                g2,
                margin,
                y,
                columnWidth,
                "VISION",
                muted,
                new String[] {
                    "MODE " + status.controlMode,
                    "PEOPLE " + status.faceCount,
                    "PERSON " + status.contactState,
                    "COMMAND " + status.command,
                    "UPDATED " + status.updatedAt
                },
                new Color[] {
                    status.controlMode.startsWith("MANUAL") ? yellow : white,
                    white,
                    contactColor,
                    status.command.equals(STOP_COMMAND) ? red : green,
                    muted
                }
            );
            drawStatusColumn(
                g2,
                margin + columnWidth + columnGap,
                y,
                columnWidth,
                "CAMERA",
                muted,
                new String[] {
                    status.cameraConnected ? "ONLINE" : "OFFLINE",
                    status.cameraStatus,
                    "FLIP[F] VERTICAL " + (status.flipEnabled ? "ON" : "OFF")
                },
                new Color[] {
                    cameraColor,
                    cameraColor,
                    white
                }
            );
            drawStatusColumn(
                g2,
                margin + (columnWidth + columnGap) * 2,
                y,
                columnWidth,
                "CONTROL",
                muted,
                new String[] {
                    "ESP32 " + (status.esp32Connected ? "ONLINE" : "OFFLINE"),
                    "ESP CTRL[SPACE] " + (status.espControlEnabled ? "ENABLED" : "DISABLED"),
                    status.esp32Status,
                    status.captureStatus,
                    "DEBUG[D] " + (status.debugVisible ? "ON" : "OFF")
                },
                new Color[] {
                    esp32Color,
                    espControlColor,
                    esp32Color,
                    status.captureAvailable ? green : yellow,
                    white
                }
            );

            int detailTop = y + 198;
            int detailHeight = Math.max(0, getHeight() - detailTop - 24);
            drawFaceDetailGrid(g2, status.faceDetailBlocks, margin, detailTop, availableWidth, detailHeight, columnGap);
        }

        private void drawStatusColumn(
            Graphics2D g2,
            int x,
            int y,
            int width,
            String title,
            Color titleColor,
            String[] lines,
            Color[] colors
        ) {
            drawShadowedString(g2, title, x, y, titleColor, width);
            int lineY = y + 30;
            for (int i = 0; i < lines.length; i++) {
                Color color = i < colors.length && colors[i] != null ? colors[i] : new Color(235, 235, 235);
                drawShadowedString(g2, lines[i], x, lineY, color, width);
                lineY += 28;
            }
        }

        private void drawShadowedString(Graphics2D g2, String text, int x, int y, Color color, int maxWidth) {
            String fitted = fitSwingText(g2, text, maxWidth);
            g2.setColor(new Color(0, 0, 0, 220));
            g2.drawString(fitted, x + 2, y + 2);
            g2.setColor(color);
            g2.drawString(fitted, x, y);
        }

        private void drawFaceDetailGrid(
            Graphics2D g2,
            List<FaceDetailBlock> detailBlocks,
            int x,
            int y,
            int width,
            int height,
            int columnGap
        ) {
            if (detailBlocks == null || detailBlocks.isEmpty() || height < 48) {
                return;
            }

            int columns = 3;
            int rowGap = 18;
            int rows = Math.max(1, (detailBlocks.size() + columns - 1) / columns);
            int blockWidth = Math.max(1, (width - columnGap * (columns - 1)) / columns);
            int blockHeight = Math.max(1, (height - rowGap * (rows - 1)) / rows);
            int detailFontSize = height < 260 ? 14 : 18;
            g2.setFont(new Font(Font.MONOSPACED, Font.PLAIN, detailFontSize));

            for (int i = 0; i < detailBlocks.size(); i++) {
                int col = i % columns;
                int row = i / columns;
                int blockX = x + col * (blockWidth + columnGap);
                int blockY = y + row * (blockHeight + rowGap);
                drawFaceDetailBlock(g2, detailBlocks.get(i), blockX, blockY, blockWidth, blockHeight);
            }
        }

        private void drawFaceDetailBlock(
            Graphics2D g2,
            FaceDetailBlock block,
            int x,
            int y,
            int width,
            int height
        ) {
            if (block == null) {
                return;
            }

            List<String> lines = block.lines;
            if (lines == null || lines.isEmpty()) {
                return;
            }

            int lineStep = g2.getFontMetrics().getHeight() + 2;
            int textY = y;
            if (block.snapshot != null && height >= 120) {
                int minTextHeight = lineStep * Math.min(4, lines.size());
                int maxImageHeight = Math.max(48, Math.min(150, height - minTextHeight - 12));
                int imageWidth = block.snapshot.getWidth();
                int imageHeight = block.snapshot.getHeight();
                double imageScale = Math.min(width / (double) imageWidth, maxImageHeight / (double) imageHeight);
                int drawWidth = Math.max(1, (int) Math.round(imageWidth * imageScale));
                int drawHeight = Math.max(1, (int) Math.round(imageHeight * imageScale));
                int imageX = x;
                int imageY = y;

                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2.drawImage(block.snapshot, imageX, imageY, drawWidth, drawHeight, null);
                textY = imageY + drawHeight + 12;
            }

            int textHeight = Math.max(1, height - (textY - y));
            int maxLines = Math.max(1, textHeight / Math.max(1, lineStep));
            int baseline = textY + g2.getFontMetrics().getAscent();
            for (int i = 0; i < lines.size() && i < maxLines; i++) {
                String line = lines.get(i);
                drawShadowedString(g2, line, x, baseline + i * lineStep, detailLineColor(line, block.contactLocked), width);
            }
        }

        private Color detailLineColor(String line, boolean contactLocked) {
            if (line.startsWith("PERSON ")) {
                return new Color(70, 235, 110);
            }
            if (line.startsWith("RESPONSE")) {
                return new Color(120, 225, 255);
            }
            if (line.startsWith("DETECTOR") || line.startsWith("PIPELINE")) {
                return new Color(175, 185, 190);
            }
            return new Color(235, 235, 235);
        }

        private String fitSwingText(Graphics2D g2, String text, int maxWidth) {
            if (g2.getFontMetrics().stringWidth(text) <= maxWidth) {
                return text;
            }

            String suffix = "...";
            int end = text.length();
            while (end > 0 && g2.getFontMetrics().stringWidth(text.substring(0, end) + suffix) > maxWidth) {
                end--;
            }
            return text.substring(0, Math.max(0, end)) + suffix;
        }
    }

    private BufferedImage matToBufferedImage(Mat m) {
        if (m.empty()) {
            return null;
        }
        int type = BufferedImage.TYPE_BYTE_GRAY;
        if (m.channels() > 1) {
            type = BufferedImage.TYPE_3BYTE_BGR;
        }
        int bufferSize = m.channels() * m.cols() * m.rows();
        byte[] b = new byte[bufferSize];
        m.get(0, 0, b);
        BufferedImage image = new BufferedImage(m.cols(), m.rows(), type);
        System.arraycopy(b, 0, ((DataBufferByte) image.getRaster().getDataBuffer()).getData(), 0, b.length);
        return image;
    }

    private static CascadeClassifier loadCascadeClassifier(String filename) {
        try (InputStream in = HoistSystem.class.getResourceAsStream("/" + filename)) {
            if (in != null) {
                Path temp = Files.createTempFile("opencv-cascade-", "-" + filename);
                temp.toFile().deleteOnExit();
                Files.copy(in, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return new CascadeClassifier(temp.toAbsolutePath().toString());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return new CascadeClassifier("程序/" + filename);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int fourcc(String value) {
        return VideoWriter.fourcc(value.charAt(0), value.charAt(1), value.charAt(2), value.charAt(3));
    }

    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        String escaped = value.replace("\"", "\"\"");
        return "\"" + escaped + "\"";
    }

    private static String config(String key, String fallback) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private static void validateCommandConfig() {
        if (CONTRACT_COMMAND.isBlank() || DESCEND_COMMAND.isBlank() || STOP_COMMAND.isBlank()) {
            throw new IllegalStateException("Hoist commands must not be blank.");
        }
        if (CONTRACT_COMMAND.equals(DESCEND_COMMAND)
            || CONTRACT_COMMAND.equals(STOP_COMMAND)
            || DESCEND_COMMAND.equals(STOP_COMMAND)) {
            throw new IllegalStateException(
                "HOIST_CONTRACT_COMMAND, HOIST_DESCEND_COMMAND and HOIST_STOP_COMMAND must be different."
            );
        }
    }

    private static String defaultCameraPageUrl(String cameraUrl) {
        if (cameraUrl == null || cameraUrl.isBlank() || cameraUrl.trim().matches("\\d+")) {
            return "";
        }

        try {
            URI uri = new URI(cameraUrl.trim());
            String scheme = uri.getScheme();
            if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                String query = uri.getQuery() == null ? "" : uri.getQuery().toLowerCase(Locale.ROOT);
                String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
                boolean streamEndpoint = query.contains("action=stream")
                    || query.contains("action=snapshot")
                    || "/stream".equals(path)
                    || "/snapshot".equals(path);
                String host = uri.getHost();
                String port = uri.getPort() >= 0 ? ":" + uri.getPort() : "";
                if (streamEndpoint && host != null && !host.isBlank()) {
                    return scheme + "://" + host + port + "/";
                }
                return cameraUrl.trim();
            }
            String host = uri.getHost();
            if (host != null && !host.isBlank()) {
                return "http://" + host + ":8080/";
            }
        } catch (URISyntaxException ignored) {
        }

        return "";
    }

    private static String withHttpScheme(String value) {
        String trimmed = value.trim();
        if (trimmed.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) {
            return trimmed;
        }
        return "http://" + trimmed;
    }

    private static int configInt(String key, int fallback) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double configDouble(String key, double fallback) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean configBoolean(String key, boolean fallback) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("1") || normalized.equals("true") || normalized.equals("yes") || normalized.equals("on")) {
            return true;
        }
        if (normalized.equals("0") || normalized.equals("false") || normalized.equals("no") || normalized.equals("off")) {
            return false;
        }
        return fallback;
    }

    private static String safeLower(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private static String describePort(SerialPort port) {
        return port.getSystemPortName() + " / " + port.getDescriptivePortName();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(HoistSystem::new);
    }
}
