package com.sgmwstarlight.stardash;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final boolean SHOW_SIGNALS = false;
    private boolean carWarningShown;
    /** The system permission dialog is offered once per launch, then the home page card takes over. */
    private boolean permissionsAsked;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private FrameLayout content;
    private final TextView[] nav = new TextView[SHOW_SIGNALS ? 4 : 3];
    private int page = -1;
    private Runnable pageTicker;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        RecorderService.start(this, null);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(Ui.BG);
        root.setFitsSystemWindows(true);

        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setPadding(24, 32, 24, 24);
        TextView title = Ui.text(this, "行车记录", 28, Ui.TEXT, true);
        side.addView(title);
        side.addView(Ui.space(this, 40));
        // The signal page is for debugging; set SHOW_SIGNALS to true to bring it back.
        String[] names = SHOW_SIGNALS ? new String[]{"录像", "回放", "设置", "信号"} : new String[]{"录像", "回放", "设置"};
        for (int i = 0; i < names.length; i++) {
            final int index = i;
            TextView t = Ui.text(this, names[i], 21, Ui.SUB, true);
            t.setPadding(28, 20, 28, 20);
            t.setOnClickListener(v -> show(index));
            side.addView(t, Ui.margins(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT), 0, 0, 0, 8));
            nav[i] = t;
        }
        root.addView(side, Ui.lp(250, ViewGroup.LayoutParams.MATCH_PARENT));

        content = new FrameLayout(this);
        content.setPadding(8, 28, 32, 28);
        root.addView(content, Ui.lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        setContentView(root);
        show(0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!permissionsAsked) {
            permissionsAsked = true;
            askPermissions();
        }
        if (page >= 0) {
            int p = page;
            page = -1;
            show(p);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopTicker();
        Recorder r = recorder();
        if (r != null) r.previewWanted = false;
    }

    private void askPermissions() {
        String[] ask = Permissions.runtime(Permissions.missing(this));
        if (ask.length > 0) requestPermissions(ask, 1);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        // Rebuild the current page so the permission card reflects the answer. The recorder retries
        // opening a file at every keyframe, so a storage grant takes effect without a restart.
        if (page == 0) {
            page = -1;
            show(0);
        }
    }

    private Recorder recorder() {
        RecorderService s = RecorderService.instance;
        return s == null ? null : s.recorder;
    }

    private void show(int index) {
        if (index == page) return;
        page = index;
        stopTicker();
        for (int i = 0; i < nav.length; i++) {
            nav[i].setTextColor(i == index ? Ui.TEXT : Ui.SUB);
            nav[i].setBackground(i == index ? Ui.round(Ui.CARD2, 14) : null);
        }
        content.removeAllViews();
        Recorder r = recorder();
        if (r != null) r.previewWanted = index == 0;
        switch (index) {
            case 0: content.addView(homePage()); break;
            case 1: content.addView(clipsPage()); break;
            case 2: content.addView(settingsPage()); break;
            default: content.addView(signalsPage());
        }
    }

    private void tick(Runnable r, long ms) {
        stopTicker();
        pageTicker = new Runnable() {
            @Override
            public void run() {
                r.run();
                handler.postDelayed(this, ms);
            }
        };
        handler.post(pageTicker);
    }

    private void stopTicker() {
        if (pageTicker != null) handler.removeCallbacks(pageTicker);
        pageTicker = null;
    }

    // ------------------------------------------------------------------ home

    private View homePage() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        FrameLayout frame = new FrameLayout(this);
        frame.setBackground(Ui.round(Color.BLACK, 18));
        frame.setClipToOutline(true);
        ImageView preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        frame.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        TextView badge = Ui.text(this, "", 17, Color.WHITE, true);
        badge.setPadding(18, 8, 18, 8);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT);
        blp.setMargins(18, 18, 0, 0);
        frame.addView(badge, blp);
        left.addView(frame, Ui.lp(1000, 750));
        ImageView barView = new ImageView(this);
        barView.setScaleType(ImageView.ScaleType.FIT_XY);
        left.addView(barView, Ui.margins(Ui.lp(1000, 38), 0, 12, 0, 0));
        row.addView(left);

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        LinearLayout status = Ui.card(this, LinearLayout.VERTICAL);
        TextView state = Ui.text(this, "", 28, Ui.TEXT, true);
        TextView file = Ui.text(this, "", 16, Ui.SUB, false);
        ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(60);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.GREEN));
        TextView stats = Ui.text(this, "", 17, Ui.TEXT, false);
        stats.setLineSpacing(6, 1);
        TextView error = Ui.text(this, "", 15, Ui.AMBER, false);
        status.addView(state);
        status.addView(file, Ui.margins(Ui.lp(-1, -2), 0, 6, 0, 0));
        status.addView(progress, Ui.margins(Ui.lp(-1, 14), 0, 16, 0, 16));
        status.addView(stats);
        status.addView(error, Ui.margins(Ui.lp(-1, -2), 0, 8, 0, 0));
        right.addView(status, Ui.lp(-1, -2));

        TextView toggle = Ui.button(this, "", Ui.RED, Color.WHITE);
        TextView lock = Ui.button(this, "锁定当前片段", Ui.CARD2, Ui.TEXT);
        right.addView(toggle, Ui.margins(Ui.lp(-1, -2), 0, 20, 0, 0));
        right.addView(lock, Ui.margins(Ui.lp(-1, -2), 0, 14, 0, 0));
        TextView hint = Ui.text(this, "锁定后的片段不会被循环覆盖", 14, Ui.SUB, false);
        hint.setGravity(Gravity.CENTER);
        right.addView(hint, Ui.margins(Ui.lp(-1, -2), 0, 8, 0, 0));

        LinearLayout perms = new LinearLayout(this);
        perms.setOrientation(LinearLayout.VERTICAL);
        perms.setBackground(Ui.round(Color.argb(40, 255, 184, 0), 14));
        perms.setPadding(22, 16, 22, 16);
        right.addView(perms, Ui.margins(Ui.lp(-1, -2), 0, 20, 0, 0));
        String[] permsShown = {null};
        fillPermissionCard(perms, permsShown);

        // Other head units: warn once per app launch, for 10 seconds.
        if (!carWarningShown) {
            carWarningShown = true;
            TextView warn = Ui.text(this, "", 16, Ui.AMBER, true);
            warn.setVisibility(View.GONE);
            warn.setBackground(Ui.round(Color.argb(40, 255, 184, 0), 14));
            warn.setPadding(22, 16, 22, 16);
            right.addView(warn, Ui.margins(Ui.lp(-1, -2), 0, 20, 0, 0));
            new Thread(() -> {
                if (CarCheck.isSupported()) return;
                handler.post(() -> {
                    warn.setText("非2025款五菱星光车型，使用过程中可能会遇到错误或者无法录制的情况");
                    warn.setVisibility(View.VISIBLE);
                    handler.postDelayed(() -> warn.setVisibility(View.GONE), 10_000);
                });
            }).start();
        }
        // Scrolls only when the permission card makes the column taller than the screen.
        ScrollView rightScroll = new ScrollView(this);
        rightScroll.addView(right);
        row.addView(rightScroll, Ui.margins(Ui.lp(0, -1, 1), 28, 0, 0, 0));

        toggle.setOnClickListener(v -> {
            RecorderService s = RecorderService.instance;
            if (s != null) s.setRecording(!s.recorder.recordingWanted());
        });
        lock.setOnClickListener(v -> {
            Recorder r = recorder();
            if (r != null && r.encoding) {
                r.lockCurrent();
                lock.setText("已锁定 ✓");
                handler.postDelayed(() -> lock.setText("锁定当前片段"), 2500);
            }
        });

        long[] version = {-1};
        int[] ticks = {0};
        tick(() -> {
            // Grants made over adb do not resume the activity, so recheck every 5 seconds.
            if (++ticks[0] % 10 == 0) fillPermissionCard(perms, permsShown);
            RecorderService s = RecorderService.instance;
            if (s == null) {
                state.setText("服务启动中…");
                return;
            }
            Recorder r = s.recorder;
            r.previewWanted = true;
            Bitmap b = r.copyPreview(null, version);
            if (b != null) preview.setImageBitmap(b);
            Bitmap bar = s.bar.snapshot();
            if (bar != null) barView.setImageBitmap(bar);

            // The encoder keeps running while files cannot be opened, so do not call that recording.
            boolean cannotSave = r.lastError.startsWith("无法写入存储");
            boolean rec = r.recordingWanted() && r.encoding && !cannotSave;
            String modeText = r.mode == Recorder.Mode.DRIVE ? "行车" : r.mode == Recorder.Mode.PARKING ? "停车监控" : "停车事件";
            if (!r.recordingWanted()) state.setText("已暂停");
            else if (!r.sourceOk) state.setText("等待 360 画面");
            else if (cannotSave) state.setText("无法保存录像");
            else state.setText("● 录制中 · " + modeText);
            state.setTextColor(rec ? Ui.RED : cannotSave && r.recordingWanted() ? Ui.AMBER : Ui.SUB);
            badge.setText(rec ? "● REC  " + modeText : "未录制");
            badge.setBackground(Ui.round(rec ? Color.argb(200, 200, 30, 40) : Color.argb(160, 0, 0, 0), 10));
            file.setText(r.currentFile.isEmpty() ? " " : r.currentFile);
            long secs = r.segmentStartMs == 0 ? 0 : (System.currentTimeMillis() - r.segmentStartMs) / 1000;
            int seg = r.mode == Recorder.Mode.DRIVE ? 60 : 600;
            progress.setMax(seg);
            progress.setProgress((int) Math.min(seg, secs));
            long used = cachedUsed();
            stats.setText(String.format(Locale.US, "360 画面  %.1f fps\n编码输出  %.1f fps\n视频格式  %s · %d Mbps\n存储占用  %s / %d GB\n剩余空间  %s",
                    r.sourceFps, r.encodedFps, s.config.hevc() ? "H.265" : "H.264", s.config.bitrateMbps(),
                    Ui.size(used), s.config.quotaGb(), Ui.size(Storage.freeBytes())));
            boolean noStorage = r.lastError.startsWith("无法写入存储") && Permissions.storageMissing(this);
            error.setText(noStorage ? "缺少存储权限，录像无法保存（见下方说明）" : r.lastError);
            toggle.setText(r.recordingWanted() ? "停止录制" : "开始录制");
            toggle.setBackground(Ui.round(r.recordingWanted() ? Ui.CARD2 : Ui.GREEN, 14));
            toggle.setTextColor(r.recordingWanted() ? Ui.RED : Ui.BG);
        }, 500);
        return row;
    }

    /** Lists missing permissions with what each breaks, a grant button and one-line adb commands. */
    private void fillPermissionCard(LinearLayout card, String[] shown) {
        List<Permissions.Missing> missing = Permissions.missing(this);
        StringBuilder key = new StringBuilder();
        for (Permissions.Missing m : missing) key.append(m.label).append(',');
        if (key.toString().equals(shown[0])) return;
        shown[0] = key.toString();
        card.removeAllViews();
        card.setVisibility(missing.isEmpty() ? View.GONE : View.VISIBLE);
        if (missing.isEmpty()) return;
        card.addView(Ui.text(this, "缺少权限，部分功能无法使用", 17, Ui.AMBER, true));
        StringBuilder lines = new StringBuilder();
        StringBuilder commands = new StringBuilder();
        boolean anyRuntime = false;
        for (Permissions.Missing m : missing) {
            if (lines.length() > 0) lines.append('\n');
            lines.append("· ").append(m.label).append("：").append(m.effect);
            if (commands.length() > 0) commands.append('\n');
            commands.append(m.command);
            anyRuntime |= m.runtime;
        }
        TextView list = Ui.text(this, lines.toString(), 15, Ui.TEXT, false);
        list.setLineSpacing(4, 1);
        card.addView(list, Ui.margins(Ui.lp(-1, -2), 0, 8, 0, 0));
        if (anyRuntime) {
            TextView grant = Ui.button(this, "授予权限", Ui.AMBER, Ui.BG);
            grant.setOnClickListener(v -> askPermissions());
            card.addView(grant, Ui.margins(Ui.lp(-1, -2), 0, 14, 0, 0));
        }
        card.addView(Ui.text(this, anyRuntime ? "如果没有弹出授权窗口，请在电脑上用 adb 逐条执行：" : "请在电脑上用 adb 逐条执行：",
                14, Ui.SUB, false), Ui.margins(Ui.lp(-1, -2), 0, 14, 0, 0));
        TextView cmd = Ui.text(this, commands.toString(), 13, Ui.TEXT, false);
        cmd.setTypeface(android.graphics.Typeface.MONOSPACE);
        cmd.setLineSpacing(4, 1);
        card.addView(cmd, Ui.margins(Ui.lp(-1, -2), 0, 6, 0, 0));
    }

    private long usedCache, usedAt;

    private long cachedUsed() {
        long now = System.currentTimeMillis();
        if (now - usedAt > 15000) {
            usedAt = now;
            new Thread(() -> usedCache = Storage.usedBytes()).start();
        }
        return usedCache;
    }

    // ------------------------------------------------------------------ clips

    private static final int COLUMNS = 4, CELL_W = 360, CELL_H = 284, GAP = 18;
    private String filter = "ALL";
    private Thumbs thumbs;

    private View clipsPage() {
        if (thumbs == null) thumbs = new Thumbs(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout chips = new LinearLayout(this);
        String[][] filters = {{"ALL", "全部"}, {"DRIVE", "行车"}, {"PARKING", "停车缩时"}, {"EVENT", "停车事件"}, {"LOCKED", "已锁定"}};
        ListView list = new ListView(this);
        ClipAdapter adapter = new ClipAdapter();
        list.setAdapter(adapter);
        list.setDivider(null);
        list.setSelector(android.R.color.transparent);
        list.setVerticalScrollBarEnabled(false);
        List<TextView> chipViews = new ArrayList<>();
        for (String[] f : filters) {
            TextView c = Ui.chip(this, f[1], f[0].equals(filter), Ui.GREEN);
            c.setOnClickListener(v -> {
                filter = f[0];
                for (int i = 0; i < chipViews.size(); i++) {
                    boolean on = filters[i][0].equals(filter);
                    chipViews.get(i).setTextColor(on ? Ui.BG : Ui.SUB);
                    chipViews.get(i).setBackground(on ? Ui.round(Ui.GREEN, 12) : Ui.outline(Ui.LINE, 12));
                }
                adapter.load();
            });
            chipViews.add(c);
            chips.addView(c, Ui.margins(Ui.lp(-2, -2), 0, 0, 12, 0));
        }
        col.addView(chips);
        col.addView(list, Ui.margins(Ui.lp(-1, 0, 1), 0, 12, 0, 0));
        adapter.load();
        return col;
    }

    private void play(List<Storage.Clip> clips, Storage.Clip chosen) {
        ArrayList<String> paths = new ArrayList<>();
        int start = 0;
        // The player walks forward in time, so hand it the list oldest first.
        for (int i = clips.size() - 1; i >= 0; i--) {
            if (clips.get(i) == chosen) start = paths.size();
            paths.add(clips.get(i).file.getPath());
        }
        Intent i = new Intent(this, PlayerActivity.class);
        i.putStringArrayListExtra("paths", paths);
        i.putExtra("index", start);
        startActivity(i);
    }

    private void clipMenu(Storage.Clip clip, ClipAdapter adapter) {
        boolean locked = clip.type.equals("LOCKED");
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(clip.file.getName())
                .setItems(new String[]{locked ? "解除锁定" : "锁定（不被覆盖）", "删除"}, (d, which) -> {
                    if (which == 0) {
                        if (locked) Storage.unlock(clip.file);
                        else Storage.lock(clip.file);
                        adapter.load();
                    } else {
                        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                                .setMessage("删除后无法恢复，确定删除这个片段吗？")
                                .setPositiveButton("删除", (d2, w2) -> {
                                    clip.file.delete();
                                    adapter.load();
                                })
                                .setNegativeButton("取消", null).show();
                    }
                }).show();
    }

    /** Rows are either a date header (String) or up to four clips (Storage.Clip[]). */
    private final class ClipAdapter extends BaseAdapter {
        final List<Storage.Clip> clips = new ArrayList<>();
        final List<Object> rows = new ArrayList<>();

        void load() {
            new Thread(() -> {
                List<Storage.Clip> all = Storage.list(true);
                java.util.Set<String> alive = new java.util.HashSet<>();
                List<Storage.Clip> picked = new ArrayList<>();
                for (Storage.Clip c : all) {
                    alive.add(c.file.getName());
                    if (filter.equals("ALL") || c.type.equals(filter)) picked.add(c);
                }
                thumbs.trim(alive);
                List<Object> r = new ArrayList<>();
                SimpleDateFormat day = new SimpleDateFormat("M月d日 EEEE", Locale.CHINA);
                String lastDay = null;
                List<Storage.Clip> group = new ArrayList<>();
                for (Storage.Clip c : picked) {
                    String d = day.format(new Date(c.time));
                    if (!d.equals(lastDay) || group.size() == COLUMNS) {
                        if (!group.isEmpty()) r.add(group.toArray(new Storage.Clip[0]));
                        group.clear();
                    }
                    if (!d.equals(lastDay)) {
                        r.add(d);
                        lastDay = d;
                    }
                    group.add(c);
                }
                if (!group.isEmpty()) r.add(group.toArray(new Storage.Clip[0]));
                handler.post(() -> {
                    clips.clear();
                    clips.addAll(picked);
                    rows.clear();
                    rows.addAll(r);
                    notifyDataSetChanged();
                });
            }).start();
        }

        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int p) { return rows.get(p); }
        @Override public long getItemId(int p) { return p; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int p) { return rows.get(p) instanceof String ? 0 : 1; }
        @Override public boolean isEnabled(int p) { return false; }

        @Override
        public View getView(int p, View convert, ViewGroup parent) {
            Object item = rows.get(p);
            Context c = MainActivity.this;
            if (item instanceof String) {
                TextView t = Ui.text(c, (String) item, 19, Ui.SUB, true);
                t.setPadding(4, 26, 4, 14);
                return t;
            }
            LinearLayout row = new LinearLayout(c);
            row.setPadding(0, 0, 0, GAP);
            for (Storage.Clip clip : (Storage.Clip[]) item) {
                row.addView(cell(c, clip), Ui.margins(Ui.lp(CELL_W, CELL_H), 0, 0, GAP, 0));
            }
            return row;
        }

        private View cell(Context c, Storage.Clip clip) {
            FrameLayout cell = new FrameLayout(c);
            cell.setBackground(Ui.round(Ui.CARD, 16));
            cell.setClipToOutline(true);
            ImageView image = new ImageView(c);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cell.addView(image, new FrameLayout.LayoutParams(-1, -1));
            thumbs.load(image, clip.file);

            // Bottom shade with the time and size
            LinearLayout bottom = new LinearLayout(c);
            bottom.setGravity(Gravity.CENTER_VERTICAL);
            bottom.setPadding(18, 30, 18, 14);
            android.graphics.drawable.GradientDrawable shade = new android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
                    new int[]{Color.argb(230, 0, 0, 0), Color.argb(0, 0, 0, 0)});
            bottom.setBackground(shade);
            String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(clip.time));
            bottom.addView(Ui.text(c, time, 22, Color.WHITE, true), Ui.lp(0, -2, 1));
            bottom.addView(Ui.text(c, Ui.size(clip.file.length()), 14, Color.rgb(200, 205, 212), false));
            cell.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

            // Type badge
            String name = clip.file.getName();
            String label;
            int color;
            switch (clip.type) {
                case "PARKING": label = "停车缩时"; color = Ui.BLUE; break;
                case "EVENT": label = "停车事件"; color = Ui.AMBER; break;
                case "LOCKED": label = "🔒 " + (name.contains("_E") ? "事件" : name.contains("_P") ? "缩时" : "行车"); color = Ui.RED; break;
                default: label = "行车"; color = Ui.GREEN;
            }
            TextView badge = Ui.text(c, label, 14, Ui.BG, true);
            badge.setPadding(12, 4, 12, 4);
            badge.setBackground(Ui.round(color, 9));
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT);
            blp.setMargins(12, 12, 0, 0);
            cell.addView(badge, blp);

            List<Storage.Clip> snapshot = new ArrayList<>(clips);
            cell.setOnClickListener(v -> play(snapshot, clip));
            cell.setOnLongClickListener(v -> {
                clipMenu(clip, this);
                return true;
            });
            return cell;
        }
    }

    // ------------------------------------------------------------------ settings

    private View settingsPage() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(col);
        RecorderService s = RecorderService.instance;
        Config cfg = s != null ? s.config : new Config(this);
        Runnable restart = () -> {
            RecorderService svc = RecorderService.instance;
            if (svc != null) svc.recorder.restartEncoder();
        };

        LinearLayout rec = section(col, "录制");
        switchRow(rec, "开机自动录制", "车机启动后自动开始录制", cfg.autoStart(), v -> cfg.put("autoStart", v));
        choiceRow(rec, "视频编码", "H.265 体积约小 40%，车机和主流手机都能播放", new String[]{"H.265", "H.264"},
                cfg.hevc() ? 0 : 1, i -> {
                    cfg.put("hevc", i == 0);
                    cfg.put("bitrate", i == 0 ? 5 : 8);
                    restart.run();
                });
        stepRow(rec, "码率", "Mbps，越高越清晰、文件越大", cfg.bitrateMbps(), 2, 12, 1, v -> {
            cfg.put("bitrate", v);
            restart.run();
        });
        switchRow(rec, "烧录车辆状态", "视频下方显示时间、车速、档位、灯光等", cfg.infoBar(), v -> {
            cfg.put("infoBar", v);
            restart.run();
        });
        stepRow(rec, "存储配额", "GB，超出后自动删除最旧的未锁定片段", cfg.quotaGb(), 8, 64, 4, v -> cfg.put("quotaGb", v));
        rec.addView(Ui.text(this, "行车每 1 分钟一段，存放在 内部存储/StarDash", 14, Ui.SUB, false), Ui.margins(Ui.lp(-1, -2), 0, 12, 0, 0));

        LinearLayout park = section(col, "停车监控");
        switchRow(park, "启用停车监控", "挂 P 挡一段时间后转为缩时录影 + 画面变动录制", cfg.parking(), v -> cfg.put("parking", v));
        stepRow(park, "进入延时", "秒，挂 P 挡多久后进入停车监控", cfg.parkDelaySec(), 10, 300, 10, v -> cfg.put("parkDelay", v));
        stepRow(park, "缩时帧率", "每秒采集帧数，按 25 帧播放", cfg.timelapseFps(), 1, 5, 1, v -> cfg.put("timelapseFps", v));
        switchRow(park, "画面变动录制", "有人或车经过时，以全帧率单独录一段（含触发前约 5 秒）", cfg.motion(), v -> cfg.put("motion", v));
        stepRow(park, "变动灵敏度", "1 最迟钝，5 最灵敏", cfg.motionLevel(), 1, 5, 1, v -> cfg.put("motionLevel", v));

        LinearLayout mir = section(col, "悬浮画面");
        switchRow(mir, "启用悬浮画面", "按车辆状态自动弹出摄像头画面，点击画面可关闭", cfg.mirror(), v -> cfg.put("mirror", v));
        switchRow(mir, "转向灯显示侧后方", "左转显示左后方，右转显示右后方；关闭转向灯 2 秒后收回", cfg.ruleTurn(), v -> cfg.put("ruleTurn", v));
        switchRow(mir, "高速刹车显示后方", "车速高于阈值且踩刹车时显示；松开刹车或低于收回车速时收回", cfg.ruleBrake(), v -> cfg.put("ruleBrake", v));
        stepRow(mir, "显示车速", "km/h", cfg.brakeShowKmh(), 40, 130, 5, v -> cfg.put("brakeShowKmh", v));
        stepRow(mir, "收回车速", "km/h", cfg.brakeHideKmh(), 0, 40, 5, v -> cfg.put("brakeHideKmh", v));
        choiceRow(mir, "画面大小", null, new String[]{"小", "中", "大"}, cfg.mirrorSize(), i -> cfg.put("mirrorSize", i));
        LinearLayout tests = new LinearLayout(this);
        String[][] sides = {{"预览左后方", "LEFT"}, {"预览右后方", "RIGHT"}, {"预览后方", "REAR"}};
        for (String[] sd : sides) {
            TextView b = Ui.button(this, sd[0], Ui.CARD2, Ui.TEXT);
            b.setTextSize(17);
            b.setOnClickListener(v -> {
                RecorderService svc = RecorderService.instance;
                if (svc != null) svc.mirror.preview(MirrorController.Side.valueOf(sd[1]));
            });
            tests.addView(b, Ui.margins(Ui.lp(-2, -2), 0, 0, 14, 0));
        }
        mir.addView(tests, Ui.margins(Ui.lp(-1, -2), 0, 16, 0, 0));
        col.addView(Ui.space(this, 40));
        return scroll;
    }

    interface IntSink { void set(int v); }
    interface BoolSink { void set(boolean v); }

    private LinearLayout section(LinearLayout parent, String title) {
        parent.addView(Ui.text(this, title, 18, Ui.SUB, true), Ui.margins(Ui.lp(-1, -2), 8, parent.getChildCount() == 0 ? 0 : 28, 0, 12));
        LinearLayout card = Ui.card(this, LinearLayout.VERTICAL);
        parent.addView(card, Ui.margins(Ui.lp(1100, -2), 0, 0, 0, 0));
        return card;
    }

    private LinearLayout rowBase(LinearLayout card, String title, String sub) {
        if (card.getChildCount() > 0) card.addView(Ui.divider(this));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 18, 0, 18);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(Ui.text(this, title, 20, Ui.TEXT, false));
        if (sub != null) texts.addView(Ui.text(this, sub, 14, Ui.SUB, false));
        row.addView(texts, Ui.lp(0, -2, 1));
        card.addView(row);
        return row;
    }

    private void switchRow(LinearLayout card, String title, String sub, boolean on, BoolSink sink) {
        LinearLayout row = rowBase(card, title, sub);
        Switch sw = new Switch(this);
        sw.setChecked(on);
        sw.setThumbTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        sw.setTrackTintList(new android.content.res.ColorStateList(new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{Ui.GREEN, Ui.LINE}));
        sw.setOnCheckedChangeListener((b, v) -> sink.set(v));
        row.addView(sw);
    }

    private void stepRow(LinearLayout card, String title, String sub, int value, int min, int max, int step, IntSink sink) {
        LinearLayout row = rowBase(card, title, sub);
        int[] cur = {value};
        TextView minus = Ui.button(this, "−", Ui.CARD2, Ui.TEXT);
        TextView plus = Ui.button(this, "+", Ui.CARD2, Ui.TEXT);
        minus.setPadding(26, 6, 26, 6);
        plus.setPadding(26, 6, 26, 6);
        TextView val = Ui.text(this, String.valueOf(value), 21, Ui.TEXT, true);
        val.setGravity(Gravity.CENTER);
        minus.setOnClickListener(v -> {
            cur[0] = Math.max(min, cur[0] - step);
            val.setText(String.valueOf(cur[0]));
            sink.set(cur[0]);
        });
        plus.setOnClickListener(v -> {
            cur[0] = Math.min(max, cur[0] + step);
            val.setText(String.valueOf(cur[0]));
            sink.set(cur[0]);
        });
        row.addView(minus);
        row.addView(val, Ui.lp(90, -2));
        row.addView(plus);
    }

    private void choiceRow(LinearLayout card, String title, String sub, String[] options, int selected, IntSink sink) {
        LinearLayout row = rowBase(card, title, sub);
        List<TextView> chips = new ArrayList<>();
        for (int i = 0; i < options.length; i++) {
            final int index = i;
            TextView c = Ui.chip(this, options[i], i == selected, Ui.GREEN);
            c.setOnClickListener(v -> {
                for (int j = 0; j < chips.size(); j++) {
                    chips.get(j).setTextColor(j == index ? Ui.BG : Ui.SUB);
                    chips.get(j).setBackground(j == index ? Ui.round(Ui.GREEN, 12) : Ui.outline(Ui.LINE, 12));
                }
                sink.set(index);
            });
            chips.add(c);
            row.addView(c, Ui.margins(Ui.lp(-2, -2), 10, 0, 0, 0));
        }
    }

    // ------------------------------------------------------------------ signals

    private View signalsPage() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(Ui.text(this, "车辆信号（原始值）", 18, Ui.SUB, true), Ui.margins(Ui.lp(-1, -2), 8, 0, 0, 12));
        LinearLayout card = Ui.card(this, LinearLayout.VERTICAL);
        col.addView(card, Ui.lp(1100, -2));
        TextView[] values = new TextView[VehicleState.IDS.length];
        for (int i = 0; i < VehicleState.IDS.length; i++) {
            if (i > 0) card.addView(Ui.divider(this));
            LinearLayout row = new LinearLayout(this);
            row.setPadding(0, 12, 0, 12);
            row.addView(Ui.text(this, VehicleState.NAMES[i], 19, Ui.TEXT, false), Ui.lp(300, -2));
            row.addView(Ui.text(this, String.format("0x%08x", VehicleState.IDS[i]), 16, Ui.SUB, false), Ui.lp(240, -2));
            values[i] = Ui.text(this, "--", 19, Ui.GREEN, true);
            row.addView(values[i]);
            card.addView(row);
        }
        TextView avh = Ui.text(this, "", 19, Ui.TEXT, true);
        col.addView(avh, Ui.margins(Ui.lp(-1, -2), 8, 14, 0, 0));
        col.addView(Ui.text(this, "车机收不到 AutoHold 信号，按“D/R 挡、车速 0、未踩刹车、刹车灯亮”推断。", 14, Ui.SUB, false),
                Ui.margins(Ui.lp(-1, -2), 8, 6, 0, 0));
        tick(() -> {
            RecorderService s = RecorderService.instance;
            if (s == null) return;
            for (int i = 0; i < VehicleState.IDS.length; i++) {
                int id = VehicleState.IDS[i];
                Object v = s.vehicle.raw(id);
                String text = v == null ? (s.vehicle.available(id) ? "等待数据" : "不可用") : String.valueOf(v);
                if (id == VehicleState.GEAR && v != null) text += "  (" + VehicleState.gearName(VehicleState.asInt(v)) + ")";
                values[i].setText(text);
            }
            boolean hold = s.vehicle.autoHold();
            avh.setText("AutoHold（推断）：" + (hold ? "工作中" : "未工作"));
            avh.setTextColor(hold ? Ui.GREEN : Ui.SUB);
        }, 200);
        return col;
    }
}
