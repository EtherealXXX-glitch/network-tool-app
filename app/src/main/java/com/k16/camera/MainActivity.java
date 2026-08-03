package com.k16.camera;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

public class MainActivity extends Activity {
    private static final int PICK_SEND_FILE = 6101;
    private static final String DEFAULT_REMOTE_HOST = "192.168.2.100";
    private static final String DEFAULT_BIND_HOST = "0.0.0.0";
    private static final String DEFAULT_PORT = "8866";
    private static final int AUTO_SEND_MIN_MS = 50;
    private static final String PREFS_NAME = "netassist_config_blank_defaults";
    private static final int PAGE_CONNECTION = 0;
    private static final int PAGE_SETTINGS = 1;
    private static final int PAGE_COMMUNICATION = 2;
    private static final int DEFAULT_CUSTOM_COMMAND_COUNT = 3;
    private static final int SWIPE_MIN_DISTANCE_DP = 72;
    private static final int SWIPE_MAX_OFF_AXIS_DP = 96;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final NetworkDebugSession session = new NetworkDebugSession();
    private final List<String> sendHistory = new ArrayList<>();
    private final AppendSettings appendSettings = new AppendSettings();

    private LogBuffer logBuffer;
    private LinearLayout tabBar;
    private FrameLayout pageHost;
    private LinearLayout rootLayout;
    private View connectionPage;
    private View settingsPage;
    private View communicationPage;

    private Spinner modeSpinner;
    private Spinner localHostSpinner;
    private Spinner historySpinner;
    private LinearLayout localHostListBlock;
    private LinearLayout localHostInputBlock;
    private LinearLayout localPortBlock;
    private LinearLayout remoteHostBlock;
    private LinearLayout remotePortBlock;
    private EditText localHostInput;
    private EditText remoteHostInput;
    private EditText remotePortInput;
    private EditText localPortInput;
    private EditText sendInput;
    private EditText intervalInput;
    private TextView stateText;
    private TextView connectedCountText;
    private TextView logText;
    private TextView counterText;
    private TextView appendSummaryText;
    private TextView cacheText;
    private RadioButton lightThemeRadio;
    private RadioButton darkThemeRadio;
    private Spinner sendModeSpinner;
    private LinearLayout manualSendPanel;
    private LinearLayout customCommandPanel;
    private LinearLayout customCommandList;
    private final List<EditText> customCommandInputs = new ArrayList<>();
    private ScrollView logScroll;
    private Button openButton;

    private RadioButton receiveAsciiRadio;
    private RadioButton receiveHexRadio;
    private RadioButton sendAsciiRadio;
    private RadioButton sendHexRadio;

    private CheckBox receiveLogModeBox;
    private CheckBox receiveAutoLineBox;
    private CheckBox receiveHiddenBox;
    private CheckBox receiveSaveBox;
    private CheckBox sendEscapeBox;
    private CheckBox sendAppendBox;
    private CheckBox autoSendBox;
    private CheckBox udpBroadcastBox;

    private File receiveFile;
    private long rxBytes;
    private long txBytes;
    private int selectedPage;
    private boolean restoringConfig;
    private boolean darkTheme;
    private boolean reloadingLocalHosts;
    private boolean pageAnimating;
    private float swipeStartX;
    private float swipeStartY;

    private final NetworkDebugSession.Listener networkListener = new NetworkDebugSession.Listener() {
        @Override
        public void onStateChanged(String state) {
            updateConnectionStatus(state);
            openButton.setText(session.isRunning() ? "关闭" : "打开");
            appendSystem(state);
        }

        @Override
        public void onReceived(byte[] data, String remote) {
            rxBytes += data.length;
            updateCounters();
            saveReceivedIfNeeded(data);
            if (!receiveHiddenBox.isChecked()) {
                appendReceive(remote, data);
            }
        }

        @Override
        public void onSent(int byteCount, String target) {
            txBytes += byteCount;
            updateCounters();
            appendSystem("已发送 " + byteCount + " bytes -> " + target);
        }

        @Override
        public void onError(String message) {
            appendLogLine("ERR", message);
            Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
        }
    };

    private final Runnable autoSendRunnable = new Runnable() {
        @Override
        public void run() {
            if (!autoSendBox.isChecked()) {
                return;
            }
            sendCurrentPayload(false);
            handler.postDelayed(this, autoSendIntervalMs());
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        darkTheme = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean("dark_theme", false);
        logBuffer = new LogBuffer(getCacheDir());
        buildUi();
        reloadLocalHosts();
        loadConfig();
        updateModeUi();
        updateCounters();
        showPage(selectedPage);
    }

    @Override
    protected void onPause() {
        saveConfig();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        saveConfig();
        stopAutoSend();
        session.close(networkListener);
        if (logBuffer != null) {
            logBuffer.deleteOnExit();
        }
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_SEND_FILE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            sendFile(data.getData());
        }
    }

    private void buildUi() {
        applySystemBars();

        rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setBackgroundColor(appBackgroundColor());

        View topSpacer = new View(this);
        topSpacer.setBackgroundColor(appBackgroundColor());
        rootLayout.addView(topSpacer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(63)
        ));

        tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setBackgroundColor(sidebarColor());
        rootLayout.addView(tabBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
        ));
        addTab("连接设置", PAGE_CONNECTION);
        addTab("通信", PAGE_COMMUNICATION);
        addTab("设置", PAGE_SETTINGS);

        pageHost = new FrameLayout(this);
        pageHost.setBackgroundColor(appBackgroundColor());
        rootLayout.addView(pageHost, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1
        ));

        connectionPage = buildConnectionPage();
        settingsPage = buildSettingsPage();
        communicationPage = buildCommunicationPage();
        pageHost.addView(connectionPage);
        pageHost.addView(settingsPage);
        pageHost.addView(communicationPage);

        setContentView(rootLayout);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (handlePageSwipe(event)) {
            return true;
        }
        return super.dispatchTouchEvent(event);
    }

    private View buildConnectionPage() {
        ScrollView scrollView = pageScroll();
        LinearLayout content = pageContent(scrollView);

        LinearLayout networkPanel = panel(content, "网络设置");
        modeSpinner = spinner(new String[]{"TCP Server", "TCP Client", "UDP"});
        networkPanel.addView(labeled("协议类型", modeSpinner));

        LinearLayout openRow = row();
        openButton = button("打开");
        Button refreshIpButton = button("刷新IP");
        openButton.setOnClickListener(v -> toggleSession());
        refreshIpButton.setOnClickListener(v -> {
            reloadLocalHosts();
            Toast.makeText(this, "已刷新本机地址", Toast.LENGTH_SHORT).show();
        });
        openRow.addView(openButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        openRow.addView(refreshIpButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        setRowMargins(openRow);
        networkPanel.addView(openRow);

        localHostSpinner = spinner(new String[]{DEFAULT_BIND_HOST});
        localHostListBlock = labeled("本地主机地址列表", localHostSpinner);
        networkPanel.addView(localHostListBlock);
        localHostInput = edit("", "可手动输入本机监听IP");
        localHostInputBlock = labeled("本地主机地址", localHostInput);
        networkPanel.addView(localHostInputBlock);

        localPortInput = edit("", "本地主机端口");
        localPortInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        localPortBlock = labeled("本地主机端口", localPortInput);
        networkPanel.addView(localPortBlock);

        remoteHostInput = edit("", "远程主机地址");
        remotePortInput = edit("", "远程端口");
        remotePortInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        remoteHostBlock = labeled("远程主机地址", remoteHostInput);
        remotePortBlock = labeled("远程端口", remotePortInput);
        networkPanel.addView(remoteHostBlock);
        networkPanel.addView(remotePortBlock);

        udpBroadcastBox = checkbox("UDP广播");
        networkPanel.addView(udpBroadcastBox);

        LinearLayout statusBox = new LinearLayout(this);
        statusBox.setOrientation(LinearLayout.VERTICAL);
        statusBox.setPadding(dp(12), dp(10), dp(12), dp(10));
        statusBox.setBackground(box(disconnectedColor(), disconnectedColor(), 2));
        stateText = body("未连接");
        stateText.setTextSize(20);
        stateText.setTypeface(Typeface.DEFAULT_BOLD);
        stateText.setTextColor(Color.WHITE);
        connectedCountText = body("设备：0 台");
        connectedCountText.setTextSize(15);
        connectedCountText.setTextColor(Color.WHITE);
        statusBox.addView(stateText);
        statusBox.addView(connectedCountText);
        networkPanel.addView(statusBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout configPanel = panel(content, "配置存储");
        LinearLayout configRow = row();
        Button saveConfigButton = button("保存配置");
        Button loadConfigButton = button("恢复配置");
        Button clearConfigButton = button("清除配置");
        saveConfigButton.setOnClickListener(v -> {
            saveConfig();
            Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show();
        });
        loadConfigButton.setOnClickListener(v -> {
            loadConfig();
            updateModeUi();
            Toast.makeText(this, "配置已恢复", Toast.LENGTH_SHORT).show();
        });
        clearConfigButton.setOnClickListener(v -> {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().clear().apply();
            Toast.makeText(this, "配置已清除，下次启动恢复默认值", Toast.LENGTH_SHORT).show();
        });
        configRow.addView(saveConfigButton, new LinearLayout.LayoutParams(0, dp(46), 1));
        configRow.addView(loadConfigButton, new LinearLayout.LayoutParams(0, dp(46), 1));
        configRow.addView(clearConfigButton, new LinearLayout.LayoutParams(0, dp(46), 1));
        setRowMargins(configRow);
        configPanel.addView(configRow);

        modeSpinner.setOnItemSelectedListener(new SimpleItemSelectedListener() {
            @Override
            public void onItemSelected() {
                updateModeUi();
            }
        });
        localHostSpinner.setOnItemSelectedListener(new SimpleItemSelectedListener() {
            @Override
            public void onItemSelected() {
                if (restoringConfig || reloadingLocalHosts) {
                    return;
                }
                Object selected = localHostSpinner.getSelectedItem();
                if (selected != null && localHostInput != null) {
                    localHostInput.setText(selected.toString());
                }
            }
        });
        return scrollView;
    }

    private View buildSettingsPage() {
        ScrollView scrollView = pageScroll();
        LinearLayout content = pageContent(scrollView);

        LinearLayout receivePanel = panel(content, "接收设置");
        RadioGroup receiveFormatGroup = horizontalRadioGroup();
        receiveAsciiRadio = radio("ASCII");
        receiveHexRadio = radio("HEX");
        receiveFormatGroup.addView(receiveAsciiRadio);
        receiveFormatGroup.addView(receiveHexRadio);
        receiveAsciiRadio.setChecked(true);
        receivePanel.addView(receiveFormatGroup);

        receiveLogModeBox = checkbox("按日志模式显示");
        receiveAutoLineBox = checkbox("接收区自动换行");
        receiveHiddenBox = checkbox("接收数据不显示");
        receiveSaveBox = checkbox("接收保存到文件");
        receivePanel.addView(receiveLogModeBox);
        receivePanel.addView(receiveAutoLineBox);
        receivePanel.addView(receiveHiddenBox);
        receivePanel.addView(receiveSaveBox);

        LinearLayout receiveActions = row();
        Button autoScrollButton = button("自动滚屏");
        Button clearReceiveButton = button("清除接收");
        autoScrollButton.setOnClickListener(v -> scrollLogToBottom());
        clearReceiveButton.setOnClickListener(v -> clearLog());
        receiveActions.addView(autoScrollButton, new LinearLayout.LayoutParams(0, dp(46), 1));
        receiveActions.addView(clearReceiveButton, new LinearLayout.LayoutParams(0, dp(46), 1));
        setRowMargins(receiveActions);
        receivePanel.addView(receiveActions);

        LinearLayout sendPanel = panel(content, "发送设置");
        RadioGroup sendFormatGroup = horizontalRadioGroup();
        sendAsciiRadio = radio("ASCII");
        sendHexRadio = radio("HEX");
        sendFormatGroup.addView(sendAsciiRadio);
        sendFormatGroup.addView(sendHexRadio);
        sendAsciiRadio.setChecked(true);
        sendPanel.addView(sendFormatGroup);

        sendEscapeBox = checkbox("自动解析转义符");
        sendAppendBox = checkbox("自动发送附加位");
        sendPanel.addView(sendEscapeBox);
        sendPanel.addView(sendAppendBox);

        appendSummaryText = body(appendSettings.summary());
        appendSummaryText.setTextColor(secondaryTextColor());
        sendPanel.addView(appendSummaryText);
        Button appendSettingButton = button("附加位设置");
        appendSettingButton.setOnClickListener(v -> showAppendSettingsDialog());
        sendPanel.addView(appendSettingButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
        ));

        LinearLayout autoRow = row();
        autoSendBox = checkbox("循环周期");
        intervalInput = edit("", "ms");
        intervalInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        autoRow.addView(autoSendBox, new LinearLayout.LayoutParams(0, dp(44), 1));
        autoRow.addView(intervalInput, new LinearLayout.LayoutParams(0, dp(44), 1));
        sendPanel.addView(autoRow);

        autoSendBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (restoringConfig) {
                    return;
                }
                if (isChecked) {
                    startAutoSend();
                } else {
                    stopAutoSend();
                }
            }
        });

        LinearLayout themePanel = panel(content, "界面设置");
        RadioGroup themeGroup = horizontalRadioGroup();
        lightThemeRadio = radio("明亮主题");
        darkThemeRadio = radio("黑色主题");
        themeGroup.addView(lightThemeRadio);
        themeGroup.addView(darkThemeRadio);
        if (darkTheme) {
            darkThemeRadio.setChecked(true);
        } else {
            lightThemeRadio.setChecked(true);
        }
        themeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (restoringConfig) {
                return;
            }
            boolean selectedDark = checkedId == darkThemeRadio.getId();
            switchTheme(selectedDark);
        });
        themePanel.addView(themeGroup);

        LinearLayout aboutPanel = panel(content, "简介");
        TextView aboutText = body("版本：V3.0\n作者：EtherealXXX-glitch");
        aboutText.setTextColor(primaryTextColor());
        aboutPanel.addView(aboutText);

        return scrollView;
    }

    private View buildCommunicationPage() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(10), dp(10), dp(10), dp(10));
        content.setBackgroundColor(appBackgroundColor());
        customCommandInputs.clear();

        LinearLayout monitorPanel = panel(content, "通信日志");
        monitorPanel.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1
        ));
        LinearLayout topRow = row();
        counterText = body("");
        cacheText = body("");
        topRow.addView(counterText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        topRow.addView(cacheText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        monitorPanel.addView(topRow);

        logScroll = new ScrollView(this);
        logScroll.setFillViewport(true);
        logScroll.setBackground(box(logBackgroundColor(), terminalBorderColor(), 2));
        logText = body("");
        logText.setTextColor(logTextColor());
        logText.setTextSize(12);
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setGravity(Gravity.START | Gravity.TOP);
        logText.setBackgroundColor(logBackgroundColor());
        logText.setLineSpacing(dp(2), 1.0f);
        logText.setPadding(dp(12), dp(12), dp(12), dp(12));
        logScroll.addView(logText);
        monitorPanel.addView(logScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1
        ));

        LinearLayout logActions = row();
        Button clearLogButton = button("清空日志");
        Button copyLogButton = button("复制日志");
        Button resetCounterButton = button("复位计数");
        clearLogButton.setOnClickListener(v -> clearLog());
        copyLogButton.setOnClickListener(v -> copyText(logBuffer.allTextForCopy()));
        resetCounterButton.setOnClickListener(v -> {
            rxBytes = 0;
            txBytes = 0;
            updateCounters();
        });
        logActions.addView(clearLogButton, new LinearLayout.LayoutParams(0, dp(44), 1));
        logActions.addView(copyLogButton, new LinearLayout.LayoutParams(0, dp(44), 1));
        logActions.addView(resetCounterButton, new LinearLayout.LayoutParams(0, dp(44), 1));
        setRowMargins(logActions);
        monitorPanel.addView(logActions);

        ScrollView sendScroll = new ScrollView(this);
        sendScroll.setFillViewport(true);
        sendScroll.setBackgroundColor(appBackgroundColor());
        LinearLayout sendContent = new LinearLayout(this);
        sendContent.setOrientation(LinearLayout.VERTICAL);
        sendScroll.addView(sendContent);
        content.addView(sendScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1
        ));

        LinearLayout modePanel = panel(sendContent, "发送模式");
        sendModeSpinner = spinner(new String[]{"手动输入", "自定义指令"});
        sendModeSpinner.setOnItemSelectedListener(new SimpleItemSelectedListener() {
            @Override
            public void onItemSelected() {
                updateSendModeUi();
            }
        });
        modePanel.addView(labeled("选择发送来源", sendModeSpinner));

        manualSendPanel = panel(sendContent, "手动输入");
        sendInput = edit("", "发送内容");
        sendInput.setSingleLine(false);
        sendInput.setGravity(Gravity.TOP | Gravity.START);
        sendInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        manualSendPanel.addView(sendInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(96)
        ));

        LinearLayout sendRow = row();
        Button sendButton = button("发送");
        Button clearSendButton = button("清除输入");
        Button fileButton = button("发送文件");
        sendButton.setOnClickListener(v -> sendCurrentPayload(true));
        clearSendButton.setOnClickListener(v -> sendInput.setText(""));
        fileButton.setOnClickListener(v -> pickSendFile());
        sendRow.addView(sendButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        sendRow.addView(clearSendButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        sendRow.addView(fileButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        setRowMargins(sendRow);
        manualSendPanel.addView(sendRow);

        historySpinner = spinner(new String[]{"历史发送"});
        Button quickStatusButton = button("快捷命令");
        quickStatusButton.setOnClickListener(v -> sendInput.setText("{\"GetDevStatus\":{}}"));
        LinearLayout historyRow = row();
        historyRow.addView(historySpinner, new LinearLayout.LayoutParams(0, dp(48), 1));
        historyRow.addView(quickStatusButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        setRowMargins(historyRow);
        manualSendPanel.addView(historyRow);

        customCommandPanel = panel(sendContent, "自定义指令");
        customCommandList = new LinearLayout(this);
        customCommandList.setOrientation(LinearLayout.VERTICAL);
        customCommandPanel.addView(customCommandList);
        for (int i = 0; i < DEFAULT_CUSTOM_COMMAND_COUNT; i++) {
            addCustomCommandRow("");
        }
        Button addCommandButton = button("添加指令");
        addCommandButton.setOnClickListener(v -> {
            addCustomCommandRow("");
            saveConfig();
        });
        customCommandPanel.addView(addCommandButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
        ));
        updateSendModeUi();

        historySpinner.setOnItemSelectedListener(new SimpleItemSelectedListener() {
            @Override
            public void onItemSelected() {
                int position = historySpinner.getSelectedItemPosition();
                if (position > 0 && position - 1 < sendHistory.size()) {
                    sendInput.setText(sendHistory.get(position - 1));
                }
            }
        });
        return content;
    }

    private void addTab(String title, int index) {
        Button tab = button(title);
        tab.setTag(index);
        tab.setOnClickListener(v -> showPage(index));
        tab.setGravity(Gravity.CENTER);
        tabBar.addView(tab, new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                1
        ));
    }

    private void showPage(int index) {
        pageAnimating = false;
        selectedPage = index;
        connectionPage.animate().cancel();
        settingsPage.animate().cancel();
        communicationPage.animate().cancel();
        connectionPage.setTranslationX(0f);
        settingsPage.setTranslationX(0f);
        communicationPage.setTranslationX(0f);
        connectionPage.setAlpha(1f);
        settingsPage.setAlpha(1f);
        communicationPage.setAlpha(1f);
        connectionPage.setVisibility(index == PAGE_CONNECTION ? View.VISIBLE : View.GONE);
        settingsPage.setVisibility(index == PAGE_SETTINGS ? View.VISIBLE : View.GONE);
        communicationPage.setVisibility(index == PAGE_COMMUNICATION ? View.VISIBLE : View.GONE);
        updateTabState(index);
        if (index == PAGE_COMMUNICATION) {
            refreshLogDisplay();
        }
    }

    private void showPageAnimated(int index, boolean leftSwipe) {
        if (pageAnimating || index == selectedPage) {
            return;
        }
        View current = pageForIndex(selectedPage);
        View next = pageForIndex(index);
        if (current == null || next == null) {
            showPage(index);
            return;
        }

        int width = pageHost.getWidth() > 0 ? pageHost.getWidth() : getResources().getDisplayMetrics().widthPixels;
        float incomingX = leftSwipe ? width : -width;
        float outgoingX = leftSwipe ? -width : width;

        pageAnimating = true;
        selectedPage = index;
        updateTabState(index);
        if (index == PAGE_COMMUNICATION) {
            refreshLogDisplay();
        }

        current.animate().cancel();
        next.animate().cancel();
        next.setVisibility(View.VISIBLE);
        next.setAlpha(0.86f);
        next.setTranslationX(incomingX);
        next.bringToFront();

        AccelerateDecelerateInterpolator interpolator = new AccelerateDecelerateInterpolator();
        current.animate()
                .translationX(outgoingX)
                .alpha(0.72f)
                .setDuration(240)
                .setInterpolator(interpolator)
                .start();
        next.animate()
                .translationX(0f)
                .alpha(1f)
                .setDuration(240)
                .setInterpolator(interpolator)
                .withEndAction(() -> {
                    current.setVisibility(View.GONE);
                    current.setTranslationX(0f);
                    current.setAlpha(1f);
                    pageAnimating = false;
                })
                .start();
    }

    private View pageForIndex(int index) {
        if (index == PAGE_CONNECTION) {
            return connectionPage;
        }
        if (index == PAGE_SETTINGS) {
            return settingsPage;
        }
        if (index == PAGE_COMMUNICATION) {
            return communicationPage;
        }
        return null;
    }

    private void updateTabState(int index) {
        for (int i = 0; i < tabBar.getChildCount(); i++) {
            View child = tabBar.getChildAt(i);
            Object pageIndex = child.getTag();
            boolean selected = pageIndex instanceof Integer && ((Integer) pageIndex) == index;
            child.setBackgroundColor(selected ? accentColor() : sidebarColor());
            if (child instanceof Button) {
                ((Button) child).setTextColor(selected ? Color.WHITE : secondaryTextColor());
            }
        }
    }

    private boolean handlePageSwipe(MotionEvent event) {
        if (pageAnimating) {
            return false;
        }
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            swipeStartX = event.getX();
            swipeStartY = event.getY();
            return false;
        }
        if (event.getAction() != MotionEvent.ACTION_UP) {
            return false;
        }

        float deltaX = event.getX() - swipeStartX;
        float deltaY = event.getY() - swipeStartY;
        if (Math.abs(deltaX) < dp(SWIPE_MIN_DISTANCE_DP) || Math.abs(deltaY) > dp(SWIPE_MAX_OFF_AXIS_DP)) {
            return false;
        }
        boolean leftSwipe = deltaX < 0;
        showPageAnimated(nextPageForSwipe(leftSwipe), leftSwipe);
        return true;
    }

    private int nextPageForSwipe(boolean leftSwipe) {
        int position;
        if (selectedPage == PAGE_CONNECTION) {
            position = 0;
        } else if (selectedPage == PAGE_COMMUNICATION) {
            position = 1;
        } else {
            position = 2;
        }
        int nextPosition = leftSwipe ? position + 1 : position - 1;
        if (nextPosition < 0) {
            nextPosition = 2;
        } else if (nextPosition > 2) {
            nextPosition = 0;
        }
        if (nextPosition == 0) {
            return PAGE_CONNECTION;
        }
        if (nextPosition == 1) {
            return PAGE_COMMUNICATION;
        }
        return PAGE_SETTINGS;
    }

    private void switchTheme(boolean selectedDark) {
        if (darkTheme == selectedDark) {
            return;
        }
        saveConfig();
        darkTheme = selectedDark;
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean("dark_theme", darkTheme)
                .apply();
        buildUi();
        reloadLocalHosts();
        loadConfig();
        updateModeUi();
        updateCounters();
        showPage(selectedPage);
        refreshLogDisplay();
    }

    private void updateConnectionStatus(String detail) {
        if (stateText == null) {
            return;
        }
        int count = session.activeEndpointCount();
        boolean running = session.isRunning();
        boolean connected = count > 0;
        String title;
        if (!running) {
            title = "未连接";
        } else if (connected) {
            title = "已连接";
        } else {
            title = "监听中";
        }
        stateText.setText(title + "  " + detail);
        stateText.setTextColor(Color.WHITE);
        if (connectedCountText != null) {
            connectedCountText.setText("设备：" + count + " 台");
            connectedCountText.setTextColor(Color.WHITE);
        }
        if (openButton != null) {
            openButton.setText(running ? "关闭" : "打开");
        }
        View statusBox = (View) stateText.getParent();
        statusBox.setBackground(box(connected ? connectedColor() : (running ? listeningColor() : disconnectedColor()),
                connected ? connectedColor() : (running ? listeningColor() : disconnectedColor()), 2));
    }

    private void toggleSession() {
        if (session.isRunning()) {
            stopAutoSend();
            session.close(networkListener);
            return;
        }

        int mode = modeSpinner.getSelectedItemPosition();
        int remotePort = parsePort(text(remotePortInput, DEFAULT_PORT), 8866);
        int localPort = parsePort(text(localPortInput, DEFAULT_PORT), 8866);

        if (mode == 0) {
            String bindHost = selectedLocalHost();
            appendSystem("监听 TCP " + bindHost + ":" + localPort);
            session.openTcpServer(bindHost, localPort, networkListener);
        } else if (mode == 1) {
            String remoteHost = text(remoteHostInput, DEFAULT_REMOTE_HOST);
            appendSystem("连接 TCP " + remoteHost + ":" + remotePort);
            session.openTcpClient(remoteHost, remotePort, networkListener);
        } else {
            String remoteHost = text(remoteHostInput, DEFAULT_REMOTE_HOST);
            String bindHost = selectedLocalHost();
            appendSystem("打开 UDP " + bindHost + ":" + localPort + " -> " + remoteHost + ":" + remotePort);
            session.openUdp(bindHost, localPort, remoteHost, remotePort, udpBroadcastBox.isChecked(), networkListener);
        }
    }

    private void sendCurrentPayload(boolean showEmptyWarning) {
        String rawText = sendInput.getText().toString();
        if (rawText.length() == 0) {
            if (showEmptyWarning) {
                Toast.makeText(this, "请输入发送内容", Toast.LENGTH_SHORT).show();
            }
            return;
        }

        byte[] data;
        try {
            if (sendHexRadio.isChecked()) {
                data = HexCodec.parse(rawText);
            } else {
                String payload = sendEscapeBox.isChecked() ? HexCodec.unescape(rawText) : rawText;
                data = payload.getBytes(StandardCharsets.UTF_8);
            }
            data = applyAppendIfNeeded(data);
        } catch (IllegalArgumentException error) {
            Toast.makeText(this, error.getMessage(), Toast.LENGTH_SHORT).show();
            appendLogLine("ERR", error.getMessage());
            return;
        }

        rememberHistory(rawText);
        session.send(data, networkListener);
        appendSend(data);
    }

    private void sendCustomCommand(int index) {
        if (index < 0 || index >= customCommandInputs.size() || customCommandInputs.get(index) == null) {
            return;
        }
        String command = customCommandInputs.get(index).getText().toString();
        if (command.trim().length() == 0) {
            Toast.makeText(this, "请先编辑自定义指令", Toast.LENGTH_SHORT).show();
            return;
        }
        sendInput.setText(command);
        saveConfig();
        sendCurrentPayload(true);
    }

    private String defaultCustomCommand(int index) {
        return "";
    }

    private void addCustomCommandRow(String value) {
        if (customCommandList == null) {
            return;
        }
        int index = customCommandInputs.size();
        LinearLayout commandBlock = new LinearLayout(this);
        commandBlock.setOrientation(LinearLayout.VERTICAL);
        commandBlock.setPadding(0, dp(4), 0, dp(8));
        EditText commandInput = edit(value, "指令 " + (index + 1));
        customCommandInputs.add(commandInput);
        Button commandButton = button("发送这条指令");
        final int commandIndex = index;
        commandButton.setOnClickListener(v -> sendCustomCommand(commandIndex));
        commandBlock.addView(commandInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
        ));
        commandBlock.addView(commandButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(44)
        ));
        customCommandList.addView(commandBlock);
    }

    private void updateSendModeUi() {
        if (sendModeSpinner == null || manualSendPanel == null || customCommandPanel == null) {
            return;
        }
        boolean customMode = sendModeSpinner.getSelectedItemPosition() == 1;
        manualSendPanel.setVisibility(customMode ? View.GONE : View.VISIBLE);
        customCommandPanel.setVisibility(customMode ? View.VISIBLE : View.GONE);
    }

    private void sendFile(Uri uri) {
        try {
            byte[] data = applyAppendIfNeeded(readUri(uri));
            session.send(data, networkListener);
            appendSystem("发送文件: " + displayName(uri) + " (" + data.length + " bytes)");
        } catch (IOException error) {
            appendLogLine("ERR", "读取文件失败: " + error.getMessage());
            Toast.makeText(this, "读取文件失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void appendReceive(String remote, byte[] data) {
        String payload = receiveHexRadio.isChecked() ? HexCodec.toHex(data) : new String(data, StandardCharsets.UTF_8);
        if (receiveLogModeBox.isChecked()) {
            appendLogLine("RX " + remote, payload);
        } else {
            appendRaw(payload, receiveAutoLineBox.isChecked());
        }
    }

    private void appendSend(byte[] data) {
        String payload = sendHexRadio.isChecked() ? HexCodec.toHex(data) : new String(data, StandardCharsets.UTF_8);
        appendLogLine("TX", payload);
    }

    private void appendSystem(String text) {
        appendLogLine("SYS", text);
    }

    private void appendLogLine(String prefix, String text) {
        appendRaw("[" + HexCodec.now() + "] " + prefix + ": " + text, true);
    }

    private void appendRaw(String text, boolean newline) {
        String separator = newline ? "\n" : "";
        logBuffer.append(text + separator);
        refreshLogDisplay();
    }

    private void refreshLogDisplay() {
        if (logText == null || logBuffer == null) {
            return;
        }
        logText.setText(logBuffer.displayText());
        updateCacheText();
        if (receiveAutoLineBox == null || receiveAutoLineBox.isChecked()) {
            scrollLogToBottom();
        }
    }

    private void clearLog() {
        logBuffer.clear();
        logText.setText("");
        updateCacheText();
    }

    private void updateCacheText() {
        if (cacheText != null && logBuffer != null) {
            cacheText.setText("缓存: " + (logBuffer.size() / 1024) + "KB / 100MB");
        }
    }

    private void saveReceivedIfNeeded(byte[] data) {
        if (!receiveSaveBox.isChecked()) {
            return;
        }
        try {
            if (receiveFile == null) {
                File dir = new File(getExternalFilesDir(null), "received");
                if (!dir.exists() && !dir.mkdirs()) {
                    throw new IOException("无法创建保存目录");
                }
                receiveFile = new File(dir, "netassist_rx_" + System.currentTimeMillis() + ".bin");
                appendSystem("接收保存文件: " + receiveFile.getAbsolutePath());
            }
            FileOutputStream stream = new FileOutputStream(receiveFile, true);
            stream.write(data);
            stream.close();
        } catch (IOException error) {
            receiveSaveBox.setChecked(false);
            appendLogLine("ERR", "保存接收数据失败: " + error.getMessage());
        }
    }

    private byte[] applyAppendIfNeeded(byte[] data) {
        return sendAppendBox.isChecked() ? appendSettings.apply(data) : data;
    }

    private void saveConfig() {
        if (modeSpinner == null || sendInput == null) {
            return;
        }
        SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
        editor.putBoolean("configured", true);
        editor.putBoolean("dark_theme", darkTheme);
        editor.putInt("page", selectedPage);
        editor.putInt("mode", modeSpinner.getSelectedItemPosition());
        editor.putString("local_host", rawText(localHostInput));
        editor.putString("local_port", rawText(localPortInput));
        editor.putString("remote_host", rawText(remoteHostInput));
        editor.putString("remote_port", rawText(remotePortInput));
        editor.putBoolean("udp_broadcast", udpBroadcastBox.isChecked());
        editor.putBoolean("receive_hex", receiveHexRadio.isChecked());
        editor.putBoolean("receive_log_mode", receiveLogModeBox.isChecked());
        editor.putBoolean("receive_auto_line", receiveAutoLineBox.isChecked());
        editor.putBoolean("receive_hidden", receiveHiddenBox.isChecked());
        editor.putBoolean("receive_save", receiveSaveBox.isChecked());
        editor.putBoolean("send_hex", sendHexRadio.isChecked());
        editor.putBoolean("send_escape", sendEscapeBox.isChecked());
        editor.putBoolean("send_append", sendAppendBox.isChecked());
        editor.putBoolean("auto_send", autoSendBox.isChecked());
        editor.putString("auto_interval", rawText(intervalInput));
        editor.putString("send_text", sendInput.getText().toString());
        if (sendModeSpinner != null) {
            editor.putInt("send_mode", sendModeSpinner.getSelectedItemPosition());
        }
        editor.putInt("custom_command_count", customCommandInputs.size());
        for (int i = 0; i < customCommandInputs.size(); i++) {
            if (customCommandInputs.get(i) != null) {
                editor.putString("custom_command_" + i, customCommandInputs.get(i).getText().toString());
            }
        }
        editor.putInt("append_checksum", appendSettings.checksumMode);
        editor.putInt("append_tail", appendSettings.tailMode);
        editor.putInt("append_start", appendSettings.startOffset);
        editor.putInt("append_poly", appendSettings.crcPoly);
        editor.putInt("append_init", appendSettings.crcInit);
        editor.putInt("append_xor", appendSettings.crcXorOut);
        editor.putBoolean("append_high_first", appendSettings.highByteFirst);
        editor.putBoolean("append_input_reflect", appendSettings.inputReflect);
        editor.putBoolean("append_output_reflect", appendSettings.outputReflect);
        editor.putString("append_tail_hex", appendSettings.customTailHex);
        editor.apply();
    }

    private void loadConfig() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (!prefs.getBoolean("configured", false)) {
            return;
        }

        restoringConfig = true;
        try {
            selectedPage = boundIndex(prefs.getInt("page", 0), 3);
            modeSpinner.setSelection(boundIndex(prefs.getInt("mode", 0), modeSpinner.getCount()));
            localHostInput.setText(prefs.getString("local_host", ""));
            localPortInput.setText(prefs.getString("local_port", ""));
            remoteHostInput.setText(prefs.getString("remote_host", ""));
            remotePortInput.setText(prefs.getString("remote_port", ""));
            udpBroadcastBox.setChecked(prefs.getBoolean("udp_broadcast", false));

            boolean receiveHex = prefs.getBoolean("receive_hex", false);
            receiveHexRadio.setChecked(receiveHex);
            receiveAsciiRadio.setChecked(!receiveHex);
            receiveLogModeBox.setChecked(prefs.getBoolean("receive_log_mode", false));
            receiveAutoLineBox.setChecked(prefs.getBoolean("receive_auto_line", false));
            receiveHiddenBox.setChecked(prefs.getBoolean("receive_hidden", false));
            receiveSaveBox.setChecked(prefs.getBoolean("receive_save", false));

            boolean sendHex = prefs.getBoolean("send_hex", false);
            sendHexRadio.setChecked(sendHex);
            sendAsciiRadio.setChecked(!sendHex);
            sendEscapeBox.setChecked(prefs.getBoolean("send_escape", false));
            sendAppendBox.setChecked(prefs.getBoolean("send_append", false));
            autoSendBox.setChecked(prefs.getBoolean("auto_send", false));
            intervalInput.setText(prefs.getString("auto_interval", ""));
            sendInput.setText(prefs.getString("send_text", ""));
            if (sendModeSpinner != null) {
                sendModeSpinner.setSelection(boundIndex(prefs.getInt("send_mode", 0), sendModeSpinner.getCount()));
            }
            int commandCount = Math.max(DEFAULT_CUSTOM_COMMAND_COUNT, prefs.getInt("custom_command_count", DEFAULT_CUSTOM_COMMAND_COUNT));
            while (customCommandInputs.size() < commandCount) {
                addCustomCommandRow("");
            }
            for (int i = 0; i < customCommandInputs.size(); i++) {
                customCommandInputs.get(i).setText(prefs.getString("custom_command_" + i, defaultCustomCommand(i)));
            }
            updateSendModeUi();
            lightThemeRadio.setChecked(!darkTheme);
            darkThemeRadio.setChecked(darkTheme);

            appendSettings.checksumMode = boundIndex(prefs.getInt("append_checksum", 0), AppendSettings.CHECKSUM_NAMES.length);
            appendSettings.tailMode = boundIndex(prefs.getInt("append_tail", 0), AppendSettings.TAIL_NAMES.length);
            appendSettings.startOffset = Math.max(0, prefs.getInt("append_start", 0));
            appendSettings.crcPoly = prefs.getInt("append_poly", 0xA001) & 0xFFFF;
            appendSettings.crcInit = prefs.getInt("append_init", 0xFFFF) & 0xFFFF;
            appendSettings.crcXorOut = prefs.getInt("append_xor", 0) & 0xFFFF;
            appendSettings.highByteFirst = prefs.getBoolean("append_high_first", false);
            appendSettings.inputReflect = prefs.getBoolean("append_input_reflect", false);
            appendSettings.outputReflect = prefs.getBoolean("append_output_reflect", false);
            appendSettings.customTailHex = prefs.getString("append_tail_hex", "");
            appendSummaryText.setText(appendSettings.summary());
        } finally {
            restoringConfig = false;
        }
    }

    private int boundIndex(int value, int count) {
        if (count <= 0) {
            return 0;
        }
        if (value < 0) {
            return 0;
        }
        return Math.min(value, count - 1);
    }

    private void showAppendSettingsDialog() {
        ScrollView scrollView = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14), dp(8), dp(14), dp(8));
        scrollView.addView(content);

        Spinner checksumSpinner = spinner(AppendSettings.CHECKSUM_NAMES);
        checksumSpinner.setSelection(appendSettings.checksumMode);
        CheckBox highByteBox = checkbox("高字节在前");
        highByteBox.setChecked(appendSettings.highByteFirst);
        content.addView(labeled("帧尾和校验码", checksumSpinner));
        content.addView(highByteBox);

        LinearLayout crcRow1 = row();
        EditText polyInput = edit(hex4(appendSettings.crcPoly), "多项式");
        EditText initInput = edit(hex4(appendSettings.crcInit), "初始值");
        crcRow1.addView(polyInput, new LinearLayout.LayoutParams(0, dp(48), 1));
        crcRow1.addView(initInput, new LinearLayout.LayoutParams(0, dp(48), 1));
        setRowMargins(crcRow1);
        content.addView(crcRow1);

        LinearLayout crcRow2 = row();
        CheckBox inputReflectBox = checkbox("输入反转");
        CheckBox outputReflectBox = checkbox("输出反转");
        inputReflectBox.setChecked(appendSettings.inputReflect);
        outputReflectBox.setChecked(appendSettings.outputReflect);
        EditText xorOutInput = edit(hex4(appendSettings.crcXorOut), "结果异或");
        crcRow2.addView(inputReflectBox, new LinearLayout.LayoutParams(0, dp(42), 1));
        crcRow2.addView(outputReflectBox, new LinearLayout.LayoutParams(0, dp(42), 1));
        crcRow2.addView(xorOutInput, new LinearLayout.LayoutParams(0, dp(48), 1));
        content.addView(crcRow2);

        LinearLayout rangeRow = row();
        EditText startInput = edit(String.valueOf(appendSettings.startOffset), "开始字节");
        startInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        TextView rangeHint = body("结束于末尾，校验码自动加在帧尾前");
        rangeRow.addView(startInput, new LinearLayout.LayoutParams(0, dp(48), 0.7f));
        rangeRow.addView(rangeHint, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f));
        content.addView(rangeRow);

        Spinner tailSpinner = spinner(AppendSettings.TAIL_NAMES);
        tailSpinner.setSelection(appendSettings.tailMode);
        EditText tailInput = edit(appendSettings.customTailHex, "自定义帧尾HEX");
        content.addView(labeled("帧尾结束符", tailSpinner));
        content.addView(tailInput, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("附加位设置")
                .setView(scrollView)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", null)
                .create();
        dialog.setOnShowListener(dialogInterface -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                appendSettings.checksumMode = checksumSpinner.getSelectedItemPosition();
                appendSettings.tailMode = tailSpinner.getSelectedItemPosition();
                appendSettings.highByteFirst = highByteBox.isChecked();
                appendSettings.inputReflect = inputReflectBox.isChecked();
                appendSettings.outputReflect = outputReflectBox.isChecked();
                appendSettings.startOffset = Math.max(0, parseInt(text(startInput, "0"), 0));
                appendSettings.crcPoly = parseHex16(text(polyInput, "A001"));
                appendSettings.crcInit = parseHex16(text(initInput, "FFFF"));
                appendSettings.crcXorOut = parseHex16(text(xorOutInput, "0000"));
                appendSettings.customTailHex = text(tailInput, "");
                if (appendSettings.tailMode == 4) {
                    HexCodec.parse(appendSettings.customTailHex);
                }
                appendSummaryText.setText(appendSettings.summary());
                sendAppendBox.setChecked(true);
                appendSystem(appendSettings.summary());
                dialog.dismiss();
            } catch (IllegalArgumentException error) {
                Toast.makeText(this, error.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }));
        dialog.show();
    }

    private void startAutoSend() {
        stopAutoSend();
        handler.postDelayed(autoSendRunnable, autoSendIntervalMs());
        appendSystem("循环发送已开启: " + autoSendIntervalMs() + " ms");
    }

    private void stopAutoSend() {
        handler.removeCallbacks(autoSendRunnable);
        if (autoSendBox != null && autoSendBox.isChecked()) {
            autoSendBox.setChecked(false);
        }
    }

    private int autoSendIntervalMs() {
        int value = parsePort(text(intervalInput, "1000"), 1000);
        return Math.max(AUTO_SEND_MIN_MS, value);
    }

    private void updateModeUi() {
        if (remoteHostInput == null) {
            return;
        }
        int mode = modeSpinner.getSelectedItemPosition();
        boolean server = mode == 0;
        boolean udp = mode == 2;
        boolean client = mode == 1;
        localHostSpinner.setEnabled(server || udp);
        localHostInput.setEnabled(server || udp);
        remoteHostInput.setEnabled(!server);
        remotePortInput.setEnabled(!server);
        localPortInput.setEnabled(server || udp);
        udpBroadcastBox.setEnabled(udp);
        localHostListBlock.setVisibility((server || udp) ? View.VISIBLE : View.GONE);
        localHostInputBlock.setVisibility((server || udp) ? View.VISIBLE : View.GONE);
        localPortBlock.setVisibility((server || udp) ? View.VISIBLE : View.GONE);
        remoteHostBlock.setVisibility((client || udp) ? View.VISIBLE : View.GONE);
        remotePortBlock.setVisibility((client || udp) ? View.VISIBLE : View.GONE);
        udpBroadcastBox.setVisibility(udp ? View.VISIBLE : View.GONE);

        if (server) {
            updateConnectionStatus("TCP Server 就绪");
        } else if (udp) {
            updateConnectionStatus("UDP 就绪");
        } else {
            updateConnectionStatus("TCP Client 就绪");
        }
    }

    private void reloadLocalHosts() {
        List<String> hosts = new ArrayList<>();
        hosts.add(DEFAULT_BIND_HOST);
        hosts.add("127.0.0.1");
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                    continue;
                }
                Enumeration<java.net.InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    java.net.InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        String host = address.getHostAddress();
                        if (!hosts.contains(host)) {
                            hosts.add(host);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        if (localHostSpinner != null) {
            String currentHostText = localHostInput == null ? "" : localHostInput.getText().toString();
            reloadingLocalHosts = true;
            localHostSpinner.setAdapter(spinnerAdapter(hosts));
            reloadingLocalHosts = false;
            if (localHostInput != null) {
                localHostInput.setText(currentHostText);
            }
        }
    }

    private String selectedLocalHost() {
        String value = localHostInput.getText().toString().trim();
        return value.length() > 0 ? value : DEFAULT_BIND_HOST;
    }

    private void rememberHistory(String text) {
        if (text.trim().length() == 0) {
            return;
        }
        sendHistory.remove(text);
        sendHistory.add(0, text);
        while (sendHistory.size() > 20) {
            sendHistory.remove(sendHistory.size() - 1);
        }
        List<String> items = new ArrayList<>();
        items.add("历史发送");
        items.addAll(sendHistory);
        historySpinner.setAdapter(spinnerAdapter(items));
    }

    private byte[] readUri(Uri uri) throws IOException {
        InputStream inputStream = getContentResolver().openInputStream(uri);
        if (inputStream == null) {
            throw new IOException("无法打开文件");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = inputStream.read(buffer)) >= 0) {
            if (count > 0) {
                output.write(buffer, 0, count);
            }
        }
        inputStream.close();
        return output.toByteArray();
    }

    private String displayName(Uri uri) {
        Cursor cursor = getContentResolver().query(uri, null, null, null, null);
        if (cursor != null) {
            try {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0 && cursor.moveToFirst()) {
                    return cursor.getString(index);
                }
            } finally {
                cursor.close();
            }
        }
        return uri.getLastPathSegment() == null ? "file" : uri.getLastPathSegment();
    }

    private void pickSendFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, PICK_SEND_FILE);
    }

    private void updateCounters() {
        if (counterText != null) {
            counterText.setText("RX: " + rxBytes + "    TX: " + txBytes);
        }
        updateCacheText();
    }

    private String text(EditText editText, String fallback) {
        String value = editText.getText().toString().trim();
        return value.isEmpty() ? fallback : value;
    }

    private String rawText(EditText editText) {
        return editText.getText().toString().trim();
    }

    private int parsePort(String value, int fallback) {
        try {
            int port = Integer.parseInt(value);
            return port >= 0 && port <= 65535 ? port : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private int parseHex16(String value) {
        String normalized = value.trim().replace("0x", "").replace("0X", "");
        if (normalized.length() == 0) {
            return 0;
        }
        try {
            int parsed = Integer.parseInt(normalized, 16);
            if (parsed < 0 || parsed > 0xFFFF) {
                throw new IllegalArgumentException("HEX参数必须在0000到FFFF之间");
            }
            return parsed;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("HEX参数格式错误");
        }
    }

    private String hex4(int value) {
        String hex = Integer.toHexString(value & 0xFFFF).toUpperCase(java.util.Locale.US);
        while (hex.length() < 4) {
            hex = "0" + hex;
        }
        return hex;
    }

    private void scrollLogToBottom() {
        if (logScroll != null) {
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    private void copyText(String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("NetAssistLog", text));
        Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
    }

    private void applySystemBars() {
        getWindow().setStatusBarColor(titleBarColor());
        getWindow().setNavigationBarColor(appBackgroundColor());

        int flags = 0;
        if (!darkTheme) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
        }
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    private LinearLayout panel(LinearLayout parent, String title) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(10), dp(7), dp(10), dp(10));
        panel.setBackground(box(panelBackgroundColor(), borderColor(), 0));

        TextView titleView = panelTitle(title);
        panel.addView(titleView);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 0, 0, dp(10));
        parent.addView(panel, params);
        return panel;
    }

    private ScrollView pageScroll() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(appBackgroundColor());
        return scrollView;
    }

    private LinearLayout pageContent(ScrollView scrollView) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(10), dp(10), dp(10), dp(20));
        content.setBackgroundColor(appBackgroundColor());
        scrollView.addView(content);
        return content;
    }

    private TextView title(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(20);
        view.setGravity(Gravity.START);
        view.setSingleLine(false);
        return view;
    }

    private TextView panelTitle(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(13);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setTextColor(panelTitleColor());
        view.setPadding(0, 0, 0, dp(8));
        return view;
    }

    private TextView body(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(14);
        view.setTextColor(primaryTextColor());
        view.setPadding(0, dp(6), 0, dp(6));
        return view;
    }

    private EditText edit(String text, String hint) {
        EditText editText = new EditText(this);
        editText.setText(text);
        editText.setHint(hint);
        editText.setSingleLine(true);
        editText.setTextSize(14);
        editText.setTextColor(primaryTextColor());
        editText.setHintTextColor(mutedTextColor());
        editText.setBackground(box(inputBackgroundColor(), borderColor(), 2));
        editText.setSelectAllOnFocus(false);
        editText.setPadding(dp(10), 0, dp(10), 0);
        return editText;
    }

    private Spinner spinner(String[] values) {
        Spinner spinner = new Spinner(this);
        spinner.setAdapter(spinnerAdapter(values));
        spinner.setBackground(box(inputBackgroundColor(), borderColor(), 2));
        return spinner;
    }

    private ArrayAdapter<String> spinnerAdapter(String[] values) {
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, values) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                view.setTextColor(primaryTextColor());
                view.setTextSize(14);
                view.setBackgroundColor(inputBackgroundColor());
                return view;
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getDropDownView(position, convertView, parent);
                view.setTextColor(primaryTextColor());
                view.setBackgroundColor(panelBackgroundColor());
                view.setTextSize(14);
                return view;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return adapter;
    }

    private ArrayAdapter<String> spinnerAdapter(List<String> values) {
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, values) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                view.setTextColor(primaryTextColor());
                view.setBackgroundColor(inputBackgroundColor());
                view.setTextSize(14);
                return view;
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getDropDownView(position, convertView, parent);
                view.setTextColor(primaryTextColor());
                view.setBackgroundColor(panelBackgroundColor());
                view.setTextSize(14);
                return view;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return adapter;
    }

    private RadioButton radio(String text) {
        RadioButton radioButton = new RadioButton(this);
        radioButton.setId(View.generateViewId());
        radioButton.setText(text);
        radioButton.setTextSize(14);
        radioButton.setTextColor(primaryTextColor());
        radioButton.setButtonTintList(controlTint());
        return radioButton;
    }

    private RadioGroup horizontalRadioGroup() {
        RadioGroup group = new RadioGroup(this);
        group.setOrientation(RadioGroup.HORIZONTAL);
        group.setPadding(0, dp(2), 0, dp(2));
        return group;
    }

    private Button button(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTextColor(Color.WHITE);
        button.setBackground(box(accentColor(), accentColor(), 2));
        return button;
    }

    private CheckBox checkbox(String text) {
        CheckBox checkBox = new CheckBox(this);
        checkBox.setText(text);
        checkBox.setTextSize(14);
        checkBox.setTextColor(primaryTextColor());
        checkBox.setButtonTintList(controlTint());
        return checkBox;
    }

    private LinearLayout labeled(String label, View child) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        TextView text = body(label);
        text.setTextColor(secondaryTextColor());
        layout.addView(text);
        layout.addView(child, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        return layout;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(3), 0, dp(3));
        return row;
    }

    private void setRowMargins(LinearLayout row) {
        for (int i = 0; i < row.getChildCount(); i++) {
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) row.getChildAt(i).getLayoutParams();
            params.setMargins(dp(3), 0, dp(3), 0);
            row.getChildAt(i).setLayoutParams(params);
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable box(int fillColor, int strokeColor, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fillColor);
        drawable.setStroke(dp(1), strokeColor);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private ColorStateList controlTint() {
        int[][] states = new int[][]{
                new int[]{android.R.attr.state_checked},
                new int[]{-android.R.attr.state_enabled},
                new int[]{}
        };
        int[] colors = new int[]{
                checkedControlColor(),
                disabledControlColor(),
                uncheckedControlColor()
        };
        return new ColorStateList(states, colors);
    }

    private int titleBarColor() {
        return darkTheme ? Color.rgb(8, 15, 24) : Color.rgb(250, 252, 255);
    }

    private int titleTextColor() {
        return darkTheme ? Color.rgb(238, 246, 255) : Color.rgb(17, 32, 48);
    }

    private int sidebarColor() {
        return darkTheme ? Color.rgb(14, 25, 38) : Color.rgb(225, 235, 245);
    }

    private int appBackgroundColor() {
        return darkTheme ? Color.rgb(10, 18, 28) : Color.rgb(238, 244, 250);
    }

    private int panelBackgroundColor() {
        return darkTheme ? Color.rgb(18, 30, 44) : Color.rgb(255, 255, 255);
    }

    private int inputBackgroundColor() {
        return darkTheme ? Color.rgb(11, 22, 34) : Color.rgb(248, 251, 254);
    }

    private int logBackgroundColor() {
        return darkTheme ? Color.rgb(6, 13, 20) : Color.rgb(247, 250, 253);
    }

    private int primaryTextColor() {
        return darkTheme ? Color.rgb(231, 239, 247) : Color.rgb(20, 35, 52);
    }

    private int secondaryTextColor() {
        return darkTheme ? Color.rgb(169, 187, 202) : Color.rgb(74, 91, 108);
    }

    private int mutedTextColor() {
        return darkTheme ? Color.rgb(114, 137, 157) : Color.rgb(125, 143, 160);
    }

    private int logTextColor() {
        return darkTheme ? Color.rgb(202, 219, 232) : Color.rgb(31, 48, 66);
    }

    private int terminalBorderColor() {
        return darkTheme ? Color.rgb(49, 79, 103) : Color.rgb(178, 194, 211);
    }

    private int borderColor() {
        return darkTheme ? Color.rgb(52, 76, 98) : Color.rgb(186, 203, 220);
    }

    private int accentColor() {
        return darkTheme ? Color.rgb(20, 151, 176) : Color.rgb(18, 116, 150);
    }

    private int checkedControlColor() {
        return darkTheme ? Color.rgb(92, 225, 245) : Color.rgb(17, 118, 156);
    }

    private int uncheckedControlColor() {
        return darkTheme ? Color.rgb(183, 207, 224) : Color.rgb(78, 101, 120);
    }

    private int disabledControlColor() {
        return darkTheme ? Color.rgb(88, 107, 123) : Color.rgb(156, 170, 184);
    }

    private int panelTitleColor() {
        return darkTheme ? Color.rgb(120, 213, 232) : Color.rgb(17, 92, 122);
    }

    private int connectedColor() {
        return Color.rgb(30, 145, 80);
    }

    private int listeningColor() {
        return Color.rgb(193, 126, 31);
    }

    private int disconnectedColor() {
        return Color.rgb(173, 52, 62);
    }

    private abstract static class SimpleItemSelectedListener implements AdapterView.OnItemSelectedListener {
        @Override
        public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            onItemSelected();
        }

        @Override
        public void onNothingSelected(AdapterView<?> parent) {
        }

        public abstract void onItemSelected();
    }
}
