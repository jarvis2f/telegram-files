import React, { type KeyboardEvent, useRef, useState } from "react";
import { Plus, X } from "lucide-react";
import { AnimatePresence, motion } from "framer-motion";
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { useTheme } from "next-themes";

interface TagsInputProps {
  value: string[];
  onChange: (tags: string[]) => void;
  placeholder?: string;
  maxTags?: number;
  className?: string;
}

export const TagsInput: React.FC<TagsInputProps> = ({
  value,
  onChange,
  placeholder = "Enter the tag and press Enter...",
  maxTags = Infinity,
  className = "",
}) => {
  const [inputValue, setInputValue] = useState<string>("");
  const [isInputFocused, setIsInputFocused] = useState<boolean>(false);
  const inputRef = useRef<HTMLInputElement>(null);
  const { theme } = useTheme();

  const handleAddTag = (tag: string) => {
    const trimmedTag = tag.trim();

    if (!trimmedTag) {
      return;
    }

    const isDuplicate = value.some(
      (t) => t.toLowerCase() === trimmedTag.toLowerCase(),
    );

    const isMaxExceeded = value.length >= maxTags;

    if (!isDuplicate && !isMaxExceeded) {
      onChange([...value, trimmedTag]);
    }

    setInputValue("");
  };

  const handleRemoveTag = (indexToRemove: number) => {
    onChange(value.filter((_, index) => index !== indexToRemove));
  };

  const handleKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === "Enter" && inputValue.trim()) {
      e.preventDefault();
      handleAddTag(inputValue);
    } else if (e.key === "Backspace" && !inputValue && value.length > 0) {
      handleRemoveTag(value.length - 1);
    } else if (e.key === "," && inputValue.trim()) {
      e.preventDefault();
      handleAddTag(inputValue.replace(",", ""));
    }
  };

  const focusInput = () => {
    inputRef.current?.focus();
  };

  return (
    <motion.div
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      className={`flex w-full flex-col space-y-2 ${className}`}
    >
      <div
        onClick={focusInput}
        className={`flex min-h-12 flex-wrap gap-2 rounded-md border p-2 transition-all ${
          isInputFocused
            ? "ring-2 ring-primary ring-offset-2 ring-offset-background"
            : ""
        } ${theme === "dark" ? "border-gray-700 bg-gray-900" : "border-gray-200 bg-white"}`}
      >
        <AnimatePresence mode="popLayout">
          {value.map((tag, index) => (
            <motion.div
              key={`tag-${index}-${tag}`}
              initial={{ opacity: 0, scale: 0.8 }}
              animate={{ opacity: 1, scale: 1 }}
              exit={{ opacity: 0, scale: 0.8, transition: { duration: 0.15 } }}
              layout
              className="flex items-center"
            >
              <Badge
                variant="secondary"
                className="flex items-center gap-1 px-3 py-1.5 text-sm font-medium"
              >
                {tag}
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  className="h-5 w-5 rounded-full p-0 hover:bg-destructive/20 hover:text-destructive"
                  onClick={(e) => {
                    e.stopPropagation();
                    handleRemoveTag(index);
                  }}
                >
                  <X className="h-3 w-3" />
                  <span className="sr-only">Delete {tag}</span>
                </Button>
              </Badge>
            </motion.div>
          ))}
        </AnimatePresence>

        <div className="flex min-w-24 flex-grow items-center">
          <Input
            ref={inputRef}
            type="text"
            value={inputValue}
            onChange={(e) => setInputValue(e.target.value)}
            onKeyDown={handleKeyDown}
            onFocus={() => setIsInputFocused(true)}
            onBlur={() => setIsInputFocused(false)}
            placeholder={value.length === 0 ? placeholder : ""}
            className="border-0 bg-transparent p-0 text-sm shadow-none focus-visible:ring-0 focus-visible:ring-offset-0"
          />
        </div>

        {inputValue.trim() && (
          <motion.div
            initial={{ opacity: 0, scale: 0.8 }}
            animate={{ opacity: 1, scale: 1 }}
            className="flex items-center self-center"
          >
            <Button
              type="button"
              variant="ghost"
              size="sm"
              className="h-8 w-8 rounded-full p-0 text-green-500 hover:bg-green-500/20"
              onClick={() => handleAddTag(inputValue)}
            >
              <Plus className="h-4 w-4" />
              <span className="sr-only">Add tag</span>
            </Button>
          </motion.div>
        )}
      </div>

      {maxTags !== Infinity && (
        <div className="text-right text-xs text-muted-foreground">
          {value.length} / {maxTags}
        </div>
      )}
    </motion.div>
  );
};
