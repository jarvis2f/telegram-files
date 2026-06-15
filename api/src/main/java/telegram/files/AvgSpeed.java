package telegram.files;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.stream.Collectors;

public class AvgSpeed {
    private final int interval;

    // Concurrent: update() runs on TDLib's callback thread while getSpeedStats() runs on a Vert.x
    // timer thread — a plain TreeMap threw ConcurrentModificationException when read while modified.
    private final ConcurrentNavigableMap<Long, SpeedPoint> speedPoints;

    private final int smoothingWindowSize;

    /**
     * @param speed Speed since last point
     */
    record SpeedPoint(long downloadedSize, long speed, long timestamp) {
    }

    public AvgSpeed() {
        this(5 * 60); // Default interval is 5 minutes
    }

    public AvgSpeed(int interval) {
        this(interval, 6);
    }

    public AvgSpeed(int interval, int smoothingWindowSize) {
        this.interval = interval;
        this.speedPoints = new ConcurrentSkipListMap<>();
        this.smoothingWindowSize = smoothingWindowSize;
    }

    /**
     * Update download progress
     */
    public void update(long downloadedSize, long timestamp) {
        if (downloadedSize <= 0) {
            removeOldPoints(timestamp);
            return;
        }
        // Calculate speed since last point
        long speed = calculateInstantSpeed(downloadedSize, timestamp);

        // Apply smoothing if we have enough points
        if (speedPoints.size() >= smoothingWindowSize) {
            speed = smoothSpeed(speed);
        }

        // Add new speed point
        speedPoints.put(timestamp, new SpeedPoint(downloadedSize, speed, timestamp));

        removeOldPoints(timestamp);
    }

    private void removeOldPoints(long timestamp) {
        long cutoffTime = timestamp - interval * 1000L; // Convert interval to milliseconds
        speedPoints.headMap(cutoffTime).clear();
    }

    private long calculateInstantSpeed(long currentSize, long currentTime) {
        if (speedPoints.isEmpty()) {
            return 0;
        }

        // Find the earliest point within our smoothing window
        int pointsToConsider = Math.min(smoothingWindowSize, speedPoints.size());
        List<SpeedPoint> recentPoints = speedPoints.values().stream()
                .skip(speedPoints.size() - pointsToConsider)
                .toList();

        SpeedPoint earliestPoint = recentPoints.getFirst();

        long timeDiff = currentTime - earliestPoint.timestamp;
        if (timeDiff <= 0) {
            return 0;
        }

        long bytesDiff = currentSize - earliestPoint.downloadedSize;
        if (bytesDiff < 0) {
            // Handle download restart
            bytesDiff = currentSize;
        }

        return (bytesDiff * 1000L) / timeDiff; // Speed in bytes per second
    }

    private long smoothSpeed(long currentSpeed) {
        if (speedPoints.isEmpty()) {
            return currentSpeed;
        }

        List<Long> recentSpeeds = speedPoints.values().stream()
                .skip(Math.max(0, speedPoints.size() - smoothingWindowSize))
                .map(point -> point.speed)
                .collect(Collectors.toList());
        recentSpeeds.add(currentSpeed);

        if (recentSpeeds.size() < 2) {
            return currentSpeed;
        }

        double mean = recentSpeeds.stream()
                .mapToLong(Long::longValue)
                .average()
                .orElse(currentSpeed);

        double standardDeviation = Math.sqrt(
                recentSpeeds.stream()
                        .mapToDouble(speed -> {
                            double diff = speed - mean;
                            return diff * diff;
                        })
                        .average()
                        .orElse(0.0)
        );

        double upperThreshold = mean + (3 * standardDeviation);
        double lowerThreshold = mean - (3 * standardDeviation);

        List<Long> filteredSpeeds = recentSpeeds.stream()
                .filter(speed -> speed >= lowerThreshold && speed <= upperThreshold)
                .toList();

        if (filteredSpeeds.isEmpty()) {
            return currentSpeed;
        }

        double totalWeight = 0;
        double weightedSum = 0;
        int size = filteredSpeeds.size();

        for (int i = 0; i < size; i++) {
            double weight = (i + 1.0) / size;
            weightedSum += filteredSpeeds.get(i) * weight;
            totalWeight += weight;
        }

        return (long) (weightedSum / totalWeight);
    }

    /**
     * Get average speed in bytes per second for last interval
     */
    public long getSpeed() {
        if (speedPoints.size() < 2) {
            return 0;
        }

        Map.Entry<Long, SpeedPoint> firstEntry = speedPoints.firstEntry();
        Map.Entry<Long, SpeedPoint> lastEntry = speedPoints.lastEntry();

        long timeDiff = lastEntry.getKey() - firstEntry.getKey();
        if (timeDiff <= 0) {
            return 0;
        }

        long bytesDownloaded = lastEntry.getValue().downloadedSize - firstEntry.getValue().downloadedSize;
        if (bytesDownloaded < 0) {
            bytesDownloaded = lastEntry.getValue().downloadedSize;
        }

        return (bytesDownloaded * 1000L) / timeDiff;
    }

    /**
     * Get median speed from all recorded points
     */
    public long getMedianSpeed() {
        if (speedPoints.size() < 2) {
            return 0;
        }

        List<Long> speeds = new ArrayList<>(speedPoints.values()).stream()
                .map(point -> point.speed)
                .filter(speed -> speed > 0)
                .sorted()
                .toList();

        if (speeds.isEmpty()) {
            return 0;
        }

        return speeds.get(speeds.size() / 2);
    }

    /**
     * Get maximum recorded speed
     */
    public long getMaxSpeed() {
        return speedPoints.values().stream()
                .map(point -> point.speed)
                .max(Long::compare)
                .orElse(0L);
    }

    /**
     * Get minimum recorded speed
     */
    public long getMinSpeed() {
        return speedPoints.values().stream()
                .map(point -> point.speed)
                .filter(speed -> speed > 0)
                .min(Long::compare)
                .orElse(0L);
    }

    /**
     * Get speed statistics summary
     */
    public SpeedStats getSpeedStats() {
        return new SpeedStats(
                interval,
                getSpeed(),
                getMedianSpeed(),
                getMaxSpeed(),
                getMinSpeed()
        );
    }

    public record SpeedStats(int interval, long avgSpeed, long medianSpeed, long maxSpeed, long minSpeed) {
    }
}
