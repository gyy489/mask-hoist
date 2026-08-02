import java.util.ArrayList;
import java.util.List;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.imgproc.Moments;

public class AirViewProcessor {

    // 颜色常量
    private static final Scalar COLOR_LIMIT = new Scalar(0, 0, 255);    // 红线
    private static final Scalar COLOR_STRIP = new Scalar(0, 255, 255);  // 黄色 (物体轮廓)
    private static final Scalar COLOR_INFO  = new Scalar(0, 255, 0);    // 绿色 (文字信息)

    // 阈值 (可根据反光条亮度微调)
    private static final int BRIGHTNESS_THRESHOLD = 220;

    /**
     * 处理上帝视角画面
     * @param peopleGazing 正在看的人数
     * @param peopleTotal 总人数
     */
    public double process(Mat frame, double limitTopPct, double limitBotPct, boolean flip, double currentHeightPct, int peopleGazing, int peopleTotal) {
        
        // 1. 翻转处理
        if (flip) {
            Core.flip(frame, frame, -1);
        }

        Mat gray = new Mat();
        Mat thresh = new Mat();
        Mat hierarchy = new Mat();
        List<MatOfPoint> contours = new ArrayList<>();
        double detectedHeight = -1.0; 

        try {
            Imgproc.cvtColor(frame, gray, Imgproc.COLOR_BGR2GRAY);
            Imgproc.threshold(gray, thresh, BRIGHTNESS_THRESHOLD, 255, Imgproc.THRESH_BINARY);

            Imgproc.findContours(thresh, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

            // --- 寻找最大轮廓 ---
            MatOfPoint maxContour = null;
            double maxArea = 0;
            Point center = null;

            for (MatOfPoint c : contours) {
                double area = Imgproc.contourArea(c);
                // 过滤噪点，面积大于50才算
                if (area > 50 && area > maxArea) {
                    maxArea = area;
                    maxContour = c;
                    
                    Moments M = Imgproc.moments(c);
                    if (M.m00 != 0) {
                        center = new Point(M.m10 / M.m00, M.m01 / M.m00);
                    }
                }
            }

            int rowH = frame.rows();
            int colW = frame.cols();
            int yTopPx = (int)(rowH * limitTopPct);
            int yBotPx = (int)(rowH * limitBotPct);

            if (center != null && maxContour != null) {
                // --- 绘制物体真实轮廓 (黄色描边) ---
                List<MatOfPoint> listToDraw = new ArrayList<>();
                listToDraw.add(maxContour);
                // -1 表示画所有，3 是线宽
                Imgproc.drawContours(frame, listToDraw, -1, COLOR_STRIP, 3);
                
                // [修复点] 这里改成了 drawMarker
                Imgproc.drawMarker(frame, center, COLOR_LIMIT, Imgproc.MARKER_CROSS, 20, 2);

                // 计算高度
                double range = Math.max(1.0, yBotPx - yTopPx);
                double posFromBot = yBotPx - center.y;
                double instantHeight = (posFromBot / range) * 100.0;
                detectedHeight = Math.max(0, Math.min(100, instantHeight));
                currentHeightPct = (currentHeightPct * 0.8) + (detectedHeight * 0.2);
            }

            // 绘制 UI
            drawUI(frame, yTopPx, yBotPx, colW, rowH, currentHeightPct, center != null, flip, peopleGazing, peopleTotal);

        } finally {
            for (MatOfPoint contour : contours) {
                contour.release();
            }
            hierarchy.release();
            gray.release();
            thresh.release();
        }

        return currentHeightPct;
    }

    private void drawUI(Mat frame, int yTop, int yBot, int w, int h, double heightPct, boolean isDetected, boolean isFlipped, int gaze, int total) {
        // 画红线
        Imgproc.line(frame, new Point(0, yTop), new Point(w, yTop), COLOR_LIMIT, 2);
        Imgproc.line(frame, new Point(0, yBot), new Point(w, yBot), COLOR_LIMIT, 2);
        Imgproc.putText(frame, "TOP", new Point(10, yTop + 25), Imgproc.FONT_HERSHEY_PLAIN, 1.5, COLOR_LIMIT, 2);
        Imgproc.putText(frame, "BOT", new Point(10, yBot - 10), Imgproc.FONT_HERSHEY_PLAIN, 1.5, COLOR_LIMIT, 2);

        // --- 左上角显示人数数据 (绿色文字) ---
        String stats = String.format("People: %d / %d", gaze, total);
        Imgproc.putText(frame, stats, new Point(20, 50), Imgproc.FONT_HERSHEY_PLAIN, 2.5, COLOR_INFO, 3);

        // 中心大字 (当前高度)
        String centerText = isDetected ? String.format("%.0f%%", heightPct) : "NO STRIP";
        Scalar centerColor = isDetected ? COLOR_STRIP : COLOR_LIMIT;
        
        int fontFace = Imgproc.FONT_HERSHEY_SIMPLEX;
        double fontScale = 4.0;
        int thickness = 8;
        Size textSize = Imgproc.getTextSize(centerText, fontFace, fontScale, thickness, new int[1]);
        Point textOrg = new Point((w - textSize.width) / 2, (h + textSize.height) / 2);
        Imgproc.putText(frame, centerText, textOrg, fontFace, fontScale, centerColor, thickness);

        if (isFlipped) {
            Imgproc.putText(frame, "[F]lip: ON", new Point(w - 150, h - 20), Imgproc.FONT_HERSHEY_PLAIN, 1.2, new Scalar(255, 255, 255), 1);
        }
    }
}
