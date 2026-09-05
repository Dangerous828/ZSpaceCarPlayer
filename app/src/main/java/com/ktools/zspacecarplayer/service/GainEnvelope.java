package com.ktools.zspacecarplayer.service;

import android.os.Handler;

/** Main-thread stepped gain envelope. A newer envelope always cancels the previous one. */
public final class GainEnvelope {

    static final long STEP_MS = 16L;

    public interface VolumeTarget {
        void setVolume(float gain);
    }

    interface Cancellable {
        void cancel();
    }

    interface Scheduler {
        Cancellable postDelayed(Runnable runnable, long delayMs);
    }

    private static final class HandlerScheduler implements Scheduler {
        private final Handler handler;

        HandlerScheduler(Handler handler) {
            this.handler = handler;
        }

        @Override
        public Cancellable postDelayed(final Runnable runnable, long delayMs) {
            handler.postDelayed(runnable, delayMs);
            return new Cancellable() {
                @Override
                public void cancel() {
                    handler.removeCallbacks(runnable);
                }
            };
        }
    }

    private final VolumeTarget target;
    private final Scheduler scheduler;
    private float currentGain = 1.0f;
    private long envelopeId;
    private Cancellable pendingStep;

    public GainEnvelope(VolumeTarget target, Handler handler) {
        this(target, new HandlerScheduler(handler));
    }

    GainEnvelope(VolumeTarget target, Scheduler scheduler) {
        if (target == null || scheduler == null) {
            throw new IllegalArgumentException("target and scheduler are required");
        }
        this.target = target;
        this.scheduler = scheduler;
    }

    public synchronized float getCurrentGain() {
        return currentGain;
    }

    public synchronized void setImmediate(float gain) {
        cancelLocked();
        applyGain(clamp(gain));
    }

    public synchronized void cancel() {
        cancelLocked();
    }

    /** Cancels asynchronous envelopes and synchronously silences the output target. */
    public synchronized void hardMute() {
        cancelLocked();
        applyGain(0.0f);
    }

    public synchronized void fadeTo(float requestedGain, long durationMs, Runnable completion) {
        cancelLocked();
        final long activeEnvelope = envelopeId;
        final float startGain = currentGain;
        final float endGain = clamp(requestedGain);
        if (durationMs <= 0L || startGain == endGain) {
            applyGain(endGain);
            if (completion != null) {
                completion.run();
            }
            return;
        }

        final int stepCount = Math.max(1, (int) ((durationMs + STEP_MS - 1L) / STEP_MS));
        final long stepDelayMs = Math.max(1L, durationMs / stepCount);
        scheduleStep(activeEnvelope, startGain, endGain, stepCount, 1, stepDelayMs, completion);
    }

    private synchronized void scheduleStep(final long activeEnvelope,
                                           final float startGain,
                                           final float endGain,
                                           final int stepCount,
                                           final int step,
                                           final long stepDelayMs,
                                           final Runnable completion) {
        pendingStep = scheduler.postDelayed(new Runnable() {
            @Override
            public void run() {
                synchronized (GainEnvelope.this) {
                    if (activeEnvelope != envelopeId) {
                        return;
                    }
                    float fraction = (float) step / (float) stepCount;
                    applyGain(startGain + ((endGain - startGain) * fraction));
                    if (step < stepCount) {
                        scheduleStep(activeEnvelope, startGain, endGain, stepCount,
                                step + 1, stepDelayMs, completion);
                    } else {
                        pendingStep = null;
                        if (completion != null) {
                            completion.run();
                        }
                    }
                }
            }
        }, stepDelayMs);
    }

    private void cancelLocked() {
        envelopeId++;
        if (pendingStep != null) {
            pendingStep.cancel();
            pendingStep = null;
        }
    }

    private void applyGain(float gain) {
        currentGain = gain;
        target.setVolume(gain);
    }

    private static float clamp(float gain) {
        if (gain < 0.0f) return 0.0f;
        if (gain > 1.0f) return 1.0f;
        return gain;
    }
}
