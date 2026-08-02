# 面具-悬挂

这是一个行为表演/交互装置项目。当前版本已经从旧方案彻底改为：

- 只有头盔中的摄像头。
- 表演者佩戴头盔，摄像头从表演者视角向外看。
- 程序启动后默认不向 ESP32 输出控制命令；按 `空格` 后才启用 ESP32 控制。
- 摄像头画面中的人检测、状态显示、算法信息一直运行，不受 ESP32 控制开关影响。
- ESP32 控制启用时，检测到人会输出收缩命令；没有检测到人会输出停止命令。
- ESP32 控制启用时，手动按住 `↑` / `↓` 会输出上升 / 下降命令，手动权限高于自动识别。
- 头盔摄像头拍到的内容可以按需保存到项目内，默认关闭以降低卡顿风险。
- 松开手动方向键后立即发送 `STOP`，等待 3 秒后才恢复自动识别控制。
- 不做电子高度闭环，不做电子限位停止；最终停止依赖机器自身的物理停止/限位装置。

当前程序会输出三类命令：

```text
UP
DOWN
STOP
```

当前现场开关方向已在软件里反过来：收缩/拉动默认发送 `DOWN`，手动下降/放松默认发送 `UP`。若后续 ESP32 或继电器接线再调整，优先修改 `HOIST_CONTRACT_COMMAND` / `HOIST_DESCEND_COMMAND` 环境变量或 ESP32 端命令映射。

## 当前行为逻辑

新版主程序是 [程序/HoistSystem.java](程序/HoistSystem.java)。

运行后程序主要会做四件事：

1. 持续连接头盔摄像头视频流。
2. 持续扫描并连接 ESP32 串口。
3. 在视频画面中检测人。
4. 根据是否检测到人计算当前命令；只有 ESP32 控制启用时，才把当前命令写入 ESP32。

如果设置 `CAPTURE_ENABLED=true`，程序还会把头盔摄像头画面保存到 `captures/helmet/<时间戳>/`。

核心判断：

```text
程序启动              -> ESP32 控制默认 DISABLED，只显示检测和当前命令
按空格                -> 切换 ESP32 控制 ENABLED / DISABLED
头盔画面中检测到人 -> person_active = true  -> 当前命令 DOWN（当前现场接线下的收缩命令）
头盔画面中没有人   -> person_active = false -> 当前命令 STOP
按住 ↑ / ↓          -> 当前命令切到收缩 / 下降，覆盖自动识别
松开手动方向键          -> 当前命令 STOP，等待 3 秒后恢复自动识别
摄像头断流/无新画面   -> person_active = false -> 当前命令 STOP
ESP32 断开            -> 后台持续扫描；只有 ESP32 控制启用时才继续输出当前命令
```

当前“看到人”优先使用 OpenCV HOG 行人检测；如果 HOG 没有完整人体结果，但 Haar 正脸检测到了人脸，程序会把脸框扩展为一个人框作为兜底。

## 目录结构

```text
.
├── README.md
├── build.gradle
├── settings.gradle
├── .vscode/
│   └── settings.json
├── captures/
│   └── helmet/
│       └── <session_time>/
│           ├── helmet.mp4
│           ├── metadata.csv
│           └── frames/
│               ├── frame_000001.jpg
│               └── ...
├── libs/
│   └── libopencv_java.dylib -> /opt/homebrew/.../libopencv_java4130.dylib
├── 程序/
│   ├── HoistSystem.java                  # 当前主程序：头盔摄像头 + 看到人触发收缩
│   ├── AirViewProcessor.java             # 旧方案遗留：俯视反光条高度检测，当前不再调用
│   ├── streamer.py                       # 摄像头 MJPEG 推流脚本，可用于头盔摄像头推流测试
│   ├── 记录.txt                          # 简短运行备忘
│   ├── haarcascade_frontalface_alt.xml   # 当前使用：正脸检测模型
│   ├── haarcascade_profileface.xml       # 旧方案遗留：侧脸检测模型，当前不再调用
│   └── libs/
│       └── jSerialComm.jar               # 手动 javac 运行时的串口库
├── 物质/
│   ├── IMG_8833.JPG
│   ├── IMG_8835.JPG
│   ├── thczv-female-v2.stl.stl
│   ├── 效果图/
│   │   ├── 截屏2026-02-16 02.11.34.png
│   │   ├── 截屏2026-02-16 02.16.50.png
│   │   ├── 截屏2026-02-16 15.16.41.png
│   │   ├── 截屏2026-02-16 17.23.32.png
│   │   └── 截屏2026-02-17 13.23.36.png
│   ├── 我/
│   │   ├── -1-.blend
│   │   ├── -1-.blend1
│   │   ├── 我的头-03-1202024.blend
│   │   └── textured_mesh_obj/
│   │       ├── mesh.obj
│   │       ├── mesh.mtl
│   │       └── textures/
│   └── 把自己吊起来/
│       └── 打印模型/
│           ├── 脸.obj
│           ├── 头发-2.obj
│           ├── 头发-2.mtl
│           ├── 头发-3.mtl
│           └── 头发.mtl
├── build/          # Gradle 生成物
├── out/            # 手动 javac 生成物
├── .gradle/        # Gradle 缓存
└── .gradle-home/   # 本地 Gradle home/cache
```

