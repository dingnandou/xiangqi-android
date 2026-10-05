package com.yijin.xiangqi.light;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.util.Log;

/** Short, bundled sounds; never speaks on refresh or resumes a stopped sound. */
final class GameSounds {
    private final SoundPool pool;
    private final int move, capture, check;
    private final boolean[] ready = new boolean[4];
    private boolean enabled = true, active, released;
    private int tapStream, voiceStream;

    GameSounds(Context context) {
        pool = new SoundPool.Builder().setMaxStreams(2)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .build();
        pool.setOnLoadCompleteListener((soundPool, id, status) -> {
            if (!released && id > 0 && id < ready.length) ready[id] = status == 0;
            if (status != 0) Log.e("GameSounds", "Sound load failed: " + id + ", " + status);
        });
        move = pool.load(context, R.raw.move, 1);
        capture = pool.load(context, R.raw.capture, 1);
        check = pool.load(context, R.raw.check, 1);
    }

    void setEnabled(boolean value) { enabled = value; if (!value) stop(); }
    void setActive(boolean value) { active = value; if (!value) stop(); }

    void onMove(boolean captured, boolean checking) {
        if (released || !enabled || !active) return;
        int sound = checking ? check : captured ? capture : move;
        if (sound <= 0 || sound >= ready.length || !ready[sound]) return;
        // A quick AI reply must not cut off the check announcement.
        if (tapStream != 0) pool.stop(tapStream);
        tapStream = 0;
        if (checking) {
            if (voiceStream != 0) pool.stop(voiceStream);
            voiceStream = pool.play(sound, .8f, .8f, 2, 0, 1f);
        } else tapStream = pool.play(sound, .55f, .55f, 1, 0, 1f);
    }

    void stop() {
        if (!released) {
            if (tapStream != 0) pool.stop(tapStream);
            if (voiceStream != 0) pool.stop(voiceStream);
        }
        tapStream = voiceStream = 0;
    }

    void release() {
        if (released) return;
        stop(); released = true;
        pool.setOnLoadCompleteListener(null);
        pool.release();
    }
}
