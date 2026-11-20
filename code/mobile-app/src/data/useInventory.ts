import { useCallback, useEffect, useRef, useState } from 'react';
import { Snapshot, StockRepository } from './repository';
export function useInventory(repository: StockRepository) {
  const [snapshot, setSnapshot] = useState<Snapshot | null>(null),
    [error, setError] = useState<string | null>(null),
    [loading, setLoading] = useState(false),
    [synced, setSynced] = useState<Date | null>(null);
  const request = useRef(0);
  const refresh = useCallback(async () => {
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
  }, [repository]);
  const invalidate = useCallback(() => {
    request.current++;
  }, []);
  useEffect(() => {
    setSnapshot(null);
    setSynced(null);
    setError(null);
    refresh();
    const timer =
      repository.mode === 'api' ? setInterval(refresh, 5000) : undefined;
    return () => {
      if (timer) clearInterval(timer);
      invalidate();
    };
  }, [repository, refresh, invalidate]);
  return { snapshot, error, loading, synced, refresh };
}
