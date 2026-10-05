package com.yijin.xiangqi.light;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class MainActivity extends Activity {
    private static final int BG = 0xfff7f4ee, INK = 0xff302f2b, MUTED = 0xff8a8378,
            RED_INK = 0xff963d35, WOOD = 0xfff0dfbb, LINE = 0xff927858, GOLD = 0xffb18a4d;
    private static final String[] LEVELS = {"简单", "普通", "困难", "大师"};
    private static final String[] LEVEL_NOTES = {"熟悉规则，轻松入门", "稳扎稳打，练习应对", "专业引擎，深入计算", "专业引擎，全力挑战"};
    private final WoodPiecePainter piecePainter = new WoodPiecePainter();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Future<?> search;
    private int[] board = Chess.initial();
    private int turn = Chess.RED, selected = -1, lastFrom = -1, lastTo = -1, difficulty = 1;
    private boolean twoPlayers, watching, thinking, finished, active;
    private boolean gameShowing, hasSavedGame, vibration = true;
    private int setupMode, setupLevel = 1, winner, plyCount;
    private String resultReason = "";
    private FrameLayout screen;
    private StrongEngine strongEngine;
    private final LinearLayout[] modeChoices = new LinearLayout[3], levelChoices = new LinearLayout[4];
    private Button startButton;
    private TextView levelDescription, blackName, redName, blackMeta, redMeta, gameFoot;
    private boolean watchPaused = true;
    private int watchDelay = 2000;
    private final Runnable watchAdvance = () -> {
        if (active && gameShowing && watching && !watchPaused && !thinking && !finished) startSearch(false);
    };
    private int generation;
    private String note = "点选红方棋子，再点落点。";
    private Chess.Move hintMove;
    private List<Integer> targets = new ArrayList<>();
    private final ArrayList<Snapshot> history = new ArrayList<>();
    private BoardView boardView;
    private TextView status, caption, modeLabel;
    private Button undoButton, hintButton, pauseButton, stepButton, speedButton;
    private LinearLayout watchControls;

    private static final class Snapshot {
        final int[] board;
        final int turn, from, to;
        Snapshot(int[] board, int turn, int from, int to) {
            this.board = board.clone(); this.turn = turn; this.from = from; this.to = to;
        }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        strongEngine = new StrongEngine(this);
        restore();
        SharedPreferences options = getSharedPreferences("options", MODE_PRIVATE);
        setupMode = options.getInt("mode", watching ? 2 : twoPlayers ? 1 : 0);
        if (setupMode < 0 || setupMode > 2) setupMode = 0;
        setupLevel = Math.max(0, Math.min(3, options.getInt("level", difficulty)));
        vibration = options.getBoolean("vibration", true);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        else getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        if (Build.VERSION.SDK_INT >= 26) getWindow().getDecorView().setSystemUiVisibility(
                getWindow().getDecorView().getSystemUiVisibility() | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                        | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        if (Build.VERSION.SDK_INT >= 33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, () -> handleBack());
        showHome();
    }

    private void installPage(View page) {
        screen = new FrameLayout(this);
        screen.setBackgroundColor(BG);
        screen.addView(page, new FrameLayout.LayoutParams(-1, -1));
        screen.setOnApplyWindowInsetsListener((v, insets) -> {
            int top, bottom, left, right;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                top = bars.top; bottom = bars.bottom; left = bars.left; right = bars.right;
            } else {
                top = insets.getSystemWindowInsetTop(); bottom = insets.getSystemWindowInsetBottom();
                left = insets.getSystemWindowInsetLeft(); right = insets.getSystemWindowInsetRight();
            }
            v.setPadding(dp(18) + left, dp(10) + top, dp(18) + right, dp(8) + bottom);
            return insets;
        });
        setContentView(screen); screen.requestApplyInsets();
    }

    private GradientDrawable panel(int color, int border, int radius) {
        GradientDrawable bg = new GradientDrawable(); bg.setColor(color); bg.setCornerRadius(dp(radius));
        if (border != 0) bg.setStroke(dp(1), border);
        return bg;
    }
    private void space(LinearLayout root, int height) { root.addView(new View(this), new LinearLayout.LayoutParams(1, dp(height))); }
    private LinearLayout column() { LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); return root; }
    private TextView section(String text) {
        TextView title = label(text, 15, INK); title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setPadding(0, dp(14), 0, dp(9)); return title;
    }

    private void showHome() {
        gameShowing = false; cancelSearch();
        LinearLayout root = column();
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(root);
        LinearLayout brand = new LinearLayout(this); brand.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView seal = label("帅", 23, RED_INK); seal.setTypeface(Typeface.create("serif", Typeface.BOLD));
        seal.setGravity(android.view.Gravity.CENTER); seal.setBackground(panel(0xffefe4d6, 0xffdfcbb0, 14));
        brand.addView(seal, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout names = column(); names.setPadding(dp(12), 0, 0, 0);
        TextView name = label("掌上象棋", 26, INK); name.setTypeface(Typeface.create("serif", Typeface.BOLD));
        names.addView(name); names.addView(label("落子有章，进退有道", 11, MUTED));
        brand.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
        Button settings = button("设置", v -> showSettings());
        brand.addView(settings, new LinearLayout.LayoutParams(dp(52), dp(38)));
        root.addView(brand); space(root, 16);

        FrameLayout hero = new FrameLayout(this);
        GradientDrawable heroBg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xff8d3933, 0xff6b2927});
        heroBg.setCornerRadius(dp(20)); hero.setBackground(heroBg); hero.setClipToOutline(true);
        hero.addView(new HeroView(), new FrameLayout.LayoutParams(-1, -1));
        LinearLayout words = column(); words.setPadding(dp(20), dp(20), 0, 0);
        TextView tag = label("方寸之间 · 自有天地", 11, 0xffdec4a4); words.addView(tag);
        TextView heroTitle = label("来下一盘好棋", 21, 0xfffff8ec);
        heroTitle.setTypeface(Typeface.create("serif", Typeface.BOLD)); heroTitle.setPadding(0, dp(9), 0, dp(7));
        words.addView(heroTitle); words.addView(label("从容落子，慢慢进步", 11, 0xffe5c9b7));
        hero.addView(words); root.addView(hero, new LinearLayout.LayoutParams(-1, dp(126)));
        root.addView(section("选择模式"));
        LinearLayout modes = new LinearLayout(this);
        String[] titles = {"人机对战", "同机双人", "AI 观战"}, subtitles = {"挑战四档对手", "与朋友轮流下", "看双方自动下"}, marks = {"将", "友", "弈"};
        for (int i = 0; i < 3; i++) {
            final int choice = i;
            LinearLayout card = column(); card.setGravity(android.view.Gravity.CENTER); card.setPadding(dp(3), dp(9), dp(3), dp(9));
            TextView mark = label(marks[i], 22, RED_INK); mark.setTypeface(Typeface.create("serif", Typeface.BOLD)); mark.setGravity(17);
            card.addView(mark); TextView title = label(titles[i], 14, INK); title.setGravity(17); title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            title.setPadding(0, dp(4), 0, dp(4)); card.addView(title);
            TextView subtitle = label(subtitles[i], 10, MUTED); subtitle.setGravity(17); card.addView(subtitle);
            card.setOnClickListener(v -> { setupMode = choice; saveOptions(); updateHomeChoices(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1); lp.setMargins(i == 0 ? 0 : dp(7), 0, 0, 0);
            modes.addView(card, lp); modeChoices[i] = card;
        }
        root.addView(modes); root.addView(section("对手难度"));
        for (int row = 0; row < 2; row++) {
            LinearLayout levels = new LinearLayout(this);
            for (int x = 0; x < 2; x++) {
                final int level = row * 2 + x;
                LinearLayout card = column(); card.setPadding(dp(14), dp(10), dp(8), dp(10));
                TextView title = label(LEVELS[level], 16, INK); title.setTypeface(Typeface.DEFAULT, Typeface.BOLD); card.addView(title);
                TextView desc = label(LEVEL_NOTES[level], 11, MUTED); desc.setPadding(0, dp(3), 0, 0); card.addView(desc);
                card.setOnClickListener(v -> { if (setupMode != 1) { setupLevel = level; saveOptions(); updateHomeChoices(); } });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1); lp.setMargins(x == 0 ? 0 : dp(8), 0, 0, dp(7));
                levels.addView(card, lp); levelChoices[level] = card;
            }
            root.addView(levels);
        }
        levelDescription = label("", 11, MUTED); levelDescription.setPadding(0, dp(2), 0, dp(12)); root.addView(levelDescription);
        startButton = button("开始对局", v -> startFromHome()); primary(startButton);
        root.addView(startButton, new LinearLayout.LayoutParams(-1, dp(50)));
        if (hasSavedGame) {
            space(root, 9); Button resume = button("继续上局", v -> { showGame(); refresh(); maybePhoneMove(); });
            root.addView(resume, new LinearLayout.LayoutParams(-1, dp(44)));
            TextView saved = label((watching ? "AI 观战" : twoPlayers ? "同机双人" : "人机对战")
                    + (twoPlayers ? "" : " · " + LEVELS[difficulty]) + " · 已走 " + plyCount + " 着", 10, MUTED);
            saved.setGravity(17); saved.setPadding(0, dp(5), 0, 0); root.addView(saved);
        }
        LinearLayout foot = new LinearLayout(this); foot.setGravity(17);
        Button rules = button("玩法说明", v -> showRules()), about = button("关于", v -> showAbout());
        for (Button b : new Button[]{rules, about}) {
            b.setBackgroundColor(Color.TRANSPARENT); b.setTextSize(12); b.setTextColor(MUTED);
            foot.addView(b, new LinearLayout.LayoutParams(0, dp(40), 1));
        }
        root.addView(foot); installPage(scroll); updateHomeChoices();
    }

    private void updateHomeChoices() {
        for (int i = 0; i < 3; i++) {
            boolean chosen = setupMode == i; modeChoices[i].setSelected(chosen);
            modeChoices[i].setBackground(panel(chosen ? 0xfff1e3dc : 0xfffffdfa, chosen ? RED_INK : 0xffe6ded2, 14));
        }
        for (int i = 0; i < 4; i++) {
            boolean chosen = setupLevel == i && setupMode != 1; levelChoices[i].setSelected(chosen);
            levelChoices[i].setEnabled(setupMode != 1); levelChoices[i].setAlpha(setupMode == 1 ? .45f : 1f);
            levelChoices[i].setBackground(panel(chosen ? 0xfff1e3dc : 0xfffffdfa, chosen ? RED_INK : 0xffe6ded2, 12));
        }
        levelDescription.setText(setupMode == 1 ? "同机双人：红方先走，两人轮流操作手机。"
                : setupMode == 2 ? "双方使用所选难度，可暂停、逐步观看。" : "你执红先走，可随时使用提示与悔棋。");
        startButton.setText(setupMode == 2 ? "开始观战" : "开始对局");
    }
    private void saveOptions() {
        getSharedPreferences("options", MODE_PRIVATE).edit().putInt("mode", setupMode)
                .putInt("level", setupLevel).putBoolean("vibration", vibration).apply();
    }
    private void startFromHome() {
        Runnable begin = () -> { twoPlayers = setupMode == 1; watching = setupMode == 2; difficulty = setupLevel; showGame(); restart(); };
        if (hasSavedGame && plyCount > 0 && !finished) new AlertDialog.Builder(this).setTitle("开始新的一局？")
                .setMessage("上一次的棋局将被替换。要继续旧棋局，可以返回首页选择“继续上局”。")
                .setPositiveButton("开始新局", (d, w) -> begin.run()).setNegativeButton("取消", null).show();
        else begin.run();
    }
    private void goHome() {
        cancelSearch(); if (watching) watchPaused = true;
        selected = -1; targets.clear(); if (hasSavedGame) save(); showHome();
    }
    private void handleBack() { if (gameShowing) goHome(); else finish(); }
    @Override public void onBackPressed() { handleBack(); }

    private LinearLayout player(boolean red) {
        LinearLayout card = new LinearLayout(this); card.setGravity(android.view.Gravity.CENTER_VERTICAL);
        card.setPadding(dp(10), dp(6), dp(10), dp(6)); card.setBackground(panel(0xfffffdfa, 0xffe8dfd2, 14));
        card.addView(new PieceAvatar(red), new LinearLayout.LayoutParams(dp(36), dp(36)));
        LinearLayout text = column(); text.setPadding(dp(10), 0, 0, 0);
        TextView name = label("", 13, INK), meta = label("", 10, MUTED); name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        text.addView(name); meta.setPadding(0, dp(3), 0, 0); text.addView(meta);
        card.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        if (red) { redName = name; redMeta = meta; } else { blackName = name; blackMeta = meta; }
        return card;
    }

    private void showGame() {
        gameShowing = true;
        LinearLayout root = column();
        LinearLayout toolbar = new LinearLayout(this); toolbar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        Button home = button("‹ 首页", v -> goHome()); home.setBackgroundColor(Color.TRANSPARENT); home.setTextColor(RED_INK);
        toolbar.addView(home, new LinearLayout.LayoutParams(dp(64), dp(40)));
        LinearLayout heading = column(); heading.setGravity(17);
        TextView title = label("掌上象棋", 20, INK); title.setGravity(17); title.setTypeface(Typeface.create("serif", Typeface.BOLD));
        heading.addView(title); modeLabel = label("", 10, MUTED); modeLabel.setGravity(17); heading.addView(modeLabel);
        toolbar.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        Button settings = button("设置", v -> showSettings()); settings.setBackgroundColor(Color.TRANSPARENT);
        toolbar.addView(settings, new LinearLayout.LayoutParams(dp(54), dp(40))); root.addView(toolbar);
        space(root, 7); root.addView(player(false));

        status = label("", 14, RED_INK); status.setPadding(dp(3), dp(8), 0, dp(4));
        status.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(status);
        boardView = new BoardView();
        root.addView(boardView, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(player(true));
        caption = label(note, 13, MUTED);
        caption.setMinHeight(dp(42));
        caption.setGravity(android.view.Gravity.CENTER);
        root.addView(caption, new LinearLayout.LayoutParams(-1, -2));
        watchControls = new LinearLayout(this);
        pauseButton = button("继续", v -> toggleWatching());
        stepButton = button("下一步", v -> startSearch(false));
        speedButton = button("间隔 2 秒", v -> showWatchSpeed());
        for (Button b : new Button[]{pauseButton, stepButton, speedButton}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1);
            lp.setMargins(dp(3), 0, dp(3), dp(4));
            watchControls.addView(b, lp);
        }
        root.addView(watchControls, new LinearLayout.LayoutParams(-1, dp(50)));
        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(android.view.Gravity.CENTER);
        undoButton = button("悔棋", v -> undo());
        hintButton = button("提示", v -> startSearch(true));
        Button reset = button("重开", v -> confirmRestart());
        Button modes = button("模式", v -> showModes());
        for (Button b : new Button[]{undoButton, hintButton, reset, modes}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1);
            lp.setMargins(dp(3), 0, dp(3), 0);
            buttons.addView(b, lp);
        }
        root.addView(buttons, new LinearLayout.LayoutParams(-1, dp(54)));
        gameFoot = label("", 10, MUTED); gameFoot.setGravity(17); gameFoot.setPadding(0, dp(4), 0, 0); root.addView(gameFoot);
        installPage(root);
        refresh();
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView label(String text, int size, int color) {
        TextView v = new TextView(this); v.setText(text); v.setTextSize(size); v.setTextColor(color); return v;
    }
    private Button button(String text, View.OnClickListener click) {
        Button b = new Button(this); b.setText(text); b.setTextSize(14); b.setAllCaps(false);
        b.setTextColor(INK); b.setPadding(0, 0, 0, 0); b.setMinWidth(0); b.setMinimumWidth(0);
        b.setStateListAnimator(null);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x18963d35), panel(0xfffffdfa, 0xffe2d8c8, 12), null));
        b.setOnClickListener(click); return b;
    }
    private void primary(Button b) {
        b.setTextColor(0xfffff9ef); b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x30ffffff), panel(RED_INK, 0, 14), null));
    }

    private void showSettings() {
        if (watching && gameShowing && !watchPaused) toggleWatching();
        new AlertDialog.Builder(this).setTitle("游戏设置")
                .setMultiChoiceItems(new String[]{"落子震动"}, new boolean[]{vibration}, (d, n, checked) -> { vibration = checked; saveOptions(); })
                .setNeutralButton("玩法说明", (d, w) -> showRules()).setPositiveButton("完成", null).show();
    }
    private void showRules() {
        new AlertDialog.Builder(this).setTitle("玩法说明")
                .setMessage("红方先走。点一下己方棋子，再点标记的落点。圆点可以走，圆环可以吃。\n\n人机对战：你执红，手机执黑，可提示、悔棋和认输。简单、普通适合练习；困难、大师使用 Pikafish 专业引擎，大师是本游戏最高档，并非正式棋力评级。\n\n同机双人：两人轮流操作手机，悔棋撤回一着。\n\nAI 观战：双方使用所选难度，可暂停、逐步观看、调速；暂停后可以提示、撤回一着。离开棋盘后自动暂停保存。\n\n将死与困毙均判负。休闲规则不自动裁判复杂长将长捉，可以重开或结束本局。")
                .setPositiveButton("知道了", null).show();
    }
    private void showAbout() {
        new AlertDialog.Builder(this).setTitle("关于掌上象棋")
                .setMessage("掌上象棋 2.1\n离线对弈 · 本地存档\n\n困难、大师：Pikafish 专业象棋引擎。引擎采用 GPL-3.0 许可，完整源码及许可随本项目提供。NNUE 权重采用上游非商业使用许可。\n\n棋局和设置保存在这部手机上，无需账号或联网。")
                .setPositiveButton("关闭", null).show();
    }

    private int countPieces(int side) { int count = 0; for (int p : board) if (p * side > 0) count++; return count; }

    private void refresh() {
        if (winner == 0 && Chess.moves(board, turn).isEmpty()) { winner = -turn; resultReason = Chess.inCheck(board, turn) ? "将死" : "困毙"; }
        finished = winner != 0;
        if (!gameShowing) return;
        String level = LEVELS[difficulty];
        modeLabel.setText(watching ? "观战模式 · " + level + " AI"
                : twoPlayers ? "双人模式 · 本机轮流走棋" : "单人模式 · " + level);
        if (finished) status.setText((winner == Chess.RED ? "红方" : "黑方") + "获胜 · " + resultReason);
        else if (thinking) status.setText((watching ? (turn == Chess.RED ? "红方 AI" : "黑方 AI") : "") + "正在思考…");
        else if (watching) status.setText((watchPaused ? "观战已暂停 · " : "观战中 · ")
                + (turn == Chess.RED ? "轮到红方" : "轮到黑方") + (Chess.inCheck(board, turn) ? " · 将军" : ""));
        else status.setText((turn == Chess.RED ? "轮到红方" : twoPlayers ? "轮到黑方" : "轮到手机")
                    + (Chess.inCheck(board, turn) ? " · 将军，请应将" : ""));
        caption.setText(note);
        blackName.setText(watching ? "黑方 AI" : twoPlayers ? "黑方棋手" : "手机对手");
        redName.setText(watching ? "红方 AI" : twoPlayers ? "红方棋手" : "我方 · 红方");
        blackMeta.setText((twoPlayers ? "黑方" : level + "难度") + " · 已吃 " + Math.max(0, 16 - countPieces(Chess.RED)) + " 子");
        redMeta.setText((watching ? level + "难度" : "红方先走") + " · 已吃 " + Math.max(0, 16 - countPieces(Chess.BLACK)) + " 子");
        gameFoot.setText("第 " + (plyCount / 2 + 1) + " 回合 · 离线对弈 · 自动保存");
        undoButton.setEnabled(!history.isEmpty());
        undoButton.setAlpha(history.isEmpty() ? .45f : 1f);
        hintButton.setEnabled(!finished && !thinking && (watching ? watchPaused : twoPlayers || turn == Chess.RED));
        hintButton.setAlpha(hintButton.isEnabled() ? 1f : .45f);
        watchControls.setVisibility(watching ? View.VISIBLE : View.GONE);
        pauseButton.setText(watchPaused ? "继续" : "暂停");
        pauseButton.setEnabled(!finished);
        pauseButton.setAlpha(finished ? .45f : 1f);
        stepButton.setEnabled(watchPaused && !thinking && !finished);
        stepButton.setAlpha(stepButton.isEnabled() ? 1f : .45f);
        speedButton.setText("间隔 " + watchDelay / 1000 + " 秒");
        boardView.setContentDescription("中国象棋棋盘，" + status.getText());
        boardView.invalidate();
    }

    private void tap(int at) {
        if (watching || finished || thinking || (!twoPlayers && turn != Chess.RED)) return;
        hintMove = null;
        if (board[at] * turn > 0) {
            selected = selected == at ? -1 : at;
            targets = selected < 0 ? new ArrayList<>() : Chess.targets(board, selected, turn);
            note = selected < 0 ? "点选棋子，再点落点。" : "圆点是可走位置，圆环是可吃棋子。";
            refresh(); return;
        }
        if (selected < 0) return;
        if (!Chess.legal(board, selected, at, turn)) {
            note = Chess.inCheck(board, turn) ? "正在被将军，这一步不能解将。" : "这里不能走，请选择标记的落点。";
            refresh(); return;
        }
        move(new Chess.Move(selected, at));
        maybePhoneMove();
    }

    private void move(Chess.Move m) {
        if (!Chess.legal(board, m.from, m.to, turn)) return;
        history.add(new Snapshot(board, turn, lastFrom, lastTo));
        if (history.size() > 300) history.remove(0);
        int piece = board[m.from], captured = board[m.to];
        String notation = Chess.notation(board, m);
        Chess.play(board, m); turn = -turn;
        plyCount++;
        lastFrom = m.from; lastTo = m.to; selected = -1; targets.clear(); hintMove = null;
        note = (piece > 0 ? "红方 · " : "黑方 · ") + notation + (captured == 0 ? "" : " · 吃" + Chess.name(captured));
        if (Chess.inCheck(board, turn)) note += "将军。";
        refresh(); save();
        if (vibration) boardView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        if (finished) {
            note = "本局结束，可以悔棋继续，也可以重开。";
            refresh();
            if (active) new AlertDialog.Builder(this).setTitle(status.getText())
                    .setMessage("再来一盘，还是悔棋继续？")
                    .setPositiveButton("再来一盘", (d, w) -> restart())
                    .setNegativeButton("留在棋盘", null).show();
        }
    }

    private void maybePhoneMove() {
        main.removeCallbacks(watchAdvance);
        if (!active || !gameShowing || finished || thinking) return;
        if (watching) {
            if (!watchPaused) main.postDelayed(watchAdvance, watchDelay);
        } else if (!twoPlayers && turn == Chess.BLACK) startSearch(false);
    }

    private void toggleWatching() {
        if (!watching || finished) return;
        if (!watchPaused) {
            watchPaused = true;
            cancelSearch();
            note = "已暂停，可用下一步、提示或悔棋慢慢看。";
        } else {
            cancelSearch();
            watchPaused = false;
            note = "红黑双方自动对弈，随时可以暂停。";
        }
        save(); refresh(); maybePhoneMove();
    }

    private void showWatchSpeed() {
        String[] labels = {"快速：每着间隔 1 秒", "标准：每着间隔 2 秒", "慢速：每着间隔 4 秒"};
        int current = watchDelay == 1000 ? 0 : watchDelay == 4000 ? 2 : 1;
        new AlertDialog.Builder(this).setTitle("观战速度")
                .setSingleChoiceItems(labels, current, (dialog, n) -> {
                    watchDelay = new int[]{1000, 2000, 4000}[n];
                    save(); refresh(); maybePhoneMove(); dialog.dismiss();
                }).setNegativeButton("取消", null).show();
    }

    private void startSearch(final boolean hint) {
        if (!active || !gameShowing || thinking || finished) return;
        if (hint && (watching ? !watchPaused : !twoPlayers && turn != Chess.RED)) return;
        if (!hint && !watching && (twoPlayers || turn != Chess.BLACK)) return;
        main.removeCallbacks(watchAdvance);
        final int id = ++generation, side = turn;
        final int[] position = board.clone();
        final int level = hint ? Math.max(1, difficulty) : difficulty;
        thinking = true; selected = -1; targets.clear();
        note = hint ? "正在寻找一着可参考的走法…" : watching
                ? (side == Chess.RED ? "红方" : "黑方") + " AI 正在选择走法…" : "手机正在走黑棋…";
        refresh();
        search = worker.submit(() -> {
            try {
                final Chess.Move found = level >= 2 ? strongEngine.bestMove(position, side, level)
                        : Chess.bestMove(position, side, level == 0 ? 1 : 3, level == 0 ? 180 : 650);
                main.post(() -> {
                    if (!active || !gameShowing || id != generation || side != turn || !Arrays.equals(position, board)) return;
                    thinking = false; search = null;
                    if (hint) {
                        hintMove = found;
                        note = found == null ? "没有可走的着法。" : "箭头是一种建议，你也可以选择其他走法。";
                        refresh();
                    } else if (found != null) { move(found); maybePhoneMove(); }
                    else refresh();
                });
            } catch (Exception | LinkageError error) {
                main.post(() -> {
                    if (!active || id != generation) return;
                    thinking = false; search = null;
                    if (watching) watchPaused = true;
                    note = "对手暂时未能完成计算，请重试或选择其他难度。"; refresh();
                    new AlertDialog.Builder(this).setTitle("对手暂时不可用")
                            .setMessage("当前计算未完成，棋局已经保留。你可以重试，或在模式中调整难度。")
                            .setPositiveButton("重试", (d, w) -> { if (watching) { watchPaused = false; refresh(); } maybePhoneMove(); })
                            .setNegativeButton("留在棋盘", null).show();
                });
            }
        });
    }

    private void cancelSearch() {
        main.removeCallbacks(watchAdvance);
        ++generation;
        if (strongEngine != null) strongEngine.stop();
        if (search != null) search.cancel(true);
        search = null; thinking = false; hintMove = null;
    }

    private void undo() {
        if (history.isEmpty()) return;
        cancelSearch();
        if (watching) watchPaused = true;
        Snapshot restore = history.remove(history.size() - 1);
        int removed = 1;
        if (!watching && !twoPlayers && restore.turn == Chess.BLACK && !history.isEmpty()) { restore = history.remove(history.size() - 1); removed++; }
        board = restore.board.clone(); turn = restore.turn; lastFrom = restore.from; lastTo = restore.to;
        plyCount = Math.max(0, plyCount - removed); winner = 0; resultReason = "";
        selected = -1; targets.clear(); note = watching ? "已撤回一着并暂停，点击下一步或继续观战。" : "已悔棋，请重新选择走法。";
        save(); refresh(); maybePhoneMove();
    }

    private void confirmRestart() {
        if (history.isEmpty()) { restart(); return; }
        new AlertDialog.Builder(this).setTitle("重新开始？")
                .setMessage("当前这一盘会重新摆棋。")
                .setPositiveButton("重开", (d, w) -> restart()).setNegativeButton("继续下", null).show();
    }

    private void restart() {
        cancelSearch(); board = Chess.initial(); turn = Chess.RED; history.clear();
        winner = 0; resultReason = ""; plyCount = 0; hasSavedGame = true;
        selected = lastFrom = lastTo = -1; targets.clear();
        if (watching) watchPaused = false;
        note = watching ? "红黑双方自动对弈，随时可以暂停。"
                : twoPlayers ? "红方先走，两人轮流操作。" : "你执红先走，手机会自动走黑棋。";
        save(); refresh(); maybePhoneMove();
    }

    private void showModes() {
        if (watching && !watchPaused) toggleWatching();
        new AlertDialog.Builder(this).setTitle("怎么玩")
                .setItems(new String[]{"单人：手机自动走黑棋", "双人：在本机轮流走棋", "观战：AI 对战 AI", "对手难度", "走棋说明", "结束本局 / 认输", "返回首页"}, (d, which) -> {
                    if (which <= 2) {
                        final boolean nextTwo = which == 1, nextWatch = which == 2;
                        if (nextTwo == twoPlayers && nextWatch == watching) return;
                        if (history.isEmpty()) { twoPlayers = nextTwo; watching = nextWatch; restart(); }
                        else new AlertDialog.Builder(this).setTitle("切换模式并重开？")
                                .setPositiveButton("切换", (dialog, w) -> { twoPlayers = nextTwo; watching = nextWatch; restart(); })
                                .setNegativeButton("取消", null).show();
                    } else if (which == 3) {
                        new AlertDialog.Builder(this).setTitle("手机对手难度")
                                .setSingleChoiceItems(LEVELS, difficulty, (dialog, n) -> {
                                    cancelSearch(); difficulty = n; save(); dialog.dismiss(); refresh(); maybePhoneMove();
                                }).setNegativeButton("取消", null).show();
                    } else if (which == 4) showRules();
                    else if (which == 5) {
                        if (watching || finished) { goHome(); return; }
                        new AlertDialog.Builder(this).setTitle("确认认输？").setMessage("本局将判对方获胜，仍可在棋盘上悔棋。")
                                .setPositiveButton("认输", (dialog, w) -> { cancelSearch(); winner = twoPlayers ? -turn : Chess.BLACK; resultReason = "认输"; note = "本局已结束，可重开或返回首页。"; refresh(); save(); })
                                .setNegativeButton("继续下", null).show();
                    } else goHome();
                }).show();
    }

    private JSONArray encodeBoard(int[] b) {
        JSONArray a = new JSONArray(); for (int p : b) a.put(p); return a;
    }
    private int[] decodeBoard(JSONArray a) throws Exception {
        if (a == null || a.length() != 90) throw new Exception("Bad board");
        int[] b = new int[90]; int redKing = 0, blackKing = 0;
        for (int i = 0; i < 90; i++) {
            b[i] = a.getInt(i); if (b[i] < -7 || b[i] > 7) throw new Exception("Bad piece");
            if (b[i] == Chess.KING) redKing++; if (b[i] == -Chess.KING) blackKing++;
        }
        if (redKing != 1 || blackKing != 1) throw new Exception("Bad kings"); return b;
    }
    private void save() {
        try {
            if (!hasSavedGame) return;
            JSONObject state = new JSONObject(); state.put("version", 2); state.put("board", encodeBoard(board));
            state.put("turn", turn); state.put("two", twoPlayers); state.put("level", difficulty);
            state.put("watch", watching); state.put("watchDelay", watchDelay);
            state.put("winner", winner); state.put("reason", resultReason); state.put("ply", plyCount);
            state.put("from", lastFrom); state.put("to", lastTo);
            JSONArray past = new JSONArray();
            for (Snapshot s : history) {
                JSONObject h = new JSONObject(); h.put("board", encodeBoard(s.board)); h.put("turn", s.turn);
                h.put("from", s.from); h.put("to", s.to); past.put(h);
            }
            state.put("history", past);
            getSharedPreferences("xiangqi", MODE_PRIVATE).edit().putString("saved", state.toString()).apply();
        } catch (Exception error) { Toast.makeText(this, "本局暂时未能保存", Toast.LENGTH_SHORT).show(); }
    }
    private void restore() {
        try {
            String data = getSharedPreferences("xiangqi", MODE_PRIVATE).getString("saved", "");
            if (data.isEmpty()) return;
            JSONObject state = new JSONObject(data);
            if (state.getInt("version") != 1 && state.getInt("version") != 2) return;
            int[] loaded = decodeBoard(state.getJSONArray("board"));
            int side = state.getInt("turn"); if (side != Chess.RED && side != Chess.BLACK) return;
            ArrayList<Snapshot> past = new ArrayList<>();
            JSONArray array = state.optJSONArray("history");
            if (array != null) for (int i = Math.max(0, array.length() - 300); i < array.length(); i++) {
                JSONObject h = array.getJSONObject(i);
                int s = h.getInt("turn"); if (s != Chess.RED && s != Chess.BLACK) throw new Exception("Bad turn");
                past.add(new Snapshot(decodeBoard(h.getJSONArray("board")), s, h.optInt("from", -1), h.optInt("to", -1)));
            }
            board = loaded; turn = side; history.addAll(past);
            twoPlayers = state.optBoolean("two", false); difficulty = Math.max(0, Math.min(3, state.optInt("level", 1)));
            watching = state.optBoolean("watch", false);
            if (watching) twoPlayers = false;
            watchPaused = true;
            int delay = state.optInt("watchDelay", 2000);
            watchDelay = delay == 1000 || delay == 4000 ? delay : 2000;
            lastFrom = state.optInt("from", -1); lastTo = state.optInt("to", -1);
            winner = state.optInt("winner", 0); if (winner != Chess.RED && winner != Chess.BLACK) winner = 0;
            resultReason = state.optString("reason", ""); plyCount = Math.max(past.size(), state.optInt("ply", past.size()));
            hasSavedGame = true;
            note = watching ? "已恢复上次的观战，点击继续或下一步。" : "已恢复上次的棋局。";
        } catch (Exception ignored) { board = Chess.initial(); turn = Chess.RED; history.clear(); hasSavedGame = false; winner = 0; plyCount = 0; }
    }

    @Override protected void onResume() { super.onResume(); active = true; refresh(); maybePhoneMove(); }
    @Override protected void onPause() {
        active = false; cancelSearch();
        if (watching) watchPaused = true;
        note = watching ? "观战已保存并暂停，点击继续或下一步。" : "棋局已保存，可以继续下棋。";
        save(); super.onPause();
    }
    @Override protected void onDestroy() { cancelSearch(); worker.submit(() -> strongEngine.close()); worker.shutdown(); super.onDestroy(); }

    private final class PieceAvatar extends View {
        private final boolean red;
        PieceAvatar(boolean red) {
            super(MainActivity.this); this.red = red;
            setContentDescription(red ? "红方帅" : "黑方将");
        }
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            piecePainter.draw(c, getWidth() / 2f, getHeight() / 2f,
                    Math.min(getWidth(), getHeight()) * .425f, red ? "帅" : "将", red);
        }
    }

    private final class HeroView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        HeroView() { super(MainActivity.this); setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); }
        @Override protected void onDraw(Canvas c) {
            float cx = getWidth() - dp(52), cy = getHeight() / 2f;
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1)); paint.setColor(0x18f8dfb8);
            for (int i = -3; i <= 3; i++) {
                c.drawLine(cx + dp(i * 22), 0, cx + dp(i * 22), getHeight(), paint);
                c.drawLine(getWidth() - dp(143), cy + dp(i * 22), getWidth(), cy + dp(i * 22), paint);
            }
            c.save(); c.rotate(-12, cx, cy);
            piecePainter.draw(c, cx, cy, dp(33), "马", true); c.restore();
        }
    }

    private final class BoardView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float cell, ox, oy;
        BoardView() { super(MainActivity.this); setLayerType(View.LAYER_TYPE_SOFTWARE, null); setFocusable(true); }
        private float px(int at) { return ox + (at % 9) * cell; }
        private float py(int at) { return oy + (at / 9) * cell; }
        private void ink(int color, float width, Paint.Style style) {
            paint.setShader(null); paint.setColor(color); paint.setStrokeWidth(width); paint.setStyle(style); paint.clearShadowLayer();
        }
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            // 包含边缘棋子的完整半径，避免左右两路棋子被视图裁掉。
            cell = Math.min((getWidth() - dp(12)) / 8.84f, (getHeight() - dp(12)) / 9.84f);
            if (cell <= 0) return;
            ox = (getWidth() - 8 * cell) / 2; oy = (getHeight() - 9 * cell) / 2;
            float radius = cell * .44f;
            ink(WOOD, 1, Paint.Style.FILL);
            paint.setShader(new LinearGradient(ox, oy, ox + 8 * cell, oy + 9 * cell, 0xfff2e4c9, 0xffe7cfa6, Shader.TileMode.CLAMP));
            c.drawRoundRect(ox - radius - dp(3), oy - radius - dp(3), ox + 8 * cell + radius + dp(3),
                    oy + 9 * cell + radius + dp(3), dp(12), dp(12), paint);
            ink(LINE, dp(1), Paint.Style.STROKE);
            for (int y = 0; y < 10; y++) c.drawLine(ox, oy + y * cell, ox + cell * 8, oy + y * cell, paint);
            for (int x = 0; x < 9; x++) {
                if (x == 0 || x == 8) c.drawLine(ox + x * cell, oy, ox + x * cell, oy + 9 * cell, paint);
                else {
                    c.drawLine(ox + x * cell, oy, ox + x * cell, oy + 4 * cell, paint);
                    c.drawLine(ox + x * cell, oy + 5 * cell, ox + x * cell, oy + 9 * cell, paint);
                }
            }
            for (int top : new int[]{0, 7}) {
                c.drawLine(ox + 3 * cell, oy + top * cell, ox + 5 * cell, oy + (top + 2) * cell, paint);
                c.drawLine(ox + 5 * cell, oy + top * cell, ox + 3 * cell, oy + (top + 2) * cell, paint);
            }
            ink(LINE, 1, Paint.Style.FILL); paint.setTypeface(Typeface.create("serif", Typeface.NORMAL));
            paint.setTextSize(cell * .48f); paint.setTextAlign(Paint.Align.CENTER);
            float river = oy + cell * 4.5f - (paint.ascent() + paint.descent()) / 2;
            c.drawText("楚 河", ox + 2 * cell, river, paint); c.drawText("汉 界", ox + 6 * cell, river, paint);
            for (int at : new int[]{lastFrom, lastTo}) if (at >= 0 && at < 90) {
                ink(0xffb08738, dp(2), Paint.Style.STROKE);
                c.drawRoundRect(px(at) - radius, py(at) - radius, px(at) + radius, py(at) + radius, dp(4), dp(4), paint);
            }
            for (int at = 0; at < 90; at++) {
                if (board[at] == 0) continue;
                piecePainter.draw(c, px(at), py(at), radius, Chess.name(board[at]), board[at] > 0);
            }
            if (selected >= 0) {
                ink(0xffc08b21, dp(3), Paint.Style.STROKE);
                c.drawCircle(px(selected), py(selected), radius + dp(2), paint);
                for (int target : targets) {
                    ink(0xff497960, dp(2.5f), board[target] == 0 ? Paint.Style.FILL : Paint.Style.STROKE);
                    c.drawCircle(px(target), py(target), board[target] == 0 ? cell * .1f : radius + dp(2), paint);
                }
            }
            if (hintMove != null) drawArrow(c, hintMove, radius);
        }
        private void drawArrow(Canvas c, Chess.Move m, float radius) {
            float fx = px(m.from), fy = py(m.from), tx = px(m.to), ty = py(m.to);
            float dx = tx - fx, dy = ty - fy, distance = (float)Math.sqrt(dx * dx + dy * dy);
            if (distance == 0) return;
            float ux = dx / distance, uy = dy / distance;
            fx += ux * radius; fy += uy * radius; tx -= ux * radius * .6f; ty -= uy * radius * .6f;
            ink(0xee3a765b, dp(4), Paint.Style.STROKE); c.drawLine(fx, fy, tx, ty, paint);
            ink(0xee3a765b, 1, Paint.Style.FILL);
            Path p = new Path(); p.moveTo(tx, ty);
            p.lineTo(tx - ux * dp(12) + uy * dp(6), ty - uy * dp(12) - ux * dp(6));
            p.lineTo(tx - ux * dp(12) - uy * dp(6), ty - uy * dp(12) + ux * dp(6)); p.close(); c.drawPath(p, paint);
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_DOWN) return true;
            if (event.getAction() == MotionEvent.ACTION_UP) {
                performClick();
                if (cell <= 0) return true;
                int x = Math.round((event.getX() - ox) / cell), y = Math.round((event.getY() - oy) / cell);
                if (x >= 0 && x < 9 && y >= 0 && y < 10
                        && Math.abs(event.getX() - ox - x * cell) <= cell * .5f
                        && Math.abs(event.getY() - oy - y * cell) <= cell * .5f) tap(y * 9 + x);
                return true;
            }
            return true;
        }
        @Override public boolean performClick() { super.performClick(); return true; }
    }
}