说明：

- `.DS_Store` 和 `._*` 是 macOS 产生的元数据/AppleDouble 文件，不是项目核心内容。
- `build/`、`out/`、`.gradle/`、`.gradle-home/` 是生成物或缓存。
- `程序/*.class` 是编译产物，真正源码是 `程序/*.java`。
- `captures/helmet/` 是运行时自动生成的头盔摄像头记录。

## 核心文件

| 文件 | 当前作用 |
| --- | --- |
| `程序/HoistSystem.java` | 当前主程序。单摄像头检测人，自动重连视频流，自动重连 ESP32，发送收缩命令 / `STOP`。 |
| `程序/AirViewProcessor.java` | 旧方案遗留。原来用于俯视角检测反光条高度，现在主程序不再调用。 |
| `程序/streamer.py` | 用 Flask + OpenCV 把本机/树莓派摄像头推成 MJPEG HTTP 流。 |
| `程序/haarcascade_frontalface_alt.xml` | 当前使用的 OpenCV 正脸检测模型。 |
| `程序/haarcascade_profileface.xml` | 旧方案遗留的侧脸检测模型。 |
| `build.gradle` | Gradle Java 配置。源码目录被设置为 `程序/`。 |
| `.vscode/settings.json` | VS Code Java 依赖提示。 |

## HoistSystem.java 结构

主程序内部可以按这些区域理解：

| 区域 | 作用 |
| --- | --- |
| 配置常量 | 摄像头地址、串口匹配、命令字符串、重连间隔、检测频率。 |
| `mainLoop()` | 每 33ms 处理最新帧；无画面时强制 `STOP`。 |
| `processHelmetView()` | 翻转画面、检测人、更新收缩 / `STOP` 命令、绘制调试信息。 |
| `detectPeople()` | OpenCV HOG 行人检测；HOG 失败时使用 Haar 正脸检测扩展为人框兜底。 |
| `startHelmetCameraLoop()` | 后台连接视频流；断流、无帧、错误后自动重试。 |
| `startSerialMonitorLoop()` | 后台扫描 ESP32；断开后自动重连。 |
| `heartbeatLoop()` | ESP32 控制启用时，每 100ms 把当前命令重复发给 ESP32；禁用时不写串口。 |
| `initCaptureSession()` / `saveCaptureFrame()` | 开启保存时创建本次拍摄记录目录，保存 `helmet.mp4`、JPG 帧和 CSV 元数据。 |
| `handleKeyPress()` | 处理 `ESC` 安全退出、`F` 翻转、`D` 调试层开关，以及 `↑` / `↓` 手动控制互锁。 |

## 可配置项

这些配置可以直接改 `HoistSystem.java`，也可以在运行前用环境变量覆盖。

