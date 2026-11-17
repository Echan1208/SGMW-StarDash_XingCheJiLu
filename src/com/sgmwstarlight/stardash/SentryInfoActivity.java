package com.sgmwstarlight.stardash;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Sentry mode instructions, with the buttons to start it or go back. */
public final class SentryInfoActivity extends Activity {
    /** Each tip: the main line, then optional sub-steps shown indented below it. */
    private static final String[][] TIPS = {
            {"点击开启停车哨兵模式后车辆将不会熄火下电，15秒后仅关闭中控屏幕（请勿主动熄火）。"},
            {"建议车辆开启展厅模式，展厅模式进入方式：",
                    "① 车辆处于P档位且激活电子手刹；",
                    "② 灯光状态拨杆处于OFF档位，进行以下操作并在5秒内完成：持续踩住刹车踏板 → 连续按2次双闪 → "
                            + "灯光状态拨杆从OFF档位快速从底至顶旋转2次最后回到OFF档位 → 松开刹车踏板；",
                    "③ 车辆仪表盘显示为展厅模式，READY状态已消失，且车辆前部示廓灯已经关闭。"},
            {"车辆中控屏幕息屏后单击任意位置会点亮屏幕，可以退出停车哨兵模式。"},
            {"离车请折叠后视镜（可选），降下主驾车窗，下车后从外部锁定车门并升起主驾车窗。上车解锁请使用手机五菱汽车APP解锁车门。"},
            {"退出展厅模式方式：",
                    "① 灯光状态拨杆处于OFF档位，进行以下操作并在5秒内完成：持续踩住刹车踏板 → 连续按2次双闪 → "
                            + "灯光状态拨杆从OFF档位快速从底至顶旋转2次最后回到OFF档位 → 松开刹车踏板；",
                    "② 车辆仪表盘显示为用户模式，START一键启动车辆，READY状态已就绪，且车辆前部示廓灯已经开启。"},
            {"停车哨兵模式将会持续缩时录制车辆周边环境视频，行人、车辆路过或旁车开门等画面变动将会升级为中危事件录制停车事件视频。"},
            {"该模式下会持续消耗车辆电量，不建议车辆在低于20%的电量下开启。"},
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setFitsSystemWindows(true);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(40, 28, 40, 28);

        TextView title = Ui.text(this, "哨兵模式", 40, Ui.TEXT, true);
        root.addView(title, Ui.lp(CONTENT_W, -2));

        // Numbered tips in a card that scrolls if the screen is short.
        LinearLayout tips = Ui.card(this, LinearLayout.VERTICAL);
        tips.setPadding(36, 28, 36, 28);
        for (int i = 0; i < TIPS.length; i++) {
            LinearLayout row = new LinearLayout(this);
            TextView num = Ui.text(this, (i + 1) + ".", 18, Ui.BLUE, true);
            row.addView(num, Ui.lp(40, -2));
            LinearLayout body = new LinearLayout(this);
            body.setOrientation(LinearLayout.VERTICAL);
            TextView main = Ui.text(this, TIPS[i][0], 18, Ui.TEXT, false);
            main.setLineSpacing(6, 1);
            body.addView(main);
            for (int k = 1; k < TIPS[i].length; k++) {
                TextView sub = Ui.text(this, TIPS[i][k], 16, Ui.SUB, false);
                sub.setLineSpacing(5, 1);
                body.addView(sub, Ui.margins(Ui.lp(-1, -2), 20, 8, 0, 0));
            }
            row.addView(body, Ui.lp(0, -2, 1));
            tips.addView(row, Ui.margins(Ui.lp(-1, -2), 0, i == 0 ? 0 : 18, 0, 0));
        }
        ScrollView scroll = new ScrollView(this);
        scroll.addView(tips);
        root.addView(scroll, Ui.margins(Ui.lp(CONTENT_W, -2), 0, 20, 0, 0));

        // The buttons sit centred in the space between the tips and the bottom of the screen.
        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER);
        buttons.setMinimumHeight(110);
        TextView start = Ui.button(this, "开启停车哨兵模式", Ui.GREEN, Ui.BG);
        start.setOnClickListener(v -> {
            startActivity(new Intent(this, SentryActivity.class));
            finish();
        });
        TextView exit = Ui.button(this, "退出", Ui.CARD2, Ui.TEXT);
        exit.setOnClickListener(v -> finish());
        buttons.addView(start, Ui.margins(Ui.lp(360, -2), 0, 0, 24, 0));
        buttons.addView(exit, Ui.lp(360, -2));
        root.addView(buttons, Ui.lp(-1, 0, 1));

        setContentView(root);
    }

    private static final int CONTENT_W = 1280;
}
