import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { closestCenter, DndContext, type DragEndEvent } from "@dnd-kit/core";
import {
  arrayMove,
  SortableContext,
  useSortable,
  verticalListSortingStrategy,
} from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";
import { useState } from "react";
import { Button } from "./ui/button";
import { ChevronDown, Columns, Menu } from "lucide-react";
import { cn } from "@/lib/utils";

export type Column = {
  id: string;
  label: string;
  isVisible: boolean;
  className?: string;
  tooltip?: string;
};

type SortableItemProps = {
  id: string;
  label: string;
  onToggleVisibility: (id: string) => void;
  isVisible: boolean;
};

function SortableItem({
  id,
  label,
  onToggleVisibility,
  isVisible,
}: SortableItemProps) {
  const { attributes, isDragging, listeners, setNodeRef, transform } =
    useSortable({ id });

  return (
    <DropdownMenuCheckboxItem
      checked={isVisible}
      onCheckedChange={() => onToggleVisibility(id)}
      ref={setNodeRef}
      className={cn(
        isDragging ? "opacity-80" : "opacity-100",
        "group whitespace-nowrap",
      )}
      style={{
        transform: transform ? CSS.Translate.toString(transform) : "none",
        transition: "width transform 0.2s ease-in-out",
        zIndex: isDragging ? 1 : undefined,
      }}
    >
      <div className="mr-1">
        <span className="capitalize">{label}</span>
      </div>
      <Button
        {...attributes}
        {...listeners}
        variant="ghost"
        size="sm"
        title="Drag and drop to reorder columns"
        className="invisible ml-auto group-hover:visible"
      >
        <Menu className="h-3 w-3" />
      </Button>
    </DropdownMenuCheckboxItem>
  );
}

type TableColumnFilterProps = {
  columns: Column[];
  onColumnConfigChange: (config: Column[]) => void;
};

export default function TableColumnFilter({
  columns,
  onColumnConfigChange,
}: TableColumnFilterProps) {
  const [isOpen, setIsOpen] = useState(false);

  const handleToggleVisibility = (id: string) => {
    onColumnConfigChange(
      columns.map((col) =>
        col.id === id ? { ...col, isVisible: !col.isVisible } : col,
      ),
    );
  };

  const handleDragEnd = (event: DragEndEvent) => {
    const { active, over } = event;
    if (!over || active.id === over.id) {
      return;
    }

    const oldIndex = columns.findIndex((col) => col.id === active.id);
    const newIndex = columns.findIndex((col) => col.id === over.id);
    onColumnConfigChange(arrayMove(columns, oldIndex, newIndex));
  };

  const visibleColumnCount = columns.filter(
    (column) => column.isVisible,
  ).length;

  return (
    <DropdownMenu open={isOpen} onOpenChange={setIsOpen}>
      <DropdownMenuTrigger className="select-none" asChild>
        <Button variant="outline" title="Show/hide columns">
          <Columns className="mr-2 h-4 w-4" />
          <span className="text-xs text-muted-foreground">{`(${visibleColumnCount}/${columns.length})`}</span>
          <ChevronDown className="ml-2 h-4 w-4" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent
        align="end"
        onPointerDownOutside={() => setIsOpen(false)}
        className="max-h-96 overflow-y-auto"
      >
        <DndContext
          collisionDetection={closestCenter}
          onDragEnd={handleDragEnd}
        >
          <SortableContext
            items={columns.map((col) => col.id)}
            strategy={verticalListSortingStrategy}
          >
            {columns.map((col) => (
              <SortableItem
                key={col.id}
                id={col.id}
                label={col.label}
                isVisible={col.isVisible}
                onToggleVisibility={handleToggleVisibility}
              />
            ))}
          </SortableContext>
        </DndContext>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
