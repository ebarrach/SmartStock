package smartstock.state;

import java.util.*;
import java.util.concurrent.*;

/** Bounded, replayable snapshots. Slow clients get a resynchronization snapshot. */
public final class EventHub {
    public static final class Event {
        public final long sequence;
        public final String json;
        public Event(long sequence, String json) { this.sequence = sequence; this.json = json; }
    }
    private final Deque<Event> history = new ArrayDeque<>();
    private final Set<BlockingQueue<Event>> clients = new HashSet<>();
    public synchronized void publish(Event event) {
        history.addLast(event);
        while (history.size() > 128) history.removeFirst();
        for (BlockingQueue<Event> queue : clients) {
            if (!queue.offer(event)) { queue.clear(); queue.offer(event); }
        }
    }
    public synchronized BlockingQueue<Event> subscribe(long after) {
        BlockingQueue<Event> queue = new ArrayBlockingQueue<>(256);
        if (!history.isEmpty()) {
            if (after < 0 || after < history.getFirst().sequence - 1 || after > history.getLast().sequence) queue.offer(history.getLast());
            else for (Event e : history) if (e.sequence > after) queue.offer(e);
        }
        clients.add(queue);
        return queue;
    }
    public synchronized void unsubscribe(BlockingQueue<Event> queue) { clients.remove(queue); }
}
