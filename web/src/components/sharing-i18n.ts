import type { Translate } from "@/i18n";
import {
  DEFAULT_SHARE_PUBLICATION_POLICY,
  type SharePolicyCategory,
  type SharePolicyRule,
} from "@/lib/share-publication-policy";

export function shareCategoryLabel(
  category: SharePolicyCategory,
  t: Translate,
): string {
  const builtIn = DEFAULT_SHARE_PUBLICATION_POLICY.categories.find(
    (item) => item.id === category.id,
  );
  // 只翻译内置文案，不改写平台管理员自定义的分类名称。
  return builtIn?.label === category.label ? t(category.label) : category.label;
}

export function shareRuleReason(rule: SharePolicyRule, t: Translate): string {
  const builtIn = DEFAULT_SHARE_PUBLICATION_POLICY.shareRules.find(
    (item) => item.id === rule.id,
  );
  return builtIn?.reason === rule.reason ? t(rule.reason) : rule.reason;
}
