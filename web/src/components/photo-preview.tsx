"use client";
import Image from "next/image";
import { type TelegramFile } from "@/lib/types";
import { getApiUrl } from "@/lib/api";
import { useEffect, useState } from "react";
import { ImageOff } from "lucide-react";
import { cn } from "@/lib/utils";

const ImageErrorFallback = ({
  className = "",
  message = "Image loading failed!",
}) => (
  <div
    className={`flex flex-col items-center justify-center rounded bg-gray-100 p-4 ${className}`}
  >
    <ImageOff className="mb-2 h-8 w-8 text-gray-400" />
    <p className="text-sm text-gray-500">{message}</p>
  </div>
);

interface PhotoPreviewProps {
  className?: string;
  file: TelegramFile;
}

export default function PhotoPreview({ className, file }: PhotoPreviewProps) {
  const [viewportHeight, setViewportHeight] = useState(0);
  const [error, setError] = useState(false);
  const src =
    !file.localPath || file.type === "video"
      ? `data:image/jpeg;base64,${file.thumbnail}`
      : `${getApiUrl()}/file/${file.telegramId}/${file.uniqueId}`;

  useEffect(() => {
    setViewportHeight(window.innerHeight);

    const handleResize = () => {
      setViewportHeight(window.innerHeight);
    };

    window.addEventListener("resize", handleResize);
    return () => window.removeEventListener("resize", handleResize);
  }, []);

  const handleError = () => {
    setError(true);
  };

  if (error) {
    return <ImageErrorFallback className="h-full min-h-[200px] w-full" />;
  }

  if (file?.extra?.width && file?.extra?.height && file?.localPath) {
    const aspectRatio = file.extra.width / file.extra.height;
    const maxHeight = viewportHeight;
    const calculatedHeight = Math.min(file.extra.height, maxHeight);
    const calculatedWidth = calculatedHeight * aspectRatio;

    return (
      <div
        className={cn("relative", className)}
        style={{ maxHeight: `${maxHeight}px` }}
      >
        <Image
          src={src}
          placeholder="blur"
          unoptimized={true}
          blurDataURL={`data:image/jpeg;base64,${file.thumbnail}`}
          alt={file.fileName ?? "Photo"}
          width={calculatedWidth}
          height={calculatedHeight}
          className={cn("h-auto max-h-screen w-auto object-contain")}
          onError={handleError}
        />
      </div>
    );
  }

  return (
    <div className={cn("relative h-60 w-60", className)}>
      <Image
        src={src}
        placeholder="blur"
        unoptimized={true}
        blurDataURL={`data:image/jpeg;base64,${file.thumbnail}`}
        alt={file.fileName ?? "Photo"}
        fill={true}
        className="object-contain"
        onError={handleError}
      />
    </div>
  );
}
