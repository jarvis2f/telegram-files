import { useCallback, useState } from "react";
import { toast } from "./use-toast";

export function useCopyToClipboard(): [string | null, (value: string) => void] {
  const [state, setState] = useState<string | null>(null);

  const copyToClipboard = useCallback((value: string) => {
    const handleCopy = async () => {
      try {
        if (navigator?.clipboard?.writeText) {
          await navigator.clipboard.writeText(value);
          setState(value);
          toast({
            variant: "success",
            title: "Successfully copied to clipboard",
            description: (
              <p className="line-clamp-1">
                <code className="text-xs">{value}</code>
              </p>
            ),
          });
        } else {
          throw new Error("writeText not supported");
        }
        // eslint-disable-next-line @typescript-eslint/no-unused-vars
      } catch (e) {
        oldSchoolCopy(value);
        setState(value);
        toast({
          variant: "success",
          title: "Successfully copied to clipboard",
          description: (
            <p className="line-clamp-1">
              <code className="text-xs">{value}</code>
            </p>
          ),
        });
      }
    };

    void handleCopy();
  }, []);

  return [state, copyToClipboard];
}

function oldSchoolCopy(text: string): void {
  const tempTextArea = document.createElement("textarea");
  tempTextArea.value = text;
  document.body.appendChild(tempTextArea);
  tempTextArea.select();
  document.execCommand("copy");
  document.body.removeChild(tempTextArea);
}
