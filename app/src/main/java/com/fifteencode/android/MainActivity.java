package com.fifteencode.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
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

public class MainActivity extends Activity {
    private static final String PLATFORM = "https://15code.com";
    private static final String LLM = "https://cli.15code.com/v1/chat/completions";
    private static final String PREFS = "15code_android";
    private static final String APP_VERSION = "1.2.5";
    private static final String PREFERRED_MODEL = "qwen3.6";

    private SharedPreferences prefs;
    private String sessionToken;
    private String goKey;
    private String selectedModel;
    private String accountEmail;
    private double credits;
    private volatile boolean streaming;
    private volatile boolean stopRequested;
    private volatile HttpURLConnection activeChatConnection;
    private final List<Model> models = new ArrayList<>();
    private final JSONArray messages = new JSONArray();

    private LinearLayout root;
    private LinearLayout loginPanel;
    private LinearLayout chatPanel;
    private LinearLayout messageList;
    private EditText emailInput;
    private EditText passwordInput;
    private EditText promptInput;
    private Button modelButton;
    private TextView statusText;
    private TextView accountText;
    private Button newChatButton;
    private Button logoutButton;
    private Button sendButton;
    private ProgressBar progress;
    private ScrollView scroll;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        sessionToken = prefs.getString("sessionToken", null);
        goKey = prefs.getString("goKey", null);
        selectedModel = prefs.getString("model", null);
        buildUi();
        if (sessionToken != null) restoreSession();
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
        header.setBackgroundColor(0xFF0F172A);
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(64)));

        TextView title = new TextView(this);
        title.setText("15code");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(title, new LinearLayout.LayoutParams(0, -1, 1));

        newChatButton = new Button(this);
        newChatButton.setText("新对话");
        newChatButton.setAllCaps(false);
        newChatButton.setOnClickListener(v -> newChat());
        header.addView(newChatButton, new LinearLayout.LayoutParams(dp(88), dp(42)));

        logoutButton = new Button(this);
        logoutButton.setText("退出");
        logoutButton.setAllCaps(false);
        logoutButton.setOnClickListener(v -> logout());
        header.addView(logoutButton, new LinearLayout.LayoutParams(dp(68), dp(42)));

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
        chatPanel.setPadding(dp(12), dp(8), dp(12), dp(12));
        root.addView(chatPanel, new LinearLayout.LayoutParams(-1, 0, 1));

        accountText = new TextView(this);
        accountText.setTextColor(0xFF475569);
        accountText.setTextSize(13);
        accountText.setPadding(dp(4), 0, dp(4), dp(6));
        chatPanel.addView(accountText, new LinearLayout.LayoutParams(-1, dp(34)));

        modelButton = new Button(this);
        modelButton.setText("选择模型");
        modelButton.setAllCaps(false);
        modelButton.setGravity(Gravity.CENTER_VERTICAL);
        modelButton.setPadding(dp(12), 0, dp(12), 0);
        modelButton.setOnClickListener(v -> showModelPicker());
        chatPanel.addView(modelButton, new LinearLayout.LayoutParams(-1, dp(48)));

        scroll = new ScrollView(this);
        messageList = new LinearLayout(this);
        messageList.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(messageList, new ScrollView.LayoutParams(-1, -2));
        chatPanel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout composer = new LinearLayout(this);
        composer.setOrientation(LinearLayout.HORIZONTAL);
        composer.setGravity(Gravity.BOTTOM);
        chatPanel.addView(composer, new LinearLayout.LayoutParams(-1, dp(82)));

        promptInput = new EditText(this);
        promptInput.setHint("发消息给 15code");
        promptInput.setTextColor(0xFF111827);
        promptInput.setHintTextColor(0xFF94A3B8);
        promptInput.setTextSize(16);
        promptInput.setMinLines(1);
        promptInput.setMaxLines(4);
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
        promptInput.setBackground(makeBg(0xFFFFFFFF, 0xFFE2E8F0, dp(14)));
        promptInput.setOnClickListener(v -> {
            promptInput.requestFocus();
            openKeyboard(promptInput);
        });
        composer.addView(promptInput, new LinearLayout.LayoutParams(0, -1, 1));

        sendButton = new Button(this);
        sendButton.setText("发送");
        sendButton.setAllCaps(false);
        sendButton.setOnClickListener(v -> {
            if (streaming) stopStreaming();
            else sendMessage();
        });
        composer.addView(sendButton, new LinearLayout.LayoutParams(dp(84), -1));
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
                prefs.edit().putString("sessionToken", sessionToken).apply();
                bootstrapAccount();
                runOnUiThread(() -> {
                    setBusy(false, "已登录");
                    showChat();
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
        setBusy(true, "正在恢复会话...");
        new Thread(() -> {
            try {
                bootstrapAccount();
                runOnUiThread(() -> {
                    setBusy(false, "已连接 15code");
                    showChat();
                });
            } catch (Exception e) {
                prefs.edit().clear().apply();
                sessionToken = null;
                goKey = null;
                runOnUiThread(() -> {
                    setBusy(false, "请重新登录");
                    showLogin();
                });
            }
        }).start();
    }

    private void bootstrapAccount() throws Exception {
        JSONObject me = getJson(PLATFORM + "/api/me", sessionToken);
        JSONObject user = me.optJSONObject("user");
        accountEmail = user == null ? "" : user.optString("email", "");
        credits = user == null ? 0 : user.optDouble("credits", 0) / 1_000_000d;
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
        prefs.edit().putString("goKey", goKey).apply();

        JSONArray pricing = getJson(PLATFORM + "/api/pricing", sessionToken).optJSONArray("models");
        models.clear();
        if (pricing != null) {
            for (int i = 0; i < pricing.length(); i++) {
                JSONObject m = pricing.getJSONObject(i);
                String id = m.optString("modelId");
                String name = m.optString("displayName", id);
                if (!id.isEmpty()) models.add(new Model(id, name));
            }
        }
        if (models.isEmpty()) throw new Exception("未加载到可用模型");
        if (selectedModel == null || selectedModel.isEmpty()) {
            selectedModel = models.get(0).id;
            for (Model model : models) {
                if (PREFERRED_MODEL.equals(model.id)) {
                    selectedModel = model.id;
                    break;
                }
            }
        }
    }

    private void sendMessage() {
        String text = promptInput.getText().toString().trim();
        if (text.isEmpty()) {
            promptInput.requestFocus();
            openKeyboard(promptInput);
            return;
        }
        sendMessageText(text);
    }

    private void openComposerDialog() {
        EditText input = new EditText(this);
        input.setHint("输入消息");
        input.setMinLines(3);
        input.setMaxLines(8);
        input.setSingleLine(false);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        int pad = dp(16);
        input.setPadding(pad, dp(10), pad, dp(10));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("输入消息")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("发送", null)
                .create();
        dialog.setOnShowListener(d -> {
            if (dialog.getWindow() != null) {
                dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                        | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String text = input.getText().toString().trim();
                if (text.isEmpty()) {
                    toast("请输入消息");
                    return;
                }
                dialog.dismiss();
                sendMessageText(text);
            });
            input.requestFocus();
            input.postDelayed(() -> openKeyboard(input), 120);
        });
        dialog.show();
    }

    private void sendMessageText(String text) {
        promptInput.setText("");
        addBubble("你", text, true);
        try {
            JSONObject user = new JSONObject();
            user.put("role", "user");
            user.put("content", text);
            messages.put(user);
        } catch (Exception ignored) {}

        TextView assistantBubble = addBubble(modelLabel(selectedModel), "正在思考...", false);
        setStreamingUi(true);
        new Thread(() -> {
            StringBuilder answer = new StringBuilder();
            JSONObject body = new JSONObject();
            try {
                body.put("model", selectedModel);
                body.put("stream", true);
                body.put("max_tokens", 4096);
                body.put("messages", messages);
                streamChat(body, chunk -> {
                    answer.append(chunk);
                    runOnUiThread(() -> updateBubble(assistantBubble, modelLabel(selectedModel), answer.toString()));
                });
                if (answer.length() == 0) throw new Exception("模型返回为空，请换模型重试");
                JSONObject assistant = new JSONObject();
                assistant.put("role", "assistant");
                assistant.put("content", answer.toString());
                messages.put(assistant);
            } catch (Exception e) {
                if (stopRequested && answer.length() > 0) {
                    try {
                        JSONObject assistant = new JSONObject();
                        assistant.put("role", "assistant");
                        assistant.put("content", answer.toString());
                        messages.put(assistant);
                    } catch (Exception ignored) {}
                    runOnUiThread(() -> updateBubble(assistantBubble, modelLabel(selectedModel), answer + "\n\n[已停止]"));
                } else if (answer.length() == 0 && isRetryableStreamError(e)) {
                    try {
                        runOnUiThread(() -> updateBubble(assistantBubble, modelLabel(selectedModel), "连接不稳定，正在切换普通模式..."));
                        body.put("stream", false);
                        String fallback = completeChat(body);
                        if (fallback.isEmpty()) throw new Exception("模型返回为空，请换模型重试");
                        JSONObject assistant = new JSONObject();
                        assistant.put("role", "assistant");
                        assistant.put("content", fallback);
                        messages.put(assistant);
                        runOnUiThread(() -> updateBubble(assistantBubble, modelLabel(selectedModel), fallback));
                    } catch (Exception fallbackError) {
                        runOnUiThread(() -> updateBubble(assistantBubble, "错误", friendlyError(fallbackError)));
                    }
                } else if (answer.length() == 0) {
                    runOnUiThread(() -> updateBubble(assistantBubble, "错误", friendlyError(e)));
                } else {
                    runOnUiThread(() -> updateBubble(assistantBubble, modelLabel(selectedModel), answer + "\n\n[连接中断]"));
                }
            } finally {
                activeChatConnection = null;
                runOnUiThread(() -> setStreamingUi(false));
            }
        }).start();
    }

    private void streamChat(JSONObject body, StreamHandler handler) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(LLM).openConnection();
        activeChatConnection = conn;
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(600000);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("Accept", "text/event-stream");
        conn.setRequestProperty("Authorization", "Bearer " + goKey);
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
            while ((line = reader.readLine()) != null) {
                if (!streaming) throw new Exception("用户已停止生成");
                line = line.trim();
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if (data.isEmpty()) continue;
                if ("[DONE]".equals(data)) break;
                JSONObject obj = new JSONObject(data);
                JSONObject choice = obj.optJSONArray("choices") == null ? null : obj.optJSONArray("choices").optJSONObject(0);
                if (choice == null) continue;
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

    private String completeChat(JSONObject body) throws Exception {
        JSONObject resp = postJson(LLM, body, goKey, false);
        JSONArray choices = resp.optJSONArray("choices");
        if (choices == null || choices.length() == 0) return "";
        JSONObject choice = choices.optJSONObject(0);
        if (choice == null) return "";
        JSONObject message = choice.optJSONObject("message");
        return message == null ? "" : message.optString("content", "");
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
        return requestJson("POST", url, body, bearer, loginMode);
    }

    private JSONObject requestJson(String method, String urlText, JSONObject body, String bearer, boolean loginMode) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlText).openConnection();
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(180000);
        conn.setRequestMethod(method);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "15code-android-native/" + APP_VERSION);
        if (loginMode) conn.setRequestProperty("X-Auth-Mode", "bearer");
        if (bearer != null && !bearer.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + bearer);
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
        logoutButton.setVisibility(View.GONE);
        statusText.setText("请登录");
    }

    private void showChat() {
        loginPanel.setVisibility(View.GONE);
        chatPanel.setVisibility(View.VISIBLE);
        newChatButton.setVisibility(View.VISIBLE);
        logoutButton.setVisibility(View.VISIBLE);
        accountText.setText((accountEmail == null || accountEmail.isEmpty() ? "已登录" : accountEmail)
                + " · 余额 " + String.format("%.4f", credits));
        updateModelButton();
        if (messageList.getChildCount() == 0) {
            addBubble("15code", "已连接。", false);
        }
    }

    private List<String> modelNames() {
        List<String> names = new ArrayList<>();
        for (Model m : models) names.add(m.name + "  ·  " + m.id);
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
                    selectedModel = models.get(which).id;
                    prefs.edit().putString("model", selectedModel).apply();
                    updateModelButton();
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

    private String modelLabel(String id) {
        for (Model m : models) if (m.id.equals(id)) return m.name;
        return id;
    }

    private TextView addBubble(String who, String text, boolean mine) {
        TextView bubble = new TextView(this);
        updateBubble(bubble, who, text);
        bubble.setTextSize(15);
        bubble.setTextColor(mine ? 0xFFFFFFFF : 0xFF111827);
        bubble.setPadding(dp(12), dp(10), dp(12), dp(10));
        bubble.setTextIsSelectable(true);
        bubble.setBackground(makeBg(mine ? 0xFF2563EB : 0xFFFFFFFF, mine ? 0xFF2563EB : 0xFFE2E8F0, dp(14)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(mine ? dp(42) : 0, dp(8), mine ? 0 : dp(42), dp(8));
        messageList.addView(bubble, lp);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        return bubble;
    }

    private void updateBubble(TextView bubble, String who, String text) {
        bubble.setText(who + "\n" + text);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private void newChat() {
        while (messages.length() > 0) messages.remove(0);
        messageList.removeAllViews();
        addBubble("15code", "新对话已开始。", false);
        promptInput.requestFocus();
    }

    private void logout() {
        prefs.edit().clear().apply();
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
        Model(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private interface StreamHandler {
        void onChunk(String chunk);
    }
}
