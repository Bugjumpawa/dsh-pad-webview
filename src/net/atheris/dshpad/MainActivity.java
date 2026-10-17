package net.atheris.dshpad;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.inputmethod.InputMethodManager;
import android.webkit.JavascriptInterface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.Manifest;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final String TAG = "DshPad";
    private static final int REQ_SETTINGS = 1;
    private static final int REQ_FILE = 2;
    private static final int REQ_PERM = 3;
    private static final int BG = 0xFF0F1417;
    /**
     * 页面在会话切换时会 autofocus 输入框，桌面端无所谓，平板上就是键盘乱弹。
     * 规则：在用户真正碰过屏幕之前，任何 input/textarea 的自动 focus 一律 blur 掉。
     */
    private static final String KEYBOARD_HOOK = "(function(){"
            + "if(window.__dshPadKbHooked)return;window.__dshPadKbHooked=true;"
            // 只有"用户刚刚点了输入框"才允许键盘；页面的 autofocus 一律 blur。
            + "window.__dshPadComposerTs=0;"
            // DSH 的输入框是 contenteditable 的 DIV，不是 textarea —— 必须按可编辑性判断
            + "var EDITABLE='input,textarea,[contenteditable=\"\"],[contenteditable=true]';"
            + "var isInput=function(el){return !!(el&&(el.isContentEditable||/^(INPUT|TEXTAREA)$/.test(el.tagName)))};"
            + "var mark=function(ev){var t=ev.target;if(!t)return;"
            + "var el=t.closest?t.closest(EDITABLE):null;"
            + "if(isInput(t)||el){window.__dshPadComposerTs=Date.now();}};"
            + "document.addEventListener('touchstart',mark,{capture:true,passive:true});"
            + "document.addEventListener('mousedown',mark,{capture:true,passive:true});"
            + "var attempts=0,lastTry=0;"
            + "document.addEventListener('focusin',function(ev){"
            + "var t=ev.target;if(!isInput(t))return;"
            + "if(Date.now()-window.__dshPadComposerTs<40000)return;"
            + "var now=Date.now();attempts=now-lastTry<600?attempts+1:1;lastTry=now;"
            + "try{t.blur();}catch(e){}"
            + "try{if(window.DshPad&&DshPad.blockKeyboard)DshPad.blockKeyboard();}catch(e){}"
            + "},true);"
            // 会话切换走 history API，focus 往往在渲染后几百毫秒才发生：
            // 路由变化后补几枪，但用户刚点过输入框就放过。
            + "var guard=function(){if(Date.now()-window.__dshPadComposerTs<1200)return;"
            + "try{if(window.DshPad&&DshPad.blockKeyboard)DshPad.blockKeyboard();}catch(e){}};"
            + "try{['pushState','replaceState'].forEach(function(k){var o=history[k];"
            + "history[k]=function(){var r=o.apply(this,arguments);"
            + "setTimeout(guard,50);setTimeout(guard,400);setTimeout(guard,1000);return r};});}catch(e){}"
            + "window.addEventListener('popstate',function(){setTimeout(guard,50);setTimeout(guard,400);});"
            + "})();";
    /** 页面里的 blob:/data: 下载不会把 a[download] 的文件名交给原生，必须在 JS 侧截住。 */
    private static final String DOWNLOAD_HOOK = "(function(){"
            + "if(window.__dshPadHooked)return;window.__dshPadHooked=true;"
            + "document.addEventListener('click',function(ev){"
            + "var a=ev.target&&ev.target.closest?ev.target.closest('a[download]'):null;"
            + "if(!a)return;var href=a.href||'';"
            + "if(href.indexOf('blob:')!==0&&href.indexOf('data:')!==0)return;"
            + "ev.preventDefault();ev.stopPropagation();"
            + "var name=a.download||'download';"
            + "if(href.indexOf('data:')===0){var i=href.indexOf(','),meta=href.substring(5,i),d=href.substring(i+1);"
            + "if(meta.indexOf('base64')<0){try{d=btoa(decodeURIComponent(d));}catch(e){DshPad.failed('data URL 解析失败');return;}}"
            + "DshPad.save(name,'',d);return;}"
            + "fetch(href).then(function(r){return r.blob()}).then(function(b){"
            + "var fr=new FileReader();"
            + "fr.onload=function(){var s=String(fr.result);DshPad.save(name,b.type||'',s.substring(s.indexOf(',')+1));};"
            + "fr.onerror=function(){DshPad.failed('读取失败');};"
            + "fr.readAsDataURL(b);}).catch(function(e){DshPad.failed(String((e&&e.message)||e))});"
            + "},true);})();";
    /** adb 一键配置入口（见 README「从电脑配置」）。 */
    private static final String EXTRA_URL = "dshpad.url";
    private static final String EXTRA_QUERY = "dshpad.query";
    private static final String EXTRA_DIAG = "dshpad.diag";

    private WebView web;
    private ProgressBar bar;
    private LinearLayout errorPanel;
    private TextView errorText;

    private String loadedUrl;
    private boolean diagnostics;
    private String pendingEval;
    private boolean errorShown;
    private long lastBackPress;
    private ValueCallback<Uri[]> fileCallback;
    private PermissionRequest pendingPermission;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        CookieManager.getInstance().setAcceptCookie(true);
        setContentView(buildUi());
        enterFullscreen();
        applyProvisionExtras(getIntent());
        if (state != null) {
            web.restoreState(state);
            // restoreState 已恢复历史，避免 onResume 时又整页重载
            loadedUrl = buildTarget(Prefs.url(this), Prefs.query(this));
        }
        handleShortcutIntent(getIntent());
        applyPrefs(false);
    }

    /** 全屏：藏掉状态栏（导航条保留，不然在平板上不好退出）。 */
    @SuppressWarnings("deprecation")
    private void enterFullscreen() {
        Window window = getWindow();
        if (window == null) return;
        View decor = window.getDecorView();
        if (decor == null) return; // 必须在 setContentView 之后调用，否则 mDecor 还是 null
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowInsetsController controller = decor.getWindowInsetsController();
                if (controller != null) {
                    controller.hide(WindowInsets.Type.statusBars());
                    controller.setSystemBarsBehavior(
                            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_FULLSCREEN);
            }
        } catch (RuntimeException ignored) {
            // 个别 ROM 的 PhoneWindow 在 decor 未挂载时抛 NPE；主题里的 windowFullscreen 已兜底
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enterFullscreen();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        boolean changed = applyProvisionExtras(intent);
        handleShortcutIntent(intent);
        if (changed) {
            applyPrefs(true);
        } else if (pendingEval != null && web != null) {
            runPendingEval(web); // 只发了 eval，不动当前页面
        }
    }

    /**
     * adb shell am start -n net.atheris.dshpad/.MainActivity \
     *   --es dshpad.url https://192.168.5.100:8443/ --es dshpad.query "k=<LAN密钥>"
     * 一次写入入口地址和附加参数，省得在平板上手敲密钥。
     */
    private boolean applyProvisionExtras(Intent intent) {
        if (intent == null) return false;
        diagnostics = intent.getBooleanExtra(EXTRA_DIAG, diagnostics);
        String eval = intent.getStringExtra("dshpad.eval");
        if (eval != null) pendingEval = eval;
        boolean changed = false;
        String url = intent.getStringExtra(EXTRA_URL);
        String query = intent.getStringExtra(EXTRA_QUERY);
        if (url != null && !url.trim().isEmpty()) {
            Prefs.url(this, url.trim());
            loadedUrl = null;
            changed = true;
        }
        if (query != null) {
            // 「-」= 清空：adb 传空字符串 extra 会被 am 丢掉，只能用哨兵
            String q = "-".equals(query.trim()) ? "" : query.trim();
            Prefs.query(this, q);
            loadedUrl = null;
            changed = true;
        }
        return changed;
    }

    private void runPendingEval(WebView view) {
        if (pendingEval == null) return;
        final String js = pendingEval;
        pendingEval = null;
        view.evaluateJavascript(js, new ValueCallback<String>() {
            @Override public void onReceiveValue(String value) {
                Log.i("DshPadEval", value);
            }
        });
    }

    /** 桌面长按图标 -> 「设置入口地址」快捷方式会带 dshpad://settings。 */
    private void handleShortcutIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) return;
        Uri data = intent.getData();
        if (data != null && "dshpad".equals(data.getScheme()) && "settings".equals(data.getHost())) {
            openSettings();
        }
    }

    // ---------------------------------------------------------------- UI

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);

        web = new WebView(this);
        web.setBackgroundColor(BG);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setVisibility(View.GONE);
        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3));
        barLp.gravity = Gravity.TOP;
        root.addView(bar, barLp);

        errorPanel = new LinearLayout(this);
        errorPanel.setOrientation(LinearLayout.VERTICAL);
        errorPanel.setGravity(Gravity.CENTER);
        errorPanel.setBackgroundColor(BG);
        errorPanel.setVisibility(View.GONE);
        int pad = dp(24);
        errorPanel.setPadding(pad, pad, pad, pad);

        errorText = new TextView(this);
        errorText.setTextColor(0xFFE6EDF3);
        errorText.setTextSize(15);
        errorText.setGravity(Gravity.CENTER);
        errorText.setLineSpacing(dp(4), 1f);
        errorPanel.addView(errorText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button retry = new Button(this);
        retry.setText("重新加载");
        retry.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                errorPanel.setVisibility(View.GONE);
                errorShown = false;
                if (loadedUrl != null) web.loadUrl(loadedUrl); else applyPrefs(true);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        errorPanel.addView(retry, lp);

        Button configure = new Button(this);
        configure.setText("设置入口地址");
        configure.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openSettings(); }
        });
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp2.topMargin = dp(8);
        lp2.gravity = Gravity.CENTER_HORIZONTAL;
        errorPanel.addView(configure, lp2);

        root.addView(errorPanel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        configureWeb();
        return root;
    }

    @SuppressWarnings({"deprecation", "SetJavaScriptEnabled"})
    private void configureWeb() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUseWideViewPort(false);
        s.setLoadWithOverviewMode(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        }
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUrl(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrl(Uri.parse(url));
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                hideIme();
                errorShown = false;
                bar.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                bar.setVisibility(View.GONE);
                if (errorShown) errorPanel.setVisibility(View.VISIBLE);
                view.evaluateJavascript(DOWNLOAD_HOOK, null);
                view.evaluateJavascript(KEYBOARD_HOOK, null);
                if (diagnostics) diag(view);
                runPendingEval(view);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    errorShown = true;
                    showError("无法打开\n" + request.getUrl() + "\n\n"
                            + error.getDescription() + "\n\n检查平板与主机是否同一局域网、8443 是否在监听。");
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                if (request.isForMainFrame() && response != null && response.getStatusCode() == 401) {
                    errorShown = true;
                    showError("认证已失效（401）\n\nWebView 的登录 cookie 不在了。\n"
                            + "用带 ?k=LAN密钥 的地址重新配对一次即可，cookie 之后会一直保留。");
                }
            }

            @Override
            public void onReceivedSslError(WebView view, final SslErrorHandler handler, SslError error) {
                handler.cancel();
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("证书不受信任")
                        .setMessage("系统不认这个证书。\n\n平板「设置 → 安全 → 加密与凭据 → 安装证书」里装上 "
                                + "ca.crt 后再试；本 app 已声明信任用户 CA。\n\n"
                                + error.getPrimaryError() + " @ " + error.getUrl())
                        .setPositiveButton("知道了", null)
                        .show();
            }

            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                toast("页面渲染进程崩溃，正在重建");
                view.destroy();
                runOnUiThread(new Runnable() {
                    @Override public void run() { recreate(); }
                });
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int progress) {
                if (progress < 100) {
                    bar.setProgress(progress);
                    bar.setVisibility(View.VISIBLE);
                } else {
                    bar.setVisibility(View.GONE);
                }
            }

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                boolean needRuntime = false;
                List<String> granted = new ArrayList<String>();
                for (String res : request.getResources()) {
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(res)) {
                        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                                == PackageManager.PERMISSION_GRANTED) {
                            granted.add(res);
                        } else {
                            needRuntime = true;
                        }
                    } else if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(res)) {
                        if (checkSelfPermission(Manifest.permission.CAMERA)
                                == PackageManager.PERMISSION_GRANTED) {
                            granted.add(res);
                        } else {
                            needRuntime = true;
                        }
                    } else {
                        granted.add(res);
                    }
                }
                if (needRuntime) {
                    pendingPermission = request;
                    List<String> ask = new ArrayList<String>();
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                            != PackageManager.PERMISSION_GRANTED) {
                        ask.add(Manifest.permission.RECORD_AUDIO);
                    }
                    if (checkSelfPermission(Manifest.permission.CAMERA)
                            != PackageManager.PERMISSION_GRANTED) {
                        ask.add(Manifest.permission.CAMERA);
                    }
                    requestPermissions(ask.toArray(new String[0]), REQ_PERM);
                } else if (granted.isEmpty()) {
                    request.deny();
                } else {
                    request.grant(granted.toArray(new String[0]));
                }
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileCallback != null) {
                    fileCallback.onReceiveValue(null);
                }
                fileCallback = callback;
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                String type = "*/*";
                String[] accept = params.getAcceptTypes();
                if (accept != null && accept.length > 0 && accept[0] != null) {
                    String first = accept[0].split(",")[0].trim();
                    if (!first.isEmpty()) type = first;
                }
                intent.setType(type);
                if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                }
                try {
                    startActivityForResult(Intent.createChooser(intent, "选择文件"), REQ_FILE);
                } catch (ActivityNotFoundException e) {
                    fileCallback = null;
                    Toast.makeText(MainActivity.this, "没有可用的文件选择器", Toast.LENGTH_SHORT).show();
                    return false;
                }
                return true;
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin,
                    android.webkit.GeolocationPermissions.Callback callback) {
                callback.invoke(origin, false, false);
            }
        });

        web.addJavascriptInterface(new WebBridge(), "DshPad");
        // WebView 没有 Chrome 那种「长按图片/链接保存」，自己补一个：
        // 长按任意图片或链接 → 保存到 Download/DSH/。
        web.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                WebView.HitTestResult hit = web.getHitTestResult();
                if (hit == null) return false;
                int type = hit.getType();
                final String url = hit.getExtra();
                boolean savable = (type == WebView.HitTestResult.IMAGE_TYPE
                        || type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
                        || type == WebView.HitTestResult.SRC_ANCHOR_TYPE)
                        && url != null
                        && (url.startsWith("http") || url.startsWith("blob:") || url.startsWith("data:"));
                if (!savable) return false;
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("保存到本机？")
                        .setMessage(url)
                        .setNegativeButton("取消", null)
                        .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                            @Override public void onClick(android.content.DialogInterface d, int w) {
                                jsDownload(url, downloadName(null, Uri.parse(url), guessMime(url)), guessMime(url));
                            }
                        })
                        .show();
                return true;
            }
        });
        web.setDownloadListener(new android.webkit.DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimeType, long contentLength) {
                // 统一走页面内 fetch + JS bridge 落盘：
                // 8443 是自签 CA，DownloadManager（系统进程）未必认；
                // 而 WebView 内的 fetch 用的是已信任的证书 + 现成 cookie，
                // blob:/data: 这类 URL 也只有页面内才拿得到。
                jsDownload(url, downloadName(contentDisposition, Uri.parse(url), mimeType), mimeType);
            }
        });
    }

    /** 诊断：主题状态 + 命中元素 + 认证探针 + 主线程往返耗时（grep DshPadDiag）。 */
    private void diag(WebView view) {
        final long started = SystemClock.uptimeMillis();
        String js = "(function(){try{var d=document.documentElement;"
                + "var pts=[[349,226],[900,660],[1990,700],[100,1030]];"
                + "var hits=pts.map(function(p){var el=document.elementFromPoint(p[0],p[1]);"
                + "return el?(el.tagName+'.'+String(el.className||'').slice(0,24)"
                + "+' pe='+getComputedStyle(el).pointerEvents):'null'});"
                + "var st='-';try{var x=new XMLHttpRequest();"
                + "x.open('HEAD','/prts-theme-api/emblems/configuration',false);x.send();st=x.status;}"
                + "catch(e){st='err'}finally{}"
                + "return JSON.stringify({search:location.search,"
                + "dataPrts:d.hasAttribute('data-dsh-prts'),"
                + "canvases:document.querySelectorAll('canvas').length,"
                + "hits:hits,auth:st,vis:document.visibilityState,focus:document.hasFocus()})}"
                + "catch(e){return 'err:'+e.message}})()";
        view.evaluateJavascript(js, new ValueCallback<String>() {
            @Override public void onReceiveValue(String value) {
                Log.i("DshPadDiag", "rtt=" + (SystemClock.uptimeMillis() - started) + "ms " + value);
            }
        });
    }

    // ---------------------------------------------------------- downloads

    /** 页面 JS 与原生之间的落盘桥；只接受页面自己发来的字节流。 */
    private final class WebBridge {
        @JavascriptInterface
        public void save(String name, String mime, String base64) {
            try {
                byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                final String saved = saveDownload(name, mime, bytes);
                runOnUiThread(new Runnable() {
                    @Override public void run() { toast("已保存：" + saved); }
                });
            } catch (Exception e) {
                Log.w(TAG, "download save failed", e);
                failed(e.getMessage());
            }
        }

        /** 大文件不塞 base64（内存扛不住），退回系统 DownloadManager。 */
        @JavascriptInterface
        public void tooBig(final String url, final String name, final String mime) {
            runOnUiThread(new Runnable() {
                @Override public void run() { enqueueSystemDownload(url, name, mime); }
            });
        }

        /** 页面自己抢焦点时，让原生立刻把软键盘收掉（JS blur 在部分 ROM 上不够）。 */
        @JavascriptInterface
        public void blockKeyboard() {
            Log.i("DshPadKb", "blockKeyboard");
            runOnUiThread(new Runnable() {
                @Override public void run() { hideIme(); }
            });
        }

        @JavascriptInterface
        public void failed(final String reason) {
            runOnUiThread(new Runnable() {
                @Override public void run() { toast("下载失败：" + reason); }
            });
        }
    }

    private long lastImeHideAt;

    private void hideIme() {
        if (web == null) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastImeHideAt < 250) return; // 页面连续抢焦点时别刷屏
        lastImeHideAt = now;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowInsetsController controller = getWindow().getInsetsController();
                if (controller != null) controller.hide(WindowInsets.Type.ime());
            }
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(web.getWindowToken(), 0);
        } catch (RuntimeException ignored) {
            // 部分 ROM 在窗口未 attach 时抛异常
        }
        web.clearFocus();
    }

    private void jsDownload(String url, String name, String mime) {
        String type = mime == null ? "" : mime;
        String js = "(function(){"
                + "var url=" + JSONObject.quote(url) + ",name=" + JSONObject.quote(name)
                + ",mime=" + JSONObject.quote(type) + ";"
                + "if(url.indexOf('data:')===0){"
                + "  var i=url.indexOf(','),meta=url.substring(5,i),data=url.substring(i+1);"
                + "  if(meta.indexOf('base64')<0){try{data=btoa(decodeURIComponent(data));}catch(e){DshPad.failed('data URL 解析失败');return;}}"
                + "  DshPad.save(name,mime,data);return;}"
                + "fetch(url,{credentials:'include'}).then(function(r){"
                + "  if(!r.ok)throw new Error('HTTP '+r.status);return r.blob();"
                + "}).then(function(b){"
                + "  if(b.size>40*1024*1024){DshPad.tooBig(url,name,mime||b.type);return;}"
                + "  var fr=new FileReader();"
                + "  fr.onload=function(){var s=String(fr.result);DshPad.save(name,mime||b.type,s.substring(s.indexOf(',')+1));};"
                + "  fr.onerror=function(){DshPad.failed('读取文件流失败');};"
                + "  fr.readAsDataURL(b);"
                + "}).catch(function(e){DshPad.failed(String((e&&e.message)||e));});"
                + "})();";
        web.evaluateJavascript(js, null);
    }

    /** mime → 扩展名；只在名字还是 .bin 兜底时用。 */
    private static String mimeExt(String mime) {
        String m = mime == null ? "" : mime.toLowerCase();
        if (m.contains("png")) return ".png";
        if (m.contains("jpeg") || m.contains("jpg")) return ".jpg";
        if (m.contains("webp")) return ".webp";
        if (m.contains("gif")) return ".gif";
        if (m.contains("pdf")) return ".pdf";
        if (m.contains("json")) return ".json";
        if (m.contains("zip")) return ".zip";
        if (m.contains("text")) return ".txt";
        return null;
    }

    private String saveDownload(String name, String mime, byte[] bytes) throws IOException {
        String safe = sanitizeName(name);
        if (safe.endsWith(".bin")) {
            String ext = mimeExt(mime);
            if (ext != null) safe = safe.substring(0, safe.length() - 4) + ext;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, safe);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, mime == null || mime.isEmpty() ? "application/octet-stream" : mime);
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DSH");
            cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new IOException("MediaStore 拒绝写入");
            OutputStream os = getContentResolver().openOutputStream(uri);
            if (os == null) throw new IOException("打不开输出流");
            try {
                os.write(bytes);
                os.flush();
            } finally {
                os.close();
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            getContentResolver().update(uri, done, null, null);
            return "Download/DSH/" + safe;
        }
        File dir = new File(getExternalFilesDir(null), "Downloads");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("无法创建下载目录");
        File out = new File(dir, safe);
        FileOutputStream fos = new FileOutputStream(out);
        try {
            fos.write(bytes);
        } finally {
            fos.close();
        }
        return out.getAbsolutePath();
    }

    private void enqueueSystemDownload(final String url, String name, String mime) {
        try {
            Uri uri = Uri.parse(url);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme();
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                toast("这个链接只能页面内下载，且文件过大");
                return;
            }
            DownloadManager.Request req = new DownloadManager.Request(uri);
            req.setMimeType(mime);
            req.setTitle(name);
            req.setDescription("来自 DSH");
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) req.addRequestHeader("Cookie", cookie);
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "DSH/" + sanitizeName(name));
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (dm == null) {
                toast("系统下载服务不可用");
                return;
            }
            dm.enqueue(req);
            toast("文件较大，已交给系统下载：" + name);
        } catch (Exception e) {
            Log.w(TAG, "system download failed", e);
            toast("下载失败：" + e.getMessage());
        }
    }

    /** blob:/data: 这类没有文件名的下载，按 mime 生成一个 dsh-<时间戳>.ext。 */
    private static String downloadName(String contentDisposition, Uri uri, String mime) {
        String name = guessFileName(contentDisposition, uri);
        if (name.contains(".") && !name.startsWith("download")) return name;
        String ext = ".bin";
        String m = mime == null ? "" : mime.toLowerCase();
        if (m.contains("png")) ext = ".png";
        else if (m.contains("jpeg") || m.contains("jpg")) ext = ".jpg";
        else if (m.contains("webp")) ext = ".webp";
        else if (m.contains("gif")) ext = ".gif";
        else if (m.contains("pdf")) ext = ".pdf";
        else if (m.contains("json")) ext = ".json";
        else if (m.contains("zip")) ext = ".zip";
        else if (m.contains("text")) ext = ".txt";
        return "dsh-" + System.currentTimeMillis() + ext;
    }

    private static String guessMime(String url) {
        String u = url.toLowerCase();
        if (u.startsWith("data:")) {
            int end = u.indexOf(';');
            if (end < 0) end = u.indexOf(',');
            if (end > 5 && u.substring(5, end).contains("/")) return u.substring(5, end);
        }
        int q = u.indexOf('?');
        if (q > 0) u = u.substring(0, q);
        if (u.endsWith(".png")) return "image/png";
        if (u.endsWith(".jpg") || u.endsWith(".jpeg")) return "image/jpeg";
        if (u.endsWith(".webp")) return "image/webp";
        if (u.endsWith(".gif")) return "image/gif";
        if (u.endsWith(".pdf")) return "application/pdf";
        if (u.endsWith(".zip")) return "application/zip";
        if (u.endsWith(".txt") || u.endsWith(".md")) return "text/plain";
        if (u.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }

    private static String sanitizeName(String name) {
        String safe = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (safe.isEmpty()) safe = "dsh-download";
        if (safe.length() > 80) {
            String ext = "";
            int dot = safe.lastIndexOf('.');
            if (dot > 0) ext = safe.substring(dot);
            safe = safe.substring(0, 60) + ext;
        }
        return safe;
    }

    // ------------------------------------------------------------ routing

    private boolean handleUrl(Uri uri) {
        if (uri == null) return false;
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if ("http".equals(scheme) || "https".equals(scheme)) {
            String host = uri.getHost();
            String entry = entryHost();
            if (host != null && host.equalsIgnoreCase(entry)) return false; // 留在 WebView
            openExternal(uri);
            return true;
        }
        if ("mailto".equals(scheme) || "tel".equals(scheme) || "sms".equals(scheme)
                || "market".equals(scheme) || "intent".equals(scheme)) {
            openExternal(uri);
            return true;
        }
        return false;
    }

    private void openExternal(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException e) {
            toast("没有能打开 " + uri + " 的应用");
        }
    }

    private String entryHost() {
        try {
            return Uri.parse(Prefs.url(this)).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    // -------------------------------------------------------------- state

    private void applyPrefs(boolean forceReload) {
        if (Prefs.keepAwake(this)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        String base = Prefs.url(this);
        if (base == null || base.trim().isEmpty()) {
            openSettings();
            return;
        }
        String target = buildTarget(base, Prefs.query(this));
        if (forceReload || !target.equals(loadedUrl)) {
            loadedUrl = target;
            errorPanel.setVisibility(View.GONE);
            errorShown = false;
            web.loadUrl(target);
        }
    }

    static String buildTarget(String base, String query) {
        String b = base.trim();
        String q = query == null ? "" : query.trim();
        if (q.startsWith("?")) q = q.substring(1);
        if (q.isEmpty()) return b;
        return b + (b.contains("?") ? "&" : "?") + q;
    }

    private void showError(String message) {
        errorText.setText(message);
        errorPanel.setVisibility(View.VISIBLE);
    }

    private void openSettings() {
        startActivityForResult(new Intent(this, SettingsActivity.class), REQ_SETTINGS);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_SETTINGS) {
            applyPrefs(true);
            return;
        }
        if (requestCode == REQ_FILE) {
            if (fileCallback == null) return;
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null) {
                ClipData clip = data.getClipData();
                if (clip != null) {
                    result = new Uri[clip.getItemCount()];
                    for (int i = 0; i < clip.getItemCount(); i++) {
                        result[i] = clip.getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    result = new Uri[]{data.getData()};
                }
            }
            fileCallback.onReceiveValue(result);
            fileCallback = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_PERM && pendingPermission != null) {
            List<String> granted = new ArrayList<String>();
            for (int i = 0; i < permissions.length && i < grantResults.length; i++) {
                if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    granted.add(permissions[i]);
                }
            }
            if (granted.isEmpty()) {
                pendingPermission.deny();
            } else {
                List<String> res = new ArrayList<String>();
                for (String r : pendingPermission.getResources()) {
                    if (r.equals(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                            && granted.contains(Manifest.permission.RECORD_AUDIO)) {
                        res.add(r);
                    } else if (r.equals(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                            && granted.contains(Manifest.permission.CAMERA)) {
                        res.add(r);
                    } else if (!r.equals(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                            && !r.equals(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) {
                        res.add(r);
                    }
                }
                if (res.isEmpty()) pendingPermission.deny();
                else pendingPermission.grant(res.toArray(new String[0]));
            }
            pendingPermission = null;
            return;
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastBackPress < 2000) {
            super.onBackPressed();
        } else {
            lastBackPress = now;
            toast("再按一次退出");
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) web.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
        applyPrefs(false);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (web != null) web.saveState(outState);
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.setWebChromeClient(null);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------- helpers

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private static String guessFileName(String contentDisposition, Uri uri) {
        String name = null;
        if (contentDisposition != null) {
            String[] parts = contentDisposition.split(";");
            for (String part : parts) {
                String p = part.trim();
                if (p.toLowerCase().startsWith("filename=")) {
                    name = p.substring("filename=".length()).trim();
                    if (name.startsWith("\"") && name.endsWith("\"") && name.length() > 2) {
                        name = name.substring(1, name.length() - 1);
                    }
                    break;
                }
            }
        }
        if (name == null || name.isEmpty()) {
            name = uri.getLastPathSegment();
        }
        if (name == null || name.isEmpty()) name = "download.bin";
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (name.length() > 80) {
            String ext = "";
            int dot = name.lastIndexOf('.');
            if (dot > 0) ext = name.substring(dot);
            name = name.substring(0, 60) + ext;
        }
        return name;
    }
}
