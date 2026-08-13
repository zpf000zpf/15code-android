package com.fifteencode.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
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
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
    private static final String SEARCH_CHAT = PLATFORM + "/api/search-chat";
    private static final String ANDROID_RELEASES = "https://github.com/zpf000zpf/15code-android/releases";
    private static final String ANDROID_LATEST_RELEASE = "https://api.github.com/repos/zpf000zpf/15code-android/releases/latest";
    private static final String PREFS = "15code_android";
    private static final String APP_VERSION = "1.4.7";
    private static final int SUPPORTED_CATALOG_SCHEMA_VERSION = 1;
    private static final String PREFERRED_MODEL = "qwen3.6";
    private static final int PICK_IMAGE_REQUEST = 7301;
    private static final int MAX_HISTORY_MESSAGES = 20;
    private static final int MAX_HISTORY_IMAGE_VERSIONS = 20;
    private static final int MAX_IMAGE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_GENERATED_IMAGE_BYTES = 16 * 1024 * 1024;
    private static final int CARD_PREVIEW_MAX_DIMENSION = 720;
    private static final int ATTACHMENT_PREVIEW_MAX_DIMENSION = 256;
    private static final long STREAM_RENDER_INTERVAL_MS = 100;
    private static final long DRAFT_SAVE_DELAY_MS = 350;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private SecurePreferences securePrefs;
    private ChatDao chatDao;
    private final ExecutorService storageExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService imageExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService imagePreviewExecutor = Executors.newSingleThreadExecutor();
    private String currentConversationId;
    private volatile String currentDraft = "";
    private String promptDraftConversationId;
    private Runnable pendingDraftSave;
    private boolean restoringDraft;
    private long draftEditRevision;
    private long conversationLoadGeneration;
    private boolean composerSmokeMode;
    private volatile boolean historyLoadStarted;
    private String sessionToken;
    private String goKey;
    private String selectedModel;
    private String accountEmail;
    private String selectedImageDataUrl;
    private String selectedImageLocalPath;
    private String selectedImageMimeType;
    private String selectedImageName;
    private String selectedImageVersionId;
    private String selectedImageConversationId;
    private boolean selectedImageOwnedFile;
    private Bitmap selectedImagePreviewBitmap;
    private volatile long imageSelectionGeneration;
    private long lastTimelineTimestamp;
    private volatile boolean imageRequestRunning;
    private volatile boolean imageAttachmentLoading;
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
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        securePrefs = new SecurePreferences(this);
        migratePlaintextCredentials();
        sessionToken = securePrefs.get("sessionToken");
        goKey = securePrefs.get("goKey");
        chatDao = ChatDatabase.get(this).chatDao();
        composerSmokeMode = isDebugBuild() && getIntent().getBooleanExtra("smokeComposer", false);
        if (composerSmokeMode) {
            String smokeId = getIntent().getStringExtra("smokeConversationId");
            currentConversationId = smokeId != null && smokeId.matches("[A-Za-z0-9._-]{1,80}")
                    ? "smoke-" + smokeId : "smoke-composer";
        } else {
            currentConversationId = prefs.getString("currentConversationId", "");
            if (currentConversationId == null || currentConversationId.isEmpty()) {
                currentConversationId = UUID.randomUUID().toString();
                prefs.edit().putString("currentConversationId", currentConversationId).apply();
            }
        }
        selectedModel = prefs.getString("model", null);
        accountEmail = prefs.getString("accountEmail", "");
        try { credits = Double.parseDouble(prefs.getString("creditsUsd", "0")); }
        catch (Exception ignored) { credits = 0; }
        loadCachedModels();
        if (selectedModel == null || selectedModel.isEmpty()) selectedModel = PREFERRED_MODEL;
        buildUi();
        if (composerSmokeMode) {
            showSmokeComposer();
        } else {
            if (sessionToken != null) restoreSession();
            root.postDelayed(() -> checkAppUpdate(false), 1800);
        }
    }

    @Override
    protected void onPause() {
        flushCurrentDraft();
        persistCurrentImageSelection();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        flushCurrentDraft();
        if (pendingDraftSave != null) uiHandler.removeCallbacks(pendingDraftSave);
        storageExecutor.shutdown();
        imageExecutor.shutdownNow();
        imagePreviewExecutor.shutdownNow();
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
        Uri uri = data.getData();
        final String conversationId = currentConversationId;
        String detectedMime = getContentResolver().getType(uri);
        final String mime = detectedMime != null && detectedMime.startsWith("image/")
                ? detectedMime : "image/jpeg";
        final long selectionGeneration = beginImageSelection();
        setBusy(true, "正在读取图片...");
        imagePreviewExecutor.execute(() -> {
            try {
                byte[] bytes = readLimitedBytes(uri, MAX_IMAGE_BYTES);
                Bitmap preview = decodeSampledBitmap(bytes, ATTACHMENT_PREVIEW_MAX_DIMENSION);
                File attachment = persistAlbumAttachment(conversationId, bytes, mime);
                uiHandler.post(() -> finishAlbumImageSelection(selectionGeneration,
                        conversationId, attachment.getAbsolutePath(), mime, preview));
            } catch (Exception e) {
                uiHandler.post(() -> failImageSelection(selectionGeneration, e));
            }
        });
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
        promptInput.setFocusable(true);
        promptInput.setFocusableInTouchMode(true);
        promptInput.setCursorVisible(true);
        promptInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        promptInput.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        promptInput.setBackground(makeBg(0xFFFFFFFF, 0xFFCBD5E1, dp(18)));
        promptInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable editable) {
                if (restoringDraft) return;
                currentDraft = editable.toString();
                promptDraftConversationId = currentConversationId;
                draftEditRevision++;
                prefs.edit().putString(draftFallbackKey(currentConversationId), currentDraft).apply();
                scheduleDraftSave(currentConversationId, currentDraft);
            }
        });
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(0, dp(56), 1);
        inputLp.setMargins(0, 0, dp(8), 0);
        composer.addView(promptInput, inputLp);

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
        attachButton.setContentDescription("添加图片或生成图片");
        attachButton.setOnClickListener(this::showImageComposerMenu);
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
    }

    private void showHeaderMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("检查更新");
        if (sessionToken != null) menu.getMenu().add("会话列表");
        if (sessionToken != null) menu.getMenu().add("退出登录");
        menu.setOnMenuItemClickListener(item -> {
            if ("检查更新".contentEquals(item.getTitle())) checkAppUpdate(true);
            else if ("会话列表".contentEquals(item.getTitle())) showConversationList("");
            else if ("退出登录".contentEquals(item.getTitle())) logout();
            return true;
        });
        menu.show();
    }

    private void showImageComposerMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("从相册添加图片");
        if (sessionToken != null && goKey != null) menu.getMenu().add("在当前对话中生成/修改图片");
        menu.setOnMenuItemClickListener(item -> {
            if ("从相册添加图片".contentEquals(item.getTitle())) pickImage();
            else showImageStudioDialog();
            return true;
        });
        menu.show();
    }

    private void showImageStudioDialog() {
        EditText input = new EditText(this);
        input.setHint(!hasSelectedImage() ? "描述要生成的图片" : "描述要生成的图片，或修改已附加图片");
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
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("图片生成与编辑")
                .setView(panel)
                .setNegativeButton("取消", null)
                .setPositiveButton("生成", null);
        if (hasSelectedImage()) builder.setNeutralButton("修改已附加图片", null);
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String prompt = input.getText().toString().trim();
                if (prompt.isEmpty()) { input.setError("请输入提示词"); return; }
                dialog.dismiss();
                requestImage(prompt, false, imageSizeValue(size), imageQualityValue(quality), imageFormatValue(format));
            });
            Button editButton = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            if (editButton != null) editButton.setOnClickListener(v -> {
                String prompt = input.getText().toString().trim();
                if (prompt.isEmpty()) { input.setError("请输入修改要求"); return; }
                dialog.dismiss();
                requestImage(prompt, true, imageSizeValue(size), imageQualityValue(quality),
                        imageFormatValue(format));
            });
        });
        dialog.show();
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
        if (historyLoadStarted || streaming) {
            toast("请等待当前对话完成加载或生成");
            return;
        }
        if (imageRequestRunning) {
            toast("上一张图片还在处理中");
            return;
        }
        if (edit && !hasSelectedImage()) {
            toast("请先附加要修改的图片");
            return;
        }
        imageRequestRunning = true;
        updateChatControls();
        final String versionId = UUID.randomUUID().toString();
        final String parentVersionId = edit ? selectedImageVersionId : null;
        final String requestId = (edit ? "img-edit-" : "img-") + UUID.randomUUID();
        final long createdAt = nextTimelineTimestamp();
        final String conversationId = currentConversationId;
        final String inputImageDataUrl = selectedImageDataUrl;
        final String inputImageLocalPath = selectedImageLocalPath;
        final String inputImageMimeType = selectedImageMimeType;
        String promptMessage = (edit ? "修改图片：" : "生成图片：") + prompt;
        addBubble("你", promptMessage, true);
        appendTimelineMessage("user", promptMessage, createdAt);
        setBusy(true, edit ? "正在修改图片..." : "正在生成图片...");
        imageExecutor.execute(() -> {
            ImageVersionEntity version = new ImageVersionEntity(versionId, conversationId,
                    parentVersionId, edit ? "edit" : "generation", "running", prompt,
                    null, null, null, size, quality, format, requestId, createdAt, 0);
            try {
                String title = imageConversationTitle(prompt);
                chatDao.saveImageVersionWithConversation(version,
                        new ConversationEntity(conversationId, title, false, false,
                                createdAt, createdAt, ""));
                JSONObject result;
                if (edit) result = postImageEdit(prompt, inputImageDataUrl, inputImageLocalPath,
                        inputImageMimeType, size, quality, format, requestId);
                else {
                    JSONObject body = new JSONObject();
                    body.put("model", "gpt-image-2"); body.put("prompt", prompt);
                    body.put("size", size); body.put("quality", quality); body.put("output_format", format);
                    result = postJson(IMAGE_GENERATIONS, body, goKey, false, requestId);
                }
                JSONArray data = result.optJSONArray("data");
                String encoded = data == null || data.length() == 0 ? "" : data.optJSONObject(0).optString("b64_json", "");
                if (encoded.isEmpty()) throw new Exception("图片服务没有返回图片数据");
                String mime = "jpeg".equals(format) ? "image/jpeg" : "image/" + format;
                byte[] bytes = Base64.decode(encoded, Base64.DEFAULT);
                if (bytes.length > MAX_GENERATED_IMAGE_BYTES) throw new IOException("生成图片文件过大");
                File imageFile = persistImageVersion(conversationId, versionId, bytes, format);
                long completedAt = nextTimelineTimestamp();
                version.status = "succeeded";
                version.localPath = imageFile.getAbsolutePath();
                version.thumbnailPath = imageFile.getAbsolutePath();
                version.mimeType = mime;
                version.completedAt = completedAt;
                chatDao.completeImageVersion(versionId, version.status, version.localPath,
                        version.thumbnailPath, version.mimeType, completedAt);
                uiHandler.post(() -> {
                    if (conversationId.equals(currentConversationId)) {
                        showImageResult(version);
                    } else {
                        toast("图片已生成并保存到原对话");
                    }
                });
            } catch (Exception e) {
                long completedAt = nextTimelineTimestamp();
                version.status = "failed";
                version.completedAt = completedAt;
                chatDao.completeImageVersion(versionId, version.status, null, null, null, completedAt);
                String message = e.getMessage() != null && e.getMessage().contains("HTTP 403") ? "当前账号尚未开通图片权限" : friendlyError(e);
                uiHandler.post(() -> {
                    if (conversationId.equals(currentConversationId)) {
                        addBubble("15code", "图片任务失败：" + message, false);
                    } else {
                        toast("原对话中的图片任务失败：" + message);
                    }
                });
            } finally {
                uiHandler.post(() -> {
                    imageRequestRunning = false;
                    setBusy(false, "已连接 15code");
                    updateChatControls();
                });
            }
        });
    }

    private JSONObject postImageEdit(String prompt, String dataUrl, String localPath,
                                     String inputMimeType, String size, String quality,
                                     String format, String requestId) throws Exception {
        if ((dataUrl == null || dataUrl.isEmpty()) && (localPath == null || localPath.isEmpty())) {
            throw new Exception("请先附加要修改的图片");
        }
        String mimeType = inputMimeType == null || !inputMimeType.startsWith("image/")
                ? imageMimeType(dataUrl) : inputMimeType;
        String boundary = "----15code-" + UUID.randomUUID();
        HttpURLConnection conn = (HttpURLConnection) new URL(IMAGE_EDITS).openConnection();
        conn.setConnectTimeout(20000); conn.setReadTimeout(180000); conn.setRequestMethod("POST"); conn.setDoOutput(true);
        conn.setRequestProperty("Authorization", "Bearer " + goKey);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        conn.setRequestProperty("X-Client-Request-Id", requestId);
        conn.setChunkedStreamingMode(8192);
        try (OutputStream out = conn.getOutputStream()) {
            writeMultipartField(out, boundary, "model", "gpt-image-2"); writeMultipartField(out, boundary, "prompt", prompt);
            writeMultipartField(out, boundary, "size", size); writeMultipartField(out, boundary, "quality", quality);
            writeMultipartField(out, boundary, "output_format", format);
            out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"image\"; filename=\"input."
                    + imageExtension(mimeType) + "\"\r\nContent-Type: " + mimeType + "\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            if (localPath != null && !localPath.isEmpty()) {
                copyFileToStreamBounded(localPath, out, MAX_GENERATED_IMAGE_BYTES);
            } else {
                int comma = dataUrl.indexOf(',');
                byte[] image = Base64.decode(comma >= 0 ? dataUrl.substring(comma + 1) : dataUrl,
                        Base64.DEFAULT);
                if (image.length > MAX_IMAGE_BYTES) throw new IOException("图片过大，请选择 4 MB 以内的图片");
                out.write(image);
            }
            out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        String text = readAll(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
        if (code < 200 || code >= 300) throw new Exception("HTTP " + code + ": " + text);
        return new JSONObject(text);
    }

    private void writeMultipartField(OutputStream out, String boundary, String name, String value) throws IOException {
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private File persistImageVersion(String conversationId, String versionId, byte[] bytes, String format) throws IOException {
        File directory = new File(getFilesDir(), "image-conversations/" + conversationId);
        if (!directory.exists() && !directory.mkdirs()) throw new IOException("无法创建图片会话目录");
        String extension = "jpeg".equals(format) ? "jpg" : "webp".equals(format) ? "webp" : "png";
        File target = new File(directory, versionId + "." + extension);
        try (FileOutputStream output = new FileOutputStream(target)) { output.write(bytes); }
        return target;
    }

    private File persistAlbumAttachment(String conversationId, byte[] bytes, String mimeType)
            throws IOException {
        File directory = new File(getFilesDir(), "image-attachments/" + conversationId);
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("无法创建图片附件目录");
        }
        File target = new File(directory,
                "attachment-" + UUID.randomUUID() + "." + imageExtension(mimeType));
        try (FileOutputStream output = new FileOutputStream(target)) {
            output.write(bytes);
        }
        return target;
    }

    private void showImageResult(ImageVersionEntity version) {
        final String mimeType = safeImageMimeType(version);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(10), dp(10), dp(10), dp(10));
        card.setBackground(makeBg(0xFFFFFFFF, 0xFFE2E8F0, dp(14)));
        TextView caption = new TextView(this);
        caption.setText(("edit".equals(version.operation) ? "修改结果" : "生成结果")
                + (version.prompt == null || version.prompt.isEmpty() ? "" : " · " + version.prompt));
        caption.setTextColor(0xFF334155);
        caption.setTextSize(13);
        caption.setPadding(dp(2), 0, dp(2), dp(8));
        card.addView(caption, new LinearLayout.LayoutParams(-1, -2));
        ImageView preview = new ImageView(this);
        preview.setAdjustViewBounds(true);
        preview.setMinimumHeight(dp(160));
        preview.setContentDescription("生成图片");
        card.addView(preview, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.RIGHT);
        Button edit = new Button(this); edit.setText("继续修改"); edit.setAllCaps(false);
        Button save = new Button(this); save.setText("保存"); save.setAllCaps(false);
        actions.addView(edit, new LinearLayout.LayoutParams(dp(110), dp(48)));
        actions.addView(save, new LinearLayout.LayoutParams(dp(82), dp(48)));
        card.addView(actions, new LinearLayout.LayoutParams(-1, dp(52)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(8), dp(42), dp(8));
        messageList.addView(card, lp);
        edit.setOnClickListener(v -> selectGeneratedImageVersion(version, true));
        save.setOnClickListener(v -> saveImageToGallery(version.localPath, mimeType));
        loadImageCardPreview(version, preview);
        scrollToChatBottom();
    }

    private String imageMimeType(String dataUrl) {
        if (dataUrl != null && dataUrl.startsWith("data:image/jpeg;")) return "image/jpeg";
        if (dataUrl != null && dataUrl.startsWith("data:image/webp;")) return "image/webp";
        return "image/png";
    }

    private void saveImageToGallery(String localPath, String mimeType) {
        imagePreviewExecutor.execute(() -> {
            try {
                String extension = imageExtension(mimeType);
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME,
                        "15code-image-" + System.currentTimeMillis() + "." + extension);
                values.put(MediaStore.Images.Media.MIME_TYPE, mimeType);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.put(MediaStore.Images.Media.RELATIVE_PATH,
                            Environment.DIRECTORY_PICTURES + "/15code");
                }
                Uri uri = getContentResolver().insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new IOException("无法创建图片文件");
                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out == null) throw new IOException("无法写入图片");
                    copyFileToStreamBounded(localPath, out, MAX_GENERATED_IMAGE_BYTES);
                }
                uiHandler.post(() -> toast("图片已保存到系统相册"));
            } catch (Exception e) {
                uiHandler.post(() -> toast("保存失败：" + e.getMessage()));
            }
        });
    }

    private long beginImageSelection() {
        deleteSelectedImageOwnedFile();
        long generation = ++imageSelectionGeneration;
        imageAttachmentLoading = true;
        selectedImageDataUrl = null;
        selectedImageLocalPath = null;
        selectedImageMimeType = null;
        selectedImageName = null;
        selectedImageVersionId = null;
        selectedImageConversationId = null;
        selectedImageOwnedFile = false;
        selectedImagePreviewBitmap = null;
        updateAttachmentPreview();
        updateChatControls();
        return generation;
    }

    private void finishAlbumImageSelection(long generation, String conversationId,
                                           String localPath, String mimeType, Bitmap preview) {
        if (generation != imageSelectionGeneration || isFinishing() || isDestroyed()
                || !conversationId.equals(currentConversationId)) {
            deleteFileQuietly(localPath);
            return;
        }
        selectedImageDataUrl = null;
        selectedImageLocalPath = localPath;
        selectedImageMimeType = mimeType;
        selectedImageName = "图片";
        selectedImageVersionId = null;
        selectedImageConversationId = conversationId;
        selectedImageOwnedFile = true;
        selectedImagePreviewBitmap = preview;
        imageAttachmentLoading = false;
        updateAttachmentPreview();
        try {
            boolean switched = selectVisionModelForImage();
            Model imageModel = findModel(selectedModel);
            if (imageModel == null || !imageModel.isAvailable() || !imageModel.vision) {
                throw new Exception("当前目录没有可用的图片模型");
            }
            statusText.setText(switched
                    ? "已附加图片 · 已切换到 " + modelLabel(selectedModel)
                    : "已附加图片 · 当前模型 " + modelLabel(selectedModel));
            persistCurrentImageSelection();
            setBusy(false, statusText.getText().toString());
            updateChatControls();
        } catch (Exception error) {
            clearSelectedImageState();
            setBusy(false, "已连接 15code");
            updateChatControls();
            toast(error.getMessage());
        }
    }

    private void failImageSelection(long generation, Exception error) {
        if (generation != imageSelectionGeneration || isFinishing() || isDestroyed()) return;
        clearSelectedImageState();
        setBusy(false, "已连接 15code");
        toast(error.getMessage());
    }

    private void selectGeneratedImageVersion(ImageVersionEntity version, boolean openEditor) {
        if (version == null || version.localPath == null || version.localPath.isEmpty()) {
            toast("图片文件不可用");
            return;
        }
        final long generation = beginImageSelection();
        setBusy(true, "正在准备继续修改...");
        imagePreviewExecutor.execute(() -> {
            try {
                File file = new File(version.localPath);
                if (!file.isFile()) throw new IOException("图片文件不存在");
                if (file.length() > MAX_GENERATED_IMAGE_BYTES) throw new IOException("图片文件过大");
                Bitmap preview = decodeSampledBitmap(file, ATTACHMENT_PREVIEW_MAX_DIMENSION);
                uiHandler.post(() -> {
                    if (generation != imageSelectionGeneration || isFinishing() || isDestroyed()) return;
                    selectedImageDataUrl = null;
                    selectedImageLocalPath = version.localPath;
                    selectedImageMimeType = safeImageMimeType(version);
                    selectedImageName = "生成图片";
                    selectedImageVersionId = version.id;
                    selectedImageConversationId = version.conversationId;
                    selectedImageOwnedFile = false;
                    selectedImagePreviewBitmap = preview;
                    imageAttachmentLoading = false;
                    persistCurrentImageSelection();
                    updateAttachmentPreview();
                    setBusy(false, "已选择图片版本 · 可继续修改");
                    updateChatControls();
                    if (openEditor) showImageStudioDialog();
                });
            } catch (Exception error) {
                uiHandler.post(() -> {
                    if (generation != imageSelectionGeneration || isFinishing() || isDestroyed()) return;
                    clearSelectedImageState();
                    setBusy(false, "已连接 15code");
                    updateChatControls();
                    toast(error.getMessage());
                });
            }
        });
    }

    private void loadImageCardPreview(ImageVersionEntity version, ImageView target) {
        if (version == null || version.localPath == null || version.localPath.isEmpty()) return;
        final String conversationId = currentConversationId;
        final long loadGeneration = conversationLoadGeneration;
        imagePreviewExecutor.execute(() -> {
            try {
                Bitmap preview = decodeSampledBitmap(new File(version.localPath),
                        CARD_PREVIEW_MAX_DIMENSION);
                uiHandler.post(() -> {
                    if (conversationId.equals(currentConversationId)
                            && loadGeneration == conversationLoadGeneration
                            && target.isAttachedToWindow()) {
                        target.setImageBitmap(preview);
                    }
                });
            } catch (Exception ignored) {
                uiHandler.post(() -> {
                    if (target.isAttachedToWindow()) target.setContentDescription("图片文件不可用");
                });
            }
        });
    }

    private Bitmap decodeSampledBitmap(byte[] bytes, int maxDimension) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        BitmapFactory.Options options = sampledOptions(bounds.outWidth, bounds.outHeight, maxDimension);
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (bitmap == null) throw new IOException("无法解析图片");
        return bitmap;
    }

    private Bitmap decodeSampledBitmap(File file, int maxDimension) throws IOException {
        if (file == null || !file.isFile()) throw new IOException("图片文件不存在");
        if (file.length() > MAX_GENERATED_IMAGE_BYTES) throw new IOException("图片文件过大");
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        BitmapFactory.Options options = sampledOptions(bounds.outWidth, bounds.outHeight, maxDimension);
        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        if (bitmap == null) throw new IOException("无法解析图片");
        return bitmap;
    }

    private BitmapFactory.Options sampledOptions(int width, int height, int maxDimension)
            throws IOException {
        if (width <= 0 || height <= 0) throw new IOException("无效图片");
        int sample = 1;
        while (width / sample > maxDimension * 2 || height / sample > maxDimension * 2) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        return options;
    }

    private void copyFileToStreamBounded(String localPath, OutputStream out, int limit)
            throws IOException {
        File source = new File(localPath);
        if (!source.isFile()) throw new IOException("图片文件不存在");
        if (source.length() > limit) throw new IOException("图片文件过大");
        try (InputStream input = new FileInputStream(source)) {
            byte[] chunk = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(chunk)) != -1) {
                total += read;
                if (total > limit) throw new IOException("图片文件过大");
                out.write(chunk, 0, read);
            }
        }
    }

    private String safeImageMimeType(ImageVersionEntity version) {
        if (version.mimeType != null && version.mimeType.startsWith("image/")) {
            return version.mimeType;
        }
        if ("jpeg".equals(version.format) || "jpg".equals(version.format)) return "image/jpeg";
        if ("webp".equals(version.format)) return "image/webp";
        return "image/png";
    }

    private String imageExtension(String mimeType) {
        if ("image/jpeg".equals(mimeType)) return "jpg";
        if ("image/webp".equals(mimeType)) return "webp";
        return "png";
    }

    private void updateAttachmentPreview() {
        if (attachmentPreview == null) return;
        if (!hasSelectedImage()) {
            attachmentPreview.setVisibility(View.GONE);
            if (attachmentImage != null) attachmentImage.setImageDrawable(null);
            if (attachButton != null) attachButton.setText("＋");
            return;
        }
        attachmentImage.setImageBitmap(selectedImagePreviewBitmap);
        attachmentPreview.setVisibility(View.VISIBLE);
        attachButton.setText("图");
    }

    private void clearSelectedImage() {
        clearSelectedImageState();
        statusText.setText("已移除图片");
    }

    private void clearSelectedImageState() {
        deleteSelectedImageOwnedFile();
        imageSelectionGeneration++;
        selectedImageDataUrl = null;
        selectedImageLocalPath = null;
        selectedImageMimeType = null;
        selectedImageName = null;
        selectedImageVersionId = null;
        selectedImageConversationId = null;
        selectedImageOwnedFile = false;
        selectedImagePreviewBitmap = null;
        imageAttachmentLoading = false;
        updateAttachmentPreview();
        updateChatControls();
    }

    private void resetImageSelectionForConversationChange() {
        imageSelectionGeneration++;
        selectedImageDataUrl = null;
        selectedImageLocalPath = null;
        selectedImageMimeType = null;
        selectedImageName = null;
        selectedImageVersionId = null;
        selectedImageConversationId = null;
        selectedImageOwnedFile = false;
        selectedImagePreviewBitmap = null;
        imageAttachmentLoading = false;
        updateAttachmentPreview();
        updateChatControls();
    }

    private void persistCurrentImageSelection() {
        if (prefs == null || currentConversationId == null) return;
        String prefix = imageSelectionKeyPrefix(currentConversationId);
        SharedPreferences.Editor editor = prefs.edit();
        if (!hasSelectedImage()) {
            editor.remove(prefix + "conversationId")
                    .remove(prefix + "localPath")
                    .remove(prefix + "mimeType")
                    .remove(prefix + "name")
                    .remove(prefix + "versionId")
                    .remove(prefix + "owned")
                    .apply();
            return;
        }
        editor.putString(prefix + "conversationId", currentConversationId)
                .putString(prefix + "localPath", selectedImageLocalPath)
                .putString(prefix + "mimeType", selectedImageMimeType)
                .putString(prefix + "name", selectedImageName)
                .putString(prefix + "versionId", selectedImageVersionId)
                .putBoolean(prefix + "owned", selectedImageOwnedFile)
                .apply();
    }

    private void restoreImageSelection(String conversationId, long loadGeneration) {
        String prefix = imageSelectionKeyPrefix(conversationId);
        String savedConversationId = prefs.getString(prefix + "conversationId", null);
        String localPath = prefs.getString(prefix + "localPath", null);
        String mimeType = prefs.getString(prefix + "mimeType", null);
        String name = prefs.getString(prefix + "name", null);
        String versionId = prefs.getString(prefix + "versionId", null);
        boolean owned = prefs.getBoolean(prefix + "owned", false);
        if (!conversationId.equals(savedConversationId) || localPath == null || localPath.isEmpty()) {
            return;
        }
        File file = new File(localPath);
        if (!file.isFile() || file.length() > MAX_GENERATED_IMAGE_BYTES) {
            clearPersistedImageSelection(conversationId);
            return;
        }
        imageAttachmentLoading = true;
        updateChatControls();
        final long selectionGeneration = ++imageSelectionGeneration;
        imagePreviewExecutor.execute(() -> {
            try {
                Bitmap preview = decodeSampledBitmap(file, ATTACHMENT_PREVIEW_MAX_DIMENSION);
                uiHandler.post(() -> {
                    if (!conversationId.equals(currentConversationId)
                            || loadGeneration != conversationLoadGeneration
                            || selectionGeneration != imageSelectionGeneration
                            || isFinishing() || isDestroyed()) return;
                    selectedImageDataUrl = null;
                    selectedImageLocalPath = localPath;
                    selectedImageMimeType = mimeType;
                    selectedImageName = name;
                    selectedImageVersionId = versionId;
                    selectedImageConversationId = conversationId;
                    selectedImageOwnedFile = owned;
                    selectedImagePreviewBitmap = preview;
                    imageAttachmentLoading = false;
                    updateAttachmentPreview();
                    updateChatControls();
                });
            } catch (Exception ignored) {
                uiHandler.post(() -> {
                    if (selectionGeneration != imageSelectionGeneration) return;
                    imageAttachmentLoading = false;
                    clearPersistedImageSelection(conversationId);
                    updateChatControls();
                });
            }
        });
    }

    private void clearPersistedImageSelection(String conversationId) {
        if (conversationId == null || conversationId.isEmpty()) return;
        String prefix = imageSelectionKeyPrefix(conversationId);
        prefs.edit().remove(prefix + "conversationId")
                .remove(prefix + "localPath")
                .remove(prefix + "mimeType")
                .remove(prefix + "name")
                .remove(prefix + "versionId")
                .remove(prefix + "owned")
                .apply();
    }

    private String imageSelectionKeyPrefix(String conversationId) {
        return "pendingImageSelection:" + conversationId + ":";
    }

    private void deleteSelectedImageOwnedFile() {
        if (selectedImageOwnedFile && selectedImageLocalPath != null) {
            deleteFileQuietly(selectedImageLocalPath);
        }
        clearPersistedImageSelection(selectedImageConversationId != null
                ? selectedImageConversationId : currentConversationId);
    }

    private void deleteFileQuietly(String localPath) {
        if (localPath == null || localPath.isEmpty()) return;
        try {
            File file = new File(localPath);
            if (file.isFile()) file.delete();
        } catch (Exception ignored) {}
    }

    private boolean hasSelectedImage() {
        return (selectedImageDataUrl != null && !selectedImageDataUrl.isEmpty())
                || (selectedImageLocalPath != null && !selectedImageLocalPath.isEmpty()
                && currentConversationId != null
                && currentConversationId.equals(selectedImageConversationId));
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
        if (getIntent().getBooleanExtra("smokeResetDraft", false)) {
            draftEditRevision++;
            restorePromptDraft("");
            persistDraftAsync(currentConversationId, "", true);
        }
        if (getIntent().getBooleanExtra("smokeStreaming", false)) {
            setStreamingUi(true);
            uiHandler.postDelayed(() -> {
                TextView bubble = addBubble("15code", "流式输入稳定性测试", false);
                runComposerStreamingSmokeFrame(bubble, 0);
            }, 700);
        }
    }

    private void runComposerStreamingSmokeFrame(TextView bubble, int frame) {
        if (!composerSmokeMode || frame >= 120) return;
        updateBubble(bubble, "15code", "流式输入稳定性测试 · " + frame);
        uiHandler.postDelayed(() -> runComposerStreamingSmokeFrame(bubble, frame + 1), 50);
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
        if (streaming) return;
        if (imageAttachmentLoading || imageRequestRunning) {
            toast(imageAttachmentLoading ? "图片正在读取" : "图片正在生成");
            return;
        }
        if (historyLoadStarted) {
            toast("对话正在加载，文字已保存在输入框中");
            return;
        }
        String text = promptInput.getText().toString().trim();
        if (text.isEmpty() && !hasSelectedImage()) {
            toast("请输入消息");
            return;
        }
        sendMessageText(text);
    }

    private void sendMessageText(String text) {
        String attachedImage = selectedImageDataUrl;
        String attachedImagePath = selectedImageLocalPath;
        String attachedImageMime = selectedImageMimeType;
        String imageName = selectedImageName;
        boolean deleteAttachedFileAfterRead = selectedImageOwnedFile;
        if ((attachedImage == null || attachedImage.isEmpty())
                && attachedImagePath != null && !attachedImagePath.isEmpty()) {
            final String pendingPath = attachedImagePath;
            final String pendingMime = attachedImageMime;
            final String pendingName = imageName;
            final boolean pendingOwnedFile = deleteAttachedFileAfterRead;
            selectedImageOwnedFile = false;
            imageAttachmentLoading = true;
            updateChatControls();
            setBusy(true, "正在准备图片消息...");
            imagePreviewExecutor.execute(() -> {
                try {
                    byte[] bytes = readLimitedFileBytes(pendingPath, MAX_GENERATED_IMAGE_BYTES);
                    String mime = pendingMime == null ? "image/png" : pendingMime;
                    String dataUrl = "data:" + mime + ";base64,"
                            + Base64.encodeToString(bytes, Base64.NO_WRAP);
                    uiHandler.post(() -> {
                        imageAttachmentLoading = false;
                        if (pendingOwnedFile) deleteFileQuietly(pendingPath);
                        sendMessageTextWithAttachment(text, dataUrl, pendingName);
                    });
                } catch (Exception error) {
                    uiHandler.post(() -> {
                        imageAttachmentLoading = false;
                        if (pendingOwnedFile) selectedImageOwnedFile = true;
                        setBusy(false, "已连接 15code");
                        updateChatControls();
                        toast("读取附加图片失败：" + error.getMessage());
                    });
                }
            });
            return;
        }
        sendMessageTextWithAttachment(text, attachedImage, imageName);
    }

    private void sendMessageTextWithAttachment(String text, String attachedImage, String imageName) {
        final String requestModel = selectedModel;
        final boolean requestForceWebSearch = forceWebSearch;
        cancelPendingDraftSave();
        restorePromptDraft("");
        persistDraftAsync(currentConversationId, "", false);
        clearSelectedImageState();

        String displayText = text.isEmpty() ? "[图片]" : text;
        if (attachedImage != null) displayText += "\n[已附加" + (imageName == null ? "图片" : imageName) + "]";
        addBubble("你", displayText, true);
        try {
            JSONObject user = new JSONObject();
            user.put("role", "user");
            user.put("content", text.isEmpty() ? "[图片]" : text);
            user.put("createdAt", nextTimelineTimestamp());
            messages.put(user);
            trimMessages();
            saveChatHistory();
        } catch (Exception ignored) {}

        TextView assistantBubble = addBubble(modelLabel(requestModel), "正在思考...", false);
        setStreamingUi(true);
        resetStreamRenderState();
        final String requestImageDataUrl = attachedImage;
        new Thread(() -> {
            StringBuilder answer = new StringBuilder();
            JSONObject body = new JSONObject();
            try {
                body.put("model", requestModel);
                body.put("stream", true);
                if (requestForceWebSearch) {
                    body.put("webSearch", true);
                } else {
                    body.put("searchMode", "auto");
                }
                body.put("max_tokens", 4096);
                body.put("messages", buildRequestMessages(text, requestImageDataUrl));
                streamChat(body, chunk -> {
                    answer.append(chunk);
                    queueBubbleUpdate(assistantBubble, modelLabel(requestModel), answer.toString());
                });
                flushBubbleUpdate(assistantBubble, modelLabel(requestModel), answer.toString());
                if (answer.length() == 0) throw new Exception("模型返回为空，请换模型重试");
                JSONObject assistant = new JSONObject();
                assistant.put("role", "assistant");
                assistant.put("content", answer.toString());
                assistant.put("createdAt", nextTimelineTimestamp());
                messages.put(assistant);
                trimMessages();
                saveChatHistory();
            } catch (Exception e) {
                if (stopRequested) {
                    if (answer.length() > 0) {
                        try {
                            JSONObject assistant = new JSONObject();
                            assistant.put("role", "assistant");
                            assistant.put("content", answer.toString());
                            assistant.put("createdAt", nextTimelineTimestamp());
                            messages.put(assistant);
                            trimMessages();
                            saveChatHistory();
                        } catch (Exception ignored) {}
                        flushBubbleUpdate(assistantBubble, modelLabel(requestModel), answer + "\n\n[已停止]");
                    } else {
                        flushBubbleUpdate(assistantBubble, modelLabel(requestModel), "[已停止]");
                    }
                } else if (answer.length() == 0 && isRetryableStreamError(e)) {
                    try {
                        flushBubbleUpdate(assistantBubble, modelLabel(requestModel), "连接不稳定，正在切换普通模式...");
                        body.put("stream", false);
                        String fallback = completeChat(body);
                        if (fallback.isEmpty()) throw new Exception("模型返回为空，请换模型重试");
                        JSONObject assistant = new JSONObject();
                        assistant.put("role", "assistant");
                        assistant.put("content", fallback);
                        assistant.put("createdAt", nextTimelineTimestamp());
                        messages.put(assistant);
                        trimMessages();
                        saveChatHistory();
                        flushBubbleUpdate(assistantBubble, modelLabel(requestModel), fallback);
                    } catch (Exception fallbackError) {
                        flushBubbleUpdate(assistantBubble, "错误", friendlyError(fallbackError));
                    }
                } else if (answer.length() == 0) {
                    flushBubbleUpdate(assistantBubble, "错误", friendlyError(e));
                } else {
                    flushBubbleUpdate(assistantBubble, modelLabel(requestModel), answer + "\n\n[连接中断]");
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
            List<MessageEntity> rows = new ArrayList<>();
            long previousTimestamp = 0;
            for (int i = 0; i < saved.length(); i++) {
                JSONObject item = saved.optJSONObject(i);
                if (item == null) continue;
                long timestamp = item.optLong("createdAt", 0);
                if (timestamp <= 0) timestamp = now + i;
                if (timestamp <= previousTimestamp) timestamp = previousTimestamp + 1;
                previousTimestamp = timestamp;
                rows.add(new MessageEntity(conversationId, item.optString("role"),
                        item.optString("content"), timestamp));
            }
            chatDao.replaceConversationMessages(new ConversationEntity(conversationId, title, pinned,
                    deleted, createdAt, now, ""), rows);
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

    private String imageConversationTitle(String prompt) {
        String title = prompt == null ? "" : prompt.replace('\n', ' ').trim();
        if (title.length() > 24) title = title.substring(0, 24) + "…";
        return title.isEmpty() ? "图片对话" : title;
    }

    private synchronized long nextTimelineTimestamp() {
        long now = System.currentTimeMillis();
        if (now <= lastTimelineTimestamp) now = lastTimelineTimestamp + 1;
        lastTimelineTimestamp = now;
        return now;
    }

    private void appendTimelineMessage(String role, String content, long createdAt) {
        try {
            JSONObject message = new JSONObject();
            message.put("role", role);
            message.put("content", content);
            message.put("createdAt", createdAt);
            messages.put(message);
            trimMessages();
            saveChatHistory();
        } catch (Exception ignored) {}
    }

    private void scheduleDraftSave(String conversationId, String draft) {
        if (conversationId == null || conversationId.isEmpty()) return;
        cancelPendingDraftSave();
        final String value = draft == null ? "" : draft;
        pendingDraftSave = () -> {
            pendingDraftSave = null;
            persistDraftAsync(conversationId, value, false);
        };
        uiHandler.postDelayed(pendingDraftSave, DRAFT_SAVE_DELAY_MS);
    }

    private void flushCurrentDraft() {
        if (promptInput == null || currentConversationId == null || currentConversationId.isEmpty()) return;
        if (!currentConversationId.equals(promptDraftConversationId)) return;
        cancelPendingDraftSave();
        currentDraft = promptInput.getText().toString();
        persistDraftAsync(currentConversationId, currentDraft, true);
    }

    private void cancelPendingDraftSave() {
        if (pendingDraftSave == null) return;
        uiHandler.removeCallbacks(pendingDraftSave);
        pendingDraftSave = null;
    }

    private void persistDraftAsync(String conversationId, String draft, boolean synchronousFallback) {
        if (conversationId == null || conversationId.isEmpty()) return;
        final String value = draft == null ? "" : draft;
        SharedPreferences.Editor fallback = prefs.edit().putString(draftFallbackKey(conversationId), value);
        if (synchronousFallback) fallback.commit();
        else fallback.apply();
        if (storageExecutor.isShutdown()) return;
        storageExecutor.execute(() -> persistDraft(conversationId, value));
    }

    private void persistDraft(String conversationId, String value) {
        try {
            ConversationEntity existing = chatDao.getConversationEntity(conversationId);
            long now = System.currentTimeMillis();
            if (existing == null) {
                chatDao.saveConversation(new ConversationEntity(conversationId, "新对话", false,
                        false, now, now, value));
            } else {
                chatDao.saveDraft(conversationId, value, now);
            }
            String key = draftFallbackKey(conversationId);
            if (value.equals(prefs.getString(key, null))) prefs.edit().remove(key).apply();
        } catch (Exception ignored) {
            // Keep the SharedPreferences fallback for the next process start.
        }
    }

    private String draftFallbackKey(String conversationId) {
        return "pendingDraft:" + conversationId;
    }

    private void restorePromptDraft(String draft) {
        String value = draft == null ? "" : draft;
        restoringDraft = true;
        try {
            promptInput.setText(value);
            promptInput.setSelection(promptInput.getText().length());
            currentDraft = value;
            promptDraftConversationId = currentConversationId;
        } finally {
            restoringDraft = false;
        }
    }

    private void preparePromptForConversation() {
        restoringDraft = true;
        try {
            promptInput.setText("");
            currentDraft = "";
            promptDraftConversationId = null;
        } finally {
            restoringDraft = false;
        }
    }

    private void streamChat(JSONObject body, StreamHandler handler) throws Exception {
        if (stopRequested) throw new Exception("用户已停止生成");
        HttpURLConnection conn = (HttpURLConnection) new URL(SEARCH_CHAT).openConnection();
        activeChatConnection = conn;
        if (stopRequested) {
            conn.disconnect();
            throw new Exception("用户已停止生成");
        }
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
                if (stopRequested) throw new Exception("用户已停止生成");
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
        menuButton.setVisibility(View.VISIBLE);
        statusText.setText("请登录");
    }

    private void showChat() {
        loginPanel.setVisibility(View.GONE);
        chatPanel.setVisibility(View.VISIBLE);
        newChatButton.setVisibility(View.VISIBLE);
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
        updateChatControls();
        final String conversationId = currentConversationId;
        final long loadGeneration = ++conversationLoadGeneration;
        final long editRevisionAtStart = draftEditRevision;
        restoreImageSelection(conversationId, loadGeneration);
        storageExecutor.execute(() -> {
            ConversationWithMessages stored = chatDao.getConversation(conversationId);
            List<ImageVersionEntity> imageVersions =
                    chatDao.listRecentImageVersions(conversationId, MAX_HISTORY_IMAGE_VERSIONS);
            String legacy = prefs.getString("chatHistory", "");
            if (!composerSmokeMode && stored == null && legacy != null && !legacy.isEmpty()) {
                persistConversation(conversationId, legacy);
                stored = chatDao.getConversation(conversationId);
            }
            String fallbackDraft = prefs.getString(draftFallbackKey(conversationId), null);
            ConversationWithMessages result = stored;
            if (fallbackDraft != null) {
                persistDraft(conversationId, fallbackDraft);
            }
            runOnUiThread(() -> renderConversation(conversationId, loadGeneration,
                    editRevisionAtStart, result, imageVersions, fallbackDraft));
        });
    }

    private void renderConversation(String conversationId, long loadGeneration, long editRevisionAtStart,
                                    ConversationWithMessages stored,
                                    List<ImageVersionEntity> imageVersions, String fallbackDraft) {
        if (!conversationId.equals(currentConversationId) || loadGeneration != conversationLoadGeneration) return;
        messageList.removeAllViews();
        while (messages.length() > 0) messages.remove(0);
        String storedDraft = fallbackDraft != null
                ? fallbackDraft
                : stored == null ? "" : stored.conversation.draft;
        if (draftEditRevision == editRevisionAtStart) restorePromptDraft(storedDraft);
        List<TimelineEntry> timeline = new ArrayList<>();
        if (stored != null && stored.messages != null) {
            stored.messages.sort((left, right) -> Long.compare(left.createdAt, right.createdAt));
            int start = Math.max(0, stored.messages.size() - MAX_HISTORY_MESSAGES);
            for (int i = start; i < stored.messages.size(); i++) {
                MessageEntity row = stored.messages.get(i);
                if (row.content == null || row.content.isEmpty()) continue;
                timeline.add(TimelineEntry.forMessage(row));
                try {
                    JSONObject msg = new JSONObject();
                    msg.put("role", row.role);
                    msg.put("content", row.content);
                    msg.put("createdAt", row.createdAt);
                    messages.put(msg);
                    if (row.createdAt > lastTimelineTimestamp) lastTimelineTimestamp = row.createdAt;
                } catch (Exception ignored) {}
            }
        }
        if (imageVersions != null) {
            for (ImageVersionEntity version : imageVersions) {
                if (version == null) continue;
                if (version.completedAt > lastTimelineTimestamp) {
                    lastTimelineTimestamp = version.completedAt;
                }
                if (version.createdAt > lastTimelineTimestamp) {
                    lastTimelineTimestamp = version.createdAt;
                }
                if ("succeeded".equals(version.status)
                        && version.localPath != null && new File(version.localPath).isFile()) {
                    timeline.add(TimelineEntry.forImage(version,
                            version.completedAt > 0 ? version.completedAt : version.createdAt));
                } else if ("failed".equals(version.status)) {
                    timeline.add(TimelineEntry.forImageFailure(version,
                            version.completedAt > 0 ? version.completedAt : version.createdAt));
                }
            }
        }
        Collections.sort(timeline, Comparator
                .comparingLong((TimelineEntry entry) -> entry.timestamp)
                .thenComparingInt(entry -> entry.kind)
                .thenComparing(entry -> entry.stableId));
        for (TimelineEntry entry : timeline) {
            if (entry.message != null) {
                MessageEntity row = entry.message;
                addBubble("user".equals(row.role) ? "你" : modelLabel(selectedModel),
                        row.content, "user".equals(row.role));
            } else if (entry.image != null && entry.kind == TimelineEntry.KIND_IMAGE) {
                showImageResult(entry.image);
            } else if (entry.image != null) {
                addBubble("15code", "图片任务失败："
                        + (entry.image.prompt == null ? "" : entry.image.prompt), false);
            }
        }
        if (messageList.getChildCount() == 0) addBubble("15code", "已连接。", false);
        historyLoadStarted = false;
        updateChatControls();
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
        flushCurrentDraft();
        saveChatHistory();
        persistCurrentImageSelection();
        currentConversationId = UUID.randomUUID().toString();
        prefs.edit().putString("currentConversationId", currentConversationId).apply();
        currentDraft = "";
        draftEditRevision++;
        conversationLoadGeneration++;
        historyLoadStarted = false;
        while (messages.length() > 0) messages.remove(0);
        prefs.edit().remove("chatHistory").apply();
        resetImageSelectionForConversationChange();
        messageList.removeAllViews();
        addBubble("15code", "新对话已开始。", false);
        restorePromptDraft("");
        persistDraftAsync(currentConversationId, "", false);
        updateChatControls();
    }

    private void logout() {
        clearSelectedImageState();
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
        flushCurrentDraft();
        saveChatHistory();
        persistCurrentImageSelection();
        currentConversationId = id;
        prefs.edit().putString("currentConversationId", id).apply();
        resetImageSelectionForConversationChange();
        preparePromptForConversation();
        draftEditRevision++;
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
        updateChatControls();
    }

    private void updateChatControls() {
        if (sendButton == null) return;
        if (streaming) {
            sendButton.setText(stopRequested ? "停止中" : "停止");
            sendButton.setEnabled(!stopRequested);
        } else {
            sendButton.setText(historyLoadStarted ? "加载中"
                    : imageAttachmentLoading ? "读图中"
                    : imageRequestRunning ? "生图中" : "发送");
            sendButton.setEnabled(!historyLoadStarted && !imageAttachmentLoading
                    && !imageRequestRunning);
        }
        boolean allowConversationChanges = !streaming && !historyLoadStarted
                && !imageAttachmentLoading && !imageRequestRunning;
        newChatButton.setEnabled(allowConversationChanges);
        menuButton.setEnabled(allowConversationChanges);
        modelButton.setEnabled(allowConversationChanges);
        if (attachButton != null) attachButton.setEnabled(!historyLoadStarted
                && !imageAttachmentLoading && !imageRequestRunning);
    }

    private void stopStreaming() {
        if (!streaming || stopRequested) return;
        stopRequested = true;
        statusText.setText("正在停止...");
        updateChatControls();
        if (activeChatConnection != null) activeChatConnection.disconnect();
    }

    private void toast(String text) {
        Toast.makeText(this, text == null ? "操作失败" : text, Toast.LENGTH_LONG).show();
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

    private byte[] readLimitedFileBytes(String localPath, int limit) throws Exception {
        File source = new File(localPath);
        if (!source.isFile()) throw new Exception("图片文件不存在");
        if (source.length() > limit) throw new Exception("图片文件过大");
        try (InputStream in = new FileInputStream(source);
             ByteArrayOutputStream out = new ByteArrayOutputStream(
                     (int) Math.min(source.length(), 1024 * 1024))) {
            byte[] buf = new byte[8192];
            int total = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > limit) throw new Exception("图片文件过大");
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
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

    private static class TimelineEntry {
        static final int KIND_MESSAGE = 0;
        static final int KIND_IMAGE = 1;
        static final int KIND_IMAGE_FAILURE = 2;

        final long timestamp;
        final int kind;
        final String stableId;
        final MessageEntity message;
        final ImageVersionEntity image;

        private TimelineEntry(long timestamp, int kind, String stableId,
                              MessageEntity message, ImageVersionEntity image) {
            this.timestamp = timestamp;
            this.kind = kind;
            this.stableId = stableId == null ? "" : stableId;
            this.message = message;
            this.image = image;
        }

        static TimelineEntry forMessage(MessageEntity message) {
            return new TimelineEntry(message.createdAt, KIND_MESSAGE,
                    "message-" + message.id, message, null);
        }

        static TimelineEntry forImage(ImageVersionEntity image, long timestamp) {
            return new TimelineEntry(timestamp, KIND_IMAGE, image.id, null, image);
        }

        static TimelineEntry forImageFailure(ImageVersionEntity image, long timestamp) {
            return new TimelineEntry(timestamp, KIND_IMAGE_FAILURE, image.id, null, image);
        }
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
