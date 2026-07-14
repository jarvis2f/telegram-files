import useSWR from "swr";

import { request } from "@/lib/api";
import {
  DEFAULT_SHARE_PUBLICATION_POLICY,
  type SharePublicationPolicy,
} from "@/lib/share-publication-policy";
import { useShareEnabled } from "@/hooks/use-share-enabled";

export function useSharePublicationPolicy() {
  const shareEnabled = useShareEnabled();
  const { data } = useSWR<SharePublicationPolicy>(
    shareEnabled ? "/share/publication-policy" : null,
    (url) => request<SharePublicationPolicy>(url),
    {
      fallbackData: DEFAULT_SHARE_PUBLICATION_POLICY,
      revalidateOnFocus: false,
    },
  );

  return shareEnabled ? (data ?? DEFAULT_SHARE_PUBLICATION_POLICY) : DEFAULT_SHARE_PUBLICATION_POLICY;
}
