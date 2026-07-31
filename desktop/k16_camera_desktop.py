import queue
import socket
import threading
import time
import tkinter as tk
from tkinter import messagebox, ttk

import cv2
from PIL import Image, ImageTk


DEFAULT_IP = "192.168.1.188"
DEFAULT_RTSP_PORT = "554"
DEFAULT_TCP_PORT = "8866"
DEFAULT_STREAM = "1"


class TcpClient:
    def __init__(self, on_message, on_state, on_error):
        self.on_message = on_message
        self.on_state = on_state
        self.on_error = on_error
        self.sock = None
        self.thread = None
        self.running = False
        self.lock = threading.Lock()

    def connect(self, host, port):
        self.disconnect(silent=True)
        self.running = True
        self.thread = threading.Thread(target=self._connect_and_read, args=(host, port), daemon=True)
        self.thread.start()

    def send(self, text):
        with self.lock:
            sock = self.sock
        if not sock:
            self.on_error("TCP未连接")
            return
        try:
            sock.sendall(text.encode("utf-8"))
        except OSError as exc:
            self.on_error(f"发送失败: {exc}")
            self.disconnect()

    def disconnect(self, silent=False):
        self.running = False
        with self.lock:
            sock = self.sock
            self.sock = None
        if sock:
            try:
                sock.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            try:
                sock.close()
            except OSError:
                pass
        if not silent:
            self.on_state(False)

    def is_connected(self):
        with self.lock:
            return self.sock is not None

    def _connect_and_read(self, host, port):
        try:
            sock = socket.create_connection((host, port), timeout=5)
            sock.settimeout(1)
            with self.lock:
                self.sock = sock
            self.on_state(True)
            while self.running:
                try:
                    data = sock.recv(4096)
                    if not data:
                        break
                    self.on_message(data.decode("utf-8", errors="replace"))
                except socket.timeout:
                    continue
        except OSError as exc:
            self.on_error(f"TCP连接失败: {exc}")
        finally:
            self.disconnect()


class VideoPlayer:
    def __init__(self, frame_queue, on_log):
        self.frame_queue = frame_queue
        self.on_log = on_log
        self.running = False
        self.thread = None

    def play(self, rtsp_url):
        self.stop()
        self.running = True
        self.thread = threading.Thread(target=self._capture_loop, args=(rtsp_url,), daemon=True)
        self.thread.start()

    def stop(self):
        self.running = False

    def _capture_loop(self, rtsp_url):
        self.on_log(f"Play: {rtsp_url}")
        capture = cv2.VideoCapture(rtsp_url, cv2.CAP_FFMPEG)
        if not capture.isOpened():
            self.on_log("RTSP打开失败，请确认手机/电脑和模块在同一WiFi，且地址可访问")
            return
        while self.running:
            ok, frame = capture.read()
            if not ok:
                self.on_log("RTSP读取中断")
                break
            frame = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
            try:
                self.frame_queue.put_nowait(frame)
            except queue.Full:
                try:
                    self.frame_queue.get_nowait()
                    self.frame_queue.put_nowait(frame)
                except queue.Empty:
                    pass
            time.sleep(0.01)
        capture.release()


