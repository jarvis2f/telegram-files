"use client";

import * as React from "react";
import { useEffect, useMemo, useRef, useState } from "react";
import { motion } from "framer-motion";
import { X } from "lucide-react";
import { isEqual } from "lodash";

type TagsSelectorProps = {
  value: string[];
  onChange?: (tags: string[]) => void;
  tags: string[];
};

export function TagsSelector({
  value = [],
  onChange,
  tags,
}: TagsSelectorProps) {
  const [selectedTags, setSelectedTags] = useState<string[]>(value);
  const selectedsContainerRef = useRef<HTMLDivElement>(null);

  const removeSelectedTag = (tag: string) => {
    setSelectedTags((prev) => prev.filter((t) => tag !== t));
  };

  const addSelectedTag = (tag: string) => {
    setSelectedTags((prev) => [...prev, tag]);
  };

  const optionalTags = useMemo(() => {
    return tags.filter(
      (tag) => !selectedTags.some((selected) => selected === tag),
    );
  }, [selectedTags, tags]);

  useEffect(() => {
    if (selectedsContainerRef.current) {
      selectedsContainerRef.current.scrollTo({
        left: selectedsContainerRef.current.scrollWidth,
        behavior: "smooth",
      });
    }
  }, [selectedTags]);

  useEffect(() => {
    if (onChange && !isEqual(value, selectedTags)) {
      onChange(selectedTags);
    }
  }, [selectedTags, onChange, value]);

  return (
    <div className="flex w-full max-w-lg flex-col">
      <span className="mb-2 border-b py-1 text-xs text-gray-700 dark:text-gray-200">
        Selected Tags
      </span>
      <motion.div
        className="no-scrollbar mb-3 flex max-h-32 w-full flex-wrap items-center justify-start gap-3 overflow-auto p-1 md:gap-1.5"
        ref={selectedsContainerRef}
        layout
      >
        {selectedTags.length === 0 && (
          <motion.div
            className="flex w-full items-center justify-center rounded-md bg-gray-100 p-2 text-sm text-gray-500 dark:bg-gray-700"
            layoutId="empty-tag"
          >
            No tags selected
          </motion.div>
        )}
        {selectedTags.map((tag) => (
          <motion.div
            key={tag}
            className="flex shrink-0 items-center gap-1 rounded-md bg-white p-1 shadow dark:bg-gray-800"
            layoutId={`tag-${tag}`}
          >
            <motion.span
              layoutId={`tag-${tag}-label`}
              className="text-xs text-gray-700 dark:text-gray-200"
            >
              {tag}
            </motion.span>
            <button
              onClick={() => removeSelectedTag(tag)}
              className="rounded-full p-1"
            >
              <X className="size-3 text-gray-500" />
            </button>
          </motion.div>
        ))}
      </motion.div>
      <span className="mb-2 border-b py-1 text-xs text-gray-700 dark:text-gray-200">
        Optional Tags
      </span>
      {optionalTags.length > 0 && (
        <motion.div className="w-full p-1" layout>
          <motion.div className="flex max-h-32 flex-wrap gap-3 overflow-auto md:gap-1.5">
            {optionalTags.map((tag) => (
              <motion.button
                key={tag}
                layoutId={`tag-${tag}`}
                className="flex shrink-0 items-center gap-1 rounded-lg bg-gray-100 px-2 py-1 dark:bg-gray-700"
                onClick={() => addSelectedTag(tag)}
              >
                <motion.span
                  layoutId={`tag-${tag}-label`}
                  className="text-xs text-gray-700 dark:text-gray-200"
                >
                  {tag}
                </motion.span>
              </motion.button>
            ))}
          </motion.div>
        </motion.div>
      )}
    </div>
  );
}