| 环境变量 | 默认值 | 作用 |
| --- | --- | --- |
| `HELMET_CAMERA_URL` | `http://192.168.1.98:8080/stream` | 头盔摄像头视频流。也可设为 `0` 使用本机摄像头。 |
| `FLIP_HELMET_CAMERA` | `true` | 是否默认把头盔画面上下翻转。运行中可按 `F` 切换。 |
| `AUTO_OPEN_CAMERA_PAGE` | `false` | 摄像头连接成功后是否自动打开浏览器网页。默认关闭，避免遮挡主视频窗口。 |
| `CAMERA_PAGE_URL` | 从摄像头地址推导 | 要自动打开的摄像头网页地址。默认推导为 `http://<host>:8080/`；不对时手动设置。 |
| `OPEN_CAMERA_PAGE_ON_RECONNECT` | `false` | 是否每次断线重连成功都重新打开网页。默认每次程序运行只打开一次。 |
| `CONTROLLER_PORT_MATCH` | `usb,arduino,esp32,cp210,ch340,wchusbserial,usbserial` | ESP32 串口自动匹配关键词。匹配系统端口名或描述。旧的 `ARDUINO_PORT_MATCH` 仍兼容。 |
| `CONTROLLER_BAUD_RATE` | `115200` | ESP32 串口波特率。旧的 `ARDUINO_BAUD_RATE` 仍兼容。 |
| `CONTROLLER_BOOT_WAIT_MS` | `2000` | 打开串口后等待 ESP32 重启完成的时间。旧的 `ARDUINO_BOOT_WAIT_MS` 仍兼容。 |
| `ESP32_ENABLED_ON_START` | `false` | 程序启动时是否立即启用 ESP32 控制。默认关闭，需按 `空格` 启用。 |
| `HOIST_CONTRACT_COMMAND` | `DOWN` | 检测到人时发送的收缩命令；当前默认值用于现场开关接反后的软件反向。 |
| `HOIST_DESCEND_COMMAND` | `UP` | 手动按住 `↓` 时发送的下降/放松命令；当前默认值用于现场开关接反后的软件反向。 |
| `HOIST_STOP_COMMAND` | `STOP` | 无人/断流时发送的停止命令。 |
| `PROCESS_INTERVAL_MS` | `33` | 主处理循环间隔，约 30 FPS。 |
| `COMMAND_INTERVAL_MS` | `100` | 向 ESP32 重复发送当前命令的间隔。 |
| `MANUAL_AUTO_RESUME_DELAY_MS` | `3000` | 松开手动方向键后，自动识别控制恢复前的缓冲时间。 |
| `STREAM_RECONNECT_MS` | `1000` | 视频流断开后的重连间隔。 |
| `STREAM_STALE_MS` | `3000` | 超过多久没有新画面就认为断流。 |
| `FRAME_CONTENT_CHECK_MS` | `250` | 检查视频内容是否持续变化的间隔。连续重复帧超过 `STREAM_STALE_MS` 时强制 `STOP`。 |
| `SERIAL_SCAN_INTERVAL_MS` | `1000` | ESP32 串口扫描间隔。 |
| `OPENCV_THREADS` | `2` | 限制 OpenCV 内部线程数，避免检测时把本机 CPU 吃满导致 UI 卡顿。 |
| `DETECT_EVERY_N_FRAMES` | `1` | 每隔几帧做人检测。`1` 表示每个处理帧都检测，反应最快。 |
| `CONTACT_START_FRAMES` | `2` | 启动防抖：连续多少次检测到人后才允许发送收缩命令。 |
| `CONTACT_START_HOLD_MS` | `180` | 启动防抖：人需要持续多少毫秒后才发送收缩命令。 |
| `CONTACT_LOST_FRAMES` | `3` | 连续几次检测不到人后才停止，避免识别闪烁。 |
| `DETECT_MAX_WIDTH` | `640` | 人检测使用的最大宽度。`1280x960` 会缩到 `640x480` 检测，再把人框映射回原画面。 |
| `PERSON_HOG_HIT_THRESHOLD` | `0.35` | HOG 行人检测阈值。越高越严格，误检少但可能漏检。 |
| `PERSON_HOG_SCALE` | `1.05` | HOG 多尺度扫描比例。越接近 1 越细，越慢。 |
| `PERSON_HOG_FINAL_THRESHOLD` | `2.0` | HOG 分组阈值。越高越稳定但可能漏检。 |
| `PERSON_MIN_HOG_WEIGHT` | `0.35` | HOG 候选框最低权重。背景误判时优先调高这个值。 |
| `PERSON_FACE_FALLBACK_MIN_WEIGHT` | `0.8` | Haar 人脸兜底的最低权重。误把背景纹理当脸时调高。 |
| `PERSON_MIN_HEIGHT_RATIO` | `0.12` | 人框高度至少占检测画面高度的比例。 |
| `PERSON_MIN_AREA_RATIO` | `0.008` | 人框面积至少占检测画面面积的比例。 |
| `PERSON_MIN_ASPECT_RATIO` | `0.25` | 人框最小宽高比，过滤过窄竖线。 |
| `PERSON_MAX_ASPECT_RATIO` | `0.85` | 人框最大宽高比，过滤过宽背景块。 |
| `PERSON_SCAN_ALL_ROTATIONS` | `true` | 是否按多个旋转方向检测人。用于摄像头翻滚后仍能触发。 |
| `PERSON_ROTATION_STEP_DEGREES` | `45` | 多方向检测角度步长。默认扫描 0/45/90/135/180/225/270/315；设为 `30` 更密但更慢。 |
| `UI_MAX_FPS` | `20` | `Helmet View` 最大刷新帧率。显示会丢弃过期帧，避免 Swing 排队造成卡顿和延迟。 |
| `FACE_SCALE_FACTOR` | `1.1` | Haar Cascade 多尺度扫描比例步长。越接近 1 越细，越慢。 |
| `FACE_MIN_NEIGHBORS` | `6` | Haar 检测框合并阈值。越大越严格，误检少但可能漏检。 |
| `DEBUG_UI_ENABLED` | `true` | 是否启动时在 `Helmet View` 同一画面上显示算法文字叠加层。运行中可按 `D` 开关。 |
| `CAPTURE_ENABLED` | `false` | 是否保存头盔摄像头画面。默认关闭，减少磁盘写入造成的卡顿。 |
| `CAPTURE_DIR` | `captures/helmet` | 拍摄记录保存根目录。 |
| `CAPTURE_INTERVAL_MS` | `200` | 每隔多久保存一帧。`200` 约等于 5 fps；设为 `0` 表示尽量每帧保存。 |
| `CAPTURE_JPEG_QUALITY` | `92` | JPEG 保存质量，范围 `1-100`。 |
| `CAPTURE_MP4_ENABLED` | `true` | 是否同时保存 MP4 视频。 |
| `CAPTURE_MP4_FILENAME` | `helmet.mp4` | 每次记录目录里的 MP4 文件名。 |
| `CAPTURE_MP4_FPS` | `30` | 写入 MP4 的播放帧率。 |
| `CAPTURE_MP4_INTERVAL_MS` | `0` | MP4 写入间隔。`0` 表示每个新鲜处理帧都写入；设为 `100` 约等于 10 fps。 |
| `UI_STATUS_FONT` | `Helvetica Neue` | `Helmet View` 等待/连接状态文字字体。中央 `STOP` / `CONTRACT` 大字使用 OpenCV 内置字体。 |