class K16App(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("K16 Camera")
        self.geometry("1120x720")
        self.minsize(920, 620)

        self.frame_queue = queue.Queue(maxsize=2)
        self.photo = None
        self.tcp = TcpClient(self._tcp_message, self._tcp_state, self._tcp_error)
        self.video = VideoPlayer(self.frame_queue, self._log_threadsafe)

        self._build_ui()
        self._poll_frames()
        self.protocol("WM_DELETE_WINDOW", self._close)

    def _build_ui(self):
        self.columnconfigure(0, weight=3)
        self.columnconfigure(1, weight=2)
        self.rowconfigure(1, weight=1)

        toolbar = ttk.Frame(self, padding=10)
        toolbar.grid(row=0, column=0, columnspan=2, sticky="ew")

        self.ip_var = tk.StringVar(value=DEFAULT_IP)
        self.rtsp_port_var = tk.StringVar(value=DEFAULT_RTSP_PORT)
        self.stream_var = tk.StringVar(value=DEFAULT_STREAM)
        self.tcp_port_var = tk.StringVar(value=DEFAULT_TCP_PORT)

        self._entry(toolbar, "设备IP", self.ip_var, 16).pack(side=tk.LEFT, padx=(0, 8))
        self._entry(toolbar, "RTSP端口", self.rtsp_port_var, 8).pack(side=tk.LEFT, padx=(0, 8))
        self._entry(toolbar, "码流", self.stream_var, 6).pack(side=tk.LEFT, padx=(0, 8))
        ttk.Button(toolbar, text="播放视频", command=self._play_video).pack(side=tk.LEFT, padx=(0, 8))
        ttk.Button(toolbar, text="停止视频", command=self.video.stop).pack(side=tk.LEFT, padx=(0, 8))
        ttk.Button(toolbar, text="复制RTSP", command=self._copy_rtsp).pack(side=tk.LEFT)

        video_panel = ttk.Frame(self, padding=(10, 0, 5, 10))
        video_panel.grid(row=1, column=0, sticky="nsew")
        video_panel.columnconfigure(0, weight=1)
        video_panel.rowconfigure(0, weight=1)
        self.video_label = ttk.Label(video_panel, text="等待播放 RTSP 视频", anchor=tk.CENTER, background="#111820", foreground="#FFFFFF")
        self.video_label.grid(row=0, column=0, sticky="nsew")

        side = ttk.Frame(self, padding=(5, 0, 10, 10))
        side.grid(row=1, column=1, sticky="nsew")
        side.columnconfigure(0, weight=1)
        side.rowconfigure(5, weight=1)

        tcp_bar = ttk.Frame(side)
        tcp_bar.grid(row=0, column=0, sticky="ew", pady=(0, 8))
        self._entry(tcp_bar, "TCP端口", self.tcp_port_var, 8).pack(side=tk.LEFT, padx=(0, 8))
        self.connect_button = ttk.Button(tcp_bar, text="连接TCP", command=self._toggle_tcp)
        self.connect_button.pack(side=tk.LEFT, padx=(0, 8))
        self.tcp_state_label = ttk.Label(tcp_bar, text="TCP未连接")
        self.tcp_state_label.pack(side=tk.LEFT)

        ttk.Label(side, text="JSON命令").grid(row=1, column=0, sticky="w")
        self.json_text = tk.Text(side, height=8, wrap=tk.WORD)
        self.json_text.grid(row=2, column=0, sticky="ew", pady=(4, 8))
        self.json_text.insert("1.0", '{"GetDevStatus":{}}')

        send_bar = ttk.Frame(side)
        send_bar.grid(row=3, column=0, sticky="ew", pady=(0, 8))
        ttk.Button(send_bar, text="状态命令", command=lambda: self._set_json('{"GetDevStatus":{}}')).pack(side=tk.LEFT, padx=(0, 8))
        ttk.Button(send_bar, text="发送JSON", command=self._send_json).pack(side=tk.LEFT)

        ttk.Label(side, text="日志").grid(row=4, column=0, sticky="w")
        self.log_text = tk.Text(side, height=12, wrap=tk.WORD, state=tk.DISABLED)
        self.log_text.grid(row=5, column=0, sticky="nsew", pady=(4, 0))

    def _entry(self, parent, label, variable, width):
        frame = ttk.Frame(parent)
        ttk.Label(frame, text=label).pack(side=tk.TOP, anchor=tk.W)
        ttk.Entry(frame, textvariable=variable, width=width).pack(side=tk.TOP)
        return frame

    def _rtsp_url(self):
        ip = self.ip_var.get().strip() or DEFAULT_IP
        port = self.rtsp_port_var.get().strip() or DEFAULT_RTSP_PORT
        stream = self.stream_var.get().strip() or DEFAULT_STREAM
        return f"rtsp://{ip}:{port}/{stream}"

    def _play_video(self):
        self.video.play(self._rtsp_url())

    def _copy_rtsp(self):
        self.clipboard_clear()
        self.clipboard_append(self._rtsp_url())
        self._log(f"RTSP copied: {self._rtsp_url()}")

    def _toggle_tcp(self):
        if self.tcp.is_connected():
            self.tcp.disconnect()
            return
        ip = self.ip_var.get().strip() or DEFAULT_IP
        try:
            port = int(self.tcp_port_var.get().strip() or DEFAULT_TCP_PORT)
        except ValueError:
            messagebox.showerror("端口错误", "TCP端口必须是数字")
            return
        self._log(f"Connect: {ip}:{port}")
        self.tcp.connect(ip, port)

    def _send_json(self):
        text = self.json_text.get("1.0", tk.END).strip()
        if not text:
            messagebox.showwarning("JSON为空", "请输入要发送的JSON命令")
            return
        self._log(f"TX: {text}")
        self.tcp.send(text)

    def _set_json(self, text):
        self.json_text.delete("1.0", tk.END)
        self.json_text.insert("1.0", text)

    def _tcp_state(self, connected):
        self.after(0, lambda: self._apply_tcp_state(connected))

    def _apply_tcp_state(self, connected):
        self.tcp_state_label.config(text="TCP已连接" if connected else "TCP未连接")
        self.connect_button.config(text="断开TCP" if connected else "连接TCP")
        self._log("TCP connected" if connected else "TCP disconnected")

    def _tcp_message(self, text):
        self._log_threadsafe(f"RX: {text}")

    def _tcp_error(self, message):
        self._log_threadsafe(message)

    def _log_threadsafe(self, message):
        self.after(0, lambda: self._log(message))

    def _log(self, message):
        self.log_text.config(state=tk.NORMAL)
        self.log_text.insert(tk.END, message + "\n")
        self.log_text.see(tk.END)
        self.log_text.config(state=tk.DISABLED)

    def _poll_frames(self):
        try:
            frame = self.frame_queue.get_nowait()
            self._show_frame(frame)
        except queue.Empty:
            pass
        self.after(33, self._poll_frames)

    def _show_frame(self, frame):
        width = max(self.video_label.winfo_width(), 1)
        height = max(self.video_label.winfo_height(), 1)
        image = Image.fromarray(frame)
        image.thumbnail((width, height), Image.Resampling.LANCZOS)
        self.photo = ImageTk.PhotoImage(image)
        self.video_label.config(image=self.photo, text="")

    def _close(self):
        self.video.stop()
        self.tcp.disconnect(silent=True)
        self.destroy()


if __name__ == "__main__":
    app = K16App()
    app.mainloop()
