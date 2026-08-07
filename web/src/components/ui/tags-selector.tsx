"use client";

import * as React from "react";
import { useEffect, useState } from "react";
import { Check, Plus } from "lucide-react";
import { cn } from "@/lib/utils";

type TagsSelectorProps = {
  value: string[];
  onChangeAction?: (tags: string[]) => void;
  tags: string[];
  className?: string;
};

export function TagsSelector({
  value = [],
  onChangeAction,
  tags = [],
  className,
}: TagsSelectorProps) {
  const [selectedTags, setSelectedTags] = useState<string[]>(value);

  useEffect(() => {
    setSelectedTags(value);
  }, [value]);

  const toggleTag = (tag: string) => {
    let nextTags: string[];
    if (selectedTags.includes(tag)) {
      nextTags = selectedTags.filter((t) => t !== tag);
    } else {
      nextTags = [...selectedTags, tag];
    }
    setSelectedTags(nextTags);
    onChangeAction?.(nextTags);
  };

  if (tags.length === 0) {
    return (
      <div className="py-2 text-center text-xs text-muted-foreground">
        暂无可选标签
      </div>
    );
  }

  return (
    <div className={cn("flex flex-wrap gap-1.5 py-1", className)}>
      {tags.map((tag) => {
        const isSelected = selectedTags.includes(tag);
        return (
          <button
            key={tag}
            type="button"
            onClick={() => toggleTag(tag)}
            className={cn(
              "flex items-center gap-1 rounded-lg px-2.5 py-1 text-xs font-medium transition active:scale-95",
              isSelected
                ? "bg-primary text-primary-foreground font-semibold shadow"
                : "bg-muted text-muted-foreground hover:bg-accent hover:text-accent-foreground",
            )}
          >
            {isSelected ? (
              <Check className="h-3 w-3" />
            ) : (
              <Plus className="h-3 w-3 opacity-60" />
            )}
            <span>{tag}</span>
          </button>
        );
      })}
    </div>
  );
}
