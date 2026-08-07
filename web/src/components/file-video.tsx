import { type TelegramFile } from "@/lib/types";
import React, { useCallback, useEffect, useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import { AnimatePresence, motion } from "framer-motion";
import {
  AlertTriangle,
  Loader2,
  Maximize,
  Minimize,
  Pause,
  Play,
  RotateCcw,
  RotateCw,
  VideoOff,
  Volume2,
  VolumeX,
} from "lucide-react";
import { getApiUrl } from "@/lib/api";
import { Popover, PopoverContent, PopoverTrigger } from "./ui/popover";
import { cn } from "@/lib/utils";
import useIsMobile from "@/hooks/use-is-mobile";
import * as SliderPrimitive from "@radix-ui/react-slider";
import prettyBytes from "pretty-bytes";
import { MobilePreviewTagOverlay } from "@/components/mobile/mobile-preview-tag-overlay";

// 检测浏览器是否支持特定视频格式
const checkVideoSupport = (mimeType: string): "probably" | "maybe" | "" => {
  const video = document.createElement("video");
  return video.canPlayType(mimeType);
};

// Browser compatibility limited formats
const BROWSER_LIMITED_FORMATS: Record<string, string> = {
  "video/quicktime": "QuickTime format is recommended for Safari browser",
  "video/mp2t":
    "MPEG-TS format is not supported by browsers, please download and use VLC player",
  "video/x-matroska": "MKV format has limited support in some browsers",
};

// 获取 MIME 类型
const getMimeType = (file: TelegramFile): string => {
  // 优先使用顶层的 mimeType
  if (file.mimeType) {
    return file.mimeType;
  }

  // 如果 extra 存在且包含 mimeType (即 VideoExtra 类型)
  if (file.extra && "mimeType" in file.extra) {
    return file.extra.mimeType;
  }

  // 默认返回 video/mp4
  return "video/mp4";
};

const estimateBufferedBytes = (
  video: HTMLVideoElement,
  duration: number,
  fileSize: number,
) => {
  if (!Number.isFinite(duration) || duration <= 0 || fileSize <= 0) return 0;

  let bufferedSeconds = 0;
  for (let index = 0; index < video.buffered.length; index += 1) {
    bufferedSeconds += Math.max(
      0,
      video.buffered.end(index) - video.buffered.start(index),
    );
  }

  return Math.min(
    fileSize,
    Math.round((bufferedSeconds / duration) * fileSize),
  );
};

const formatLoadingSpeed = (bytesPerSecond: number) => {
  if (!Number.isFinite(bytesPerSecond) || bytesPerSecond <= 0) return "0 B/s";

  return `${prettyBytes(bytesPerSecond)}/s`;
};

const LOAD_SPEED_WINDOW_MS = 2500;
const LOAD_SPEED_IDLE_TIMEOUT_MS = 1500;

const VideoErrorFallback = ({
  className = "",
  message = "Video loading failed!",
}) => (
  <div
    className={cn(
      "flex flex-col items-center justify-center rounded-lg border border-white/10 bg-zinc-950/90 p-6 text-center shadow-2xl ring-1 ring-white/10",
      className,
    )}
  >
    <div className="mb-3 rounded-full bg-white/10 p-3">
      <VideoOff className="h-7 w-7 text-white/70" />
    </div>
    <p className="text-sm font-medium text-white">Video unavailable</p>
    <p className="mt-1 max-w-sm text-sm text-white/60">{message}</p>
  </div>
);

const Slider = React.forwardRef<
  React.ComponentRef<typeof SliderPrimitive.Root>,
  React.ComponentPropsWithoutRef<typeof SliderPrimitive.Root> & {
    isMobile?: boolean;
  }
>(({ className, isMobile = false, ...props }, ref) => (
  <SliderPrimitive.Root
    ref={ref}
    className={cn(
      "relative flex w-full touch-none select-none items-center",
      isMobile ? "min-h-14 py-6" : "",
      className,
    )}
    {...props}
  >
    <SliderPrimitive.Track
      className={cn(
        "relative w-full grow overflow-hidden rounded-full bg-white/20",
        isMobile ? "h-3" : "h-1.5",
      )}
    >
      <SliderPrimitive.Range className="absolute h-full bg-white" />
    </SliderPrimitive.Track>
    <SliderPrimitive.Thumb
      className={cn(
        "block rounded-full border-4 border-white bg-zinc-950 shadow transition-all",
        isMobile ? "h-8 w-8 border-[5px] active:scale-110" : "h-4 w-4",
        "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-white/70",
        "disabled:pointer-events-none disabled:opacity-50",
      )}
    />
  </SliderPrimitive.Root>
));

Slider.displayName = "Slider";

const DesktopControls = ({
  isPlaying,
  currentTime,
  duration,
  volume,
  isMuted,
  isFullscreen,
  playbackRate,
  onPlayPause,
  onVolumeChange,
  onMuteToggle,
  onFullscreenToggle,
  onPlaybackRateChange,
  onSeek,
  progressBarRef,
  onProgressBarHover,
  onProgressBarLeave,
  showPreview,
  previewTime,
  previewPos,
  canvasRef,
}: {
  isPlaying: boolean;
  currentTime: number;
  duration: number;
  volume: number;
  isMuted: boolean;
  isFullscreen: boolean;
  playbackRate: number;
  onPlayPause: () => void;
  onVolumeChange: (volume: number) => void;
  onMuteToggle: () => void;
  onFullscreenToggle: () => void;
  onPlaybackRateChange: (rate: number) => void;
  onSeek: (time: number) => void;
  progressBarRef: React.RefObject<HTMLDivElement | null>;
  onProgressBarHover: (e: React.MouseEvent) => void;
  onProgressBarLeave: () => void;
  showPreview: boolean;
  previewTime: number;
  previewPos: number;
  canvasRef: React.RefObject<HTMLCanvasElement | null>;
}) => {
  const playbackRates = [0.5, 0.75, 1, 1.25, 1.5, 2];
  const formatTime = (seconds: number) => {
    const mins = Math.floor(seconds / 60);
    const secs = Math.floor(seconds % 60);
    return `${mins}:${secs.toString().padStart(2, "0")}`;
  };

  return (
    <div className="space-y-4">
      <div
        ref={progressBarRef}
        className="relative"
        onMouseMove={onProgressBarHover}
        onMouseLeave={onProgressBarLeave}
      >
        <Slider
          value={[currentTime]}
          max={duration}
          step={0.1}
          className="w-full cursor-pointer"
          onValueChange={(value) => value[0] !== undefined && onSeek(value[0])}
        />
        {showPreview && (
          <motion.div
            initial={{ opacity: 0, y: -10 }}
            animate={{ opacity: 1, y: 0 }}
            className="absolute bottom-full mb-4 overflow-hidden rounded-md border border-white/10 bg-black shadow-2xl"
            style={{ left: `${previewPos}px`, transform: "translateX(-50%)" }}
          >
            <div className="flex aspect-video w-48 items-center justify-center bg-black">
              <canvas
                ref={canvasRef}
                className="h-full w-full object-contain"
              />
            </div>
            <div className="bg-black/80 px-2 py-1 text-center text-xs font-medium text-white">
              {formatTime(previewTime)}
            </div>
          </motion.div>
        )}
      </div>

      <div className="flex items-center gap-2">
        <Button
          variant="ghost"
          size="icon"
          className="rounded-full bg-white/10 text-white hover:bg-white/20 hover:text-white [&_svg]:size-5"
          onClick={onPlayPause}
        >
          {isPlaying ? (
            <Pause className="h-6 w-6" />
          ) : (
            <Play className="h-6 w-6" />
          )}
        </Button>

        <div className="flex items-center gap-2">
          <Button
            variant="ghost"
            size="icon"
            className="rounded-full text-white hover:bg-white/20 hover:text-white [&_svg]:size-5"
            onClick={onMuteToggle}
          >
            {isMuted ? (
              <VolumeX className="h-6 w-6" />
            ) : (
              <Volume2 className="h-6 w-6" />
            )}
          </Button>
          <Slider
            value={[volume * 100]}
            max={100}
            className="w-24"
            onValueChange={(value) => onVolumeChange(value[0]! / 100)}
          />
        </div>

        <div className="rounded-full bg-white/10 px-3 py-1 text-xs font-medium text-white/90">
          {formatTime(currentTime)} / {formatTime(duration)}
        </div>

        <div className="ml-auto flex items-center gap-2">
          <Popover>
            <PopoverTrigger asChild>
              <Button
                variant="ghost"
                size="sm"
                className="rounded-full bg-white/10 text-white hover:bg-white/20 hover:text-white"
              >
                {playbackRate}x
              </Button>
            </PopoverTrigger>
            <PopoverContent className="w-18 p-0" modal={true} side="top">
              <div className="flex flex-col">
                {playbackRates.map((rate) => (
                  <Button
                    key={rate}
                    variant="ghost"
                    className={cn(
                      "justify-start rounded-none",
                      rate === playbackRate && "bg-accent",
                    )}
                    onClick={() => onPlaybackRateChange(rate)}
                  >
                    {rate}x
                  </Button>
                ))}
              </div>
            </PopoverContent>
          </Popover>

          <Button
            variant="ghost"
            size="icon"
            className="rounded-full text-white hover:bg-white/20 hover:text-white [&_svg]:size-5"
            onClick={onFullscreenToggle}
          >
            {isFullscreen ? (
              <Minimize className="h-6 w-6" />
            ) : (
              <Maximize className="h-6 w-6" />
            )}
          </Button>
        </div>
      </div>
    </div>
  );
};

const MobileControls = ({
  isPlaying,
  currentTime,
  duration,
  onPlayPause,
  onSeek,
  onSkipForward,
  onSkipBackward,
}: {
  isPlaying: boolean;
  currentTime: number;
  duration: number;
  onPlayPause: () => void;
  onSeek: (time: number) => void;
  onSkipForward: () => void;
  onSkipBackward: () => void;
}) => {
  const formatTime = (seconds: number) => {
    const mins = Math.floor(seconds / 60);
    const secs = Math.floor(seconds % 60);
    return `${mins}:${secs.toString().padStart(2, "0")}`;
  };

  return (
    <div className="pb-safe" onClick={(e) => e.stopPropagation()}>
      <div className="flex items-center justify-between px-4">
        <span className="rounded-full bg-white/10 px-2.5 py-1 text-xs font-medium text-white">
          {formatTime(currentTime)}
        </span>
        <span className="rounded-full bg-white/10 px-2.5 py-1 text-xs font-medium text-white">
          {formatTime(duration)}
        </span>
      </div>

      <div
        className="-mx-2 rounded-2xl px-2"
        onPointerDown={(e) => e.stopPropagation()}
        onTouchStart={(e) => e.stopPropagation()}
        onClick={(e) => e.stopPropagation()}
      >
        <Slider
          isMobile={true}
          value={[currentTime]}
          max={duration}
          step={0.1}
          className="w-full cursor-pointer"
          onValueChange={(value) => value[0] !== undefined && onSeek(value[0])}
        />
      </div>

      <div className="flex items-center justify-center gap-9">
        <Button
          variant="ghost"
          size="icon"
          className="h-12 w-12 rounded-full bg-white/10 text-white hover:bg-white/20 hover:text-white [&_svg]:size-5"
          onClick={onSkipBackward}
        >
          <RotateCcw className="h-8 w-8" />
        </Button>

        <Button
          variant="ghost"
          size="icon"
          className="h-14 w-14 rounded-full bg-white text-black shadow-2xl hover:bg-white/90 hover:text-black [&_svg]:size-7"
          onClick={onPlayPause}
        >
          {isPlaying ? (
            <Pause className="h-12 w-12" />
          ) : (
            <Play className="h-12 w-12" />
          )}
        </Button>

        <Button
          variant="ghost"
          size="icon"
          className="h-12 w-12 rounded-full bg-white/10 text-white hover:bg-white/20 hover:text-white [&_svg]:size-5"
          onClick={onSkipForward}
        >
          <RotateCw className="h-8 w-8" />
        </Button>
      </div>
    </div>
  );
};

const FileVideo = ({
  file,
  onTimeUpdate,
  onVolumeChange,
  onControlsVisibilityChange,
  onFileChange,
  className,
}: {
  file: TelegramFile;
  onTimeUpdate?: (time: number) => void;
  onVolumeChange?: (volume: number) => void;
  onControlsVisibilityChange?: (visible: boolean) => void;
  onFileChange?: (file: TelegramFile) => void;
  className?: string;
}) => {
  const videoRef = useRef<HTMLVideoElement>(null);
  const previewVideoRef = useRef<HTMLVideoElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const progressBarRef = useRef<HTMLDivElement>(null);
  const hideControlsTimeoutRef = useRef<number | null>(null);
  const loadSpeedSamplesRef = useRef<
    Array<{
      bytes: number;
      timestamp: number;
    }>
  >([]);
  const resetLoadSpeedTimeoutRef = useRef<number | null>(null);
  const isMobile = useIsMobile();
  const [loading, setLoading] = useState(false);
  const [isPlaying, setIsPlaying] = useState(false);
  const [currentTime, setCurrentTime] = useState(0);
  const [duration, setDuration] = useState(0);
  const [volume, setVolume] = useState(1);
  const [isMuted, setIsMuted] = useState(false);
  const [isFullscreen, setIsFullscreen] = useState(false);
  const [showControls, setShowControls] = useState(true);
  const [playbackRate, setPlaybackRate] = useState(1);
  const [showPreview, setShowPreview] = useState(false);
  const [previewTime, setPreviewTime] = useState(0);
  const [previewPos, setPreviewPos] = useState(0);
  const [isPreviewReady, setIsPreviewReady] = useState(false);
  const [error, setError] = useState(false);
  const [errorMessage, setErrorMessage] = useState("");
  const [formatWarning, setFormatWarning] = useState<string | null>(null);
  const [loadedBytesEstimate, setLoadedBytesEstimate] = useState(0);
  const [loadingSpeed, setLoadingSpeed] = useState(0);
  const [isBufferGrowing, setIsBufferGrowing] = useState(false);

  const url = `${getApiUrl()}/${file.telegramId}/file/${file.uniqueId}`;
  const mimeType = getMimeType(file);
  const loadedPercent =
    file.size > 0 ? Math.min(100, (loadedBytesEstimate / file.size) * 100) : 0;
  const showLoadingSpeed =
    file.size > 0 && duration > 0 && (loading || isBufferGrowing);

  const clearControlsHideTimeout = useCallback(() => {
    if (hideControlsTimeoutRef.current === null) return;
    window.clearTimeout(hideControlsTimeoutRef.current);
    hideControlsTimeoutRef.current = null;
  }, []);

  const scheduleControlsHide = useCallback(() => {
    clearControlsHideTimeout();

    if (isMobile || !isPlaying) return;

    hideControlsTimeoutRef.current = window.setTimeout(() => {
      setShowControls(false);
      hideControlsTimeoutRef.current = null;
    }, 2600);
  }, [clearControlsHideTimeout, isMobile, isPlaying]);

  const revealControls = useCallback(() => {
    setShowControls(true);
    scheduleControlsHide();
  }, [scheduleControlsHide]);

  const toggleControls = useCallback(() => {
    setShowControls((visible) => {
      if (visible) {
        clearControlsHideTimeout();
        return false;
      }

      return true;
    });
  }, [clearControlsHideTimeout]);

  const clearLoadSpeedResetTimeout = useCallback(() => {
    if (resetLoadSpeedTimeoutRef.current === null) return;
    window.clearTimeout(resetLoadSpeedTimeoutRef.current);
    resetLoadSpeedTimeoutRef.current = null;
  }, []);

  const updateLoadingSpeed = useCallback(() => {
    const video = videoRef.current;
    if (!video) return;

    const bufferedBytes = estimateBufferedBytes(video, duration, file.size);
    const timestamp = performance.now();
    const previous = loadSpeedSamplesRef.current.at(-1);

    setLoadedBytesEstimate(bufferedBytes);

    if (previous && bufferedBytes < previous.bytes) {
      loadSpeedSamplesRef.current = [{ bytes: bufferedBytes, timestamp }];
      clearLoadSpeedResetTimeout();
      setLoadingSpeed(0);
      setIsBufferGrowing(false);
      return;
    }

    const cutoff = timestamp - LOAD_SPEED_WINDOW_MS;
    const samples = [
      ...loadSpeedSamplesRef.current,
      { bytes: bufferedBytes, timestamp },
    ];
    const firstRecentIndex = samples.findIndex(
      (sample) => sample.timestamp >= cutoff,
    );
    const windowStartIndex = Math.max(0, firstRecentIndex - 1);
    const recentSamples = samples.slice(windowStartIndex);
    loadSpeedSamplesRef.current = recentSamples;

    if (!previous || bufferedBytes <= previous.bytes) return;

    const oldest = recentSamples[0]!;
    const elapsedSeconds = (timestamp - oldest.timestamp) / 1000;
    const loadedDelta = bufferedBytes - oldest.bytes;

    setIsBufferGrowing(true);

    if (elapsedSeconds >= 0.25 && loadedDelta > 0) {
      setLoadingSpeed(loadedDelta / elapsedSeconds);
    }

    clearLoadSpeedResetTimeout();
    resetLoadSpeedTimeoutRef.current = window.setTimeout(() => {
      setLoadingSpeed(0);
      setIsBufferGrowing(false);
      resetLoadSpeedTimeoutRef.current = null;
    }, LOAD_SPEED_IDLE_TIMEOUT_MS);
  }, [clearLoadSpeedResetTimeout, duration, file.size]);

  useEffect(() => {
    onControlsVisibilityChange?.(showControls);
  }, [onControlsVisibilityChange, showControls]);

  useEffect(() => {
    if (isPlaying && showControls) {
      scheduleControlsHide();
    } else {
      clearControlsHideTimeout();
    }
  }, [clearControlsHideTimeout, isPlaying, scheduleControlsHide, showControls]);

  useEffect(() => clearControlsHideTimeout, [clearControlsHideTimeout]);

  useEffect(
    () => () => {
      clearLoadSpeedResetTimeout();
    },
    [clearLoadSpeedResetTimeout],
  );

  useEffect(() => {
    loadSpeedSamplesRef.current = [];
    setLoadedBytesEstimate(0);
    setLoadingSpeed(0);
    setIsBufferGrowing(false);
    clearLoadSpeedResetTimeout();
  }, [clearLoadSpeedResetTimeout, file.id, url]);

  // Check format compatibility
  useEffect(() => {
    const support = checkVideoSupport(mimeType);

    if (support === "" && BROWSER_LIMITED_FORMATS[mimeType]) {
      setFormatWarning(BROWSER_LIMITED_FORMATS[mimeType]);
      console.warn(
        `Video format ${mimeType} may not be supported in current browser`,
      );
    }
  }, [mimeType]);

  useEffect(() => {
    const video = videoRef.current;
    if (!video) return;

    const handleLoadedMetadata = () => {
      setDuration(video.duration);
      setIsPreviewReady(true);
    };

    video.addEventListener("loadedmetadata", handleLoadedMetadata);

    return () => {
      video.removeEventListener("loadedmetadata", handleLoadedMetadata);
    };
  }, []);

  useEffect(() => {
    updateLoadingSpeed();
  }, [duration, updateLoadingSpeed]);

  useEffect(() => {
    const video = videoRef.current;
    if (!video) return;

    const handleWaiting = () => {
      setLoading(true);
      updateLoadingSpeed();
    };
    const handlePlaying = () => {
      setLoading(false);
      updateLoadingSpeed();
    };
    const handleCanPlay = () => {
      setLoading(false);
      updateLoadingSpeed();
    };
    const handleLoadingProgress = () => updateLoadingSpeed();

    video.addEventListener("waiting", handleWaiting);
    video.addEventListener("playing", handlePlaying);
    video.addEventListener("canplay", handleCanPlay);
    video.addEventListener("canplaythrough", handleCanPlay);
    video.addEventListener("loadeddata", handleLoadingProgress);
    video.addEventListener("loadstart", handleLoadingProgress);
    video.addEventListener("progress", handleLoadingProgress);
    video.addEventListener("suspend", handleLoadingProgress);

    return () => {
      video.removeEventListener("waiting", handleWaiting);
      video.removeEventListener("playing", handlePlaying);
      video.removeEventListener("canplay", handleCanPlay);
      video.removeEventListener("canplaythrough", handleCanPlay);
      video.removeEventListener("loadeddata", handleLoadingProgress);
      video.removeEventListener("loadstart", handleLoadingProgress);
      video.removeEventListener("progress", handleLoadingProgress);
      video.removeEventListener("suspend", handleLoadingProgress);
    };
  }, [updateLoadingSpeed]);

  const captureVideoFrame = () => {
    const previewVideo = previewVideoRef.current;
    const canvas = canvasRef.current;
    if (!previewVideo || !canvas || !isPreviewReady) return;

    const context = canvas.getContext("2d");
    if (!context) return;

    // Set canvas dimensions to match video dimensions
    canvas.width = previewVideo.videoWidth;
    canvas.height = previewVideo.videoHeight;

    // Draw the current frame
    context.drawImage(previewVideo, 0, 0, canvas.width, canvas.height);
  };

  useEffect(() => {
    if (showPreview) {
      const timeoutId = setTimeout(captureVideoFrame, 150); // Add slight delay for frame to load
      return () => clearTimeout(timeoutId);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [previewTime, showPreview]);

  const handleSkipForward = () => {
    if (videoRef.current) {
      videoRef.current.currentTime = Math.min(duration, currentTime + 15);
    }
  };

  const handleSkipBackward = () => {
    if (videoRef.current) {
      videoRef.current.currentTime = Math.max(0, currentTime - 15);
    }
  };

  const handleProgressBarHover = (e: React.MouseEvent) => {
    if (isMobile) return;

    const progressBar = progressBarRef.current;
    const previewVideo = previewVideoRef.current;
    if (!progressBar || !previewVideo || !isPreviewReady) return;

    const rect = progressBar.getBoundingClientRect();
    const percent = Math.max(
      0,
      Math.min(1, (e.clientX - rect.left) / rect.width),
    );
    const previewTimeValue = percent * duration;

    setPreviewTime(previewTimeValue);
    setPreviewPos(e.clientX - rect.left);
    setShowPreview(true);
    previewVideo.currentTime = previewTimeValue;
  };

  const togglePlay = () => {
    const video = videoRef.current;
    if (!video || !isPreviewReady) {
      return;
    }

    if (isPlaying) {
      video.pause();
      setIsPlaying(false);
    } else {
      video
        .play()
        .then(() => setIsPlaying(true))
        .catch((err: unknown) => {
          console.warn("Video play request failed", err);
          setIsPlaying(false);
        });
    }
  };

  const handleSeek = (time: number) => {
    if (videoRef.current) {
      videoRef.current.currentTime = time;
      setCurrentTime(time);
    }
  };

  const handleTimeUpdate = () => {
    if (videoRef.current) {
      setCurrentTime(videoRef.current.currentTime);
      updateLoadingSpeed();
      onTimeUpdate?.(videoRef.current.currentTime);
    }
  };

  const handleVolumeChange = (newVolume: number) => {
    if (videoRef.current) {
      videoRef.current.volume = newVolume;
      setVolume(newVolume);
      setIsMuted(newVolume === 0);
      onVolumeChange?.(newVolume);
    }
  };

  const handleError = (e: React.SyntheticEvent<HTMLVideoElement>) => {
    setError(true);
    const videoElement = e.currentTarget;
    console.error("Video playback error:", {
      code: videoElement.error?.code,
      message: videoElement.error?.message,
      src: videoElement.currentSrc,
      mimeType: mimeType,
    });
    // Set a user-friendly error message based on the error code
    let message = "Video loading failed";
    if (videoElement.error) {
      switch (videoElement.error.code) {
        case 1:
          message = "Playback aborted";
          break;
        case 2:
          message = "Network error";
          break;
        case 3:
          message = "Decode error";
          break;
        case 4:
          message = `Unsupported format (${mimeType})`;
          if (BROWSER_LIMITED_FORMATS[mimeType]) {
            message += ` - ${BROWSER_LIMITED_FORMATS[mimeType]}`;
          }
          break;
        default:
          message = "Unknown error";
      }
    }
    setErrorMessage(message);
  };

  const toggleMute = () => {
    if (videoRef.current) {
      const newMuted = !isMuted;
      videoRef.current.muted = newMuted;
      setIsMuted(newMuted);
      if (newMuted) {
        handleVolumeChange(0);
      } else {
        handleVolumeChange(1);
      }
    }
  };

  const toggleFullscreen = () => {
    if (!containerRef.current) return;

    if (!document.fullscreenElement) {
      void containerRef.current.requestFullscreen();
      setIsFullscreen(true);
    } else {
      void document.exitFullscreen();
      setIsFullscreen(false);
    }
  };

  const handlePlaybackRateChange = (rate: number) => {
    if (videoRef.current) {
      videoRef.current.playbackRate = rate;
      setPlaybackRate(rate);
    }
  };

  const handleEnded = () => {
    setCurrentTime(0);
    setIsPlaying(false);
    setShowControls(true);
  };

  if (error) {
    return (
      <VideoErrorFallback
        className="h-dvh min-h-[240px] w-dvw rounded-none"
        message={errorMessage}
      />
    );
  }

  return (
    <motion.div
      ref={containerRef}
      className={cn(
        "group relative flex h-dvh w-dvw items-center overflow-hidden bg-black shadow-2xl ring-1 ring-white/10",
      )}
      onClick={toggleControls}
      onMouseEnter={!isMobile ? revealControls : undefined}
      onMouseMove={!isMobile ? revealControls : undefined}
      onMouseLeave={() => {
        if (isMobile || !isPlaying) return;
        clearControlsHideTimeout();
        setShowControls(false);
      }}
    >
      {!isPreviewReady && file.thumbnailFile && (
        <div className="pointer-events-none absolute inset-0 z-10 flex items-center justify-center">
          <div className="rounded-full bg-black/45 p-4 backdrop-blur-md">
            <Loader2 className="h-8 w-8 animate-spin text-white" />
          </div>
        </div>
      )}

      <video
        ref={videoRef}
        autoPlay={isMobile}
        preload="metadata"
        onPlay={() => isMobile && !isPlaying && setIsPlaying(true)}
        onPause={() => setIsPlaying(false)}
        onEnded={handleEnded}
        onError={handleError}
        playsInline
        className={cn("h-full w-full bg-black object-contain", className)}
        onTimeUpdate={handleTimeUpdate}
      >
        <source src={url} type={mimeType} />
        Your browser does not support this video format
      </video>

      {/* Hidden video for preview */}
      {!isMobile && (
        <video ref={previewVideoRef} className="hidden" preload="metadata">
          <source src={url} type={mimeType} />
        </video>
      )}

      {loading && (
        <div className="pointer-events-none absolute inset-0 z-10 flex items-center justify-center">
          <div className="rounded-full bg-black/45 p-4 backdrop-blur-md">
            <Loader2 className="h-8 w-8 animate-spin text-white" />
          </div>
        </div>
      )}

      {/* Format warning */}
      {formatWarning && !error && (
        <div className="absolute left-3 right-3 top-3 z-20 flex items-center justify-center gap-2 rounded-md border border-amber-300/30 bg-amber-500/90 px-3 py-2 text-center text-sm font-medium text-amber-950 shadow-lg backdrop-blur-md">
          <AlertTriangle className="h-4 w-4 flex-shrink-0" />
          <span>{formatWarning}</span>
        </div>
      )}

      <AnimatePresence>
        {showControls && (
          <>
            <MobilePreviewTagOverlay
              file={file}
              onFileChange={onFileChange}
              bottomOffset={isMobile ? "bottom-44" : "bottom-24"}
            />
            {showLoadingSpeed && (
              <motion.div
                initial={{ opacity: 0, y: -10 }}
                animate={{ opacity: 1, y: 0 }}
                exit={{ opacity: 0, y: -10 }}
                className={cn(
                  "pointer-events-none absolute right-3 z-20 rounded-full border border-white/10 bg-black/45 px-3 py-1.5 text-xs font-medium text-white shadow-2xl backdrop-blur-md sm:right-5",
                  formatWarning ? "top-16 sm:top-16" : "top-3 sm:top-5",
                )}
              >
                <span className="text-white/60">Loading</span>{" "}
                {formatLoadingSpeed(loadingSpeed)}
                <span className="ml-2 text-white/50">
                  {Math.floor(loadedPercent)}%
                </span>
              </motion.div>
            )}
            <motion.div
              id="video-controls"
              onClick={(e) => e.stopPropagation()}
              onTouchStart={(e) => e.stopPropagation()}
              initial={{ opacity: 0, y: 20 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, y: 20 }}
              className={cn(
                "absolute bottom-0 left-0 right-0 bg-gradient-to-t from-black/90 via-black/55 to-transparent p-4 pt-14",
                isMobile && "bg-black/40 pt-3",
              )}
            >
              {isMobile ? (
                <MobileControls
                  isPlaying={isPlaying}
                  currentTime={currentTime}
                  duration={duration}
                  onPlayPause={togglePlay}
                  onSeek={handleSeek}
                  onSkipForward={handleSkipForward}
                  onSkipBackward={handleSkipBackward}
                />
              ) : (
                <DesktopControls
                  isPlaying={isPlaying}
                  currentTime={currentTime}
                  duration={duration}
                  volume={volume}
                  isMuted={isMuted}
                  isFullscreen={isFullscreen}
                  playbackRate={playbackRate}
                  onPlayPause={togglePlay}
                  onVolumeChange={handleVolumeChange}
                  onMuteToggle={toggleMute}
                  onFullscreenToggle={toggleFullscreen}
                  onPlaybackRateChange={handlePlaybackRateChange}
                  onSeek={handleSeek}
                  progressBarRef={progressBarRef}
                  onProgressBarHover={handleProgressBarHover}
                  onProgressBarLeave={() => setShowPreview(false)}
                  showPreview={showPreview}
                  previewTime={previewTime}
                  previewPos={previewPos}
                  canvasRef={canvasRef}
                />
              )}
            </motion.div>
          </>
        )}
      </AnimatePresence>
    </motion.div>
  );
};

export default FileVideo;
