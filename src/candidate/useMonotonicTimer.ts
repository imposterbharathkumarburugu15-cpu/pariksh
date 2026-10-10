import { useState, useEffect, useRef } from 'react';
import { ExamStorageBridge } from './nativeBridge';

export interface MonotonicTimerOptions {
  officialDeadlineIso: string;
  officialStartTimeIso: string;
  durationSeconds: number;
  onTimeout?: () => void;
  onDriftDetected?: (driftMs: number) => void;
}

export interface MonotonicTimerState {
  remainingSeconds: number;
  formattedRemaining: string;
  isExpired: boolean;
  isDriftWarning: boolean;
  rebootDetected: boolean;
}

export function formatTimeRemaining(seconds: number): string {
  if (seconds <= 0) return '00:00:00';
  const hrs = Math.floor(seconds / 3600);
  const mins = Math.floor((seconds % 3600) / 60);
  const secs = Math.floor(seconds % 60);
  const pad = (n: number) => n.toString().padStart(2, '0');
  return `${pad(hrs)}:${pad(mins)}:${pad(secs)}`;
}

export function useMonotonicTimer({
  officialDeadlineIso,
  officialStartTimeIso,
  durationSeconds,
  onTimeout,
  onDriftDetected
}: MonotonicTimerOptions): MonotonicTimerState {
  const [remainingSeconds, setRemainingSeconds] = useState<number>(() => {
    const deadlineMs = new Date(officialDeadlineIso).getTime();
    const diff = Math.max(0, Math.floor((deadlineMs - Date.now()) / 1000));
    return Math.min(diff, durationSeconds);
  });
  const [isExpired, setIsExpired] = useState<boolean>(false);
  const [isDriftWarning, setIsDriftWarning] = useState<boolean>(false);
  const [rebootDetected, setRebootDetected] = useState<boolean>(false);

  // Time anchors: (wall clock sample, monotonic elapsed sample, deadline wall ms)
  const anchorRef = useRef<{
    initialElapsedMs: number;
    initialWallMs: number;
    deadlineWallMs: number;
  } | null>(null);

  const timeoutTriggeredRef = useRef<boolean>(false);

  useEffect(() => {
    let isCancelled = false;

    async function initAnchor() {
      const mono = await ExamStorageBridge.getMonotonicTime();
      if (isCancelled) return;

      const deadlineWallMs = new Date(officialDeadlineIso).getTime();
      anchorRef.current = {
        initialElapsedMs: mono.elapsedRealtimeMs,
        initialWallMs: mono.currentWallMs,
        deadlineWallMs
      };
    }

    initAnchor();

    const interval = setInterval(async () => {
      if (!anchorRef.current) return;

      const currentMono = await ExamStorageBridge.getMonotonicTime();
      if (isCancelled) return;

      const { initialElapsedMs, initialWallMs, deadlineWallMs } = anchorRef.current;

      // 1. Monotonic elapsed progress calculation
      const elapsedSinceStart = currentMono.elapsedRealtimeMs - initialElapsedMs;

      // 2. Check for device reboot: monotonic elapsedRealtime reset
      if (elapsedSinceStart < -5000) {
        setRebootDetected(true);
      }

      // 3. Wall clock comparison for clock manipulation detection
      const wallElapsedSinceStart = currentMono.currentWallMs - initialWallMs;
      const drift = Math.abs(elapsedSinceStart - wallElapsedSinceStart);
      if (drift > 15000) {
        // Clock drift greater than 15 seconds detected!
        setIsDriftWarning(true);
        onDriftDetected?.(drift);
      }

      // 4. Authoritative Remaining calculation using monotonic advancement
      // Total allowed duration in ms from anchor:
      const totalAllocatedMs = Math.max(0, deadlineWallMs - initialWallMs);
      const remainingMs = Math.max(0, totalAllocatedMs - elapsedSinceStart);
      const remainingSec = Math.floor(remainingMs / 1000);

      setRemainingSeconds(remainingSec);

      if (remainingSec <= 0) {
        setIsExpired(true);
        if (!timeoutTriggeredRef.current) {
          timeoutTriggeredRef.current = true;
          onTimeout?.();
        }
      }
    }, 1000);

    return () => {
      isCancelled = true;
      clearInterval(interval);
    };
  }, [officialDeadlineIso, officialStartTimeIso, durationSeconds, onTimeout, onDriftDetected]);

  return {
    remainingSeconds,
    formattedRemaining: formatTimeRemaining(remainingSeconds),
    isExpired,
    isDriftWarning,
    rebootDetected
  };
}
