package com.k16.camera;

import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class NetworkDebugSession {
    interface Listener {
        void onStateChanged(String state);

        void onReceived(byte[] data, String remote);

        void onSent(int byteCount, String target);

        void onError(String message);
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Object lock = new Object();
    private final List<Socket> serverClients = new ArrayList<>();

    private volatile boolean running;
    private Socket tcpClientSocket;
    private ServerSocket serverSocket;
    private DatagramSocket udpSocket;
    private InetSocketAddress udpTarget;
    private int generation;

    boolean isRunning() {
        return running;
    }

    int activeEndpointCount() {
        synchronized (lock) {
            if (tcpClientSocket != null && tcpClientSocket.isConnected() && !tcpClientSocket.isClosed()) {
                return 1;
            }
            if (udpSocket != null && !udpSocket.isClosed()) {
                return 1;
            }
            int count = 0;
            for (Socket client : serverClients) {
                if (client.isConnected() && !client.isClosed()) {
                    count++;
                }
            }
            return count;
        }
    }

    void openTcpClient(String host, int port, Listener listener) {
        int token = prepareOpen();
        executor.execute(() -> {
            try {
                Socket socket = new Socket();
                socket.connect(new InetSocketAddress(host, port), 5000);
                socket.setTcpNoDelay(true);
                synchronized (lock) {
                    if (token != generation) {
                        closeSocket(socket);
                        return;
                    }
                    tcpClientSocket = socket;
                    running = true;
                }
                post(() -> listener.onStateChanged("TCP Client 已连接 " + host + ":" + port));
                readTcp(socket, socket.getRemoteSocketAddress().toString(), token, listener);
            } catch (IOException error) {
                if (isCurrent(token)) {
                    post(() -> listener.onError("TCP Client 打开失败: " + error.getMessage()));
                    closeSockets();
                    post(() -> listener.onStateChanged("未打开"));
                }
            }
        });
    }

    void openTcpServer(String bindHost, int port, Listener listener) {
        int token = prepareOpen();
        executor.execute(() -> {
            try {
                ServerSocket nextServer = new ServerSocket();
                nextServer.bind(new InetSocketAddress(bindHost, port));
                synchronized (lock) {
                    if (token != generation) {
                        nextServer.close();
                        return;
                    }
                    serverSocket = nextServer;
                    running = true;
                }
                post(() -> listener.onStateChanged("TCP Server 正在监听 " + bindHost + ":" + port));
                while (running && isCurrent(token) && !nextServer.isClosed()) {
                    Socket client = nextServer.accept();
                    client.setTcpNoDelay(true);
                    synchronized (lock) {
                        if (token != generation) {
                            closeSocket(client);
                            return;
                        }
                        serverClients.add(client);
                    }
                    String remote = client.getRemoteSocketAddress().toString();
                    post(() -> listener.onStateChanged("TCP Server 客户端已连接 " + remote));
                    executor.execute(() -> readTcp(client, remote, token, listener));
                }
            } catch (IOException error) {
                if (running && isCurrent(token)) {
                    post(() -> listener.onError("TCP Server 监听失败: " + error.getMessage()));
                }
            } finally {
                if (isCurrent(token)) {
                    close(listener);
                }
            }
        });
    }

    void openUdp(String bindHost, int localPort, String host, int port, boolean broadcast, Listener listener) {
        int token = prepareOpen();
        executor.execute(() -> {
            try {
                DatagramSocket socket = new DatagramSocket(new InetSocketAddress(bindHost, localPort));
                socket.setBroadcast(broadcast);
                synchronized (lock) {
                    if (token != generation) {
                        socket.close();
                        return;
                    }
                    udpSocket = socket;
                    udpTarget = new InetSocketAddress(host, port);
                    running = true;
                }
                String local = socket.getLocalAddress().getHostAddress() + ":" + socket.getLocalPort();
                post(() -> listener.onStateChanged("UDP 已打开，本地 " + local + "，远端 " + host + ":" + port));
                readUdp(socket, token, listener);
            } catch (SocketException error) {
                if (isCurrent(token)) {
                    post(() -> listener.onError("UDP 打开失败: " + error.getMessage()));
                    closeSockets();
                    post(() -> listener.onStateChanged("未打开"));
                }
            }
        });
    }

    void send(byte[] data, Listener listener) {
        executor.execute(() -> {
            try {
                Socket tcpClient;
                ServerSocket tcpServer;
                DatagramSocket udp;
                InetSocketAddress udpAddress;
                synchronized (lock) {
                    tcpClient = tcpClientSocket;
                    tcpServer = serverSocket;
                    udp = udpSocket;
                    udpAddress = udpTarget;
                }

                if (tcpClient != null && tcpClient.isConnected() && !tcpClient.isClosed()) {
                    writeTcp(tcpClient, data);
                    String target = tcpClient.getRemoteSocketAddress().toString();
                    post(() -> listener.onSent(data.length, target));
                    return;
                }

                if (tcpServer != null && !tcpServer.isClosed()) {
                    int sentCount = sendToServerClients(data);
                    post(() -> listener.onSent(data.length, sentCount + " TCP clients"));
                    return;
                }

                if (udp != null && !udp.isClosed() && udpAddress != null) {
                    DatagramPacket packet = new DatagramPacket(data, data.length, udpAddress);
                    udp.send(packet);
                    post(() -> listener.onSent(data.length, udpAddress.toString()));
                    return;
                }

                post(() -> listener.onError("连接未打开，无法发送"));
            } catch (IOException error) {
                post(() -> listener.onError("发送失败: " + error.getMessage()));
            }
        });
    }

    void close(Listener listener) {
        executor.execute(() -> {
            closeSockets();
            post(() -> listener.onStateChanged("未打开"));
        });
    }

    private void readTcp(Socket socket, String remote, int token, Listener listener) {
        byte[] buffer = new byte[4096];
        try {
            InputStream inputStream = socket.getInputStream();
            while (running && isCurrent(token) && !socket.isClosed()) {
                int count = inputStream.read(buffer);
                if (count < 0) {
                    break;
                }
                if (count > 0) {
                    byte[] data = new byte[count];
                    System.arraycopy(buffer, 0, data, 0, count);
                    post(() -> listener.onReceived(data, remote));
                }
            }
        } catch (IOException error) {
            if (running && isCurrent(token)) {
                post(() -> listener.onError("TCP 接收失败: " + error.getMessage()));
            }
        } finally {
            removeClient(socket);
            closeSocket(socket);
            if (isCurrent(token) && socket == tcpClientSocket) {
                close(listener);
            } else if (running && isCurrent(token)) {
                post(() -> listener.onStateChanged("TCP Server 客户端已断开 " + remote));
            }
        }
    }

    private void readUdp(DatagramSocket socket, int token, Listener listener) {
        byte[] buffer = new byte[8192];
        try {
            while (running && isCurrent(token) && !socket.isClosed()) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                byte[] data = new byte[packet.getLength()];
                System.arraycopy(packet.getData(), packet.getOffset(), data, 0, packet.getLength());
                String remote = packet.getAddress().getHostAddress() + ":" + packet.getPort();
                post(() -> listener.onReceived(data, remote));
            }
        } catch (IOException error) {
            if (running && isCurrent(token)) {
                post(() -> listener.onError("UDP 接收失败: " + error.getMessage()));
            }
        } finally {
            if (isCurrent(token)) {
                close(listener);
            }
        }
    }

    private int sendToServerClients(byte[] data) {
        List<Socket> clients;
        synchronized (lock) {
            clients = new ArrayList<>(serverClients);
        }

        int sent = 0;
        for (Socket client : clients) {
            try {
                if (client.isConnected() && !client.isClosed()) {
                    writeTcp(client, data);
                    sent++;
                }
            } catch (IOException ignored) {
                removeClient(client);
                closeSocket(client);
            }
        }
        return sent;
    }

    private void writeTcp(Socket socket, byte[] data) throws IOException {
        OutputStream outputStream = socket.getOutputStream();
        outputStream.write(data);
        outputStream.flush();
    }

    private int prepareOpen() {
        synchronized (lock) {
            generation++;
            closeAllLocked();
            return generation;
        }
    }

    private void closeSockets() {
        synchronized (lock) {
            generation++;
            closeAllLocked();
        }
    }

    private void closeAllLocked() {
        running = false;
        closeSocket(tcpClientSocket);
        tcpClientSocket = null;

        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
        }
        serverSocket = null;

        for (Socket client : serverClients) {
            closeSocket(client);
        }
        serverClients.clear();

        if (udpSocket != null) {
            udpSocket.close();
        }
        udpSocket = null;
        udpTarget = null;
    }

    private boolean isCurrent(int token) {
        synchronized (lock) {
            return token == generation;
        }
    }

    private void removeClient(Socket socket) {
        synchronized (lock) {
            serverClients.remove(socket);
        }
    }

    private void closeSocket(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private void post(Runnable runnable) {
        mainHandler.post(runnable);
    }
}
