import { type TelegramFile } from "@/lib/types";
import React, { useEffect, useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import { AnimatePresence, motion } from "framer-motion";
import {
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

const VideoErrorFallback = ({
  className = "",
  message = "Video loading failed!",
}) => (
  <div
    className={`flex flex-col items-center justify-center rounded bg-gray-100 p-4 ${className}`}
  >
    <VideoOff className="mb-2 h-8 w-8 text-gray-400" />
    <p className="text-sm text-gray-500">{message}</p>
  </div>
);

const Slider = React.forwardRef<
  React.ElementRef<typeof SliderPrimitive.Root>,
  React.ComponentPropsWithoutRef<typeof SliderPrimitive.Root>
>(({ className, ...props }, ref) => (
  <SliderPrimitive.Root
    ref={ref}
    className={cn(
      "relative flex w-full touch-none select-none items-center",
      className,
    )}
    {...props}
  >
    <SliderPrimitive.Track className="relative h-1.5 w-full grow overflow-hidden rounded-full bg-gray-600/40">
      <SliderPrimitive.Range className="absolute h-full bg-white" />
    </SliderPrimitive.Track>
    <SliderPrimitive.Thumb className="block h-4 w-4 rounded-full border-4 border-white bg-background shadow transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring disabled:pointer-events-none disabled:opacity-50" />
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
  progressBarRef: React.RefObject<HTMLDivElement>;
  onProgressBarHover: (e: React.MouseEvent) => void;
  onProgressBarLeave: () => void;
  showPreview: boolean;
  previewTime: number;
  previewPos: number;
  canvasRef: React.RefObject<HTMLCanvasElement>;
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
          onValueChange={(value) => value[0] && onSeek(value[0])}
        />
        {showPreview && (
          <motion.div
            initial={{ opacity: 0, y: -10 }}
            animate={{ opacity: 1, y: 0 }}
            className="absolute bottom-full mb-4 overflow-hidden bg-black"
            style={{ left: `${previewPos}px`, transform: "translateX(-50%)" }}
          >
            <div className="flex aspect-video w-48 items-center justify-center bg-black">
              <canvas
                ref={canvasRef}
                className="h-full w-full object-contain"
              />
            </div>
            <div className="bg-black/80 px-2 py-1 text-center text-sm text-white">
              {formatTime(previewTime)}
            </div>
          </motion.div>
        )}
      </div>

      <div className="flex items-center gap-2">
        <Button
          variant="ghost"
          size="icon"
          className="text-white hover:bg-white/20"
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
            className="text-white hover:bg-white/20"
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

        <div className="text-sm text-white">
          {formatTime(currentTime)} / {formatTime(duration)}
        </div>

        <div className="ml-auto flex items-center gap-2">
          <Popover>
            <PopoverTrigger asChild>
              <Button
                variant="ghost"
                size="sm"
                className="text-white hover:bg-white/20"
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
            className="text-white hover:bg-white/20"
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
    <div className="space-y-4" onClick={(e) => e.stopPropagation()}>
      <div className="flex items-center justify-between px-4">
        <span className="text-sm text-white">{formatTime(currentTime)}</span>
        <span className="text-sm text-white">{formatTime(duration)}</span>
      </div>

      <Slider
        value={[currentTime]}
        max={duration}
        step={0.1}
        className="w-full cursor-pointer"
        onValueChange={(value) => value[0] && onSeek(value[0])}
      />

      <div className="flex items-center justify-center gap-8">
        <Button
          variant="ghost"
          size="icon"
          className="text-white hover:bg-white/20"
          onClick={onSkipBackward}
        >
          <RotateCcw className="h-8 w-8" />
        </Button>

        <Button
          variant="ghost"
          size="icon"
          className="text-white hover:bg-white/20"
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
          className="text-white hover:bg-white/20"
          onClick={onSkipForward}
        >
          <RotateCw className="h-8 w-8" />
        </Button>
      </div>
    </div>
  );
};

const VideoPreview = ({
  file,
  onTimeUpdate,
  onVolumeChange,
  className,
}: {
  file: TelegramFile;
  onTimeUpdate?: (time: number) => void;
  onVolumeChange?: (volume: number) => void;
  className?: string;
}) => {
  const videoRef = useRef<HTMLVideoElement>(null);
  const previewVideoRef = useRef<HTMLVideoElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const progressBarRef = useRef<HTMLDivElement>(null);
  const isMobile = useIsMobile();
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

  const url = `${getApiUrl()}/file/${file.telegramId}/${file.uniqueId}`;

  useEffect(() => {
    const video = videoRef.current;
    const previewVideo = previewVideoRef.current;
    if (!video || !previewVideo) return;

    const handleLoadedMetadata = () => {
      setDuration(video.duration);
      setIsPreviewReady(true);
    };

    video.addEventListener("loadedmetadata", handleLoadedMetadata);
    previewVideo.addEventListener("loadedmetadata", handleLoadedMetadata);

    return () => {
      video.removeEventListener("loadedmetadata", handleLoadedMetadata);
      previewVideo.removeEventListener("loadedmetadata", handleLoadedMetadata);
    };
  }, []);

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
    if (videoRef.current) {
      if (isPlaying) {
        videoRef.current.pause();
      } else {
        void videoRef.current.play();
      }
      setIsPlaying(!isPlaying);
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
  };

  if (error) {
    return <VideoErrorFallback className="h-full min-h-[200px] w-full" />;
  }

  return (
    <motion.div
      ref={containerRef}
      className={cn(
        "group relative w-full overflow-hidden bg-black",
        isMobile && "flex h-screen w-screen items-center",
      )}
      onClick={isMobile ? () => setShowControls((prev) => !prev) : undefined}
      onMouseEnter={() => setShowControls(true)}
      onMouseLeave={() => setShowControls(false)}
    >
      <video
        ref={videoRef}
        autoPlay={isMobile}
        onPlay={() => isMobile && !isPlaying && setIsPlaying(true)}
        onEnded={handleEnded}
        onError={() => setError(true)}
        src={url}
        playsInline
        className={cn("max-h-[calc(100vh-5rem)] w-full", className)}
        onTimeUpdate={handleTimeUpdate}
      />

      {/* Hidden video for preview */}
      {!isMobile && (
        <video
          ref={previewVideoRef}
          src={url}
          className="hidden"
          preload="auto"
        />
      )}

      <AnimatePresence>
        {showControls && (
          <motion.div
            id="video-controls"
            onTouchStart={(e) => e.stopPropagation()}
            initial={{ opacity: 0, y: 20 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: 20 }}
            className={cn(
              "absolute bottom-0 left-0 right-0 bg-gradient-to-t from-black/80 to-transparent p-4",
              isMobile && "bg-gray-900 bg-opacity-20 pb-16",
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
        )}
      </AnimatePresence>
    </motion.div>
  );
};

export default VideoPreview;
