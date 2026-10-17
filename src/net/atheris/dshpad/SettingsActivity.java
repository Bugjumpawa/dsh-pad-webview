package net.atheris.dshpad;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebStorage;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** 入口地址 / 附加 query / 屏幕常亮；以及显式的「清登录态」。 */
public class SettingsActivity extends Activity {

    private EditText urlField;
    private EditText queryField;
    private CheckBox keepAwake;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);
        setContentView(scroll);

        root.addView(hint("入口地址（含协议与端口），例如\n"
                + "https://192.168.5.100:8443/  （TLS 直连，平板需装 ca.crt）\n"
                + "http://192.168.5.100:8790/   （LAN key 中继，明文）"));

        urlField = new EditText(this);
        urlField.setSingleLine(true);
        urlField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlField.setText(Prefs.url(this).isEmpty() ? Prefs.DEFAULT_URL : Prefs.url(this));
        root.addView(urlField, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(hint("附加查询参数（问号后面的部分）。配对时填 k=LAN密钥，平时留空。"
                + "⚠️ prts-safe=1 会整个停用 PRTS 主题，只在主题把页面搞挂时当兜底用。"));

        queryField = new EditText(this);
        queryField.setSingleLine(true);
        queryField.setInputType(InputType.TYPE_CLASS_TEXT);
        queryField.setText(Prefs.query(this));
        root.addView(queryField, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        keepAwake = new CheckBox(this);
        keepAwake.setText("保持屏幕常亮（看实时输出时有用）");
        keepAwake.setChecked(Prefs.keepAwake(this));
        LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cbLp.topMargin = dp(12);
        root.addView(keepAwake, cbLp);

        Button save = new Button(this);
        save.setText("保存并返回");
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { saveAndFinish(); }
        });
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        saveLp.topMargin = dp(20);
        root.addView(save, saveLp);

        Button reset = new Button(this);
        reset.setText("恢复默认地址");
        reset.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                urlField.setText(Prefs.DEFAULT_URL);
                queryField.setText(Prefs.DEFAULT_QUERY);
            }
        });
        root.addView(reset, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button wipe = new Button(this);
        wipe.setText("清除登录状态（cookie）");
        wipe.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { confirmWipe(); }
        });
        LinearLayout.LayoutParams wipeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wipeLp.topMargin = dp(24);
        root.addView(wipe, wipeLp);

        root.addView(hint("清掉 cookie 后主页面可能还能渲染，但所有 /api/ 会 401。"
                + "需要重新用带 ?k= 的地址配对。"));
    }

    private void saveAndFinish() {
        String url = urlField.getText().toString().trim();
        if (url.isEmpty()) {
            toast("地址不能为空");
            return;
        }
        String lower = url.toLowerCase();
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            toast("地址要以 http:// 或 https:// 开头");
            return;
        }
        String query = queryField.getText().toString().trim();
        if (query.startsWith("?")) query = query.substring(1);
        Prefs.url(this, url);
        Prefs.query(this, query);
        Prefs.keepAwake(this, keepAwake.isChecked());
        toast("已保存");
        finish();
    }

    private void confirmWipe() {
        new AlertDialog.Builder(this)
                .setTitle("清除登录状态？")
                .setMessage("会清掉 WebView 的 cookie 和站点存储。"
                        + "之后所有 /api/ 都会 401，必须用带 ?k=LAN密钥 的地址重新配对。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清除", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        CookieManager.getInstance().removeAllCookies(null);
                        CookieManager.getInstance().flush();
                        WebStorage.getInstance().deleteAllData();
                        toast("已清除，请重新配对");
                    }
                })
                .show();
    }

    private TextView hint(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTextColor(0xFF5B6670);
        tv.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        lp.bottomMargin = dp(4);
        tv.setLayoutParams(lp);
        return tv;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
