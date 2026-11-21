import OrbitingCircles from "@/components/ui/orbiting-circles";
import {
  FileImage,
  FileText,
  FolderClosed,
  Headphones,
  ImageIcon,
  SquarePlay,
} from "lucide-react";
import { useI18n } from "@/hooks/use-i18n";

const SolidFileIcon = ({ className }: { className: string }) => (
  <svg
    xmlns="http://www.w3.org/2000/svg"
    viewBox="0 0 50 50"
    className={className}
  >
    <path d="M 30.398438 2 L 7 2 L 7 48 L 43 48 L 43 14.601563 Z M 15 28 L 31 28 L 31 30 L 15 30 Z M 35 36 L 15 36 L 15 34 L 35 34 Z M 35 24 L 15 24 L 15 22 L 35 22 Z M 30 15 L 30 4.398438 L 40.601563 15 Z" />
  </svg>
);

export default function FileNotFount() {
  const { t } = useI18n();
  return (
    <div className="relative flex h-full min-h-[calc(100vh-17rem)] w-full flex-col items-center justify-center overflow-hidden">
      <SolidFileIcon className="h-10 w-10 text-muted-foreground" />
      <div className="absolute bottom-1/3 left-1/2 z-0 -translate-x-1/2 transform-gpu text-center">
        <span className="pointer-events-none whitespace-pre-wrap bg-gradient-to-b from-black to-gray-300 bg-clip-text text-center text-xl font-semibold leading-none text-transparent dark:from-white dark:to-black">
          {t("No files found")}
        </span>
        <p className="pointer-events-none text-center text-xs text-muted-foreground">
          {t("Your search did not match any files.")} <br />
          {t("Try searching for something else.")}
        </p>
      </div>

      {/* Inner Circles */}
      <OrbitingCircles
        className="size-[30px] border-none bg-transparent dark:bg-transparent"
        duration={20}
        delay={20}
        radius={80}
      >
        <div className="rounded bg-gray-100 p-2 dark:bg-gray-800">
          <FolderClosed className="h-4 w-4 stroke-1" />
        </div>
      </OrbitingCircles>
      <OrbitingCircles
        className="size-[30px] border-none bg-transparent dark:bg-transparent"
        duration={20}
        delay={10}
        radius={80}
      >
        <FileImage className="h-6 w-6 stroke-1" />
      </OrbitingCircles>

      {/* Outer Circles (reverse) */}
      <OrbitingCircles
        className="size-[100px] border-none bg-transparent dark:bg-transparent"
        radius={120}
        duration={20}
        reverse
      >
        <div className="rounded bg-gray-100 p-2 dark:bg-gray-800">
          <ImageIcon className="h-4 w-4 stroke-1" />
        </div>
      </OrbitingCircles>
      <OrbitingCircles
        className="size-[120px] border-none bg-transparent dark:bg-transparent"
        radius={160}
        duration={20}
        delay={20}
        reverse
      >
        <SquarePlay className="h-6 w-6 stroke-1 text-muted-foreground" />
      </OrbitingCircles>

      <OrbitingCircles
        className="size-[160px] border-none bg-transparent dark:bg-transparent"
        circleClassName="stroke-black/5 stroke-1 dark:stroke-white/5"
        radius={200}
        duration={20}
        delay={10}
      >
        <Headphones className="h-6 w-6 stroke-1 text-muted-foreground" />
      </OrbitingCircles>

      <OrbitingCircles
        className="size-[160px] border-none bg-transparent dark:bg-transparent"
        circleClassName="stroke-black/5 stroke-1 dark:stroke-white/5"
        radius={200}
        duration={20}
        delay={20}
      >
        <FileText className="h-6 w-6 stroke-1 text-muted-foreground" />
      </OrbitingCircles>

      <OrbitingCircles
        className="size-[200px] border-none bg-transparent dark:bg-transparent"
        circleClassName="stroke-black/5 stroke-1 dark:stroke-white/5"
        radius={240}
      ></OrbitingCircles>
      <OrbitingCircles
        className="size-[280px] border-none bg-transparent dark:bg-transparent"
        circleClassName="stroke-black/5 stroke-1 dark:stroke-white/5"
        radius={280}
      ></OrbitingCircles>
    </div>
  );
}
