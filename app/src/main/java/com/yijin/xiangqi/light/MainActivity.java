package com.yijin.xiangqi.light;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
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
    private static final int BG = 0xfff6f1e8, INK = 0xff34362f, MUTED = 0xff827a6c,
            RED_INK = 0xffa7392f, WOOD = 0xffead4ad, LINE = 0xff826548;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Future<?> search;
    private int[] board = Chess.initial();
    private int turn = Chess.RED, selected = -1, lastFrom = -1, lastTo = -1, difficulty = 1;
    private boolean twoPlayers, watching, thinking, finished, active;
    private boolean watchPaused = true;
    private int watchDelay = 2000;
    private final Runnable watchAdvance = () -> {
        if (active && watching && !watchPaused && !thinking && !finished) startSearch(false);
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
        restore();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(16), dp(12), dp(16), dp(12));
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        else getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        if (Build.VERSION.SDK_INT >= 26) getWindow().getDecorView().setSystemUiVisibility(
                getWindow().getDecorView().getSystemUiVisibility() | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                        | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top, bottom, left, right;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                top = bars.top; bottom = bars.bottom; left = bars.left; right = bars.right;
            } else {
                top = insets.getSystemWindowInsetTop(); bottom = insets.getSystemWindowInsetBottom();
                left = insets.getSystemWindowInsetLeft(); right = insets.getSystemWindowInsetRight();
            }
            v.setPadding(dp(16) + left, dp(10) + top, dp(16) + right, dp(10) + bottom);
            return insets;
        });

        TextView title = label("掌上象棋", 25, INK);
        title.setTypeface(Typeface.create("serif", Typeface.BOLD));
        root.addView(title);
        modeLabel = label("", 13, MUTED);
        modeLabel.setPadding(0, dp(5), 0, dp(10));
        root.addView(modeLabel);
        status = label("", 16, RED_INK);
        status.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(status);
        boardView = new BoardView();
        root.addView(boardView, new LinearLayout.LayoutParams(-1, 0, 1));
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
        TextView foot = label("离线游玩 · 自动保存", 11, MUTED);
        foot.setGravity(android.view.Gravity.CENTER);
        root.addView(foot);
        setContentView(root);
        root.requestApplyInsets();
        refresh();
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView label(String text, int size, int color) {
        TextView v = new TextView(this); v.setText(text); v.setTextSize(size); v.setTextColor(color); return v;
    }
    private Button button(String text, View.OnClickListener click) {
        Button b = new Button(this); b.setText(text); b.setTextSize(14); b.setAllCaps(false);
        b.setTextColor(INK); b.setPadding(0, 0, 0, 0); b.setMinWidth(0); b.setMinimumWidth(0);
        GradientDrawable bg = new GradientDrawable(); bg.setColor(0xffe9e0d1); bg.setCornerRadius(dp(10));
        b.setBackground(bg); b.setBackgroundTintList(ColorStateList.valueOf(0xffe9e0d1));
        b.setOnClickListener(click); return b;
    }

    private void refresh() {
        finished = Chess.moves(board, turn).isEmpty();
        String level = difficulty == 0 ? "轻松" : "普通";
        modeLabel.setText(watching ? "观战模式 · 红黑双方自动对弈 · " + level + " AI"
                : twoPlayers ? "双人模式 · 两人在本机轮流走棋" : "单人模式 · 你执红，手机执黑 · " + level);
        if (finished) status.setText((turn == Chess.RED ? "黑方" : "红方") + "获胜 · " + (Chess.inCheck(board, turn) ? "将死" : "困毙"));
        else if (thinking) status.setText((watching ? (turn == Chess.RED ? "红方 AI" : "黑方 AI") : "") + "正在思考…");
        else if (watching) status.setText((watchPaused ? "观战已暂停 · " : "观战中 · ")
                + (turn == Chess.RED ? "轮到红方" : "轮到黑方") + (Chess.inCheck(board, turn) ? " · 将军" : ""));
        else status.setText((turn == Chess.RED ? "轮到红方" : twoPlayers ? "轮到黑方" : "轮到手机")
                    + (Chess.inCheck(board, turn) ? " · 将军，请应将" : ""));
        caption.setText(note);
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
        Chess.play(board, m); turn = -turn;
        lastFrom = m.from; lastTo = m.to; selected = -1; targets.clear(); hintMove = null;
        note = (piece > 0 ? "红" : "黑") + Chess.name(piece) + "走棋" + (captured == 0 ? "。" : "，吃掉" + Chess.name(captured) + "。");
        if (Chess.inCheck(board, turn)) note += "将军。";
        save(); refresh();
        boardView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
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
        if (!active || finished || thinking) return;
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
        if (!active || thinking || finished) return;
        if (hint && (watching ? !watchPaused : !twoPlayers && turn != Chess.RED)) return;
        if (!hint && !watching && (twoPlayers || turn != Chess.BLACK)) return;
        main.removeCallbacks(watchAdvance);
        final int id = ++generation, side = turn;
        final int[] position = board.clone();
        final int depth = difficulty == 0 ? 1 : 3;
        final int budget = difficulty == 0 ? 200 : 650;
        thinking = true; selected = -1; targets.clear();
        note = hint ? "正在寻找一着可参考的走法…" : watching
                ? (side == Chess.RED ? "红方" : "黑方") + " AI 正在选择走法…" : "手机正在走黑棋…";
        refresh();
        search = worker.submit(() -> {
            try {
                final Chess.Move found = Chess.bestMove(position, side, depth, budget);
                main.post(() -> {
                    if (!active || id != generation || side != turn || !Arrays.equals(position, board)) return;
                    thinking = false; search = null;
                    if (hint) {
                        hintMove = found;
                        note = found == null ? "没有可走的着法。" : "箭头是一种建议，你也可以选择其他走法。";
                        refresh();
                    } else if (found != null) { move(found); maybePhoneMove(); }
                    else refresh();
                });
            } catch (RuntimeException error) {
                main.post(() -> {
                    if (!active || id != generation) return;
                    thinking = false; search = null;
                    if (watching) watchPaused = true;
                    note = "计算已中断，可悔棋或重开。"; refresh();
                });
            }
        });
    }

    private void cancelSearch() {
        main.removeCallbacks(watchAdvance);
        ++generation;
        if (search != null) search.cancel(true);
        search = null; thinking = false; hintMove = null;
    }

    private void undo() {
        if (history.isEmpty()) return;
        cancelSearch();
        if (watching) watchPaused = true;
        Snapshot restore = history.remove(history.size() - 1);
        if (!watching && !twoPlayers && restore.turn == Chess.BLACK && !history.isEmpty()) restore = history.remove(history.size() - 1);
        board = restore.board.clone(); turn = restore.turn; lastFrom = restore.from; lastTo = restore.to;
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
        selected = lastFrom = lastTo = -1; targets.clear();
        if (watching) watchPaused = false;
        note = watching ? "红黑双方自动对弈，随时可以暂停。"
                : twoPlayers ? "红方先走，两人轮流操作。" : "你执红先走，手机会自动走黑棋。";
        save(); refresh(); maybePhoneMove();
    }

    private void showModes() {
        if (watching && !watchPaused) toggleWatching();
        new AlertDialog.Builder(this).setTitle("怎么玩")
                .setItems(new String[]{"单人：手机自动走黑棋", "双人：在本机轮流走棋", "观战：AI 对战 AI", "对手难度", "走棋说明"}, (d, which) -> {
                    if (which <= 2) {
                        final boolean nextTwo = which == 1, nextWatch = which == 2;
                        if (nextTwo == twoPlayers && nextWatch == watching) return;
                        if (history.isEmpty()) { twoPlayers = nextTwo; watching = nextWatch; restart(); }
                        else new AlertDialog.Builder(this).setTitle("切换模式并重开？")
                                .setPositiveButton("切换", (dialog, w) -> { twoPlayers = nextTwo; watching = nextWatch; restart(); })
                                .setNegativeButton("取消", null).show();
                    } else if (which == 3) {
                        new AlertDialog.Builder(this).setTitle("手机对手难度")
                                .setSingleChoiceItems(new String[]{"轻松", "普通"}, difficulty, (dialog, n) -> {
                                    cancelSearch(); difficulty = n; save(); dialog.dismiss(); refresh(); maybePhoneMove();
                                }).setNegativeButton("取消", null).show();
                    } else new AlertDialog.Builder(this).setTitle("走棋说明")
                            .setMessage("红方先走。点一下己方棋子，再点标记的落点。\n\n圆点：可以走。圆环：可以吃。\n\n单人时，你执红，手机自动走黑棋。双人时，两人轮流操作这部手机。\n\n观战时，红黑双方由本地 AI 自动对弈。可暂停、逐步观看、调整间隔；暂停后可以提示、撤回一着。退出后会暂停保存，回来点继续即可。内置 AI 是休闲练习对手，尚非大师棋力。\n\n悔棋可以回到你上一次走棋前。提示只画建议箭头，不会替你落子。\n\n将死、困毙均判负。休闲版不自动裁判复杂长将长捉，可自行重开。")
                            .setPositiveButton("知道了", null).show();
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
            JSONObject state = new JSONObject(); state.put("version", 1); state.put("board", encodeBoard(board));
            state.put("turn", turn); state.put("two", twoPlayers); state.put("level", difficulty);
            state.put("watch", watching); state.put("watchDelay", watchDelay);
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
            if (state.getInt("version") != 1) return;
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
            twoPlayers = state.optBoolean("two", false); difficulty = state.optInt("level", 1) == 0 ? 0 : 1;
            watching = state.optBoolean("watch", false);
            if (watching) twoPlayers = false;
            watchPaused = true;
            int delay = state.optInt("watchDelay", 2000);
            watchDelay = delay == 1000 || delay == 4000 ? delay : 2000;
            lastFrom = state.optInt("from", -1); lastTo = state.optInt("to", -1);
            note = watching ? "已恢复上次的观战，点击继续或下一步。" : "已恢复上次的棋局。";
        } catch (Exception ignored) { board = Chess.initial(); turn = Chess.RED; history.clear(); }
    }

    @Override protected void onResume() { super.onResume(); active = true; refresh(); maybePhoneMove(); }
    @Override protected void onPause() {
        active = false; cancelSearch();
        if (watching) watchPaused = true;
        note = watching ? "观战已保存并暂停，点击继续或下一步。" : "棋局已保存，可以继续下棋。";
        save(); super.onPause();
    }
    @Override protected void onDestroy() { cancelSearch(); worker.shutdownNow(); super.onDestroy(); }

    private final class BoardView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float cell, ox, oy;
        BoardView() { super(MainActivity.this); setLayerType(View.LAYER_TYPE_SOFTWARE, null); setFocusable(true); }
        private float px(int at) { return ox + (at % 9) * cell; }
        private float py(int at) { return oy + (at / 9) * cell; }
        private void ink(int color, float width, Paint.Style style) {
            paint.setColor(color); paint.setStrokeWidth(width); paint.setStyle(style); paint.clearShadowLayer();
        }
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            // 包含边缘棋子的完整半径，避免左右两路棋子被视图裁掉。
            cell = Math.min((getWidth() - dp(12)) / 8.84f, (getHeight() - dp(12)) / 9.84f);
            if (cell <= 0) return;
            ox = (getWidth() - 8 * cell) / 2; oy = (getHeight() - 9 * cell) / 2;
            float radius = cell * .42f;
            ink(WOOD, 1, Paint.Style.FILL);
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
                float x = px(at), y = py(at);
                ink(0xfff8e8c9, 1, Paint.Style.FILL);
                paint.setShadowLayer(dp(2), 0, dp(1), 0x55492e18);
                c.drawCircle(x, y, radius, paint);
                ink(board[at] > 0 ? RED_INK : INK, dp(1.5f), Paint.Style.STROKE);
                c.drawCircle(x, y, radius - dp(1), paint);
                c.drawCircle(x, y, radius - dp(4), paint);
                ink(board[at] > 0 ? RED_INK : INK, 1, Paint.Style.FILL);
                paint.setTypeface(Typeface.create("serif", Typeface.BOLD)); paint.setTextSize(cell * .57f);
                c.drawText(Chess.name(board[at]), x, y - (paint.ascent() + paint.descent()) / 2, paint);
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