示例：

```bash
HELMET_CAMERA_URL="http://192.168.1.24:8000/video" \
CAMERA_PAGE_URL="http://192.168.1.24:8000/video" \
CONTROLLER_PORT_MATCH="usbmodem,usbserial,cp210,esp32" \
HOIST_CONTRACT_COMMAND="DOWN" \
HOIST_DESCEND_COMMAND="UP" \
gradle run
```

如果要用本机摄像头测试：

```bash
HELMET_CAMERA_URL="0" gradle run
```

如果要显式指定面具树莓派的网页预览地址：

```bash
HELMET_CAMERA_URL="http://192.168.1.98:8080/stream" \
CAMERA_PAGE_URL="http://192.168.1.98:8080/" \
gradle run
```

如果需要自动打开摄像头网页：

```bash
AUTO_OPEN_CAMERA_PAGE="true" gradle run
```

如果需要保存画面：

```bash
CAPTURE_ENABLED="true" gradle run
```

## 操作按键

| 按键 | 功能 |
| --- | --- |
| `F` | 开关头盔摄像头画面上下翻转和检测方向 |
| `D` | 显示/隐藏 `Helmet View` 上的算法文字叠加层 |
| `空格` | 启用/禁用 ESP32 控制。禁用时只检测和显示，不向 ESP32 输出上下移动命令 |
| `↑` | 手动上升/收缩。当前命令为 `HOIST_CONTRACT_COMMAND`，默认 `DOWN`，优先级高于自动识别 |
| `↓` | 手动下降/放松。当前命令为 `HOIST_DESCEND_COMMAND`，默认 `UP`，优先级高于自动识别 |
| `ESC` | 发送一次 `STOP` 并退出程序 |

旧按键已经废弃：

- 不再有 `E` 自动模式。
- 不再有 `M` 手动模式。
- 不再有 `Q/A/W/S` 上下限校准。

现在的手动控制直接使用键盘方向键。松开 `↑` / `↓` 后会立即发送 `STOP`，并等待 `MANUAL_AUTO_RESUME_DELAY_MS` 默认 3 秒后才恢复自动识别控制。

## 运行方式

### Gradle 运行

在项目根目录：

```bash
gradle run
```

`build.gradle` 默认使用：

```text
OpenCV jar: /opt/homebrew/opt/opencv-java/share/java/opencv4/opencv-4130.jar
OpenCV lib: /opt/homebrew/opt/opencv-java/share/java/opencv4
Java toolchain: 23
```

如果 OpenCV 路径不同：

```bash
OPENCV_JAR="/path/to/opencv.jar" \
OPENCV_LIB_PATH="/path/to/opencv/lib/dir" \
gradle run
```

### 摄像头推流脚本

如果头盔摄像头由电脑或树莓派采集，可以用 `程序/streamer.py` 推 MJPEG 流：

```bash
cd "程序"
python3 streamer.py
```

默认输出：

```text
http://<设备IP>:8000/video
```

然后运行主程序时设置：

```bash
HELMET_CAMERA_URL="http://<设备IP>:8000/video" gradle run
```

Python 依赖：

```bash
pip install flask opencv-python
```

### 手动 javac 运行

仍可手动编译，但更推荐 Gradle：

