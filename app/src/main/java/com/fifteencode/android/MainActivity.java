package com.fifteencode.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.graphics.BitmapFactory;
import android.graphics.drawable.ColorDrawable;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.PopupWindow;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ArrayAdapter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String PLATFORM = "https://15code.com";
    private static final String CATALOG = PLATFORM + "/api/catalog";
    private static final String LLM = "https://cli.15code.com/v1/chat/completions";
    private static final String IMAGE_GENERATIONS = "https://cli.15code.com/v1/images/generations";
    private static final String IMAGE_EDITS = "https://cli.15code.com/v1/images/edits";
    private static final String IMAGE_PRICING = PLATFORM + "/api/image-pricing";
    private static final String SEARCH_CHAT = PLATFORM + "/api/search-chat";
    private static final String ANDROID_RELEASES = "https://github.com/zpf000zpf/15code-android/releases";
    private static final String ANDROID_LATEST_RELEASE = "https://api.github.com/repos/zpf000zpf/15code-android/releases/latest";
    private static final String PREFS = "15code_android";
    private static final String APP_VERSION = "1.4.5";
    private static final int SUPPORTED_CATALOG_SCHEMA_VERSION = 1;
    private static final String PREFERRED_MODEL = "qwen3.6";
    private static final int PICK_IMAGE_REQUEST = 7301;
    private static final int MAX_HISTORY_MESSAGES = 20;
    private static final int MAX_IMAGE_BYTES = 4 * 1024 * 1024;
    private static final long STREAM_RENDER_INTERVAL_MS = 100;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private SecurePreferences securePrefs;
    private ChatDao chatDao;
    private final ExecutorService storageExecutor = Executors.newSingleThreadExecutor();
    private String currentConversationId;
    private String currentDraft = "";
    private volatile boolean historyLoadStarted;
    private String sessionToken;
    private String goKey;
    private String selectedModel;
    private String accountEmail;
    private String selectedImageDataUrl;
    private String selectedImageName;
    private boolean forceWebSearch;
    private double credits;
    private volatile boolean streaming;
    private volatile boolean stopRequested;
    private volatile HttpURLConnection activeChatConnection;
    private volatile String catalogWarning;
    private volatile String catalogLatestVersion;
    private volatile String catalogDownloadUrl;
    private volatile String catalogMinimumVersion;
    private volatile String catalogForceUpgradeVersion;
    private final List<Model> models = new ArrayList<>();
    private final JSONArray messages = new JSONArray();

    private LinearLayout root;
    private LinearLayout loginPanel;
    private LinearLayout chatPanel;
    private LinearLayout messageList;
    private LinearLayout composer;
    private EditText emailInput;
    private EditText passwordInput;
    private EditText promptInput;
    private Button modelButton;
    private TextView statusText;
    private TextView accountText;
    private Button newChatButton;
    private Button imageStudioButton;
    private Button menuButton;
    private Button searchButton;
    private Button attachButton;
    private Button sendButton;
    private LinearLayout attachmentPreview;
    private ImageView attachmentImage;
    private ProgressBar progress;
    private ScrollView scroll;
    private long lastStreamRenderAt;
    private Runnable pendingStreamRender;
    private String pendingStreamText = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        securePrefs = new SecurePreferences(this);
        migratePlaintextCredentials();
        sessionToken = securePrefs.get("sessionToken");
        goKey = securePrefs.get("goKey");
        chatDao = ChatDatabase.get(this).chatDao();
        currentConversationId = prefs.getString("currentConversationId", "");
        if (currentConversationId == null || currentConversationId.isEmpty()) {
            currentConversationId = UUID.randomUUID().toString();
            prefs.edit().putString("currentConversationId", currentConversationId).apply();
        }
        selectedModel = prefs.getString("model", null);
        accountEmail = prefs.getString("accountEmail", "");
        try { credits = Double.parseDouble(prefs.getString("creditsUsd", "0")); }
        catch (Exception ignored) { credits = 0; }
        loadCachedModels();
        if (selectedModel == null || selectedModel.isEmpty()) selectedModel = PREFERRED_MODEL;
        buildUi();
        if (isDebugBuild() && getIntent().getBooleanExtra("smokeComposer", false)) {
            showSmokeComposer();
        } else {
            if (sessionToken != null) restoreSession();
            root.postDelayed(() -> checkAppUpdate(false), 1800);
        }
    }

    @Override
    protected void onDestroy() {
        storageExecutor.shutdown();
        super.onDestroy();
    }

    private void migratePlaintextCredentials() {
        String oldSession = prefs.getString("sessionToken", null);
        String oldGoKey = prefs.getString("goKey", null);
        if (securePrefs.get("sessionToken") == null && oldSession != null) securePrefs.put("sessionToken", oldSession);
        if (securePrefs.get("goKey") == null && oldGoKey != null) securePrefs.put("goKey", oldGoKey);
        prefs.edit().remove("sessionToken").remove("goKey").apply();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_IMAGE_REQUEST || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        try {
            Uri uri = data.getData();
            String mime = getContentResolver().getType(uri);
            if (mime == null || !mime.startsWith("image/")) mime = "image/jpeg";
            byte[] bytes = readLimitedBytes(uri, MAX_IMAGE_BYTES);
            selectedImageDataUrl = "data:" + mime + ";base64," + Base64.encodeToString(bytes, Base64.NO_WRAP);
            selectedImageName = "图片";
            updateAttachmentPreview();
            boolean switched = selectVisionModelForImage();
            Model imageModel = findModel(selectedModel);
            if (imageModel == null || !imageModel.isAvailable() || !imageModel.vision) {
                throw new Exception("当前目录没有可用的图片模型");
            }
            statusText.setText(switched
                    ? "已附加图片 · 已切换到 " + modelLabel(selectedModel)
                    : "已附加图片 · 当前模型 " + modelLabel(selectedModel));
        } catch (Exception e) {
            selectedImageDataUrl = null;
            selectedImageName = null;
            updateAttachmentPreview();
            toast(e.getMessage());
        }
    }

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF6F8FB);
        setContentView(root);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(12), dp(12), dp(12));
        header.setBackgroundColor(0xFF111827);
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(60)));

        TextView title = new TextView(this);
        title.setText("15code");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(21);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(title, new LinearLayout.LayoutParams(0, -1, 1));

        newChatButton = new Button(this);
        newChatButton.setText("＋");
        newChatButton.setAllCaps(false);
        newChatButton.setTextSize(22);
        newChatButton.setContentDescription("新建对话");
        newChatButton.setOnClickListener(v -> newChat());
        header.addView(newChatButton, new LinearLayout.LayoutParams(dp(52), dp(42)));

        imageStudioButton = new Button(this);
        imageStudioButton.setText("图片");
        imageStudioButton.setAllCaps(false);
        imageStudioButton.setTextSize(13);
        imageStudioButton.setContentDescription("图片生成与编辑");
        imageStudioButton.setOnClickListener(v -> {
            if (sessionToken == null || goKey == null) {
                toast("请先登录后使用图片生成");
                return;
            }
            showImageStudioDialog();
        });
        header.addView(imageStudioButton, new LinearLayout.LayoutParams(dp(62), dp(42)));

        menuButton = new Button(this);
        menuButton.setText("⋮");
        menuButton.setAllCaps(false);
        menuButton.setTextSize(22);
        menuButton.setContentDescription("更多操作");
        menuButton.setOnClickListener(this::showHeaderMenu);
        header.addView(menuButton, new LinearLayout.LayoutParams(dp(52), dp(42)));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setVisibility(View.GONE);
        root.addView(progress, new LinearLayout.LayoutParams(-1, dp(3)));

        statusText = new TextView(this);
        statusText.setTextColor(0xFF64748B);
        statusText.setTextSize(13);
        statusText.setPadding(dp(16), dp(8), dp(16), dp(8));
        root.addView(statusText, new LinearLayout.LayoutParams(-1, dp(40)));

        buildLoginPanel();
        buildChatPanel();
        showLogin();
    }

    private void buildLoginPanel() {
        loginPanel = new LinearLayout(this);
        loginPanel.setOrientation(LinearLayout.VERTICAL);
        loginPanel.setPadding(dp(22), dp(28), dp(22), dp(22));
        root.addView(loginPanel, new LinearLayout.LayoutParams(-1, 0, 1));

        TextView headline = new TextView(this);
        headline.setText("登录 15code");
        headline.setTextSize(26);
        headline.setTextColor(0xFF111827);
        headline.setTypeface(Typeface.DEFAULT_BOLD);
        headline.setGravity(Gravity.CENTER_HORIZONTAL);
        loginPanel.addView(headline, new LinearLayout.LayoutParams(-1, dp(54)));

        emailInput = field("邮箱", false);
        passwordInput = field("密码", true);

        Button loginButton = new Button(this);
        loginButton.setText("登录");
        loginButton.setAllCaps(false);
        loginButton.setTextSize(16);
        loginButton.setOnClickListener(v -> login());
        loginPanel.addView(loginButton, new LinearLayout.LayoutParams(-1, dp(52)));

        TextView hint = new TextView(this);
        hint.setText("15code 账号");
        hint.setTextColor(0xFF64748B);
        hint.setTextSize(13);
        hint.setPadding(0, dp(16), 0, 0);
        loginPanel.addView(hint);
    }

    private EditText field(String hint, boolean password) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setSingleLine(true);
        input.setTextSize(16);
        input.setPadding(dp(12), 0, dp(12), 0);
        input.setInputType(password
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        loginPanel.addView(input, new LinearLayout.LayoutParams(-1, dp(56)));
        return input;
    }

    private void buildChatPanel() {
        chatPanel = new LinearLayout(this);
        chatPanel.setOrientation(LinearLayout.VERTICAL);
        chatPanel.setPadding(dp(12), dp(8), dp(12), dp(8));
        root.addView(chatPanel, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout accountRow = new LinearLayout(this);
        accountRow.setOrientation(LinearLayout.HORIZONTAL);
        accountRow.setGravity(Gravity.CENTER_VERTICAL);
        accountRow.setPadding(dp(4), 0, dp(4), dp(6));
        chatPanel.addView(accountRow, new LinearLayout.LayoutParams(-1, dp(58)));

        accountText = new TextView(this);
        accountText.setTextColor(0xFF475569);
        accountText.setTextSize(13);
        accountText.setGravity(Gravity.CENTER_VERTICAL);
        accountRow.addView(accountText, new LinearLayout.LayoutParams(0, -1, 1));

        modelButton = new Button(this);
        modelButton.setText("选择模型");
        modelButton.setAllCaps(false);
        modelButton.setGravity(Gravity.CENTER);
        modelButton.setPadding(dp(12), 0, dp(12), 0);
        modelButton.setTextColor(0xFF0F172A);
        modelButton.setBackground(makeBg(0xFFFFFFFF, 0xFFE2E8F0, dp(12)));
        modelButton.setOnClickListener(v -> showModelPicker());
        accountRow.addView(modelButton, new LinearLayout.LayoutParams(dp(190), dp(46)));

        scroll = new ScrollView(this);
        messageList = new LinearLayout(this);
        messageList.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(messageList, new ScrollView.LayoutParams(-1, -2));
        chatPanel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        attachmentPreview = new LinearLayout(this);
        attachmentPreview.setOrientation(LinearLayout.HORIZONTAL);
        attachmentPreview.setGravity(Gravity.CENTER_VERTICAL);
        attachmentPreview.setPadding(dp(8), dp(6), dp(8), dp(6));
        attachmentPreview.setBackground(makeBg(0xFFFFFFFF, 0xFFBFDBFE, dp(14)));
        attachmentPreview.setVisibility(View.GONE);

        attachmentImage = new ImageView(this);
        attachmentImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        attachmentPreview.addView(attachmentImage, new LinearLayout.LayoutParams(dp(52), dp(52)));

        TextView attachmentLabel = new TextView(this);
        attachmentLabel.setText("图片已附加 · 将使用视觉模型");
        attachmentLabel.setTextColor(0xFF334155);
        attachmentLabel.setTextSize(13);
        attachmentLabel.setPadding(dp(10), 0, dp(8), 0);
        attachmentPreview.addView(attachmentLabel, new LinearLayout.LayoutParams(0, -1, 1));

        Button removeAttachment = new Button(this);
        removeAttachment.setText("移除");
        removeAttachment.setAllCaps(false);
        removeAttachment.setOnClickListener(v -> clearSelectedImage());
        attachmentPreview.addView(removeAttachment, new LinearLayout.LayoutParams(dp(72), dp(44)));
        LinearLayout.LayoutParams previewLp = new LinearLayout.LayoutParams(-1, dp(66));
        previewLp.setMargins(0, dp(6), 0, 0);
        chatPanel.addView(attachmentPreview, previewLp);

        composer = new LinearLayout(this);
        composer.setOrientation(LinearLayout.HORIZONTAL);
        composer.setGravity(Gravity.BOTTOM);
        composer.setPadding(0, dp(8), 0, 0);
        chatPanel.addView(composer, new LinearLayout.LayoutParams(-1, dp(72)));

        promptInput = new EditText(this);
        promptInput.setHint("发消息给 15code");
        promptInput.setContentDescription("chat-composer-input");
        promptInput.setTextColor(0xFF111827);
        promptInput.setHintTextColor(0xFF94A3B8);
        promptInput.setTextSize(16);
        promptInput.setMinLines(1);
        promptInput.setMaxLines(3);
        promptInput.setSingleLine(false);
        promptInput.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        promptInput.setPadding(dp(14), 0, dp(14), 0);
        promptInput.setFocusable(false);
        promptInput.setFocusableInTouchMode(false);
        promptInput.setCursorVisible(false);
        promptInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        promptInput.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        promptInput.setBackground(makeBg(0xFFFFFFFF, 0xFFCBD5E1, dp(18)));
        promptInput.setOnClickListener(v -> openComposerDialog());
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(0, dp(56), 1);
        inputLp.setMargins(0, 0, dp(8), 0);
        composer.addView(promptInput, inputLp);
        composer.setOnClickListener(v -> openComposerDialog());

        searchButton = new Button(this);
        searchButton.setText("联网");
        searchButton.setAllCaps(false);
        searchButton.setTextSize(13);
        searchButton.setOnClickListener(v -> {
            forceWebSearch = !forceWebSearch;
            updateSearchButton();
        });
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(dp(58), dp(56));
        searchLp.setMargins(0, 0, dp(8), 0);
        composer.addView(searchButton, searchLp);
        updateSearchButton();

        attachButton = new Button(this);
        attachButton.setText("+");
        attachButton.setAllCaps(false);
        attachButton.setTextColor(0xFF0F172A);
        attachButton.setBackground(makeBg(0xFFFFFFFF, 0xFFCBD5E1, dp(18)));
        attachButton.setOnClickListener(v -> pickImage());
        LinearLayout.LayoutParams attachLp = new LinearLayout.LayoutParams(dp(48), dp(56));
        attachLp.setMargins(0, 0, dp(8), 0);
        composer.addView(attachButton, attachLp);

        sendButton = new Button(this);
        sendButton.setText("发送");
        sendButton.setAllCaps(false);
        sendButton.setTextColor(0xFFFFFFFF);
        sendButton.setBackground(makeBg(0xFF2563EB, 0xFF2563EB, dp(18)));
        sendButton.setOnClickListener(v -> {
            if (streaming) stopStreaming();
            else sendMessage();
        });
        composer.addView(sendButton, new LinearLayout.LayoutParams(dp(76), dp(56)));
        installKeyboardAvoidance();
    }

    private void showHeaderMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("检查更新");
        if (sessionToken != null) menu.getMenu().add("图片生成/编辑");
        if (sessionToken != null) menu.getMenu().add("会话列表");
        if (sessionToken != null) menu.getMenu().add("退出登录");
        menu.setOnMenuItemClickListener(item -> {
            if ("检查更新".contentEquals(item.getTitle())) checkAppUpdate(true);
            else if ("图片生成/编辑".contentEquals(item.getTitle())) showImageStudioDialog();
            else if ("会话列表".contentEquals(item.getTitle())) showConversationList("");
            else if ("退出登录".contentEquals(item.getTitle())) logout();
            return true;
        });
        menu.show();
    }

    private void showImageStudioDialog() {
        EditText input = new EditText(this);
        input.setHint(selectedImageDataUrl == null ? "描述要生成的图片" : "描述要生成的图片，或修改已附加图片");
        input.setMinLines(3);
        input.setGravity(Gravity.TOP | Gravity.START);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(16);
        panel.setPadding(padding, 0, padding, 0);
        panel.addView(input, new LinearLayout.LayoutParams(-1, -2));

        Spinner size = new Spinner(this);
        Spinner quality = new Spinner(this);
        Spinner format = new Spinner(this);
        size.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"横版 1536x1024", "方图 1024x1024", "竖版 1024x1536"}));
        quality.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"标准质量", "高清质量"}));
        format.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"PNG", "JPEG", "WebP"}));
        panel.addView(labelledImageOption("尺寸", size));
        panel.addView(labelledImageOption("质量", quality));
        panel.addView(labelledImageOption("格式", format));
        TextView pricing = new TextView(this);
        pricing.setText("正在读取图片价格说明…");
        pricing.setTextSize(12);
        pricing.setTextColor(0xFF64748B);
        pricing.setPadding(0, dp(10), 0, 0);
        panel.addView(pricing, new LinearLayout.LayoutParams(-1, -2));

        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("图片生成与编辑").setView(panel)
                .setNegativeButton("取消", null).setPositiveButton("生成", null).create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String prompt = input.getText().toString().trim();
                if (prompt.isEmpty()) { input.setError("请输入提示词"); return; }
                dialog.dismiss();
                requestImage(prompt, false, imageSizeValue(size), imageQualityValue(quality), imageFormatValue(format));
            });
            if (selectedImageDataUrl != null) dialog.setButton(AlertDialog.BUTTON_NEUTRAL, "修改已附加图片", (d, which) -> {
                String prompt = input.getText().toString().trim();
                if (prompt.isEmpty()) { toast("请输入修改要求"); return; }
                requestImage(prompt, true, imageSizeValue(size), imageQualityValue(quality), imageFormatValue(format));
            });
        });
        dialog.show();
        loadImagePricing(pricing);
    }

    // 价格快照只用平台 session 获取；图片 API Key 始终仅用于图片请求。
    // 客户端仅作提交前说明，最终金额仍由服务端预约和结算链路决定。
    private void loadImagePricing(TextView target) {
        storageExecutor.execute(() -> {
            String display = "图片价格暂不可读取；实际扣费仍由服务端统一结算。";
            try {
                JSONObject pricing = getJson(IMAGE_PRICING, sessionToken);
                JSONArray snapshots = pricing.optJSONArray("snapshots");
                JSONObject selected = null;
                if (snapshots != null) {
                    for (int i = 0; i < snapshots.length(); i++) {
                        JSONObject row = snapshots.optJSONObject(i);
                        if (row != null && "gpt-image-2".equals(row.optString("modelId"))
                                && "generation".equals(row.optString("operation"))) { selected = row; break; }
                        if (selected == null && row != null && "gpt-image-2".equals(row.optString("modelId"))) selected = row;
                    }
                }
                if (selected != null) {
                    double max = selected.optDouble("maxReservationCredits", 0) / 1_000_000d;
                    String time = selected.optString("effectiveAt", selected.optString("fetchedAt", "未知"));
                    display = String.format(java.util.Locale.US,
                            "价格来源：OpenRouter\n快照时间：%s\n单次最大预约：%.4f USD\n实际扣费以服务端结算为准。", time, max);
                }
            } catch (Exception ignored) { }
            final String text = display;
            uiHandler.post(() -> target.setText(text));
        });
    }

    private LinearLayout labelledImageOption(String label, Spinner control) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(label);
        title.setTextSize(13);
        title.setTextColor(0xFF475569);
        row.addView(title, new LinearLayout.LayoutParams(dp(48), -2));
        row.addView(control, new LinearLayout.LayoutParams(0, -2, 1));
        return row;
    }

    private String imageSizeValue(Spinner size) {
        int index = size.getSelectedItemPosition();
        return index == 1 ? "1024x1024" : index == 2 ? "1024x1536" : "1536x1024";
    }

    private String imageQualityValue(Spinner quality) {
        return quality.getSelectedItemPosition() == 1 ? "high" : "medium";
    }

    private String imageFormatValue(Spinner format) {
        int index = format.getSelectedItemPosition();
        return index == 1 ? "jpeg" : index == 2 ? "webp" : "png";
    }

    private void requestImage(String prompt, boolean edit, String size, String quality, String format) {
        setBusy(true, edit ? "正在修改图片..." : "正在生成图片...");
        storageExecutor.execute(() -> {
            try {
                JSONObject result;
                if (edit) result = postImageEdit(prompt, selectedImageDataUrl, size, quality, format);
                else {
                    JSONObject body = new JSONObject();
                    body.put("model", "gpt-image-2"); body.put("prompt", prompt);
                    body.put("size", size); body.put("quality", quality); body.put("output_format", format);
                    result = postJson(IMAGE_GENERATIONS, body, goKey, false, "img-" + UUID.randomUUID());
                }
                JSONArray data = result.optJSONArray("data");
                String encoded = data == null || data.length() == 0 ? "" : data.optJSONObject(0).optString("b64_json", "");
                if (encoded.isEmpty()) throw new Exception("图片服务没有返回图片数据");
                String mime = "jpeg".equals(format) ? "image/jpeg" : "image/" + format;
                String dataUrl = "data:" + mime + ";base64," + encoded;
                uiHandler.post(() -> showImageResult(dataUrl));
            } catch (Exception e) {
                String message = e.getMessage() != null && e.getMessage().contains("HTTP 403") ? "当前账号尚未开通图片权限" : friendlyError(e);
                uiHandler.post(() -> toast(message));
            } finally { uiHandler.post(() -> setBusy(false, "已连接 15code")); }
        });
    }

    private JSONObject postImageEdit(String prompt, String dataUrl, String size, String quality, String format) throws Exception {
        if (dataUrl == null) throw new Exception("请先附加要修改的图片");
        int comma = dataUrl.indexOf(',');
        byte[] image = Base64.decode(comma >= 0 ? dataUrl.substring(comma + 1) : dataUrl, Base64.DEFAULT);
        String boundary = "----15code-" + UUID.randomUUID();
        HttpURLConnection conn = (HttpURLConnection) new URL(IMAGE_EDITS).openConnection();
        conn.setConnectTimeout(20000); conn.setReadTimeout(180000); conn.setRequestMethod("POST"); conn.setDoOutput(true);
        conn.setRequestProperty("Authorization", "Bearer " + goKey);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        conn.setRequestProperty("X-Client-Request-Id", "img-edit-" + UUID.randomUUID());
        try (OutputStream out = conn.getOutputStream()) {
            writeMultipartField(out, boundary, "model", "gpt-image-2"); writeMultipartField(out, boundary, "prompt", prompt);
            writeMultipartField(out, boundary, "size", size); writeMultipartField(out, boundary, "quality", quality);
            writeMultipartField(out, boundary, "output_format", format);
            out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"image\"; filename=\"input.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(image); out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        String text = readAll(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
        if (code < 200 || code >= 300) throw new Exception("HTTP " + code + ": " + text);
        return new JSONObject(text);
    }

    private void writeMultipartField(OutputStream out, String boundary, String name, String value) throws IOException {
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private void showImageResult(String dataUrl) {
        byte[] bytes = Base64.decode(dataUrl.substring(dataUrl.indexOf(',') + 1), Base64.DEFAULT);
        ImageView preview = new ImageView(this); preview.setAdjustViewBounds(true);
        preview.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.length));
        new AlertDialog.Builder(this).setTitle("图片已完成").setView(preview).setNegativeButton("关闭", null)
                .setNeutralButton("继续修改", (d, w) -> { selectedImageDataUrl = dataUrl; selectedImageName = "生成图片"; updateAttachmentPreview(); showImageStudioDialog(); })
                .setPositiveButton("保存", (d, w) -> saveImageToGallery(bytes, imageMimeType(dataUrl))).show();
    }

    private String imageMimeType(String dataUrl) {
        if (dataUrl != null && dataUrl.startsWith("data:image/jpeg;")) return "image/jpeg";
        if (dataUrl != null && dataUrl.startsWith("data:image/webp;")) return "image/webp";
        return "image/png";
    }

    private void saveImageToGallery(byte[] bytes, String mimeType) {
        try {
            String extension = "image/jpeg".equals(mimeType) ? "jpg" : "image/webp".equals(mimeType) ? "webp" : "png";
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, "15code-image-" + System.currentTimeMillis() + "." + extension);
            values.put(MediaStore.Images.Media.MIME_TYPE, mimeType);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/15code");
            Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("无法创建图片文件");
            try (OutputStream out = getContentResolver().openOutputStream(uri)) { if (out == null) throw new IOException("无法写入图片"); out.write(bytes); }
            toast("图片已保存到系统相册");
        } catch (Exception e) { toast("保存失败：" + e.getMessage()); }
    }

    private void updateAttachmentPreview() {
        if (attachmentPreview == null) return;
        if (selectedImageDataUrl == null || selectedImageDataUrl.isEmpty()) {
            attachmentPreview.setVisibility(View.GONE);
            if (attachmentImage != null) attachmentImage.setImageDrawable(null);
            if (attachButton != null) attachButton.setText("＋");
            return;
        }
        try {
            int comma = selectedImageDataUrl.indexOf(',');
            String encoded = comma >= 0 ? selectedImageDataUrl.substring(comma + 1) : selectedImageDataUrl;
            byte[] bytes = Base64.decode(encoded, Base64.DEFAULT);
            attachmentImage.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.length));
        } catch (Exception ignored) {
            attachmentImage.setImageDrawable(null);
        }
        attachmentPreview.setVisibility(View.VISIBLE);
        attachButton.setText("图");
    }

    private void clearSelectedImage() {
        selectedImageDataUrl = null;
        selectedImageName = null;
        updateAttachmentPreview();
        statusText.setText("已移除图片");
    }

    private void showSmokeComposer() {
        sessionToken = "smoke";
        goKey = "smoke";
        selectedModel = PREFERRED_MODEL;
        accountEmail = "smoke-test";
        credits = 0;
        models.clear();
        models.add(Model.basic(PREFERRED_MODEL, PREFERRED_MODEL));
        setBusy(false, "Smoke test");
        showChat();
        root.postDelayed(() -> focusPromptInput(true), 400);
    }

    private boolean isDebugBuild() {
        return (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    private void login() {
        String email = emailInput.getText().toString().trim();
        String password = passwordInput.getText().toString();
        if (email.isEmpty() || password.isEmpty()) {
            toast("请输入邮箱和密码");
            return;
        }
        setBusy(true, "正在登录...");
        new Thread(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("email", email);
                body.put("password", password);
                JSONObject resp = postJson(PLATFORM + "/api/auth/login", body, null, true);
                sessionToken = resp.optString("sessionToken", "");
                if (sessionToken.isEmpty()) throw new Exception("服务端未返回 sessionToken");
                securePrefs.put("sessionToken", sessionToken);
                bootstrapAccount();
                runOnUiThread(() -> {
                    setBusy(false, catalogWarning == null ? "已登录" : catalogWarning);
                    showChat();
                    showCatalogWarningIfNeeded();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setBusy(false, "登录失败");
                    toast(e.getMessage());
                });
            }
        }).start();
    }

    private void restoreSession() {
        showChat();
        setBusy(true, "正在后台同步账户...");
        new Thread(() -> {
            try {
                bootstrapAccount();
                runOnUiThread(() -> {
                    setBusy(false, catalogWarning == null ? "已连接 15code" : catalogWarning);
                    showChat();
                    showCatalogWarningIfNeeded();
                });
            } catch (Exception e) {
                String message = e.getMessage() == null ? "" : e.getMessage();
                boolean authExpired = message.contains("HTTP 401") || message.contains("HTTP 403");
                if (authExpired) {
                    securePrefs.remove("sessionToken");
                    securePrefs.remove("goKey");
                    sessionToken = null;
                    goKey = null;
                    runOnUiThread(() -> {
                        setBusy(false, "登录已失效，请重新登录");
                        showLogin();
                    });
                } else {
                    runOnUiThread(() -> setBusy(false, "离线显示 · 后台同步失败"));
                }
            }
        }).start();
    }

    private void bootstrapAccount() throws Exception {
        JSONObject me = getJson(PLATFORM + "/api/me", sessionToken);
        JSONObject user = me.optJSONObject("user");
        accountEmail = user == null ? "" : user.optString("email", "");
        credits = user == null ? 0 : user.optDouble("credits", 0) / 1_000_000d;
        prefs.edit()
                .putString("accountEmail", accountEmail)
                .putString("creditsUsd", String.valueOf(credits))
                .apply();
        JSONArray tokens = getJson(PLATFORM + "/api/tokens", sessionToken).optJSONArray("tokens");
        goKey = "";
        if (tokens != null) {
            for (int i = 0; i < tokens.length(); i++) {
                JSONObject t = tokens.getJSONObject(i);
                if ("active".equals(t.optString("status")) && !t.optString("go_key").isEmpty()) {
                    goKey = t.optString("go_key");
                    break;
                }
            }
        }
        if (goKey.isEmpty()) {
            JSONObject req = new JSONObject();
            req.put("name", "15code Android");
            req.put("withGoKey", true);
            goKey = postJson(PLATFORM + "/api/tokens", req, sessionToken, false).optString("goKey");
        }
        if (goKey.isEmpty()) throw new Exception("未找到可用 API Key");
        securePrefs.put("goKey", goKey);

        refreshCatalog();
        selectAvailableModel();
    }

    private void refreshCatalog() throws Exception {
        catalogWarning = null;
        try {
            JSONObject catalog = getPublicJson(CATALOG);
            int schemaVersion = catalog.optInt("schemaVersion", -1);
            if (schemaVersion != SUPPORTED_CATALOG_SCHEMA_VERSION) {
                catalogWarning = "目录版本较新，请升级客户端";
                if (models.isEmpty()) throw new Exception(catalogWarning);
                return;
            }
            JSONArray rows = catalog.optJSONArray("models");
            JSONObject releases = catalog.optJSONObject("releases");
            JSONObject android = releases == null ? null : releases.optJSONObject("android");
            JSONObject stable = android == null ? null : android.optJSONObject("stable");
            if (stable != null) {
                catalogLatestVersion = stable.optString("version", "");
                catalogDownloadUrl = absolutePlatformUrl(stable.optString("downloadUrl", ANDROID_RELEASES));
                catalogMinimumVersion = stable.optString("minimumSupportedVersion", "");
                catalogForceUpgradeVersion = stable.optString("forceUpgradeBelow", "");
            }
            List<Model> fresh = new ArrayList<>();
            if (rows != null) {
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.optJSONObject(i);
                    if (row == null) continue;
                    String id = row.optString("id", "");
                    if (id.isEmpty()) continue;
                    JSONObject capabilities = row.optJSONObject("capabilities");
                    fresh.add(new Model(
                            id,
                            row.optString("displayName", id),
                            row.optString("provider", ""),
                            row.optString("family", "other"),
                            row.optString("status", "available"),
                            row.optBoolean("recommended", false),
                            row.optInt("sortOrder", Integer.MAX_VALUE),
                            capabilities != null && capabilities.optBoolean("vision", false),
                            capabilities != null && capabilities.optBoolean("webSearch", false),
                            capabilities != null && capabilities.optBoolean("tools", false)));
                }
            }
            fresh.sort((left, right) -> {
                int order = Integer.compare(left.sortOrder, right.sortOrder);
                return order != 0 ? order : left.name.compareToIgnoreCase(right.name);
            });
            if (fresh.isEmpty()) throw new Exception("Catalog 未返回模型");
            models.clear();
            models.addAll(fresh);
            saveCachedModels();
        } catch (Exception e) {
            if (catalogWarning != null && !models.isEmpty()) return;
            if (!models.isEmpty()) {
                catalogWarning = "离线目录 · 暂时无法刷新";
                return;
            }
            throw e;
        }
    }

    private void selectAvailableModel() throws Exception {
        Model current = findModel(selectedModel);
        if (current != null && current.isAvailable()) return;
        Model fallback = null;
        for (Model model : models) {
            if (!model.isAvailable()) continue;
            if (fallback == null || model.recommended) fallback = model;
            if (model.recommended) break;
        }
        if (fallback == null) throw new Exception("当前没有可用模型");
        selectedModel = fallback.id;
        prefs.edit().putString("model", selectedModel).apply();
    }

    private void loadCachedModels() {
        String raw = prefs.getString("cachedModels", "");
        if (raw == null || raw.isEmpty()) return;
        try {
            JSONArray saved = new JSONArray(raw);
            for (int i = 0; i < saved.length(); i++) {
                JSONObject row = saved.optJSONObject(i);
                if (row == null) continue;
                String id = row.optString("id", "");
                if (!id.isEmpty()) models.add(new Model(
                        id,
                        row.optString("name", id),
                        row.optString("provider", ""),
                        row.optString("family", "other"),
                        row.optString("status", "available"),
                        row.optBoolean("recommended", false),
                        row.optInt("sortOrder", Integer.MAX_VALUE),
                        row.optBoolean("vision", false),
                        row.optBoolean("webSearch", false),
                        row.optBoolean("tools", false)));
            }
        } catch (Exception ignored) {
            models.clear();
        }
    }

    private void saveCachedModels() {
        JSONArray saved = new JSONArray();
        try {
            for (Model model : models) {
                JSONObject row = new JSONObject();
                row.put("id", model.id);
                row.put("name", model.name);
                row.put("provider", model.provider);
                row.put("family", model.family);
                row.put("status", model.status);
                row.put("recommended", model.recommended);
                row.put("sortOrder", model.sortOrder);
                row.put("vision", model.vision);
                row.put("webSearch", model.webSearch);
                row.put("tools", model.tools);
                saved.put(row);
            }
            prefs.edit().putString("cachedModels", saved.toString()).apply();
        } catch (Exception ignored) {}
    }

    private void sendMessage() {
        String text = promptInput.getText().toString().trim();
        if (text.isEmpty() && selectedImageDataUrl == null) {
            openComposerDialog();
            return;
        }
        sendMessageText(text);
    }

    private void openComposerDialog() {
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(16), dp(14), dp(16), dp(16));
        sheet.setBackground(makeBg(0xFFFFFFFF, 0xFFE2E8F0, dp(18)));

        TextView title = new TextView(this);
        title.setText("输入消息");
        title.setTextColor(0xFF0F172A);
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        sheet.addView(title, new LinearLayout.LayoutParams(-1, dp(30)));

        EditText input = new EditText(this);
        input.setHint("发消息给 15code");
        input.setContentDescription("chat-composer-sheet-input");
        input.setMinLines(3);
        input.setMaxLines(6);
        input.setSingleLine(false);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        int pad = dp(16);
        input.setPadding(pad, dp(10), pad, dp(10));
        input.setTextColor(0xFF111827);
        input.setHintTextColor(0xFF94A3B8);
        input.setTextSize(16);
        input.setBackground(makeBg(0xFFF8FAFC, 0xFFCBD5E1, dp(14)));
        input.setText(currentDraft);
        input.setSelection(input.getText().length());
        sheet.addView(input, new LinearLayout.LayoutParams(-1, dp(132)));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        actions.setPadding(0, dp(12), 0, 0);

        Button cancel = new Button(this);
        cancel.setText("取消");
        cancel.setAllCaps(false);
        cancel.setTextColor(0xFF334155);
        cancel.setBackground(makeBg(0xFFFFFFFF, 0xFFCBD5E1, dp(14)));
        actions.addView(cancel, new LinearLayout.LayoutParams(dp(82), dp(48)));

        Button send = new Button(this);
        send.setText("发送");
        send.setAllCaps(false);
        send.setTextColor(0xFFFFFFFF);
        send.setBackground(makeBg(0xFF2563EB, 0xFF2563EB, dp(14)));
        LinearLayout.LayoutParams sendLp = new LinearLayout.LayoutParams(dp(92), dp(48));
        sendLp.setMargins(dp(10), 0, 0, 0);
        actions.addView(send, sendLp);
        sheet.addView(actions, new LinearLayout.LayoutParams(-1, dp(60)));

        PopupWindow popup = new PopupWindow(sheet, -1, -2, true);
        popup.setBackgroundDrawable(new ColorDrawable(0x00000000));
        popup.setOutsideTouchable(true);
        popup.setInputMethodMode(PopupWindow.INPUT_METHOD_NEEDED);
        popup.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        popup.setOnDismissListener(() -> saveDraft(input.getText().toString()));

        cancel.setOnClickListener(v -> popup.dismiss());
        send.setOnClickListener(v -> {
            String text = input.getText().toString().trim();
            if (text.isEmpty() && selectedImageDataUrl == null) {
                toast("请输入消息");
                return;
            }
            popup.dismiss();
            saveDraft("");
            sendMessageText(text);
        });

        popup.showAtLocation(root, Gravity.BOTTOM, 0, 0);
        input.requestFocus();
        input.postDelayed(() -> openKeyboard(input), 160);
    }

    private void sendMessageText(String text) {
        promptInput.setText("");
        String attachedImage = selectedImageDataUrl;
        String imageName = selectedImageName;
        selectedImageDataUrl = null;
        selectedImageName = null;
        updateAttachmentPreview();

        String displayText = text.isEmpty() ? "[图片]" : text;
        if (attachedImage != null) displayText += "\n[已附加图片]";
        addBubble("你", displayText, true);
        try {
            JSONObject user = new JSONObject();
            user.put("role", "user");
            user.put("content", text.isEmpty() ? "[图片]" : text);
            messages.put(user);
            trimMessages();
            saveChatHistory();
        } catch (Exception ignored) {}

        TextView assistantBubble = addBubble(modelLabel(selectedModel), "正在思考...", false);
        setStreamingUi(true);
        resetStreamRenderState();
        new Thread(() -> {
            StringBuilder answer = new StringBuilder();
            JSONObject body = new JSONObject();
            try {
                body.put("model", selectedModel);
                body.put("stream", true);
                if (forceWebSearch) {
                    body.put("webSearch", true);
                } else {
                    body.put("searchMode", "auto");
                }
                body.put("max_tokens", 4096);
                body.put("messages", buildRequestMessages(text, attachedImage));
                streamChat(body, chunk -> {
                    answer.append(chunk);
                    queueBubbleUpdate(assistantBubble, modelLabel(selectedModel), answer.toString());
                });
                flushBubbleUpdate(assistantBubble, modelLabel(selectedModel), answer.toString());
                if (answer.length() == 0) throw new Exception("模型返回为空，请换模型重试");
                JSONObject assistant = new JSONObject();
                assistant.put("role", "assistant");
                assistant.put("content", answer.toString());
                messages.put(assistant);
                trimMessages();
                saveChatHistory();
            } catch (Exception e) {
                if (stopRequested && answer.length() > 0) {
                    try {
                        JSONObject assistant = new JSONObject();
                        assistant.put("role", "assistant");
                        assistant.put("content", answer.toString());
                        messages.put(assistant);
                        trimMessages();
                        saveChatHistory();
                    } catch (Exception ignored) {}
                    flushBubbleUpdate(assistantBubble, modelLabel(selectedModel), answer + "\n\n[已停止]");
                } else if (answer.length() == 0 && isRetryableStreamError(e)) {
                    try {
                        flushBubbleUpdate(assistantBubble, modelLabel(selectedModel), "连接不稳定，正在切换普通模式...");
                        body.put("stream", false);
                        String fallback = completeChat(body);
                        if (fallback.isEmpty()) throw new Exception("模型返回为空，请换模型重试");
                        JSONObject assistant = new JSONObject();
                        assistant.put("role", "assistant");
                        assistant.put("content", fallback);
                        messages.put(assistant);
                        trimMessages();
                        saveChatHistory();
                        flushBubbleUpdate(assistantBubble, modelLabel(selectedModel), fallback);
                    } catch (Exception fallbackError) {
                        flushBubbleUpdate(assistantBubble, "错误", friendlyError(fallbackError));
                    }
                } else if (answer.length() == 0) {
                    flushBubbleUpdate(assistantBubble, "错误", friendlyError(e));
                } else {
                    flushBubbleUpdate(assistantBubble, modelLabel(selectedModel), answer + "\n\n[连接中断]");
                }
            } finally {
                activeChatConnection = null;
                runOnUiThread(() -> setStreamingUi(false));
            }
        }).start();
    }

    private JSONArray buildRequestMessages(String text, String imageDataUrl) throws Exception {
        JSONArray out = new JSONArray();
        int last = messages.length() - 1;
        for (int i = 0; i < messages.length(); i++) {
            JSONObject src = messages.getJSONObject(i);
            JSONObject dst = new JSONObject();
            dst.put("role", src.optString("role"));
            if (i == last && imageDataUrl != null && "user".equals(src.optString("role"))) {
                JSONArray content = new JSONArray();
                JSONObject textPart = new JSONObject();
                textPart.put("type", "text");
                textPart.put("text", text == null || text.isEmpty() ? "请分析这张图片" : text);
                content.put(textPart);
                JSONObject imageUrl = new JSONObject();
                imageUrl.put("url", imageDataUrl);
                JSONObject imagePart = new JSONObject();
                imagePart.put("type", "image_url");
                imagePart.put("image_url", imageUrl);
                content.put(imagePart);
                dst.put("content", content);
            } else {
                dst.put("content", src.optString("content", ""));
            }
            out.put(dst);
        }
        return out;
    }

    private void trimMessages() {
        while (messages.length() > MAX_HISTORY_MESSAGES) messages.remove(0);
    }

    private void saveChatHistory() {
        final String conversationId = currentConversationId;
        final String snapshot = messages.toString();
        storageExecutor.execute(() -> persistConversation(conversationId, snapshot));
    }

    private void persistConversation(String conversationId, String snapshot) {
        try {
            JSONArray saved = new JSONArray(snapshot);
            long now = System.currentTimeMillis();
            ConversationWithMessages existing = chatDao.getConversation(conversationId);
            long createdAt = existing == null ? now : existing.conversation.createdAt;
            boolean pinned = existing != null && existing.conversation.pinned;
            boolean deleted = existing != null && existing.conversation.deleted;
            String title = existing == null ? conversationTitle(saved) : existing.conversation.title;
            if ((title == null || title.equals("新对话")) && saved.length() > 0) title = conversationTitle(saved);
            chatDao.saveConversation(new ConversationEntity(conversationId, title, pinned, deleted,
                    createdAt, now, currentDraft == null ? "" : currentDraft));
            chatDao.deleteMessages(conversationId);
            List<MessageEntity> rows = new ArrayList<>();
            for (int i = 0; i < saved.length(); i++) {
                JSONObject item = saved.optJSONObject(i);
                if (item == null) continue;
                rows.add(new MessageEntity(conversationId, item.optString("role"),
                        item.optString("content"), now + i));
            }
            if (!rows.isEmpty()) chatDao.saveMessages(rows);
            prefs.edit().remove("chatHistory").apply();
        } catch (Exception ignored) {}
    }

    private String conversationTitle(JSONArray saved) {
        for (int i = 0; i < saved.length(); i++) {
            JSONObject item = saved.optJSONObject(i);
            if (item == null || !"user".equals(item.optString("role"))) continue;
            String text = item.optString("content", "").replace('\n', ' ').trim();
            if (text.length() > 28) text = text.substring(0, 28) + "…";
            if (!text.isEmpty()) return text;
        }
        return "新对话";
    }

    private void saveDraft(String draft) {
        currentDraft = draft == null ? "" : draft;
        final String conversationId = currentConversationId;
        final String value = currentDraft;
        storageExecutor.execute(() -> {
            ConversationWithMessages existing = chatDao.getConversation(conversationId);
            long now = System.currentTimeMillis();
            if (existing == null) {
                chatDao.saveConversation(new ConversationEntity(conversationId, "新对话", false,
                        false, now, now, value));
            } else {
                chatDao.saveDraft(conversationId, value, now);
            }
        });
    }

    private void streamChat(JSONObject body, StreamHandler handler) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(SEARCH_CHAT).openConnection();
        activeChatConnection = conn;
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(600000);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("Accept", "text/event-stream");
        conn.setRequestProperty("Authorization", "Bearer " + sessionToken);
        conn.setRequestProperty("User-Agent", "15code-android-native/" + APP_VERSION);
        conn.setDoOutput(true);
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        conn.setRequestProperty("Content-Length", String.valueOf(bytes.length));
        try (OutputStream out = conn.getOutputStream()) {
            out.write(bytes);
        }
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new Exception("HTTP " + code + ": " + readAll(conn.getErrorStream()));
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            String event = "";
            String data = "";
            while ((line = reader.readLine()) != null) {
                if (!streaming) throw new Exception("用户已停止生成");
                if (line.trim().isEmpty()) {
                    if (!data.isEmpty()) {
                        if ("meta".equals(event)) {
                            JSONObject meta = new JSONObject(data);
                            if (meta.optBoolean("searched")) {
                                runOnUiThread(() -> statusText.setText("已搜索最新信息，正在生成..."));
                            } else if (!meta.optString("searchError", "").isEmpty()) {
                                String err = meta.optString("searchError");
                                runOnUiThread(() -> statusText.setText("检索跳过 · " + err));
                            }
                        } else if ("error".equals(event)) {
                            JSONObject err = new JSONObject(data);
                            throw new Exception(err.optString("error", data));
                        } else {
                            if ("[DONE]".equals(data)) break;
                            JSONObject obj = new JSONObject(data);
                            JSONObject choice = obj.optJSONArray("choices") == null ? null : obj.optJSONArray("choices").optJSONObject(0);
                            if (choice != null) {
                                JSONObject delta = choice.optJSONObject("delta");
                                String chunk = "";
                                if (delta != null) chunk = delta.optString("content", "");
                                if (chunk.isEmpty()) {
                                    JSONObject message = choice.optJSONObject("message");
                                    if (message != null) chunk = message.optString("content", "");
                                }
                                if (!chunk.isEmpty()) handler.onChunk(chunk);
                            }
                        }
                    }
                    event = "";
                    data = "";
                    continue;
                }
                if (line.startsWith("event:")) {
                    event = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    String part = line.substring(5).trim();
                    data = data.isEmpty() ? part : data + "\n" + part;
                }
            }
        }
    }

    private String completeChat(JSONObject body) throws Exception {
        JSONObject resp = postJson(SEARCH_CHAT, body, sessionToken, false);
        return resp.optString("content", "");
    }

    private boolean isRetryableStreamError(Exception e) {
        Throwable cur = e;
        while (cur != null) {
            if (cur instanceof SocketException || cur instanceof IOException) {
                String msg = cur.getMessage() == null ? "" : cur.getMessage().toLowerCase();
                return msg.contains("software caused connection abort")
                        || msg.contains("connection reset")
                        || msg.contains("unexpected end")
                        || msg.contains("eof")
                        || msg.contains("read error");
            }
            cur = cur.getCause();
        }
        return false;
    }

    private String friendlyError(Exception e) {
        String msg = e.getMessage();
        if (msg == null || msg.trim().isEmpty()) return "请求失败，请稍后重试";
        String lower = msg.toLowerCase();
        if (lower.contains("software caused connection abort")
                || lower.contains("connection reset")
                || lower.contains("unexpected end")
                || lower.contains("eof")) {
            return "连接中断，请重试";
        }
        return msg;
    }

    private JSONObject getJson(String url, String bearer) throws Exception {
        return requestJson("GET", url, null, bearer, false);
    }

    private JSONObject postJson(String url, JSONObject body, String bearer, boolean loginMode) throws Exception {
        return requestJson("POST", url, body, bearer, loginMode, null);
    }

    private JSONObject postJson(String url, JSONObject body, String bearer, boolean loginMode, String clientRequestId) throws Exception {
        return requestJson("POST", url, body, bearer, loginMode, clientRequestId);
    }

    private JSONObject requestJson(String method, String urlText, JSONObject body, String bearer, boolean loginMode) throws Exception {
        return requestJson(method, urlText, body, bearer, loginMode, null);
    }

    private JSONObject requestJson(String method, String urlText, JSONObject body, String bearer, boolean loginMode, String clientRequestId) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlText).openConnection();
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(180000);
        conn.setRequestMethod(method);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "15code-android-native/" + APP_VERSION);
        if (loginMode) conn.setRequestProperty("X-Auth-Mode", "bearer");
        if (bearer != null && !bearer.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + bearer);
        if (clientRequestId != null && !clientRequestId.isEmpty()) conn.setRequestProperty("X-Client-Request-Id", clientRequestId);
        if (body != null) {
            conn.setDoOutput(true);
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            conn.setRequestProperty("Content-Length", String.valueOf(bytes.length));
            try (OutputStream out = conn.getOutputStream()) {
                out.write(bytes);
            }
        }
        int code = conn.getResponseCode();
        String text = readAll(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
        if (code < 200 || code >= 300) throw new Exception("HTTP " + code + ": " + text);
        return new JSONObject(text);
    }

    private String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);
        return sb.toString();
    }

    private void showLogin() {
        loginPanel.setVisibility(View.VISIBLE);
        chatPanel.setVisibility(View.GONE);
        newChatButton.setVisibility(View.GONE);
        imageStudioButton.setVisibility(View.GONE);
        menuButton.setVisibility(View.VISIBLE);
        statusText.setText("请登录");
    }

    private void showChat() {
        loginPanel.setVisibility(View.GONE);
        chatPanel.setVisibility(View.VISIBLE);
        newChatButton.setVisibility(View.VISIBLE);
        imageStudioButton.setVisibility(View.VISIBLE);
        menuButton.setVisibility(View.VISIBLE);
        accountText.setText((accountEmail == null || accountEmail.isEmpty() ? "已登录" : accountEmail)
                + " · 余额 " + String.format("%.4f", credits));
        updateModelButton();
        updateSearchButton();
        if (messageList.getChildCount() == 0 && !historyLoadStarted) {
            loadChatHistory();
        }
    }

    private void loadChatHistory() {
        historyLoadStarted = true;
        final String conversationId = currentConversationId;
        storageExecutor.execute(() -> {
            ConversationWithMessages stored = chatDao.getConversation(conversationId);
            String legacy = prefs.getString("chatHistory", "");
            if (stored == null && legacy != null && !legacy.isEmpty()) {
                persistConversation(conversationId, legacy);
                stored = chatDao.getConversation(conversationId);
            }
            ConversationWithMessages result = stored;
            runOnUiThread(() -> renderConversation(result));
        });
    }

    private void renderConversation(ConversationWithMessages stored) {
        messageList.removeAllViews();
        while (messages.length() > 0) messages.remove(0);
        currentDraft = stored == null ? "" : stored.conversation.draft;
        if (stored != null && stored.messages != null) {
            stored.messages.sort((left, right) -> Long.compare(left.createdAt, right.createdAt));
            int start = Math.max(0, stored.messages.size() - MAX_HISTORY_MESSAGES);
            for (int i = start; i < stored.messages.size(); i++) {
                MessageEntity row = stored.messages.get(i);
                if (row.content == null || row.content.isEmpty()) continue;
                try {
                    JSONObject msg = new JSONObject();
                    msg.put("role", row.role);
                    msg.put("content", row.content);
                    messages.put(msg);
                    addBubble("user".equals(row.role) ? "你" : modelLabel(selectedModel),
                            row.content, "user".equals(row.role));
                } catch (Exception ignored) {}
            }
        }
        if (messageList.getChildCount() == 0) addBubble("15code", "已连接。", false);
        historyLoadStarted = false;
    }

    private List<String> modelNames() {
        List<String> names = new ArrayList<>();
        for (Model m : models) {
            StringBuilder label = new StringBuilder(m.name).append("  ·  ").append(m.provider);
            if (m.recommended) label.append("  ·  推荐");
            if (m.vision) label.append("  ·  图片");
            if (m.webSearch) label.append("  ·  联网");
            if (m.tools) label.append("  ·  工具");
            if (!m.isAvailable()) label.append("  ·  ").append(modelStatusLabel(m.status));
            names.add(label.toString());
        }
        return names;
    }

    private void showModelPicker() {
        if (models.isEmpty()) {
            toast("模型列表还没有加载完成");
            return;
        }
        String[] labels = modelNames().toArray(new String[0]);
        int selected = 0;
        for (int i = 0; i < models.size(); i++) {
            if (models.get(i).id.equals(selectedModel)) {
                selected = i;
                break;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("选择模型")
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    Model chosen = models.get(which);
                    if (!chosen.isAvailable()) {
                        toast("该模型当前" + modelStatusLabel(chosen.status));
                        return;
                    }
                    selectedModel = chosen.id;
                    prefs.edit().putString("model", selectedModel).apply();
                    if (!chosen.webSearch) forceWebSearch = false;
                    updateModelButton();
                    updateSearchButton();
                    dialog.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void updateModelButton() {
        String label = modelLabel(selectedModel);
        modelButton.setText(label + "\n" + selectedModel);
        statusText.setText("当前模型 · " + label);
    }

    private boolean selectVisionModelForImage() {
        Model current = findModel(selectedModel);
        if (current != null && current.isAvailable() && current.vision) return false;
        Model fallback = null;
        for (Model model : models) {
            if (!model.isAvailable() || !model.vision) continue;
            if (fallback == null || model.recommended) fallback = model;
            if (model.recommended) break;
        }
        if (fallback == null) return false;
        selectedModel = fallback.id;
        prefs.edit().putString("model", selectedModel).apply();
        updateModelButton();
        updateSearchButton();
        return true;
    }

    private void updateSearchButton() {
        if (searchButton == null) return;
        Model current = findModel(selectedModel);
        boolean supported = current == null || current.webSearch;
        if (!supported) forceWebSearch = false;
        searchButton.setEnabled(supported);
        searchButton.setText(!supported ? "不支持" : forceWebSearch ? "联网开" : "联网");
        searchButton.setTextColor(forceWebSearch ? 0xFFFFFFFF : supported ? 0xFF0F172A : 0xFF94A3B8);
        searchButton.setBackground(makeBg(
                forceWebSearch ? 0xFF059669 : 0xFFFFFFFF,
                forceWebSearch ? 0xFF059669 : 0xFFCBD5E1,
                dp(18)));
        statusText.setText(!supported ? "当前模型不支持联网" : forceWebSearch ? "联网检索已开启" : "自动检索");
    }

    private Model findModel(String id) {
        if (id == null) return null;
        for (Model model : models) if (id.equals(model.id)) return model;
        return null;
    }

    private String modelStatusLabel(String status) {
        if ("maintenance".equals(status)) return "维护中";
        if ("paused".equals(status) || "disabled".equals(status)) return "已暂停";
        return "不可用";
    }

    private void showCatalogWarningIfNeeded() {
        if (catalogWarning == null || catalogWarning.isEmpty()) return;
        toast(catalogWarning);
        catalogWarning = null;
    }

    private String modelLabel(String id) {
        for (Model m : models) if (m.id.equals(id)) return m.name;
        return id;
    }

    private TextView addBubble(String who, String text, boolean mine) {
        TextView bubble = new TextView(this);
        updateBubble(bubble, who, text);
        bubble.setTextSize(15);
        bubble.setLineSpacing(0, 1.18f);
        bubble.setTextColor(mine ? 0xFFFFFFFF : 0xFF111827);
        bubble.setPadding(dp(12), dp(10), dp(12), dp(10));
        bubble.setTextIsSelectable(true);
        bubble.setBackground(makeBg(mine ? 0xFF2563EB : 0xFFFFFFFF, mine ? 0xFF2563EB : 0xFFE2E8F0, dp(14)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(mine ? dp(42) : 0, dp(8), mine ? 0 : dp(42), dp(8));
        messageList.addView(bubble, lp);
        scrollToChatBottom();
        return bubble;
    }

    private void updateBubble(TextView bubble, String who, String text) {
        boolean shouldFollow = isNearScrollBottom();
        bubble.setText(who + "\n" + text);
        if (shouldFollow) {
            scrollToChatBottom();
        }
    }

    private void scrollToChatBottom() {
        if (scroll == null || messageList == null) return;
        // fullScroll() relies on focus navigation and can stop at the first page
        // when a growing, selectable TextView is updated during streaming. Wait
        // for layout and scroll to the measured content height directly instead.
        scroll.post(() -> scroll.scrollTo(0,
                Math.max(0, messageList.getHeight() - scroll.getHeight())));
    }

    private boolean isNearScrollBottom() {
        if (scroll == null || messageList == null) return true;
        int distance = messageList.getBottom() - (scroll.getScrollY() + scroll.getHeight());
        return distance < dp(120);
    }

    private void resetStreamRenderState() {
        uiHandler.post(() -> {
            if (pendingStreamRender != null) {
                uiHandler.removeCallbacks(pendingStreamRender);
                pendingStreamRender = null;
            }
            lastStreamRenderAt = 0;
            pendingStreamText = "";
        });
    }

    private void queueBubbleUpdate(TextView bubble, String who, String text) {
        uiHandler.post(() -> {
            pendingStreamText = text;
            long now = System.currentTimeMillis();
            long elapsed = now - lastStreamRenderAt;
            if (elapsed >= STREAM_RENDER_INTERVAL_MS) {
                if (pendingStreamRender != null) {
                    uiHandler.removeCallbacks(pendingStreamRender);
                    pendingStreamRender = null;
                }
                lastStreamRenderAt = now;
                updateBubble(bubble, who, text);
                return;
            }
            if (pendingStreamRender != null) return;
            long delay = STREAM_RENDER_INTERVAL_MS - elapsed;
            pendingStreamRender = () -> {
                pendingStreamRender = null;
                lastStreamRenderAt = System.currentTimeMillis();
                updateBubble(bubble, who, pendingStreamText);
            };
            uiHandler.postDelayed(pendingStreamRender, delay);
        });
    }

    private void flushBubbleUpdate(TextView bubble, String who, String text) {
        uiHandler.post(() -> {
            if (pendingStreamRender != null) {
                uiHandler.removeCallbacks(pendingStreamRender);
                pendingStreamRender = null;
            }
            lastStreamRenderAt = System.currentTimeMillis();
            pendingStreamText = text;
            updateBubble(bubble, who, text);
        });
    }

    private void newChat() {
        saveChatHistory();
        currentConversationId = UUID.randomUUID().toString();
        prefs.edit().putString("currentConversationId", currentConversationId).apply();
        currentDraft = "";
        while (messages.length() > 0) messages.remove(0);
        prefs.edit().remove("chatHistory").apply();
        selectedImageDataUrl = null;
        selectedImageName = null;
        updateAttachmentPreview();
        messageList.removeAllViews();
        addBubble("15code", "新对话已开始。", false);
        promptInput.setText("");
        saveDraft("");
    }

    private void logout() {
        securePrefs.remove("sessionToken");
        securePrefs.remove("goKey");
        prefs.edit().remove("accountEmail").remove("creditsUsd").apply();
        sessionToken = null;
        goKey = null;
        selectedModel = null;
        accountEmail = null;
        credits = 0;
        models.clear();
        while (messages.length() > 0) messages.remove(0);
        messageList.removeAllViews();
        showLogin();
    }

    private void showConversationList(String query) {
        storageExecutor.execute(() -> {
            List<ConversationEntity> rows = chatDao.listConversations(query == null ? "" : query.trim());
            runOnUiThread(() -> {
                String[] labels = new String[rows.size()];
                for (int i = 0; i < rows.size(); i++) {
                    ConversationEntity row = rows.get(i);
                    labels[i] = (row.pinned ? "📌 " : "") + row.title;
                }
                new AlertDialog.Builder(this)
                        .setTitle(query == null || query.isEmpty() ? "会话列表" : "搜索：" + query)
                        .setItems(labels, (dialog, which) -> showConversationActions(rows.get(which)))
                        .setPositiveButton("搜索", (dialog, which) -> promptConversationSearch())
                        .setNeutralButton("最近删除", (dialog, which) -> showDeletedConversations())
                        .setNegativeButton("关闭", null)
                        .show();
            });
        });
    }

    private void promptConversationSearch() {
        EditText input = new EditText(this);
        input.setHint("输入会话标题");
        input.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle("搜索会话")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("搜索", (dialog, which) -> showConversationList(input.getText().toString()))
                .show();
    }

    private void showConversationActions(ConversationEntity conversation) {
        String pinLabel = conversation.pinned ? "取消置顶" : "置顶";
        new AlertDialog.Builder(this)
                .setTitle(conversation.title)
                .setItems(new String[]{"打开", "重命名", pinLabel, "删除"}, (dialog, which) -> {
                    if (which == 0) openConversation(conversation.id);
                    else if (which == 1) renameConversation(conversation);
                    else if (which == 2) storageExecutor.execute(() ->
                            chatDao.setPinned(conversation.id, !conversation.pinned, System.currentTimeMillis()));
                    else storageExecutor.execute(() -> {
                        chatDao.setDeleted(conversation.id, true, System.currentTimeMillis());
                        if (conversation.id.equals(currentConversationId)) runOnUiThread(this::newChat);
                    });
                })
                .show();
    }

    private void renameConversation(ConversationEntity conversation) {
        EditText input = new EditText(this);
        input.setText(conversation.title);
        input.setSelection(input.getText().length());
        new AlertDialog.Builder(this)
                .setTitle("重命名会话")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) -> {
                    String title = input.getText().toString().trim();
                    if (!title.isEmpty()) storageExecutor.execute(() ->
                            chatDao.rename(conversation.id, title, System.currentTimeMillis()));
                })
                .show();
    }

    private void openConversation(String id) {
        saveChatHistory();
        currentConversationId = id;
        prefs.edit().putString("currentConversationId", id).apply();
        historyLoadStarted = false;
        loadChatHistory();
    }

    private void showDeletedConversations() {
        storageExecutor.execute(() -> {
            List<ConversationEntity> rows = chatDao.listDeleted();
            runOnUiThread(() -> {
                String[] labels = new String[rows.size()];
                for (int i = 0; i < rows.size(); i++) labels[i] = rows.get(i).title;
                new AlertDialog.Builder(this)
                        .setTitle("最近删除")
                        .setItems(labels, (dialog, which) -> {
                            ConversationEntity row = rows.get(which);
                            storageExecutor.execute(() -> chatDao.setDeleted(row.id, false, System.currentTimeMillis()));
                            toast("会话已恢复");
                        })
                        .setNegativeButton("关闭", null)
                        .show();
            });
        });
    }

    private void setBusy(boolean busy, String status) {
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        statusText.setText(status);
    }

    private void setStreamingUi(boolean active) {
        streaming = active;
        if (active) stopRequested = false;
        progress.setVisibility(active ? View.VISIBLE : View.GONE);
        statusText.setText(active ? "正在生成 · " + modelLabel(selectedModel) : "已连接 15code");
        sendButton.setText(active ? "停止" : "发送");
        promptInput.setEnabled(!active);
    }

    private void stopStreaming() {
        stopRequested = true;
        streaming = false;
        if (activeChatConnection != null) activeChatConnection.disconnect();
        setStreamingUi(false);
    }

    private void toast(String text) {
        Toast.makeText(this, text == null ? "操作失败" : text, Toast.LENGTH_LONG).show();
    }

    private void openKeyboard(View view) {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT);
    }

    private void focusPromptInput(boolean showKeyboard) {
        if (promptInput == null || !promptInput.isEnabled()) return;
        promptInput.requestFocus();
        promptInput.setSelection(promptInput.getText().length());
        if (showKeyboard) {
            openKeyboard(promptInput);
        }
    }

    private void checkAppUpdate(boolean manual) {
        if (manual) setBusy(true, "正在检查更新...");
        new Thread(() -> {
            try {
                JSONObject catalog = getPublicJson(CATALOG);
                JSONObject releases = catalog.optJSONObject("releases");
                JSONObject android = releases == null ? null : releases.optJSONObject("android");
                JSONObject release = android == null ? null : android.optJSONObject("stable");
                if (release == null) throw new Exception("Catalog 未返回 Android Release");
                String version = release.optString("version", "");
                String tag = "v" + version;
                String url = absolutePlatformUrl(release.optString("downloadUrl", ANDROID_RELEASES));
                String minimum = release.optString("minimumSupportedVersion", "");
                String forceBelow = release.optString("forceUpgradeBelow", "");
                boolean hasUpdate = compareVersion(version, APP_VERSION) > 0;
                boolean mandatory = (!forceBelow.isEmpty() && compareVersion(APP_VERSION, forceBelow) < 0)
                        || (!minimum.isEmpty() && compareVersion(APP_VERSION, minimum) < 0);
                runOnUiThread(() -> {
                    if (manual) setBusy(false, hasUpdate ? "发现新版 " + tag : "当前已是最新版本");
                    if (hasUpdate || mandatory) showUpdateDialog(tag, url, mandatory);
                    else if (manual) toast("当前已是最新版本");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (manual) {
                        setBusy(false, "检查更新失败");
                        toast("无法读取 Catalog Release，请稍后重试");
                    }
                });
            }
        }).start();
    }

    private void showUpdateDialog(String tag, String url, boolean mandatory) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("发现新版本 " + tag)
                .setMessage(mandatory ? "当前版本已低于最低支持版本，请升级后继续使用。" : "新版 APK 已发布，可从 15code 下载并覆盖安装。")
                .setNegativeButton(mandatory ? null : "稍后", null)
                .setPositiveButton("立即下载", (d, which) -> openExternal(url == null || url.isEmpty() ? ANDROID_RELEASES : url))
                .create();
        dialog.setCancelable(!mandatory);
        dialog.setCanceledOnTouchOutside(!mandatory);
        dialog.show();
    }

    private String absolutePlatformUrl(String value) {
        if (value == null || value.isEmpty()) return ANDROID_RELEASES;
        if (value.startsWith("http://") || value.startsWith("https://")) return value;
        return PLATFORM + (value.startsWith("/") ? value : "/" + value);
    }

    private void openExternal(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            toast("无法打开链接");
        }
    }

    private JSONObject getPublicJson(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        conn.setRequestProperty("User-Agent", "15code-android/" + APP_VERSION);
        int code = conn.getResponseCode();
        String text = readAll(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
        if (code < 200 || code >= 300) throw new Exception("HTTP " + code + ": " + text);
        return new JSONObject(text);
    }

    private String normaliseVersion(String tag) {
        String value = tag == null ? "" : tag.trim();
        while (value.startsWith("v") || value.startsWith("V")) value = value.substring(1);
        return value;
    }

    private int compareVersion(String a, String b) {
        String[] left = normaliseVersion(a).split("\\.");
        String[] right = normaliseVersion(b).split("\\.");
        int count = Math.max(left.length, right.length);
        for (int i = 0; i < count; i++) {
            int l = i < left.length ? parseVersionPart(left[i]) : 0;
            int r = i < right.length ? parseVersionPart(right[i]) : 0;
            if (l != r) return l > r ? 1 : -1;
        }
        return 0;
    }

    private int parseVersionPart(String value) {
        try {
            return Integer.parseInt(value.replaceAll("[^0-9].*$", ""));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private void pickImage() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, PICK_IMAGE_REQUEST);
    }

    private byte[] readLimitedBytes(Uri uri, int limit) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) throw new Exception("无法读取图片");
            byte[] buf = new byte[8192];
            int total = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > limit) throw new Exception("图片过大，请选择 4 MB 以内的图片");
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    private void installKeyboardAvoidance() {
        root.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            Rect visible = new Rect();
            getWindow().getDecorView().getWindowVisibleDisplayFrame(visible);
            int fullHeight = getResources().getDisplayMetrics().heightPixels;
            int hidden = fullHeight - visible.bottom;
            int keyboardHeight = hidden > dp(140) ? hidden : 0;
            composer.setTranslationY(-keyboardHeight);
            composer.bringToFront();
            scroll.setPadding(0, 0, 0, keyboardHeight == 0 ? 0 : keyboardHeight + dp(12));
            if (keyboardHeight > 0 && promptInput.hasFocus()) {
                scrollToChatBottom();
            }
        });
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable makeBg(int color, int stroke, int radius) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(radius);
        bg.setStroke(dp(1), stroke);
        return bg;
    }

    private static class Model {
        final String id;
        final String name;
        final String provider;
        final String family;
        final String status;
        final boolean recommended;
        final int sortOrder;
        final boolean vision;
        final boolean webSearch;
        final boolean tools;

        Model(String id, String name, String provider, String family, String status,
              boolean recommended, int sortOrder, boolean vision, boolean webSearch, boolean tools) {
            this.id = id;
            this.name = name;
            this.provider = provider;
            this.family = family;
            this.status = status;
            this.recommended = recommended;
            this.sortOrder = sortOrder;
            this.vision = vision;
            this.webSearch = webSearch;
            this.tools = tools;
        }

        static Model basic(String id, String name) {
            return new Model(id, name, "", "other", "available", false,
                    Integer.MAX_VALUE, false, true, false);
        }

        boolean isAvailable() {
            return "available".equals(status);
        }
    }

    private interface StreamHandler {
        void onChunk(String chunk);
    }
}
