"use client";

import { useEffect, useState } from "react";

/** Also clears when React suspends the route's effects while preserving its component state. */
export function useEphemeralState<T>(initial: T) {
  const [value, setValue] = useState(initial);
  useEffect(() => () => { setValue(initial); }, [initial]);
  return [value, setValue] as const;
}