```bash
cd "程序"
javac -cp "libs/*" *.java
java -cp "libs/*:." HoistSystem
```

手动方式需要自行保证 OpenCV Java jar 和 native library 能被 Java 找到。

## 拍摄内容保存

每次启动主程序，会自动创建一个新目录：

```text
captures/helmet/YYYYMMDD_HHMMSS_SSS/
├── helmet.mp4
├── metadata.csv
└── frames/
    ├── frame_000001.jpg
    ├── frame_000002.jpg
    └── ...
```

`helmet.mp4` 保存的是头盔摄像头画面经过当前翻转设置后的原始画面，不包含窗口里叠加的人框、`CONTRACT` / `STOP` 大字和状态文字。

默认 MP4 会写入每个新鲜处理帧，播放帧率为 `CAPTURE_MP4_FPS=30`。同时，程序仍会按 `CAPTURE_INTERVAL_MS=200` 保存 JPG 图片序列，方便之后逐帧检查和对应 `metadata.csv`。

`metadata.csv` 每行对应一张图片：

| 字段 | 含义 |
| --- | --- |
| `index` | 帧序号。 |
| `time` | 保存时间。 |
| `epoch_ms` | 毫秒时间戳。 |
| `file` | 图片相对路径。 |
| `people` | 当时检测到的人数。 |
| `person_active` | 当时是否已经通过防抖并发送收缩命令。 |
| `command` | 当时算法/手动状态计算出的当前命令。 |
| `esp_enabled` | 当时 ESP32 控制是否启用。为 `false` 时，`command` 只显示不输出到 ESP32。 |
| `stream_status` | 摄像头状态。 |
| `serial_status` | ESP32 串口状态。 |

默认不保存画面。开启 `CAPTURE_ENABLED=true` 后，`CAPTURE_INTERVAL_MS=200`，约 5 fps。这里控制的是 JPG 图片序列，不影响默认 MP4 写入。需要完整 JPG 帧记录时可以设为：

```bash
CAPTURE_ENABLED="true" CAPTURE_INTERVAL_MS="0" gradle run
```

如果想减轻 MP4 写入压力，可以降低 MP4 写入频率：

```bash
CAPTURE_ENABLED="true" CAPTURE_MP4_FPS="10" CAPTURE_MP4_INTERVAL_MS="100" gradle run
```

## ESP32 通信约定

ESP32 控制启用时，电脑端持续发送一行文本命令；禁用时不写串口：

```text
UP\n
DOWN\n
STOP\n
```

ESP32 端需要做的最小逻辑：

```text
收到 DOWN -> 继电器/电机进入收缩方向
收到 UP   -> 继电器/电机进入下降/放松方向
收到 STOP -> 停止收缩
```

当前现场开关方向已在软件里反过来：自动识别收缩默认发送 `DOWN`，手动按住 `↓` 默认发送 `UP`。ESP32 控制禁用时，这些命令只在 UI 中显示，不写入 ESP32。

ESP32 自动连接逻辑：

- 程序每秒扫描一次串口。
- 默认匹配端口名/描述中包含 `usb`、`esp32`、`cp210`、`ch340`、`usbserial` 等关键词的设备。
- 打开串口后等待 2 秒，给 ESP32 重启留时间。
- 写入失败或端口断开后，关闭旧端口并继续扫描。
- 重连成功后继续发送当前命令。

## 视频流自动检查

视频流逻辑：

- 程序启动后持续尝试连接 `HELMET_CAMERA_URL`。
- 摄像头连接成功后默认只显示 `Helmet View`，不自动打开浏览器网页。
- 如果连接失败，1 秒后重试。
- 如果连接成功但 3 秒没有新画面，认为断流。
- 断流时立即把当前命令切成 `STOP`。
- 断流后继续重连。
- 设置 `AUTO_OPEN_CAMERA_PAGE=true` 后，程序会打开 `CAMERA_PAGE_URL`；默认每次程序运行只打开一次，设置 `OPEN_CAMERA_PAGE_ON_RECONNECT=true` 后，每次重连成功都会打开。

## 树莓派服务

当前面具树莓派：

```text
IP: 192.168.1.98
Host: ziyang.local
User: ziyang
```

树莓派当前使用 uStreamer 做网页预览和 MJPEG 推流：

| 服务 | 地址 | 作用 |
| --- | --- | --- |
| `mjpgcam.service` | `http://192.168.1.98:8080/stream` | 当前主程序读取的视频流。 |
| `mjpgcam.service` | `http://192.168.1.98:8080/` | 浏览器预览网页。 |
| `mjpgcam.service` | `http://192.168.1.98:8080/snapshot` | 抓取当前单帧，用于检查画面。 |
| `mjpgcam-guard.timer` | 每 20 秒检查一次 | 抓不到 `/snapshot` 时自动重启 `mjpgcam.service`。 |
| `rtspcam.service` | `rtsp://192.168.1.98:8554/unicast` | 旧 RTSP 服务，当前已停用，避免和网页流抢摄像头。 |

