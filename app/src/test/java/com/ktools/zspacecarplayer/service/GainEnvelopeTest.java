package com.ktools.zspacecarplayer.service;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class GainEnvelopeTest {

    private static final class FakeScheduler implements GainEnvelope.Scheduler {
        private final List<Task> tasks = new ArrayList<Task>();

        @Override
        public GainEnvelope.Cancellable postDelayed(Runnable runnable, long delayMs) {
            final Task task = new Task(runnable);
            tasks.add(task);
            return new GainEnvelope.Cancellable() {
                @Override
                public void cancel() {
                    task.cancelled = true;
                }
            };
        }

        void runUntilIdle() {
            while (!tasks.isEmpty()) {
                Task task = tasks.remove(0);
                if (!task.cancelled) {
                    task.runnable.run();
                }
            }
        }
    }

    private static final class Task {
        final Runnable runnable;
        boolean cancelled;

        Task(Runnable runnable) {
            this.runnable = runnable;
        }
    }

    @Test
    public void fadeUsesStepsAndReachesRequestedGain() {
        final List<Float> gains = new ArrayList<Float>();
        FakeScheduler scheduler = new FakeScheduler();
        GainEnvelope envelope = new GainEnvelope(new GainEnvelope.VolumeTarget() {
            @Override
            public void setVolume(float gain) {
                gains.add(gain);
            }
        }, scheduler);

        envelope.fadeTo(0.0f, 80L, null);
        scheduler.runUntilIdle();

        Assert.assertTrue(gains.size() >= 4);
        Assert.assertEquals(0.0f, gains.get(gains.size() - 1), 0.0001f);
    }

    @Test
    public void newerFadeCancelsPreviousEnvelopeAndItsCompletion() {
        final List<Float> gains = new ArrayList<Float>();
        final int[] oldCompletionCount = new int[] {0};
        FakeScheduler scheduler = new FakeScheduler();
        GainEnvelope envelope = new GainEnvelope(new GainEnvelope.VolumeTarget() {
            @Override
            public void setVolume(float gain) {
                gains.add(gain);
            }
        }, scheduler);

        envelope.fadeTo(0.0f, 80L, new Runnable() {
            @Override
            public void run() {
                oldCompletionCount[0]++;
            }
        });
        envelope.fadeTo(0.2f, 80L, null);
        scheduler.runUntilIdle();

        Assert.assertEquals(0, oldCompletionCount[0]);
        Assert.assertEquals(0.2f, envelope.getCurrentGain(), 0.0001f);
        Assert.assertEquals(0.2f, gains.get(gains.size() - 1), 0.0001f);
    }

    @Test
    public void releaseHardMuteWritesZeroWhileCancelAloneDoesNot() {
        final List<Float> gains = new ArrayList<Float>();
        GainEnvelope envelope = new GainEnvelope(new GainEnvelope.VolumeTarget() {
            @Override
            public void setVolume(float gain) {
                gains.add(gain);
            }
        }, new FakeScheduler());

        envelope.setImmediate(0.7f);
        gains.clear();
        envelope.cancel();
        Assert.assertTrue(gains.isEmpty());
        Assert.assertEquals(0.7f, envelope.getCurrentGain(), 0.0001f);

        envelope.hardMute();
        Assert.assertEquals(1, gains.size());
        Assert.assertEquals(0.0f, gains.get(0), 0.0001f);
        Assert.assertEquals(0.0f, envelope.getCurrentGain(), 0.0001f);
    }
}
