"use client";

import { Toggle } from "@/components/ui/toggle";
import { Moon, Sun } from "lucide-react";
import { useTheme } from "next-themes";

export default function ThemeToggleButton() {
  const { theme, setTheme } = useTheme();

  return (
    <Toggle
      variant="default"
      size="sm"
      className="group size-9 data-[state=on]:bg-transparent data-[state=on]:hover:bg-muted"
      pressed={theme === "dark"}
      onPressedChange={() =>
        setTheme((prev) => (prev === "dark" ? "light" : "dark"))
      }
      aria-label={`Switch to ${theme === "dark" ? "light" : "dark"} mode`}
    >
      <Moon
        size={16}
        strokeWidth={2}
        className="shrink-0 scale-0 opacity-0 transition-all group-data-[state=on]:scale-100 group-data-[state=on]:opacity-100"
        aria-hidden="true"
      />
      <Sun
        size={16}
        strokeWidth={2}
        className="absolute shrink-0 scale-100 opacity-100 transition-all group-data-[state=on]:scale-0 group-data-[state=on]:opacity-0"
        aria-hidden="true"
      />
    </Toggle>
  );
}