当前已测稳定配置：

| 项目 | 结果 |
| --- | --- |
| 推流程序 | `ustreamer` |
| 当前配置 | `1280x960`、MJPEG、目标 `30 fps`、`--buffers 8 --workers 1` |
| 实测状态 | `/state` 显示 `source.online=true`，`captured_fps` 约 `30`，客户端实收约 `30 fps` |
| 稳定性 | 2026-06-28 连续拉 `/stream` 60 秒通过，snapshot 正确，测试窗口内无新的 USB/UVC 内核错误。 |
| 已定位原因 | 旧配置下 uStreamer 只申请默认 2 个 V4L2 缓冲；`1920x1080` 推流时缓冲太紧，会触发 UVC 采集错误和 USB 设备重连。增加缓冲后 1080p 也通过测试；当前最终选择 `1280x960` 作为 4:3 表演档。 |
| 不稳定高档 | `2592x1944` 可以出图，但 60 秒拉流中出现 USB 摄像头断开重连，不作为当前稳定档。 |
| 异常降级 | 如果 `/state` 变成 `160x120` / `YUYV` / CPU encoder，先查 `lsusb -t`。出现 `12M` 或 `not running at top speed` 时，摄像头已退到 USB full-speed；重启树莓派可恢复到 `480M` high-speed。 |

4:3 档位测试记录：

| 分辨率 | 比例 | 测试结果 |
| --- | --- | --- |
| `2048x1536` | 4:3 | 60 秒 HTTP 拉流未断，摄像头端 `/state` 显示 `captured_fps=30`，snapshot 正确；但客户端实收 fps 一度约 `17`，并且测试中 SSH 曾短暂超时，说明树莓派系统余量不足。不建议作为表演稳定档。 |
| `1280x960` | 4:3 | 60 秒 HTTP 拉流通过，摄像头端和客户端均显示约 `30 fps`，snapshot 正确，测试窗口内无新的 USB/UVC 内核错误。推荐作为需要 4:3 画面时的稳定档。 |

摄像头硬件通过 UVC 上报的输出规格：

| 格式 | 分辨率 | 标称帧率 |
| --- | --- | --- |
| MJPG | `1920x1080` | 30 fps |
| MJPG | `160x120` | 30 fps |
| MJPG | `320x240` | 30 fps |
| MJPG | `352x288` | 30 fps |
| MJPG | `640x480` | 30 fps |
| MJPG | `800x600` | 30 fps |
| MJPG | `1024x768` | 30 fps |
| MJPG | `1280x720` | 30 fps |
| MJPG | `1280x960` | 30 fps |
| MJPG | `2592x1944` | 30 fps |
| MJPG | `2048x1536` | 30 fps |
| YUYV | `640x480` | 30 fps |
| YUYV | `1280x720` | 10 fps |
| YUYV | `2048x1536` | 1 fps |
| YUYV | `1920x1080` | 5 fps |
| YUYV | `1280x960` | 5 fps |
| YUYV | `960x540` | 10 fps |
| YUYV | `800x600` | 10 fps |
| YUYV | `2592x1944` | 1 fps |

这张表是摄像头当前通过 `v4l2-ctl --list-formats-ext` 上报的能力，不等于所有档位都适合表演。当前演出使用 `1280x960` MJPG；`2048x1536` 虽然接近当前比例但树莓派系统余量不足，`2592x1944` 实测不稳定。

相关远端文件：

| 路径 | 作用 |
| --- | --- |
| `/usr/local/bin/start-mjpgcam.sh` | 启动 `ustreamer`，当前设为 `1280x960` MJPEG，8 个采集缓冲。 |
| `/etc/systemd/system/mjpgcam.service` | 网页预览和 MJPEG 推流 systemd 服务。 |
| `/usr/local/bin/mjpgcam_guard.sh` | 检查 `/snapshot` 是否能抓到帧，失败则重启推流服务。 |
| `/etc/systemd/system/mjpgcam-guard.timer` | 每 20 秒运行一次推流 watchdog。 |
| `/home/ziyang/mjpg-streamer/mjpg-streamer-experimental/` | 旧 MJPG-streamer 目录，当前不作为主服务使用。 |
| `/usr/local/bin/startstream.sh` | 旧 RTSP 启动脚本。 |
| `/etc/systemd/system/rtspcam.service` | 旧 RTSP systemd 服务，当前 disabled/inactive。 |
| `/etc/systemd/system/adaptive-stream.service` | 旧 H264 自适应服务，脚本仍指向 `192.168.1.XX`，当前 disabled/inactive。 |

