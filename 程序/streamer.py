import cv2
from flask import Flask, Response

# ================= 配置 =================
# 如果是 MacBook Air，通常是 0
# 如果是 树莓派，通常是 0 (但如果是老式摄像头模块可能需要改用 libcamera 命令，先试 0)
CAMERA_INDEX = 0 
PORT = 8000
# =======================================

app = Flask(__name__)

def generate_frames():
    camera = cv2.VideoCapture(CAMERA_INDEX)
    # 设置分辨率为 640x480 以保证流畅度 (树莓派跑高清会卡)
    camera.set(cv2.CAP_PROP_FRAME_WIDTH, 640)
    camera.set(cv2.CAP_PROP_FRAME_HEIGHT, 480)
    
    if not camera.isOpened():
        print("错误：无法打开摄像头！")
        return

    print(f"摄像头已启动，正在推流到端口 {PORT}...")
    
    while True:
        success, frame = camera.read()
        if not success:
            break
        else:
            # 压缩成 JPEG 格式传输
            ret, buffer = cv2.imencode('.jpg', frame)
            frame = buffer.tobytes()
            # HTTP Multipart 格式
            yield (b'--frame\r\n'
                   b'Content-Type: image/jpeg\r\n\r\n' + frame + b'\r\n')

@app.route('/video')
def video_feed():
    return Response(generate_frames(), mimetype='multipart/x-mixed-replace; boundary=frame')

if __name__ == '__main__':
    # host='0.0.0.0' 表示允许局域网内任何设备访问
    app.run(host='0.0.0.0', port=PORT, debug=False, threaded=True)
