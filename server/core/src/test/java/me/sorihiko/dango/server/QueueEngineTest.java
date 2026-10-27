package me.sorihiko.dango.server;

import me.sorihiko.dango.protocol.MusicProtocol.Track;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class QueueEngineTest {
    private final AtomicLong clock = new AtomicLong();
    private QueueEngine engine(int limit, int cooldown) { return new QueueEngine(clock::get, limit, 100, cooldown); }
    private Track song(long id) { return new Track(id, "Synthetic track " + id, List.of("Test artist"), 180000, ""); }
    @Test void requestsRequireSubscription() {
        QueueEngine e = engine(3, 0);
        assertThrows(QueueEngine.RuleException.class, () -> e.add("a", "A", song(1)));
        e.subscribe("a"); assertNotNull(e.add("a", "A", song(1)));
    }
    @Test void fifoAndCurrentSongDoesNotConsumePendingLimit() {
        QueueEngine e = engine(3, 0); e.subscribe("a");
        e.add("a", "A", song(1)); assertEquals(1, e.selectNext().track.id); e.ready();
        e.add("a", "A", song(2)); e.add("a", "A", song(3)); e.add("a", "A", song(4));
        assertThrows(QueueEngine.RuleException.class, () -> e.add("a", "A", song(5)));
        e.skip(); assertEquals(2, e.selectNext().track.id);
    }
    @Test void duplicateTracksAndCooldownAreChecked() {
        QueueEngine e = engine(3, 30); e.subscribe("a"); e.subscribe("b");
        e.add("a", "A", song(1)); e.selectNext(); e.ready();
        assertThrows(QueueEngine.RuleException.class, () -> e.add("a", "A", song(2)));
        assertThrows(QueueEngine.RuleException.class, () -> e.add("b", "B", song(1)));
        clock.set(30_000_000_000L); assertNotNull(e.add("a", "A", song(2)));
    }
    @Test void withdrawActuallyRemovesOnlyOwnedPendingSong() {
        QueueEngine e = engine(3, 0); e.subscribe("a"); e.subscribe("b");
        var entry = e.add("a", "A", song(1));
        assertThrows(QueueEngine.RuleException.class, () -> e.remove("b", entry.entryId, false));
        e.remove("a", entry.entryId, false); assertTrue(e.queue().isEmpty());
    }
    @Test void skipVoteRequiresFifteenPercentAndVotesDoNotDuplicate() {
        QueueEngine e = engine(3, 0); e.subscribe("a"); e.subscribe("b");
        for (int i = 0; i < 5; i++) e.subscribe("listener" + i);
        e.add("a", "A", song(1)); e.selectNext(); e.ready();
        assertFalse(e.vote("a")); assertFalse(e.vote("a")); assertEquals(1, e.votes());
        assertTrue(e.vote("b")); e.skip(); assertEquals(0, e.votes());
    }
    @Test void voteThresholdRoundsUpAndSnapshotMatchesTheDecision() {
        int[] counts = {1, 6, 7, 13, 14, 20, 21, 100};
        int[] required = {1, 1, 2, 2, 3, 3, 4, 15};
        for (int c = 0; c < counts.length; c++) {
            QueueEngine e = engine(3, 0);
            assertEquals(1, e.votesRequired());
            for (int i = 0; i < counts[c]; i++) e.subscribe("listener" + i);
            e.add("listener0", "Fixture listener", song(1)); e.selectNext(); e.ready();
            assertEquals(required[c], e.state().get("votesRequired").getAsInt(), "Listeners: " + counts[c]);
            for (int i = 0; i < required[c]; i++) assertEquals(i == required[c] - 1, e.vote("listener" + i));
        }
    }
    @Test void nonVoterLeavingCanReachTheLowerThreshold() {
        QueueEngine e = engine(3, 0);
        for (int i = 0; i < 7; i++) e.subscribe("listener" + i);
        e.add("listener0", "Fixture listener", song(1)); e.selectNext(); e.ready();
        assertFalse(e.vote("listener0"));
        assertTrue(e.unsubscribe("listener6"));
        assertEquals(1, e.votesRequired()); assertEquals(1, e.votes());
    }
    @Test void newSubscriberRaisesThresholdAndLeavingVoterLosesTheirVote() {
        QueueEngine e = engine(3, 0);
        for (int i = 0; i < 13; i++) e.subscribe("listener" + i);
        e.add("listener0", "Fixture listener", song(1)); e.selectNext(); e.ready();
        assertFalse(e.vote("listener0"));
        e.subscribe("listener13"); assertEquals(3, e.votesRequired());
        assertFalse(e.vote("listener1"));
        assertFalse(e.unsubscribe("listener0")); assertEquals(1, e.votes());
        e.subscribe("listener0"); assertEquals(3, e.votesRequired());
        assertFalse(e.vote("listener0")); assertTrue(e.vote("listener2"));
    }
    @Test void leavingRemovesVoteAndRetainsQueuedRequests() {
        QueueEngine e = engine(3, 0); e.subscribe("a"); e.subscribe("b");
        for (int i = 0; i < 5; i++) e.subscribe("listener" + i);
        e.add("a", "A", song(1)); e.selectNext(); e.ready(); e.add("a", "A", song(2)); e.vote("a");
        e.unsubscribe("a"); assertEquals(0, e.votes()); assertEquals(1, e.queue().size());
    }
    @Test void withdrawalRequiresSubscriptionButAdminRemovalDoesNot() {
        QueueEngine e = engine(3, 0); e.subscribe("a");
        var entry = e.add("a", "A", song(1)); e.unsubscribe("a");
        assertThrows(QueueEngine.RuleException.class, () -> e.remove("a", entry.entryId, false));
        e.remove("console", entry.entryId, true); assertTrue(e.queue().isEmpty());
    }
    @Test void noListenersDoesNotPauseClockAndEmptyQueueStaysEmpty() {
        QueueEngine e = engine(3, 0); e.subscribe("a"); e.add("a", "A", song(1)); e.selectNext(); e.ready();
        e.unsubscribe("a"); clock.set(180_000_000_000L); assertTrue(e.ended());
        e.skip(); assertNull(e.selectNext());
    }
    @Test void pauseFreezesClockAndResumeContinues() {
        QueueEngine e = engine(3, 0); e.subscribe("a"); e.add("a", "A", song(1)); e.selectNext(); e.ready();
        clock.set(5_000_000_000L); e.pause(true); clock.set(80_000_000_000L); assertEquals(5000, e.position());
        e.pause(false); clock.set(81_000_000_000L); assertEquals(6000, e.position());
    }
    @Test void restoredProgressExcludesDowntimeAndUsesNewPlaybackInstance() {
        QueueEngine e = engine(3, 0); e.subscribe("a"); e.add("a", "A", song(1)); e.selectNext(); e.ready();
        clock.set(15_000_000_000L); var saved = e.save(); String oldInstance = e.instance();
        clock.set(300_000_000_000L); QueueEngine restored = engine(3, 0); restored.restore(saved);
        assertEquals(15000, restored.position()); assertFalse(restored.isReady()); assertNotEquals(oldInstance, restored.instance());
        restored.ready(); clock.set(301_000_000_000L); assertEquals(16000, restored.position()); assertEquals(0, restored.listeners());
    }
}