远端检查命令：

```bash
ssh ziyang@192.168.1.98 'systemctl status mjpgcam.service mjpgcam-guard.timer --no-pager'
ssh ziyang@192.168.1.98 'ss -ltnp | grep ":8080"'
```

运行窗口 `Helmet View` 会显示：

- 当前检测到的人数。
- 当前发送命令。
- 摄像头状态。
- ESP32 状态。
- 当前拍摄保存状态。
- 是否翻转画面。
- 中央大字 `CONTRACT` 或 `STOP`。

树莓派画面与人检测结果相互独立：没有识别到人时仍持续显示摄像头画面，只隐藏人框和算法信息块。

`Helmet View` 会随窗口大小自适应显示画面。缩放时保持摄像头原始长宽比，不会拉伸变形；默认优先用窗口高度撑满画面，再按比例计算宽度。如果投影画面太窄，程序会自动改为按宽度适配，避免裁掉画面。窗口启动时会默认最大化，适合直接接投影使用。

当前树莓派推流固定为 `1280x960`，画面比例是 `4:3`。`Helmet View` 默认窗口尺寸也是 `1280x960`，最大化后继续保持 4:3，不拉伸。摄像头硬件支持其它比例，但当前服务脚本固定输出 `1280x960`；如果 `/state` 异常变成 `160x120`，那是 USB 降级/错误状态，不是正常演出规格。

窗口画面上的非中央信息由 Swing 叠层绘制，固定按三栏自适应排版；中央 `STOP` / `CONTRACT` 大字仍直接绘制在视频画面中心。连接等待文字默认使用 `Helvetica Neue`，整体视觉比调试默认字体更克制。

`Helmet View` 可以显示同画面的算法文字叠加层。主摄像头画面一直保留，调试信息直接叠在画面上，不再打开单独调试窗口。这个叠加层显示：

- 顶部状态信息按三栏显示：视觉判断、摄像头状态、ESP32/保存状态。
- 没有人时不显示算法细节，画面保留顶部三栏实时状态信息和中央命令。
- 检测到人时，主画面显示绿色人框；检测默认按 45 度步长扫描 8 个方向，摄像头斜着、横着、倒着都能触发。
- 算法信息块按检测框在画面里的位置排序：从左到右。
- 画面会为每个检测框显示一个无底色、无边框的算法信息块，数量与人框严格对应。
- 每个算法信息块顶部显示该检测框裁剪出的人像照片，照片下方显示算法细节。
- 算法信息块和顶部状态使用同一套三栏排版，随窗口宽高自动调整位置和可见行数。
- 每个信息块用 6 行显示检测器、预处理流程、帧编号、源图/检测图尺寸、HOG/Haar 参数、检测框原图坐标、检测响应和检测耗时。
- 顶部三栏实时状态信息每秒刷新一次，显示摄像头、ESP32、识别/保存状态。

这个叠加层不能显示 HOG/Haar 内部每一层特征如何通过/失败，因为 OpenCV 不暴露逐步判断。真正来自 OpenCV 的结果是最终人/脸矩形、检测权重和检测耗时。

卡顿排查记录：

- 2026-06-28 发现 `Helmet View` 卡顿时，树莓派日志同时出现 `Mainloop select() error: Inappropriate ioctl for device`、`USB disconnect`、`Failed to set UVC probe control : -71`。这说明当时不只是本地 UI 慢，摄像头 USB 也发生过真实断开重连。
- 同时本地 `HoistSystem` Java 进程一度约 `260% CPU`，主因是对 `1280x960` 全尺寸画面持续做检测、转 `BufferedImage`、Swing 刷新和视频保存。
- 当前代码已做两处缓解：人检测缩小到 `DETECT_MAX_WIDTH=640` 后再映射回原画面；`Helmet View` 刷新限制为 `UI_MAX_FPS=20`，并丢弃过期帧，避免 UI 队列积压。
- 如果仍然卡，优先检查 `curl http://192.168.1.98:8080/state` 是否稳定 `online=true`、`captured_fps` 是否接近目标；再查 `dmesg -T | tail` 是否还有 USB 断开。如果 USB 仍断，软件 UI 优化不能完全解决，需要降低树莓派推流 fps 或处理供电/线材/摄像头连接。

## 旧方案与新版差异

旧方案：

- 有头盔/面具摄像头。
- 有第三方俯视摄像头。
- 通过反光条检测悬挂物高度。
- 根据观众注视比例计算目标高度。
- 自动控制电葫芦上升或下降，做高度闭环。

