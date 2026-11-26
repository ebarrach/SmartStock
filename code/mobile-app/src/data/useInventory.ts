import { useCallback, useEffect, useRef, useState } from 'react';
import { Snapshot, StockRepository } from './repository';
export function useInventory(repository: StockRepository, enabled = true) {
  const [snapshot, setSnapshot] = useState<Snapshot | null>(null),
    [error, setError] = useState<string | null>(null),
    [loading, setLoading] = useState(false),
    [synced, setSynced] = useState<Date | null>(null);
  const request = useRef(0);
  const refresh = useCallback(async () => {
    if (!enabled) return;
    const id = ++request.current;
    setLoading(true);
    try {
      const value = await repository.load();
      if (id === request.current) {
        setSnapshot(value);
        setError(null);
        setSynced(new Date());
      }
    } catch (e) {
      if (id === request.current)
        setError(e instanceof Error ? e.message : 'Service indisponible');
    } finally {
      if (id === request.current) setLoading(false);
    }
  }, [repository, enabled]);
  const invalidate = useCallback(() => {
    request.current++;
  }, []);
  useEffect(() => {
    setSnapshot(null);
    setSynced(null);
    setError(null);
    if (!enabled) return;
    let stopped = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const poll = async () => {
      await refresh();
      if (!stopped && repository.mode === 'api') timer = setTimeout(poll, 5000);
    };
    poll();
    return () => {
      stopped = true;
      if (timer) clearTimeout(timer);
      invalidate();
    };
  }, [repository, refresh, invalidate, enabled]);
  return { snapshot, error, loading, synced, refresh };
}
