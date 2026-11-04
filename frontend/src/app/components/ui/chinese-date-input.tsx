import { useState } from "react";

import { chineseDateInputPresentation } from "./chinese-date-input-presentation";

interface ChineseDateInputProps {
  value: string;
  onChange: (value: string) => void;
  className?: string;
  ariaLabel?: string;
}

export function ChineseDateInput({ value, onChange, className = "", ariaLabel }: ChineseDateInputProps) {
  const [focused, setFocused] = useState(false);
  const presentation = chineseDateInputPresentation(value, focused);

  return (
    <span className="relative block">
      <input
        type={presentation.type}
        aria-label={ariaLabel}
        lang={presentation.lang}
        value={presentation.value}
        onChange={(event) => onChange(event.currentTarget.value)}
        onFocus={() => setFocused(true)}
        onBlur={() => setFocused(false)}
        className={className}
      />
      {presentation.showPlaceholder && (
        <span aria-hidden="true"
          className="pointer-events-none absolute bottom-px left-px right-9 top-1 flex items-center rounded-l bg-white px-2 text-sm text-slate-500">
          {presentation.emptyPlaceholder}
        </span>
      )}
    </span>
  );
}