新版：

- 只有头盔摄像头。
- 不检测高度。
- 不需要第三方俯视摄像头。
- 不使用 `AirViewProcessor`。
- 不计算目标高度。
- 自动识别发送 `HOIST_CONTRACT_COMMAND`；当前默认是 `DOWN`，用于现场开关接反后的收缩动作。
- 手动模式有软件互锁：`↑` 和 `↓` 同时按下时强制发送 `STOP`，不会输出上/下任一方向命令；释放到只剩一个方向键后才恢复对应方向。
- 不做电子限位停止。
- 看到人就收缩；没有人就停止收缩。

## 常见修改入口

| 想改什么 | 去哪里改 |
| --- | --- |
| 头盔摄像头地址 | `HELMET_CAMERA_URL` 或 `HoistSystem.java` 顶部常量 |
| 自动打开的摄像头网页 | `CAMERA_PAGE_URL` |
| 是否自动打开网页 | `AUTO_OPEN_CAMERA_PAGE` |
| ESP32 匹配关键词 | `CONTROLLER_PORT_MATCH` |
| ESP32 波特率 | `CONTROLLER_BAUD_RATE` |
| 收缩命令文本 | `HOIST_CONTRACT_COMMAND` |
| 下降/放松命令文本 | `HOIST_DESCEND_COMMAND` |
| 停止命令文本 | `HOIST_STOP_COMMAND` |
| 手动结束后恢复自动的延迟 | `MANUAL_AUTO_RESUME_DELAY_MS` |
| 画面是否默认翻转 | `FLIP_HELMET_CAMERA` |
| 人检测频率 | `DETECT_EVERY_N_FRAMES` |
| 看到人启动防抖 | `CONTACT_START_FRAMES` / `CONTACT_START_HOLD_MS` |
| 识别丢失后多久停止 | `CONTACT_LOST_FRAMES` |
| 人检测参数 | `detectPeople()` 里的 HOG / Haar 参数 |
| 是否保存头盔画面 | `CAPTURE_ENABLED` |
| 保存目录 | `CAPTURE_DIR` |
| 保存频率 | `CAPTURE_INTERVAL_MS` |
| JPEG 质量 | `CAPTURE_JPEG_QUALITY` |
| 是否保存 MP4 | `CAPTURE_MP4_ENABLED` |
| MP4 文件名 | `CAPTURE_MP4_FILENAME` |
| MP4 帧率/写入频率 | `CAPTURE_MP4_FPS` / `CAPTURE_MP4_INTERVAL_MS` |
| 视频流重连策略 | `startHelmetCameraLoop()` |
| 串口重连策略 | `ensureControllerConnected()` / `findControllerPort()` |
| Java 依赖和 OpenCV 路径 | `build.gradle` |

## 实体资产说明

| 路径 | 内容 |
| --- | --- |
| `物质/IMG_8833.JPG`、`物质/IMG_8835.JPG` | 展场/安装空间照片。 |
| `物质/效果图/` | 旧测试截图和效果图，包含摄像头测试画面、识别窗口、吊挂结构预览。 |
| `物质/我/` | 与个人头部/头像相关的 Blender 和贴图 OBJ 资产。 |
| `物质/我/textured_mesh_obj/` | 头部扫描/贴图网格导出，配套 `mesh.mtl` 和 `textures/`。 |
| `物质/把自己吊起来/打印模型/` | 用于打印/制作的脸和头发 OBJ/MTL 模型。 |
| `物质/thczv-female-v2.stl.stl` | STL 模型文件，具体用途需结合建模软件确认。 |

## 依赖和环境

- Java 23，见 `build.gradle`。
- Gradle。
- OpenCV Java 4.13.0 左右，目前默认路径指向 Homebrew 安装位置。
- `com.fazecast:jSerialComm:2.11.0`，Gradle 会从 Maven Central 拉取。
- Python 推流脚本需要 Flask 和 OpenCV Python。

## 安全注意

- 这是控制实体电葫芦的程序。当前版本故意不做电子高度闭环，也不做电子限位停止。
- 表演运行前必须确认机器自身物理停止/限位装置可靠。
- ESP32 控制启用时，程序断流、无人、识别丢失会输出 `STOP`，但这不是限位保护。
- 如果 ESP32 断线，电脑端无法发送停止命令；硬件侧必须有独立的安全机制。
- 第一次联调建议断开电葫芦负载，只观察 ESP32/继电器状态和串口命令。

## 当前项目体量

大致大小：

```text
整个项目: 6.6G
程序/:   7.0M
物质/:   6.3G
build/:  45M
out/:    1.8M
libs/:   768K
```

主要空间占用在 `物质/` 的 3D 模型、贴图和照片。
