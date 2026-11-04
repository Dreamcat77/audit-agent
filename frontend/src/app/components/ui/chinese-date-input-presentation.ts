export interface ChineseDateInputPresentation {
  type: "date";
  lang: "zh-CN";
  value: string;
  emptyPlaceholder: "年 / 月 / 日" | null;
  showPlaceholder: boolean;
}

export function chineseDateInputPresentation(
  value: string,
  _focused = false,
): ChineseDateInputPresentation {
  return {
    type: "date",
    lang: "zh-CN",
    value,
    emptyPlaceholder: value ? null : "年 / 月 / 日",
    showPlaceholder: !value,
  };
}
