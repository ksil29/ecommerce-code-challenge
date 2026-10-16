import { useState } from "react";

interface QuantityInputProps {
  value: number;
  max: number;
  onChange: (value: number) => void;
  id?: string;
  label?: string;
  className?: string;
}

export function QuantityInput({ value, max, onChange, id, label, className = "form-control quantity-input" }: QuantityInputProps) {
  const [draft, setDraft] = useState(String(value));
  const [shown, setShown] = useState(value);
  if (value !== shown) {
    setShown(value);
    setDraft(String(value));
  }

  return (
    <input
      id={id}
      type="number"
      className={className}
      min={1}
      max={max}
      value={draft}
      aria-label={label}
      onChange={(event) => {
        setDraft(event.target.value);
        const next = event.target.valueAsNumber;
        if (Number.isFinite(next) && next >= 1) onChange(Math.min(Math.floor(next), max));
      }}
      onBlur={() => setDraft(String(value))}
    />
  );
}
